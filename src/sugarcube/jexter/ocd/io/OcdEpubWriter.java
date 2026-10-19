package sugarcube.jexter.ocd.io;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDIndex;
import sugarcube.jexter.ocd.model.OCDPage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static sugarcube.jexter.ocd.io.EpubPackage.OPF;
import static sugarcube.jexter.core.JxNum.fmt;
import static sugarcube.jexter.core.JxName.safe;

/**
 * Writes the <b>OCD-EPUB</b> — the working format of the pipeline: one fully valid fixed-layout EPUB carrying two faces of the same
 * document.
 *
 * <ul>
 *   <li><b>Universal face</b> — {@code pages/*.xhtml} in the SVG-OCD dialect ({@link SvgOcdWriter}:
 *       glyphs as {@code <use>}d paths, no characters, <b>no fonts at all</b>), {@code images/},
 *       {@code media/}, cover, {@code nav.xhtml}/NCX, OPF. Opens pixel-faithfully in any EPUB
 *       reader with zero font dependency (text is simply not selectable there).</li>
 *   <li><b>Authoritative face</b> — the pages themselves ARE the model (SVG-OCD v2: text,
 *       reading order, lines, links, roles as data), fonts live once in {@code pages/fonts.svg}
 *       (outlines + metrics + cmap — the single representation), and the few non-visual
 *       members ride as JSON under {@code ocd/}: {@code meta.json}, {@code outline.json},
 *       {@code structures.json}, sparse {@code annots.json}, and {@code output-intent.icc} when the source
 *       carries an output intent with a profile.</li>
 * </ul>
 *
 * <p>Editable = the presence of {@code ocd/}. For platform distribution, export the
 * <i>generic</i> EPUB ({@link EpubWriter}: native selectable text + compiled OTF, no members).
 */
public final class OcdEpubWriter {

    public static final String JX = OCDVocab.MEMBERS;

    private OcdEpubWriter() {}

    /** Uniform projection. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        String uid = EpubPackage.uniqueId(doc);
        try (JxZip zip = new JxZip(out)) {
            zip.mimetype(EpubPackage.MIME);                       // STORED, first
            EpubPackage.container(zip);
            EpubPackage.images(zip, doc);
            media(zip, doc);
            EpubPackage.cover(zip, doc);
            String coverKey = pages(zip, doc);                    // SVG-OCD pages (universal face)
            members(zip, doc, coverKey);                          // ocd/* (authoritative face)
            String toc = doc.structure() != null
                    ? EpubPackage.tocOl(doc.structure(), OCDIndex.of(doc), EpubPackage::headingHref) : "";
            zip.deflated(OPF + "nav.xhtml", EpubPackage.fixedNav(doc, toc));
            zip.deflated(OPF + "toc.ncx", EpubPackage.fixedNcx(doc, uid));
            opf(zip, doc, uid);
        }
    }

    // ── universal face ───────────────────────────────────────────────────────────


    private static void media(JxZip zip, OCDDocument doc) throws IOException {
        for (var e : doc.media().entrySet())
            zip.deflated(OPF + "media/" + safe(e.getKey()), e.getValue());
    }

    /** Writes the pages and returns the cover key of page 1 (null for a document without pages). */
    private static String pages(JxZip zip, OCDDocument doc) throws IOException {
        var glyphs = new SvgOcdWriter.Fonts(doc);            // shared accumulator: aliases doc-stable
        String coverKey = null;
        int i = 1;
        for (OCDPage page : doc.pages()) {
            byte[] xhtml = utf8(pageXhtml(doc, page, i, glyphs));
            if (i == 1) coverKey = OCDVocab.coverKey(xhtml);
            zip.deflated(OCDVocab.pagePath(i++), xhtml);
        }
        zip.deflated(OPF + "pages/fonts.svg", utf8(SvgOcdWriter.glyphsSvg(doc, glyphs)));
        for (var e : doc.passengers().entrySet())                       // what the model does not own, verbatim
            zip.deflated(OPF + e.getKey(), e.getValue());
        return coverKey;
    }

