package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.ocd.analysis.LanguageDetector;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.font.JxFont;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDPage;

import java.io.IOException;
import java.io.OutputStream;

import static sugarcube.jexter.write.EpubPackage.OPF;
import static sugarcube.jexter.write.EpubPackage.num;
import static sugarcube.jexter.write.EpubPackage.safe;

/**
 * Writes a {@link OCDDocument} as a clean, normalized <b>EPUB3 fixed-layout replica</b> that is
 * also a lossless round-trip pivot.
 *
 * <p>The container is a valid EPUB (opens in e-readers) carrying:
 * <ul>
 *   <li><b>physical</b> — {@code pages/*.xhtml} (fixed-layout, inline SVG fac-simile),
 *       embedded {@code fonts/*.otf} via {@code @font-face} (real selectable text), {@code images/};</li>
 *   <li><b>logical</b> — {@code nav.xhtml} (TOC + page-list).</li>
 * </ul>
 *
 * <p>The EPUB is purely a delivery <em>projection</em> for e-readers — it carries no model-relecture
 * sidecar. <b>OCD is the vector pivot</b>: anything needed to reconstruct the model lives in the
 * OCD-EPUB members, never duplicated here.
 *
 * <p>The OCF container, cover, metadata, navigation, NCX and package skeleton are shared with the
 * reflowable export through {@link EpubPackage}; only the SVG page content, the data sidecar and
 * the pre-paginated manifest/spine are specific here. Rendering reuses the audited
 * {@link SvgWriter} (page SVG) and {@link JxFont} (OTF compiler).
 *
 * @see ReflowEpubWriter reflowable, content-first counterpart driven by the logical structure
 */
public final class EpubWriter {

    public static final String MIME = EpubPackage.MIME;

    private EpubWriter() {}

