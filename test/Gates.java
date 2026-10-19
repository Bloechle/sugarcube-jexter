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
//   EDITED    a path a viewer moved (Prism's editor composes the move in front of the page flip)
//             reads back moved — once, it was read back where it started.
//   HOSTILE   broken and malicious variants of the container: refused with context, or read with a
//             warning — never read in silence, never a leaked file, never an entity expansion.
//
//   node nimbus-jexter.cjs --run=test          the whole run, on any OS: compile, corpus, every gate
//
// By hand, from the repo root, the engine compiled into target/classes (`;` on Windows):
//   SOURCE_DATE_EPOCH=1000000000 java -cp "target/classes:lib/*" test/Gates.java           the corpus
//   SOURCE_DATE_EPOCH=1000000000 java -cp "target/classes:lib/*" test/Gates.java a.pdf …   these PDFs
//
// With no PDF named, the corpus is BUILT first into target/corpus by the generators in test/fixtures
// (Python: test/fixtures/requirements.txt) — it is produced, so it is never committed. Only public
// engine API is used, so single-file source mode runs this file as it is.

import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.*;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.write.Conversion;

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
    private static final Logger READER = Logger.getLogger(OCDReader.class.getName());   // held: JUL keeps loggers weakly
    private static int failed;

    public static void main(String[] args) throws Exception {
        if (System.getenv("SOURCE_DATE_EPOCH") == null) {      // an unpinned clock alone fails REPRO
            System.err.println("SOURCE_DATE_EPOCH is not set - run `node nimbus-jexter.cjs --run=test`, or set it (e.g. 1000000000)");
            System.exit(2);
        }
        if (args.length == 0) args = corpus();
        captureWarnings();
        Path tmp = Files.createTempDirectory("gates");
        for (String a : args) {
            File pdf = new File(a);
            System.out.println("== " + pdf.getName());
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
            gate("STRADDLE", () -> straddle(model));
            gate("CLIPS",    () -> clips(model, OCDReader.read(container.toFile())));
            gate("EDITED",   () -> edited(ocd, tmp));
            gate("HOSTILE",  () -> hostile(ocd, tmp));
        }
        System.out.println(failed == 0 ? "ALL GATES GREEN" : failed + " GATE(S) FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ── the corpus ───────────────────────────────────────────────────────────────

    private static final Path FIXTURES = Path.of("test", "fixtures"), CORPUS = Path.of("target", "corpus");
    private static final String WELCOME = "src/sugarcube/jexter/ui/pdf/web/welcome.pdf";

    /** Builds target/corpus with the fixture generators; returns every PDF in it, plus the app's welcome page. */
    private static String[] corpus() throws Exception {
        if (!Files.isDirectory(FIXTURES)) throw new IllegalStateException("run from the repo root: no " + FIXTURES);
        if (Files.exists(CORPUS)) try (var walk = Files.walk(CORPUS)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
        Files.createDirectories(CORPUS);
        String py = python();
        fixture(py, "generate_matrix.py", "--all", "--out", CORPUS.toString());
        fixture(py, "make_rich.py", CORPUS.resolve("rich.pdf").toString());
        fixture(py, "make_tagged.py", CORPUS.resolve("tagged.pdf").toString());
        List<String> pdfs = new ArrayList<>();
        try (var ls = Files.list(CORPUS)) { ls.filter(p -> p.toString().endsWith(".pdf")).sorted().forEach(p -> pdfs.add(p.toString())); }
        pdfs.add(WELCOME);
        return pdfs.toArray(String[]::new);
    }

    private static void fixture(String python, String script, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(python, FIXTURES.resolve(script).toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        if (p.waitFor() != 0) throw new IllegalStateException(script + " failed - the corpus is incomplete, nothing was gated");
    }

    /** The first Python that answers. Windows has no python3 but a Store alias that pretends to be one. */
    private static String python() throws Exception {
        boolean win = System.getProperty("os.name").startsWith("Windows");
        for (String py : win ? List.of("python", "py") : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(py, "--version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return py;
            } catch (java.io.IOException notOnPath) { /* next */ }
        }
        throw new IllegalStateException("no Python on the PATH - the fixture generators need one (test/fixtures/requirements.txt)");
    }

    // ── the runner ───────────────────────────────────────────────────────────────

    private interface Check { List<String> run() throws Exception; }

    private static void gate(String name, Check c) {
        List<String> problems;
        try { problems = c.run(); }
        catch (Throwable t) { problems = List.of("threw " + t); }
        if (problems.isEmpty()) { System.out.printf("   %-9s ok%n", name); return; }
        failed++;
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
        }
        if (x instanceof OCDPath a && y instanceof OCDPath b) {
            if (a.fill() != b.fill()) d.add(String.format("fill %08X -> %08X", a.fill(), b.fill()));
            if (a.stroke() != b.stroke()) d.add(String.format("stroke %08X -> %08X", a.stroke(), b.stroke()));
            if (Math.abs(a.strokeWidth() - b.strokeWidth()) > TOLERANCE) d.add("strokeWidth " + a.strokeWidth() + " -> " + b.strokeWidth());
            if (!a.geometry().toSvg().equals(b.geometry().toSvg())) d.add("geometry");
        }
        return d;
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
                page.nodes().filter(n -> n.clipId() != null && !page.clips().containsKey(n.clipId()))
                        .forEach(n -> bad.add(page.id() + " " + n.id() + " names missing clip " + n.clipId()));
            }
        return bad;
    }

    // ── EDITED ───────────────────────────────────────────────────────────────────

    private static List<String> edited(byte[] good, Path tmp) throws Exception {
        var m = java.util.regex.Pattern.compile("<path id=\"([^\"]+)\"[^>]* transform=\"").matcher(member(good, PAGE));
        if (!m.find()) return ok();                                    // no content path on page 1
        String id = m.group(1), at = m.group();
        Path f = tmp.resolve("edited.ocd.epub");
        Files.write(f, onPage(good, s -> once(s, at, at + "translate(7 0) ")));
        double before = pathX(OCDReader.read(tmpWrite(tmp, good)), id), after = pathX(OCDReader.read(f.toFile()), id);
        return Math.abs(after - before - 7) < TOLERANCE ? ok() : List.of(id + " moved by " + (after - before) + ", expected 7");
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

        List<String> bad = new ArrayList<>();
        var variants = new LinkedHashMap<String, byte[]>();
        for (var e : edits.entrySet()) {
            String edited = e.getValue().apply(page);
            if (edited.equals(page)) {                 // a payload that hits nothing tests nothing
                if (!e.getKey().equals("badclip")) bad.add(e.getKey() + ": the payload found nothing to hit");
                continue;                              // a page that does not clip has no clip to break
            }
            variants.put(e.getKey(), rezip(good, (n, b) -> n.endsWith(PAGE) ? edited.getBytes(StandardCharsets.UTF_8) : b));
        }
        variants.put("nofonts", rezip(good, (n, b) -> n.endsWith(FONTS) ? null : b));
        variants.put("trunc", Arrays.copyOf(good, good.length / 2));

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

    /** "refused: …", "warned: …", "LEAK …" or "silent". A read that never returns is a refusal that failed. */
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
