package sugarcube.jexter.ocd.io;

import sugarcube.jexter.ocd.model.OCDIndex;
import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxZip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.render.OCDRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.Locale;

/**
 * Shared EPUB3 packaging — the parts that a fixed-layout replica ({@code write.EpubWriter}) and a
 * reflowable export ({@code write.ReflowEpubWriter}) produce identically: the OCF container, the cover,
 * Dublin Core + accessibility metadata, the navigation document and NCX (parameterised by how a
 * heading resolves to an href), the package skeleton, and small text helpers. Each writer keeps
 * only what genuinely differs (content documents, spine, and the {@code rendition:layout} flag).
 */
public final class EpubPackage {

    private EpubPackage() {}

    public static final String MIME       = "application/epub+zip";
    public static final String OPF        = OCDVocab.OPF;
    /** The cover is a RASTERISED page, and a rasterised page is a photograph: PNG stores it losslessly
     *  and pays for it — measured 482 KB of a 722 KB container, two thirds of the whole book for a
     *  thumbnail a reader shows at a couple of hundred pixels. JPEG is what a cover is for. */
    public static final String COVER_HREF  = "images/cover.jpg";
    public static final String COVER_MEDIA = "image/jpeg";
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
    public static void container(JxZip zip) throws IOException {
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
    public static boolean cover(JxZip zip, OCDDocument doc) throws IOException {
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
    public static String navDoc(String title, String tocInner, String start, String pageListInner) {
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
                """.formatted(JxText.text(title), JxText.text(title), tocInner, start, start, pageList);
    }

    /** One entry of a table of contents: a heading level, its words, its target. */
    public record TocEntry(int level, String title, String href) {}

    /** {@code entries} (in reading order) nested by level: an entry holds the deeper ones that follow it. */
    private record TocNode(TocEntry entry, List<TocNode> kids) {}

    private static List<TocNode> nest(List<TocEntry> entries) {
        List<TocNode> top = new ArrayList<>();
        java.util.Deque<TocNode> open = new java.util.ArrayDeque<>();
        for (TocEntry e : entries) {
            while (!open.isEmpty() && open.peek().entry().level() >= e.level()) open.pop();
            TocNode n = new TocNode(e, new ArrayList<>());
            (open.isEmpty() ? top : open.peek().kids()).add(n);
            open.push(n);
        }
        return top;
    }

    /** {@code entries} as the nested {@code <li>} of a nav's {@code <ol>}. */
    public static String tocOl(List<TocEntry> entries) {
        var sb = new StringBuilder();
        tocOl(nest(entries), sb, "    ");
        return sb.toString();
    }

    private static void tocOl(List<TocNode> nodes, StringBuilder sb, String in) {
        for (TocNode n : nodes) {
            sb.append(in).append("<li><a href=\"").append(JxText.attr(n.entry().href())).append("\">").append(JxText.text(n.entry().title())).append("</a>");
            if (!n.kids().isEmpty()) {
                sb.append('\n').append(in).append("  <ol>\n");
                tocOl(n.kids(), sb, in + "    ");
                sb.append(in).append("  </ol>\n").append(in);
            }
            sb.append("</li>\n");
        }
    }

    /** {@code entries} as nested navPoints. */
    public static String ncxPoints(List<TocEntry> entries) {
        var ncx = new Ncx();
        ncxPoints(nest(entries), ncx);
        return ncx.inner();
    }

    private static void ncxPoints(List<TocNode> nodes, Ncx ncx) {
        for (TocNode n : nodes) { ncx.open(n.entry().title(), n.entry().href()); ncxPoints(n.kids(), ncx); ncx.close(); }
    }

    /** Nested {@code <ol>} of HEADING nodes; {@code href} maps a heading to its target. Headings
     *  nested under non-heading wrappers are surfaced. */
    public static String tocOl(OCDStructNode node, OCDIndex ix, Function<OCDStructNode, String> href) {
        var sb = new StringBuilder();
        for (OCDStructNode c : node.children()) {
            if (c.type() == OCDStructNode.Type.HEADING) {
                sb.append("    <li><a href=\"").append(href.apply(c)).append("\">")
                  .append(JxText.text(label(c, ix))).append("</a>");
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
    public static String label(OCDStructNode h, OCDIndex ix) {
        String s = ix.text(h);
        return s.isEmpty() ? ("Heading " + Math.max(1, h.level())) : (s.length() > 120 ? s.substring(0, 120) + "\u2026" : s);
    }

    public static void collectHeadings(OCDStructNode s, List<OCDStructNode> out) {
        for (OCDStructNode c : s.children()) {
            if (c.type() == OCDStructNode.Type.HEADING) out.add(c);
            collectHeadings(c, out);
        }
    }

    /**
     * A TOC with ONE ENTRY PER PAGE: the document's own headings where a page has them, a plain
     * "Page N" where it has none — every page reachable from the navigation.
     *
     * <p>This is not a better table of contents and is not the default: an EPUB nav is a table of
     * CONTENTS, and a page with nothing to say does not belong in it. It exists because some readers
     * navigate by page and want an entry for each, which some EPUB pipelines build by hand
     * (emptying the nav and refilling it page by page). Here it is a projection
     * choice — the model is never touched, and no other target sees it.
     *
     * @param doc      the document, for its page count
     * @param root     the logical structure, or {@code null} when there is none
     * @param ix       the read-out, for heading text (same {@code label} rule as {@link #tocOl})
     * @param href     how a page index (0-based) becomes an href
     * @param label    how a page index (0-based) becomes its fallback label
     */
    public static String tocPerPage(OCDDocument doc, OCDStructNode root, OCDIndex ix,
                             java.util.function.IntFunction<String> href,
                             java.util.function.IntFunction<String> label) {
        // page -> the headings that start on it, in tree order. collectHeadings is this file's
        // existing walker (tocOl's own): one authority for "what is a heading in this tree".
        var flat = new java.util.ArrayList<OCDStructNode>();
        if (root != null) collectHeadings(root, flat);
        var byPage = new java.util.HashMap<Integer, java.util.List<OCDStructNode>>();
        for (OCDStructNode h : flat) {
            int hp = firstPage(h);
            if (hp >= 0) byPage.computeIfAbsent(hp, k -> new java.util.ArrayList<>()).add(h);
        }

        var sb = new StringBuilder();
        for (int p = 0; p < doc.pages().size(); p++) {
            var heads = byPage.get(p);
            if (heads == null || heads.isEmpty()) {
                sb.append("    <li><a href=\"").append(JxText.text(href.apply(p))).append("\">")
                  .append(JxText.text(label.apply(p))).append("</a></li>\n");
            } else {
                for (OCDStructNode h : heads) {
                    String text = label(h, ix);                 // the same authority tocOl uses
                    if (text == null || text.isBlank()) text = label.apply(p);
                    sb.append("    <li><a href=\"").append(JxText.text(href.apply(p))).append("\">")
                      .append(JxText.text(text.strip())).append("</a></li>\n");
                }
            }
        }
        return sb.toString();
    }

    public static int firstPage(OCDStructNode s) {
        if (!s.refs().isEmpty()) return s.refs().get(0).page();
        for (OCDStructNode c : s.children()) { int p = firstPage(c); if (p >= 0) return p; }
        return -1;
    }

    // ── NCX (EPUB2 compatibility) ───────────────────────────────────────────────
    public static String ncx(String uid, String title, String navPointsInner) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <head><meta name="dtb:uid" content="%s"/></head>
                <docTitle><text>%s</text></docTitle>
                <navMap>
                %s</navMap>
                </ncx>
                """.formatted(uid, JxText.text(title), navPointsInner);
    }

    /** Sequential NCX builder. The NCX spec requires navPoints that reference the SAME
     *  target to carry the SAME playOrder (epubcheck RSC-005) — several headings on one
     *  fixed-layout page all point at that page's xhtml. So ids stay unique (np-N) while
     *  playOrder is first-seen per target href: the one numbering authority. */
    public static final class Ncx {
        private final StringBuilder sb = new StringBuilder();
        private final java.util.Map<String, Integer> orderOf = new java.util.LinkedHashMap<>();
        private int n = 0;
        public Ncx add(String label, String href) { return open(label, href).close(); }

        /** A navPoint left open: the points added until {@link #close} nest inside it. */
        public Ncx open(String label, String href) {
            int po = orderOf.computeIfAbsent(href, h -> orderOf.size() + 1);
            sb.append("    <navPoint id=\"np-").append(++n).append("\" playOrder=\"").append(po).append("\">")
              .append("<navLabel><text>").append(JxText.text(label)).append("</text></navLabel>")
              .append("<content src=\"").append(href).append("\"/>\n");
            return this;
        }

        public Ncx close() { sb.append("    </navPoint>\n"); return this; }
        public String inner() { return sb.toString(); }
    }

    // ── the fixed-layout package, shared by the presentation EPUB and the OCD-EPUB ──────────────────

    /** The fixed-layout {@code nav.xhtml}: {@code tocInner} (blank → one "Page N" entry per page) and the
     *  page-list. */
    public static String fixedNav(OCDDocument doc, String tocInner) {
        if (tocInner.isBlank()) {
            var sb = new StringBuilder();
            for (int i = 1; i <= doc.pageCount(); i++)
                sb.append("    <li><a href=\"").append(OCDVocab.pageHref(i)).append("\">Page ").append(i).append("</a></li>\n");
            tocInner = sb.toString();
        }
        var plist = new StringBuilder();
        for (int i = 1; i <= doc.pageCount(); i++)
            plist.append("    <li><a href=\"").append(OCDVocab.pageHref(i)).append("\">").append(i).append("</a></li>\n");
        return navDoc(title(doc), tocInner, OCDVocab.pageHref(1), plist.toString());
    }

    /** The fixed-layout {@code toc.ncx}: the headings of the default structure, else one entry per page. */
    public static String fixedNcx(OCDDocument doc, String uid) {
        var heads = new java.util.ArrayList<OCDStructNode>();
        if (doc.structure() != null) collectHeadings(doc.structure(), heads);
        OCDIndex ix = OCDIndex.of(doc);
        var ncx = new Ncx();
        if (!heads.isEmpty()) for (OCDStructNode h : heads) ncx.add(label(h, ix), headingHref(h));
        else for (int n = 1; n <= doc.pageCount(); n++) ncx.add("Page " + n, OCDVocab.pageHref(n));
        return ncx(uid, title(doc), ncx.inner());
    }

    /** The fixed-layout {@code content.opf}. Both flavours declare nav, ncx, the cover, every image and every
     *  page; each adds its own items at three places — {@code head} after the ncx, {@code resources} after
     *  the images, {@code tail} after the pages. {@code modified} as for {@link #packageOpf}. */
    public static String fixedOpf(OCDDocument doc, String uid, String head, String resources, String tail, String modified) {
        var manifest = new StringBuilder();
        var spine = new StringBuilder();
        item(manifest, "nav", "nav.xhtml", "application/xhtml+xml", "nav");
        item(manifest, "ncx", "toc.ncx", "application/x-dtbncx+xml", null);
        manifest.append(head);
        boolean cover = doc.pageCount() > 0;
        if (cover) item(manifest, "cover-image", COVER_HREF, COVER_MEDIA, "cover-image");
        for (String name : doc.images().keySet()) {
            String n = JxName.safe(name);
            item(manifest, "img-" + n, "images/" + n, mediaType(n), null);
        }
        manifest.append(resources);
        for (int i = 1; i <= doc.pageCount(); i++) {
            item(manifest, "page-" + i, OCDVocab.pageHref(i), "application/xhtml+xml", "svg");
            spine.append("    <itemref idref=\"page-").append(i).append("\"/>\n");
        }
        manifest.append(tail);
        String metaExtra = (cover ? "<meta name=\"cover\" content=\"cover-image\"/>\n" : "")
                + dublinCore(doc.meta()) + accessibilityMeta(doc, true);
        return packageOpf(uid, title(doc), lang(doc), metaExtra, manifest.toString(), spine.toString(), true, modified);
    }

    /** One manifest {@code <item>}; {@code properties} null when it has none. */
    public static void item(StringBuilder manifest, String id, String href, String mediaType, String properties) {
        manifest.append("    <item id=\"").append(id).append("\" href=\"").append(JxText.attr(href))
                .append("\" media-type=\"").append(mediaType).append('"');
        if (properties != null) manifest.append(" properties=\"").append(properties).append('"');
        manifest.append("/>\n");
    }

    // ── package skeleton ────────────────────────────────────────────────────────
    /** The {@code content.opf}. {@code prePaginated} adds the fixed-layout {@code rendition:*}
     *  metadata; reflowable packages omit it. {@code metaExtra} carries cover-meta + Dublin Core +
     *  accessibility. */
    public static String packageOpf(String uid, String title, String lang, String metaExtra,
                             String manifest, String spine, boolean prePaginated) {
        return packageOpf(uid, title, lang, metaExtra, manifest, spine, prePaginated, null);
    }

    /** {@code modified} null → now (delivery exports); the OCD-EPUB passes a deterministic value
     *  so identical models serialize to identical bytes (writer idempotence — the identity gate). */
    public static String packageOpf(String uid, String title, String lang, String metaExtra,
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
                """.formatted(uid, JxText.text(title), JxText.text(lang), modified, rendition, metaExtra, manifest, spine);
    }

    // ── metadata (Dublin Core + accessibility) ──────────────────────────────────
    /** THE identifier authority for every EPUB flavor: an explicit {@code identifier} in the model's
     *  metadata wins; otherwise a name-based UUID derived from the document id — which the importer
     *  content-addresses — so the same source always yields the same {@code dc:identifier}. */
    public static String uniqueId(OCDDocument doc) {
        String id = doc.meta().custom().get("identifier");
        if (id != null && !id.isBlank()) return id.trim();
        byte[] seed = ("jexter:" + doc.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return "urn:uuid:" + UUID.nameUUIDFromBytes(seed);
    }

    /** Deterministic dcterms:modified for the working format: the model's own dates, else a fixed
     *  sentinel — never the wall clock. */
    public static String deterministicModified(OCDDocument doc) {
        String m = doc.meta().modified();
        if (m == null || m.isBlank()) m = doc.meta().created();
        if (m != null && !m.isBlank()) {
            try { return Instant.parse(m).truncatedTo(ChronoUnit.SECONDS).toString(); } catch (Exception ignore) { }
        }
        return "2000-01-01T00:00:00Z";
    }

    public static String dublinCore(OCDMeta m) {
        var sb = new StringBuilder();
        int i = 1;
        for (String a : m.authors()) {
            String id = "creator-" + i++;
            sb.append("<dc:creator id=\"").append(id).append("\">").append(JxText.text(a)).append("</dc:creator>\n");
            sb.append("<meta refines=\"#").append(id).append("\" property=\"role\" scheme=\"marc:relators\">aut</meta>\n");
        }
        if (!m.subject().isBlank())  sb.append("<dc:description>").append(JxText.text(m.subject())).append("</dc:description>\n");
        for (String k : m.keywords()) sb.append("<dc:subject>").append(JxText.text(k)).append("</dc:subject>\n");
        if (!m.created().isBlank())  sb.append("<dc:date>").append(JxText.text(m.created())).append("</dc:date>\n");
        String publisher = m.custom().get("publisher");
        if (publisher != null && !publisher.isBlank())
            sb.append("<dc:publisher>").append(JxText.text(publisher)).append("</dc:publisher>\n");
        String rights = m.custom().get("rights");
        if (rights != null && !rights.isBlank())
            sb.append("<dc:rights>").append(JxText.text(rights)).append("</dc:rights>\n");
        if (!m.producer().isBlank()) {
            sb.append("<dc:contributor id=\"bkp\">").append(JxText.text(m.producer())).append("</dc:contributor>\n");
            sb.append("<meta refines=\"#bkp\" property=\"role\" scheme=\"marc:relators\">bkp</meta>\n");
        }
        return sb.toString();
    }

    /** EPUB Accessibility 1.1 / schema.org discovery metadata. {@code pageList} reflects whether the
     *  navigation exposes a page-list (fixed-layout replica) — only then are print page numbers
     *  claimed; a reflowable export has no source pagination. */
    public static String accessibilityMeta(OCDDocument doc, boolean pageList) {
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

    private static boolean hasAlt(OCDStructNode s) {
        if (s == null) return false;
        if (!s.alt().isBlank()) return true;
        for (OCDStructNode c : s.children()) if (hasAlt(c)) return true;
        return false;
    }

    // ── small helpers ────────────────────────────────────────────────────────────
    public static String title(OCDDocument doc) {
        String t = doc.meta().title();
        return !t.isBlank() ? t : (doc.id() != null ? doc.id() : "OCDDocument");
    }
    public static String lang(OCDDocument doc) {
        String l = doc.meta().language();
        return !l.isBlank() ? l : "und";
    }
    /** THE media type of a member, by its name: images, audio and video, the model's data members — and the
     *  octet-stream fallback for anything else, which is legal and honest (a passenger the model does not own
     *  is better declared as a blob than left undeclared for epubcheck to refuse). */
    public static String mediaType(String path) {
        String n = path.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return switch (dot < 0 ? "" : n.substring(dot + 1)) {
            case "png"         -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif"         -> "image/gif";
            case "webp"        -> "image/webp";
            case "svg"         -> "image/svg+xml";
            case "mp4", "m4v"  -> "video/mp4";
            case "webm"        -> "video/webm";
            case "mp3"         -> "audio/mpeg";
            case "m4a"         -> "audio/mp4";
            case "ogg", "oga"  -> "audio/ogg";
            case "wav"         -> "audio/wav";
            case "json"        -> "application/json";
            case "xml"         -> "application/xml";
            case "txt"         -> "text/plain";
            case "css"         -> "text/css";
            case "otf"         -> "font/otf";
            case "icc"         -> "application/vnd.iccprofile";
            default            -> "application/octet-stream";
        };
    }

    /** Every image resource, written under {@code OEBPS/images/}. Both flavours carry the same bytes
     *  under the same names — this was one method in each writer, character for character. */
    public static void images(JxZip zip, OCDDocument doc) throws IOException {
        for (var e : doc.images().entrySet())
            zip.deflated(OPF + "images/" + JxName.safe(e.getKey()), e.getValue());
    }

    /** Where a heading lives: the page member of its first referenced page. Also one method in each. */
    public static String headingHref(OCDStructNode s) {
        int p = firstPage(s);
        return OCDVocab.pageHref((p < 0 ? 0 : p) + 1);
    }
}
