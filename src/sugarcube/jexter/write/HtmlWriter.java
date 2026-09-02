package sugarcube.jexter.write;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.ocd.analysis.LanguageDetector;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDMedia;
import sugarcube.jexter.ocd.model.OCDVideo;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDStruct;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Reflowable HTML projection of an {@link OCDDocument}.
 *
 * <p>Unlike the SVG / fixed-layout EPUB projections (which reproduce page geometry), this walks the
 * <em>logical</em> structure tree in reading order and emits semantic, reflowable HTML5: headings
 * nest HTML5-outline style, paragraphs flow, lists and tables map to their native elements, figures
 * carry their image (or {@code <video>}/{@code <audio>}) plus accessibility text. Presentation
 * geometry is intentionally discarded — this is the "content" view, the payoff of the structured layer.
 *
 * <p>Three entry points share one structure walk:
 * <ul>
 *   <li>{@link #toHtml} — a self-contained standalone HTML document (assets inlined as data URIs);</li>
 *   <li>{@link #body} — the {@code <body>} fragment only, for embedding (e.g. an EPUB chapter);</li>
 *   <li>{@link #chapters} — the body split at top-level headings, for a navigable EPUB spine.</li>
 * </ul>
 * The optional {@link Assets} sink lets a caller externalize images/media as packaged resources
 * (returning an href) instead of inlining them; passing {@code null} keeps the self-contained
 * data-URI behavior. If the document has no structure tree, a minimal fallback emits each page's text.
 */
public final class HtmlWriter {

    private HtmlWriter() {}

    /** Sink for externalized binary resources: store {@code data} under a name derived from
     *  {@code name}, return the href to reference it from the (X)HTML. {@code null} sink ⇒ inline. */
    @FunctionalInterface
    public interface Assets {
        String ref(String name, byte[] data, String mime);
    }

    /** One spine document: a stable {@code id} (also the file base), a {@code title} for the TOC,
     *  and the rendered {@code <body>} HTML fragment. */
    public record Chapter(String id, String title, String html) {}

    /** Uniform projection: a self-contained standalone HTML document (UTF-8). Whole-document
     *  reflow — export options are accepted for contract uniformity but not consumed. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        out.write(toHtml(doc).getBytes(StandardCharsets.UTF_8));
    }

    // ── entry points ─────────────────────────────────────────────────────────

    public static String toHtml(OCDDocument doc) {
        LanguageDetector.detect(doc);                 // fill the BCP-47 lang for <html lang> when the PDF declared none (export-time fallback)
        String title = doc.meta() != null && doc.meta().title() != null && !doc.meta().title().isBlank()
                ? doc.meta().title() : "Document";
        String lang = doc.meta() != null && doc.meta().language() != null && !doc.meta().language().isBlank()
                ? doc.meta().language() : "en";
        var sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html>\n<html lang=\"").append(attr(lang)).append("\">\n<head>\n")
          .append("<meta charset=\"utf-8\"/>\n")
          .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>\n")
          .append("<title>").append(text(title)).append("</title>\n")
          .append("<style>").append(CSS).append("</style>\n</head>\n<body>\n<main>\n");
        sb.append(body(doc, null));                              // inline assets ⇒ self-contained
        sb.append("</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    /** The {@code <body>} fragment (no {@code <html>/<head>}). {@code assets} externalizes binaries
     *  when non-null, else they inline as data URIs (matching {@link #toHtml}). */
    public static String body(OCDDocument doc, Assets assets) {
        OCDIndex ix = OCDIndex.of(doc);
        var sb = new StringBuilder(16384);
        OCDStruct root = doc.structure();
        if (root != null) for (OCDStruct c : root.children()) render(c, doc, ix, sb, assets);
        else fallback(doc, sb);
        return sb.toString();
    }

    /** Split the body into chapters at top-level HEADING boundaries (content before the first
     *  heading, and the no-heading case, become a single "Content" chapter). Each chapter is a
     *  self-contained body fragment, ready to wrap as one spine document. */
    public static List<Chapter> chapters(OCDDocument doc, Assets assets) {
        OCDIndex ix = OCDIndex.of(doc);
        var out = new ArrayList<Chapter>();
        OCDStruct root = doc.structure();
        if (root == null || root.children().isEmpty()) {
            String html = body(doc, assets);
            if (!html.isBlank()) out.add(new Chapter("chapter-1", "Content", html));
            return out;
        }
        var cur = new StringBuilder();
        String curTitle = null;
        for (OCDStruct c : root.children()) {
            if (c.type() == OCDStruct.Type.HEADING && cur.length() > 0) {
                out.add(new Chapter("chapter-" + (out.size() + 1), curTitle != null ? curTitle : "Content", cur.toString()));
                cur = new StringBuilder();
                curTitle = null;
            }
            if (c.type() == OCDStruct.Type.HEADING && curTitle == null) {
                String t = content(c, ix).strip();
                curTitle = t.isEmpty() ? "Section" : (t.length() > 120 ? t.substring(0, 120) + "\u2026" : t);
            }
            render(c, doc, ix, cur, assets);
        }
        if (cur.length() > 0)
            out.add(new Chapter("chapter-" + (out.size() + 1), curTitle != null ? curTitle : "Content", cur.toString()));
        return out;
    }

    // ── structure → HTML ─────────────────────────────────────────────────────

    private static void render(OCDStruct s, OCDDocument doc, OCDIndex ix, StringBuilder sb, Assets assets) {
        switch (s.type()) {
            case HEADING -> {
                int lv = Math.min(6, Math.max(1, s.level() == 0 ? 1 : s.level()));
                line(sb, "h" + lv, content(s, ix));
                for (OCDStruct c : s.children()) render(c, doc, ix, sb, assets);   // HTML5 outline: children are siblings
            }
            case SECTION   -> wrap(s, doc, ix, sb, "section", null, assets);
            case PARAGRAPH -> line(sb, "p", content(s, ix));
            case LIST      -> wrap(s, doc, ix, sb, s.ordered() ? "ol" : "ul", null, assets);
            case ITEM      -> wrap(s, doc, ix, sb, "li", content(s, ix), assets);
            case TABLE     -> wrap(s, doc, ix, sb, "table", null, assets);
            case ROW       -> wrap(s, doc, ix, sb, "tr", null, assets);
            case CELL      -> wrap(s, doc, ix, sb, s.header() != OCDStruct.HeaderKind.NONE ? "th" : "td", content(s, ix), assets);
            case FIGURE    -> figure(s, doc, ix, sb, assets);
            case CAPTION   -> line(sb, "figcaption", content(s, ix));
            case QUOTE     -> wrap(s, doc, ix, sb, "blockquote", content(s, ix), assets);
            case CODE      -> sb.append("<pre><code>").append(text(content(s, ix))).append("</code></pre>\n");
            case NOTE      -> wrap(s, doc, ix, sb, "aside", content(s, ix), assets);
            case TOC       -> wrap(s, doc, ix, sb, "nav", null, assets);
            case SPAN      -> line(sb, "span", content(s, ix));
            default        -> wrap(s, doc, ix, sb, "div", content(s, ix), assets);
        }
    }

    /** Open {@code <tag>}, optional inline text, render children, close. */
    private static void wrap(OCDStruct s, OCDDocument doc, OCDIndex ix,
                             StringBuilder sb, String tag, String inline, Assets assets) {
        sb.append('<').append(tag);
        if (!s.lang().isEmpty()) sb.append(" lang=\"").append(attr(s.lang())).append('"');
        if (s.colSpan() > 1) sb.append(" colspan=\"").append(s.colSpan()).append('"');
        if (s.rowSpan() > 1) sb.append(" rowspan=\"").append(s.rowSpan()).append('"');
        if (tag.equals("th")) {                                  // accessible header cell scope
            String scope = switch (s.header()) { case COLUMN -> "col"; case ROW -> "row"; default -> null; };
            if (scope != null) sb.append(" scope=\"").append(scope).append('"');
        }
        if (tag.equals("nav")) sb.append(" class=\"toc\"");
        sb.append(">\n");
        if (inline != null && !inline.isEmpty()) sb.append(text(inline)).append('\n');
        for (OCDStruct c : s.children()) render(c, doc, ix, sb, assets);
        sb.append("</").append(tag).append(">\n");
    }

    private static void figure(OCDStruct s, OCDDocument doc, OCDIndex ix, StringBuilder sb, Assets assets) {
        sb.append("<figure>\n");
        String alt = !s.alt().isEmpty() ? s.alt() : s.text();
        OCDMedia media = referencedMedia(s, ix);
        if (media != null) {
            sb.append(mediaElement(media, doc, assets));
        } else {
            String src = imageSrc(s, doc, ix, assets);
            if (src != null)
                sb.append("<img src=\"").append(src).append("\" alt=\"").append(attr(alt)).append("\"/>\n");
        }
        for (OCDStruct c : s.children()) render(c, doc, ix, sb, assets);   // caption(s)
        sb.append("</figure>\n");
    }

    private static OCDMedia referencedMedia(OCDStruct s, OCDIndex ix) {
        for (OCDStruct.Ref r : s.refs()) {
            OCDNode n = ix.node(r.page(), r.nodeId());
            if (n instanceof OCDMedia m) return m;
        }
        return null;
    }

    /** A {@code <video>}/{@code <audio>} element; media + poster externalized via {@code assets}
     *  (else inlined as data URIs). */
    private static String mediaElement(OCDMedia m, OCDDocument doc, Assets assets) {
        byte[] data = doc.media().get(m.resourceRef());
        if (data == null) return "";
        boolean audio = "audio".equals(m.tag());
        String mime = audio ? "audio/mpeg" : "video/mp4";
        String src = assets != null
                ? assets.ref(named(m.resourceRef(), audio ? ".mp3" : ".mp4"), data, mime)
                : dataUri(mime, data);
        var sb = new StringBuilder();
        sb.append('<').append(m.tag());
        if (m.controls()) sb.append(" controls");
        if (m.autoplay()) sb.append(" autoplay");
        if (m.loop())     sb.append(" loop");
        if (m.muted())    sb.append(" muted");
        if (m instanceof OCDVideo v && v.poster() != null) {
            byte[] pd = doc.images().get(v.poster());
            if (pd != null) {
                String psrc = assets != null ? assets.ref(named(v.poster(), ".png"), pd, "image/png")
                                              : dataUri("image/png", pd);
                sb.append(" poster=\"").append(psrc).append('"');
            }
        }
        sb.append(">\n<source src=\"").append(src).append("\" type=\"").append(mime).append("\"/>\n")
          .append("</").append(m.tag()).append(">\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String tag, String inner) {
        if (inner == null || inner.isBlank()) return;
        sb.append('<').append(tag).append('>').append(text(inner)).append("</").append(tag).append(">\n");
    }

    // ── reference resolution ───────────────────────────────────────────────────

    /** Text of an element: its referenced runs joined, else its denormalized text. */
    private static String content(OCDStruct s, OCDIndex ix) {
        return ix.text(s);
    }

    /** First referenced image (directly or in a child) as an {@code src}: an external resource href
     *  (when {@code assets} non-null) or a {@code data:} URI, or {@code null}. */
    private static String imageSrc(OCDStruct s, OCDDocument doc, OCDIndex ix, Assets assets) {
        for (OCDStruct.Ref r : s.refs()) {
            OCDNode n = ix.node(r.page(), r.nodeId());
            if (n instanceof OCDImage img) {
                byte[] data = doc.images().get(img.resourceRef());
                if (data != null) {
                    boolean jpg = "jpg".equals(img.format());
                    String mime = jpg ? "image/jpeg" : "image/png";
                    return assets != null
                            ? assets.ref(named(img.resourceRef(), jpg ? ".jpg" : ".png"), data, mime)
                            : dataUri(mime, data);
                }
            }
        }
        return null;
    }

    private static String dataUri(String mime, byte[] data) {
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
    }

    /** A safe resource filename derived from a reference, ensuring an extension. */
    private static String named(String ref, String ext) {
        String n = EpubPackage.safe(ref);
        return n.contains(".") ? n : n + ext;
    }

    // ── fallback (no structure tree) ───────────────────────────────────────────

    private static void fallback(OCDDocument doc, StringBuilder sb) {
        for (int p = 0; p < doc.pageCount(); p++)
            doc.page(p).texts().filter(t -> t.text() != null && !t.text().isBlank())
                             .forEach(t -> sb.append("<p>").append(text(t.text())).append("</p>\n"));
    }

    // ── escaping ────────────────────────────────────────────────────────────────

    private static String text(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String attr(String s) {
        return text(s).replace("\"", "&quot;");
    }

    static final String CSS =
        "html{font:1rem/1.65 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#1a1a1a}" +
        "body{margin:0}" +
        "main{max-width:42rem;margin:3rem auto;padding:0 1.25rem}" +
        "h1,h2,h3,h4,h5,h6{line-height:1.25;margin:2em 0 .6em;font-weight:650}" +
        "h1{font-size:1.9rem}h2{font-size:1.5rem}h3{font-size:1.25rem}" +
        "p{margin:0 0 1em}" +
        "figure{margin:1.6em 0;text-align:center}" +
        "figure img{max-width:100%;height:auto}" +
        "figcaption{margin-top:.5em;font-size:.9rem;color:#666}" +
        "blockquote{margin:1.2em 0;padding-left:1em;border-left:3px solid #ddd;color:#444}" +
        "pre{background:#f5f5f5;padding:1em;overflow:auto;border-radius:6px}" +
        "table{border-collapse:collapse;margin:1.2em 0}td{border:1px solid #ccc;padding:.4em .7em}" +
        "nav.toc{font-size:.95rem}" +
        "aside{border-left:3px solid #cfe;padding-left:1em;color:#445}";
}
