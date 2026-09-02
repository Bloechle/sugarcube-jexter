package sugarcube.jexter.write;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.render.OCDRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Shared EPUB3 packaging — the parts that a fixed-layout replica ({@link EpubWriter}) and a
 * reflowable export ({@link ReflowEpubWriter}) produce identically: the OCF container, the cover,
 * Dublin Core + accessibility metadata, the navigation document and NCX (parameterised by how a
 * heading resolves to an href), the package skeleton, and small text helpers. Each writer keeps
 * only what genuinely differs (content documents, spine, and the {@code rendition:layout} flag).
 */
final class EpubPackage {

    private EpubPackage() {}

    static final String MIME       = "application/epub+zip";
    static final String OPF        = "OEBPS/";
    /** The cover is a RASTERISED page, and a rasterised page is a photograph: PNG stores it losslessly
     *  and pays for it — measured 482 KB of a 722 KB container, two thirds of the whole book for a
     *  thumbnail a reader shows at a couple of hundred pixels. JPEG is what a cover is for. */
    static final String COVER_HREF  = "images/cover.jpg";
    static final String COVER_MEDIA = "image/jpeg";
    private static final float  COVER_QUALITY   = 0.86f;
    /** Px on the long edge, for EVERY page whatever its size.
     *
     *  <p>600 because that is what the picture is FOR: a preview a reader shows in a shelf at ~200 CSS px,
     *  which a 3× phone renders at exactly 600. Above it nothing is visible — 1000 px cost 233 KB against
     *  90 KB and the same title read the same at display size — and the bill lands where it hurts most:
     *  a one-page text document came out 44 KB of which 35 were a thumbnail of itself, 79% of the book.
     *
     *  <p>Retail guidelines say 1400–1600, and they are about a DIFFERENT object: a designed cover for a
     *  book on sale, not a rasterised first page used as a preview. Taking the first rule for the second
     *  is what produced those 79%. */
    private static final double COVER_LONG_EDGE = 600;

    // ── OCF container ─────────────────────────────────────────────────────────
    static void container(JxZip zip) throws IOException {
        zip.deflated("META-INF/container.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """);
    }

    // ── cover (rasterized page 1, flattened on white, ~1000 px long edge) ───────
    static boolean cover(JxZip zip, OCDDocument doc) throws IOException {
        if (doc.cover() != null) {                       // resource read back — reuse verbatim, byte-stable
            zip.stored(OPF + COVER_HREF, doc.cover());   // SAME method as the fresh write, or the two disagree
            return true;
        }
        if (doc.pages().isEmpty()) return false;
        OCDPage p0 = doc.pages().get(0);
        double longEdge = Math.max(p0.displayWidth(), p0.displayHeight());
        // ONE size, whatever the page. The cover is a thumbnail: what a reader shows in a shelf, and a
        // shelf wants every spine the same height. Deriving it from the page instead gave a business card
        // a 521 px cover and an A0 poster a 2247 px one — the two documents where the difference means
        // least. Resolution is only how we reach the size: the content is vector, so a small page is
        // simply rendered at a higher dpi and stays sharp. Every cover therefore costs the same to make
        // and to store, which is what a conversion service actually needs.
        double dpi = 72.0 * COVER_LONG_EDGE / Math.max(1, longEdge);
        BufferedImage argb = OCDRenderer.render(p0, doc, dpi);
        BufferedImage rgb = new BufferedImage(argb.getWidth(), argb.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(argb, 0, 0, null);
        } finally {
            g.dispose();
        }
        var buf = new ByteArrayOutputStream();
        writeJpeg(rgb, buf);
        zip.stored(OPF + COVER_HREF, buf.toByteArray());     // already compressed — deflate would only add
        return true;
    }

