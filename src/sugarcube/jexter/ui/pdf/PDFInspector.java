package sugarcube.jexter.ui.pdf;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import sugarcube.jexter.ui.WebApp;
import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.write.Conversion;
import sugarcube.jexter.convert.PdfImporter;
import sugarcube.jexter.convert.PdfRenderer;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.render.OCDRenderer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF Inspector — shows <em>exactly</em> what comes out of Apache PDFBox, with
 * nothing in between: no OCD model, no Jexter conversion. Serves the same shared
 * qry-stack front-end as {@link WebApp}/Prism and exposes PDFBox directly:
 * {@code /api/page} ({@link PDFRenderer} raster), {@code /api/tree} (the parsed
 * <b>COS</b> object tree of a page) and {@code /api/text} ({@link PDFTextStripper}).
 *
 * <p>For side-by-side debugging of the conversion pipeline, {@code /api/page}
 * also offers two non-PDFBox rasterizers via {@code ?src=}: {@code ocd} renders
 * the in-memory OCD model ({@link PdfImporter} → {@link OCDRenderer}), and
 * {@code ocd-file} renders the model after a full OCD-EPUB
 * write/re-read round-trip (returns with the OCD-EPUB reader). Toggling the
 * source while keeping page/zoom fixed makes it obvious <em>which stage</em>
 * introduced a discrepancy: PDFBox → OCD (converter) → reread (serialization).
 *
 * <pre>  java sugarcube.jexter.ui.pdf.PDFInspector [--port N] [--web DIR] [--serve]</pre>
 */
public final class PDFInspector extends WebApp {

    // COS tree guards — keep the JSON bounded on pathological / deeply-shared graphs.
    private static final int MAX_DEPTH = 14;
    private static final int MAX_NODES = 6000;

    private volatile PDDocument doc;

    // Source PDF on disk + lazily-built OCD models, for the `?src=ocd|ocd-file` rasterizers.
    // All three are invalidated and rebuilt on each /api/open. The OCD build is the expensive
    // step (full conversion), so it is cached and shared by every render of the open document.
    private volatile Path        pdfFile;        // the bytes of the open PDF (PdfImporter needs a File)
    private volatile OCDDocument ocdVirtual;     // PdfImporter.convert(pdf)            — converter output
    private volatile OCDDocument ocdReread;      // serialization round-trip stage (retired until the OCD-EPUB reader)
    private final Object ocdLock = new Object();
    // PDFBox's PDDocument / PDFRenderer / stream decoding is NOT thread-safe. The front-end fires
    // overlapping requests (blur-up sends a thumbnail + a full render at once, plus the tree/text),
    // and the server runs them on separate threads — concurrent use of one PDDocument corrupts the
    // shared Flate decoder ("DataFormatException: invalid distance / block type"). Every access to
    // `doc` (render, COS tree, text, and the open/replace) is serialized through this monitor.
    private final Object pdfLock = new Object();

    public static void main(String[] args) throws Exception { new PDFInspector().launch(args); }

    @Override protected String title()       { return "PDF Inspector"; }
    @Override protected int    defaultPort()  { return 7346; }   // Prism sits at 7345; next to it
    @Override protected String[] webCandidates() {
        return new String[]{"web", "sugarcube/jexter/ui/pdf/web",
                "src/sugarcube/jexter/ui/pdf/web", "build/src/sugarcube/jexter/ui/pdf/web"};
    }
    @Override protected void routes(HttpServer server) {
        server.createContext("/api/open", this::open);
        server.createContext("/api/page", this::page);
        server.createContext("/api/ocd", this::ocd);     // the OCD model (OCD-EPUB zip) → the client displays the stored page SVG verbatim
        server.createContext("/api/tree", this::tree);   // COS structure (page- or document-rooted)
        server.createContext("/api/content", this::content); // decoded content-stream operators
        server.createContext("/api/stream", this::stream);   // decoded bytes of one indirect stream
        server.createContext("/api/render", this::render);    // rasterize one image XObject → PNG
        server.createContext("/api/images", this::images);    // list the document's image XObjects (+ metadata, page usage)
        server.createContext("/api/text", this::text);
    }

