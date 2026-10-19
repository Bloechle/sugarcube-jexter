package sugarcube.jexter.ocd.io;

import sugarcube.jexter.core.JxBlend;
import sugarcube.jexter.core.JxXml;
import sugarcube.jexter.core.JxCmyk;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxStroke;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDAudio;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDClip;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGlyph;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDGradient;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDLayerContent;
import sugarcube.jexter.ocd.model.OCDLink;
import sugarcube.jexter.ocd.model.OCDMedia;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.OCDText;
import sugarcube.jexter.ocd.model.OCDVideo;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static sugarcube.jexter.core.JxJson.bool;
import static sugarcube.jexter.core.JxJson.has;
import static sugarcube.jexter.core.JxJson.optArr;
import static sugarcube.jexter.core.JxJson.optObj;
import static sugarcube.jexter.core.JxJson.str;

/**
 * One SVG-OCD member read back into the model — the inverse of {@link SvgOcdWriter}: a page
 * ({@link #page}: its boxes, its content in paint order re-sorted into reading order, its lines, links
 * and clips) and the font sheet ({@link #fonts}). One reader per page holds that page's context — its
 * paint classes, gradients, clips, fonts and the nodes read so far — so no method passes it around.
 * Container-level reading (the zip, the spine, the JSON members) is {@link OCDReader}'s.
 */
final class SvgOcdReader {

    private final OCDPage page;
    private final Map<String, String> css;
    private final Map<String, OCDGradient> grads;
    private final Map<String, JxPath> clipDefs;
    private final Map<String, OCDFont> bySafe = new HashMap<>();
    private final Map<String, OCDNode> parsed = new LinkedHashMap<>();   // id -> node, in paint (document) order
    private final Map<OCDNode, Integer> ord = new HashMap<>();           // node -> data-order (reading index)
    private float z;


    /** The crop box a page's root {@code viewBox} states, in page space — null when the window is the
     *  whole media box (an uncropped page says {@code 0 0 w h}, exactly as it always did). */
    private static JxRect window(Element svg, JxRect media) {
        String[] p = svg.getAttribute("viewBox").trim().split("[\\s,]+");
        if (p.length != 4) return null;
        double x = dbl(p[0], 0), y = dbl(p[1], 0), w = dbl(p[2], 0), h = dbl(p[3], 0);
        if (w <= 0 || h <= 0) return null;
        if (x == 0 && y == 0 && w == media.width() && h == media.height()) return null;
        return new JxRect(media.x() + x, (media.y() + media.height()) - (y + h), w, h);
    }

    // ── page: SVG-OCD v2 — the page is the single source (text, order, lines, links, boxes) ──
    /** One SVG-OCD page → its {@link OCDPage}: boxes, content in reading order, links, clips. */
    static OCDPage page(Element svg, OCDDocument doc) {
        return new SvgOcdReader(svg, doc).page;
    }

    private SvgOcdReader(Element svg, OCDDocument doc) {

        JxRect media = battr(svg, "data-mediabox");
        if (media == null) media = new JxRect(0, 0, dbl(svg.getAttribute("width"), 0), dbl(svg.getAttribute("height"), 0));
        page = new OCDPage(media);
        if (svg.hasAttribute("data-rotate")) page.rotation((int) dbl(svg.getAttribute("data-rotate"), 0));
        page.cmykBlending("cmyk".equals(svg.getAttribute("data-blending")));
        if (svg.hasAttribute("data-dpi")) page.dpi(dbl(svg.getAttribute("data-dpi"), 72));
        JxRect r;
        // The crop is a WINDOW: the root viewBox states it (§B2), so a re-crop moves no element and the
        // stored page is the unrotated document.
        page.cropBox(window(svg, media));
        if ((r = battr(svg, "data-bleedbox")) != null) page.bleedBox(r);
        if ((r = battr(svg, "data-trimbox"))  != null) page.trimBox(r);
        if ((r = battr(svg, "data-artbox"))   != null) page.artBox(r);

        css = cssClasses(svg);
        grads = gradients(svg);
        clipDefs = clipPaths(svg, page);
        for (OCDFont f : doc.fonts().values()) bySafe.put(JxName.safe(f.id()), f);
        for (Element el : children(svg)) {
            if ("links".equals(el.getAttribute("data-ocd"))) { links(el); continue; }
            walk(el, null, null);
        }

        // reading order: paint order re-sorted by data-order (content index), exact reconstruction —
        // nodes without data-order were emitted at their content position already
        for (OCDNode n : parsed.values())
            if (topLevel(n)) page.add(n);
        reorder(page.content());
        for (OCDNode n : parsed.values())
            if (n instanceof OCDGroup g && !(g instanceof OCDParagraph)) reorder(g.children());

        for (var e : clipDefs.entrySet()) page.addClip(new OCDClip(e.getKey(), e.getValue()));
    }

