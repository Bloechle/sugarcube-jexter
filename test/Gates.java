// Gates — the engine's functional gate: every check, over every PDF given, in one JVM. CI runs it on push.
//
//   REPRO     same input converted twice is byte-identical, for every target (pin SOURCE_DATE_EPOCH).
//   DOCTAGS   pdf -> doctags == pdf -> ocd -> doctags: a projection must not depend on the route.
//   STABLE    write(read(x)) == x, byte for byte. A drift is a diagnosis away: run ContainerDiff.
//   MODEL     read(write(m)) == m field by field, and paint order kept. Bytes agree when a field is
//             written wrong and read back wrong the same way; this is the gate that sees it.
//   TIERS     readMembers / readPage / pageCount give what read gives, page for page.
//   STRADDLE  no paragraph wraps a paint span another node sits inside (it could not be stored atomically).
//   CLIPS     every clip reference resolves, on the imported model and on the model read back.
//   COPY      a page and its copy project identically, and editing the copy leaves the page alone.
//   EDITED    a path a viewer moved (Prism's editor composes the move in front of the page flip)
//             reads back moved — once, it was read back where it started.
//   HOSTILE   broken and malicious variants of the container: refused with context, or read with a
//             warning — never read in silence, never a leaked file, never an entity expansion.
//
//   node nimbus-jexter.cjs --run=test     the whole run, on any OS: compile src/ and test/, corpus, every gate
//
// With no PDF named, the corpus is written first into target/corpus by Fixtures (PDFBox, nothing else);
// it is produced, so it is never committed. Name PDFs to gate those instead:
//   java -cp "target/test-classes:target/classes:lib/*" Gates a.pdf …   (SOURCE_DATE_EPOCH set; `;` on Windows)

import sugarcube.jexter.Conversion;
import sugarcube.jexter.ocd.io.SvgOcdWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxStroke;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.analysis.Analysis;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.io.OCDVocab;
import sugarcube.jexter.ocd.model.*;
import sugarcube.jexter.ocd.render.OCDRenderer;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.pdfimport.PdfRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import java.util.logging.*;
import java.util.zip.*;

public final class Gates {

    private static final String[] TARGETS = { "ocd", "epub", "epub-reflow", "pdf", "html", "md", "doctags", "svg" };
    private static final double TOLERANCE = 1e-3;        // export precision is 4 decimals
    private static final String PAGE = "page-001.xhtml", FONTS = "pages/fonts.svg";

    private static final List<String> warnings = new ArrayList<>();
    private static String current;          // the PDF being gated, printed once if one of its gates fails
    private static boolean reported;
    private static final Logger READER = Logger.getLogger(OCDReader.class.getName());   // held: JUL keeps loggers weakly
    private static int failed, checks;

