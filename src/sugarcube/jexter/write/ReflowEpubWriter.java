package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.model.OCDDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static sugarcube.jexter.write.EpubPackage.OPF;
import static sugarcube.jexter.write.EpubPackage.safe;
import static sugarcube.jexter.write.EpubPackage.xml;

/**
 * Writes a {@link OCDDocument} as a <b>reflowable EPUB3</b> — the content-first counterpart of the
 * fixed-layout {@link EpubWriter}.
 *
 * <p>Where the replica reproduces page geometry (one SVG fac-simile per page), this projects the
 * <em>logical</em> structure: {@link HtmlWriter#chapters} walks the structure tree in reading order
 * and splits it at top-level headings into spine documents of semantic, reflowable HTML5 (headings,
 * paragraphs, lists, tables, figures, and {@code <video>}/{@code <audio>}). Text reflows to the
 * reader's screen and type size — the payoff of the analysis layer.
 *
 * <p>The OCF container, cover, metadata, navigation, NCX and package skeleton are shared with the
 * replica through {@link EpubPackage}; the package is declared <b>reflowable</b> (no
 * {@code rendition:layout}). Images and media are packaged as {@code media/*} resources (not data
 * URIs). Fonts are intentionally <em>not</em> embedded: reflow lets the reader choose typography.
 *
 * @see EpubWriter fixed-layout, pixel-faithful counterpart
 */
public final class ReflowEpubWriter {

    private ReflowEpubWriter() {}

    /** Uniform projection. Reflow is content-first (no page geometry, no embedded fonts),
     *  so it consumes no export options — {@code opt} is accepted for contract uniformity. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        write(doc, out);
    }

    public static void write(OCDDocument doc, OutputStream out) throws IOException {
        String uid = EpubPackage.uniqueId(doc);
        try (JxZip zip = new JxZip(out)) {
            zip.mimetype(EpubPackage.MIME);                      // STORED, first
            EpubPackage.container(zip);                          // META-INF/container.xml
            boolean hasCover = EpubPackage.cover(zip, doc);      // OEBPS/images/cover.jpg
            zip.deflated(OPF + "style.css", HtmlWriter.CSS);     // shared readable typography

            // content: chapters with images/media externalized as media/* resources
            var assetData = new LinkedHashMap<String, byte[]>();  // href -> bytes
            var assetMime = new LinkedHashMap<String, String>();  // href -> media-type
            HtmlWriter.Assets sink = (name, data, mime) -> {
                String href = "media/" + name;
                assetData.putIfAbsent(href, data);
                assetMime.putIfAbsent(href, mime);
                return href;
            };
            List<HtmlWriter.Chapter> chapters = HtmlWriter.chapters(doc, sink);
            if (chapters.isEmpty())
                chapters = List.of(new HtmlWriter.Chapter("chapter-1", "Content",
                        "<p>(No extractable text content.)</p>\n"));

            for (var e : assetData.entrySet()) zip.deflated(OPF + e.getKey(), e.getValue());
            for (HtmlWriter.Chapter c : chapters) zip.deflated(OPF + c.id() + ".xhtml", chapterXhtml(c));

            nav(zip, doc, chapters);
            ncx(zip, doc, uid, chapters);
            opf(zip, doc, uid, chapters, assetMime, hasCover);
        }
    }

    // ── chapter document ─────────────────────────────────────────────────────────

    private static String chapterXhtml(HtmlWriter.Chapter c) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head>
                <meta charset="utf-8"/>
                <title>%s</title>
                <link rel="stylesheet" type="text/css" href="style.css"/>
                </head>
                <body>
                <main>
                %s</main>
                </body>
                </html>
                """.formatted(xml(c.title()), c.html());
    }

    // ── navigation ─────────────────────────────────────────────────────────────────

    private static void nav(JxZip zip, OCDDocument doc, List<HtmlWriter.Chapter> chapters) throws IOException {
        var toc = new StringBuilder();
        for (HtmlWriter.Chapter c : chapters)
            toc.append("    <li><a href=\"").append(c.id()).append(".xhtml\">")
               .append(xml(c.title())).append("</a></li>\n");
        String start = chapters.get(0).id() + ".xhtml";
        zip.deflated(OPF + "nav.xhtml", EpubPackage.navDoc(EpubPackage.title(doc), toc.toString(), start, ""));
    }

    private static void ncx(JxZip zip, OCDDocument doc, String uid, List<HtmlWriter.Chapter> chapters) throws IOException {
        var ncx = new EpubPackage.Ncx();
        for (HtmlWriter.Chapter c : chapters) ncx.add(c.title(), c.id() + ".xhtml");
        zip.deflated(OPF + "toc.ncx", EpubPackage.ncx(uid, EpubPackage.title(doc), ncx.inner()));
    }

    // ── package: manifest + spine (reflowable) ───────────────────────────────────

    private static void opf(JxZip zip, OCDDocument doc, String uid, List<HtmlWriter.Chapter> chapters,
                            Map<String, String> assetMime, boolean hasCover) throws IOException {
        var manifest = new StringBuilder();
        var spine = new StringBuilder();
        manifest.append("    <item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n");
        manifest.append("    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n");
        manifest.append("    <item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n");
        if (hasCover)
            manifest.append("    <item id=\"cover-image\" href=\"").append(EpubPackage.COVER_HREF)
                    .append("\" media-type=\"").append(EpubPackage.COVER_MEDIA).append("\" properties=\"cover-image\"/>\n");
        int a = 1;
        for (var e : assetMime.entrySet())
            manifest.append("    <item id=\"asset-").append(a++).append("\" href=\"").append(e.getKey())
                    .append("\" media-type=\"").append(e.getValue()).append("\"/>\n");
        for (HtmlWriter.Chapter c : chapters) {
            manifest.append("    <item id=\"").append(safe(c.id())).append("\" href=\"").append(c.id())
                    .append(".xhtml\" media-type=\"application/xhtml+xml\"/>\n");
            spine.append("    <itemref idref=\"").append(safe(c.id())).append("\"/>\n");
        }
        String coverMeta = hasCover ? "<meta name=\"cover\" content=\"cover-image\"/>\n" : "";
        String metaExtra = coverMeta + EpubPackage.dublinCore(doc.meta()) + EpubPackage.accessibilityMeta(doc, false);
        String opf = EpubPackage.packageOpf(uid, EpubPackage.title(doc), EpubPackage.lang(doc),
                metaExtra, manifest.toString(), spine.toString(), false);   // reflowable
        zip.deflated(OPF + "content.opf", opf);
    }
}