    /** Stable re-sort by reading index: {@code data-order} where present, current position otherwise. */
    private void reorder(List<OCDNode> children) {
        if (children.size() < 2) return;
        var pos = new HashMap<OCDNode, Integer>();
        for (int i = 0; i < children.size(); i++) pos.put(children.get(i), i);
        children.sort(java.util.Comparator.comparingInt(n -> ord.getOrDefault(n, pos.get(n))));
    }

    /** Native page anchors → {@link OCDLink}s (SVG space back to page space: {@link OCDPage#fromSvg()}). */
    private void links(Element g) {
        JxTransform flip = page.fromSvg();
        for (Element a : children(g)) {
            if (!"a".equals(a.getLocalName())) continue;
            Element rc = null;
            for (Element c : children(a)) if ("rect".equals(c.getLocalName())) { rc = c; break; }
            if (rc == null) continue;
            JxRect sr = new JxRect(dbl(rc.getAttribute("x"), 0), dbl(rc.getAttribute("y"), 0),
                                   dbl(rc.getAttribute("width"), 0), dbl(rc.getAttribute("height"), 0));
            OCDLink lk = new OCDLink(flip.apply(sr));
            String href = a.getAttribute("href");
            if (href == null || href.isEmpty()) href = a.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            int target = OCDVocab.linkedPage(href);
            if (target >= 0) {
                lk.pageIndex(target);
                if (a.hasAttribute("data-y")) lk.y(dbl(a.getAttribute("data-y"), Double.NaN));
            } else lk.uri(href == null ? "" : href);
            page.addLink(lk);
        }
    }

    /** {@code "x y w h"} box attribute → rect (null when absent). */
    private static JxRect battr(Element el, String name) {
        String v = el.getAttribute(name);
        if (v == null || v.isEmpty()) return null;
        String[] p = v.trim().split("\\s+");
        if (p.length != 4) return null;
        return new JxRect(Double.parseDouble(p[0]), Double.parseDouble(p[1]),
                          Double.parseDouble(p[2]), Double.parseDouble(p[3]));
    }

    private boolean topLevel(OCDNode n) {
        for (OCDNode p : parsed.values())
            if (p instanceof OCDGroup g && g.children().contains(n)) return false;
        return true;
    }

