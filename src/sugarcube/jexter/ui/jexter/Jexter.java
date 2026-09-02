package sugarcube.jexter.ui.jexter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.convert.PdfImporter;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ui.WebApp;
import sugarcube.jexter.write.Conversion;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Jexter — distil any PDF into a <em>normalized</em> one through jexter's canonical OCD model.
 *
 * <p>Crude PDF in, pure PDF out: the source is imported to OCD and re-emitted as PDF, inheriting
 * every normalization the pipeline performs — repaired embedded fonts, dropped per-glyph self-clips,
 * one regular content structure, consistent colour/transform handling — always with a real,
 * selectable text layer.
 *
 * <p>This class is two things and nothing else: the <b>engine</b> ({@link #normalize}, the single
 * output-naming authority {@link #outName}, {@link #isOutput}) and the <b>WebApp</b> — a local
 * {@link HttpServer} on 127.0.0.1 serving the full-window app from {@code web/} and converting
 * <em>in-process</em>:
 *
 * <pre>
 *   POST /api/convert[?name=&lt;file&gt;&amp;&lt;opt&gt;=&lt;val&gt;…]   PDF bytes → normalized PDF bytes (always selectable)
 *   GET  /api/options                                  the introspectable ConvertOptions registry
 *   GET  /api/health                                   liveness ("ok")
 * </pre>
 *
 * <p>All headless orchestration (single file, folder batch, and the hot-folder daemon) lives in
 * {@link JexterCli}, which calls this engine. {@code main} dispatches: a positional argument or
 * {@code --hotfolder} runs headless, otherwise the app window opens.
 *
 * @author Jean-Luc Bloechle with Claude.ai
 */
public final class Jexter extends WebApp {

    /** The one suffix. Output naming and output detection both derive from it — change it here only. */
    private static final String SUFFIX = "-normalized.pdf";

    // ── engine ───────────────────────────────────────────────────────────────────

    /** Normalize a PDF the Jexter way — rebuild the text layer, recover navigation only when the source
     *  has none, keep annotations, always a selectable text layer. The whole API in one call. */
    public static OCDDocument normalize(File in, File out) throws IOException {
        return normalize(in, out, null, true);
    }

    /** As above, with optional {@code overrides} layered on the fixed policy and {@code selectable=false}
     *  for outline-only output. Package-private: the headless CLI is the only other caller. */
    static OCDDocument normalize(File in, File out, Map<String, String> overrides, boolean selectable) throws IOException {
        ConvertOptions opts = policy(overrides);
        OCDDocument doc = PdfImporter.convert(in, opts);
        Map<String, String> m = new LinkedHashMap<>();
        opts.toMap().forEach((k, v) -> m.put(k, String.valueOf(v)));
        m.put("selectable", String.valueOf(selectable));
        writeAtomic(out.toPath(), Conversion.convert(doc, "pdf", m).bytes());
        return doc;
    }

    /** The fixed Jexter policy: engine defaults except header/footer detection off (explicit overrides win). */
    static void applyPolicy(Map<String, String> m) { m.putIfAbsent("detectHeaders", "false"); }
    private static ConvertOptions policy(Map<String, String> overrides) {
        Map<String, String> m = new LinkedHashMap<>(overrides == null ? Map.of() : overrides);
        applyPolicy(m);
        return ConvertOptions.fromMap(m);
    }

    /** {@code foo.pdf} (or {@code /a/b/foo.pdf}) → {@code foo-normalized.pdf} — the only place a name is formed. */
    public static String outName(String name) {
        String base = (name == null || name.isBlank()) ? "document" : name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + SUFFIX;
    }
    public static String  outName(File f)       { return outName(f.getName()); }
    /** True for our own output files — so no pass ever re-normalizes a {@code *-normalized.pdf}. */
    public static boolean isOutput(String name) { return name.toLowerCase().endsWith(SUFFIX); }

    /** Write to a sibling temp file, then atomically move it into place — a folder watcher or
     *  downstream tool never sees a half-written PDF. Falls back to a plain replace where atomic
     *  moves aren't supported (e.g. replacing an existing target on Windows). */
    static void writeAtomic(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);                                  // also makes mirrored sub-dirs
        Path tmp = Files.createTempFile(dir, ".jexter-", ".part");      // same dir → same filesystem → atomic move
        try {
            Files.write(tmp, data);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);                                 // no-op once the move succeeded
        }
    }

    // ── entry point — positional arg or --hotfolder → headless; otherwise the window ──
    public static void main(String[] args) throws Exception {
        if (isHeadless(args)) JexterCli.run(args);
        else new Jexter().launch(args);
    }
    private static boolean isHeadless(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--hotfolder")) return true;
            if (a.equals("--port") || a.equals("--web")) { i++; continue; }   // window flags carry a value
            if (a.startsWith("--")) continue;
            return true;                                                       // a positional → headless conversion
        }
        return false;
    }

    // ── WebApp contract ──────────────────────────────────────────────────────────
    @Override protected String title()      { return "Jexter"; }
    @Override protected String windowSize() { return "1000,707"; }   // A4 landscape (210:297), compact
    @Override protected int    defaultPort() { return 7347; }   // Prism 7345 · PDFInspector 7346 · Jexter 7347 — one port per app
    @Override protected String[] webCandidates() {
        return new String[]{ "web", "sugarcube/jexter/ui/jexter/web",
                "src/sugarcube/jexter/ui/jexter/web", "build/src/sugarcube/jexter/ui/jexter/web" };
    }
    @Override protected void routes(HttpServer server) {
        server.createContext("/api/convert", this::convert);   // the one engine route: PDF bytes → normalized PDF bytes
        server.createContext("/api/options", this::options);   // ConvertOptions registry (parity; the app curates its own toggles)
        server.createContext("/api/health",  this::health);    // liveness probe
    }

    // ── /api/convert — PDF bytes in, normalized PDF bytes out (always selectable) ─
    private void convert(HttpExchange x) throws IOException {
        try {
            Map<String, String> opts = new LinkedHashMap<>(query(x));
            String name = opts.remove("name");                 // original filename → the download name
            opts.remove("to");                                  // target is always pdf
            applyPolicy(opts);                                  // the fixed Jexter policy (header/footer detection off)
            opts.putIfAbsent("selectable", "true");             // Jexter always emits a real selectable text layer
            byte[] body = x.getRequestBody().readAllBytes();
            if (body.length == 0) { error(x, new IllegalArgumentException("empty body — upload a PDF")); return; }

            Conversion.Output out = Conversion.convert(body, "pdf", opts);   // import to OCD + re-export as normalized PDF
            byte[] d = out.bytes();
            // Report what actually happened. The UI's whole promise is «a clean, selectable PDF», and
            // «normalized ✓» is not evidence — page count and the size delta are. The page count comes
            // from a COS parse of the OUTPUT (structure only, no content extraction: ~200 ms on a
            // 600-page book, nothing on a normal one), so it costs a fraction of the conversion and
            // proves the file we just produced is readable by a PDF parser at all.
            x.getResponseHeaders().set("X-Jexter-In", String.valueOf(body.length));
            x.getResponseHeaders().set("X-Jexter-Out", String.valueOf(d.length));
            x.getResponseHeaders().set("X-Jexter-Selectable", "true");
            try (org.apache.pdfbox.pdmodel.PDDocument chk = org.apache.pdfbox.Loader.loadPDF(d)) {
                x.getResponseHeaders().set("X-Jexter-Pages", String.valueOf(chk.getNumberOfPages()));
            } catch (Exception ignore) { /* the bytes are still valid output; the count is a nicety */ }
            x.getResponseHeaders().set("Content-Type", out.mediaType());
            x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + outName(name) + "\"");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.sendResponseHeaders(200, d.length);
            try (OutputStream os = x.getResponseBody()) { os.write(d); }
        } catch (Exception e) { error(x, e); }
    }

    // ── read-only routes ─────────────────────────────────────────────────────────
    private void options(HttpExchange x) throws IOException {
        try { json(x, ConvertOptions.optionsJson(false, "", "", "")); }
        catch (Exception e) { error(x, e); }
    }
    private void health(HttpExchange x) throws IOException {
        byte[] b = "ok".getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }
}
