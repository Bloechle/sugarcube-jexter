package sugarcube.jexter.ui.prism;

import sugarcube.jexter.bind.LlmEngines;
import sugarcube.jexter.bind.OcrEngines;
import sugarcube.jexter.Conversion;
import sugarcube.jexter.ocd.io.SvgOcdWriter;
import com.sun.net.httpserver.HttpExchange;

import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.ui.WebApp;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxProgress;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import sugarcube.jexter.core.JxName;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Prism — the jexter document workbench as a local web app: the reader chassis (the EPUB tree
 * served client-side by a Service Worker, untouched) over the engine, on the same stateless
 * convert contract as any conversion service (doc/FRONTEND.md). The front-end holds the source
 * and the book; the server just turns bytes into bytes through {@link Conversion}.
 *
 * <pre>
 *   POST /api/convert?to=&lt;a target id&gt;[&amp;&lt;opt&gt;=&lt;val&gt;…]
 *        body: the document — a PDF, an image or an OCD-EPUB; the engine reads its first bytes
 *        → the artifact (media type + filename from jexter)
 *   GET  /api/options      → the ConvertOptions registry and the bound model
 *   GET  /api/targets      → the valid `to` ids
 *   GET  /api/health       → "ok"
 *   POST /api/font             a font file → its block for the container                    (desktop only)
 *   GET  /api/log · /api/progress   Server-Sent Events: JxLog (F2) and the conversion's progress (desktop only)
 * </pre>
 *
 * Structure refinement is not a route of its own: it rides {@code /api/convert?to=ocd&refineStructure=true}
 * (the bound LLM runs in the engine), as on any convert host. All conversion semantics live in
 * {@link Conversion}; adding a target happens once, there.
 */
public final class Prism extends WebApp {

    public static void main(String[] args) throws Exception { new Prism().launch(args); }

    @Override protected String title()       { return "Prism"; }
    @Override protected int    defaultPort()  { return 7345; }
    @Override protected String[] webCandidates() {
        return new String[]{"web", "sugarcube/jexter/ui/prism/web",
                "src/sugarcube/jexter/ui/prism/web", "build/src/sugarcube/jexter/ui/prism/web"};
    }
    @Override protected void routes(Routes routes) {
        installLogBridge();                                   // mirror JxLog → /api/log (in-page console)
        LlmEngines.bindFromEnv();                             // bind a language model iff the environment names one
        OcrEngines.bindFromEnv();                             // bind an OCR engine iff the environment names one
        routes.add("/api/convert",  this::convert); // the one engine route: bytes (+ to/opts) → artifact bytes
        routes.add("/api/options",  this::options); // the ConvertOptions registry (+ bound-model status)
        routes.add("/api/targets",  this::targets); // valid `to` ids
        routes.add("/api/font",     this::font);    // a TTF/OTF → the container's own font block
        routes.add("/api/log",      this::log);     // JxLog SSE (F2)
        routes.add("/api/progress", this::progress);// conversion progress SSE
    }

    // ── /api/convert — the single conversion entry (PDF, image or OCD-EPUB → any target) ──
    private void convert(HttpExchange x) throws IOException {
        try {
            Map<String, String> opts = new LinkedHashMap<>(query(x));
            String to   = opts.remove("to");  if (to == null || to.isBlank()) to = "ocd";
            byte[] body = body(x);
            if (body.length == 0) { error(x, new IllegalArgumentException("empty body — POST a PDF, an image or an OCD-EPUB")); return; }

            // The window is watching: bind the progress channel of THIS conversion to the SSE stream.
            // Per conversion, never global — the engine's concurrency contract is one document per thread.
            ConvertOptions co = ConvertOptions.fromRequest(opts).progress(p -> pushProgress(p.toJson()));
            Conversion.Output out = Conversion.convert(body, to, co);   // PDF, image or OCD-EPUB: the engine sniffs
            pushProgress(new JxProgress(JxProgress.Stage.WRITE, 0, 0, "done").toJson());
            send(x, out);
        } catch (Exception e) { error(x, e); }
    }

