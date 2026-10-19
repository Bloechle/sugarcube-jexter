package sugarcube.jexter.write;

import sugarcube.jexter.ocd.io.EpubPackage;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.io.OCDVocab;
import sugarcube.jexter.font.JxFont;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDIndex;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDPage;

import java.io.IOException;
import java.io.OutputStream;

import static sugarcube.jexter.ocd.io.EpubPackage.OPF;
import static sugarcube.jexter.core.JxNum.fmt;
import static sugarcube.jexter.core.JxName.safe;

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

    private EpubWriter() {}

    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        boolean annotations = Boolean.TRUE.equals(opt.get(ConvertOptions.RENDER_ANNOTATIONS));
        String uid = EpubPackage.uniqueId(doc);
        try (JxZip zip = new JxZip(out)) {
            zip.mimetype(EpubPackage.MIME);                      // STORED, first
            EpubPackage.container(zip);                          // META-INF/container.xml
            fonts(zip, doc);                                     // OEBPS/fonts/*.otf
            fontsCss(zip, doc);                                  // OEBPS/fonts.css (@font-face)
            EpubPackage.images(zip, doc);                                    // OEBPS/images/*
            EpubPackage.cover(zip, doc);                         // OEBPS/images/cover.jpg (rasterized page 1)
            pagesXhtml(zip, doc, annotations);                   // OEBPS/pages/page-NNN.xhtml (inline SVG)
            zip.deflated(OPF + "nav.xhtml", EpubPackage.fixedNav(doc, toc(doc, opt)));   // toc + page-list
            zip.deflated(OPF + "toc.ncx", EpubPackage.fixedNcx(doc, uid));               // EPUB2 compat
            zip.deflated(OPF + "content.opf", opf(doc, uid));                            // manifest + spine
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
                    """.formatted("OCDPage " + i, fmt(page.displayWidth()), fmt(page.displayHeight()), svg);
            zip.deflated(OCDVocab.pagePath(i++), xhtml);
        }
    }

    // ── logical: the toc (headings, or every page with navPerPage) — the rest is the shared package ──

    private static String toc(OCDDocument doc, ConvertOptions opt) {
        OCDIndex ix = OCDIndex.of(doc);
        // navPerPage: every page in the nav, headings where they exist, "Page N" otherwise. A
        // projection choice, not a fact about the document — the model is never touched.
        return Boolean.TRUE.equals(opt.get(ConvertOptions.NAV_PER_PAGE))
                ? EpubPackage.tocPerPage(doc, doc.structure(), ix, p -> OCDVocab.pageHref(p + 1), p -> "Page " + (p + 1))
                : doc.structure() != null ? EpubPackage.tocOl(doc.structure(), ix, EpubPackage::headingHref) : "";
    }

    private static String opf(OCDDocument doc, String uid) {
        var css = new StringBuilder();
        EpubPackage.item(css, "css", "fonts.css", "text/css", null);
        var fonts = new StringBuilder();
        for (OCDFont f : doc.fonts().values()) {
            String n = safe(f.id());
            EpubPackage.item(fonts, "font-" + n, "fonts/" + n + ".otf", "font/otf", null);
        }
        return EpubPackage.fixedOpf(doc, uid, css.toString(), fonts.toString(), "", null);
    }

}
