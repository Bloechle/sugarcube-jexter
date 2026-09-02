package sugarcube.jexter.write;

import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDMedia;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDText;
import sugarcube.jexter.ocd.model.OCDVideo;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Emits an {@link OCDPage} as an <b>SVG-OCD v2</b> page — the canonical, SELF-CONTAINED page
 * serialization of the OCD-EPUB format: paths only (every glyph is a {@code <use>} of an
 * em-normalized outline in {@code <defs>}), no fonts — and, since v2, the page also carries its
 * text stream, reading order, line structure, links and page boxes. There is no page JSON member
 * anymore: the page is the single source of truth.
 *
 * <p>Grammar v2 (all OCD semantics ride on {@code data-*} attributes, stripped by generic export):
 * <ul>
 *   <li>{@code <svg data-ocd="page" data-v="2" data-media="x y w h" [data-crop|bleed|trim|art]
 *       [data-dpi]>} — viewBox from the effective box, rotation wrapper and Y-up→SVG flip exactly
 *       as {@link SvgWriter} (same audited math);</li>
 *   <li>{@code OCDParagraph} → {@code <g id data-ocd="p">} containing {@code <g data-ocd="l">}
 *       line groups (children in CONTENT order, split on {@link sugarcube.jexter.ocd.model.OCDBreak}
 *       — pure grouping, render-neutral);</li>
 *   <li>{@code OCDText} run → {@code <g id data-ocd="t" data-f data-u [data-cl] [data-b] class
 *       transform>}: the font size is FACTORED INTO the run matrix ({@code matrix(fs·…)}) so each
 *       painted glyph is one {@code <use href="#fN-gid" x="em"/>}; {@code data-u} is the unicode of
 *       the full stream (blanks included), {@code data-cl} the per-glyph char counts (only when
 *       non-uniform), {@code data-b} the never-painted sentinels as {@code at:xem} / {@code
 *       at:gid:xem} tokens;</li>
 *   <li>glyph outlines live ONCE per document in the shared sibling {@code pages/f.svg}
 *       (grouped per font: {@code <g id="fN" data-f>}); pages reference them via external
 *       {@code <use href="f.svg#fN-gid">} — the same pattern as the shared images;</li>
 *   <li>PDF link annotations → native {@code <a href>} hit-rects in a trailing {@code <g
 *       data-ocd="links">} (clickable in any reader; internal targets point at the sibling page
 *       file and carry {@code data-y});</li>
 *   <li>{@code OCDPath}/{@code OCDImage} → {@link SvgWriter}'s audited emitters, with ids;
 *       groups → {@code <g id data-ocd="g|gr|media">}; clips/gradients/styles deduped.</li>
 * </ul>
 *
 * <p>Paint order = document order (z), as ever. Reading order is carried by {@code data-o}
 * (the content index), emitted only where it differs from the paint position.
 */
public final class SvgOcdWriter {

    private SvgOcdWriter() {}

    public static String render(OCDDocument doc, OCDPage page, Fonts glyphs) {
        double w = page.displayWidth(), h = page.displayHeight();
        double ch = page.effectiveBox().height();
        var styles = new LinkedHashMap<String, String>();   // css decl -> class
        var clips  = new LinkedHashMap<String, String>();   // MODEL clip id -> clip d (SvgWriter.clipRef)
        var grads  = new LinkedHashMap<String, String>();   // grad def -> id
        var body   = new StringBuilder(8192);

        children(body, doc, page, page.content(), styles, clips, grads, glyphs);
        links(body, doc, page);

        var sb = new StringBuilder(body.length() + 2048);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" ")
          .append("viewBox=\"0 0 ").append(f(w)).append(' ').append(f(h)).append("\" ")
          .append("width=\"").append(f(w)).append("\" height=\"").append(f(h)).append("\" ")
          .append("data-ocd=\"page\" data-v=\"2\"");
        String lang = pageLang(doc, page);
        if (!lang.isEmpty()) sb.append(" xml:lang=\"").append(esc(lang)).append('"');
        if (page.rotation() != 0) sb.append(" data-rot=\"").append(page.rotation()).append('"');
        box(sb, "data-media", page.mediaBox());
        box(sb, "data-crop",  page.cropBox());
        box(sb, "data-bleed", page.bleedBox());
        box(sb, "data-trim",  page.trimBox());
        box(sb, "data-art",   page.artBox());
        if (page.dpi() != 72) sb.append(" data-dpi=\"").append(f(page.dpi())).append('"');
        sb.append(">\n");
        if (!styles.isEmpty()) {
            sb.append("<style>\n");
            for (var e : styles.entrySet()) sb.append('.').append(e.getValue()).append('{').append(e.getKey()).append("}\n");
            sb.append("</style>\n");
        }
        sb.append("<defs>\n");
        for (var e : clips.entrySet())
            sb.append("<clipPath id=\"").append(e.getKey()).append("\" clipPathUnits=\"userSpaceOnUse\">")
              .append("<path d=\"").append(e.getValue()).append("\"/></clipPath>\n");   // id AND geometry are the model's (SvgWriter.clipRef)
        for (var e : grads.entrySet())
            sb.append(e.getKey().replace("__ID__", e.getValue())).append('\n');
        sb.append("</defs>\n");

