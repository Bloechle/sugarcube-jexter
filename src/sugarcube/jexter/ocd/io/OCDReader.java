package sugarcube.jexter.ocd.io;

import sugarcube.jexter.core.JxXml;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxName;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxRect;
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
import sugarcube.jexter.ocd.model.OCDStruct;
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

/**
 * Reads an <b>OCD-EPUB</b> back into the {@link OCDDocument} model — the inverse of
 * {@code OcdEpubWriter}/{@code SvgOcdWriter}. One loader, three reaches:
 *
 * <ul>
 *   <li>{@link #readMembers(File)} — Tier 0: document skeleton (meta, outline, structures, fonts),
 *       no pages;</li>
 *   <li>{@link #readPage(File, int)} — Tier 1: the pocket model — one page parsed (SVG-OCD v2
 *       grammar, self-contained) on top of the skeleton;</li>
 *   <li>{@link #read(File)} — Tier 2: the full model ({@code readPage} over all pages).</li>
 * </ul>
 *
 * <p>Files without {@code OEBPS/jexter/} members are <b>refused</b> — a foreign EPUB is a book,
 * not a model; there is no SVG back-parsing of arbitrary files. Fonts (outline + metrics + cmap)
 * come from {@code pages/f.svg} — the single font representation; the pages reference its glyphs
 * externally. Paint attributes are recovered from the page CSS classes; the text stream
 * (unicode, blanks, breaks) from the run's {@code data-u}/{@code data-cl}/{@code data-b}; reading
 * order from {@code data-o}; lines from {@code <g data-ocd="l">}; links from the native anchors;
 * page boxes from the root attributes. PDF annotations/fields come from {@code jexter/annots.json}.
 *
 * <p>Known normalizations (by design): {@code z} is rebuilt from document order (order is the
 * semantics; the float values are not serialized) and node {@code tr} on paths is identity
 * (geometry is baked in page space, exactly as the audited writers paint it).
 */
public final class OCDReader {

    private OCDReader() {}

    static final String OPF = "OEBPS/";
    static final String JX  = OPF + "jexter/";