    /** JPEG at a fixed quality, so the same page always gives the same bytes. */
    private static void writeJpeg(BufferedImage rgb, ByteArrayOutputStream out) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) { ImageIO.write(rgb, "png", out); return; }
        var w = writers.next();
        var param = w.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(COVER_QUALITY);
        try (var ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
        } finally {
            w.dispose();
        }
    }

    // ── navigation document (EPUB3) ─────────────────────────────────────────────
    /** Full {@code nav.xhtml}: a TOC plus landmarks; {@code pageListInner} (may be empty) is a
     *  {@code page-list} nav. {@code start} is the bodymatter/cover href. */
    static String navDoc(String title, String tocInner, String start, String pageListInner) {
        String pageList = pageListInner == null || pageListInner.isBlank() ? "" : """
                <nav epub:type="page-list" role="doc-pagelist" id="page-list" hidden="hidden">
                  <ol>
                %s  </ol>
                </nav>
                """.formatted(pageListInner);
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><meta charset="utf-8"/><title>%s</title></head>
                <body>
                <nav epub:type="toc" role="doc-toc" id="toc"><h1>%s</h1>
                  <ol>
                %s  </ol>
                </nav>
                <nav epub:type="landmarks" aria-label="Guide" hidden="hidden">
                  <ol>
                    <li><a epub:type="cover" href="%s">Cover</a></li>
                    <li><a epub:type="bodymatter" href="%s">Start of Content</a></li>
                  </ol>
                </nav>
                %s</body>
                </html>
                """.formatted(xml(title), xml(title), tocInner, start, start, pageList);
    }

    /** Nested {@code <ol>} of HEADING nodes; {@code href} maps a heading to its target. Headings
     *  nested under non-heading wrappers are surfaced. */
    static String tocOl(OCDStruct node, OCDIndex ix, Function<OCDStruct, String> href) {
        var sb = new StringBuilder();
        for (OCDStruct c : node.children()) {
            if (c.type() == OCDStruct.Type.HEADING) {
                sb.append("    <li><a href=\"").append(href.apply(c)).append("\">")
                  .append(xml(label(c, ix))).append("</a>");
                String inner = tocOl(c, ix, href);
                if (!inner.isBlank()) sb.append("\n      <ol>\n").append(inner).append("      </ol>\n    ");
                sb.append("</li>\n");
            } else {
                sb.append(tocOl(c, ix, href));
            }
        }
        return sb.toString();
    }

    /** Heading label: its resolved text (refs, else denormalized), capped; else a generic label. */
    static String label(OCDStruct h, OCDIndex ix) {
        String s = ix.text(h);
        return s.isEmpty() ? ("Heading " + Math.max(1, h.level())) : (s.length() > 120 ? s.substring(0, 120) + "\u2026" : s);
    }

    static void collectHeadings(OCDStruct s, List<OCDStruct> out) {
        for (OCDStruct c : s.children()) {
            if (c.type() == OCDStruct.Type.HEADING) out.add(c);
            collectHeadings(c, out);
        }
    }

    static int firstPage(OCDStruct s) {
        if (!s.refs().isEmpty()) return s.refs().get(0).page();
        for (OCDStruct c : s.children()) { int p = firstPage(c); if (p >= 0) return p; }
        return -1;
    }

    // ── NCX (EPUB2 compatibility) ───────────────────────────────────────────────
    static String ncx(String uid, String title, String navPointsInner) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <head><meta name="dtb:uid" content="%s"/></head>
                <docTitle><text>%s</text></docTitle>
                <navMap>
                %s</navMap>
                </ncx>
                """.formatted(uid, xml(title), navPointsInner);
    }

    /** Sequential NCX builder. The NCX spec requires navPoints that reference the SAME
     *  target to carry the SAME playOrder (epubcheck RSC-005) — several headings on one
     *  fixed-layout page all point at that page's xhtml. So ids stay unique (np-N) while
     *  playOrder is first-seen per target href: the one numbering authority. */
    static final class Ncx {
        private final StringBuilder sb = new StringBuilder();
        private final java.util.Map<String, Integer> orderOf = new java.util.LinkedHashMap<>();
        private int n = 0;
        Ncx add(String label, String href) {
            int po = orderOf.computeIfAbsent(href, h -> orderOf.size() + 1);
            sb.append("    <navPoint id=\"np-").append(++n).append("\" playOrder=\"").append(po).append("\">")
              .append("<navLabel><text>").append(xml(label)).append("</text></navLabel>")
              .append("<content src=\"").append(href).append("\"/></navPoint>\n");
            return this;
        }
        String inner() { return sb.toString(); }
    }

    // ── package skeleton ────────────────────────────────────────────────────────
    /** The {@code content.opf}. {@code prePaginated} adds the fixed-layout {@code rendition:*}
     *  metadata; reflowable packages omit it. {@code metaExtra} carries cover-meta + Dublin Core +
     *  accessibility. */
    static String packageOpf(String uid, String title, String lang, String metaExtra,
                             String manifest, String spine, boolean prePaginated) {
        return packageOpf(uid, title, lang, metaExtra, manifest, spine, prePaginated, null);
    }

    /** {@code modified} null → now (delivery exports); the OCD-EPUB passes a deterministic value
     *  so identical models serialize to identical bytes (writer idempotence — the identity gate). */
    static String packageOpf(String uid, String title, String lang, String metaExtra,
                             String manifest, String spine, boolean prePaginated, String modifiedOpt) {
        String modified = modifiedOpt != null ? modifiedOpt
                : Instant.ofEpochMilli(JxClock.millis()).truncatedTo(ChronoUnit.SECONDS).toString();
        String rendition = prePaginated ? """
                <meta property="rendition:layout">pre-paginated</meta>
                <meta property="rendition:orientation">auto</meta>
                <meta property="rendition:spread">auto</meta>
                """ : "";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" \
                prefix="rendition: http://www.idpf.org/vocab/rendition/#">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="pub-id">%s</dc:identifier>
                <dc:title>%s</dc:title>
                <dc:language>%s</dc:language>
                <meta property="dcterms:modified">%s</meta>
                %s%s</metadata>
                <manifest>
                %s</manifest>
                <spine toc="ncx">
                %s</spine>
                </package>
                """.formatted(uid, xml(title), xml(lang), modified, rendition, metaExtra, manifest, spine);
    }

    // ── metadata (Dublin Core + accessibility) ──────────────────────────────────
    /** THE identifier authority for every EPUB flavor: an explicit {@code identifier} in the model's
     *  metadata wins; otherwise a name-based UUID derived from the document id — which the importer
     *  content-addresses — so the same source always yields the same {@code dc:identifier}. */
    static String uniqueId(OCDDocument doc) {
        String id = doc.meta().custom().get("identifier");
        if (id != null && !id.isBlank()) return id.trim();
        byte[] seed = ("jexter:" + doc.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "urn:uuid:" + UUID.nameUUIDFromBytes(seed);
    }

    /** Deterministic dcterms:modified for the working format: the model's own dates, else a fixed
     *  sentinel — never the wall clock. */
    static String deterministicModified(OCDDocument doc) {
        String m = doc.meta().modified();
        if (m == null || m.isBlank()) m = doc.meta().created();
        if (m != null && !m.isBlank()) {
            try { return Instant.parse(m).truncatedTo(ChronoUnit.SECONDS).toString(); } catch (Exception ignore) { }
        }
        return "2000-01-01T00:00:00Z";
    }

    static String dublinCore(OCDMeta m) {
        var sb = new StringBuilder();
        int i = 1;
        for (String a : m.authors()) {
            String id = "creator-" + i++;
            sb.append("<dc:creator id=\"").append(id).append("\">").append(xml(a)).append("</dc:creator>\n");
            sb.append("<meta refines=\"#").append(id).append("\" property=\"role\" scheme=\"marc:relators\">aut</meta>\n");
        }
        if (!m.subject().isBlank())  sb.append("<dc:description>").append(xml(m.subject())).append("</dc:description>\n");
        for (String k : m.keywords()) sb.append("<dc:subject>").append(xml(k)).append("</dc:subject>\n");
        if (!m.created().isBlank())  sb.append("<dc:date>").append(xml(m.created())).append("</dc:date>\n");
        String publisher = m.custom().get("publisher");
        if (publisher != null && !publisher.isBlank())
            sb.append("<dc:publisher>").append(xml(publisher)).append("</dc:publisher>\n");
        String rights = m.custom().get("rights");
        if (rights != null && !rights.isBlank())
            sb.append("<dc:rights>").append(xml(rights)).append("</dc:rights>\n");
        if (!m.producer().isBlank()) {
            sb.append("<dc:contributor id=\"bkp\">").append(xml(m.producer())).append("</dc:contributor>\n");
            sb.append("<meta refines=\"#bkp\" property=\"role\" scheme=\"marc:relators\">bkp</meta>\n");
        }
        return sb.toString();
    }

    /** EPUB Accessibility 1.1 / schema.org discovery metadata. {@code pageList} reflects whether the
     *  navigation exposes a page-list (fixed-layout replica) — only then are print page numbers
     *  claimed; a reflowable export has no source pagination. */
    static String accessibilityMeta(OCDDocument doc, boolean pageList) {
        boolean hasImages = !doc.images().isEmpty();
        boolean hasStruct = doc.structure() != null && !doc.structure().isEmpty();
        boolean hasAlt    = hasAlt(doc.structure());
        var sb = new StringBuilder();
        sb.append("<meta property=\"schema:accessMode\">textual</meta>\n");
        if (hasImages) sb.append("<meta property=\"schema:accessMode\">visual</meta>\n");
        if (!hasImages || hasAlt) sb.append("<meta property=\"schema:accessModeSufficient\">textual</meta>\n");
        if (hasImages)            sb.append("<meta property=\"schema:accessModeSufficient\">textual,visual</meta>\n");
        if (hasStruct) {
            sb.append("<meta property=\"schema:accessibilityFeature\">structuralNavigation</meta>\n");
            sb.append("<meta property=\"schema:accessibilityFeature\">tableOfContents</meta>\n");
        }
        if (pageList) sb.append("<meta property=\"schema:accessibilityFeature\">printPageNumbers</meta>\n");
        if (hasAlt) sb.append("<meta property=\"schema:accessibilityFeature\">alternativeText</meta>\n");
        sb.append("<meta property=\"schema:accessibilityHazard\">none</meta>\n");
        String base = hasStruct ? "Structurally navigable (headings, reading order)"
                                : (pageList ? "Page-level navigation" : "Linear reading order");
        sb.append("<meta property=\"schema:accessibilitySummary\">")
          .append(base).append(pageList ? " with page-list." : ".")
          .append(hasAlt ? " Images include text alternatives." : "")
          .append("</meta>\n");
        return sb.toString();
    }

    private static boolean hasAlt(OCDStruct s) {
        if (s == null) return false;
        if (!s.alt().isBlank()) return true;
        for (OCDStruct c : s.children()) if (hasAlt(c)) return true;
        return false;
    }

    // ── small helpers ────────────────────────────────────────────────────────────
    static String title(OCDDocument doc) {
        String t = doc.meta().title();
        return !t.isBlank() ? t : (doc.id() != null ? doc.id() : "OCDDocument");
    }
    static String lang(OCDDocument doc) {
        String l = doc.meta().language();
        return !l.isBlank() ? l : "und";
    }
    static String mediaForImage(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "image/png";
    }
    static String num(double v)  { return JxNum.fmt(v); }
    static String xml(String s)  { return JxText.text(s); }
    static String safe(String s) { return JxName.safe(s); }
}