    // ── API: import (PDFBox loads it; we keep the live PDDocument) ────────────
    private void open(HttpExchange x) throws IOException {
        try {
            String name = param(x, "name", "document");
            byte[] body = x.getRequestBody().readAllBytes();
            synchronized (pdfLock) {                 // don't close `prev` while a render still holds it
                PDDocument prev = doc;
                doc = Loader.loadPDF(body);
                if (prev != null) try { prev.close(); } catch (IOException ignored) { }
            }
            baseName = name.replaceFirst("\\.[^.]+$", "");

            // keep the raw bytes on disk + drop any cached OCD models from the previous document,
            // so the `?src=ocd|ocd-file` rasterizers rebuild from this PDF on next request.
            synchronized (ocdLock) {
                Path prevFile = pdfFile;
                Path f = Files.createTempFile("pdfinspector-", ".pdf");
                Files.write(f, body);
                pdfFile = f;
                ocdVirtual = null;
                ocdReread = null;
                if (prevFile != null) try { Files.deleteIfExists(prevFile); } catch (IOException ignored) { }
            }

            PDDocumentInformation info = doc.getDocumentInformation();
            int nPages = doc.getNumberOfPages();

            // unique fonts across the document, keyed by name|subtype|embedded
            Map<String, Map<String, String>> fonts = new LinkedHashMap<>();
            var sbPages = new StringBuilder("[");
            for (int i = 0; i < nPages; i++) {
                PDPage p = doc.getPage(i);
                PDRectangle box = p.getCropBox();   // the visible (rendered) box
                int fontN = 0, xobjN = 0;
                PDResources res = p.getResources();
                if (res != null) {
                    for (COSName fn : res.getFontNames()) {
                        fontN++;
                        try {
                            PDFont f = res.getFont(fn);
                            if (f == null) continue;
                            String fname = safe(f.getName());
                            String key = fname + "|" + safe(f.getSubType()) + "|" + f.isEmbedded();
                            fonts.computeIfAbsent(key, k -> {
                                Map<String, String> m = new LinkedHashMap<>();
                                m.put("name", fname);
                                m.put("subtype", safe(f.getSubType()));
                                m.put("type", safe(f.getType()));
                                m.put("embedded", String.valueOf(f.isEmbedded()));
                                return m;
                            });
                        } catch (Exception ignored) { /* a broken font shouldn't sink the page */ }
                    }
                    for (COSName ignored : res.getXObjectNames()) xobjN++;
                }
                if (i > 0) sbPages.append(',');
                // PDFBox renders a 90/270 page with swapped dimensions — report the
                // effective (rotation-applied) size so the front-end's auto-DPI and
                // the "W x H pt" label match the image PDFRenderer actually produces.
                int rot = ((p.getRotation() % 360) + 360) % 360;
                boolean swap = rot == 90 || rot == 270;
                double ew = swap ? box.getHeight() : box.getWidth();
                double eh = swap ? box.getWidth()  : box.getHeight();
                sbPages.append("{\"w\":").append(round(ew))
                        .append(",\"h\":").append(round(eh))
                        .append(",\"rot\":").append(rot)
                        .append(",\"fonts\":").append(fontN)
                        .append(",\"xobjects\":").append(xobjN).append('}');
            }
            sbPages.append(']');

            var sb = new StringBuilder("{\"name\":").append(jstr(name))
                    .append(",\"version\":").append(jstr(String.format("%.1f", doc.getVersion())))
                    .append(",\"encrypted\":").append(doc.isEncrypted())
                    .append(",\"fonts\":").append(fonts.size())
                    .append(",\"info\":{")
                    .append("\"title\":").append(jstr(safe(info.getTitle())))
                    .append(",\"author\":").append(jstr(safe(info.getAuthor())))
                    .append(",\"creator\":").append(jstr(safe(info.getCreator())))
                    .append(",\"producer\":").append(jstr(safe(info.getProducer())))
                    .append("},\"pages\":").append(sbPages)
                    .append(",\"fontList\":[");
            int fi = 0;
            for (Map<String, String> f : fonts.values()) {
                if (fi++ > 0) sb.append(',');
                sb.append("{\"name\":").append(jstr(f.get("name")))
                        .append(",\"subtype\":").append(jstr(f.get("subtype")))
                        .append(",\"type\":").append(jstr(f.get("type")))
                        .append(",\"embedded\":").append(f.get("embedded")).append('}');
            }
            sb.append("]}");
            json(x, sb.toString());
        } catch (Exception e) { error(x, e); }
    }