    /** Uniform projection. {@link ConvertOptions#RENDER_ANNOTATIONS} keeps the annotation layer. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        write(doc, out, opt.get(ConvertOptions.RENDER_ANNOTATIONS));
    }

    public static void write(OCDDocument doc, OutputStream out, boolean annotations) throws IOException {
        LanguageDetector.detect(doc);                 // fill dc:language when the PDF declared none (export-time fallback)
        String uid = EpubPackage.uniqueId(doc);
        try (JxZip zip = new JxZip(out)) {
            zip.mimetype(MIME);                                  // STORED, first
            EpubPackage.container(zip);                          // META-INF/container.xml
            fonts(zip, doc);                                     // OEBPS/fonts/*.otf
            fontsCss(zip, doc);                                  // OEBPS/fonts.css (@font-face)
            images(zip, doc);                                    // OEBPS/images/*
            EpubPackage.cover(zip, doc);                         // OEBPS/images/cover.jpg (rasterized page 1)
            pagesXhtml(zip, doc, annotations);                   // OEBPS/pages/page-NNN.xhtml (inline SVG)
            nav(zip, doc);                                       // OEBPS/nav.xhtml (logical: toc + page-list)
            ncx(zip, doc, uid);                                  // OEBPS/toc.ncx (EPUB2 compat)
            opf(zip, doc, uid);                                  // OEBPS/content.opf (manifest + spine)
        }
    }

    // ── fonts ──────────────────────────────────────────────────────────────────

    private static void fonts(JxZip zip, OCDDocument doc) throws IOException {
        for (OCDFont f : doc.fonts().values()) {
            try {
                byte[] otf = JxFont.toOtf(f);
                if (otf != null && otf.length > 0) zip.deflated(OPF + "fonts/" + safe(f.id()) + ".otf", otf);
            } catch (Exception e) {
                JxLog.debug(EpubWriter.class, "font otf compile failed: " + f.name(), e);
            }
        }
    }

    private static void fontsCss(JxZip zip, OCDDocument doc) throws IOException {
        var css = new StringBuilder();
        for (OCDFont f : doc.fonts().values()) {
            String n = safe(f.id());
            css.append("@font-face{font-family:\"").append(n)
               .append("\";src:url(\"fonts/").append(n).append(".otf\") format(\"opentype\");}\n");
        }
        zip.deflated(OPF + "fonts.css", css.toString());
    }

    private static void images(JxZip zip, OCDDocument doc) throws IOException {
        for (var e : doc.images().entrySet())
            zip.deflated(OPF + "images/" + safe(e.getKey()), e.getValue());
    }

    // ── physical: fixed-layout pages (inline SVG fac-simile) ─────────────────────

    private static void pagesXhtml(JxZip zip, OCDDocument doc, boolean annotations) throws IOException {
        int i = 1;
        for (OCDPage page : doc.pages()) {
            String svg = SvgWriter.render(doc, page, annotations).replaceFirst("(?s)^<\\?xml[^>]*\\?>\\s*", "");
            String xhtml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE html>
                    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                    <head>
                    <meta charset="utf-8"/>
                    <title>%s</title>
                    <meta name="viewport" content="width=%s, height=%s"/>
                    <link rel="stylesheet" type="text/css" href="../fonts.css"/>
                    </head>
                    <body style="margin:0;padding:0">
                    %s
                    </body>
                    </html>
                    """.formatted("OCDPage " + i, num(page.displayWidth()), num(page.displayHeight()), svg);
            zip.deflated(OPF + "pages/" + pageFile(i++) + ".xhtml", xhtml);
        }
    }

    // ── logical: EPUB3 navigation (toc + landmarks + page-list) ──────────────────

    private static void nav(JxZip zip, OCDDocument doc) throws IOException {
        OCDIndex ix = OCDIndex.of(doc);
        String tocInner = doc.structure() != null
                ? EpubPackage.tocOl(doc.structure(), ix, EpubWriter::headingHref) : "";
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
        zip.deflated(OPF + "nav.xhtml", EpubPackage.navDoc(title(doc), tocInner, start, plist.toString()));
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
        zip.deflated(OPF + "toc.ncx", EpubPackage.ncx(uid, title(doc), ncx.inner()));
    }

    // ── package: manifest + spine (fixed-layout, pre-paginated) ──────────────────

    private static void opf(JxZip zip, OCDDocument doc, String uid) throws IOException {
        var manifest = new StringBuilder();
        var spine = new StringBuilder();
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        manifest.append("    <item id=\"css\" href=\"fonts.css\" media-type=\"text/css\"/>\n");
        if (!doc.pages().isEmpty())
            manifest.append("    <item id=\"cover-image\" href=\"").append(EpubPackage.COVER_HREF)
                    .append("\" media-type=\"").append(EpubPackage.COVER_MEDIA).append("\" properties=\"cover-image\"/>\n");
        for (OCDFont f : doc.fonts().values()) {
            String n = safe(f.id());
            manifest.append("    <item id=\"font-").append(n).append("\" href=\"fonts/").append(n)
                    .append(".otf\" media-type=\"font/otf\"/>\n");
        }
        for (var e : doc.images().entrySet()) {
            String n = safe(e.getKey());
            manifest.append("    <item id=\"img-").append(n).append("\" href=\"images/").append(n)
                    .append("\" media-type=\"").append(EpubPackage.mediaForImage(n)).append("\"/>\n");
        }
        int i = 1;
        for (OCDPage ignored : doc.pages()) {
            String pf = pageFile(i);
            manifest.append("    <item id=\"page-").append(i).append("\" href=\"pages/").append(pf)
                    .append(".xhtml\" media-type=\"application/xhtml+xml\" properties=\"svg\"/>\n");
            spine.append("    <itemref idref=\"page-").append(i).append("\"/>\n");
            i++;
        }
        String coverMeta = doc.pages().isEmpty() ? "" : "<meta name=\"cover\" content=\"cover-image\"/>\n";
        String metaExtra = coverMeta + EpubPackage.dublinCore(doc.meta()) + EpubPackage.accessibilityMeta(doc, true);
        String opf = EpubPackage.packageOpf(uid, title(doc), lang(doc), metaExtra, manifest.toString(), spine.toString(), true);
        zip.deflated(OPF + "content.opf", opf);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String title(OCDDocument doc) { return EpubPackage.title(doc); }
    private static String lang(OCDDocument doc)  { return EpubPackage.lang(doc); }
    private static String pageFile(int index)    { return String.format("page-%03d", index); }
}