        double cw = page.effectiveBox().width();
        String rot = switch (page.rotation()) {
            case 90  -> "translate(" + f(ch) + ",0) rotate(90)";
            case 180 -> "translate(" + f(cw) + ',' + f(ch) + ") rotate(180)";
            case 270 -> "translate(0," + f(cw) + ") rotate(270)";
            default  -> null;
        };
        // Typed, so the reader identifies it by NAME: "a <g> with a transform and no data-ocd" was
        // ambiguous the moment the clip carrier introduced an anonymous transformed <g> (§B3).
        if (rot != null) sb.append("<g data-ocd=\"rot\" transform=\"").append(rot).append("\">\n").append(body).append("</g>\n");
        else sb.append(body);
        sb.append("</svg>\n");
        return sb.toString();
    }

    /** Emits siblings in PAINT order (z), tagging {@code data-o} (content index) wherever the
     *  paint position differs — the reader restores reading order by sorting on it. */
    private static void children(StringBuilder sb, OCDDocument doc, OCDPage page, java.util.List<OCDNode> content,
                                 Map<String, String> styles, Map<String, String> clips,
                                 Map<String, String> grads, Fonts glyphs) {
        // Paint order RULES the document (format contract: paint order = document order).
        // Render-transparent OCDGraphic wrappers (analysis clustering: identity transform, no
        // clip/blend, opaque) are SPLICED — their children emit individually at their own z —
        // exactly as OCDNode.inPaintOrder paints them. Emitting such a wrapper as one atomic
        // <g> block would serialize its whole subtree at the group's z and silently invert the
        // paint interleave in the stored file (measured: icon connectors covered by their tile).
        // Groups that carry a paint context (transform, clip, blend, alpha) stay atomic, as in
        // paint. Reading order is carried by data-o against the same spliced list on both sides —
        // emitted ONLY where the two orders disagree, so a document that reads as it paints says nothing.
        var spliced = spliceGraphics(content);
        var reading = new java.util.IdentityHashMap<OCDNode, Integer>(spliced.size());
        for (int i = 0; i < spliced.size(); i++) reading.put(spliced.get(i), i);
        var painted = byZ(spliced);
        for (int zi = 0; zi < painted.size(); zi++) {
            OCDNode n = painted.get(zi);
            int ci = reading.get(n);
            node(sb, doc, page, n, ci != zi ? ci : -1, styles, clips, grads, glyphs);
        }
    }

    private static java.util.List<OCDNode> spliceGraphics(java.util.List<OCDNode> in) {
        var out = new java.util.ArrayList<OCDNode>(in.size());
        for (OCDNode n : in) {
            if (n instanceof OCDGraphic g && g.transform().isIdentity()
                    && !g.hasClip() && !g.hasBlend() && g.alpha() == 1f)
                out.addAll(spliceGraphics(g.children()));
            else out.add(n);
        }
        return out;
    }

    private static void node(StringBuilder sb, OCDDocument doc, OCDPage page, OCDNode n, int order,
                             Map<String, String> styles, Map<String, String> clips,
                             Map<String, String> grads, Fonts glyphs) {
        // ONE clip rule, every node kind alike (§B3): a typed <g> wrapper carries the NATIVE clip-path, so
        // any SVG engine clips with no script and no data-* interpretation to do. See SvgWriter.clipOpen.
        String clipG = SvgWriter.clipOpen(page, n, clips, true);
        sb.append(clipG);
        switch (n) {
            case OCDText t   -> text(sb, doc, t, page, order, styles, glyphs);
            case OCDParagraph para -> {
                sb.append("<g id=\"").append(para.id()).append("\" data-ocd=\"p\"");
                o(sb, order);
                // A block whose runs interleave in paint order is stored as several contiguous fragments
                // sharing one flow id — emitted ONLY then, so a whole paragraph serializes byte-identically.
                if (para.isFragment()) sb.append(" data-flow=\"").append(para.flow()).append('"');
                if (para.transform() != null && !para.transform().isIdentity())
                    sb.append(" data-tr=\"").append(para.transform().toMatrix6()).append('"');
                state(sb, para);
                sb.append(">\n");
                // lines: CONTENT order, split on OCDBreak — pure grouping, render-neutral
                boolean open = false;
                for (OCDNode c : para.children()) {
                    if (c instanceof sugarcube.jexter.ocd.model.OCDBreak) {
                        if (open) { sb.append("</g>\n"); open = false; }
                        continue;
                    }
                    if (!open) { sb.append("<g data-ocd=\"l\">\n"); open = true; }
                    node(sb, doc, page, c, -1, styles, clips, grads, glyphs);
                }
                if (open) sb.append("</g>\n");
                sb.append("</g>\n");
            }
            case OCDPath p   -> { int at = sb.length(); SvgWriter.path(sb, page, p, styles, clips, grads, n.id()); oInject(sb, at, order); }
            case OCDImage im -> { int at = sb.length(); SvgWriter.image(sb, page, im, clips, n.id()); oInject(sb, at, order); }
            case OCDMedia m  -> {
                sb.append("<g id=\"").append(m.id()).append("\" data-ocd=\"media\"");
                o(sb, order);
                sb.append(" data-kind=\"")
                  .append(m instanceof OCDVideo ? "video" : "audio")
                  .append("\" data-ref=\"").append(esc(m.resourceRef())).append('"');
                if (m instanceof OCDVideo v && v.poster() != null) sb.append(" data-poster=\"").append(esc(v.poster())).append('"');
                if (m.transform() != null && !m.transform().isIdentity())
                    sb.append(" data-tr=\"").append(m.transform().toMatrix6()).append('"');
                if (!m.controls()) sb.append(" data-controls=\"0\"");
                if (m.autoplay())  sb.append(" data-autoplay=\"1\"");
                if (m.loop())      sb.append(" data-loop=\"1\"");
                if (m.muted())     sb.append(" data-muted=\"1\"");
                state(sb, m);
                sb.append(">\n");
                if (m instanceof OCDVideo v && v.poster() != null) {
                    OCDImage poster = new OCDImage(v.poster());
                    poster.transform(m.transform());
                    SvgWriter.image(sb, page, poster, clips, null);
                }
                sb.append("</g>\n");
            }
            case OCDGroup g  -> {
                sb.append("<g id=\"").append(g.id()).append("\" data-ocd=\"").append(kind(g)).append('"');
                o(sb, order);
                if (g.transform() != null && !g.transform().isIdentity())
                    sb.append(" data-tr=\"").append(g.transform().toMatrix6()).append('"');
                if (g instanceof sugarcube.jexter.ocd.model.OCDLayerContent lc && lc.layerId() != null)
                    sb.append(" data-ref=\"").append(esc(lc.layerId())).append('"');
                state(sb, g);
                sb.append(">\n");
                children(sb, doc, page, g.children(), styles, clips, grads, glyphs);
                sb.append("</g>\n");
            }
            default -> { }   // OCDBreak: text-layer member only — never paints
        }
        if (!clipG.isEmpty()) sb.append(SvgWriter.CLIP_CLOSE);
    }

    /** Siblings in paint order, containment preserved: SVG-OCD nests paragraphs/groups (the
     *  editing surface needs the containment), so z is honored per level — never spliced flat.
     *  Fidelity vs the flat-splice reference is pixel-gated by the Fid2 harness. */
    private static java.util.List<OCDNode> byZ(java.util.List<OCDNode> nodes) {
        var out = new java.util.ArrayList<>(nodes);
        out.sort(java.util.Comparator.comparingDouble(OCDNode::z));
        return out;
    }

    /** Non-visual model state as data-* passthrough — never painted (visual parity with the
     *  audited SvgWriter), restored verbatim by the loader. */
    private static void state(StringBuilder sb, OCDNode n) {
        if (n.name() != null && !n.name().isEmpty()) sb.append(" data-name=\"").append(esc(n.name())).append('"');
        if (n.hasRole())  sb.append(" data-role=\"").append(esc(n.role())).append('"');
        if (n.hasBlend()) sb.append(" data-blend=\"").append(esc(n.blend())).append('"');
        if (n.alpha() < 1f) sb.append(" data-alpha=\"").append(f(n.alpha())).append('"');
    }

    private static String esc(String v) {
        var b = new StringBuilder(v.length() + 8);
        sugarcube.jexter.core.JxText.text(b, v);
        return b.toString();
    }

    private static String kind(OCDGroup g) {
        if (g instanceof OCDParagraph) return "p";
        if (g instanceof OCDGraphic)   return "gr";
        if (g instanceof sugarcube.jexter.ocd.model.OCDLayerContent) return "layer";
        return "g";
    }

    // ── Text run: outline glyphs, model x verbatim ─────────────────────────────

    private static void text(StringBuilder sb, OCDDocument doc, OCDText t, OCDPage page, int order,
                             Map<String, String> styles, Fonts glyphs) {
        if (t.glyphs().isEmpty()) return;
        OCDFont font = doc.findFont(t.fontId());
        String ff = JxName.safe(t.fontId());
        double fs = t.fontSize();

        JxTransform flip = SvgWriter.pageFlip(page);
        // Outline glyphs are Y-up (baseline y=0, ascenders +Y): pageFlip · tr maps them straight
        // to screen. The extra flipY of the native-<text> path is the FONT renderer's Y-down
        // compensation and must NOT apply here (it would mirror every glyph about its baseline).
        // v2: the FONT SIZE is factored into the run matrix, so glyphs place with a bare em x.
        JxTransform T = flip.concat(t.transform()).concat(JxTransform.scale(fs > 0 ? fs : 1));

        String cls = SvgWriter.classFor(styles, textCss(t, fs));

        // full-stream unicode (blanks included) + cluster map + never-painted sentinels
        var u = new StringBuilder();
        boolean uniform = true;
        for (OCDText.Glyph g : t.glyphs()) {
            String gu = g.unicode() == null ? "" : g.unicode();
            if (gu.length() != 1) uniform = false;
            u.append(gu);
        }
        // The FONT SIZE is stated, not inferred. It is also folded into the run matrix (glyphs are
        // em-normalized, so the matrix must carry it to place them), but the matrix alone cannot give it
        // back: recovering it from the determinant only works when the run's own matrix has |det| = 1, and
        // an anisotropic run — PDF Tz horizontal scaling, a squeezed CTM — makes the two indistinguishable.
        // The product still paints correctly, so the error is silent; it surfaces later, as a wrong size in
        // the analysis signals. One number removes the guess.
        sb.append("<g id=\"").append(t.id()).append("\" data-ocd=\"t\" data-f=\"").append(ff)
          .append("\" data-fs=\"").append(f(fs)).append('"');
        o(sb, order);
        sb.append(" data-u=\"").append(sugarcube.jexter.core.JxText.attr(u)).append('"');
        if (!uniform) {
            sb.append(" data-cl=\"");
            boolean first = true;
            for (OCDText.Glyph g : t.glyphs()) {
                if (!first) sb.append(' ');
                sb.append(g.unicode() == null ? 0 : g.unicode().length());
                first = false;
            }
            sb.append('"');
        }
        double k = fs > 0 ? fs : 1;
        var b = new StringBuilder();
        int i = 0;
        // ONE predicate decides the partition. A glyph is recorded in data-b iff it will not be
        // painted, and it will not be painted iff it has no outline to reference — glyphDef returns
        // null for the Spacer's sentinel (gid −1), for an unknown gid and for an inkless glyph alike.
        // Driving data-b off isBlank() (unicode-blank) while <use> skipped on "no outline" made two
        // predicates partition the same set, and a glyph that is inkless but NOT unicode-blank fell
        // between them: NBSP (U+00A0) is not whitespace to Character.isWhitespace, so it was painted
        // nowhere and recorded nowhere, while data-u still carried its char. The reader then aligned
        // N-1 glyphs against N chars and TRUNCATED the tail of the run — measured on a real form:
        // "VA\u00A0FORM" came back "VA\u00A0FOR", and a run holding a lone NBSP came back empty.
        for (OCDText.Glyph g : t.glyphs()) {
            if (glyphDef(font, ff, g.gid(), glyphs) == null) {
                if (b.length() > 0) b.append(' ');
                b.append(i).append(':');
                if (g.gid() != -1) b.append(g.gid()).append(':');
                b.append(f(g.x() / k));
            }
            i++;
        }
        if (b.length() > 0) sb.append(" data-b=\"").append(b).append('"');
        if (t.renderMode() != OCDText.FILL) sb.append(" data-rm=\"").append(t.renderMode()).append('"');
        // The mode is stated, so the colour that goes with it must be too: a stroke-only or invisible
        // run gets fill:none, and its fill would otherwise have no home in the page and read back as 0.
        // Emitted ONLY when the CSS drops a real colour, so every ordinary run stays byte-identical.
        if (!statesFill(t) && (t.fill() >>> 24) != 0)
            sb.append(" data-fill=\"").append(new JxColor(t.fill()).hex()).append('"');
        state(sb, t);
        sb.append(" class=\"").append(cls).append("\" transform=\"matrix(").append(T.toMatrix6()).append(")\">");

        for (OCDText.Glyph g : t.glyphs()) {
            String def = glyphDef(font, ff, g.gid(), glyphs);
            if (def == null) continue;                        // no outline: recorded in data-b above, paints nothing
            sb.append("<use href=\"f.svg#").append(def).append("\" x=\"").append(f(g.x() / k)).append("\"/>");
        }
        sb.append("</g>\n");
    }

    /** Document-stable font aliases: {@code f0..fN} over the SORTED safe font names —
     *  deterministic on both sides of the round-trip, independent of page and usage order. */
    public static final class Fonts {
        final java.util.TreeMap<String, String> alias = new java.util.TreeMap<>();
        public Fonts(OCDDocument doc) {
            var names = new java.util.TreeSet<String>();
            for (OCDFont f : doc.fonts().values()) names.add(JxName.safe(f.id()));
            int i = 0;
            for (String n : names) alias.put(n, "f" + i++);
        }
    }

    /** The shared font file ({@code pages/f.svg}) — since fonts.json died, this is THE
     *  single font representation: per font a {@code <g id="fN" data-f data-id …metrics…
     *  data-cmap>} carrying the COMPLETE model (weight, style, ascent/descent, cap/x-height,
     *  space width, explicit cmap sorted by codepoint) and EVERY glyph as {@code <path
     *  id="qN-gid" d data-adv [data-u] [data-gname]>} — inkless glyphs (spaces) keep an empty
     *  {@code d} so their advance and unicode survive. Sorted fonts, sorted gids, sorted cmap:
     *  byte-deterministic. */
    public static String glyphsSvg(OCDDocument doc, Fonts glyphs) {
        var byName = new java.util.TreeMap<String, OCDFont>();
        for (OCDFont f : doc.fonts().values()) byName.put(JxName.safe(f.id()), f);
        var sb = new StringBuilder(128 * 1024);
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" data-ocd=\"fonts\" data-v=\"2\">\n<defs>\n");
        for (var fe : byName.entrySet()) {
            OCDFont f = fe.getValue();
            sb.append("<g id=\"").append(glyphs.alias.get(fe.getKey()))
              .append("\" data-f=\"").append(esc(fe.getKey()))
              .append("\" data-id=\"").append(sugarcube.jexter.core.JxText.attr(f.id())).append('"');
            if (!f.name().isEmpty() && !f.name().equals(f.id()))
                sb.append(" data-name=\"").append(sugarcube.jexter.core.JxText.attr(f.name())).append('"');
            if (!f.family().isEmpty() && !f.family().equals(f.name()))
                sb.append(" data-family=\"").append(sugarcube.jexter.core.JxText.attr(f.family())).append('"');
            if (!"normal".equals(f.weight())) sb.append(" data-weight=\"").append(esc(f.weight())).append('"');
            if (!"normal".equals(f.style()))  sb.append(" data-style=\"").append(esc(f.style())).append('"');
            if (f.embedded()) sb.append(" data-embedded=\"1\"");
            sb.append(" data-asc=\"").append(f(f.ascent())).append("\" data-desc=\"").append(f(f.descent()))
              .append("\" data-cap=\"").append(f(f.capHeight())).append("\" data-x=\"").append(f(f.xHeight()))
              .append("\" data-sp=\"").append(f(f.spaceWidth())).append('"');
            if (!f.cmap().isEmpty()) {
                sb.append(" data-cmap=\"");
                boolean first = true;
                for (var e : new java.util.TreeMap<>(f.cmap()).entrySet()) {
                    if (!first) sb.append(' ');
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
                sb.append('"');
            }
            sb.append(">\n");
            for (var ge : new java.util.TreeMap<>(f.glyphs()).entrySet())
                glyphPath(sb, glyphs.alias.get(fe.getKey()), ge.getValue());
            sb.append("</g>\n");
        }
        sb.append("</defs>\n</svg>\n");
        return sb.toString();
    }

    /** The ONE glyph markup: {@code <path id="fN-gid" d data-adv [data-u] [data-gname]/>} —
     *  emitted identically into {@code pages/f.svg} (full library) and into each page's own
     *  {@code <defs data-ui="fonts">} (used subset). */
    private static void glyphPath(StringBuilder sb, String alias, OCDGlyph g) {
        sb.append("<path id=\"").append(alias).append('-').append(g.gid())
          .append("\" d=\"").append(g.outline() != null ? g.outline().toSvg() : "")
          .append("\" data-adv=\"").append(f(g.advance())).append('"');
        if (g.unicode() != null && !g.unicode().isEmpty())
            sb.append(" data-u=\"").append(sugarcube.jexter.core.JxText.attr(g.unicode())).append('"');
        if (g.name() != null && !g.name().isEmpty())
            sb.append(" data-gname=\"").append(sugarcube.jexter.core.JxText.attr(g.name())).append('"');
        sb.append("/>\n");
    }

    /** Register the glyph outline in defs (dedup) and return its aliased id — null when no ink. */
    private static String glyphDef(OCDFont font, String ff, int gid, Fonts glyphs) {
        if (font == null) return null;
        OCDGlyph g = font.glyphs().get(gid);
        if (g == null || g.outline() == null || g.outline().isEmpty()) return null;
        String q = glyphs.alias.get(ff);
        return q == null ? null : q + "-" + gid;             // inkless glyphs already returned null above
    }

    // ── reading order + links ────────────────────────────────────────────────────

    private static void o(StringBuilder sb, int order) {
        if (order >= 0) sb.append(" data-o=\"").append(order).append('"');
    }

    /** Injects {@code data-o} into an element just emitted by a {@link SvgWriter} helper (path,
     *  image): right after its id attribute — the helpers open with {@code <tag id="…"}. */
    private static void oInject(StringBuilder sb, int at, int order) {
        if (order < 0) return;
        int id = sb.indexOf("id=\"", at);
        if (id < at) return;                                 // helper emitted no id (e.g. a poster)
        int close = sb.indexOf("\"", id + 4);
        if (close > 0) sb.insert(close + 1, " data-o=\"" + order + "\"");
    }

    /** PDF link annotations as native SVG anchors: transparent hit-rects, clickable in any
     *  reader. Internal targets point at the sibling page file and carry {@code data-y}. */
    private static void links(StringBuilder sb, OCDDocument doc, OCDPage page) {
        if (page.links().isEmpty()) return;
        JxTransform flip = SvgWriter.pageFlip(page);
        sb.append("<g data-ocd=\"links\">\n");
        for (sugarcube.jexter.ocd.model.OCDLink lk : page.links()) {
            sugarcube.jexter.core.JxRect r = flip.apply(lk.rect());
            sb.append("<a href=\"");
            if (lk.isExternal()) sb.append(esc(lk.uri()));
            else sb.append(String.format("page-%03d.xhtml", lk.pageIndex() + 1));
            sb.append('"');
            if (!lk.isExternal() && lk.hasY()) sb.append(" data-y=\"").append(f(lk.y())).append('"');
            sb.append("><rect x=\"").append(f(r.x())).append("\" y=\"").append(f(r.y()))
              .append("\" width=\"").append(f(r.width())).append("\" height=\"").append(f(r.height()))
              .append("\" style=\"fill:#000;fill-opacity:0\"/></a>\n");
        }
        sb.append("</g>\n");
    }

    /** The page's language: detected from its own text (deterministic — the round-trip stays
     *  byte-stable), falling back to the document language. Empty when both are undetermined. */
    private static String pageLang(OCDDocument doc, OCDPage page) {
        var txt = new StringBuilder(2048);
        for (OCDText t : (Iterable<OCDText>) page.texts()::iterator) {
            txt.append(t.text()).append(' ');
            if (txt.length() >= 2500) break;
        }
        String lang = sugarcube.jexter.ocd.analysis.LanguageDetector.guess(txt.toString());
        if (!lang.isEmpty()) return lang;
        return doc.meta() != null ? doc.meta().language().trim() : "";
    }

    private static void box(StringBuilder sb, String name, sugarcube.jexter.core.JxRect r) {
        if (r == null) return;
        sb.append(' ').append(name).append("=\"").append(f(r.x())).append(' ').append(f(r.y()))
          .append(' ').append(f(r.width())).append(' ').append(f(r.height())).append('"');
    }

    /** Does the paint CSS state a fill for this run? Mode {@code STROKE} paints only the outline and
     *  {@code INVISIBLE} paints nothing, so both get {@code fill:none} — see {@link #textCss}. The one
     *  place this is decided: the emitter asks it to know whether the colour still needs stating. */
    private static boolean statesFill(OCDText t) {
        int rm = t.renderMode();
        boolean paints = rm == OCDText.FILL || rm == OCDText.STROKE || rm == OCDText.FILL_STROKE;
        return paints && rm != OCDText.STROKE && (t.hasFill() || !t.hasStrokePaint());
    }

    /** Paint CSS for a run — {@link SvgWriter}'s semantics on outline glyphs: stroke widths are
     *  divided by the font size (the {@code <use>} scale re-multiplies them back to text space). */
    private static String textCss(OCDText t, double fs) {
        int rm = t.renderMode();
        boolean stroked = (rm == OCDText.FILL || rm == OCDText.STROKE || rm == OCDText.FILL_STROKE)
                          && t.hasStrokePaint() && rm != OCDText.FILL;
        boolean filled  = statesFill(t);
        var css = new StringBuilder();
        if (filled) {
            css.append("fill:").append(new JxColor(t.fill()).rgbHex());
            float a = new JxColor(t.fill()).alpha();
            if (a < 1f) css.append(";fill-opacity:").append(f(a));
        } else {
            css.append("fill:none");
        }
        if (stroked) {
            double k = fs > 0 ? fs : 1;
            css.append(";stroke:").append(new JxColor(t.stroke()).rgbHex());
            if (t.strokeWidth() > 0) css.append(";stroke-width:").append(f(t.strokeWidth() / k));
            if (t.cap() == 1) css.append(";stroke-linecap:round");
            else if (t.cap() == 2) css.append(";stroke-linecap:square");
            if (t.join() == 1) css.append(";stroke-linejoin:round");
            else if (t.join() == 2) css.append(";stroke-linejoin:bevel");
            if (t.miterLimit() > 0 && t.miterLimit() != 10) css.append(";stroke-miterlimit:").append(f(t.miterLimit()));
            if (t.hasDash()) {
                css.append(";stroke-dasharray:");
                double[] dash = t.dash();
                for (int i = 0; i < dash.length; i++) { if (i > 0) css.append(','); css.append(f(dash[i] / k)); }
                if (t.dashPhase() > 0) css.append(";stroke-dashoffset:").append(f(t.dashPhase() / k));
            }
            float sa = new JxColor(t.stroke()).alpha();
            if (sa < 1f) css.append(";stroke-opacity:").append(f(sa));
        }
        if (t.hasBlend() && !t.blend().equalsIgnoreCase("Normal"))
            css.append(";mix-blend-mode:").append(t.blend().toLowerCase(Locale.US));
        return css.toString();
    }

    private static String f(double v) { return SvgWriter.f(v); }
}
