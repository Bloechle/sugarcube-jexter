// Gates — the engine's functional gate: every check, over every PDF given, in one JVM. CI runs it on push.
//
//   REPRO     same input converted twice is byte-identical, for every target (pin SOURCE_DATE_EPOCH).
//   DOCTAGS   pdf -> doctags == pdf -> ocd -> doctags: a projection must not depend on the route.
//   STABLE    write(read(x)) == x, byte for byte. A drift is a diagnosis away: run ContainerDiff.
//   MODEL     read(write(m)) == m field by field, and paint order kept. Bytes agree when a field is
//             written wrong and read back wrong the same way; this is the gate that sees it.
//   TIERS     readMembers / readPage / pageCount give what read gives, page for page.
//   NAV       every bookmark and internal link lands where PDFBox resolves it (names and GoTo included).
//   TAGS      a tagged PDF's tree lands as the author wrote it, by STANDARD type, no content claimed twice —
//             and a tree that marks little of the text (a layout program's skeleton, a few tagged objects)
//             is no structure at all: the page is read as untagged.
//   PIXELS    the model paints what PDFBox paints (< 1 % of a page off), and the direct renderer agrees
//             with the model's render (< 0.25 %): a gap there is lost in the model, not in the painting.
//   FURNITURE running heads and folios stay on the page and out of every structure the writers read — and a
//             section heading at the top of every page, the same words but its number, is no running head;
//             a paragraph the press cut at the foot of a page or of a column reads as one.
//   AGAIN     a second Analysis.run writes the same container, and reading the stages changes nothing.
//   WORDS     a fixture that declares its text reads it word for word and in order: no space glued, none
//             invented, no column read across — and never reads what it paints where no engine shows it
//             (a spread's other half, past the page's edge: once a table of contents read twice).
//   STRADDLE  no paragraph wraps a paint span another node sits inside (it could not be stored atomically).
//   CLIPS     every clip reference resolves, on the imported model and on the model read back.
//   COPY      a page and its copy project identically, and editing the copy leaves the page alone.
//   DRAWN     every glyph a run paints is reached through its compiled font: the codepoint the text
//             writers emit maps, in the font's own cmap, to that glyph (a ligature drew an « f » and a gap).
//   EDITED    a path a viewer moved (Prism's editor composes the move in front of the page flip)
//             reads back moved — once, it was read back where it started.
//   LAYERS    a layer switched off is painted and read out by no projection (pdf, epub, md, doctags,
//             html), a layer left on by every one — once the clean PDF of a privacy check SHOWED a
//             hidden draft layer, in blue, and every text target read it out.
//   FIGURES   every picture the body shows is a FIGURE of the structure the writers read (a tagged PDF's
//             own tree aside; a panel holding copy is that copy's, no figure), and the reflow book draws each one: a <figure> always holds its <img> —
//             once, 81 of 83 documents with pictures lost every one in the reflowable EPUB.
//   BOOK      the reflow book is cut in chapters that stand: none is only a heading, none too short to
//             read unless it is the whole book, and its table of contents reaches every heading, nested
//             as the headings are — once a stamp in the margin and half of a wrapped title were chapters.
//   HOSTILE   broken and malicious variants of the container: refused with context, or read with a
//             warning — never read in silence, never a leaked file, never an entity expansion, never a
//             nesting left to the stack; a passenger is data under a clean path, never markup, script or a
//             path out of the package; a link leads only to http, https or mailto; and a request naming a
//             file of the engine's machine (ConvertOptions.LOCAL) refused.
//   BOUNDS    once, not per PDF: a small hostile input costs bounded memory — a zip bomb in a container, a
//             picture declaring 40000² pixels (in a container and in a PDF), a transparency group on a
//             4000-pt page, a cyclic outline and tag tree, a page selection repeating a page 3000 times —
//             and a host whose locale writes its own digits writes the same bytes.
//
//   READ      once: a picture's words, both readings — invisible over the picture (the picture is the page), or
//             redrawn: each confident word erased from the picture and drawn as selectable text, a doubtful
//             word kept in its pixels, a picture left with no ink dropped; an engine's padded boxes give the
//             size its snug ones give (the words are measured in the picture); the text is set, not pasted: one
//             size a paragraph, one stretch a line, the spacing absorbing the rest; an unknown reading refused.
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
            gate("WORDS",    () -> words(pdf.getName(), ocd));
            gate("AGAIN",    () -> again(pdf));
            gate("STRADDLE", () -> straddle(model));
            gate("CLIPS",    () -> clips(model, OCDReader.read(container.toFile())));
            gate("COPY",     () -> copies(OCDReader.read(container.toFile())));
            gate("DRAWN",    () -> drawn(model));
            gate("EDITED",   () -> edited(ocd, tmp));
            gate("LAYERS",   () -> layers(src, model));
            gate("FIGURES",  () -> figures(model, src));
            gate("BOOK",     () -> book(pdf.getName(), src));
            gate("HOSTILE",  () -> hostile(ocd, tmp));
        }
        current = "(once)";
        reported = false;
        gate("BOUNDS",   () -> bounds(tmp));
        gate("READ",     Gates::read);
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

    // ── WORDS ────────────────────────────────────────────────────────────────────

    /** The read-out holds every line the fixture declares, word for word and in the declared order: a glued
     *  word, a split one, or columns read across each break it. Only fixtures that declare their words are
     *  checked. */
    private static List<String> words(String name, byte[] ocd) throws Exception {
        String id = name.replaceFirst("\\.pdf$", "");
        List<String> lines = Fixtures.WORDS.get(id);
        if (lines == null) return ok();
        String md = new String(to(ocd, "md"), java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        List<String> bad = new ArrayList<>();
        for (String w : Fixtures.ABSENT.getOrDefault(id, List.of()))
            if (md.contains(w)) bad.add("read what no engine shows: \"" + w + "\"");
        String text = " " + md + " ";
        int from = 0;
        for (String l : lines) {
            int at = text.indexOf(" " + l + " ", from);
            if (at < 0) bad.add(text.contains(" " + l + " ") ? "read out of order: \"" + l + "\"" : "not read as written: \"" + l + "\"");
            else from = at + l.length();
        }
        return bad;
    }

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
        if (current.equals("doc_sections.pdf")) {                    // a section at the top of every page is no running head
            String md = sugarcube.jexter.write.MarkdownWriter.toMarkdown(model);
            for (int i = 1; i <= 4; i++)
                if (!md.contains("Section " + i + ": terms")) bad.add("the heading of section " + i + " was read as a running head");
            if (md.lines().anyMatch(l -> l.strip().matches("\\d+"))) bad.add("a bare folio is read as text");
        }
        if (current.equals("doc_columns_page_break.pdf")) {          // the last column of a page flows into the first of the next
            String md = sugarcube.jexter.write.MarkdownWriter.toMarkdown(model);
            if (md.lines().noneMatch(l -> l.contains("page break to the next page"))) bad.add("the paragraph the page break cut is still two");
            if (md.lines().anyMatch(l -> l.contains("short last line.") && l.contains("The right column of the second"))) bad.add("an indented paragraph was joined to the one before");
        }
        if (current.equals("doc_magazine.pdf")) {                    // a paragraph cut at the foot of a column
            String md = sugarcube.jexter.write.MarkdownWriter.toMarkdown(model);
            if (md.lines().noneMatch(l -> l.contains("keeps the night watch over the shelves"))) bad.add("the paragraph the column break cut is still two");
            if (md.lines().anyMatch(l -> l.contains("comes.") && l.contains("The reading room"))) bad.add("an indented paragraph was joined to the one before");
        }
        if (current.equals("doc_page_break.pdf")) {                  // a bare folio, and a paragraph the press cut
            String md = sugarcube.jexter.write.MarkdownWriter.toMarkdown(model);
            if (md.lines().anyMatch(l -> l.strip().matches("\\d+"))) bad.add("a bare folio is read as text");
            if (md.lines().noneMatch(l -> l.contains("carried over the page break"))) bad.add("the paragraph the page break cut is still two");
            if (md.lines().anyMatch(l -> l.contains("short.") && l.contains("A new paragraph"))) bad.add("an indented paragraph was joined to the one before");
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
            if (Fixtures.UNTAGGED.contains(pdf.getName().replaceFirst("\\.pdf$", "")))   // a tree that marks (almost) nothing
                return pdfTree == null ? bad : List.of("a tag tree marking little of the text became the \"pdf\" structure");
            if (pdfTree == null) return List.of("tagged, but no \"pdf\" structure");
            List<String> want = new ArrayList<>(), got = new ArrayList<>();
            for (Object k : root.getKids()) if (k instanceof PDStructureElement e) tokens(e, want);
            tokens(pdfTree.root(), got);
            if (!want.equals(got)) bad.add("tree " + got + ", source " + want);
            var seen = new HashSet<OCDStructNode.Ref>();
            refs(pdfTree.root(), seen, bad);
            if (!pdfTree.id().equals(model.defaultStructureId())) bad.add("default structure is " + model.defaultStructureId() + ", not the PDF's own");
            if (model.outline().isEmpty()) headingRoles(pdfTree.root(), model, bad);   // no nav to win: the tags decide
            headingWords(pdfTree.root(), OCDIndex.of(model), bad);
            int heads = headings(pdfTree.root());
            int reached = sugarcube.jexter.write.HtmlWriter.chapters(model, null).stream().mapToInt(c -> c.headings().size()).sum();
            if (reached < heads) bad.add(heads + " headings, but the reflow book's table of contents reaches " + reached);
            if (pdf.getName().equals("tagged.pdf")) {                // its callout: two loose Spans, one paragraph
                String md = new String(to(Files.readAllBytes(pdf.toPath()), "md"), StandardCharsets.UTF_8);
                if (md.lines().noneMatch(l -> l.contains("Un encadre ecrit en deux morceaux")))
                    bad.add("the callout's two pieces are not one paragraph");
            }
        }
        return bad;
    }

    /** Every heading reads as its words, whatever wraps them: a heading whose text sits in a Div (as Typst
     *  writes them) read as nothing, and the contents of every EPUB said « Heading 1 ». */
    private static void headingWords(OCDStructNode n, OCDIndex ix, List<String> bad) {
        if (n.type() == OCDStructNode.Type.HEADING && ix.text(n).isBlank()) bad.add("an H" + n.level() + " reads as nothing");
        for (OCDStructNode c : n.children()) headingWords(c, ix, bad);
    }

    private static int headings(OCDStructNode n) {
        int k = n.type() == OCDStructNode.Type.HEADING ? 1 : 0;
        for (OCDStructNode c : n.children()) k += headings(c);
        return k;
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

    // ── DRAWN ────────────────────────────────────────────────────────────────────

    /** The SVG and EPUB pages write each glyph as the codepoint {@link OCDFont#drawCodepoints} names, in a face
     *  compiled from the model: that codepoint must map, in the compiled font's OWN cmap (parsed back by
     *  FontBox), to that glyph. The font's glyphs are dense in model-gid order, .notdef first. */
    private static List<String> drawn(OCDDocument doc) throws Exception {
        List<String> bad = new ArrayList<>();
        Map<String, org.apache.fontbox.ttf.CmapLookup> cmaps = new HashMap<>();
        for (int i = 0; i < doc.pageCount(); i++) {
            for (OCDText t : doc.page(i).texts().toList()) {
                OCDFont f = doc.findFont(t.fontId());
                if (f == null) continue;
                org.apache.fontbox.ttf.CmapLookup cmap = cmaps.get(f.id());
                if (cmap == null) {
                    byte[] otf = sugarcube.jexter.font.JxFont.toOtf(f);
                    try (var font = new org.apache.fontbox.ttf.OTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(otf))) {
                        cmap = font.getUnicodeCmapLookup();
                    }
                    cmaps.put(f.id(), cmap);
                }
                List<Integer> order = new ArrayList<>(new TreeSet<>(f.glyphs().keySet()));
                for (OCDText.Glyph g : t.glyphs()) {
                    if (!f.hasGlyph(g.gid())) continue;
                    Integer cp = f.drawCodepoints().get(g.gid());
                    int want = order.indexOf(g.gid()) + 1;
                    if (cp == null) bad.add("p" + (i + 1) + " " + f.id() + " gid " + g.gid() + " has no codepoint");
                    else if (cmap.getGlyphId(cp) != want)
                        bad.add("p" + (i + 1) + " " + f.id() + " gid " + g.gid() + " (" + g.unicode() + ") drawn by U+"
                                + Integer.toHexString(cp) + " which the font maps to glyph " + cmap.getGlyphId(cp) + ", not " + want);
                }
            }
        }
        return bad.size() > 5 ? bad.subList(0, 5) : bad;
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

    // ── LAYERS ───────────────────────────────────────────────────────────────────

    private static List<String> layers(byte[] src, OCDDocument doc) throws Exception {
        List<String> off = new ArrayList<>(), on = new ArrayList<>();
        for (OCDPage p : doc.pages())
            p.nodes().filter(OCDLayerContent.class::isInstance).forEach(l -> {
                String t = squeeze(OCDIndex.text(l));
                if (t.length() >= 4) (doc.hides(l) ? off : on).add(t);
            });
        if (off.isEmpty() && on.isEmpty()) return ok();
        List<String> bad = new ArrayList<>();
        for (String target : List.of("pdf", "epub", "epub-reflow", "md", "doctags", "html")) {
            byte[] out = Conversion.convert(src, target, Map.of("selectable", "true")).bytes();
            String text = squeeze(switch (target) {
                case "pdf" -> { try (PDDocument d = Loader.loadPDF(out)) { yield new org.apache.pdfbox.text.PDFTextStripper().getText(d); } }
                case "epub", "epub-reflow" -> zipText(out);
                default -> new String(out, StandardCharsets.UTF_8);
            });
            for (String t : off) if (text.contains(t)) bad.add(target + " shows a layer switched off: " + t);
            for (String t : on) if (!text.contains(t)) bad.add(target + " lost a layer left on: " + t);
        }
        return bad;
    }

    /** Text with every space and markup gap gone: « HIDDEN LAYER » is found however a target spaces it. */
    private static String squeeze(String s) { return s.replaceAll("<[^>]*>", "").replaceAll("\\s+", ""); }

    private static String zipText(byte[] zip) throws Exception {
        StringBuilder b = new StringBuilder();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (e.getName().endsWith(".xhtml") || e.getName().endsWith(".svg")) b.append(new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    // ── FIGURES ──────────────────────────────────────────────────────────────────

    private static List<String> figures(OCDDocument model, byte[] src) throws Exception {
        List<String> bad = new ArrayList<>();
        OCDStructure s = model.structureById(model.defaultStructureId());
        int figs = 0;
        if (s != null && !"pdf".equals(s.id())) {
            Set<String> shown = new HashSet<>();
            figs = figureRefs(s.root(), model, shown);
            for (int p = 0; p < model.pageCount(); p++) {
                int page = p;
                model.page(p).images().filter(im -> !model.hides(im) && !sugarcube.jexter.ocd.analysis.Furniture.isRunning(im))
                     .filter(im -> Math.max(im.ink().width(), im.ink().height()) >= 24)
                     .filter(im -> !framesCopy(model.page(page), im))
                     .filter(im -> !shown.contains(page + "/" + im.id()))
                     .forEach(im -> bad.add("page " + (page + 1) + ": picture " + im.id() + " is in no FIGURE"));
            }
        }
        String book = zipText(to(src, "epub-reflow"));
        int open = 0, drawn = 0;
        for (int at = book.indexOf("<figure"); at >= 0; at = book.indexOf("<figure", at + 1)) {
            open++;
            int end = book.indexOf("</figure>", at);
            if (end > at && book.substring(at, end).contains("<img")) drawn++;
        }
        if (drawn != open) bad.add((open - drawn) + " of " + open + " <figure> without a picture in the reflow book");
        if (s != null && !"pdf".equals(s.id()) && open < figs) bad.add(figs + " FIGUREs, " + open + " drawn in the reflow book");
        return bad;
    }

    /** A picture holding a paragraph of copy whole (100 characters or more) is that text's panel, no figure. */
    private static boolean framesCopy(OCDPage page, OCDNode im) {
        var box = im.ink();
        return page.nodes().filter(n -> n instanceof sugarcube.jexter.ocd.model.OCDParagraph && OCDIndex.text(n).length() >= 100)
                   .map(OCDNode::ink).anyMatch(t -> t.x() >= box.x() && t.y() >= box.y() && t.right() <= box.right() && t.maxY() <= box.maxY());
    }

    /** Count the FIGUREs under {@code n}, and note every node they reach (descendants included) as page/id. */
    private static int figureRefs(OCDStructNode n, OCDDocument model, Set<String> shown) {
        int count = 0;
        if (n.type() == OCDStructNode.Type.FIGURE) {
            count++;
            OCDIndex ix = OCDIndex.of(model);
            for (OCDStructNode.Ref r : n.refs()) {
                OCDNode node = ix.node(r.page(), r.nodeId());
                if (node != null) node.stream().forEach(x -> shown.add(r.page() + "/" + x.id()));
            }
        }
        for (OCDStructNode c : n.children()) count += figureRefs(c, model, shown);
        return count;
    }

    // ── BOOK ─────────────────────────────────────────────────────────────────────

    private static List<String> book(String name, byte[] src) throws Exception {
        List<String> bad = new ArrayList<>();
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(to(src, "epub-reflow")))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; )
                if (e.getName().endsWith(".xhtml")) files.put(e.getName().substring(e.getName().lastIndexOf('/') + 1), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        String nav = files.remove("nav.xhtml");
        nav = nav.substring(nav.indexOf("epub:type=\"toc\""), nav.indexOf("</nav>"));
        Set<String> targets = new HashSet<>();
        var href = java.util.regex.Pattern.compile("href=\"([^\"]+)\"").matcher(nav);
        while (href.find()) targets.add(href.group(1));
        var heading = java.util.regex.Pattern.compile("<h[1-6] id=\"([^\"]+)\">");
        for (var f : files.entrySet()) {
            String body = f.getValue().substring(f.getValue().indexOf("<main>"));
            String rest = body.replaceAll("(?s)<h[1-6][^>]*>.*?</h[1-6]>", "");
            int text = rest.replaceAll("<[^>]*>", "").strip().length();
            boolean picture = rest.contains("<img") || rest.contains("<table") || rest.contains("<video") || rest.contains("<audio");
            if (text == 0 && !picture) bad.add(f.getKey() + " is only a heading");
            else if (files.size() > 1 && text < sugarcube.jexter.write.HtmlWriter.MIN_CHAPTER && !picture) bad.add(f.getKey() + " holds " + text + " characters: too short to stand");
            var h = heading.matcher(body);
            while (h.find()) if (!targets.remove(f.getKey() + "#" + h.group(1))) bad.add(f.getKey() + "#" + h.group(1) + " is in no table of contents entry");
        }
        targets.removeIf(t -> !t.contains("#") && files.containsKey(t));
        if (!targets.isEmpty()) bad.add("table of contents entries lead nowhere: " + targets);
        if (name.startsWith("doc_magazine") && (!nav.contains("The archive at night") || nav.contains("Every minute")))
            bad.add("the contents are not the text's headings: bookmarks that name nothing in it stood in for them");
        if (name.startsWith("doc_chapters")) {                  // the book under one title: cut at its parts, details nested
            if (files.size() != 3) bad.add(files.size() + " chapters, not 3 (the parts, the stub joined to the last)");
            if (!java.util.regex.Pattern.compile("1 The first part</a>\\s*<ol>\\s*<li><a href=\"[^\"]+\">1\\.1 ").matcher(nav).find())
                bad.add("1.1 is not nested under 1 in the table of contents");
        }
        return bad;
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
        edits.put("deepsvg", s -> last(s, "</svg>", "<g>".repeat(20_000) + "</g>".repeat(20_000) + "</svg>"));   // once a StackOverflowError

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

        // A request may not name a file of the engine's machine (ConvertOptions.LOCAL): over HTTP it would be
        // read for whoever asked. The command line may.
        for (String key : ConvertOptions.LOCAL) {
            try { ConvertOptions.fromRequest(Map.of(key, "/etc/passwd")); bad.add("a request set " + key); }
            catch (IllegalArgumentException refused) { /* the expected answer */ }
            if (!"/etc/passwd".equals(ConvertOptions.fromMap(Map.of(key, "/etc/passwd")).toMap().get(key)))
                bad.add("the command line lost " + key);
            try { Conversion.convert(good, "ocd", Map.of(key, "/etc/passwd")); bad.add("the Map form of convert took " + key); }
            catch (IllegalArgumentException refused) { /* the Map form is a request's */ }
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

        // Passengers: the sidecar travels; markup, script and a path out of the package never do (once both were
        // written back into a container the engine signs).
        Path carried = tmp.resolve("hostile-passengers.ocd.epub");
        Files.write(carried, withMembers(good, Map.of("OEBPS/prism/augment.json", "{}", "OEBPS/evil.xhtml", "<script/>",
                "OEBPS/../../escape.txt", "x", "OEBPS/.hidden", "x")));
        Set<String> kept = OCDReader.read(carried.toFile()).passengers().keySet();
        if (!kept.equals(Set.of("prism/augment.json"))) bad.add("passengers carried: " + kept);

        // A link leads only to http, https or mailto: a javascript: href once ran in every viewer of the page.
        Path linked = tmp.resolve("hostile-jslink.ocd.epub");
        Files.write(linked, onPage(good, s -> last(s, "</svg>", "<g data-ocd=\"links\"><a href=\"javascript:alert(1)\">"
                + "<rect x=\"0\" y=\"0\" width=\"9\" height=\"9\"/></a></g></svg>")));
        for (OCDLink l : OCDReader.read(linked.toFile()).page(0).links())
            if (l.uri() != null && !l.uri().startsWith("http")) bad.add("a link kept " + l.uri());
        return bad;
    }

    // ── BOUNDS ───────────────────────────────────────────────────────────────────

    /** Small hostile inputs, built here (they are not corpus documents: every other gate would trip on them). Each
     *  once cost the heap or the stack; now each is refused with a reason, or read with what it can bear. */
    // ── READ ─────────────────────────────────────────────────────────────────────

    /** A page of ink blocks standing for words, read by an engine that answers their boxes: both readings. */
    private static List<String> read() throws Exception {
        List<String> bad = new ArrayList<>();
        int[][] blocks = { {60, 60, 120, 30}, {200, 60, 90, 30}, {310, 60, 140, 30}, {60, 140, 110, 30} };
        String[] text = { "alpha", "beta", "gamma", "omega" };
        java.util.function.Function<double[], List<sugarcube.jexter.core.OcrClient.Word>> read = conf -> {
            List<sugarcube.jexter.core.OcrClient.Word> ws = new ArrayList<>();
            for (int i = 0; i < blocks.length; i++) ws.add(new sugarcube.jexter.core.OcrClient.Word(text[i],
                    new sugarcube.jexter.core.JxRect(blocks[i][0], blocks[i][1], blocks[i][2], blocks[i][3]), conf[i], i < 3 ? 0 : 1));
            return ws;
        };
        BufferedImage page = new BufferedImage(520, 240, BufferedImage.TYPE_INT_RGB);
        var g = page.createGraphics();
        g.setColor(java.awt.Color.WHITE); g.fillRect(0, 0, 520, 240);
        g.setColor(java.awt.Color.BLACK);
        for (int[] b : blocks) g.fillRect(b[0] + 2, b[1] + 2, b[2] - 4, b[3] - 4);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(page, "png", png);
        byte[] picture = png.toByteArray();

        sugarcube.jexter.core.OcrClient sure = engine(read.apply(new double[]{0.99, 0.95, 0.9, 0.97}));
        sugarcube.jexter.core.OcrClient doubt = engine(read.apply(new double[]{0.99, 0.95, 0.9, 0.3}));
        var invisible = sugarcube.jexter.core.ConvertOptions.fromMap(Map.of());
        var redrawn = sugarcube.jexter.core.ConvertOptions.fromMap(Map.of("ocrText", "redrawn"));

        // the first reading: the picture is the page, every word invisible over it
        OCDDocument a = sugarcube.jexter.imageimport.ImageImporter.convert(picture, invisible, sure);
        if (a.page(0).images().count() != 1) bad.add("invisible: the picture is not the page");
        if (a.page(0).texts().count() != 4 || a.page(0).texts().anyMatch(t -> !t.isInvisible())) bad.add("invisible: a word is not invisible text");

        // redrawn, every word sure: drawn as text, and the picture — nothing but those words — dropped
        OCDDocument b = sugarcube.jexter.imageimport.ImageImporter.convert(picture, redrawn, sure);
        if (b.page(0).images().count() != 0) bad.add("redrawn: a picture left with no ink was kept");
        if (b.page(0).texts().filter(t -> !t.isInvisible()).count() != 4) bad.add("redrawn: not every sure word was drawn");
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        sugarcube.jexter.write.PdfWriter.write(b, pdf, sugarcube.jexter.core.ConvertOptions.fromMap(Map.of("selectable", "true")));
        String copied = pdfText(pdf.toByteArray());
        for (String w : text) if (!copied.contains(w)) bad.add("redrawn: \"" + w + "\" cannot be copied from the PDF (" + copied.strip() + ")");

        // an engine that pads its boxes (Azure's are taller than the letters): the words are measured in the
        // picture, so they come out the size the snug boxes give, not set at the padding (where all were refused)
        List<sugarcube.jexter.core.OcrClient.Word> padded = new ArrayList<>();
        for (var w : read.apply(new double[]{0.99, 0.95, 0.9, 0.97})) padded.add(new sugarcube.jexter.core.OcrClient.Word(
                w.text(), w.box().inflate(8, 14), w.confidence(), w.line()));
        OCDDocument p = sugarcube.jexter.imageimport.ImageImporter.convert(picture, redrawn, engine(padded));
        var snug = b.page(0).texts().filter(t -> !t.isInvisible()).toList();
        var loose = p.page(0).texts().filter(t -> !t.isInvisible()).toList();
        if (loose.size() != 4) bad.add("redrawn, padded boxes: " + loose.size() + " of 4 words drawn");
        else for (int i = 0; i < 4; i++)
            if (Math.abs(loose.get(i).fontSize() / snug.get(i).fontSize() - 1) > 0.03)
                bad.add("redrawn, padded boxes: \"" + text[i] + "\" set at " + loose.get(i).fontSize() + ", not " + snug.get(i).fontSize());

        // set, not pasted: one size a paragraph (two lines a few pixels apart in height), one stretch a line (two
        // words whose boxes want 1.10 and 1.15: the line takes one, the spacing between letters absorbs the rest)
        // — and a line far below, taller, is a paragraph of its own, at its own size
        {
            var ttf = new org.apache.fontbox.ttf.TTFParser().parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(sugarcube.jexter.pdfimport.TtfImporter.standardSans()));
            var cmap = ttf.getUnicodeCmapLookup();
            double upm = ttf.getUnitsPerEm();
            java.util.function.ToDoubleFunction<String> em = w -> w.codePoints().mapToDouble(c -> {
                try { return ttf.getAdvanceWidth(cmap.getGlyphId(c)) / upm; } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            }).sum();
            String[][] said = { {"same", "move"}, {"once", "more"}, {"secure"} };
            int[][] lineAt = { {40, 20}, {66, 21}, {200, 30} };              // y, ink height (px)
            double[][] want = { {1.10, 1.15}, {1.12, 1.12}, {1.0} };         // the stretch each word's box asks
            BufferedImage set = new BufferedImage(700, 300, BufferedImage.TYPE_INT_RGB);
            var gs = set.createGraphics();
            gs.setColor(java.awt.Color.WHITE); gs.fillRect(0, 0, 700, 300);
            gs.setColor(java.awt.Color.BLACK);
            List<sugarcube.jexter.core.OcrClient.Word> ws = new ArrayList<>();
            for (int l = 0; l < said.length; l++) {
                int x = 40, y = lineAt[l][0], h = lineAt[l][1];
                for (int k = 0; k < said[l].length; k++) {
                    int w = (int) Math.round(em.applyAsDouble(said[l][k]) * h / 0.53 * want[l][k]);
                    gs.fillRect(x, y, w, h);
                    ws.add(new sugarcube.jexter.core.OcrClient.Word(said[l][k], new sugarcube.jexter.core.JxRect(x - 2, y - 2, w + 4, h + 4), 0.99, l));
                    x += w + h;
                }
            }
            gs.dispose();
            ByteArrayOutputStream setPng = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(set, "png", setPng);
            var drawn = sugarcube.jexter.imageimport.ImageImporter.convert(setPng.toByteArray(), redrawn, engine(ws)).page(0).texts()
                    .filter(t -> !t.isInvisible()).collect(java.util.stream.Collectors.toMap(t -> t.text().strip(), t -> t, (x, y) -> x));
            if (!drawn.keySet().containsAll(List.of("same", "move", "once", "more", "secure"))) bad.add("set: not every word drawn " + drawn.keySet());
            else {
                if (Math.abs(drawn.get("same").transform().a() - drawn.get("move").transform().a()) > 1e-9)
                    bad.add("set: the words of one line are stretched apart (" + drawn.get("same").transform().a() + ", " + drawn.get("move").transform().a() + ")");
                if (drawn.get("same").fontSize() != drawn.get("more").fontSize())
                    bad.add("set: one paragraph, two sizes (" + drawn.get("same").fontSize() + ", " + drawn.get("more").fontSize() + ")");
                if (Math.abs(drawn.get("secure").fontSize() / drawn.get("same").fontSize() - 1) < 0.3)
                    bad.add("set: a taller line far below took the paragraph's size");
            }
        }

        // redrawn, one word in doubt: it keeps its pixels (and lies invisible over them); the others are erased
        OCDDocument c = sugarcube.jexter.imageimport.ImageImporter.convert(picture, redrawn, doubt);
        var kept = c.page(0).images().findFirst();
        if (kept.isEmpty()) bad.add("redrawn: the picture of a doubtful word was dropped");
        else {
            BufferedImage stored = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(c.image(kept.get().resourceRef())));
            if ((stored.getRGB(120, 75) & 0xFF) < 200) bad.add("redrawn: a redrawn word's pixels were not erased");
            if ((stored.getRGB(115, 155) & 0xFF) > 60) bad.add("redrawn: the doubtful word's pixels were erased");
        }
        if (c.page(0).texts().filter(t -> !t.isInvisible()).count() != 3) bad.add("redrawn: the doubtful word was drawn, or a sure one was not");
        // and the redrawn reading is an ordinary container: written, read back, written again the same
        byte[] ocd = Conversion.convert(c, "ocd", sugarcube.jexter.core.ConvertOptions.fromMap(Map.of())).bytes();
        if (!Arrays.equals(ocd, to(ocd, "ocd"))) bad.add("redrawn: the container drifts when written again");
        if (!pdfText(Conversion.convert(ocd, "pdf", Map.of("selectable", "true")).bytes()).contains("gamma"))
            bad.add("redrawn: the container's PDF lost a drawn word");

        try { sugarcube.jexter.imageimport.ImageImporter.convert(picture, sugarcube.jexter.core.ConvertOptions.fromMap(Map.of("ocrText", "maybe")), sure); bad.add("ocrText=maybe was taken"); }
        catch (IllegalArgumentException refused) { /* in those words */ }
        return bad;
    }

    private static sugarcube.jexter.core.OcrClient engine(List<sugarcube.jexter.core.OcrClient.Word> words) {
        return new sugarcube.jexter.core.OcrClient() {
            @Override public List<Word> read(byte[] image, String lang) { return words; }
            @Override public String engine() { return "gate"; }
        };
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) { return new org.apache.pdfbox.text.PDFTextStripper().getText(d); }
    }

    private static List<String> bounds(Path tmp) throws Exception {
        List<String> bad = new ArrayList<>();
        byte[] picture = pdf(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200), (d, p) -> {
            var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(d, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) { cs.drawImage(img, 20, 20, 100, 100); }
        });
        byte[] ocd = to(picture, "ocd");

        // 300 MB of zeros in a 300 KB member: once an OutOfMemoryError, now refused by the container's door.
        Path bomb = tmp.resolve("bounds-bomb.ocd.epub");
        Files.write(bomb, withZeros(ocd, "OEBPS/extra/bomb.bin", 300L << 20));
        String verdict = readHostile(bomb.toFile());
        if (!verdict.startsWith("refused")) bad.add("zip bomb: " + verdict);

        // A picture just past the budget (10001², 300 MB decoded from 300 KB): refused before a pixel is decoded.
        byte[] wide = png(10_001, 10_001);
        byte[] huge = rezip(ocd, (n, b) -> n.contains("/images/img_") ? wide : b);
        if (Arrays.equals(huge, ocd)) bad.add("the picture fixture stored no image member");
        else refused(bad, "a container's 10001² picture exported to pdf", () -> to(huge, "pdf"));

        // The same in a PDF: the dictionary states the size, a few bytes back it.
        survives(bad, "a PDF image declaring 40000²", () -> to(pdf(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200), (d, p) -> {
            var s = d.getDocument().createCOSStream();
            try (var o = s.createRawOutputStream()) { o.write(new byte[16]); }
            s.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.XOBJECT);
            s.setItem(org.apache.pdfbox.cos.COSName.SUBTYPE, org.apache.pdfbox.cos.COSName.IMAGE);
            s.setInt(org.apache.pdfbox.cos.COSName.WIDTH, 40_000);
            s.setInt(org.apache.pdfbox.cos.COSName.HEIGHT, 40_000);
            s.setInt(org.apache.pdfbox.cos.COSName.BITS_PER_COMPONENT, 8);
            s.setItem(org.apache.pdfbox.cos.COSName.COLORSPACE, org.apache.pdfbox.cos.COSName.DEVICEGRAY);
            var img = new org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(s), null);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) { cs.drawImage(img, 20, 20, 100, 100); }
        }), "ocd"));

        // A transparency group on a 4000-pt page, baked at 300 dpi: once 2.7 billion pixels from a 1 KB PDF.
        survives(bad, "a group on a 4000-pt page", () -> to(pdf(new org.apache.pdfbox.pdmodel.common.PDRectangle(4000, 4000), (d, p) -> {
            var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(d);
            form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(4000, 4000));
            var group = new org.apache.pdfbox.cos.COSDictionary();
            group.setItem(org.apache.pdfbox.cos.COSName.S, org.apache.pdfbox.cos.COSName.TRANSPARENCY);
            form.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.GROUP, group);
            try (var o = form.getContentStream().createOutputStream()) {
                o.write("0 0 1 rg 0 0 3000 3000 re f 1 0 0 rg 1000 1000 3000 3000 re f".getBytes(StandardCharsets.US_ASCII));
            }
            var gs = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(0.5f);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(d, p)) { cs.setGraphicsStateParameters(gs); cs.drawForm(form); }
        }), "ocd"));

        // An outline item that is its own child, a tag that is its own kid: once a StackOverflowError each.
        survives(bad, "a cyclic outline and tag tree", () -> to(pdf(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200), (d, p) -> {
            var outline = new org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
            var item = new PDOutlineItem();
            item.setTitle("loop");
            outline.addLast(item);
            item.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.FIRST, item);
            item.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.LAST, item);
            d.getDocumentCatalog().setDocumentOutline(outline);
            var root = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot();
            var el = new PDStructureElement("P", root);
            el.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.K, el);
            root.appendKid(el);
            d.getDocumentCatalog().setStructureTreeRoot(root);
            var mark = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo();
            mark.setMarked(true);
            d.getDocumentCatalog().setMarkInfo(mark);
        }), "md"));

        // Repetition is a feature, a 3000-fold one is not: 9 KB of request once made a 152 MB PDF of one page.
        refused(bad, "a selection of 3000 copies of one page", () -> Pages.parse("1-,".repeat(3000), 1));

        // A host whose locale writes its own digits writes the same container (once page-۰۰۱, and images that
        // collapsed onto one name).
        Locale was = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("fa"));
            if (!Arrays.equals(ocd, to(picture, "ocd"))) bad.add("a Persian locale changed the container's bytes");
        } finally { Locale.setDefault(was); }
        return bad;
    }

    private interface Build { void on(PDDocument d, PDPage p) throws Exception; }

    /** A one-page PDF of {@code size}, built by {@code b}. */
    private static byte[] pdf(org.apache.pdfbox.pdmodel.common.PDRectangle size, Build b) throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage(size);
            d.addPage(p);
            b.on(d, p);
            var out = new ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private interface Run { Object run() throws Exception; }

    /** {@code r} must be refused (an exception), never accepted, never a crash. */
    private static void refused(List<String> bad, String what, Run r) {
        try { r.run(); bad.add(what + ": accepted"); }
        catch (Exception expected) { /* the answer */ }
        catch (Error e) { bad.add(what + ": " + e); }
    }

    /** {@code r} must come back, whatever it keeps. */
    private static void survives(List<String> bad, String what, Run r) {
        try { r.run(); } catch (Exception | Error e) { bad.add(what + ": " + e); }
    }

    /** A black {@code w × h} PNG, 8-bit RGB, written row by row so it never exists decoded here. */
    private static byte[] png(int w, int h) throws Exception {
        var idat = new ByteArrayOutputStream();
        try (var z = new DeflaterOutputStream(idat)) { byte[] row = new byte[1 + 3 * w]; for (int y = 0; y < h; y++) z.write(row); }
        var buf = new ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(buf);
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10 });
        chunk(out, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 2).array());
        chunk(out, "IDAT", idat.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return buf.toByteArray();
    }

    private static void chunk(java.io.DataOutputStream out, String type, byte[] data) throws Exception {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        out.writeInt((int) crc.getValue());
    }

    /** The same container with a member of {@code n} zeros added, deflated as it is written. */
    private static byte[] withZeros(byte[] zip, String name, long n) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zi = new ZipInputStream(new java.io.ByteArrayInputStream(zip)); var zo = new ZipOutputStream(out)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) { zo.putNextEntry(new ZipEntry(e.getName())); zo.write(zi.readAllBytes()); }
            zo.putNextEntry(new ZipEntry(name));
            byte[] zeros = new byte[1 << 20];
            for (long left = n; left > 0; left -= zeros.length) zo.write(zeros, 0, (int) Math.min(zeros.length, left));
        }
        return out.toByteArray();
    }

    /** The same container with {@code extra} members added (name → text). */
    private static byte[] withMembers(byte[] zip, Map<String, String> extra) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zi = new ZipInputStream(new java.io.ByteArrayInputStream(zip)); var zo = new ZipOutputStream(out)) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) { zo.putNextEntry(new ZipEntry(e.getName())); zo.write(zi.readAllBytes()); }
            for (var e : new TreeMap<>(extra).entrySet()) {
                zo.putNextEntry(new ZipEntry(e.getKey()));
                zo.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
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

    private static String last(String s, String old, String now) {
        int i = s.lastIndexOf(old);
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