    // ── SVG-OCD walk ─────────────────────────────────────────────────────────────
    private void walk(Element el, OCDGroup parent, String wrapClip) {
        String tag = el.getLocalName();
        String ocd = el.getAttribute("data-ocd");
        switch (tag) {
            case "g" -> {                       // le nom de BALISE SVG, pas le vocabulaire OCD
                switch (ocd) {
                    case "paragraph" -> {
                        OCDParagraph g = new OCDParagraph();
                        g.id(el.getAttribute("id"));
                        if (el.hasAttribute("data-flow")) g.flow((int) dbl(el.getAttribute("data-flow"), -1));
                        if (el.hasAttribute("data-matrix")) g.transform(mat6(el.getAttribute("data-matrix")));
                        state(el, g);
                        g.z(z += 1f);
                        register(g, parent, wrapClip);
                        order(el, g);
                        // <g data-ocd="line"> line groups: containment only — an OCDBreak between lines
                        boolean anyLine = false;
                        for (Element c : children(el)) {
                            if ("g".equals(c.getLocalName()) && "line".equals(c.getAttribute("data-ocd"))) {
                                if (anyLine) { OCDBreak br = new OCDBreak(); br.z(z += 1f); g.add(br); }
                                anyLine = true;
                                for (Element lc : children(c))
                                    walk(lc, g, null);
                            } else walk(c, g, null);
                        }
                    }
                    case "graphic", "group", "layer" -> {
                        OCDGroup g = switch (ocd) {
                            case "graphic" -> new OCDGraphic();
                            case "layer" -> new OCDLayerContent(attr(el, "data-layer"));
                            default      -> new OCDGroup();
                        };
                        g.id(el.getAttribute("id"));
                        if (el.hasAttribute("data-matrix")) g.transform(mat6(el.getAttribute("data-matrix")));
                        state(el, g);
                        g.z(z += 1f);
                        register(g, parent, wrapClip);
                        order(el, g);
                        for (Element c : children(el)) walk(c, g, null);
                    }
                    case "links" -> { }                        // parsed at page level, never content
                    case "run" -> {
                        OCDText t = text(el);
                        t.z(z += 1f);
                        register(t, parent, wrapClip);
                        order(el, t);
                    }
                    case "media" -> {
                        String ref = attr(el, "data-src");
                        OCDMedia mm = "audio".equals(el.getAttribute("data-kind")) ? new OCDAudio(ref) : new OCDVideo(ref);
                        if (mm instanceof OCDVideo v && el.hasAttribute("data-poster")) v.poster(el.getAttribute("data-poster"));
                        if (el.hasAttribute("data-matrix")) mm.transform(mat6(el.getAttribute("data-matrix")));
                        if ("0".equals(attr(el, "data-controls"))) mm.controls(false);
                        if ("1".equals(attr(el, "data-autoplay"))) mm.autoplay(true);
                        if ("1".equals(attr(el, "data-loop")))     mm.loop(true);
                        if ("1".equals(attr(el, "data-muted")))    mm.muted(true);
                        mm.id(el.getAttribute("id"));
                        state(el, mm);
                        mm.z(z += 1f);
                        register(mm, parent, wrapClip);
                        order(el, mm);
                        // the poster <image> inside is a paint copy — not a model node
                    }
                    default -> {
                        // The clip wrapper (data-ocd="clip"): its clip-path names the model clip, and its
                        // transform is the page flip — immediately undone by the inner <g>, so both are paint
                        // carriers and neither is a model node. Both land here; the id must survive the inner
                        // one, hence the fall-back to the inherited wrapClip.
                        String cp = el.getAttribute("clip-path");
                        String wc = cp != null && cp.startsWith("url(#") ? cp.substring(5, cp.length() - 1) : wrapClip;
                        // Pure paint carriers: nothing here is a model node, so every child is walked
                        // straight through, carrying the clip. (An image used to be lifted out of its
                        // wrapper here; it no longer has one, and reading its data-o off the wrapper is
                        // exactly how its reading-order index went missing.)
                        for (Element c : children(el))
                            walk(c, parent, wc);
                    }
                }
            }
            case "path" -> {
                if (!el.hasAttribute("id")) return;            // glyph defs live in <defs>; content paths carry ids
                OCDPath p = path(el);
                cmyk(el, p);
                p.z(z += 1f);
                register(p, parent, wrapClip);
                order(el, p);
            }
            case "image" -> {
                OCDImage im = image(el);
                if (im == null) return;                         // poster copies carry no id
                im.z(z += 1f);
                register(im, parent, wrapClip);
                order(el, im);
            }
            default -> { }
        }
    }

    private void order(Element el, OCDNode n) {
        if (el.hasAttribute("data-order")) ord.put(n, (int) dbl(el.getAttribute("data-order"), 0));
    }

