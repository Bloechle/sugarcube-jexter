package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.analysis.LanguageDetector;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStruct;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static sugarcube.jexter.write.EpubPackage.OPF;
import static sugarcube.jexter.write.EpubPackage.num;
import static sugarcube.jexter.write.EpubPackage.safe;

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
 *       reading order, lines, links, roles as data), fonts live once in {@code pages/f.svg}
 *       (outlines + metrics + cmap — the single representation), and the few non-visual
 *       members ride as JSON under {@code jexter/}: {@code meta.json}, {@code outline.json},
 *       {@code structures.json}, sparse {@code annots.json}.</li>
 * </ul>
 *
 * <p>Editable = the presence of {@code jexter/}. For platform distribution, export the
 * <i>generic</i> EPUB ({@link EpubWriter}: native selectable text + compiled OTF, no members).
 */
public final class OcdEpubWriter {

    public static final String MIME = EpubPackage.MIME;
    static final String JX = OPF + "jexter/";

    private OcdEpubWriter() {}

    /** Uniform projection. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        LanguageDetector.detect(doc);
        String uid = EpubPackage.uniqueId(doc);
        try (JxZip zip = new JxZip(out)) {
            zip.mimetype(MIME);                                   // STORED, first
            EpubPackage.container(zip);
            images(zip, doc);
            media(zip, doc);
            EpubPackage.cover(zip, doc);
            pages(zip, doc);                                      // SVG-OCD pages (universal face)
            members(zip, doc);                                    // jexter/* (authoritative face)
            nav(zip, doc);
            ncx(zip, doc, uid);
            opf(zip, doc, uid);
        }
    }

    // ── universal face ───────────────────────────────────────────────────────────

    private static void images(JxZip zip, OCDDocument doc) throws IOException {
        for (var e : doc.images().entrySet())
            zip.deflated(OPF + "images/" + safe(e.getKey()), e.getValue());
    }

    private static void media(JxZip zip, OCDDocument doc) throws IOException {
        for (var e : doc.media().entrySet())
            zip.deflated(OPF + "media/" + safe(e.getKey()), e.getValue());
    }

    private static void pages(JxZip zip, OCDDocument doc) throws IOException {
        var glyphs = new SvgOcdWriter.Fonts(doc);            // shared accumulator: aliases doc-stable
        int i = 1;
        for (OCDPage page : doc.pages()) {
            String svg = SvgOcdWriter.render(doc, page, glyphs);
            String xhtml = """
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
                    """.formatted(i, num(page.displayWidth()), num(page.displayHeight()), svg);
            zip.deflated(OPF + "pages/" + pageFile(i++) + ".xhtml", xhtml);
        }
        zip.deflated(OPF + "pages/f.svg", utf8(SvgOcdWriter.glyphsSvg(doc, glyphs)));
    }

    // ── authoritative face: jexter/* ─────────────────────────────────────────────

    private static void members(JxZip zip, OCDDocument doc) throws IOException {
        zip.deflated(JX + "meta.json", utf8(OcdMembers.metaJson(doc)));
        zip.deflated(JX + "outline.json", utf8(OcdMembers.outlineJson(doc)));
        if (!doc.structures().isEmpty())
            zip.deflated(JX + "structures.json", utf8(OcdMembers.structuresJson(doc)));
        if (OcdMembers.hasAnnots(doc))
            zip.deflated(JX + "annots.json", utf8(OcdMembers.annotsJson(doc)));
    }

    // ── logical navigation (shared skeleton) ─────────────────────────────────────

    private static void nav(JxZip zip, OCDDocument doc) throws IOException {
        OCDIndex ix = OCDIndex.of(doc);
        String tocInner = doc.structure() != null
                ? EpubPackage.tocOl(doc.structure(), ix, OcdEpubWriter::headingHref) : "";
        if (tocInner.isBlank()) {
            var sb = new StringBuilder();
            int i = 1;
            for (OCDPage ignored : doc.pages())
                sb.append("    <li><a href=\"pages/").append(pageFile(i)).append(".xhtml\">Page ")
                  .append(i++).append("</a></li>\n");
            tocInner = sb.toString();
        }
        var plist = new StringBuilder();
        int i = 1;
        for (OCDPage ignored : doc.pages())
            plist.append("    <li><a href=\"pages/").append(pageFile(i)).append(".xhtml\">")
                 .append(i++).append("</a></li>\n");
        String start = "pages/" + pageFile(1) + ".xhtml";
        zip.deflated(OPF + "nav.xhtml", EpubPackage.navDoc(EpubPackage.title(doc), tocInner, start, plist.toString()));
    }

    private static String headingHref(OCDStruct s) {
        int p = EpubPackage.firstPage(s);
        return "pages/" + pageFile((p < 0 ? 0 : p) + 1) + ".xhtml";
    }

    private static void ncx(JxZip zip, OCDDocument doc, String uid) throws IOException {
        var pts = new StringBuilder();
        var heads = new java.util.ArrayList<OCDStruct>();
        if (doc.structure() != null) EpubPackage.collectHeadings(doc.structure(), heads);
        OCDIndex ix = OCDIndex.of(doc);
        var ncx = new EpubPackage.Ncx();
        if (!heads.isEmpty()) {
            for (OCDStruct h : heads) ncx.add(EpubPackage.label(h, ix), headingHref(h));
        } else {
            int n = 1;
            for (OCDPage ignored : doc.pages()) { ncx.add("Page " + n, "pages/" + pageFile(n) + ".xhtml"); n++; }
        }
        zip.deflated(OPF + "toc.ncx", EpubPackage.ncx(uid, EpubPackage.title(doc), ncx.inner()));
    }

    // ── package: manifest declares BOTH faces ────────────────────────────────────

    private static void opf(JxZip zip, OCDDocument doc, String uid) throws IOException {
        var manifest = new StringBuilder();
        var spine = new StringBuilder();
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        manifest.append("    <item id=\"glyphs\" href=\"pages/f.svg\" media-type=\"image/svg+xml\"/>\n");
        if (!doc.pages().isEmpty())
            manifest.append("    <item id=\"cover-image\" href=\"").append(EpubPackage.COVER_HREF)
                    .append("\" media-type=\"").append(EpubPackage.COVER_MEDIA).append("\" properties=\"cover-image\"/>\n");
        for (var e : doc.images().entrySet()) {
            String n = safe(e.getKey());
            manifest.append("    <item id=\"img-").append(n).append("\" href=\"images/").append(n)
                    .append("\" media-type=\"").append(EpubPackage.mediaForImage(n)).append("\"/>\n");
        }
        for (var e : doc.media().entrySet()) {
            String n = safe(e.getKey());
            manifest.append("    <item id=\"med-").append(n).append("\" href=\"media/").append(n)
                    .append("\" media-type=\"").append(mediaFor(n)).append("\"/>\n");
        }
        int i = 1;
        for (OCDPage ignored : doc.pages()) {
            String pf = pageFile(i);
            manifest.append("    <item id=\"page-").append(i).append("\" href=\"pages/").append(pf)
                    .append(".xhtml\" media-type=\"application/xhtml+xml\" properties=\"svg\"/>\n");
            spine.append("    <itemref idref=\"page-").append(i).append("\"/>\n");
            i++;
        }
        // the authoritative face — every jexter/* member is a declared publication resource
        jx(manifest, "jx-meta", "meta.json");
        jx(manifest, "jx-outline", "outline.json");
        if (!doc.structures().isEmpty()) jx(manifest, "jx-structures", "structures.json");
        if (OcdMembers.hasAnnots(doc)) jx(manifest, "jx-annots", "annots.json");

        String coverMeta = doc.pages().isEmpty() ? "" : "<meta name=\"cover\" content=\"cover-image\"/>\n";
        String metaExtra = coverMeta + EpubPackage.dublinCore(doc.meta()) + EpubPackage.accessibilityMeta(doc, true);
        String opf = EpubPackage.packageOpf(uid, EpubPackage.title(doc), EpubPackage.lang(doc),
                metaExtra, manifest.toString(), spine.toString(), true, EpubPackage.deterministicModified(doc));
        zip.deflated(OPF + "content.opf", opf);
    }

    private static void jx(StringBuilder manifest, String id, String href) {
        manifest.append("    <item id=\"").append(id).append("\" href=\"jexter/").append(href)
                .append("\" media-type=\"application/json\"/>\n");
    }

    private static String mediaFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3"))  return "audio/mpeg";
        if (n.endsWith(".m4a"))  return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".oga")) return "audio/ogg";
        if (n.endsWith(".wav"))  return "audio/wav";
        return "application/octet-stream";
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String pageFile(int index)   { return String.format("page-%03d", index); }
}