    // ── API: render a page → PNG ─────────────────────────────────────────────
    // ?src=pdfbox (default) renders straight from PDFBox; ?src=ocd renders the in-memory
    // OCD model; ?src=ocd-file renders it after an OCD-EPUB write/re-read round-trip.
    private void page(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            int i = (int) Math.max(0, Math.min(doc.getNumberOfPages() - 1, longParam(x, "i", 0)));
            float dpi = (float) doubleParam(x, "dpi", 144);
            String src = param(x, "src", "pdfbox");

            BufferedImage img;
            switch (src) {
                case "ocd"        -> img = renderOcd(ocdVirtual(), i, dpi);
                case "ocd-file"   -> img = renderOcd(ocdReread(),  i, dpi);
                // Our direct PDF rasterizer (COS → raster, no OCD model). Walks the shared
                // PDDocument, so it shares the pdfLock with the PDFBox path / tree / text.
                case "pdf-direct" -> { synchronized (pdfLock) { img = PdfRenderer.render(doc, i, dpi); } }
                // PDFBox render reuses the shared PDDocument → must not run concurrently with
                // another render / the COS tree / text extraction (see pdfLock).
                default           -> { synchronized (pdfLock) { img = new PDFRenderer(doc).renderImageWithDPI(i, dpi, ImageType.RGB); } }
            }
            writePng(x, img);
        } catch (Exception e) { error(x, e); }
    }

    private static BufferedImage renderOcd(OCDDocument od, int i, float dpi) {
        if (od == null || od.page(i) == null)
            throw new IllegalStateException("OCD model has no page " + i);
        return OCDRenderer.render(od.page(i), od, dpi);
    }

    // ── API: the OCD model as an OCD-EPUB container ──────────────────────────
    // The web stage displays the STORED page SVG from this container (loadOcd) — so OCD / OCD↺
    // are true vector, crisp at any zoom, no raster, no blur-up, and exactly the shipped bytes. ?src=ocd serves the in-memory model; ?src=ocd-file the write/re-read
    // round-trip. PDFBox and pdf-direct are rasterizers (PNG via /api/page) and never reach here.
    private void ocd(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            OCDDocument od = "ocd-file".equals(param(x, "src", "ocd")) ? ocdReread() : ocdVirtual();
            if (od == null) throw new IllegalStateException("OCD model unavailable");
            var baos = new ByteArrayOutputStream();
            Conversion.Output o = Conversion.convert(od, "ocd", (ConvertOptions) null);
            baos.write(o.bytes());
            byte[] body = baos.toByteArray();
            x.getResponseHeaders().set("Content-Type", o.mediaType());
            x.getResponseHeaders().set("Cache-Control", "no-store");
            x.sendResponseHeaders(200, body.length);
            try (OutputStream os = x.getResponseBody()) { os.write(body); }
        } catch (Exception e) { error(x, e); }
    }

    private static void writePng(HttpExchange x, BufferedImage img) throws IOException {
        var baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] png = baos.toByteArray();
        x.getResponseHeaders().set("Content-Type", "image/png");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(200, png.length);
        try (OutputStream os = x.getResponseBody()) { os.write(png); }
    }

    /** Convert the open PDF to an OCD model once, then cache it (the converter is the costly step). */
    private OCDDocument ocdVirtual() throws IOException {
        OCDDocument od = ocdVirtual;
        if (od != null) return od;
        synchronized (ocdLock) {
            if (ocdVirtual == null) {
                if (pdfFile == null) throw new IllegalStateException("no document");
                ocdVirtual = PdfImporter.convert(pdfFile.toFile());
            }
            return ocdVirtual;
        }
    }

    /** Round-trip the model through the OCD-EPUB container (write then re-read), to surface any
     *  serialization loss separately from the converter. Cached like the virtual model. */
    private OCDDocument ocdReread() throws Exception {
        OCDDocument od = ocdReread;
        if (od != null) return od;
        synchronized (ocdLock) {
            if (ocdReread == null) {
                OCDDocument virt = ocdVirtual();
                Path tmp = Files.createTempFile("pdfinspector-", ".ocd.epub");
                try {
                    Conversion.Output o = Conversion.convert(virt, "ocd", (ConvertOptions) null);
                    Files.write(tmp, o.bytes());
                    ocdReread = sugarcube.jexter.ocd.io.OCDReader.read(tmp.toFile());
                } finally {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
                }
            }
            return ocdReread;
        }
    }

    // ── API: COS structure → JSON tree ──────────────────────────────────────
    // ?root=page (default) roots at the page dictionary; ?root=doc roots at the file
    // trailer (→ catalog, AcroForm, Names, OCProperties, metadata… — the whole graph).
    private void tree(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            var sb = new StringBuilder("[");
            synchronized (pdfLock) {              // COS access touches the shared PDDocument's streams
                var ctx = new CosCtx();
                if ("doc".equals(param(x, "root", "page"))) {
                    cosEntries(sb, doc.getDocument().getTrailer(), 0, ctx);
                } else {
                    int i = (int) Math.max(0, Math.min(doc.getNumberOfPages() - 1, longParam(x, "page", 0)));
                    cosEntries(sb, doc.getPage(i).getCOSObject(), 0, ctx);
                }
            }
            sb.append(']');
            json(x, sb.toString());
        } catch (Exception e) { error(x, e); }
    }

    // ── API: content-stream operators of a page → JSON ──────────────────────
    // Tokenizes the page content stream(s) into the operator list a debugger wants to see
    // ("BT", "Tf /F1 12", "Td …", "Tj (…)", "re", "Do /Im0", …) — the raw drawing program,
    // before any OCD interpretation. Bounded by MAX_OPS so a huge page can't blow the response.
    private static final int MAX_OPS = 12000;
    private void content(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            var sb = new StringBuilder("{\"ops\":[");
            boolean truncated = false;
            synchronized (pdfLock) {
                int i = (int) Math.max(0, Math.min(doc.getNumberOfPages() - 1, longParam(x, "page", 0)));
                PDPage page = doc.getPage(i);
                PDFStreamParser parser = new PDFStreamParser(page);
                java.util.List<COSBase> operands = new java.util.ArrayList<>();
                Object tok; int n = 0; boolean first = true;
                while ((tok = parser.parseNextToken()) != null) {
                    if (tok instanceof Operator op) {
                        if (n++ >= MAX_OPS) { truncated = true; break; }
                        StringBuilder args = new StringBuilder();
                        for (int k = 0; k < operands.size(); k++) {
                            if (k > 0) args.append(' ');
                            args.append(cosArg(operands.get(k)));
                        }
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"op\":").append(jstr(op.getName()))
                          .append(",\"args\":").append(jstr(args.toString())).append('}');
                        operands.clear();
                    } else if (tok instanceof COSBase b) {
                        if (operands.size() < 32) operands.add(b);   // guard pathological operand runs
                    }
                }
            }
            sb.append("],\"truncated\":").append(truncated).append('}');
            json(x, sb.toString());
        } catch (Exception e) { error(x, e); }
    }

    // ── API: decoded bytes of one indirect stream → JSON ────────────────────
    // ?obj=N resolves the indirect object by number in the COS pool and returns its *decoded*
    // (filters applied) content: text when it is printable (content streams, ToUnicode, XMP,
    // CMaps…), otherwise a hex dump (font programs, images…). Used by the Structure tab when a
    // stream node is clicked.
    private static final int STREAM_LIMIT = 200_000;   // cap the payload returned to the browser
    private void stream(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            long objNum = longParam(x, "obj", -1);
            if (objNum < 0) { error(x, new IllegalArgumentException("obj required")); return; }
            byte[] bytes = null;
            synchronized (pdfLock) {
                COSStream s = findStream(doc.getDocument().getTrailer(), objNum, new IdentityHashMap<>());
                if (s != null) try (var in = s.createInputStream()) { bytes = in.readNBytes(STREAM_LIMIT + 1); }
            }
            if (bytes == null) { json(x, "{\"error\":\"object " + objNum + " is not a stream\"}"); return; }
            boolean truncated = bytes.length > STREAM_LIMIT;
            if (truncated) bytes = java.util.Arrays.copyOf(bytes, STREAM_LIMIT);
            boolean printable = isMostlyPrintable(bytes);
            String body = printable ? new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1) : hexDump(bytes);
            json(x, "{\"obj\":" + objNum + ",\"encoding\":" + jstr(printable ? "text" : "hex")
                    + ",\"truncated\":" + truncated + ",\"text\":" + jstr(body) + "}");
        } catch (Exception e) { error(x, e); }
    }

    // ── API: rasterize one image XObject → PNG ──────────────────────────────
    // ?obj=N resolves an indirect image stream and decodes it to a PNG (best effort — a
    // self-contained image decodes without page context; exotic colourspaces may not).
    private void render(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            long objNum = longParam(x, "obj", -1);
            BufferedImage img = null;
            synchronized (pdfLock) {
                COSStream s = findStream(doc.getDocument().getTrailer(), objNum, new IdentityHashMap<>());
                if (s != null && s.getDictionaryObject(COSName.SUBTYPE) instanceof COSName sn
                        && "Image".equals(sn.getName())) {
                    try { img = new PDImageXObject(new PDStream(s), null).getImage(); }
                    catch (Exception ignored) { /* undecodable image → 404 below */ }
                }
            }
            if (img == null) { x.sendResponseHeaders(404, -1); x.close(); return; }
            writePng(x, img);
        } catch (Exception e) { error(x, e); }
    }

    // ── API: the document's image XObjects → JSON ────────────────────────────
    // A cycle-guarded walk of every page's resources (recursing into Form XObjects) collects each
    // image stream once, keyed by its indirect object number, with the pages that reference it and
    // its decode metadata (size, bit-depth, colorspace, filter, soft-mask / image-mask). The browser
    // renders each thumbnail via /api/render?obj=N (the same path the detail panel's Image view uses).
    private void images(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            var imgs = new LinkedHashMap<Long, COSStream>();                 // obj → image stream (dedup, first-seen order)
            var use  = new LinkedHashMap<Long, java.util.TreeSet<Integer>>(); // obj → pages referencing it
            synchronized (pdfLock) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDResources res = doc.getPage(i).getResources();
                    if (res != null) collectImages(res.getCOSObject(), i, imgs, use, new IdentityHashMap<>());
                }
                var sb = new StringBuilder("[");
                boolean first = true;
                for (var e : imgs.entrySet()) {
                    long objNum = e.getKey(); COSStream s = e.getValue();
                    int w = s.getInt(COSName.WIDTH, 0), h = s.getInt(COSName.HEIGHT, 0), bpc = s.getInt(COSName.BITS_PER_COMPONENT, 0);
                    boolean mask = s.getBoolean(COSName.IMAGE_MASK, false), smask = s.getDictionaryObject(COSName.SMASK) != null;
                    var pages = use.getOrDefault(objNum, new java.util.TreeSet<>());
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"obj\":").append(objNum).append(",\"w\":").append(w).append(",\"h\":").append(h)
                      .append(",\"bpc\":").append(bpc).append(",\"mask\":").append(mask).append(",\"smask\":").append(smask)
                      .append(",\"cs\":").append(jstr(csLabel(s.getDictionaryObject(COSName.COLORSPACE))))
                      .append(",\"filter\":").append(jstr(filterLabel(s.getDictionaryObject(COSName.FILTER))))
                      .append(",\"pages\":").append(pages.toString()).append('}');
                }
                sb.append(']');
                json(x, sb.toString());
            }
        } catch (Exception e) { error(x, e); }
    }

    /** Collect image XObjects (by object number) from a resources dict, recursing into Form XObjects. */
    @SuppressWarnings("deprecation")  // COSObject has no non-deprecated key accessor in PDFBox 3.0.7
    private static void collectImages(COSBase resBase, int page, Map<Long, COSStream> imgs,
                                      Map<Long, java.util.TreeSet<Integer>> use, IdentityHashMap<COSBase, Boolean> seen) {
        if (!(resBase instanceof COSDictionary res) || seen.put(res, Boolean.TRUE) != null) return;
        if (!(res.getDictionaryObject(COSName.XOBJECT) instanceof COSDictionary xd)) return;
        for (COSName name : xd.keySet()) {
            COSBase item = xd.getItem(name);
            long objNum = (item instanceof COSObject co) ? co.getObjectNumber() : -1;
            if (!(xd.getDictionaryObject(name) instanceof COSStream s)) continue;
            String sub = (s.getDictionaryObject(COSName.SUBTYPE) instanceof COSName sn) ? sn.getName() : "";
            if ("Image".equals(sub)) {
                if (objNum >= 0) { imgs.putIfAbsent(objNum, s); use.computeIfAbsent(objNum, k -> new java.util.TreeSet<>()).add(page); }
            } else if ("Form".equals(sub)) {
                COSBase fr = s.getDictionaryObject(COSName.RESOURCES);
                if (fr != null) collectImages(fr, page, imgs, use, seen);
            }
        }
    }

    /** A short colorspace label: a named space (/DeviceRGB…) or the family of an array space ([/ICCBased…]). */
    private static String csLabel(COSBase cs) {
        if (cs instanceof COSName n) return n.getName();
        if (cs instanceof COSArray a && a.size() > 0 && a.get(0) instanceof COSName n) return n.getName();
        return cs == null ? "" : "array";
    }

    /** The decode filter(s): a single /Filter or a comma-joined array. */
    private static String filterLabel(COSBase f) {
        if (f instanceof COSName n) return n.getName();
        if (f instanceof COSArray a) {
            var parts = new java.util.ArrayList<String>();
            for (COSBase b : a) if (b instanceof COSName n) parts.add(n.getName());
            return String.join(",", parts);
        }
        return "";
    }

    // ── API: text extraction of a page → JSON ───────────────────────────────
    private void text(HttpExchange x) throws IOException {
        try {
            if (doc == null) { error(x, new IllegalStateException("no document")); return; }
            String t;
            synchronized (pdfLock) {              // PDFTextStripper decodes the shared PDDocument's streams
                int i = (int) Math.max(0, Math.min(doc.getNumberOfPages() - 1, longParam(x, "page", 0)));
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                t = stripper.getText(doc);
            }
            json(x, "{\"text\":" + jstr(t) + "}");
        } catch (Exception e) { error(x, e); }
    }

    // ── COS → JSON tree ─────────────────────────────────────────────────────
    /** Per-request walk state: identity-visited set + a global node budget. */
    private static final class CosCtx {
        final IdentityHashMap<COSBase, Boolean> seen = new IdentityHashMap<>();
        int budget = MAX_NODES;
    }

    /** Emit the key→value entries of a dictionary as sibling nodes. */
    private static void cosEntries(StringBuilder sb, COSDictionary d, int depth, CosCtx ctx) {
        boolean first = true;
        for (COSName key : d.keySet()) {
            if (ctx.budget <= 0) break;
            if (!first) sb.append(',');
            first = false;
            // /Parent would climb back to the page tree (and the whole doc) — show it flat.
            boolean flatten = COSName.PARENT.equals(key);
            cosNode(sb, "/" + key.getName(), d.getItem(key), depth, ctx, flatten);
        }
    }

    /** One node for {@code value}, optionally labelled with the dictionary key it sits under. */
    @SuppressWarnings("deprecation")  // COSObject has no non-deprecated key accessor in PDFBox 3.0.7
    private static void cosNode(StringBuilder sb, String key, COSBase value, int depth, CosCtx ctx, boolean flatten) {
        ctx.budget--;
        COSBase v = value;
        boolean indirect = v instanceof COSObject;
        String ref = "";
        if (indirect) {
            COSObject o = (COSObject) v;
            ref = " " + cosRef(o);              // e.g. " 12 0 R"
            try { v = o.getObject(); } catch (Exception e) { v = null; }
        }

        String type = cosType(v);
        String label = (key == null ? "" : key + "  ") + cosLabel(v) + ref;
        String detail = (key == null ? "" : key + "\n") + "type: " + type
                + (indirect ? "\nobject: " + cosRef((COSObject) value) : "")
                + cosDetail(v);

        sb.append("{\"type\":").append(jstr(type))
                .append(",\"label\":").append(jstr(label))
                .append(",\"detail\":").append(jstr(detail));
        if (indirect) sb.append(",\"obj\":").append(((COSObject) value).getObjectNumber());
        if (v instanceof COSStream st && st.getDictionaryObject(COSName.SUBTYPE) instanceof COSName sn
                && "Image".equals(sn.getName())) sb.append(",\"img\":true");

        boolean tooDeep = depth >= MAX_DEPTH;
        boolean cycle = v != null && ctx.seen.containsKey(v);
        boolean container = (v instanceof COSDictionary) || (v instanceof COSArray a && arrayInline(a) == null);

        if (container && !flatten && !tooDeep && !cycle && ctx.budget > 0) {
            ctx.seen.put(v, Boolean.TRUE);
            sb.append(",\"children\":[");
            if (v instanceof COSStream stream) {
                cosEntries(sb, stream, depth + 1, ctx);    // stream is a dictionary too
            } else if (v instanceof COSDictionary dict) {
                cosEntries(sb, dict, depth + 1, ctx);
            } else {
                COSArray arr = (COSArray) v;
                int n = arr.size();
                for (int i = 0; i < n; i++) {
                    if (ctx.budget <= 0) break;
                    if (i > 0) sb.append(',');
                    cosNode(sb, "[" + i + "]", arr.get(i), depth + 1, ctx, false);
                }
            }
            sb.append(']');
        } else if (container && (cycle || tooDeep) && !flatten) {
            // mark that there is more, without expanding
            sb.append(",\"children\":[{\"type\":\"more\",\"label\":")
                    .append(jstr(cycle ? "\u2192 (already shown above)" : "\u2192 (\u2026 depth limit)"))
                    .append(",\"detail\":\"\"}]");
        }
        sb.append('}');
    }

    /** PDF indirect-reference syntax "N G R" for an indirect object. */
    @SuppressWarnings("deprecation")   // PDFBox 3.0.x exposes no public COSObjectKey accessor
    private static String cosRef(COSObject o) {
        return o.getObjectNumber() + " " + o.getGenerationNumber() + " R";
    }

    private static String cosType(COSBase v) {
        if (v == null)                 return "null";
        if (v instanceof COSStream)    return "stream";
        if (v instanceof COSDictionary) return "dict";
        if (v instanceof COSArray)     return "array";
        if (v instanceof COSName)      return "name";
        if (v instanceof COSString)    return "string";
        if (v instanceof COSInteger)   return "int";
        if (v instanceof COSFloat)     return "real";
        if (v instanceof COSBoolean)   return "bool";
        if (v instanceof COSNull)      return "null";
        return v.getClass().getSimpleName();
    }

    private static String cosLabel(COSBase v) {
        if (v == null)                  return "null";
        if (v instanceof COSStream s)   { long len = s.getLength(); return "stream (" + len + " B) " + dictHead(s); }
        if (v instanceof COSDictionary d) return dictHead(d);
        if (v instanceof COSArray a)    { String inl = arrayInline(a); return inl != null ? inl : "[ " + a.size() + " ]"; }
        if (v instanceof COSName n)     return "/" + n.getName();
        if (v instanceof COSString s)   return "(" + abbrev(s.getString(), 40) + ")";
        if (v instanceof COSInteger i)  return Long.toString(i.longValue());
        if (v instanceof COSFloat f)    return fmt(f.floatValue());
        if (v instanceof COSBoolean b)  return Boolean.toString(b.getValue());
        if (v instanceof COSNull)       return "null";
        return abbrev(String.valueOf(v), 40);
    }

    /** Short "<< /Type … /Subtype … >>" header for a dictionary. */
    private static String dictHead(COSDictionary d) {
        COSBase t  = d.getDictionaryObject(COSName.TYPE);
        COSBase st = d.getDictionaryObject(COSName.SUBTYPE);
        StringBuilder h = new StringBuilder("<<");
        if (t instanceof COSName tn)  h.append(" /").append(tn.getName());
        if (st instanceof COSName sn) h.append(" /").append(sn.getName());
        h.append(t == null && st == null ? " " + d.keySet().size() + " entries >>" : " >>");
        return h.toString();
    }

    private static String cosDetail(COSBase v) {
        if (v instanceof COSStream s) {
            COSBase filters = s.getFilters();
            return "\nlength: " + s.getLength() + (filters != null ? "\nfilter: " + cosLabel(filters) : "");
        }
        if (v instanceof COSString s) return "\nvalue: " + s.getString();
        return "";
    }

    /** Locate the stream of indirect object {@code objNum} by a cycle-guarded DFS from the trailer. */
    @SuppressWarnings("deprecation")  // COSObject has no non-deprecated key accessor in PDFBox 3.0.7
    private static COSStream findStream(COSBase node, long objNum, IdentityHashMap<COSBase, Boolean> seen) {
        if (node == null) return null;
        COSBase v = node;
        if (node instanceof COSObject o) {
            COSBase t; try { t = o.getObject(); } catch (Exception e) { t = null; }
            if (o.getObjectNumber() == objNum && t instanceof COSStream s) return s;
            v = t;
        }
        if (v == null || seen.put(v, Boolean.TRUE) != null) return null;   // null or already visited
        if (v instanceof COSDictionary d) {
            for (COSName k : d.keySet()) {
                COSStream r = findStream(d.getItem(k), objNum, seen);
                if (r != null) return r;
            }
        } else if (v instanceof COSArray a) {
            for (int i = 0; i < a.size(); i++) {
                COSStream r = findStream(a.get(i), objNum, seen);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ── app-specific tiny helpers ─────────────────────────────────────────────
    private static String safe(String s) { return s == null ? "" : s; }
    private static String fmt(double v)  { return String.format("%.2f", v); }

    /** A short, all-scalar array rendered inline (e.g. "[ 0 0 595.28 841.89 ]"); null if it should
     *  stay expandable — a long array, or one holding references / dictionaries / nested arrays. */
    private static String arrayInline(COSArray a) {
        int n = a.size();
        if (n == 0) return "[ ]";
        if (n > 24) return null;
        StringBuilder b = new StringBuilder("[ ");
        for (int i = 0; i < n; i++) {
            COSBase e = a.get(i);
            if (!isScalar(e)) return null;     // ref / dict / nested array → keep it drillable
            if (i > 0) b.append(' ');
            b.append(cosArg(e));
            if (b.length() > 160) return null; // too wide for a label → keep it expandable
        }
        return b.append(" ]").toString();
    }

    private static boolean isScalar(COSBase v) {
        return v instanceof COSInteger || v instanceof COSFloat || v instanceof COSName
            || v instanceof COSBoolean || v instanceof COSString || v instanceof COSNull;
    }

    /** Compact one operand for the content-stream listing (e.g. /F1, 12, (Hi), [ 3 ]). */
    private static String cosArg(COSBase v) {        if (v instanceof COSName n)     return "/" + n.getName();
        if (v instanceof COSString s)   return "(" + abbrev(s.getString(), 30) + ")";
        if (v instanceof COSInteger i)  return Long.toString(i.longValue());
        if (v instanceof COSFloat f)    return fmt(f.floatValue());
        if (v instanceof COSBoolean b)  return Boolean.toString(b.getValue());
        if (v instanceof COSArray a)    return "[ " + a.size() + " ]";
        if (v instanceof COSDictionary) return "<< … >>";
        return cosType(v);
    }

    /** Heuristic: treat the stream as text when the bytes are overwhelmingly printable / whitespace. */
    private static boolean isMostlyPrintable(byte[] b) {
        if (b.length == 0) return true;
        int bad = 0, lim = Math.min(b.length, 4096);
        for (int i = 0; i < lim; i++) {
            int c = b[i] & 0xFF;
            boolean ok = c == 9 || c == 10 || c == 13 || (c >= 32 && c < 127) || c >= 160;
            if (!ok) bad++;
        }
        return bad * 100 < lim * 8;   // < 8 % control bytes → text
    }

    /** Classic offset | hex | ascii dump, for binary streams (font programs, images…). */
    private static String hexDump(byte[] b) {
        var sb = new StringBuilder();
        for (int off = 0; off < b.length; off += 16) {
            sb.append(String.format("%08x  ", off));
            var ascii = new StringBuilder();
            for (int j = 0; j < 16; j++) {
                if (off + j < b.length) {
                    int c = b[off + j] & 0xFF;
                    sb.append(String.format("%02x ", c));
                    ascii.append(c >= 32 && c < 127 ? (char) c : '.');
                } else sb.append("   ");
                if (j == 7) sb.append(' ');
            }
            sb.append(" |").append(ascii).append("|\n");
        }
        return sb.toString();
    }
}