    /** A node read from inside a clip wrapper takes the wrapper's clip — the wrapper itself is spliced,
     *  it is a paint carrier, not a model node. */
    private void register(OCDNode n, OCDGroup parent, String wrapClip) {
        if (wrapClip != null && !n.hasClip()) n.clipId(wrapClip);
        if (n.id() != null && !n.id().isEmpty()) parsed.put(n.id(), n);
        if (parent != null) parent.add(n);
    }

    private OCDText text(Element el) {
        OCDFont font = bySafe.get(el.getAttribute("data-font"));
        // The font size is STATED (data-size) — it is also folded into the run matrix so the em-normalized
        // glyphs place, but the matrix alone cannot give it back: the determinant conflates it with an
        // anisotropic run matrix (Tz, a squeezed CTM). §B4.
        JxTransform T = transformOf(el.getAttribute("transform"));
        double fs = dbl(el.getAttribute("data-size"), 0);
        OCDText t = new OCDText(font != null ? font.id() : el.getAttribute("data-font"), fs);
        t.id(el.getAttribute("id"));
        if (el.hasAttribute("data-render")) t.renderMode((int) dbl(el.getAttribute("data-render"), 0));
        if (el.hasAttribute("data-conf"))   t.confidence(dbl(el.getAttribute("data-conf"), Double.NaN));
        state(el, t);

        // tr = flipInv ∘ T ∘ scale(1/fs)  (T = pageFlip ∘ tr ∘ scale(fs) was written)
        double k = fs > 0 ? fs : 1;
        JxTransform T0 = T.concat(JxTransform.scale(1 / k));
        t.transform(page.fromSvg().concat(T0));

        // paint from the class decls (stroke widths/dash were divided by fs at write)
        Map<String, String> d = decls(css, el.getAttribute("class"));
        String fill = d.get("fill");
        if (fill != null && !"none".equals(fill)) t.fill(argb(fill, d.get("fill-opacity")));
        else if ("none".equals(fill)) t.fill(0);
        // A non-painting render mode writes fill:none; the colour is stated instead (§B4) and wins here.
        String stated = el.getAttribute("data-fill");
        if (!stated.isEmpty()) t.fill(JxColor.ofHex(stated).argb());
        String stroke = d.get("stroke");
        if (stroke != null && !"none".equals(stroke)) {
            t.stroke(argb(stroke, d.get("stroke-opacity"))).pen(pen(d).scaled(k));   // stated in em, held in text space
        }
        blend(d, t);

        // painted glyphs: gid from the aliased def id suffix, x is em → text space (× fs)
        var painted = new java.util.ArrayList<double[]>();       // {gid, x}
        for (Element u : children(el)) {
            if (!"use".equals(u.getLocalName())) continue;
            String href = u.getAttribute("href");
            if (href == null || href.isEmpty()) href = u.getAttributeNS("http://www.w3.org/1999/xlink", "href");
            int dash = href.lastIndexOf('-');
            painted.add(new double[]{ dbl(href.substring(dash + 1), 0), dbl(u.getAttribute("x"), 0) * k });
        }
        // sentinels (data-blanks: "at:xem" | "at:gid:xem") + full-stream unicode (data-text) + clusters
        var blanks = new java.util.HashMap<Integer, double[]>(); // at -> {gid, x}
        String db = el.getAttribute("data-blanks");
        if (db != null && !db.isEmpty())
            for (String tok : db.trim().split("\\s+")) {
                String[] pr = tok.split(":");
                if (pr.length == 2)      blanks.put(Integer.parseInt(pr[0]), new double[]{ -1, Double.parseDouble(pr[1]) * k });
                else if (pr.length == 3) blanks.put(Integer.parseInt(pr[0]), new double[]{ Double.parseDouble(pr[1]), Double.parseDouble(pr[2]) * k });
            }
        String u = el.getAttribute("data-text");
        if (u == null) u = "";
        int total = painted.size() + blanks.size();
        int[] cl = new int[total];
        String dcl = el.getAttribute("data-chars");
        if (dcl != null && !dcl.isEmpty()) {
            String[] ps = dcl.trim().split("\\s+");
            for (int i2 = 0; i2 < total; i2++) cl[i2] = i2 < ps.length ? Integer.parseInt(ps[i2]) : 1;
        } else java.util.Arrays.fill(cl, 1);
        int pi = 0, cpos = 0;
        for (int i2 = 0; i2 < total; i2++) {
            int len = cl[i2];
            String gu = cpos + len <= u.length() ? u.substring(cpos, cpos + len) : "";
            cpos += len;
            double[] bl = blanks.get(i2);
            if (bl != null) t.add((int) bl[0], bl[1], gu);
            else if (pi < painted.size()) { double[] g = painted.get(pi++); t.add((int) g[0], g[1], gu); }
        }
        return t;
    }