    // ── Tier 2: full model ──────────────────────────────────────────────────────
    public static OCDDocument read(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            OCDDocument doc = skeleton(zf);
            Map<String, Object> annots = pagesOf(json(zf, JX + "annots.json"));
            int i = 0;
            for (ZipEntry pe : pageFiles(zf).values()) doc.add(parsePage(zf, doc, pe, ++i, annots));
            restoreImageDims(doc);
            resolve(doc);
            return doc;
        }
    }

    // ── Tier 0: members only (no pages) ─────────────────────────────────────────
    public static OCDDocument readMembers(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) { return skeleton(zf); }
    }

    // ── Tier 1: the pocket model — skeleton + one page (0-based) ────────────────
    public static OCDDocument readPage(File file, int index) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            OCDDocument doc = skeleton(zf);
            Map<String, Object> annots = pagesOf(json(zf, JX + "annots.json"));
            TreeMap<String, ZipEntry> pages = pageFiles(zf);
            int i = 0;
            for (ZipEntry pe : pages.values()) {
                if (i == index) { doc.add(parsePage(zf, doc, pe, i + 1, annots)); restoreImageDims(doc); return doc; }
                i++;
            }
            throw new IllegalArgumentException("page " + index + " out of range (0.." + (pages.size() - 1) + ")");
        }
    }

    /** Number of pages without parsing any (page-document count). */
    public static int pageCount(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) { return pageFiles(zf).size(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pagesOf(Map<String, Object> annots) {
        return annots == null ? null : obj(annots, "pages");
    }

    // ── skeleton: meta + outline + structures + fonts + binaries ────────────────
    @SuppressWarnings("unchecked")
    private static OCDDocument skeleton(ZipFile zf) throws Exception {
        Map<String, Object> meta = json(zf, JX + "meta.json");
        if (meta == null)
            throw new IOException("not an OCD-EPUB: no " + JX + "meta.json member (a foreign EPUB is a book, not a model)");

        OCDDocument doc = new OCDDocument();
        readMeta(meta, doc);

        Map<String, Object> nav = json(zf, JX + "outline.json");
        if (nav != null) for (Object o : arr(nav, "bookmarks")) doc.addOutline(outline((Map<String, Object>) o));

        Map<String, Object> structures = json(zf, JX + "structures.json");
        if (structures != null) {
            for (Object so : arr(structures, "structures")) {
                Map<String, Object> m = (Map<String, Object>) so;
                OCDStruct root = new OCDStruct(OCDStruct.Type.DOCUMENT);
                Object rootO = m.get("root");
                if (rootO instanceof Map<?, ?> rm)
                    for (Object o : arr((Map<String, Object>) rm, "children")) root.add(struct((Map<String, Object>) o));
                Object atO = m.get("at"); long at = atO instanceof Number nn ? nn.longValue() : 0;
                doc.addStructure(new OCDStructure(s(m, "id"), s(m, "label"), OCDStructure.sourceOf(s(m, "source")))
                        .by(s(m, "by")).at(at).how(s(m, "how")).purpose(s(m, "purpose")).root(root));
            }
            if (has(structures, "default")) doc.defaultStructureId(s(structures, "default"));
        }

        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.equals(OPF + "images/cover.jpg") || name.equals(OPF + "images/cover.png"))
                doc.cover(zf.getInputStream(e).readAllBytes());        // resource: reused verbatim on re-export
            else if (name.startsWith(OPF + "images/"))
                doc.addImage(name.substring((OPF + "images/").length()), zf.getInputStream(e).readAllBytes());
            else if (name.startsWith(OPF + "media/"))
                doc.addMedia(name.substring((OPF + "media/").length()), zf.getInputStream(e).readAllBytes());
        }
        ZipEntry gl = zf.getEntry(OPF + "pages/f.svg");
        if (gl != null) fonts(zf, gl, doc);
        return doc;
    }

    private static TreeMap<String, ZipEntry> pageFiles(ZipFile zf) {
        TreeMap<String, ZipEntry> pages = new TreeMap<>();
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName();
            if (!e.isDirectory() && n.startsWith(OPF + "pages/page-") && n.endsWith(".xhtml"))
                pages.put(n, e);
        }
        return pages;
    }

    // ── page: SVG-OCD v2 — the page is the single source (text, order, lines, links, boxes) ──
    private static OCDPage parsePage(ZipFile zf, OCDDocument doc, ZipEntry pageEntry, int number,
                                     Map<String, Object> annots) throws Exception {
        Element svg = svgRoot(zf, pageEntry);

        JxRect media = battr(svg, "data-media");
        if (media == null) media = new JxRect(0, 0, dbl(svg.getAttribute("width"), 0), dbl(svg.getAttribute("height"), 0));
        OCDPage page = new OCDPage("p" + number, media);
        if (svg.hasAttribute("data-rot")) page.rotation((int) dbl(svg.getAttribute("data-rot"), 0));
        if (svg.hasAttribute("data-dpi")) page.dpi(dbl(svg.getAttribute("data-dpi"), 72));
        JxRect r;
        if ((r = battr(svg, "data-crop"))  != null) page.cropBox(r);
        if ((r = battr(svg, "data-bleed")) != null) page.bleedBox(r);
        if ((r = battr(svg, "data-trim"))  != null) page.trimBox(r);
        if ((r = battr(svg, "data-art"))   != null) page.artBox(r);

        Map<String, String> css = cssClasses(svg);
        Map<String, OCDGradient> grads = gradients(svg);
        Map<String, JxPath> clipDefs = clipPaths(svg, page);
        Map<String, OCDFont> bySafe = new HashMap<>();
        for (OCDFont f : doc.fonts().values()) bySafe.put(JxName.safe(f.id()), f);

        var parsed = new LinkedHashMap<String, OCDNode>();     // id -> node, in paint (document) order
        var ord = new HashMap<OCDNode, Integer>();             // node -> data-o (reading index)
        float[] z = { 0f };
        Element body = firstBelowRotation(svg, page);
        for (Element el : children(body)) {
            if ("links".equals(el.getAttribute("data-ocd"))) { links(el, page); continue; }
            walk(el, page, css, grads, clipDefs, bySafe, parsed, z, null, null, ord);
        }

        // reading order: paint order re-sorted by data-o (content index), exact reconstruction —
        // nodes without data-o were emitted at their content position already
        for (OCDNode n : parsed.values())
            if (topLevel(n, parsed)) page.add(n);
        reorder(page.content(), ord);
        for (OCDNode n : parsed.values())
            if (n instanceof OCDGroup g && !(g instanceof OCDParagraph)) reorder(g.children(), ord);

        if (annots != null) {
            Map<String, Object> pe = obj(annots, page.id());
            if (pe != null) applyAnnots(pe, page);
        }
        for (var e : clipDefs.entrySet()) page.addClip(new OCDClip(e.getKey(), e.getValue()));
        return page;
    }

    /** Stable re-sort by reading index: {@code data-o} where present, current position otherwise. */
    private static void reorder(List<OCDNode> children, Map<OCDNode, Integer> ord) {
        if (children.size() < 2) return;
        var pos = new HashMap<OCDNode, Integer>();
        for (int i = 0; i < children.size(); i++) pos.put(children.get(i), i);
        children.sort(java.util.Comparator.comparingInt(n -> ord.getOrDefault(n, pos.get(n))));
    }

    /** Native page anchors → {@link OCDLink}s (the page flip is self-inverse: apply it back). */
    private static void links(Element g, OCDPage page) {
        JxRect b = page.effectiveBox();
        JxTransform flip = new JxTransform(1, 0, 0, -1, b.x(), b.y() + b.height());
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
            if (href != null && href.startsWith("page-") && href.endsWith(".xhtml")) {
                lk.pageIndex((int) dbl(href.substring(5, href.length() - 6), 1) - 1);
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

    private static boolean topLevel(OCDNode n, Map<String, OCDNode> parsed) {
        for (OCDNode p : parsed.values())
            if (p instanceof OCDGroup g && g.children().contains(n)) return false;
        return true;
    }

    // ── SVG-OCD walk ─────────────────────────────────────────────────────────────
    private static void walk(Element el, OCDPage page, Map<String, String> css, Map<String, OCDGradient> grads,
                             Map<String, JxPath> clipDefs, Map<String, OCDFont> bySafe,
                             Map<String, OCDNode> out, float[] z, OCDGroup parent, String wrapClip,
                             Map<OCDNode, Integer> ord) {
        String tag = el.getLocalName();
        String ocd = el.getAttribute("data-ocd");
        switch (tag) {
            case "g" -> {
                switch (ocd) {
                    case "p" -> {
                        OCDParagraph g = new OCDParagraph();
                        g.id(el.getAttribute("id"));
                        if (el.hasAttribute("data-flow")) g.flow((int) dbl(el.getAttribute("data-flow"), -1));
                        if (el.hasAttribute("data-tr")) g.transform(mat6(el.getAttribute("data-tr")));
                        state(el, g);
                        g.z(z[0] += 1f);
                        register(out, g, parent, wrapClip);
                        oAttr(el, g, ord);
                        // <g data-ocd="l"> line groups: containment only — an OCDBreak between lines
                        boolean anyLine = false;
                        for (Element c : children(el)) {
                            if ("g".equals(c.getLocalName()) && "l".equals(c.getAttribute("data-ocd"))) {
                                if (anyLine) { OCDBreak br = new OCDBreak(); br.z(z[0] += 1f); g.add(br); }
                                anyLine = true;
                                for (Element lc : children(c))
                                    walk(lc, page, css, grads, clipDefs, bySafe, out, z, g, null, ord);
                            } else walk(c, page, css, grads, clipDefs, bySafe, out, z, g, null, ord);
                        }
                    }
                    case "gr", "g", "layer" -> {
                        OCDGroup g = switch (ocd) {
                            case "gr"    -> new OCDGraphic();
                            case "layer" -> new OCDLayerContent(attr(el, "data-ref"));
                            default      -> new OCDGroup();
                        };
                        g.id(el.getAttribute("id"));
                        if (el.hasAttribute("data-tr")) g.transform(mat6(el.getAttribute("data-tr")));
                        state(el, g);
                        g.z(z[0] += 1f);
                        register(out, g, parent, wrapClip);
                        oAttr(el, g, ord);
                        for (Element c : children(el)) walk(c, page, css, grads, clipDefs, bySafe, out, z, g, null, ord);
                    }
                    case "links" -> { }                        // parsed at page level, never content
                    case "t" -> {
                        OCDText t = text(el, page, css, bySafe);
                        t.z(z[0] += 1f);
                        register(out, t, parent, wrapClip);
                        oAttr(el, t, ord);
                    }
                    case "media" -> {
                        String ref = attr(el, "data-ref");
                        OCDMedia mm = "audio".equals(el.getAttribute("data-kind")) ? new OCDAudio(ref) : new OCDVideo(ref);
                        if (mm instanceof OCDVideo v && el.hasAttribute("data-poster")) v.poster(el.getAttribute("data-poster"));
                        if (el.hasAttribute("data-tr")) mm.transform(mat6(el.getAttribute("data-tr")));
                        if ("0".equals(attr(el, "data-controls"))) mm.controls(false);
                        if ("1".equals(attr(el, "data-autoplay"))) mm.autoplay(true);
                        if ("1".equals(attr(el, "data-loop")))     mm.loop(true);
                        if ("1".equals(attr(el, "data-muted")))    mm.muted(true);
                        mm.id(el.getAttribute("id"));
                        state(el, mm);
                        mm.z(z[0] += 1f);
                        register(out, mm, parent, wrapClip);
                        oAttr(el, mm, ord);
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
                            walk(c, page, css, grads, clipDefs, bySafe, out, z, parent, wc, ord);
                    }
                }
            }
            case "path" -> {
                if (!el.hasAttribute("id")) return;            // glyph defs live in <defs>; content paths carry ids
                OCDPath p = path(el, css, grads, clipDefs);
                p.z(z[0] += 1f);
                register(out, p, parent, wrapClip);
                oAttr(el, p, ord);
            }
            case "image" -> {
                OCDImage im = image(el, page);
                if (im == null) return;                         // poster copies carry no id
                im.z(z[0] += 1f);
                register(out, im, parent, wrapClip);
                oAttr(el, im, ord);
            }
            default -> { }
        }
    }

    private static void oAttr(Element el, OCDNode n, Map<OCDNode, Integer> ord) {
        if (el.hasAttribute("data-o")) ord.put(n, (int) dbl(el.getAttribute("data-o"), 0));
    }

    private static void register(Map<String, OCDNode> out, OCDNode n, OCDGroup parent) {
        if (n.id() != null && !n.id().isEmpty()) out.put(n.id(), n);
        if (parent != null) parent.add(n);
    }

    /** A node read from inside a clip wrapper takes the wrapper's clip — the wrapper itself is spliced,
     *  it is a paint carrier, not a model node. */
    private static void register(Map<String, OCDNode> out, OCDNode n, OCDGroup parent, String wrapClip) {
        if (wrapClip != null && !n.hasClip()) n.clipId(wrapClip);
        register(out, n, parent);
    }

    private static OCDText text(Element el, OCDPage page, Map<String, String> css, Map<String, OCDFont> bySafe) {
        OCDFont font = bySafe.get(el.getAttribute("data-f"));
        // The font size is STATED (data-fs) — it is also folded into the run matrix so the em-normalized
        // glyphs place, but the matrix alone cannot give it back: the determinant conflates it with an
        // anisotropic run matrix (Tz, a squeezed CTM). §B4.
        JxTransform T = mat6(inParens(el.getAttribute("transform")));
        double fs = dbl(el.getAttribute("data-fs"), 0);
        OCDText t = new OCDText(font != null ? font.id() : el.getAttribute("data-f"), fs);
        t.id(el.getAttribute("id"));
        if (el.hasAttribute("data-rm")) t.renderMode((int) dbl(el.getAttribute("data-rm"), 0));
        state(el, t);

        // tr = flipInv ∘ T ∘ scale(1/fs)  (T = pageFlip ∘ tr ∘ scale(fs) was written)
        double k = fs > 0 ? fs : 1;
        JxTransform T0 = T.concat(JxTransform.scale(1 / k));
        JxRect b = page.effectiveBox();
        JxTransform flipInv = new JxTransform(1, 0, 0, -1, b.x(), b.y() + b.height());
        t.transform(flipInv.concat(T0));

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
            t.strokePaint(argb(stroke, d.get("stroke-opacity")), num(d.get("stroke-width"), 0) * k);
            t.lineStyle(cap(d), join(d), num(d.get("stroke-miterlimit"), 10),
                    dash(d.get("stroke-dasharray"), k), num(d.get("stroke-dashoffset"), 0) * k);
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
        // sentinels (data-b: "at:xem" | "at:gid:xem") + full-stream unicode (data-u) + clusters
        var blanks = new java.util.HashMap<Integer, double[]>(); // at -> {gid, x}
        String db = el.getAttribute("data-b");
        if (db != null && !db.isEmpty())
            for (String tok : db.trim().split("\\s+")) {
                String[] pr = tok.split(":");
                if (pr.length == 2)      blanks.put(Integer.parseInt(pr[0]), new double[]{ -1, Double.parseDouble(pr[1]) * k });
                else if (pr.length == 3) blanks.put(Integer.parseInt(pr[0]), new double[]{ Double.parseDouble(pr[1]), Double.parseDouble(pr[2]) * k });
            }
        String u = el.getAttribute("data-u");
        if (u == null) u = "";
        int total = painted.size() + blanks.size();
        int[] cl = new int[total];
        String dcl = el.getAttribute("data-cl");
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

    private static OCDPath path(Element el, Map<String, String> css, Map<String, OCDGradient> grads,
                                Map<String, JxPath> clipDefs) {
        Map<String, String> d = decls(css, el.getAttribute("class"));
        JxPath geo = JxPath.ofSvg(el.getAttribute("d"));
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
            p.stroke(argb(stroke, d.get("stroke-opacity")), num(d.get("stroke-width"), 0));
            p.lineStyle(cap(d), join(d), num(d.get("stroke-miterlimit"), 10),
                    dash(d.get("stroke-dasharray"), 1), num(d.get("stroke-dashoffset"), 0));
        }
        blend(d, p);
        clipRef(el, p);
        return p;
    }

    private static OCDImage image(Element el, OCDPage page) {
        String id = el.getAttribute("id");
        if (id == null || id.isEmpty()) return null;
        String href = el.getAttributeNS("http://www.w3.org/1999/xlink", "href");
        if (href == null || href.isEmpty()) href = el.getAttribute("href");
        OCDImage im = new OCDImage(href.substring(href.lastIndexOf('/') + 1));
        im.id(id);

        // transform = "matrix(pageFlip) matrix(t) translate(0 1) scale(1 -1)" — ALWAYS. An image states its
        // own placement whether it is clipped or not (§B3: a clip rides on a wrapper whose two nested flips
        // cancel), so the leading page flip is always the one to strip.
        List<JxTransform> fns = transformList(el.getAttribute("transform"));
        JxTransform P = JxTransform.IDENTITY;
        for (JxTransform f : fns) P = P.concat(f);
        JxRect fb = page.effectiveBox();
        P = new JxTransform(1, 0, 0, -1, fb.x(), fb.y() + fb.height()).concat(P);   // strip the page flip
        JxTransform TS = new JxTransform(1, 0, 0, -1, 0, 1);   // translate(0,1)·scale(1,-1), self-inverse
        im.transform(P.concat(TS));

        if (el.hasAttribute("opacity")) im.alpha((float) dbl(el.getAttribute("opacity"), 1));
        String style = el.getAttribute("style");
        if (style != null && style.contains("mix-blend-mode:"))
            im.blend(blendName(style.substring(style.indexOf("mix-blend-mode:") + 15).replace(";", "").trim()));
        clipRef(el, im);
        return im;
    }

    /** {@code clip-path="url(#cN)"} borne by the node itself → its clip id. jexter writes the clip on a
     *  wrapper instead (§B3), so this is tolerance for hand-authored SVG, not the grammar's own form. */
    private static void clipRef(Element el, OCDNode n) {
        String cp = el.getAttribute("clip-path");
        if (cp != null && cp.startsWith("url(#")) n.clipId(cp.substring(5, cp.length() - 1));
    }

    private static void state(Element el, OCDNode n) {
        if (el.hasAttribute("data-name"))  n.name(el.getAttribute("data-name"));
        if (el.hasAttribute("data-role"))  n.role(el.getAttribute("data-role"));
        if (el.hasAttribute("data-blend")) n.blend(el.getAttribute("data-blend"));
        if (el.hasAttribute("data-alpha")) n.alpha((float) dbl(el.getAttribute("data-alpha"), 1));
    }

    private static void blend(Map<String, String> d, OCDNode n) {
        String bl = d.get("mix-blend-mode");
        if (bl != null) n.blend(blendName(bl));
    }

    private static String blendName(String cssName) {
        var sb = new StringBuilder(); boolean up = true;
        for (char c : cssName.trim().toCharArray()) {
            if (c == '-') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c); up = false;
        }
        return sb.toString();
    }

    // ── SVG plumbing ─────────────────────────────────────────────────────────────
    /** {@code pages/f.svg} → the document's fonts: per {@code <g>} the complete model —
     *  identity, weight/style, metrics, explicit cmap — and every glyph (empty {@code d} =
     *  inkless: advance and unicode still count). The single font representation since v2. */
    private static void fonts(ZipFile zf, ZipEntry entry, OCDDocument doc) throws Exception {
        // note: children() deliberately filters <defs> (page-content walk) — go by namespace here
        Element svg = svgRoot(zf, entry);
        NodeList gs = svg.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g");
        for (int gi = 0; gi < gs.getLength(); gi++) {
            Element g = (Element) gs.item(gi);
            if (!g.hasAttribute("data-f")) continue;
            OCDFont f = new OCDFont();
            String id = g.hasAttribute("data-id") ? g.getAttribute("data-id") : g.getAttribute("data-f");
            f.id(id);
            f.name(g.hasAttribute("data-name") ? g.getAttribute("data-name") : id);
            f.family(g.hasAttribute("data-family") ? g.getAttribute("data-family") : f.name());
            if (g.hasAttribute("data-weight")) f.weight(g.getAttribute("data-weight"));
            if (g.hasAttribute("data-style"))  f.style(g.getAttribute("data-style"));
            f.embedded(g.hasAttribute("data-embedded"));
            f.ascent(dbl(g.getAttribute("data-asc"), .75)).descent(dbl(g.getAttribute("data-desc"), .25));
            f.capHeight(dbl(g.getAttribute("data-cap"), 0)).xHeight(dbl(g.getAttribute("data-x"), 0));
            f.spaceWidth(dbl(g.getAttribute("data-sp"), 0));
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
                String gname = p.hasAttribute("data-gname") ? p.getAttribute("data-gname") : "";
                f.add(new OCDGlyph(gid, p.getAttribute("data-u"),
                        (d == null || d.isEmpty()) ? null : JxPath.ofSvg(d),
                        dbl(p.getAttribute("data-adv"), 0), gname));
            }
            doc.add(f);
        }
    }

    private static Element svgRoot(ZipFile zf, ZipEntry e) throws Exception {
        Document xd = JxXml.secureBuilder().parse(zf.getInputStream(e));   // untrusted zip → hardened parse
        NodeList svgs = xd.getElementsByTagNameNS("http://www.w3.org/2000/svg", "svg");
        if (svgs.getLength() == 0) throw new IOException("no <svg> in " + e.getName());
        return (Element) svgs.item(0);
    }

    /** Every reference in the model must point at something that exists. A container can arrive with a
     *  member missing, a clip id renamed, a font never written — hand-edited, truncated, produced by another
     *  tool — and a dangling reference is worse than none: it survives into the model, resolves to nothing at
     *  paint time and silently drops a clip or blanks a page's text, with every gate reporting green.
     *
     *  <p>So a reference that does not resolve is DROPPED and reported, never carried: the model handed back
     *  is always self-consistent, and the loss is on the log rather than in the rendering. Reading stays
     *  liberal — one broken clip must not cost the document — but never silent. */
    private static void resolve(OCDDocument doc) {
        int clips = 0, fonts = 0, refs = 0;
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            OCDPage page = doc.page(pi);
            for (OCDNode n : page.nodes().toList()) {
                if (n.hasClip() && page.clip(n.clipId()) == null) { n.clipId(null); clips++; }
                if (n instanceof OCDText t && doc.findFont(t.fontId()) == null) fonts++;
            }
        }
        for (OCDStructure st : doc.structures()) refs += prune(doc, st.root());
        if (clips > 0) JxLog.warn(OCDReader.class, clips + " clip reference(s) named a clip the page does not define — dropped");
        if (fonts > 0) JxLog.warn(OCDReader.class, fonts + " run(s) name a font the container does not carry — glyphs will not paint");
        if (refs  > 0) JxLog.warn(OCDReader.class, refs  + " structure reference(s) named a node that does not exist — dropped");
    }

    /** Drop the structure refs that name no node, depth-first; returns how many were dropped. */
    private static int prune(OCDDocument doc, OCDStruct s) {
        int n = 0;
        for (var it = s.refs().iterator(); it.hasNext(); ) {
            OCDStruct.Ref r = it.next();
            OCDPage p = r.page() >= 0 && r.page() < doc.pageCount() ? doc.page(r.page()) : null;
            boolean ok = p != null && p.nodes().anyMatch(x -> r.nodeId().equals(x.id()));
            if (!ok) { it.remove(); n++; }
        }
        for (OCDStruct k : s.children()) n += prune(doc, k);
        return n;
    }

    /** Content root: past the rotation wrapper when /Rotate ≠ 0, matched by NAME (data-ocd="rot"). */
    private static Element firstBelowRotation(Element svg, OCDPage page) {
        if (page.rotation() == 0) return svg;
        for (Element c : children(svg))
            if ("g".equals(c.getLocalName()) && "rot".equals(c.getAttribute("data-ocd"))) return c;
        return svg;
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
                        : new double[]{ dbl(g.getAttribute("fx"), 0), dbl(g.getAttribute("fy"), 0), 0,
                                        dbl(g.getAttribute("cx"), 0), dbl(g.getAttribute("cy"), 0), dbl(g.getAttribute("r"), 0) };
                JxTransform t = g.hasAttribute("gradientTransform")
                        ? mat6(inParens(g.getAttribute("gradientTransform"))) : JxTransform.IDENTITY;
                var offs = new ArrayList<Float>(); var cols = new ArrayList<Integer>();
                for (Element st : children(g)) {
                    if (!"stop".equals(st.getLocalName())) continue;
                    offs.add((float) dbl(st.getAttribute("offset"), 0));
                    cols.add(argb(st.getAttribute("stop-color"), st.getAttribute("stop-opacity")));
                }
                float[] fo = new float[offs.size()]; int[] co = new int[cols.size()];
                for (int k = 0; k < fo.length; k++) { fo[k] = offs.get(k); co[k] = cols.get(k); }
                // spreadMethod="pad" (also the SVG default when absent) is the projection of PDF
                // Extend [true true] — the writer emits it for extended axes; anything else means
                // the source axis was not extended.
                boolean pad = !g.hasAttribute("spreadMethod") || "pad".equals(g.getAttribute("spreadMethod"));
                out.put(g.getAttribute("id"),
                        new OCDGradient(linear ? OCDGradient.Kind.LINEAR : OCDGradient.Kind.RADIAL, coords, fo, co, t, pad, pad));
            }
        }
        return out;
    }

    /** Clip defs are stored in SVG space (the writer folds the page flip in, so a bare wrapper carries the
     *  clip whatever the wrapped node's matrix is); the model wants page space. The flip is self-inverse, so
     *  applying it again is the unfold — exact, since {@link JxPath} is double-backed. */
    private static Map<String, JxPath> clipPaths(Element svg, OCDPage page) {
        JxRect b = page.effectiveBox();
        java.awt.geom.AffineTransform flip = new JxTransform(1, 0, 0, -1, -b.x(), b.y() + b.height()).awt();
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

    // ── meta / outline / struct (the shared JSON vocabulary) ────────────────────
    @SuppressWarnings("unchecked")
    private static void readMeta(Map<String, Object> m, OCDDocument doc) {
        if (has(m, "id")) doc.id(s(m, "id"));
        Map<String, Object> an = obj(m, "analysis");
        if (an != null) {
            doc.textSegmented(Boolean.TRUE.equals(an.get("textSegmented")));
            doc.headingsDetected(Boolean.TRUE.equals(an.get("headingsDetected")));
        }
        for (Object o : arr(m, "layers")) {
            Map<String, Object> l = (Map<String, Object>) o;
            doc.add(new OCDLayer(s(l, "id"), s(l, "name")).visible(!Boolean.FALSE.equals(l.get("visible"))).order(ii(l, "order", 0)));
        }
        OCDMeta md = doc.meta();
        if (has(m, "title"))    md.title(s(m, "title"));
        for (Object a : arr(m, "authors"))  md.addAuthor(a.toString());
        if (has(m, "subject"))  md.subject(s(m, "subject"));
        for (Object k : arr(m, "keywords")) md.addKeyword(k.toString());
        if (has(m, "creator"))  md.creator(s(m, "creator"));
        if (has(m, "producer")) md.producer(s(m, "producer"));
        if (has(m, "language")) md.language(s(m, "language"));
        if (has(m, "created"))  md.created(s(m, "created"));
        if (has(m, "modified")) md.modified(s(m, "modified"));
        Map<String, Object> custom = obj(m, "custom");
        if (custom != null) for (Map.Entry<String, Object> e : custom.entrySet()) md.custom(e.getKey(), e.getValue().toString());
    }

    @SuppressWarnings("unchecked")
    private static OCDOutline outline(Map<String, Object> m) {
        OCDOutline o = new OCDOutline(s(m, "title"));
        if (has(m, "page")) o.pageIndex(OCDVocab.pageIndex(s(m, "page")));
        if (has(m, "y"))    o.y(d(m, "y", Double.NaN));
        for (Object c : arr(m, "children")) o.add(outline((Map<String, Object>) c));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static OCDStruct struct(Map<String, Object> m) {
        OCDStruct s = new OCDStruct(OCDStruct.Type.valueOf(s(m, "type").toUpperCase(Locale.US)));
        if (has(m, "level"))   s.level(ii(m, "level", 0));
        if (has(m, "colspan")) s.colSpan(ii(m, "colspan", 1));
        if (has(m, "rowspan")) s.rowSpan(ii(m, "rowspan", 1));
        if (has(m, "ordered")) s.ordered(b(m, "ordered"));
        if (has(m, "header"))  s.header(switch (s(m, "header")) {
            case "col", "column" -> OCDStruct.HeaderKind.COLUMN;
            case "row"           -> OCDStruct.HeaderKind.ROW;
            case "both"          -> OCDStruct.HeaderKind.BOTH;
            default              -> OCDStruct.HeaderKind.NONE;
        });
        if (has(m, "text")) s.text(s(m, "text"));
        if (has(m, "lang")) s.lang(s(m, "lang"));
        if (has(m, "alt"))  s.alt(s(m, "alt"));
        for (Object r : arr(m, "refs")) { Map<String, Object> rm = (Map<String, Object>) r; s.addRef(OCDVocab.pageIndex(s(rm, "page")), s(rm, "node")); }
        for (Object c : arr(m, "children")) s.add(struct((Map<String, Object>) c));
        return s;
    }

    // ── annotations (the shared page-member vocabulary) ─────────────────────────
    @SuppressWarnings("unchecked")
    private static void applyAnnots(Map<String, Object> annotations, OCDPage page) {
        for (Object o : arr(annotations, "annots")) {
            Map<String, Object> ae = (Map<String, Object>) o;
            OCDAnnotation a = new OCDAnnotation(markupOf(s(ae, "type")));
            if (has(ae, "rect"))  a.rect(rect(ae.get("rect")));
            if (has(ae, "color")) a.color(new JxColor(argb(s(ae, "color"), null)));
            a.author(s(ae, "author")).modified(s(ae, "modified"));
            for (Object q : arr(ae, "quads")) a.addQuad(rect(q));
            if (has(ae, "contents")) a.contents(s(ae, "contents"));
            page.addAnnotation(a);
        }
        for (Object o : arr(annotations, "fields")) {
            Map<String, Object> fe = (Map<String, Object>) o;
            OCDFormField ff = new OCDFormField(fieldOf(s(fe, "type")))
                    .name(s(fe, "name")).onState(s(fe, "on"))
                    .readOnly(b(fe, "readonly")).required(b(fe, "required")).multiline(b(fe, "multiline"));
            if (has(fe, "rect")) ff.rect(rect(fe.get("rect")));
            for (Object op : arr(fe, "options")) ff.addOption(op.toString());
            if (has(fe, "value"))   ff.value(s(fe, "value"));
            if (has(fe, "default")) ff.defaultValue(s(fe, "default"));
            page.addField(ff);
        }
    }

    // ── image intrinsic size (recovered from bytes) ──────────────────────────────
    private static void restoreImageDims(OCDDocument doc) {
        for (OCDPage p : doc.pages()) fillImageDims(p.content(), doc);
    }
    private static void fillImageDims(List<OCDNode> nodes, OCDDocument doc) {
        for (OCDNode n : nodes) {
            if (n instanceof OCDImage im && im.pixelWidth() == 0) {
                int[] wh = imageDims(doc.image(im.resourceRef()));
                if (wh != null) im.pixelSize(wh[0], wh[1]);
            } else if (n instanceof OCDGroup g) {
                fillImageDims(g.children(), doc);
            }
        }
    }
    private static int[] imageDims(byte[] bytes) {
        if (bytes == null) return null;
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                ImageReader r = readers.next();
                try { r.setInput(iis); return new int[]{ r.getWidth(0), r.getHeight(0) }; }
                finally { r.dispose(); }
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ── parsing helpers ──────────────────────────────────────────────────────────
    private static final Pattern CSS_RULE  = Pattern.compile("\\.([A-Za-z0-9_-]+)\\{([^}]*)}");
    private static final Pattern FN        = Pattern.compile("(matrix|translate|scale)\\(([^)]*)\\)");

    private static String attr(Element el, String name) { return el.hasAttribute(name) ? el.getAttribute(name) : null; }
    private static String inParens(String s) {
        int a = s.indexOf('('), b = s.lastIndexOf(')');
        return a >= 0 && b > a ? s.substring(a + 1, b) : s;
    }
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
        return new JxTransform(v[0], v[1], v[2], v[3], v[4], v[5]);
    }
    /** Every transform function in the list, each as a matrix, in written order. */
    private static List<JxTransform> transformList(String s) {
        var out = new ArrayList<JxTransform>();
        Matcher m = FN.matcher(s == null ? "" : s);
        while (m.find()) {
            String[] a = m.group(2).trim().split("[\\s,]+");
            switch (m.group(1)) {
                case "matrix"    -> out.add(mat6(m.group(2)));
                case "translate" -> out.add(JxTransform.translate(Double.parseDouble(a[0]), a.length > 1 ? Double.parseDouble(a[1]) : 0));
                case "scale"     -> out.add(JxTransform.scale(Double.parseDouble(a[0]), a.length > 1 ? Double.parseDouble(a[1]) : Double.parseDouble(a[0])));
            }
        }
        return out;
    }
    private static int argb(String color, String opacity) {
        JxColor c = JxColor.ofHex(color.trim());
        if (opacity != null && !opacity.isEmpty()) {
            int a = (int) Math.round(Double.parseDouble(opacity) * 255);
            return (a << 24) | (c.argb() & 0xFFFFFF);
        }
        return (c.argb() & 0xFF000000) == 0 ? (0xFF000000 | c.argb()) : c.argb();
    }
    private static int cap(Map<String, String> d) {
        return switch (d.getOrDefault("stroke-linecap", "butt")) { case "round" -> 1; case "square" -> 2; default -> 0; };
    }
    private static int join(Map<String, String> d) {
        return switch (d.getOrDefault("stroke-linejoin", "miter")) { case "round" -> 1; case "bevel" -> 2; default -> 0; };
    }
    private static double[] dash(String v, double k) {
        if (v == null || v.isEmpty()) return null;
        String[] p = v.split("[,\\s]+");
        double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Double.parseDouble(p[i]) * k;
        return out;
    }
    private static double num(String v, double def) { return v == null || v.isEmpty() ? def : Double.parseDouble(v); }
    private static double dbl(String v, double def)  { try { return Double.parseDouble(v); } catch (Exception e) { return def; } }

    private static OCDAnnotation.Markup markupOf(String s) {
        try { return OCDAnnotation.Markup.valueOf(s.toUpperCase(Locale.US)); } catch (Exception e) { return OCDAnnotation.Markup.OTHER; }
    }
    private static OCDFormField.Field fieldOf(String s) {
        try { return OCDFormField.Field.valueOf(s.toUpperCase(Locale.US)); } catch (Exception e) { return OCDFormField.Field.OTHER; }
    }

    private static Map<String, Object> json(ZipFile zf, String name) throws Exception {
        ZipEntry e = zf.getEntry(name);
        if (e == null) return null;
        return JxJson.asObj(JxJson.parse(new String(zf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)));
    }
    private static boolean has(Map<String, Object> m, String k) { return m.get(k) != null; }
    private static String  s(Map<String, Object> m, String k)   { Object v = m.get(k); return v == null ? "" : v.toString(); }
    private static boolean b(Map<String, Object> m, String k)   { return Boolean.TRUE.equals(m.get(k)); }
    private static double  d(Map<String, Object> m, String k, double def) { Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : def; }
    private static int     ii(Map<String, Object> m, String k, int def)   { Object v = m.get(k); return v instanceof Number n ? (int) Math.round(n.doubleValue()) : def; }
    @SuppressWarnings("unchecked")
    private static List<Object> arr(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof List<?> l ? (List<Object>) l : List.of(); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Map<?, ?> o ? (Map<String, Object>) o : null; }
    private static JxRect rect(Object v) {
        List<?> l = (List<?>) v;
        return new JxRect(((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(),
                ((Number) l.get(2)).doubleValue(), ((Number) l.get(3)).doubleValue());
    }
}