    public static void main(String[] args) throws Exception {
        if (System.getenv("SOURCE_DATE_EPOCH") == null) {      // an unpinned clock alone fails REPRO
            System.err.println("SOURCE_DATE_EPOCH is not set - run `node nimbus-jexter.cjs --run=test`, or set it (e.g. 1000000000)");
            System.exit(2);
        }
        captureWarnings();                                     // before the corpus: PDFBox logs while writing it too
        if (args.length == 0) args = corpus();
        Path tmp = Files.createTempDirectory("gates");
        for (String a : args) {
            File pdf = new File(a);
            current = pdf.getName();
            reported = false;
            byte[] src = Files.readAllBytes(pdf.toPath());
            byte[] ocd = to(src, "ocd");
            Path container = tmp.resolve(pdf.getName() + ".ocd.epub");
            Files.write(container, ocd);

            gate("REPRO",    () -> repro(src));
            gate("DOCTAGS",  () -> Arrays.equals(to(src, "doctags"), to(ocd, "doctags")) ? ok() : List.of("routes differ"));
            gate("STABLE",   () -> Arrays.equals(ocd, to(ocd, "ocd")) ? ok() : List.of("drift - ContainerDiff " + container));
            OCDDocument model = PdfImporter.convert(pdf);
            gate("MODEL",    () -> model(model, tmp.resolve(pdf.getName() + ".model.ocd.epub")));
            gate("TIERS",    () -> tiers(container.toFile()));
            gate("NAV",      () -> nav(pdf, model));
            gate("TAGS",     () -> tags(pdf, model));
            gate("PIXELS",   () -> pixels(pdf, model));
            gate("FURNITURE",() -> furniture(model));
            gate("AGAIN",    () -> again(pdf));
            gate("STRADDLE", () -> straddle(model));
            gate("CLIPS",    () -> clips(model, OCDReader.read(container.toFile())));
            gate("COPY",     () -> copies(OCDReader.read(container.toFile())));
            gate("EDITED",   () -> edited(ocd, tmp));
            gate("HOSTILE",  () -> hostile(ocd, tmp));
        }
        System.out.println(failed == 0 ? "ALL GATES GREEN: " + checks + " checks on " + args.length + " PDFs" : failed + " GATE(S) FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── the corpus ───────────────────────────────────────────────────────────────

    private static final Path CORPUS = Path.of("target", "corpus");
    private static final String WELCOME = "src/sugarcube/jexter/ui/pdf/web/welcome.pdf";

    /** Writes the fixtures into target/corpus; returns them, plus the app's welcome page (a real document). */
    private static String[] corpus() throws Exception {
        if (!Files.exists(Path.of(WELCOME))) throw new IllegalStateException("run from the repo root: no " + WELCOME);
        if (Files.exists(CORPUS)) try (var walk = Files.walk(CORPUS)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        List<String> pdfs = new ArrayList<>(Fixtures.write(CORPUS).stream().map(Path::toString).toList());
        pdfs.add(WELCOME);
        return pdfs.toArray(String[]::new);
    }

    // ── the runner ───────────────────────────────────────────────────────────────

    private interface Check { List<String> run() throws Exception; }

    private static void gate(String name, Check c) {
        List<String> problems;
        checks++;
        try { problems = c.run(); }
        catch (Throwable t) { problems = List.of("threw " + t); }
        if (problems.isEmpty()) return;
        failed++;
        if (!reported) { System.out.println(current); reported = true; }
        System.out.printf("   %-9s FAILED%n", name);
        problems.stream().limit(12).forEach(p -> System.out.println("      " + p));
        if (problems.size() > 12) System.out.println("      … " + (problems.size() - 12) + " more");
    }

    private static List<String> ok() { return List.of(); }

    private static byte[] to(byte[] in, String target) throws Exception {
        return Conversion.convert(in, target, Map.<String, String>of()).bytes();
    }

    // ── REPRO ────────────────────────────────────────────────────────────────────

    private static List<String> repro(byte[] src) throws Exception {
        List<String> bad = new ArrayList<>();
        for (String t : TARGETS) if (!Arrays.equals(to(src, t), to(src, t))) bad.add(t + " is not reproducible");
        return bad;
    }

    // ── MODEL ────────────────────────────────────────────────────────────────────

    private static List<String> model(OCDDocument src, Path file) throws Exception {
        Files.write(file, Conversion.convert(src, "ocd", Map.<String, String>of()).bytes());
        OCDDocument back = OCDReader.read(file.toFile());
        List<String> bad = new ArrayList<>();
        if (src.pageCount() != back.pageCount()) bad.add("pages " + src.pageCount() + " -> " + back.pageCount());
        if (src.fonts().size() != back.fonts().size()) bad.add("fonts " + src.fonts().size() + " -> " + back.fonts().size());
        for (var f : src.fonts().values())                            // a cmap entry names the glyph OF that character
            for (var e : f.cmap().entrySet()) {
                var g = f.glyph(e.getValue());
                String ch = new String(Character.toChars(e.getKey()));
                if (g == null || !ch.equals(g.unicode())) bad.add(f.id() + " maps '" + ch + "' to glyph " + e.getValue() + (g == null ? "" : " (" + g.unicode() + ")"));
            }
        for (String ext : List.of("png", "jpg")) {                    // a document read back already holds names
            String fresh = back.newImageRef(ext);
            if (back.images().containsKey(fresh)) bad.add("a new image ref collides with a stored one: " + fresh);
        }
        if (src.structures().size() != back.structures().size())
            bad.add("structures " + src.structures().size() + " -> " + back.structures().size());
        for (int p = 0; p < Math.min(src.pageCount(), back.pageCount()); p++) {
            bad.addAll(samePage("p" + (p + 1), src.page(p), back.page(p), true));
            if (!paintRank(src.page(p)).equals(paintRank(back.page(p)))) bad.add("p" + (p + 1) + " paint order changed");
        }
        return bad;
    }

    /** Every node of {@code a} found in {@code b} with the same fields. {@code fromImport}: the two
     *  documented write-time drops (a render-transparent OCDGraphic wrapper, a path with no geometry). */
    private static List<String> samePage(String where, OCDPage a, OCDPage b, boolean fromImport) {
        List<String> bad = new ArrayList<>();
        Map<String, OCDNode> A = byId(a), B = byId(b);
        for (var e : A.entrySet()) {
            OCDNode x = e.getValue(), y = B.get(e.getKey());
            if (y == null) {
                if (fromImport && (x instanceof OCDGraphic || x instanceof OCDPath p && isEmpty(p))) continue;
                bad.add(where + " lost " + e.getKey() + " (" + x.getClass().getSimpleName() + ")");
            } else for (String d : fields(x, y)) bad.add(where + " " + e.getKey() + " " + d);
        }
        for (String k : B.keySet()) if (!A.containsKey(k)) bad.add(where + " gained " + k);
        if (a.clips().size() != b.clips().size()) bad.add(where + " clips " + a.clips().size() + " -> " + b.clips().size());
        if (a.links().size() != b.links().size()) bad.add(where + " links " + a.links().size() + " -> " + b.links().size());
        return bad;
    }

    private static List<String> fields(OCDNode x, OCDNode y) {
        List<String> d = new ArrayList<>();
        if (x.getClass() != y.getClass()) d.add("class " + x.getClass().getSimpleName() + " -> " + y.getClass().getSimpleName());
        diff(d, "role", nz(x.role()), nz(y.role()));
        diff(d, "name", nz(x.name()), nz(y.name()));
        diff(d, "blend", nz(x.blend()), nz(y.blend()));
        diff(d, "clip", nz(x.clipId()), nz(y.clipId()));
        if (Math.abs(x.alpha() - y.alpha()) > TOLERANCE) d.add("alpha " + x.alpha() + " -> " + y.alpha());
        if (delta(x.transform(), y.transform()) > TOLERANCE) d.add("transform " + x.transform() + " -> " + y.transform());
        if (x instanceof OCDText a && y instanceof OCDText b) {
            diff(d, "text", a.text(), b.text());
            diff(d, "font", a.fontId(), b.fontId());
            if (Math.abs(a.fontSize() - b.fontSize()) > TOLERANCE) d.add("fontSize " + a.fontSize() + " -> " + b.fontSize());
            if (a.renderMode() != b.renderMode()) d.add("renderMode " + a.renderMode() + " -> " + b.renderMode());
            if (a.fill() != b.fill()) d.add(String.format("fill %08X -> %08X", a.fill(), b.fill()));
            if (a.paintsStroke() != b.paintsStroke() || a.paintsStroke() && a.stroke() != b.stroke())
                d.add(String.format("stroke %08X -> %08X", a.stroke(), b.stroke()));
            if (a.paintsStroke() && !samePen(a.pen(), b.pen())) d.add("pen " + a.pen() + " -> " + b.pen());
        }
        if (x instanceof OCDPath a && y instanceof OCDPath b) {
            if (a.fill() != b.fill()) d.add(String.format("fill %08X -> %08X", a.fill(), b.fill()));
            if (a.stroke() != b.stroke()) d.add(String.format("stroke %08X -> %08X", a.stroke(), b.stroke()));
            if (a.isStroked() && !samePen(a.pen(), b.pen())) d.add("pen " + a.pen() + " -> " + b.pen());
            if (!sameGradient(a.fillGradient(), b.fillGradient())) d.add("gradient");
            if (!a.geometry().toSvg().equals(b.geometry().toSvg())) d.add("geometry");
        }
        return d;
    }

    /** Equal at export precision: the container writes four decimals. */
    private static boolean samePen(JxStroke a, JxStroke b) {
        double[] x = a.dash(), y = b.dash();
        if ((x == null) != (y == null) || x != null && x.length != y.length) return false;
        if (x != null) for (int i = 0; i < x.length; i++) if (Math.abs(x[i] - y[i]) > TOLERANCE) return false;
        return a.cap() == b.cap() && a.join() == b.join() && Math.abs(a.width() - b.width()) <= TOLERANCE
                && Math.abs(a.miterLimit() - b.miterLimit()) <= TOLERANCE && Math.abs(a.dashPhase() - b.dashPhase()) <= TOLERANCE;
    }

    /** Same kind, both circles or the axis, both extends, the matrix and the stops — at export precision. */
    private static boolean sameGradient(OCDGradient a, OCDGradient b) {
        if (a == null || b == null) return a == b;
        if (a.kind() != b.kind() || a.extend0() != b.extend0() || a.extend1() != b.extend1()
                || a.coords().length != b.coords().length || a.offsets().length != b.offsets().length
                || delta(a.transform(), b.transform()) > TOLERANCE) return false;
        for (int i = 0; i < a.coords().length; i++) if (Math.abs(a.coords()[i] - b.coords()[i]) > TOLERANCE) return false;
        for (int i = 0; i < a.offsets().length; i++)
            if (Math.abs(a.offsets()[i] - b.offsets()[i]) > TOLERANCE || a.colors()[i] != b.colors()[i]) return false;
        return true;
    }

    private static void diff(List<String> out, String what, Object a, Object b) {
        if (!Objects.equals(a, b)) out.add(what + " " + a + " -> " + b);
    }

    /** z values are re-derived on read; their RANK is the paint order and must not move. */
    private static List<String> paintRank(OCDPage p) {
        return p.nodes().filter(n -> n.id() != null && !n.id().isEmpty() && !(n instanceof OCDGraphic))
                .sorted(Comparator.comparingDouble(OCDNode::z)).map(OCDNode::id).toList();
    }

    // ── TIERS ────────────────────────────────────────────────────────────────────

    private static List<String> tiers(File file) throws Exception {
        OCDDocument full = OCDReader.read(file);
        List<String> bad = new ArrayList<>();
        if (OCDReader.pageCount(file) != full.pageCount()) bad.add("pageCount " + OCDReader.pageCount(file) + " != " + full.pageCount());
        OCDDocument members = OCDReader.readMembers(file);
        if (members.pageCount() != 0) bad.add("readMembers parsed " + members.pageCount() + " page(s)");
        if (!members.fonts().keySet().equals(full.fonts().keySet())) bad.add("readMembers fonts differ");
        for (int i = 0; i < full.pageCount(); i++) {
            OCDDocument one = OCDReader.readPage(file, i);
            if (one.pageCount() != 1) { bad.add("readPage(" + i + ") holds " + one.pageCount() + " pages"); continue; }
            bad.addAll(samePage("readPage(" + i + ")", full.page(i), one.page(0), false));
        }
        return bad;
    }

    // ── STRADDLE ─────────────────────────────────────────────────────────────────

    // ── NAV ──────────────────────────────────────────────────────────────────────

    /** Every bookmark and every internal link lands where PDFBox resolves it — an explicit destination, a name
     *  in {@code /Names /Dests}, a name in the catalog's {@code /Dests}, or a GoTo action carrying any of them. */
    private static List<String> nav(File pdf, OCDDocument model) throws Exception {
        List<String> bad = new ArrayList<>();
        try (PDDocument src = Loader.loadPDF(pdf)) {
            var outline = src.getDocumentCatalog().getDocumentOutline();
            List<String> want = new ArrayList<>(), got = new ArrayList<>();
            if (outline != null) for (PDOutlineItem it : outline.children()) bookmarks(src, it, want);
            for (OCDOutline o : model.outline()) bookmarks(o, got);
            if (!want.equals(got)) bad.add("outline " + got + ", PDFBox says " + want);
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                List<Integer> pages = new ArrayList<>();
                for (var an : src.getPage(i).getAnnotations())
                    if (an instanceof PDAnnotationLink lk && !(lk.getAction() instanceof PDActionURI)) {
                        PDDestination d = lk.getDestination();
                        if (d == null && lk.getAction() instanceof PDActionGoTo go) d = go.getDestination();
                        if (d instanceof PDNamedDestination n) d = src.getDocumentCatalog().findNamedDestinationPage(n);
                        if (d instanceof PDPageDestination pd && pd.retrievePageNumber() >= 0) pages.add(pd.retrievePageNumber());
                    }
                List<Integer> mine = model.page(i).links().stream().filter(l -> !l.isExternal()).map(OCDLink::pageIndex).toList();
                if (!pages.equals(mine)) bad.add("page " + (i + 1) + " links to " + mine + ", PDFBox says " + pages);
            }
        }
        return bad;
    }

    private static void bookmarks(PDDocument src, PDOutlineItem it, List<String> out) throws Exception {
        PDPage p = it.findDestinationPage(src);
        out.add(it.getTitle() + "@" + (p == null ? -1 : src.getPages().indexOf(p)));
        for (PDOutlineItem c : it.children()) bookmarks(src, c, out);
    }

    private static void bookmarks(OCDOutline o, List<String> out) {
        out.add(o.title() + "@" + o.pageIndex());
        for (OCDOutline c : o.children()) bookmarks(c, out);
    }

    // ── PIXELS ───────────────────────────────────────────────────────────────────

    /** The model paints what PDFBox paints: at 72 dpi, without annotations, under 1 % of a page's pixels may
     *  differ by more than a quarter of the range on any channel (antialiasing and font hinting stay well
     *  under it). The direct renderer — our painting straight from the PDF, fed what the importer is fed —
     *  must agree with the model's render to 0.25 %: a gap there is lost in the model, not in the painting. */
    private static List<String> pixels(File pdf, OCDDocument model) throws Exception {
        List<String> bad = new ArrayList<>();
        try (PDDocument src = Loader.loadPDF(pdf)) {
            PDFRenderer reference = new PDFRenderer(src);
            reference.setAnnotationsFilter(a -> false);
            for (int i = 0; i < src.getNumberOfPages(); i++) {
                BufferedImage ocd = OCDRenderer.render(model.page(i), model, 72, false);
                double ref = differs(reference.renderImage(i, 1f), ocd), direct = differs(PdfRenderer.render(src, i, 72), ocd);
                if (ref > 1)     bad.add(String.format("page %d: %.2f %% of pixels differ from PDFBox", i + 1, ref));
                if (direct > 0.25) bad.add(String.format("page %d: %.2f %% of pixels differ from the direct render", i + 1, direct));
            }
        }
        return bad;
    }

    /** Percentage of pixels (over the common area) whose largest channel difference exceeds 64. */
    private static double differs(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        long n = 0;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int p = a.getRGB(x, y), q = b.getRGB(x, y);
            for (int s = 0; s < 24; s += 8) if (Math.abs(((p >> s) & 255) - ((q >> s) & 255)) > 64) { n++; break; }
        }
        return 100.0 * n / Math.max(1, (long) w * h);
    }

    // ── AGAIN ────────────────────────────────────────────────────────────────────

    /** Reading the analysis leaves the document as it was, and running it again finds the same document: the
     *  stages export changes nothing, a second {@code Analysis.run} writes the same container byte for byte,
     *  and Prism's "restructure text" keeps every heading role. */
    private static List<String> again(File pdf) throws Exception {
        List<String> bad = new ArrayList<>();
        OCDDocument d = PdfImporter.convert(pdf);
        byte[] first = Conversion.convert(d, "ocd", Map.<String, String>of()).bytes();
        Conversion.convert(d, "stages", Map.<String, String>of());
        if (!Arrays.equals(first, Conversion.convert(d, "ocd", Map.<String, String>of()).bytes())) bad.add("the stages export edited the document");
        Analysis.run(d, ConvertOptions.defaults());
        if (!Arrays.equals(first, Conversion.convert(d, "ocd", Map.<String, String>of()).bytes())) bad.add("a second analysis changed the container");
        List<String> roles = headingRoles(d);
        Analysis.restructureText(d, ConvertOptions.defaults());
        if (!roles.equals(headingRoles(d))) bad.add("restructure text: heading roles " + roles + " -> " + headingRoles(d));
        return bad;
    }

    private static List<String> headingRoles(OCDDocument d) {
        List<String> out = new ArrayList<>();
        for (int p = 0; p < d.pageCount(); p++)
            d.page(p).nodes().filter(OCDParagraph.class::isInstance).map(OCDParagraph.class::cast)
             .filter(q -> q.role() != null && q.role().startsWith("heading-")).forEach(q -> out.add(q.role()));
        return out;
    }

    // ── FURNITURE ────────────────────────────────────────────────────────────────

    /** Running heads and folios stay on the page and out of every structure the writers read. */
    private static List<String> furniture(OCDDocument model) {
        List<String> bad = new ArrayList<>();
        for (OCDStructure st : model.structures()) {
            if ("pdf".equals(st.id())) continue;                      // the author's tags say what they say
            List<String> hits = new ArrayList<>();
            furnitureRefs(st.root(), model, hits);
            if (!hits.isEmpty()) bad.add(st.id() + " reads running furniture: " + hits);
        }
        return bad;
    }

    private static void furnitureRefs(OCDStructNode n, OCDDocument model, List<String> hits) {
        for (var r : n.refs()) {
            OCDNode x = model.page(r.page()).nodes().filter(q -> r.nodeId().equals(q.id())).findFirst().orElse(null);
            if (x != null && x.stream().filter(OCDText.class::isInstance).map(OCDText.class::cast)
                    .filter(t -> t.text() != null && !t.text().isBlank())
                    .allMatch(t -> "page-header".equals(t.role()) || "page-footer".equals(t.role()))
                    && x.stream().anyMatch(OCDText.class::isInstance)) hits.add(r.nodeId());
        }
        for (OCDStructNode c : n.children()) furnitureRefs(c, model, hits);
    }

    // ── TAGS ─────────────────────────────────────────────────────────────────────

    /** A tagged PDF's tree lands as the author wrote it: every heading, paragraph, list and item, typed by its
     *  STANDARD type (the RoleMap followed), an LBody folded into its item, each with content when it marks
     *  some — and no content claimed by two elements (a form's MCIDs are not the page's). */
    private static List<String> tags(File pdf, OCDDocument model) throws Exception {
        List<String> bad = new ArrayList<>();
        try (PDDocument src = Loader.loadPDF(pdf)) {
            var root = src.getDocumentCatalog().getStructureTreeRoot();
            if (root == null || root.getKids().isEmpty()) return bad;
            OCDStructure pdfTree = model.structureById("pdf");
            if (pdfTree == null) return List.of("tagged, but no \"pdf\" structure");
            List<String> want = new ArrayList<>(), got = new ArrayList<>();
            for (Object k : root.getKids()) if (k instanceof PDStructureElement e) tokens(e, want);
            tokens(pdfTree.root(), got);
            if (!want.equals(got)) bad.add("tree " + got + ", source " + want);
            var seen = new HashSet<OCDStructNode.Ref>();
            refs(pdfTree.root(), seen, bad);
            if (!pdfTree.id().equals(model.defaultStructureId())) bad.add("default structure is " + model.defaultStructureId() + ", not the PDF's own");
            if (model.outline().isEmpty()) headingRoles(pdfTree.root(), model, bad);   // no nav to win: the tags decide
        }
        return bad;
    }

    /** Every paragraph a PDF/UA heading references carries that heading's role. */
    private static void headingRoles(OCDStructNode n, OCDDocument model, List<String> bad) {
        if (n.type() == OCDStructNode.Type.HEADING)
            for (var r : n.refs()) {
                String want = "heading-" + Math.max(1, Math.min(6, n.level()));
                OCDParagraph p = model.page(r.page()).nodes().filter(OCDParagraph.class::isInstance).map(OCDParagraph.class::cast)
                        .filter(q -> r.nodeId().equals(q.id()) || q.children().stream().anyMatch(c -> r.nodeId().equals(c.id())))
                        .findFirst().orElse(null);
                if (p != null && !want.equals(p.role())) bad.add("heading " + r.nodeId() + " has role " + p.role() + ", the tags say " + want);
            }
        for (OCDStructNode c : n.children()) headingRoles(c, model, bad);
    }

    private static void tokens(PDStructureElement e, List<String> out) {
        String t = e.getStandardStructureType();
        if (t != null && t.matches("H[1-6]?|P|L|LI")) out.add(t + (marks(e) ? "*" : ""));
        for (Object k : e.getKids())
            if (k instanceof PDStructureElement c && !"LBody".equals(c.getStandardStructureType())) tokens(c, out);
            else if (k instanceof PDStructureElement body) for (Object kk : body.getKids()) if (kk instanceof PDStructureElement c) tokens(c, out);
    }

    /** Whether the element marks content of its own — directly, or through its LBody. */
    private static boolean marks(PDStructureElement e) {
        for (Object k : e.getKids())
            if (k instanceof Integer || k instanceof PDMarkedContentReference) return true;
            else if (k instanceof PDStructureElement c && "LBody".equals(c.getStandardStructureType()) && marks(c)) return true;
        return false;
    }

    private static void tokens(OCDStructNode n, List<String> out) {
        String t = switch (n.type()) {
            case HEADING   -> n.level() > 0 ? "H" + n.level() : "H";
            case PARAGRAPH -> "P";
            case LIST      -> "L";
            case ITEM      -> "LI";
            default        -> null;
        };
        if (t != null) out.add(t + (n.refs().isEmpty() ? "" : "*"));
        for (OCDStructNode c : n.children()) tokens(c, out);
    }

    private static void refs(OCDStructNode n, Set<OCDStructNode.Ref> seen, List<String> bad) {
        for (var r : n.refs()) if (!seen.add(r)) bad.add("node " + r.nodeId() + " on page " + (r.page() + 1) + " claimed twice");
        for (OCDStructNode c : n.children()) refs(c, seen, bad);
    }

    private static List<String> straddle(OCDDocument doc) {
        List<String> bad = new ArrayList<>();
        for (int p = 0; p < doc.pageCount(); p++) {
            List<OCDNode> all = doc.page(p).nodes().toList();
            for (OCDNode n : all) {
                if (!(n instanceof OCDParagraph para)) continue;
                Set<OCDNode> mine = Collections.newSetFromMap(new IdentityHashMap<>());
                para.stream().forEach(mine::add);
                double lo = mine.stream().filter(k -> k != para && !(k instanceof OCDBreak)).mapToDouble(OCDNode::z).min().orElse(0);
                double hi = mine.stream().filter(k -> k != para && !(k instanceof OCDBreak)).mapToDouble(OCDNode::z).max().orElse(0);
                for (OCDNode o : all)
                    if (!mine.contains(o) && !(o instanceof OCDParagraph) && !(o instanceof OCDBreak) && o.z() > lo && o.z() < hi)
                        bad.add("p" + (p + 1) + " " + para.id() + " spans z " + lo + ".." + hi + " over " + o.id());
            }
        }
        return bad;
    }

    // ── CLIPS ────────────────────────────────────────────────────────────────────

    private static List<String> clips(OCDDocument... docs) {
        List<String> bad = new ArrayList<>();
        for (OCDDocument doc : docs)
            for (int p = 0; p < doc.pageCount(); p++) {
                OCDPage page = doc.page(p);
                String where = "p" + (p + 1);
                page.nodes().filter(n -> n.clipId() != null && !page.clips().containsKey(n.clipId()))
                        .forEach(n -> bad.add(where + " " + n.id() + " names missing clip " + n.clipId()));
            }
        return bad;
    }

    // ── COPY ─────────────────────────────────────────────────────────────────────

    /** Page surgery duplicates pages with {@link OCDPage#copy}: a field it forgets reaches the output as a
     *  difference here, and a shared list (an alias) shows when the copy is edited. */
    private static List<String> copies(OCDDocument doc) {
        List<String> bad = new ArrayList<>();
        var glyphs = new SvgOcdWriter.Fonts(doc);
        for (int i = 0; i < doc.pageCount(); i++) {
            OCDPage page = doc.page(i), copy = page.copy();
            String before = SvgOcdWriter.render(doc, page, glyphs);
            if (!before.equals(SvgOcdWriter.render(doc, copy, glyphs))) bad.add("p" + (i + 1) + " copy projects differently");
            copy.content().clear();
            copy.links().clear();
            if (!before.equals(SvgOcdWriter.render(doc, page, glyphs))) bad.add("p" + (i + 1) + " copy is aliased to the page");
        }
        return bad;
    }

    // ── EDITED ───────────────────────────────────────────────────────────────────

    private static List<String> edited(byte[] good, Path tmp) throws Exception {
        List<String> bad = new ArrayList<>();
        // The cover is page 1 rasterized (FORMAT §A2 "cover"): kept while page 1 is the page it was made
        // from, dropped the moment page 1 is edited outside the engine — even by one byte.
        OCDDocument same = OCDReader.read(tmpWrite(tmp, good));
        if (same.cover() == null) bad.add("the cover of an unedited container was not kept");
        Path touched = tmp.resolve("touched.ocd.epub");
        Files.write(touched, onPage(good, s -> s + "\n"));
        if (OCDReader.read(touched.toFile()).cover() != null) bad.add("page 1 was edited and its stale cover was kept");

        var m = java.util.regex.Pattern.compile("<path id=\"([^\"]+)\"[^>]* transform=\"").matcher(member(good, PAGE));
        if (!m.find()) return bad;                                     // no content path on page 1
        String id = m.group(1), at = m.group();
        Path f = tmp.resolve("edited.ocd.epub");
        Files.write(f, onPage(good, s -> once(s, at, at + "translate(7 0) ")));
        double before = pathX(same, id), after = pathX(OCDReader.read(f.toFile()), id);
        if (Math.abs(after - before - 7) >= TOLERANCE) bad.add(id + " moved by " + (after - before) + ", expected 7");
        return bad;
    }

    private static File tmpWrite(Path tmp, byte[] bytes) throws Exception {
        Path f = tmp.resolve("unedited.ocd.epub");
        Files.write(f, bytes);
        return f.toFile();
    }

    private static double pathX(OCDDocument d, String id) {
        return d.page(0).nodes().filter(n -> id.equals(n.id())).findFirst().orElseThrow().bounds().x();
    }

    // ── HOSTILE ──────────────────────────────────────────────────────────────────

    private static List<String> hostile(byte[] good, Path tmp) throws Exception {
        String page = member(good, PAGE);
        String chain = "<!ENTITY a \"AAAAAAAAAA\">" + "<!ENTITY b \"" + "&a;".repeat(10) + "\">"
                     + "<!ENTITY c \"" + "&b;".repeat(10) + "\">" + "<!ENTITY d \"" + "&c;".repeat(10) + "\">";
        var edits = new LinkedHashMap<String, UnaryOperator<String>>();     // name -> edit of the first page
        edits.put("xxe", s -> once(once(s, "<svg", "<!DOCTYPE svg [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n<svg"),
                "data-text=\"", "data-text=\"&xxe;"));
        edits.put("billion", s -> once(once(s, "<svg", "<!DOCTYPE svg [" + chain + "]>\n<svg"), "data-text=\"", "data-text=\"&d;"));
        edits.put("badmat", s -> s.replaceFirst("transform=\"matrix\\([^\"]*\\)\"", "transform=\"matrix(nope)\""));
        edits.put("badfont", s -> s.replaceFirst("data-font=\"[^\"]*\"", "data-font=\"NoSuchFont\""));
        edits.put("badclip", s -> once(s, "url(#c", "url(#zz"));
        edits.put("badpath", s -> s.replaceFirst("(<path id=\"[^\"]+\"[^>]*? d=\"M[^\"]*)\"", "$1 L1 1#\""));   // once looped forever

        Set<String> optional = Set.of("badclip", "badpath");
        List<String> bad = new ArrayList<>();
        var variants = new LinkedHashMap<String, byte[]>();
        for (var e : edits.entrySet()) {
            String edited = e.getValue().apply(page);
            if (edited.equals(page)) {                 // a payload that hits nothing tests nothing
                if (!optional.contains(e.getKey())) bad.add(e.getKey() + ": the payload found nothing to hit");
                continue;                              // a page with no clip or no path has none to break
            }
            variants.put(e.getKey(), rezip(good, (n, b) -> n.endsWith(PAGE) ? edited.getBytes(StandardCharsets.UTF_8) : b));
        }
        variants.put("nofonts", rezip(good, (n, b) -> n.endsWith(FONTS) ? null : b));
        variants.put("deepjson", rezip(good, (n, b) -> n.endsWith("meta.json")                       // once a StackOverflowError
                ? ("[".repeat(100_000) + "]".repeat(100_000)).getBytes(StandardCharsets.UTF_8) : b));
        variants.put("trunc", Arrays.copyOf(good, good.length / 2));
        // A version this reader is not (FORMAT, Versions): an older one and none at all are refused like a newer one.
        String meta = member(good, "meta.json"), ver = "\"version\":\"" + OCDVocab.VERSION + "\"";
        if (!meta.contains(ver)) bad.add("meta.json states no " + ver);
        for (String[] v : new String[][] { { "oldver", "\"version\":\"3\"" }, { "nover", "\"v\":\"\"" } }) {
            byte[] edited = meta.replace(ver, v[1]).getBytes(StandardCharsets.UTF_8);
            variants.put(v[0], rezip(good, (n, b) -> n.endsWith("meta.json") ? edited : b));
        }

        for (var v : variants.entrySet()) {
            Path f = tmp.resolve("hostile-" + v.getKey() + ".ocd.epub");
            Files.write(f, v.getValue());
            String verdict = readHostile(f.toFile());
            // A missing reference may be read (and dropped) with a warning; everything else must be refused.
            boolean warnable = Set.of("badfont", "badclip", "nofonts").contains(v.getKey());
            boolean pass = verdict.startsWith("refused") || warnable && verdict.startsWith("warned");
            if (!pass) bad.add(v.getKey() + ": " + verdict);
        }
        return bad;
    }

    /** "refused: …", "warned: …", "crashed: …", "LEAK …" or "silent". A read that never returns fails too. */
    private static String readHostile(File f) throws Exception {
        warnings.clear();
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        try {
            return ex.submit(() -> {
                try {
                    OCDDocument d = OCDReader.read(f);
                    String text = d.pageCount() == 0 ? "" : String.join("", d.page(0).texts().map(OCDText::text).filter(Objects::nonNull).toList());
                    if (text.contains("root:")) return "LEAK: a local file reached the text";
                    if (text.length() > 1_000_000) return "LEAK: an entity was expanded";
                    return warnings.isEmpty() ? "silent" : "warned: " + warnings.get(0);
                } catch (Exception e) {
                    return "refused: " + e.getClass().getSimpleName();
                } catch (Error e) {                    // a StackOverflowError is not a refusal, it is a crash
                    return "crashed: " + e.getClass().getSimpleName();
                }
            }).get(60, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return "no answer in 60 s";
        } finally { ex.shutdownNow(); }
    }

    private static byte[] onPage(byte[] zip, UnaryOperator<String> edit) throws Exception {
        return rezip(zip, (n, b) -> n.endsWith(PAGE) ? edit.apply(new String(b, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8) : b);
    }

    private interface Member { byte[] edit(String name, byte[] bytes); }

    /** The same container, each member passed through {@code m}; a null drops the member. */
    private static byte[] rezip(byte[] zip, Member m) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zi = new ZipInputStream(new java.io.ByteArrayInputStream(zip)); var zo = new ZipOutputStream(out)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) {
                byte[] b = m.edit(e.getName(), zi.readAllBytes());
                if (b == null) continue;
                zo.putNextEntry(new ZipEntry(e.getName()));
                zo.write(b);
            }
        }
        return out.toByteArray();
    }

