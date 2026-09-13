package sugarcube.jexter.write;

import sugarcube.jexter.ocd.analysis.Analysis;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxProgress;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.ocd.analysis.AnalysisStages;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.redact.RedactionAudit;
import sugarcube.jexter.redact.Redactor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one entry point from a source document to any converted artifact, and the single source of
 * truth for "target → bytes + how to serve them". Every writer now shares the same {@link Projection}
 * contract ({@code write(doc, OutputStream, ConvertOptions)}), so this class no longer adapts each
 * writer's output shape — it just streams to bytes and tags the result. Servers and the CLI route
 * through here instead of re-deriving per-writer semantics.
 *
 * <p>All settings — both import (any {@link ConvertOptions} key) and export ({@link ConvertOptions#PAGE},
 * {@link ConvertOptions#SELECTABLE}, {@link ConvertOptions#RENDER_ANNOTATIONS},
 * {@link ConvertOptions#DOCTAGS_GRID}) — travel in one {@link ConvertOptions} (or its {@code String}
 * map at the {@code /api} boundary). There is no writer-only side channel anymore.
 */
public final class Conversion {

    private Conversion() {}

    /** A converted artifact: its bytes plus everything a server needs to serve it. */
    public record Output(byte[] bytes, String mediaType, String filename, boolean inline) {}

    // ── convert ──────────────────────────────────────────────────────────────────

    /** Convert an already-imported document (re-export without re-parsing). */
    public static Output convert(OCDDocument doc, String target, ConvertOptions opt) throws IOException {
        Target t = Target.of(target);
        ConvertOptions o = (opt != null) ? opt : ConvertOptions.defaults();
        // On-demand re-analysis of an already-loaded OCD (Prism's Restructure actions) — off by default, so
        // loading an OCD-EPUB that did not come from a PDF never re-analyses it. Text before hierarchy, so a
        // re-segment feeds a following re-detect.
        if (o.get(ConvertOptions.RESTRUCTURE_TEXT))      Analysis.restructureText(doc, o);
        if (o.get(ConvertOptions.RESTRUCTURE_HIERARCHY)) Analysis.restructureHierarchy(doc, o);
        String sid = o.get(ConvertOptions.DEFAULT_STRUCTURE);                 // honour the selected structure
        if (sid != null && !sid.isEmpty() && doc.structureById(sid) != null) doc.defaultStructureId(sid);
        // Redaction runs BEFORE any projection, on the one model every writer reads — so a redacted PDF, EPUB,
        // Markdown, DocTags all agree — and it refuses to proceed unless its own audit of the result is clean.
        String zones = o.get(ConvertOptions.REDACT);
        if (zones != null && !zones.isBlank()) {
            String fill = o.get(ConvertOptions.REDACT_FILL);
            Redactor.apply(doc, Redactor.Zones.parse(zones, doc), fill == null || fill.isBlank() ? null : JxColor.ofHex(fill).argb());
            if (o.get(ConvertOptions.REDACT_META)) Redactor.purgeMeta(doc);
        }
        // A font the document did not carry — the enabling half of "write in any font": the outlines enter
        // the MODEL, so pages/fonts.svg is written from them like every other font and nothing downstream
        // learns a special case. Before the page set, because it is a document-scoped resource and the
        // page set only moves pages.
        String ttf = o.get(ConvertOptions.ADD_FONT);
        if (ttf != null && !ttf.isBlank()) addFont(doc, java.nio.file.Path.of(ttf), o.get(ConvertOptions.ADD_FONT_CHARS));

        // The page set is applied LAST, on the model every writer reads — after redaction, so a redaction
        // spec always names the pages of the source document, and before any projection, so every target
        // agrees on what the document now holds. Pages owns the reference fix-up; nothing else may reorder
        // or drop a page, or the outline, the links and the structure tree quietly point elsewhere.
        String set = o.get(ConvertOptions.PAGES);
        if (set != null && !set.isBlank()) {
            sugarcube.jexter.ocd.model.Pages.apply(doc, sugarcube.jexter.ocd.model.Pages.parse(set, doc.pageCount()));
            if (doc.pages().isEmpty()) throw new IOException("the page set leaves no page");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        o.at(JxProgress.Stage.WRITE, 0, 0, t.id);   // one shot: the writer reports its target, not a count
        t.writer.write(doc, bytes, o);
        return new Output(bytes.toByteArray(), t.mediaType, t.filename(o), t.inline);
    }

    /** The {@code zones} projection: every match of {@link ConvertOptions#MATCH}, in the audit's JSON shape. */
    private static void zones(OCDDocument doc, java.io.OutputStream out, ConvertOptions o) throws IOException {
        String re = o.get(ConvertOptions.MATCH);
        RedactionAudit.Report r = re == null || re.isBlank() ? new RedactionAudit.Report(doc.pages().size(), java.util.List.of())
                                                             : Redactor.match(doc, re);
        out.write(r.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Same, taking the {@code String}-map form used across the {@code /api} boundary. */
    public static Output convert(OCDDocument doc, String target, Map<String, String> options) throws IOException {
        return convert(doc, target, ConvertOptions.fromMap(options));
    }

    /** Convert raw PDF bytes: import (with the same options) then export. */
    public static Output convert(byte[] data, String target, Map<String, String> options) throws IOException {
        return convert(data, target, ConvertOptions.fromMap(options));
    }

    /** Same, with the options already built — the form a host uses when it binds a
     *  {@link JxProgress.Sink} to watch the conversion. */
    public static Output convert(byte[] data, String target, ConvertOptions o) throws IOException {
        // "Opening" starts HERE, not when the PDF is parsed: spooling the bytes, hashing them for the
        // document identity and parsing the file take seconds on a big book, and until this the viewer
        // was still showing the upload it had long finished. The page count refines the same stage later.
        o.at(JxProgress.Stage.OPEN, 0, 0);
        // A PDF goes straight from the bytes we were handed: PDFBox reads an array as happily as a file,
        // and the array is referenced for the whole call anyway, so spooling it to disk and reading it
        // back bought nothing but a write and a read. The OCD-EPUB branch still needs a file — reading a
        // zip wants a seekable source — and a container is the smaller of the two.
        if (!isOcd(data)) {
            OCDDocument doc = PdfImporter.convert(data, o);
            if (doc.pages().isEmpty()) throw new IOException("Document has no pages");
            return convert(doc, target, o);
        }
        File tmp = File.createTempFile("jexter-", ".ocd.epub");
        try {
            Files.write(tmp.toPath(), data);
            // a patch of an OCD-EPUB reads only the pages its zones name (skeleton + those pages): the round trip
            // costs what the edit touches, not the document
            var pages = "patch".equals(Target.of(target).id) ? Redactor.Zones.pagesOf(o.get(ConvertOptions.REDACT)) : null;
            if (pages != null) return convert(OCDReader.readPages(tmp, pages.stream().map(p -> p - 1).collect(java.util.stream.Collectors.toSet())), target, o);
            return convert(load(tmp, o), target, o);          // OCD-EPUB → OCDReader: the selected default structure is preserved, so the
                                                              // cloud re-exports the up-to-date OCD without re-analyzing the PDF; else PDF import
        } catch (IOException | RuntimeException e) { throw e; }   // a refused redaction or a bad option says so in its own words
        catch (Exception e)    { throw new IOException("unreadable source (not a valid PDF or OCD-EPUB)", e); }
        finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** ZIP local-file-header magic {@code PK\u0003\u0004} — an OCD-EPUB container, as opposed to a
     *  raw {@code %PDF} stream. Lets the byte[] entry round-trip an edited OCD-EPUB for export. */
    private static boolean isOcd(byte[] d) {
        return d.length >= 4 && d[0] == 0x50 && d[1] == 0x4B && d[2] == 0x03 && d[3] == 0x04;
    }

    /** The first four bytes of a file — all {@link #isOcd} needs. Never throws: an unreadable or
     *  empty source falls through to the PDF importer, which reports it in its own words. */
    private static byte[] head(File f) {
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            return in.readNBytes(4);
        } catch (Exception e) { return new byte[0]; }
    }

    /** Valid target ids, e.g. for a {@code /targets} endpoint, a CLI usage line, or a UI. */
    public static Set<String> targets() {
        return new LinkedHashSet<>(Target.ids());
    }

    // ── the target registry: the only place that knows every projection ──────────
    public enum Target {
        SVG        ("svg",         "image/svg+xml; charset=utf-8", true,  SvgWriter::write),
        PDF        ("pdf",         "application/pdf",              true,  PdfWriter::write),
        EPUB       ("epub",        "application/epub+zip",         false, EpubWriter::write),
        EPUB_REFLOW("epub-reflow", "application/epub+zip",         false, ReflowEpubWriter::write),
        HTML       ("html",        "text/html; charset=utf-8",     true,  HtmlWriter::write),
        MD         ("md",          "text/markdown; charset=utf-8", true,  MarkdownWriter::write),
        DOCTAGS    ("doctags",     "text/plain; charset=utf-8",    true,  DocTagsWriter::write),
        OCD        ("ocd",         "application/epub+zip",         false, OcdEpubWriter::write),   // the OCD-EPUB working format (SVG-OCD pages + ocd/ members)
        STAGES     ("stages",      "application/json; charset=utf-8", true, AnalysisStages::write),
        AUDIT      ("audit",       "application/json; charset=utf-8", true, RedactionAudit::write),  // what is still under the boxes
        ZONES      ("zones",       "application/json; charset=utf-8", true, Conversion::zones),       // matches of ?match=regex, as zones
        PATCH      ("patch",       "application/zip",              false, OcdEpubWriter::patch);      // only the members a redaction changed

        public final String id, mediaType;
        public final boolean inline;
        final Projection writer;

        Target(String id, String mediaType, boolean inline, Projection writer) {
            this.id = id; this.mediaType = mediaType; this.inline = inline; this.writer = writer;
        }

        public static List<String> ids() { return Arrays.stream(values()).map(t -> t.id).toList(); }

        /** Resolve a target by id, accepting a few friendly aliases. */
        public static Target of(String name) {
            String n = (name == null ? "" : name.trim().toLowerCase());
            n = switch (n) {
                case "markdown"                              -> "md";
                case "reflow", "reflow-epub", "epub_reflow"  -> "epub-reflow";
                case "doctags.txt", "txt"                    -> "doctags";
                default -> n;
            };
            for (Target t : values()) if (t.id.equals(n)) return t;
            throw new IllegalArgumentException("unknown target '" + name + "' (" + String.join("|", ids()) + ")");
        }

        /** The target whose natural output extension matches {@code file}, or {@code null}. */
        public static Target ofFilename(String file) {
            String f = file.toLowerCase();
            if (f.endsWith(".doctags.txt") || f.endsWith(".doctags")) return DOCTAGS;
            if (f.endsWith(".ocd.epub")) return OCD;
            int dot = f.lastIndexOf('.');
            String ext = dot < 0 ? "" : f.substring(dot + 1);
            return switch (ext) {
                case "svg"  -> SVG;
                case "pdf"  -> PDF;
                case "epub" -> EPUB;          // reflow shares .epub → ask explicitly with --to
                case "html", "htm" -> HTML;
                case "md", "markdown" -> MD;
                case "txt"  -> DOCTAGS;
                case "ocd"  -> OCD;
                default     -> null;
            };
        }

        /** Default download name for this target under the given options. */
        public String filename(ConvertOptions o) {
            return switch (this) {
                case SVG         -> "page-" + o.get(ConvertOptions.PAGE) + ".svg";
                case PDF         -> "document.pdf";
                case EPUB        -> "document.epub";
                case EPUB_REFLOW -> "document-reflow.epub";
                case HTML        -> "document.html";
                case MD          -> "document.md";
                case DOCTAGS     -> "document.doctags.txt";
                case OCD         -> "document.ocd.epub";
                case STAGES      -> "analysis.json";
                case AUDIT       -> "audit.json";
                case ZONES       -> "zones.json";
                case PATCH       -> "patch.zip";
            };
        }
    }

    // ── CLI ──────────────────────────────────────────────────────────────────────
    // java …Conversion <in.pdf|in.ocd.epub> <out.EXT> [--to=<target>] [--key=value | --flag] …
    //   target is taken from --to, else inferred from the output extension.
    //   no args → the multi-target Swing launcher (WriterCli).
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { WriterCli.launch(); return; }
        if (args.length < 2) {
            System.err.println("usage: <in.pdf|in.ocd.epub> <out.ext> [--to=" + String.join("|", Target.ids())
                    + "] [--key=value | --flag] …   (no args → window)");
            System.exit(2);
            return;
        }
        // Flags are position-independent: '--to=x out.md' and 'out.md --to=x' both work,
        // and a flag can never be mistaken for the output path.
        java.util.List<String> positional = new java.util.ArrayList<>();
        java.util.List<String> flags = new java.util.ArrayList<>();
        for (String a : args) (a.startsWith("--") ? flags : positional).add(a);
        if (positional.size() != 2) {
            System.err.println("usage: <in.pdf|in.ocd.epub> <out.ext> [--to=" + String.join("|", Target.ids())
                    + "] [--key=value | --flag] …   (no args → window)");
            System.exit(2);
            return;
        }
        File in = new File(positional.get(0));
        Path out = Path.of(positional.get(1));
        Map<String, String> opts = parse(flags.toArray(String[]::new), 0);

        String to = opts.remove("to");
        Target target = (to != null) ? Target.of(to) : Target.ofFilename(out.getFileName().toString());
        if (target == null) {
            System.err.println("cannot infer target from '" + out + "'; pass --to=" + String.join("|", Target.ids()));
            System.exit(2);
            return;
        }
        OCDDocument doc = load(in, ConvertOptions.fromMap(opts));
        Output o = convert(doc, target.id, opts);
        Files.write(out, o.bytes());
        System.out.println(status(in, out, doc, target, o));
    }

    /** Load a source. The kind is read from the CONTENT, never from the name — `PK\u0003\u0004` is the
     *  OCD-EPUB container, anything else is imported as a PDF (FORMAT.md §A6, and the same rule the
     *  {@code byte[]} entry point already applied). Sniffing the extension made the two paths disagree
     *  and gave a correct container named {@code .ocd} a PDF parse error for a diagnostic; the legacy
     *  {@code .ocd} JSON container is gone, so a zip here can only be this format. */
    public static OCDDocument load(File in, ConvertOptions opt) throws Exception {
        OCDDocument doc = isOcd(head(in)) ? OCDReader.read(in) : PdfImporter.convert(in, opt);
        // A pageless document (cyclic or broken page tree, decoy content) has no convertible
        // substance: refuse here — the one load authority — rather than let every writer emit a
        // structurally invalid shell (empty spine, dangling nav).
        if (doc.pages().isEmpty()) throw new java.io.IOException("Document has no pages: " + in.getName());
        return doc;
    }

    /** Parse {@code --key=value} and bare {@code --flag} (⇒ "true") into a flat map. */
    public static Map<String, String> parse(String[] args, int from) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String a = args[i].startsWith("--") ? args[i].substring(2) : args[i];
            int eq = a.indexOf('=');
            if (eq >= 0) m.put(a.substring(0, eq), a.substring(eq + 1));
            else m.put(a, "true");
        }
        return m;
    }

    private static String status(File in, Path out, OCDDocument doc, Target t, Output o) {
        return new JxStringer().obj()
                .str("source", in.getName())
                .str("target", t.id)
                .str("out", out.getFileName().toString())
                .num("bytes", o.bytes().length)
                .num("pages", doc.pageCount())
                .num("fonts", doc.fonts().size())
                .num("images", doc.images().size())
                .end().toString();
    }
    /** Add an existing TrueType/OpenType font to the document's library, for the characters given (or
     *  printable ASCII). {@code convert.TtfImporter} reads the outlines — it is the FontBox-coupled half —
     *  and they are stored as an ordinary {@link OCDFont}, which is what makes the rest free: the writer
     *  emits {@code pages/fonts.svg} from the model, so an added font is written, read back and rendered by
     *  exactly the code every imported font goes through.
     *
     *  <p>Glyphs the font does not map, or maps to no contour (the space), carry no outline — the same
     *  contract as an imported font, where an inkless glyph keeps its advance and paints nothing. */
    static void addFont(OCDDocument doc, java.nio.file.Path ttf, String chars) throws IOException {
        addFont(doc, java.nio.file.Files.readAllBytes(ttf), chars);
    }

    /** The same, from BYTES: the CLI has a path, a browser has only the file it was handed. */
    public static void addFont(OCDDocument doc, byte[] bytes, String chars) throws IOException {
        String take = (chars == null || chars.isEmpty()) ? PRINTABLE_ASCII : chars;
        int[] cps = take.codePoints().distinct().toArray();
        var glyphs = sugarcube.jexter.pdfimport.TtfImporter.extract(bytes, cps);
        if (glyphs.isEmpty()) throw new IOException("addFont: the font maps none of those characters");
        String name = sugarcube.jexter.pdfimport.TtfImporter.name(bytes);
        var font = new sugarcube.jexter.ocd.model.OCDFont(name).family(name)
                .id(sugarcube.jexter.core.JxName.safe(name)).embedded(true);
        int gid = 1;
        for (var g : glyphs) {
            var path = sugarcube.jexter.core.JxPath.ofSvg(g.emSvg());
            if (path == null || path.isEmpty()) continue;
            font.add(new sugarcube.jexter.ocd.model.OCDGlyph(gid, new String(Character.toChars(g.cp())), path, g.advance()));
            font.map(g.cp(), gid++);
        }
        doc.add(font);
        JxLog.info("addFont", name + " — " + (gid - 1) + " glyph(s)");
    }

    private static final String PRINTABLE_ASCII =
            " !\"#$%&\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";

}
