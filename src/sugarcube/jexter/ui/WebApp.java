package sugarcube.jexter.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import sugarcube.jexter.core.Args;
import sugarcube.jexter.core.JxStringer;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

/**
 * WebApp — shared base for the local browser-driven apps (Prism, PDFInspector, Jexter).
 *
 * <p>All three are the same kind of app: a zero-dependency {@link HttpServer} serving a
 * static qry-stack front-end plus a tiny JSON/binary API, opened in a chromeless
 * Chromium window whose lifetime drives the process. This base owns everything that
 * is not document-specific — argument parsing and server bootstrap, the static-file
 * handler (with a shared {@code /shared/} root — the vendored qry stack, fonts and icons live there,
 * so nothing is ever fetched from the network and the apps work offline from the first start), the
 * SSE heartbeat and shutdown watchdog, window launching, and the small HTTP/JSON
 * utilities. Subclasses add only their state and their {@code /api/*} handlers.
 *
 * <p><b>Every route goes through one guard</b> ({@link #guarded}). Binding 127.0.0.1 keeps other machines
 * out, not other web pages: any site the user visits can POST to a local port, and a DNS-rebinding page
 * can even read the answer. These apps convert whatever they are sent and bind an LLM key, so a request is
 * served only when its {@code Host} names this server, its {@code Origin} (when sent) is this server, and
 * the browser does not flag it cross-site — and its options go through {@code ConvertOptions.fromRequest},
 * which refuses the ones naming a file of this machine.
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


    protected Path webDir;       // serves the app's own web/ (index.html, entry js) — disk mode
    protected Path sharedDir;    // serves /shared/* (sugarcube-mark.svg, js/); may be null

    // Classpath fallback: when the web/ assets are not on disk (e.g. a packaged
    // jar / jpackage app-image) they are served from the classpath instead.
    protected boolean fromClasspath;
    protected String webResRoot;     // e.g. "sugarcube/jexter/ui/prism/web"
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
    /** Register the app's {@code /api/*} routes (handlers live in the subclass); each one is guarded. */
    protected abstract void routes(Routes routes);

    /** The launcher flags that take the next argument as their value: {@code --port N}, {@code --web DIR}. */
    public static final String[] VALUED = { "port", "web" };

    /** How a subclass adds a route: always through the guard, never on the raw server. */
    @FunctionalInterface
    protected interface Routes { void add(String path, HttpHandler handler); }

    // ── Bootstrap ───────────────────────────────────────────────────────────────
    protected final void launch(String[] args) throws Exception {
        Args a = Args.parse(args, VALUED);
        String p = a.take("port"), w = a.take("web");
        int port = p != null ? Integer.parseInt(p) : defaultPort();
        Path web = w != null ? Path.of(w) : null;
        boolean serve = a.takeSwitch("serve"), noOpen = a.takeSwitch("no-open");
        boolean open = !serve && !noOpen;
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
        this.port = port;

        HttpServer s = server;
        Routes routes = (path, h) -> s.createContext(path, guarded(h));
        routes(routes);                                    // app-specific /api/* handlers
        routes.add("/api/app",    x -> send(x, 200, "text/plain; charset=utf-8", title().getBytes(StandardCharsets.UTF_8)));
        routes.add("/api/health", this::health);           // liveness, the probe every convert host answers
        routes.add("/api/alive",  this::alive);            // SSE heartbeat: the window keeps this open
        routes.add("/",           this::statics);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

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
        return cl.getResource(shared + "/js/ocd.js") != null || cl.getResource(shared + "/sugarcube-mark.svg") != null
                ? shared : null;
    }

    // ── Lifecycle: the window drives the server ───────────────────────────────

    /** SSE heartbeat. The browser holds this open (EventSource); when the window
     *  closes, the connection drops and the client count falls back to zero. */
    private void alive(HttpExchange x) throws IOException {
        clients.incrementAndGet();
        everConnected = true;
        try { stream(x, List.of(), 1000, null); }
        finally { if (clients.decrementAndGet() <= 0) idleSince = System.currentTimeMillis(); }
    }

    /** A server-sent-events channel: the open streams of one route, and the one way to write to them. */
    protected static final class Events {
        private final List<OutputStream> sinks = new CopyOnWriteArrayList<>();

        public Events() {}

        /** {@code json} to every open stream, as one event; a stream that is gone leaves. */
        public void push(String json) {
            byte[] data = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            for (OutputStream os : sinks) {
                try { synchronized (os) { os.write(data); os.flush(); } }
                catch (IOException dead) { sinks.remove(os); }
            }
        }

        /** Serve one stream of this channel: {@code backlog} first, then whatever is pushed. */
        public void serve(HttpExchange x, List<String> backlog) throws IOException { stream(x, backlog, 15000, sinks); }
    }

    /** THE server-sent-events idiom of every app: the headers, {@code backlog} as events, then a keep-alive
     *  comment every {@code pingMs} until the client goes. {@code sinks} (or null) holds the stream meanwhile. */
    private static void stream(HttpExchange x, List<String> backlog, long pingMs, List<OutputStream> sinks) throws IOException {
        x.getResponseHeaders().set("Content-Type", "text/event-stream");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, 0);
        OutputStream os = x.getResponseBody();
        try {
            synchronized (os) {
                for (String line : backlog) os.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            if (sinks != null) sinks.add(os);
            while (true) {
                synchronized (os) { os.write(": ping\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); }   // an SSE comment
                try { Thread.sleep(pingMs); } catch (InterruptedException e) { break; }
            }
        } catch (IOException gone) {               // the client went
        } finally {
            if (sinks != null) sinks.remove(os);
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    /** Requests in flight, counted by the guard for every app. The watchdog reads the heartbeat to decide
     *  the window is gone, but a browser can drop and re-open an SSE stream while a tab is busy — and a
     *  conversion holds the tab busy for tens of seconds. Exiting then kills the work AND the server that
     *  was doing it, which reads as a crash. So: never exit while something is being served. A stream
     *  (the heartbeat, a log) is not work: it would hold the process open forever. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile int port;

    // ── The guard ─────────────────────────────────────────────────────────────

    private HttpHandler guarded(HttpHandler h) {
        return x -> {
            String refused = refusal(x);
            if (refused != null) {
                send(x, refused.startsWith("method") ? 405 : 403, "text/plain; charset=utf-8", refused.getBytes(StandardCharsets.UTF_8));
                return;
            }
            boolean work = !String.valueOf(x.getRequestHeaders().getFirst("Accept")).contains("text/event-stream");
            if (work) inFlight.incrementAndGet();
            try { h.handle(x); }
            finally { if (work) inFlight.decrementAndGet(); }
        };
    }

    /** Why this request is not served, or null. */
    private String refusal(HttpExchange x) {
        String m = x.getRequestMethod();
        if (!m.equals("GET") && !m.equals("HEAD") && !m.equals("POST")) return "method " + m + " not allowed";
        if (!isSelf(x.getRequestHeaders().getFirst("Host"))) return "foreign Host";              // DNS rebinding
        String origin = x.getRequestHeaders().getFirst("Origin");
        if (origin != null && !(origin.startsWith("http://") && isSelf(origin.substring(7)))) return "foreign Origin";
        String site = x.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return "cross-site request";
        return null;
    }

    private boolean isSelf(String hostPort) {
        return hostPort != null && (hostPort.equals("127.0.0.1:" + port) || hostPort.equals("localhost:" + port));
    }

    /** Exit a few seconds after the window closes (window mode only; never in --serve). */
    private void armShutdownWatchdog() {
        final long GRACE = 5000, STARTUP = 30000, start = System.currentTimeMillis();
        Thread wd = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                if (!everConnected) {
                    if (now - start > STARTUP) { System.out.println("No window connected; exiting."); System.exit(0); }
                } else if (clients.get() == 0 && idleSince > 0 && now - idleSince > GRACE && inFlight.get() == 0) {
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
        // Local app/shared assets change with every dev iteration; never let the browser serve a
        // stale entry (a cached old JS against a new HTML mismatches element IDs and renders nothing).
        x.getResponseHeaders().set("Cache-Control", "no-cache, must-revalidate");
        send(x, 200, MIME.getOrDefault(ext(p), "application/octet-stream"), data);
    }

    /** Read a web asset by request path ("/index.html", "/shared/sugarcube-mark.svg"); null if absent.
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
        if (!file.toRealPath().startsWith(root.toRealPath())) return null;   // a link inside web/ never leads out of it
        return Files.readAllBytes(file);
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

    /** The one way a response leaves: status, type, bytes. */
    protected static void send(HttpExchange x, int status, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream os = x.getResponseBody()) { os.write(body); }
    }
    /** The {@code Content-Disposition} of a file sent back — the one place it is spelled. A name can come from the
     *  request (the uploaded file's), so it is never pasted between quotes: an ASCII fallback with every other
     *  character replaced, and the real name in RFC 5987's {@code filename*}. Once {@code a"; filename*=…} chose
     *  the download's name for whoever wrote the request. */
    protected static String disposition(boolean inline, String filename) {
        String ascii = filename.replaceAll("[^A-Za-z0-9._ -]", "_");
        String utf8 = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }

    protected static void json(HttpExchange x, String body) throws IOException {
        send(x, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }
    /** {@code {"error": …}} with the status the failure means: 413 too large, 400 a bad request, else 500. */
    protected static void error(HttpExchange x, Exception e) throws IOException {
        int status = e instanceof TooLarge ? 413 : e instanceof IllegalArgumentException ? 400 : 500;
        send(x, status, "application/json; charset=utf-8",
                ("{\"error\":" + jstr(e.getMessage() == null ? e.toString() : e.getMessage()) + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** Uploads stop here: a document, a font, a settings body — never an unbounded stream into memory. */
    protected static final long MAX_BODY = 512L << 20;

    protected static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;
        TooLarge(long max) { super("request body larger than " + (max >> 20) + " MB"); }
    }

    /** The request body, refused past {@code max} bytes. */
    protected static byte[] body(HttpExchange x, long max) throws IOException {
        try (var in = x.getRequestBody()) {
            byte[] b = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 8));
            if (b.length > max) throw new TooLarge(max);
            return b;
        }
    }
    protected static byte[] body(HttpExchange x) throws IOException { return body(x, MAX_BODY); }

    /** JSON string literal (quoted + escaped). Delegates to {@link JxStringer#quoted}
     *  so escaping lives in exactly one place. */
    protected static String jstr(String s) { return JxStringer.quoted(s); }

    protected static String ext(String name) { int d = name.lastIndexOf('.'); return d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT); }
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
        String profileName = title().toLowerCase(Locale.ROOT).replace(' ', '-') + "-profile";
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
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("win") ? new String[]{"cmd", "/c", "start", "", url}
                : os.contains("mac") ? new String[]{"open", url}
                  : new String[]{"xdg-open", url};
        try { new ProcessBuilder(cmd).start(); } catch (Exception ignored) { }
    }

    /** First Chromium-family browser found, per OS (Chrome first, then Edge). */
    private static String findChromium() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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
    /** {@code /api/health} — liveness, the probe every convert host answers. */
    private void health(HttpExchange x) throws IOException {
        send(x, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
    }

}