    private OCDPath path(Element el) {
        Map<String, String> d = decls(css, el.getAttribute("class"));
        JxPath geo = JxPath.ofSvg(el.getAttribute("d"));
        // A path is in page space and its transform is the page flip (§B3). A viewer that moved it (Prism's
        // editor composes the move in front of the flip) left more than the flip: that remainder is baked
        // into the geometry, never dropped. A malformed transform is refused by mat6, with what it read.
        JxTransform extra = page.fromSvg().concat(transformOf(el.getAttribute("transform")));
        if (!extra.isIdentity()) geo = geo.transformed(extra.awt());
        if ("evenodd".equals(d.get("fill-rule"))) geo = geo.evenOdd();
        OCDPath p = new OCDPath(geo);
        p.id(el.getAttribute("id"));
        String fill = d.get("fill");
        if (fill != null && fill.startsWith("url(#")) {
            OCDGradient g = grads.get(fill.substring(5, fill.length() - 1));
            if (g != null) p.fill(g.flatArgb()).fillGradient(g);   // flat fallback rides along —
        } else if (fill != null && !"none".equals(fill)) {         // exactly as the importer sets it
            p.fill(argb(fill, d.get("fill-opacity")));
        }
        String stroke = d.get("stroke");
        if (stroke != null && !"none".equals(stroke)) {
            p.stroke(argb(stroke, d.get("stroke-opacity"))).pen(pen(d));
        }
        blend(d, p);
        return p;
    }

