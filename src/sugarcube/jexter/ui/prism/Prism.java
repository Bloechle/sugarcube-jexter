package sugarcube.jexter.ui.prism;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.LlmClient;
import sugarcube.jexter.ui.WebApp;
import sugarcube.jexter.write.Conversion;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.tool.HttpLlmClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Prism — the jexter document workbench as a local web app: the PRISM reader chassis (the
 * original EPUB tree served client-side by a Service Worker, untouched) fused with the
 * engine API of the former Jexter Lab, on the SAME stateless contract as the sugarcloud
 * convert service. The front-end holds the source and the book; the server just turns
 * bytes into bytes through {@link Conversion}.
 *
 * <pre>
 *   POST /api/convert?to=&lt;ocd|svg|pdf|epub|epub-reflow|html|md|doctags&gt;[&amp;&lt;opt&gt;=&lt;val&gt;…]
 *        body: a PDF (%PDF) → import + export · OR an OCD-EPUB (PK zip) → re-export
 *        or:   ?url=&lt;http(s)&gt; to fetch the source server-side first
 *        → the artifact (media type + filename from jexter)
 *   GET  /api/options      → the ConvertOptions registry (+ bound-model status)
 *   GET  /api/targets      → the valid `to` ids
 *   GET  /api/health       → "ok"
 *   POST /api/ai/config        bind / unbind the LLM connection from the UI                  (desktop only)
 *   GET  /api/log              JxLog as Server-Sent Events (the F2 console)                  (desktop only)
 * </pre>
 *
 * Structure refinement is not a route of its own: it rides {@code /api/convert?to=ocd&refineStructure=true}
 * (the bound LLM runs in the engine), identical to the cloud. All conversion semantics live in
 * {@link Conversion}; adding a target happens once, there.
 */
public final class Prism extends WebApp {

    private volatile Thread refineThread;   // the worker running an in-flight refine convert, so /api/ai/stop can interrupt it

    public static void main(String[] args) throws Exception { new Prism().launch(args); }

    @Override protected String title()       { return "Prism"; }
    @Override protected int    defaultPort()  { return 7345; }
    @Override protected String[] webCandidates() {
        return new String[]{"web", "sugarcube/jexter/ui/prism/web",
                "src/sugarcube/jexter/ui/prism/web", "build/src/sugarcube/jexter/ui/prism/web"};
    }
    @Override protected void routes(HttpServer server) {
        installLogBridge();                                   // mirror JxLog → /api/log (in-page console)
        bindModel();                                          // bind an LLM iff a key is in env or saved config
        server.createContext("/api/convert",  this::convert); // the one engine route: bytes (+ to/opts) → artifact bytes
        server.createContext("/api/options",  this::options); // the ConvertOptions registry (+ bound-model status)
        server.createContext("/api/targets",  this::targets); // valid `to` ids
        server.createContext("/api/health",   this::health);  // liveness probe (parity with the cloud)
        server.createContext("/api/ai/config",this::aiConfig);// AI: bind the LLM connection from the UI
        server.createContext("/api/ai/stop",  this::aiStop);  // AI: interrupt an in-flight refine (page-windowed → stops at the next page)
        server.createContext("/api/log",      this::log);     // JxLog SSE (F2)
    }

    // ── /api/convert — the single conversion entry (PDF or OCD-EPUB → any target) ──
    private void convert(HttpExchange x) throws IOException {
        try {
            Map<String, String> opts = new LinkedHashMap<>(query(x));
            String to   = opts.remove("to");  if (to == null || to.isBlank()) to = "ocd";
            String url  = opts.remove("url");
            String path = opts.remove("path");
            opts.remove("name");

            byte[] body;
            if (path != null && !path.isBlank()) {            // desktop only: re-open a recent file straight off disk
                body = Files.readAllBytes(Path.of(path));
            } else if (url != null && !url.isBlank()) {
                URI uri = URI.create(url.trim());
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
                if (!scheme.equals("http") && !scheme.equals("https")) { error(x, new IllegalArgumentException("only http/https URLs are supported")); return; }
                body = download(uri);
            } else {
                body = x.getRequestBody().readAllBytes();
            }
            if (body.length == 0) { error(x, new IllegalArgumentException("empty body — POST a PDF or an OCD-EPUB, or pass ?url= / ?path=")); return; }

            boolean refining = "true".equalsIgnoreCase(opts.get("refineStructure"));
            if (refining) refineThread = Thread.currentThread();   // expose this worker to /api/ai/stop
            try {
                Conversion.Output out = isOcd(body)
                        ? Conversion.convert(readOcd(body), to, opts)   // OCD-EPUB → re-export
                        : Conversion.convert(body, to, opts);           // PDF  → import + export
                send(x, out);
            } finally {
                if (refining) { refineThread = null; Thread.interrupted(); }   // clear any leftover interrupt before the pool reuses the thread
            }
        } catch (Exception e) { error(x, e); }
    }