    private static String member(byte[] zip, String suffix) throws Exception {
        try (var zi = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; )
                if (e.getName().endsWith(suffix)) return new String(zi.readAllBytes(), StandardCharsets.UTF_8);
        }
        return "";
    }

    private static String once(String s, String old, String now) {
        int i = s.indexOf(old);
        return i < 0 ? s : s.substring(0, i) + now + s.substring(i + old.length());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Engine logs stay quiet; the reader's warnings are collected, since a warning is a verdict here. */
    private static void captureWarnings() {
        Logger root = LogManager.getLogManager().getLogger("");
        root.setLevel(Level.OFF);
        for (Handler h : root.getHandlers()) root.removeHandler(h);
        READER.setLevel(Level.WARNING);
        READER.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { if (r.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(r.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
    }

    private static boolean isEmpty(OCDPath p) {
        return p.geometry() == null || p.geometry().getPathIterator(null).isDone();
    }

    private static double delta(JxTransform a, JxTransform b) {
        return Math.max(Math.max(Math.abs(a.a() - b.a()), Math.abs(a.b() - b.b())),
               Math.max(Math.max(Math.abs(a.c() - b.c()), Math.abs(a.d() - b.d())),
                        Math.max(Math.abs(a.tx() - b.tx()), Math.abs(a.ty() - b.ty()))));
    }

    private static String nz(String s) { return s == null || s.isEmpty() ? null : s; }

    private static Map<String, OCDNode> byId(OCDPage p) {
        Map<String, OCDNode> m = new LinkedHashMap<>();
        p.nodes().filter(n -> n.id() != null && !n.id().isEmpty()).forEach(n -> m.put(n.id(), n));
        return m;
    }
}