    private OCDImage image(Element el) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty()) return null;
        String href = el.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        if (href == null || href.isEmpty()) href = el.getAttribute("href");
        OCDImage im = new OCDImage(href.substring(href.lastIndexOf('/') + 1));
        im.id(id);

        // transform = "matrix(pageFlip) matrix(t) translate(0 1) scale(1 -1)" — ALWAYS. An image states its
        // own placement whether it is clipped or not (§B3: a clip rides on a wrapper whose two nested flips
        // cancel), so the leading page flip is always the one to strip.
        JxTransform P = page.fromSvg().concat(transformOf(el.getAttribute("transform")));   // strip the page flip
        JxTransform TS = new JxTransform(1, 0, 0, -1, 0, 1);   // translate(0,1)·scale(1,-1), self-inverse
        im.transform(P.concat(TS));

        if (el.hasAttribute("opacity")) im.alpha((float) dbl(el.getAttribute("opacity"), 1));
        String style = el.getAttribute("style");
        if (style != null && style.contains("mix-blend-mode:"))
            im.blend(JxBlend.model(style.substring(style.indexOf("mix-blend-mode:") + 15).replace(";", "").trim()));
        return im;
    }

    private static void state(Element el, OCDNode n) {
        if (el.hasAttribute("data-name"))  n.name(el.getAttribute("data-name"));
        if (el.hasAttribute("data-role"))  n.role(el.getAttribute("data-role"));
        if (el.hasAttribute("data-blend")) n.blend(el.getAttribute("data-blend"));
        if (el.hasAttribute("data-alpha")) n.alpha((float) dbl(el.getAttribute("data-alpha"), 1));
        cmyk(el, n);
    }

    /** The source CMYK behind a node's fill / stroke (FORMAT §B3) — on runs and groups through
     *  {@link #state}, on paths directly: a path's other paint rides in its class. */
    private static void cmyk(Element el, OCDNode n) {
        if (el.hasAttribute("data-cmyk"))        n.fillCmyk(JxCmyk.parse(el.getAttribute("data-cmyk")));
        if (el.hasAttribute("data-cmyk-stroke")) n.strokeCmyk(JxCmyk.parse(el.getAttribute("data-cmyk-stroke")));
    }

    private static void blend(Map<String, String> d, OCDNode n) {
        String bl = d.get("mix-blend-mode");
        if (bl != null) n.blend(JxBlend.model(bl));
    }

    // ── SVG plumbing ─────────────────────────────────────────────────────────────
    /** {@code pages/fonts.svg} → the document's fonts: per {@code <g>} the complete model —
     *  identity, weight/style, metrics, explicit cmap — and every glyph (empty {@code d} =
     *  inkless: advance and unicode still count). The single font representation since v2. */
    static void fonts(Element svg, OCDDocument doc) {
        // note: children() deliberately filters <defs> (page-content walk) — go by namespace here
        NodeList gs = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g");
        for (int gi = 0; gi < gs.getLength(); gi++) {
            Element g = (Element) gs.item(gi);
            if (!g.hasAttribute("data-font")) continue;
            OCDFont f = new OCDFont();
            String id = g.hasAttribute("data-id") ? g.getAttribute("data-id") : g.getAttribute("data-font");
            f.id(id);
            f.name(g.hasAttribute("data-name") ? g.getAttribute("data-name") : id);
            f.family(g.hasAttribute("data-family") ? g.getAttribute("data-family") : f.name());
            if (g.hasAttribute("data-weight")) f.weight(g.getAttribute("data-weight"));
            if (g.hasAttribute("data-style"))  f.style(g.getAttribute("data-style"));
            f.embedded(g.hasAttribute("data-embedded"));
            f.ascent(dbl(g.getAttribute("data-ascent"), .75)).descent(dbl(g.getAttribute("data-descent"), -.25));
            f.capHeight(dbl(g.getAttribute("data-capheight"), 0)).xHeight(dbl(g.getAttribute("data-xheight"), 0));
            f.spaceWidth(dbl(g.getAttribute("data-space"), 0));
            String cm = g.getAttribute("data-cmap");
            if (cm != null && !cm.isEmpty())
                for (String tok : cm.trim().split("\\s+")) {
                    int c = tok.indexOf(':');
                    if (c > 0) f.map(Integer.parseInt(tok.substring(0, c)), Integer.parseInt(tok.substring(c + 1)));
                }
            for (Element p : children(g)) {
                if (!"path".equals(p.getLocalName())) continue;
                String pid = p.getAttribute("id");
                int gid = Integer.parseInt(pid.substring(pid.lastIndexOf('-') + 1));
                String d = p.getAttribute("d");
                String gname = p.hasAttribute("data-glyphname") ? p.getAttribute("data-glyphname") : "";
                f.add(new OCDGlyph(gid, p.getAttribute("data-text"),
                        (d == null || d.isEmpty()) ? null : JxPath.ofSvg(d),
                        dbl(p.getAttribute("data-advance"), 0), gname));
            }
            doc.add(f);
        }
    }

    private static List<Element> children(Element el) {
        var out = new ArrayList<Element>();
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element ce && !"defs".equals(ce.getLocalName()) && !"style".equals(ce.getLocalName()))
                out.add(ce);
        return out;
    }

    private static Map<String, String> cssClasses(Element svg) {
        var map = new LinkedHashMap<String, String>();
        NodeList styles = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", "style");
        for (int i = 0; i < styles.getLength(); i++) {
            Matcher m = CSS_RULE.matcher(styles.item(i).getTextContent());
            while (m.find()) map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static Map<String, String> decls(Map<String, String> css, String cls) {
        var out = new LinkedHashMap<String, String>();
        if (cls == null) return out;
        for (String c : cls.trim().split("\\s+")) {
            String body = css.get(c);
            if (body == null) continue;
            for (String decl : body.split(";")) {
                int i = decl.indexOf(':');
                if (i > 0) out.put(decl.substring(0, i).trim(), decl.substring(i + 1).trim());
            }
        }
        return out;
    }

    private static Map<String, OCDGradient> gradients(Element svg) {
        var out = new HashMap<String, OCDGradient>();
        for (String kind : new String[]{ "linearGradient", "radialGradient" }) {
            NodeList list = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", kind);
            for (int i = 0; i < list.getLength(); i++) {
                Element g = (Element) list.item(i);
                boolean linear = kind.startsWith("linear");
                double[] coords = linear
                        ? new double[]{ dbl(g.getAttribute("x1"), 0), dbl(g.getAttribute("y1"), 0),
                                        dbl(g.getAttribute("x2"), 0), dbl(g.getAttribute("y2"), 0) }
                        : new double[]{ dbl(g.getAttribute("fx"), 0), dbl(g.getAttribute("fy"), 0), dbl(g.getAttribute("fr"), 0),
                                        dbl(g.getAttribute("cx"), 0), dbl(g.getAttribute("cy"), 0), dbl(g.getAttribute("r"), 0) };
                JxTransform t = g.hasAttribute("gradientTransform")
                        ? transformOf(g.getAttribute("gradientTransform")) : JxTransform.IDENTITY;
                var offs = new ArrayList<Float>(); var cols = new ArrayList<Integer>();
                for (Element st : children(g)) {
                    if (!"stop".equals(st.getLocalName())) continue;
                    offs.add((float) dbl(st.getAttribute("offset"), 0));
                    cols.add(argb(st.getAttribute("stop-color"), st.getAttribute("stop-opacity")));
                }
                float[] fo = new float[offs.size()]; int[] co = new int[cols.size()];
                for (int k = 0; k < fo.length; k++) { fo[k] = offs.get(k); co[k] = cols.get(k); }
                // PDF's Extend, start and end (§B3): data-extend="1 0", absent = both extended.
                String[] ext = g.getAttribute("data-extend").trim().split("\\s+");
                boolean e0 = ext.length != 2 || !"0".equals(ext[0]), e1 = ext.length != 2 || !"0".equals(ext[1]);
                out.put(g.getAttribute("id"),
                        new OCDGradient(linear ? OCDGradient.Kind.LINEAR : OCDGradient.Kind.RADIAL, coords, fo, co, t, e0, e1));
            }
        }
        return out;
    }

    /** Clip defs are stored in SVG space (the writer folds the page flip in, so a bare wrapper carries the
     *  clip whatever the wrapped node's matrix is); the model wants page space, so this is
     *  {@link OCDPage#fromSvg()} — exact, since {@link JxPath} is double-backed. The flip is an
     *  involution only when the media origin is 0, which this used to assume by re-applying the
     *  FORWARD flip: correct on every page whose box starts at (0,0), and off by twice the origin
     *  on any that does not. */
    private static Map<String, JxPath> clipPaths(Element svg, OCDPage page) {
        java.awt.geom.AffineTransform flip = page.fromSvg().awt();
        var out = new LinkedHashMap<String, JxPath>();
        NodeList list = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", "clipPath");
        for (int i = 0; i < list.getLength(); i++) {
            Element c = (Element) list.item(i);
            for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof Element pe && "path".equals(pe.getLocalName()))
                    out.put(c.getAttribute("id"),
                            new JxPath(flip.createTransformedShape(JxPath.ofSvg(pe.getAttribute("d")))));
        }
        return out;
    }

    // ── parsing helpers ──────────────────────────────────────────────────────────
    private static final Pattern CSS_RULE  = Pattern.compile("\\.([A-Za-z0-9_-]+)\\{([^}]*)}");
    private static final Pattern FN        = Pattern.compile("\\s*([A-Za-z]+)\\s*\\(([^)]*)\\)\\s*,?\\s*");

    private static String attr(Element el, String name) { return el.hasAttribute(name) ? el.getAttribute(name) : null; }
    /** Six numbers, or a message that says what was read instead. A container can be hand-edited or
     *  written by another tool, and a bare {@code NumberFormatException} from six frames down names neither
     *  the value nor the grammar it broke — the one thing a caller needs. */
    private static JxTransform mat6(String six) {
        String[] p = six == null ? new String[0] : six.trim().split("[\\s,]+");
        if (p.length < 6) throw new IllegalArgumentException("matrix() needs 6 numbers, read: \"" + six + '"');
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            try { v[i] = Double.parseDouble(p[i]); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("matrix() component " + (i + 1) + " is not a number: \""
                        + p[i] + "\" in \"" + six + '"');
            }
        }
        return JxTransform.fromMatrix6(v);   // PDF/SVG order, which is also the record's own
    }
    /** An SVG transform list, composed in written order: {@code matrix}, {@code translate}, {@code scale} and
     *  {@code rotate} — what the writers emit and what a viewer composes in front when it moves a node. Anything
     *  else is refused with the list that was read: a function skipped in silence is a node silently misplaced. */
    private static JxTransform transformOf(String s) {
        JxTransform t = JxTransform.IDENTITY;
        if (s == null || s.isBlank()) return t;
        Matcher m = FN.matcher(s);
        int at = 0;
        while (at < s.length() && m.find(at) && m.start() == at) {
            String[] a = m.group(2).trim().split("[\\s,]+");
            t = t.concat(switch (m.group(1)) {
                case "matrix"    -> mat6(m.group(2));
                case "translate" -> JxTransform.translate(num(a, 0, s), a.length > 1 ? num(a, 1, s) : 0);
                case "scale"     -> JxTransform.scale(num(a, 0, s), a.length > 1 ? num(a, 1, s) : num(a, 0, s));
                case "rotate"    -> {
                    JxTransform r = JxTransform.rotate(Math.toRadians(num(a, 0, s)));
                    if (a.length < 3) yield r;
                    double cx = num(a, 1, s), cy = num(a, 2, s);
                    yield JxTransform.translate(cx, cy).concat(r).concat(JxTransform.translate(-cx, -cy));
                }
                default -> throw new IllegalArgumentException("unsupported transform " + m.group(1) + "() in \"" + s + '"');
            });
            at = m.end();
        }
        if (at < s.length()) throw new IllegalArgumentException("malformed transform list: \"" + s + '"');
        return t;
    }
    private static double num(String[] a, int i, String list) {
        try { return Double.parseDouble(a[i]); }
        catch (RuntimeException e) { throw new IllegalArgumentException("not a number in transform \"" + list + '"'); }
    }
    static int argb(String color, String opacity) {
        JxColor c = JxColor.ofHex(color.trim());
        if (opacity != null && !opacity.isEmpty()) {
            int a = (int) Math.round(Double.parseDouble(opacity) * 255);
            return (a << 24) | (c.argb() & 0xFFFFFF);
        }
        return (c.argb() & 0xFF000000) == 0 ? (0xFF000000 | c.argb()) : c.argb();
    }
    /** The pen a paint class states — the inverse of {@code SvgPaint.strokeCss}. */
    private static JxStroke pen(Map<String, String> d) {
        double[] dash = null;
        String v = d.get("stroke-dasharray");
        if (v != null && !v.isEmpty()) dash = Arrays.stream(v.split("[,\\s]+")).mapToDouble(Double::parseDouble).toArray();
        return new JxStroke(num(d.get("stroke-width"), 0), JxStroke.Cap.of(d.get("stroke-linecap")),
                JxStroke.Join.of(d.get("stroke-linejoin")), num(d.get("stroke-miterlimit"), 10), dash, num(d.get("stroke-dashoffset"), 0));
    }
    private static double num(String v, double def) { return v == null || v.isEmpty() ? def : Double.parseDouble(v); }
    private static double dbl(String v, double def)  { try { return Double.parseDouble(v); } catch (Exception e) { return def; } }}