    // ── /api/ai/stop — interrupt an in-flight refine (it stops at the next page, keeping partial) ──
    private void aiStop(HttpExchange x) throws IOException {
        Thread t = refineThread;
        boolean stopping = t != null;
        if (stopping) { t.interrupt(); JxLog.info(this, "AI refine — stop requested"); }
        json(x, new JxStringer().obj().bool("ok", true).bool("stopping", stopping).end().toString());
    }

    // ── /api/options — the introspectable ConvertOptions registry ─────────────
    // Delegates to the shared serializer so desktop and cloud emit identical shape; also
    // reports the bound LLM so the AI panel can show connection state without a second call.
    private void options(HttpExchange x) throws IOException {
        try {
            boolean bound = LlmClient.isBound();
            json(x, ConvertOptions.optionsJson(bound,
                    bound ? LlmClient.bound().model() : "",
                    LlmClient.bound() instanceof HttpLlmClient h ? h.providerLabel() : "",
                    LlmClient.bound() instanceof HttpLlmClient h ? h.effort() : ""));
        } catch (Exception e) { error(x, e); }
    }

    // ── /api/targets — the valid `to` ids (single source: Conversion) ─────────
    private void targets(HttpExchange x) throws IOException {
        try {
            String arr = Conversion.targets().stream().map(JxStringer::quoted).collect(Collectors.joining(","));
            json(x, "{\"targets\":[" + arr + "]}");
        } catch (Exception e) { error(x, e); }
    }

    // ── /api/health — liveness, parity with the cloud probe ───────────────────
    private void health(HttpExchange x) throws IOException {
        byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    // ── /api/ai/config — bind / unbind the LLM connection at run time ─────────
    // JSON body { provider, endpoint, key, model, effort, keyless, clear }. The key travels in the
    // body (not the URL), is held inside the bound client, and is persisted owner-only for restarts.
    private void aiConfig(HttpExchange x) throws IOException {
        try {
            byte[] raw = x.getRequestBody().readAllBytes();
            Object root = raw.length == 0 ? null : JxJson.parse(new String(raw, StandardCharsets.UTF_8));
            Map<String, Object> m = JxJson.asObj(root);
            if (m == null) m = Map.of();
            if (Boolean.TRUE.equals(m.get("clear"))) {
                LlmClient.bind(null);
                deleteAiConfig();
                JxLog.info(this, "LLM unbound via AI panel");
                json(x, new JxStringer().obj().bool("ok", true).bool("bound", false).end().toString());
                return;
            }
            String key = strv(m, "key");
            boolean keyless = Boolean.TRUE.equals(m.get("keyless"));
            if (key.isBlank() && !keyless) {
                json(x, new JxStringer().obj().bool("ok", false).bool("bound", LlmClient.isBound())
                        .str("reason", "API key required").end().toString());
                return;
            }
            HttpLlmClient c = new HttpLlmClient(strv(m, "provider"), strv(m, "endpoint"), key, strv(m, "model"), strv(m, "effort"));
            LlmClient.bind(c);
            saveAiConfig(strv(m, "provider"), strv(m, "endpoint"), strv(m, "model"), strv(m, "effort"), key, keyless);
            JxLog.info(this, "LLM bound via AI panel \u2014 provider=" + c.providerLabel() + " model=" + c.model()
                    + (c.effort().isEmpty() ? "" : " effort=" + c.effort()));
            json(x, new JxStringer().obj().bool("ok", true).bool("bound", true)
                    .str("model", c.model()).str("provider", c.providerLabel()).str("effort", c.effort()).end().toString());
        } catch (Exception e) { error(x, e); }
    }

    // ── input handling: PDF vs OCD-EPUB, URL fetch, response ──────────────────
    /** An OCD-EPUB is a ZIP ("PK"); a PDF starts with "%PDF". */
    private static boolean isOcd(byte[] b) { return b.length > 1 && b[0] == 'P' && b[1] == 'K'; }

    private static OCDDocument readOcd(byte[] body) throws Exception {
        Path tmp = Files.createTempFile("prism-", ".ocd.epub");
        try { Files.write(tmp, body); return OCDReader.read(tmp.toFile()); }
        finally { Files.deleteIfExists(tmp); }
    }

    private static void send(HttpExchange x, Conversion.Output o) throws IOException {
        x.getResponseHeaders().set("Content-Type", o.mediaType());
        x.getResponseHeaders().set("Content-Disposition", (o.inline() ? "inline" : "attachment") + "; filename=\"" + o.filename() + "\"");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] d = o.bytes();
        x.sendResponseHeaders(200, d.length);
        try (OutputStream os = x.getResponseBody()) { os.write(d); }
    }

    private static final long MAX_FETCH = 128L * 1024 * 1024;   // 128 MB

