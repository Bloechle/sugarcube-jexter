package sugarcube.jexter.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sugarcube.jexter.core.JxStringer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebApp — shared base for the local browser-driven apps (Prism, PDFInspector, Jexter).
 *
 * <p>Both are the same kind of app: a zero-dependency {@link HttpServer} serving a
 * static qry-stack front-end plus a tiny JSON/binary API, opened in a chromeless
 * Chromium window whose lifetime drives the process. This base owns everything that
 * is not document-specific — argument parsing and server bootstrap, the static-file
 * handler (with a shared {@code /shared/} root), the CDN proxy + offline cache, the
 * SSE heartbeat and shutdown watchdog, window launching, and the small HTTP/JSON
 * utilities. Subclasses add only their state and their {@code /api/*} handlers.
 *
 * <pre>  java &lt;App&gt; [--port N] [--web DIR] [--serve]</pre>
 * Opens a Chromium app window by default; {@code --serve} (alias {@code --no-open})
 * just prints the URL for dev use.
 */
public abstract class WebApp {

    protected static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js",   "text/javascript; charset=utf-8"),
            Map.entry("mjs",  "text/javascript; charset=utf-8"),
            Map.entry("css",  "text/css; charset=utf-8"),
            Map.entry("svg",  "image/svg+xml"),
            Map.entry("png",  "image/png"),
            Map.entry("gif",  "image/gif"),
            Map.entry("jpg",  "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("ico",  "image/x-icon"),
            Map.entry("json", "application/json"),
            Map.entry("map",  "application/json"),
            Map.entry("doctags", "text/plain; charset=utf-8"),
            Map.entry("md",   "text/markdown; charset=utf-8"),
            Map.entry("pdf",  "application/pdf"),
            Map.entry("woff2","font/woff2"),
            Map.entry("woff", "font/woff"),
            Map.entry("ttf",  "font/ttf"),
            Map.entry("wasm", "application/wasm"));

    // CDN vendoring: everything the front-end loads comes from jsdelivr; we proxy it
    // under /cdn/ and persist a local cache so the app works offline after first use.
    protected static final String CDN_HOST = "https://cdn.jsdelivr.net/";

    protected volatile String baseName = "document";
    protected Path webDir;       // serves the app's own web/ (index.html, entry js) — disk mode
    protected Path sharedDir;    // serves /shared/* (jexter-mark.svg, js/); may be null

    // Classpath fallback: when the web/ assets are not on disk (e.g. a packaged
    // jar / jpackage app-image) they are served from the classpath instead.
    protected boolean fromClasspath;
    protected String webResRoot;     // e.g. "sugarcube/jexter/ui/lab/web"
    protected String sharedResRoot;  // e.g. "sugarcube/jexter/ui/shared/web"; may be null

    // window-lifecycle bookkeeping (see /api/alive + armShutdownWatchdog)
    private final AtomicInteger clients = new AtomicInteger();
    private volatile boolean everConnected = false;
    private volatile long idleSince = 0;   // ms when the client count last hit zero

    // ── Subclass contract ─────────────────────────────────────────────────────
    /** Human title, also used for the window profile + log line. */
    protected abstract String title();
    /** Default app-window size as Chromium's {@code W,H}; subclasses may override. */
    protected String windowSize() { return "1180,820"; }
    /** Preferred port (falls back to an ephemeral one if taken). */
    protected abstract int defaultPort();
    /** Candidate relative dirs to locate this app's {@code web/} (first existing wins). */
    protected abstract String[] webCandidates();
    /** Register the app's {@code /api/*} contexts (handlers live in the subclass). */
    protected abstract void routes(HttpServer server);

    // ── Bootstrap ───────────────────────────────────────────────────────────────
    protected final void launch(String[] args) throws Exception {
        int port = defaultPort();
        boolean open = true;
        Path web = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port"               -> port = Integer.parseInt(args[++i]);
                case "--web"                -> web = Path.of(args[++i]);
                case "--no-open", "--serve" -> open = false;
                default                     -> { }
            }
        }
        webDir = web != null ? web : findWebDir(webCandidates());
        if (webDir != null && Files.isDirectory(webDir)) {
            sharedDir = resolveSharedDir(webDir);                 // disk mode (dev / source tree)
        } else {
            webResRoot = findWebResource(webCandidates());        // classpath mode (packaged jar)
            if (webResRoot == null)
                throw new IllegalStateException("web/ assets not found on disk or classpath (use --web DIR); tried " + webDir);
            sharedResRoot = resolveSharedResource(webResRoot);
            webDir = null;
            fromClasspath = true;
        }

        HttpServer server;
        try { server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0); }
        catch (IOException e) {
            // Preferred port taken. If OUR OWN app already runs there, be single-instance:
            // don't fork a shadow server on a random port (the browser tab would keep
            // talking to the old one) — just open a window on the living instance.
            String occupant = probeApp(port);
            if (title().equals(occupant)) {
                String url = "http://127.0.0.1:" + port + "/";
                System.out.println(title() + " is already running  \u2192  " + url);
                if (open) openWindow(url);                 // its /api/alive keeps ITS lifetime
                return;
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            System.out.println("WARNING: port " + port + " is occupied"
                    + (occupant != null ? " by \"" + occupant + "\"" : "") + " \u2014 serving on an ephemeral port instead.");
        }
        port = server.getAddress().getPort();

        routes(server);                                    // app-specific /api/* handlers
        server.createContext("/api/app",   x -> {          // identity probe (single-instance check)
            byte[] b = title().getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream os = x.getResponseBody()) { os.write(b); }
        });
        server.createContext("/api/alive", this::alive);   // SSE heartbeat: the window keeps this open
        server.createContext("/cdn/",       this::cdn);    // CDN proxy + local cache (offline after first use)
        server.createContext("/",          this::statics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        Thread warm = new Thread(this::warmCache, "cdn-warm");   // best-effort prefetch into the local cache
        warm.setDaemon(true);
        warm.start();

        String url = "http://127.0.0.1:" + port + "/";
        String webLoc = fromClasspath ? "classpath:" + webResRoot : String.valueOf(webDir);
        String sharedLoc = fromClasspath ? sharedResRoot : (sharedDir != null ? sharedDir.toString() : null);
        System.out.println(title() + "  \u2192  " + url + "   (web: " + webLoc
                + (sharedLoc != null ? ", shared: " + sharedLoc : "") + ")");
        if (open) { openWindow(url); armShutdownWatchdog(); }
    }

    /** Identity of the app answering on 127.0.0.1:port ({@code /api/app}), or null. */
    private static String probeApp(int port) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(600)).build();
            HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/app"))
                    .timeout(java.time.Duration.ofMillis(900)).GET().build();
            HttpResponse<String> resp = c.send(r, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body().trim() : null;
        } catch (Exception e) { return null; }
    }

    /** {@code .../ui/<app>/web} → {@code .../ui/shared/web} (null if absent). */
    private static Path resolveSharedDir(Path webDir) {
        Path ui = webDir.toAbsolutePath().getParent();             // .../ui/<app>
        if (ui != null) ui = ui.getParent();                       // .../ui
        if (ui == null) return null;
        Path shared = ui.resolve("shared").resolve("web");
        return Files.isDirectory(shared) ? shared : null;
    }

    /** First web candidate that exists as a classpath resource (has index.html); null if none. */
    private String findWebResource(String[] candidates) {
        ClassLoader cl = getClass().getClassLoader();
        for (String c : candidates) {
            String r = c.replace('\\', '/').replaceFirst("^/", "");
            if (cl.getResource(r + "/index.html") != null) return r;
        }
        return null;
    }

    /** {@code .../ui/<app>/web} → {@code .../ui/shared/web} on the classpath (null if absent). */
    private String resolveSharedResource(String webRes) {
        int i = webRes.lastIndexOf('/');                           // strip "/web"
        int j = i > 0 ? webRes.lastIndexOf('/', i - 1) : -1;       // strip "/<app>"
        if (j < 0) return null;
        String shared = webRes.substring(0, j) + "/shared/web";
        ClassLoader cl = getClass().getClassLoader();
        return cl.getResource(shared + "/js/ocd.js") != null || cl.getResource(shared + "/jexter-mark.svg") != null
                ? shared : null;
    }

    // ── Lifecycle: the window drives the server ───────────────────────────────

    /** SSE heartbeat. The browser holds this open (EventSource); when the window
     *  closes, the connection drops and the client count falls back to zero. */
    private void alive(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.getResponseHeaders().set("Cache-Control", "no-cache");
        x.sendResponseHeaders(200, 0);
        clients.incrementAndGet();
        everConnected = true;
        try (OutputStream os = x.getResponseBody()) {
            while (true) {
                os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));   // SSE comment = keep-alive
                os.flush();
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        } catch (IOException ignored) {            // window gone
        } finally {
            if (clients.decrementAndGet() <= 0) idleSince = System.currentTimeMillis();
        }
    }

    /** Exit a few seconds after the window closes (window mode only; never in --serve). */
    private void armShutdownWatchdog() {
        final long GRACE = 2000, STARTUP = 30000, start = System.currentTimeMillis();
        Thread wd = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                if (!everConnected) {
                    if (now - start > STARTUP) { System.out.println("No window connected; exiting."); System.exit(0); }
                } else if (clients.get() == 0 && idleSince > 0 && now - idleSince > GRACE) {
                    System.out.println("Window closed; shutting down."); System.exit(0);
                }
            }
        }, "webapp-watchdog");
        wd.setDaemon(true);
        wd.start();
    }

    // ── static files (app web/ + shared /shared/) ─────────────────────────────
    private void statics(HttpExchange x) throws IOException {
        String p = x.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        byte[] data = readAsset(p);
        if (data == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
        String e = ext(p);
        if (e.equals("html") || e.equals("js") || e.equals("css"))   // point CDN refs at the local proxy
            data = rewriteCdn(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", MIME.getOrDefault(e, "application/octet-stream"));
        // Local app/shared assets change with every dev iteration; never let the browser serve a
        // stale entry (a cached old JS against a new HTML mismatches element IDs and renders nothing).
        x.getResponseHeaders().set("Cache-Control", "no-cache, must-revalidate");
        x.sendResponseHeaders(200, data.length);
        try (OutputStream os = x.getResponseBody()) { os.write(data); }
    }

    /** Read a web asset by request path ("/index.html", "/shared/jexter-mark.svg"); null if absent.
     *  Serves from the classpath in packaged mode, else from the on-disk web/ dirs. */
    private byte[] readAsset(String p) throws IOException {
        boolean shared = p.startsWith("/shared/");
        String rel = shared ? p.substring("/shared/".length()) : p.substring(1);
        if (rel.contains("..")) return null;
        if (fromClasspath) {
            String root = shared ? sharedResRoot : webResRoot;
            if (root == null) return null;
            try (var in = getClass().getClassLoader().getResourceAsStream(root + "/" + rel)) {
                return in == null ? null : in.readAllBytes();
            }
        }
        Path root = shared ? sharedDir : webDir;
        if (root == null) return null;
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) return null;
        return Files.readAllBytes(file);
    }

    // ── CDN proxy + local cache ───────────────────────────────────────────────
    // Serves /cdn/<path> from ~/.jexter/cdn, fetching https://cdn.jsdelivr.net/<path>
    // on a miss and caching it. Relative imports inside fetched modules keep the
    // /cdn/ prefix, so Shoelace's lazily-loaded chunks are cached on demand too.
    private void cdn(HttpExchange x) throws IOException {
        try {
            String rel = x.getRequestURI().getPath().substring("/cdn/".length());
            if (rel.isEmpty() || rel.contains("..")) { x.sendResponseHeaders(400, -1); x.close(); return; }
            Path cache = cdnCacheDir().resolve(rel).normalize();
            if (!cache.startsWith(cdnCacheDir())) { x.sendResponseHeaders(400, -1); x.close(); return; }

            byte[] data;
            String type = null;
            if (Files.isRegularFile(cache)) {
                data = Files.readAllBytes(cache);
                Path ct = ctOf(cache);
                if (Files.isRegularFile(ct)) type = Files.readString(ct, StandardCharsets.UTF_8).trim();
            } else {
                String q = x.getRequestURI().getRawQuery();
                Fetched f = fetch(CDN_HOST + rel + (q != null ? "?" + q : ""));
                data = f.bytes();
                type = f.type();
                if (isTextAsset(rel)) data = rewriteCdn(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                Files.createDirectories(cache.getParent());
                Files.write(cache, data);
                if (type != null && !type.isBlank()) Files.writeString(ctOf(cache), type, StandardCharsets.UTF_8);
            }
            x.getResponseHeaders().set("Content-Type", type != null && !type.isBlank() ? type : cdnType(rel));
            x.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            x.sendResponseHeaders(200, data.length);
            try (OutputStream os = x.getResponseBody()) { os.write(data); }
        } catch (Exception e) {                                   // offline & not cached, or upstream error
            x.sendResponseHeaders(502, -1); x.close();
        }
    }

    /** Prefetch the CDN URLs referenced by the web assets into the local cache (best-effort). */
    private void warmCache() {
        java.util.Set<String> rels = new java.util.LinkedHashSet<>();
        scanCdnRefs(webDir, rels);
        scanCdnRefs(sharedDir, rels);
        for (String rel : rels) {
            if (rel.isEmpty() || rel.contains("?") || rel.contains("..")) continue;
            Path cache = cdnCacheDir().resolve(rel).normalize();
            if (!cache.startsWith(cdnCacheDir()) || Files.isRegularFile(cache)) continue;
            try {
                Fetched f = fetch(CDN_HOST + rel);
                byte[] data = f.bytes();
                if (isTextAsset(rel)) data = rewriteCdn(new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                Files.createDirectories(cache.getParent());
                Files.write(cache, data);
                if (f.type() != null && !f.type().isBlank()) Files.writeString(ctOf(cache), f.type(), StandardCharsets.UTF_8);
            } catch (Exception ignore) { /* offline → leave it for the on-demand proxy */ }
        }
    }

    private static void scanCdnRefs(Path dir, java.util.Set<String> rels) {
        if (dir == null) return;
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String e = ext(f.toString());
                if (!e.equals("html") && !e.equals("js") && !e.equals("css")) continue;
                String txt = Files.readString(f);
                int i = 0;
                while ((i = txt.indexOf(CDN_HOST, i)) >= 0) {
                    int j = i + CDN_HOST.length(), end = j;
                    while (end < txt.length() && "\"'`) \t\r\n>".indexOf(txt.charAt(end)) < 0) end++;
                    rels.add(txt.substring(j, end));
                    i = end;
                }
            }
        } catch (Exception ignore) { /* no dir / IO → skip */ }
    }

    private record Fetched(byte[] bytes, String type) {}

    private static Fetched fetch(String url) throws IOException, InterruptedException {
        HttpClient c = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "Jexter").GET().build();
        HttpResponse<byte[]> r = c.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("CDN " + r.statusCode() + " for " + url);
        return new Fetched(r.body(), r.headers().firstValue("content-type").orElse(null));
    }

    /** Upstream Content-Type sidecar next to a cached asset ({@code <file>.ct}). */
    private static Path ctOf(Path cache) { return cache.resolveSibling(cache.getFileName() + ".ct"); }

    /** Rewrite absolute (and protocol-relative) jsdelivr URLs to the local /cdn/ proxy. */
    private static String rewriteCdn(String s) {
        return s.replace(CDN_HOST, "/cdn/").replace("//cdn.jsdelivr.net/", "/cdn/");
    }

    /** Content type of a CDN asset: extension map, plus jsdelivr's extensionless virtual
     *  endpoints ({@code …/+esm}) which are ES modules by contract — browsers enforce
     *  strict MIME checking on module scripts, so octet-stream would be rejected. */
    private static String cdnType(String rel) {
        if (rel.endsWith("/+esm") || rel.endsWith("+esm")) return "text/javascript; charset=utf-8";
        return MIME.getOrDefault(ext(rel), "application/octet-stream");
    }

    private static boolean isTextAsset(String name) {
        if (name.endsWith("+esm")) return true;            // rewrite embedded jsdelivr refs in virtual modules too
        String e = ext(name);
        return e.equals("js") || e.equals("mjs") || e.equals("css")
                || e.equals("html") || e.equals("map") || e.equals("json") || e.equals("svg");
    }

    private static Path cdnCacheDir() {
        return Path.of(System.getProperty("user.home", "."), ".jexter", "cdn");
    }

    // ── tiny HTTP/JSON utilities (shared with subclass handlers) ──────────────
    protected static Map<String, String> query(HttpExchange x) {
        Map<String, String> m = new HashMap<>();
        String q = x.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) m.put(dec(kv), "");
            else m.put(dec(kv.substring(0, eq)), dec(kv.substring(eq + 1)));
        }
        return m;
    }
    protected static String dec(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    protected static String  param(HttpExchange x, String k, String def)       { return query(x).getOrDefault(k, def); }
    protected static long    longParam(HttpExchange x, String k, long def)     { try { return Long.parseLong(param(x, k, "" + def)); } catch (Exception e) { return def; } }
    protected static double  doubleParam(HttpExchange x, String k, double def) { try { return Double.parseDouble(param(x, k, "" + def)); } catch (Exception e) { return def; } }

    protected static void json(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }
    protected static void error(HttpExchange x, Exception e) throws IOException {
        byte[] b = ("{\"error\":" + jstr(e.getMessage() == null ? e.toString() : e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(500, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    /** JSON string literal (quoted + escaped). Delegates to {@link JxStringer#quoted}
     *  so escaping lives in exactly one place. */
    protected static String jstr(String s) { return JxStringer.quoted(s); }

    protected static String ext(String name) { int d = name.lastIndexOf('.'); return d < 0 ? "" : name.substring(d + 1).toLowerCase(); }
    protected static long   round(double v) { return Math.round(v); }
    protected static String abbrev(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > n ? s.substring(0, n) + "\u2026" : s;
    }

    protected static Path findWebDir(String[] candidates) {
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) return p.toAbsolutePath();
        }
        return null;
    }

    // ── window launching (chromeless Chromium app window) ─────────────────────
    /** Open in a chromeless Chromium app window; fall back to the default browser. */
    private void openWindow(String url) {
        String profileName = title().toLowerCase().replace(' ', '-') + "-profile";
        String chromium = findChromium();
        if (chromium != null) {
            try {
                Path profile = Path.of(System.getProperty("java.io.tmpdir"), profileName);
                new ProcessBuilder(chromium, "--app=" + url,
                        "--user-data-dir=" + profile, "--window-size=" + windowSize())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return;
            } catch (Exception ignored) { }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url)); return;
            }
        } catch (Exception ignored) { }
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] cmd = os.contains("win") ? new String[]{"cmd", "/c", "start", "", url}
                : os.contains("mac") ? new String[]{"open", url}
                  : new String[]{"xdg-open", url};
        try { new ProcessBuilder(cmd).start(); } catch (Exception ignored) { }
    }

    /** First Chromium-family browser found, per OS. Chrome is preferred over Edge:
     *  Edge's Tracking Prevention blocks third-party (CDN) storage, which breaks the
     *  CDN-hosted front-end; Chrome does not. */
    private static String findChromium() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> candidates = new java.util.ArrayList<>();
        if (os.contains("win")) {
            String pf = System.getenv("ProgramFiles"), pfx = System.getenv("ProgramFiles(x86)"),
                    lad = System.getenv("LocalAppData");
            if (pf  != null) candidates.add(pf  + "\\Google\\Chrome\\Application\\chrome.exe");
            if (lad != null) candidates.add(lad + "\\Google\\Chrome\\Application\\chrome.exe");
            if (pfx != null) candidates.add(pfx + "\\Microsoft\\Edge\\Application\\msedge.exe");
            if (pf  != null) candidates.add(pf  + "\\Microsoft\\Edge\\Application\\msedge.exe");
        } else if (os.contains("mac")) {
            candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            candidates.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            for (String n : new String[]{"google-chrome", "chromium", "chromium-browser", "microsoft-edge"}) {
                String p = which(n);
                if (p != null) candidates.add(p);
            }
        }
        for (String c : candidates) if (Files.isRegularFile(Path.of(c))) return c;
        return null;
    }

    private static String which(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        }
        return null;
    }
}