    // ── /api/options — the introspectable ConvertOptions registry ─────────────
    // The engine's one serializer: every convert host answers the same shape, the bound model included.
    private void options(HttpExchange x) throws IOException {
        try { json(x, ConvertOptions.optionsJson()); } catch (Exception e) { error(x, e); }
    }

    // ── /api/targets — the valid `to` ids (single source: Conversion) ─────────
    private void targets(HttpExchange x) throws IOException {
        try {
            String arr = Conversion.targets().stream().map(JxStringer::quoted).collect(Collectors.joining(","));
            json(x, "{\"targets\":[" + arr + "]}");
        } catch (Exception e) { error(x, e); }
    }

    // ── the artifact back ──
    private static void send(HttpExchange x, Conversion.Output o) throws IOException {
        x.getResponseHeaders().set("Content-Type", o.mediaType());
        x.getResponseHeaders().set("Content-Disposition", disposition(o.inline(), o.filename()));
        x.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] d = o.bytes();
        x.sendResponseHeaders(200, d.length);
        try (OutputStream os = x.getResponseBody()) { os.write(d); }
    }

    // ── Debug log bridge — mirror JxLog (java.util.logging) into the F2 console ──
    private static final Events logEvents = new Events();
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

    private static final Events progressEvents = new Events();

    /** Progress has no ring: a step is only meaningful while its conversion runs, and a client that
     *  connects late wants the next step, not a replay of the last one. */
    private static void pushProgress(String json) { progressEvents.push(json); }

    private void progress(HttpExchange x) throws IOException { progressEvents.serve(x, java.util.List.of()); }

    private static void pushLog(String json) {
        synchronized (logRing) {
            logRing.addLast(json);
            while (logRing.size() > LOG_RING_MAX) logRing.removeFirst();
        }
        logEvents.push(json);
    }

    private void log(HttpExchange x) throws IOException {
        java.util.List<String> backlog;
        synchronized (logRing) { backlog = new java.util.ArrayList<>(logRing); }
        logEvents.serve(x, backlog);
    }

    // ── /api/font — a font the container does not carry, in the container's own form ──
    //
    // The body is the TTF/OTF; `chars` are the characters to take (blank = printable ASCII) and `id`
    // the alias to mint it under, which the client picks because it alone knows which are free. The
    // answer is a one-font `pages/fonts.svg` — the SAME emitter the writer uses, on a document holding
    // only this font, so what comes back is byte-for-byte the shape the container already speaks and
    // the client merges a `<g>` rather than learning a second grammar.
    //
    // A route rather than a `ConvertOptions` because /api/convert's body is the CONTAINER: a second
    // payload has nowhere to travel. The engine-side option (`addFont`) exists for the CLI, where a
    // path is enough; this is the same code path, reached from a browser that only has bytes.
    private void font(HttpExchange x) throws IOException {
        try {
            byte[] ttf = body(x);
            if (ttf.length == 0) throw new IllegalArgumentException("no font in the request body");
            String chars = param(x, "chars", "");
            String alias = param(x, "id", "f0");
            OCDDocument doc = new OCDDocument();
            Conversion.addFont(doc, ttf, chars);
            String svg = SvgOcdWriter.glyphsSvg(doc, new SvgOcdWriter.Fonts(doc));
            byte[] b = svg.replace("<g id=\"f0\"", "<g id=\"" + JxName.safe(alias) + "\"")
                          .replace("id=\"f0-", "id=\"" + JxName.safe(alias) + "-")
                          .getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "image/svg+xml; charset=utf-8");
            x.sendResponseHeaders(200, b.length);
            try (OutputStream os = x.getResponseBody()) { os.write(b); }
        } catch (Exception e) { error(x, e); }
    }

}