    private static byte[] download(URI uri) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(uri)
                .header("User-Agent", "Prism")
                .timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            try (var b = resp.body()) { b.readAllBytes(); }
            throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
        }
        try (var in = resp.body(); var bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1 << 16]; long total = 0; int r;
            while ((r = in.read(buf)) >= 0) {
                total += r;
                if (total > MAX_FETCH) throw new IOException("download exceeds " + (MAX_FETCH >> 20) + " MB cap");
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    // ── AI: bind a language model (env key wins, else the saved panel config) ──
    private void bindModel() {
        if (LlmClient.isBound()) return;
        String key = System.getenv("JEXTER_LLM_KEY");
        if (key == null || key.isBlank()) key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) {
            try { LlmClient.bind(new HttpLlmClient()); JxLog.info(this, "LLM bound from environment \u2014 structure refinement available"); }
            catch (Exception e) { JxLog.warn(this, "LLM bind failed", e); }
            return;
        }
        AiCfg saved = loadAiConfig();
        if (saved != null && (saved.keyless() || !saved.key().isBlank())) {
            try {
                HttpLlmClient c = new HttpLlmClient(saved.provider(), saved.endpoint(), saved.key(), saved.model(), saved.effort());
                LlmClient.bind(c);
                JxLog.info(this, "LLM restored from " + aiConfigPath() + " \u2014 provider=" + c.providerLabel()
                        + " model=" + c.model() + (c.effort().isEmpty() ? "" : " effort=" + c.effort()));
            } catch (Exception e) { JxLog.warn(this, "saved AI config failed to bind", e); }
            return;
        }
        JxLog.info(this, "no LLM key in environment or saved config \u2014 AI structure refinement disabled");
    }

    private static String strv(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : v.toString(); }

    // Persisted at ~/.jexter/ai.json (provider/endpoint/model/effort + key), owner-only on POSIX.
    private record AiCfg(String provider, String endpoint, String model, String effort, String key, boolean keyless) {}

    private static Path aiConfigPath() { return Path.of(System.getProperty("user.home", "."), ".jexter", "ai.json"); }

    private void saveAiConfig(String provider, String endpoint, String model, String effort, String key, boolean keyless) {
        try {
            Path p = aiConfigPath();
            Files.createDirectories(p.getParent());
            String json = new JxStringer().obj()
                    .str("provider", provider).str("endpoint", endpoint).str("model", model)
                    .str("effort", effort).bool("keyless", keyless).str("key", key)
                    .end().toString();
            Files.writeString(p, json, StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")); } catch (Exception ignore) {}
        } catch (Exception e) { JxLog.warn(this, "could not save AI config", e); }
    }

    private AiCfg loadAiConfig() {
        try {
            Path p = aiConfigPath();
            if (!Files.isReadable(p)) return null;
            Map<String, Object> m = JxJson.asObj(JxJson.parse(Files.readString(p, StandardCharsets.UTF_8)));
            if (m == null) return null;
            return new AiCfg(strv(m, "provider"), strv(m, "endpoint"), strv(m, "model"),
                    strv(m, "effort"), strv(m, "key"), Boolean.TRUE.equals(m.get("keyless")));
        } catch (Exception e) { JxLog.warn(this, "could not read saved AI config", e); return null; }
    }

    private void deleteAiConfig() { try { Files.deleteIfExists(aiConfigPath()); } catch (Exception ignore) {} }

    // ── Debug log bridge — mirror JxLog (java.util.logging) into the F2 console ──
    private static final java.util.List<java.io.OutputStream> logSinks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final java.util.Deque<String> logRing = new java.util.ArrayDeque<>();
    private static final int LOG_RING_MAX = 300;
    private static volatile boolean logBridgeReady = false;

    private static synchronized void installLogBridge() {
        if (logBridgeReady) return;
        logBridgeReady = true;
        java.util.logging.Logger.getLogger("sugarcube").setLevel(java.util.logging.Level.ALL);
        java.util.logging.Handler h = new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { if (isLoggable(r)) pushLog(formatRecord(r)); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        h.setLevel(java.util.logging.Level.ALL);
        java.util.logging.Logger.getLogger("").addHandler(h);
    }

    private static String formatRecord(java.util.logging.LogRecord r) {
        String lvl = switch (r.getLevel().getName()) {
            case "SEVERE" -> "error"; case "WARNING" -> "warn";
            case "INFO", "CONFIG" -> "info"; default -> "debug";
        };
        String name = r.getLoggerName() == null ? "" : r.getLoggerName();
        String shortName = name.substring(name.lastIndexOf('.') + 1);
        String msg = String.valueOf(r.getMessage());
        if (r.getThrown() != null) msg += " \u2014 " + r.getThrown();
        return new JxStringer().obj().str("level", lvl).str("src", shortName).str("msg", msg).end().toString();
    }

    private static void pushLog(String json) {
        synchronized (logRing) {
            logRing.addLast(json);
            while (logRing.size() > LOG_RING_MAX) logRing.removeFirst();
        }
        byte[] data = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        for (java.io.OutputStream os : logSinks) {
            try { synchronized (os) { os.write(data); os.flush(); } }
            catch (IOException dead) { logSinks.remove(os); }
        }
    }

    private void log(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, 0);
        java.io.OutputStream os = x.getResponseBody();
        synchronized (logRing) {
            for (String line : logRing) os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
        os.flush();
        logSinks.add(os);
        try {
            while (true) {
                synchronized (os) { os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); }
                try { Thread.sleep(15000); } catch (InterruptedException ie) { break; }
            }
        } catch (IOException closed) {
        } finally {
            logSinks.remove(os);
            try { os.close(); } catch (IOException ignored) {}
        }
    }
}