    public static String pageXhtml(OCDDocument doc, OCDPage page, int i, SvgOcdWriter.Fonts glyphs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head>
                <meta charset="utf-8"/>
                <title>Page %d</title>
                <meta name="viewport" content="width=%s, height=%s"/>
                </head>
                <body style="margin:0;padding:0">
                %s
                </body>
                </html>
                """.formatted(i, fmt(page.svgWindow().width()), fmt(page.svgWindow().height()),
                              SvgOcdWriter.render(doc, page, glyphs));
    }

    // ── the patch: only what a page-scoped edit changed ──────────────────────────

    /**
     * The {@code patch} projection: a zip holding only the members a redaction changed — the pages the
     * {@link ConvertOptions#REDACT} spec names (re-rendered), the images those pages reference (a redacted
     * image is a fresh member), and the authoritative members it may have edited ({@code structures.json},
     * {@code annots.json}, and {@code meta.json} when {@link ConvertOptions#REDACT_META} purged it). A client
     * puts each member into its open container and reloads those pages; nothing else moves. Font aliases are
     * computed from the skeleton exactly as the full write does, so a patched page addresses
     * {@code pages/fonts.svg} unchanged. A patch that redacts page 1 also carries the cover rasterized from
     * the redacted page and the {@code meta.json} that keys it (§A2): the cover is page 1, and an EPUB reader
     * shows it on the shelf.
     */
    public static void patch(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        var pages = sugarcube.jexter.redact.Redactor.Zones.pagesOf(opt.get(ConvertOptions.REDACT));
        if (pages == null) throw new IllegalArgumentException("patch needs a redact spec that names its pages (not \"audit\")");
        var glyphs = new SvgOcdWriter.Fonts(doc);
        String coverKey = doc.coverKey();                 // page 1 untouched: the container's cover stays true
        try (JxZip zip = new JxZip(out)) {
            for (int i : new java.util.TreeSet<>(pages)) {
                OCDPage page = doc.page(i - 1);
                if (page == null) throw new IllegalArgumentException("no page " + i);
                byte[] xhtml = utf8(pageXhtml(doc, page, i, glyphs));
                if (i == 1) coverKey = OCDVocab.coverKey(xhtml);
                zip.deflated(OCDVocab.pagePath(i), xhtml);
                for (var im : page.images().toList()) {
                    byte[] b = doc.image(im.resourceRef());
                    if (b != null) zip.deflated(OPF + "images/" + safe(im.resourceRef()), b);
                }
            }
            if (!doc.structures().isEmpty()) zip.deflated(JX + "structures.json", utf8(OcdMembers.structuresJson(doc)));
            if (OcdMembers.hasAnnots(doc))   zip.deflated(JX + "annots.json", utf8(OcdMembers.annotsJson(doc)));
            // The purge runs on the model in Conversion, so without this member it runs and is thrown away:
            // the client's container keeps the title, the authors and the keywords that quote the very text
            // the zones removed, and exports them. A patch must return everything the edit changed.
            if (pages.contains(1)) EpubPackage.cover(zip, doc);     // Redactor dropped it: a fresh one
            if (pages.contains(1) || opt.get(ConvertOptions.REDACT_META))
                zip.deflated(JX + "meta.json", utf8(OcdMembers.metaJson(doc, coverKey)));
        }
    }

    // ── authoritative face: ocd/* ─────────────────────────────────────────────

    private static void members(JxZip zip, OCDDocument doc, String coverKey) throws IOException {
        zip.deflated(JX + "meta.json", utf8(OcdMembers.metaJson(doc, coverKey)));
        if (doc.outputIntent() != null && doc.outputIntent().profile() != null)
            zip.deflated(JX + OcdMembers.OUTPUT_INTENT_PROFILE, doc.outputIntent().profile());
        zip.deflated(JX + "outline.json", utf8(OcdMembers.outlineJson(doc)));
        if (!doc.structures().isEmpty())
            zip.deflated(JX + "structures.json", utf8(OcdMembers.structuresJson(doc)));
        if (OcdMembers.hasAnnots(doc))
            zip.deflated(JX + "annots.json", utf8(OcdMembers.annotsJson(doc)));
    }

    // ── the package: the shared fixed-layout skeleton, which declares BOTH faces ────

    private static void opf(JxZip zip, OCDDocument doc, String uid) throws IOException {
        var media = new StringBuilder();
        for (String name : doc.media().keySet()) {
            String n = safe(name);
            EpubPackage.item(media, "med-" + n, "media/" + n, EpubPackage.mediaType(n), null);
        }
        int p = 1;      // declared, or epubcheck refuses the container: an EPUB manifest lists every resource
        for (String path : doc.passengers().keySet())
            EpubPackage.item(media, "px-" + p++, path, EpubPackage.mediaType(path), null);
        var members = new StringBuilder();   // the authoritative face — every ocd/* member is a declared resource
        member(members, "jx-meta", "meta.json");
        if (doc.outputIntent() != null && doc.outputIntent().profile() != null)
            member(members, "jx-output-intent", OcdMembers.OUTPUT_INTENT_PROFILE);
        member(members, "jx-outline", "outline.json");
        if (!doc.structures().isEmpty()) member(members, "jx-structures", "structures.json");
        if (OcdMembers.hasAnnots(doc)) member(members, "jx-annots", "annots.json");
        var glyphs = new StringBuilder();
        EpubPackage.item(glyphs, "glyphs", "pages/fonts.svg", "image/svg+xml", null);
        zip.deflated(OPF + "content.opf", EpubPackage.fixedOpf(doc, uid, glyphs.toString(), media.toString(),
                members.toString(), EpubPackage.deterministicModified(doc)));
    }

    private static void member(StringBuilder manifest, String id, String name) {
        EpubPackage.item(manifest, id, "ocd/" + name, EpubPackage.mediaType(name), null);
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

}
