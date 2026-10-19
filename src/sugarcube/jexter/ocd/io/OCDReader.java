package sugarcube.jexter.ocd.io;

import sugarcube.jexter.core.JxXml;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDLayer;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.OCDText;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import static sugarcube.jexter.core.JxJson.bool;
import static sugarcube.jexter.core.JxJson.has;
import static sugarcube.jexter.core.JxJson.optArr;
import static sugarcube.jexter.core.JxJson.optObj;
import static sugarcube.jexter.core.JxJson.str;

/**
 * Reads an <b>OCD-EPUB</b> back into the {@link OCDDocument} model — the inverse of
 * {@code OcdEpubWriter}/{@code SvgOcdWriter}. One loader, three reaches:
 *
 * <ul>
 *   <li>{@link #readMembers(File)} — Tier 0: document skeleton (meta, outline, structures, fonts),
 *       no pages;</li>
 *   <li>{@link #readPage(File, int)} — Tier 1: the pocket model — one page parsed (SVG-OCD
 *       grammar, self-contained) on top of the skeleton;</li>
 *   <li>{@link #read(File)} — Tier 2: the full model ({@code readPage} over all pages).</li>
 * </ul>
 *
 * <p>Files without {@code OEBPS/ocd/} members are <b>refused</b> — a foreign EPUB is a book,
 * not a model; there is no SVG back-parsing of arbitrary files. Fonts (outline + metrics + cmap)
 * come from {@code pages/fonts.svg} — the single font representation; the pages reference its glyphs
 * externally. Paint attributes are recovered from the page CSS classes; the text stream
 * (unicode, blanks, breaks) from the run's {@code data-text}/{@code data-chars}/{@code data-blanks}; reading
 * order from {@code data-order}; lines from {@code <g data-ocd="line">}; links from the native anchors;
 * page boxes from the root attributes. PDF annotations/fields come from {@code ocd/annots.json}.
 *
 * <p>Known normalizations (by design): {@code z} is rebuilt from document order (order is the
 * semantics; the float values are not serialized) and node {@code tr} on paths is identity
 * (geometry is baked in page space, exactly as the audited writers paint it).
 */
public final class OCDReader {

    private OCDReader() {}

    private static final String OPF = OCDVocab.OPF, JX = OCDVocab.MEMBERS;

    // ── Tier 2: full model ──────────────────────────────────────────────────────
    public static OCDDocument read(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            OCDDocument doc = skeleton(zf);
            Map<String, Object> annots = pagesOf(json(zf, JX + "annots.json"));
            int i = 0;
            for (ZipEntry pe : pageFiles(zf)) doc.add(page(zf, doc, pe, ++i, annots));
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
            List<ZipEntry> pages = pageFiles(zf);
            int i = 0;
            for (ZipEntry pe : pages) {
                if (i == index) { doc.add(page(zf, doc, pe, i + 1, annots)); restoreImageDims(doc); return doc; }
                i++;
            }
            throw new IllegalArgumentException("page " + index + " out of range (0.." + (pages.size() - 1) + ")");
        }
    }

    // ── Tier 1b: the pocket model over a SET of pages — the others stay as empty placeholders, so page
    //    indices are the document's (structures, zones and page numbers address the real page) ──────
    public static OCDDocument readPages(File file, java.util.Set<Integer> indices) throws Exception {
        try (ZipFile zf = new ZipFile(file)) {
            OCDDocument doc = skeleton(zf);
            Map<String, Object> annots = pagesOf(json(zf, JX + "annots.json"));
            int i = 0;
            for (ZipEntry pe : pageFiles(zf)) {
                doc.add(indices.contains(i) ? page(zf, doc, pe, i + 1, annots) : new OCDPage(1, 1));
                i++;
            }
            restoreImageDims(doc);
            resolve(doc, indices);
            return doc;
        }
    }

    /** Number of pages without parsing any (page-document count). */
    public static int pageCount(File file) throws Exception {
        try (ZipFile zf = new ZipFile(file)) { return pageFiles(zf).size(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pagesOf(Map<String, Object> annots) {
        return annots == null ? null : optObj(annots, "pages");
    }

    // ── skeleton: meta + outline + structures + fonts + binaries ────────────────
    @SuppressWarnings("unchecked")
    private static OCDDocument skeleton(ZipFile zf) throws Exception {
        Map<String, Object> meta = json(zf, JX + "meta.json");
        if (meta == null)
            throw new IOException("not an OCD-EPUB: no " + JX + "meta.json member (a foreign EPUB is a book, not a model)");
        // A version this reader does not know is REFUSED, never guessed at (OCDVocab.readable).
        Object ver = meta.get("version");
        if (!OCDVocab.readable(ver == null ? null : ver.toString()))
            throw new IOException("OCD-EPUB version " + ver + " \u2014 this reader reads version " + OCDVocab.VERSION
                    + " only: reconvert the document from its source");

        OCDDocument doc = new OCDDocument();
        readMeta(meta, doc);
        Map<String, Object> oi = optObj(meta, "outputIntent");
        if (oi != null) {
            byte[] profile = null;
            ZipEntry pe = has(oi, "profile") ? zf.getEntry(JX + str(oi, "profile", "")) : null;
            if (pe != null) try (var in = zf.getInputStream(pe)) { profile = in.readAllBytes(); }
            doc.outputIntent(new sugarcube.jexter.ocd.model.OCDOutputIntent(
                    has(oi, "subtype") ? str(oi, "subtype", "") : "GTS_PDFX", str(oi, "conditionId", ""), str(oi, "condition", ""),
                    str(oi, "info", ""), str(oi, "registry", ""), profile, profile == null ? 0 : JxJson.integer(oi, "components", 0)));
        }

        Map<String, Object> nav = json(zf, JX + "outline.json");
        if (nav != null) for (Object o : optArr(nav, "bookmarks")) doc.addOutline(outline((Map<String, Object>) o));

        Map<String, Object> structures = json(zf, JX + "structures.json");
        if (structures != null) {
            for (Object so : optArr(structures, "structures")) {
                Map<String, Object> m = (Map<String, Object>) so;
                OCDStructNode root = new OCDStructNode(OCDStructNode.Type.DOCUMENT);
                Object rootO = m.get("root");
                if (rootO instanceof Map<?, ?> rm)
                    for (Object o : optArr((Map<String, Object>) rm, "children")) root.add(struct((Map<String, Object>) o));
                Object atO = m.get("at"); long at = atO instanceof Number nn ? nn.longValue() : 0;
                doc.addStructure(new OCDStructure(str(m, "id", ""), str(m, "label", ""), OCDStructure.sourceOf(str(m, "source", "")))
                        .by(str(m, "by", "")).at(at).how(str(m, "how", "")).purpose(str(m, "purpose", "")).root(root));
            }
            if (has(structures, "default")) doc.defaultStructureId(str(structures, "default", ""));
        }

        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.equals(OPF + "images/cover.jpg") || name.equals(OPF + "images/cover.png")) {
                // Reused verbatim on re-export — while it is still a picture of page 1 (FORMAT §A2 "cover"):
                // a page edited outside the engine leaves a stale cover behind, and its key says so.
                String key = str(meta, "cover", "");
                List<ZipEntry> pages = pageFiles(zf);
                if (key != null && !pages.isEmpty()
                        && key.equals(OCDVocab.coverKey(zf.getInputStream(pages.get(0)).readAllBytes())))
                    doc.cover(zf.getInputStream(e).readAllBytes(), key);
            } else if (name.startsWith(OPF + "images/"))
                doc.addImage(name.substring((OPF + "images/").length()), zf.getInputStream(e).readAllBytes());
            else if (name.startsWith(OPF + "media/"))
                doc.addMedia(name.substring((OPF + "media/").length()), zf.getInputStream(e).readAllBytes());
            else if (isPassenger(name))
                doc.addPassenger(name.substring(OPF.length()), zf.getInputStream(e).readAllBytes());
        }
        ZipEntry gl = zf.getEntry(OPF + "pages/fonts.svg");
        if (gl != null) SvgOcdReader.fonts(svgRoot(zf, gl), doc);
        return doc;
    }

    /** The pages, IN ORDER — and the order is the OPF <b>spine</b>, because that is where the container
     *  states it. Two things follow, both of them defects until this read it:
     *
     *  <ul>
     *    <li>a reader that reorders the spine (Prism, organising pages) is now believed: before, the
     *        engine re-derived order from the file NAMES and silently undid the move — measured, a
     *        6-page book whose spine said 1,5,3,4,2,6 exported as 1..6 and round-tripped byte-identical
     *        to the original;</li>
     *    <li>a document of 1000 pages or more comes back in order. Names sort lexicographically, so
     *        {@code page-1000} sat between {@code page-100} and {@code page-101}: measured on a
     *        1001-page book, the read-out ran 99, 100, 1000, 1001, 101 and {@code write(read(x)) == x}
     *        failed on it.</li>
     *  </ul>
     *
     *  A container whose spine names no page at all (hand-built, truncated) falls back to name order —
     *  liberal, as the grammar requires — and any page member the spine forgot is appended in name
     *  order rather than dropped: the model must hold every page the container carries. */
    private static List<ZipEntry> pageFiles(ZipFile zf) {
        TreeMap<String, ZipEntry> byName = new TreeMap<>();
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName();
            if (!e.isDirectory() && OCDVocab.isPageMember(n))
                byName.put(n, e);
        }
        List<ZipEntry> out = new ArrayList<>(byName.size());
        for (String path : spine(zf)) {
            ZipEntry e = byName.remove(path);
            if (e != null) out.add(e);
        }
        out.addAll(byName.values());                       // never lose a page the spine did not name
        return out;
    }

    /** The spine, as zip paths: container.xml → the OPF → {@code <itemref>} in order, resolved through
     *  the manifest. Empty when the container states none, which is the caller's cue to fall back. */
    private static List<String> spine(ZipFile zf) {
        try {
            Element container = xml(zf, "META-INF/container.xml");
            if (container == null) return List.of();
            String opfPath = null;
            NodeList roots = container.getElementsByTagNameNS("*", "rootfile");
            if (roots.getLength() > 0) opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            if (opfPath == null || opfPath.isEmpty()) return List.of();
            Element pkg = xml(zf, opfPath);
            if (pkg == null) return List.of();
            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
            Map<String, String> href = new HashMap<>();
            NodeList items = pkg.getElementsByTagNameNS("*", "item");
            for (int i = 0; i < items.getLength(); i++) {
                Element it = (Element) items.item(i);
                href.put(it.getAttribute("id"), base + it.getAttribute("href"));
            }
            List<String> out = new ArrayList<>();
            NodeList refs = pkg.getElementsByTagNameNS("*", "itemref");
            for (int i = 0; i < refs.getLength(); i++) {
                String h = href.get(((Element) refs.item(i)).getAttribute("idref"));
                if (h != null) out.add(h);
            }
            return out;
        } catch (Exception unreadable) {
            return List.of();                              // a broken package is not a reason to lose the pages
        }
    }

    /** One member, parsed with the ONE hardened parser (no DOCTYPE, §A7). Null when absent. */
    private static Element xml(ZipFile zf, String path) throws Exception {
        ZipEntry e = zf.getEntry(path);
        if (e == null) return null;
        try (java.io.InputStream in = zf.getInputStream(e)) {
            return JxXml.secureBuilder().parse(in).getDocumentElement();
        }
    }

    /** Page {@code number} (1-based): its SVG-OCD, then its annotations and fields from {@code annots.json}. */
    private static OCDPage page(ZipFile zf, OCDDocument doc, ZipEntry entry, int number, Map<String, Object> annots) throws Exception {
        OCDPage page = SvgOcdReader.page(svgRoot(zf, entry), doc);
        Map<String, Object> pe = annots == null ? null : optObj(annots, OCDVocab.pageId(number - 1));
        if (pe != null) applyAnnots(pe, page);
        return page;
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
    private static void resolve(OCDDocument doc) { resolve(doc, null); }

    /** Same, over the pages actually parsed ({@code null} = all): a placeholder page of a pocket read defines
     *  nothing, so its references are left alone — they are not broken, just not loaded. */
    private static void resolve(OCDDocument doc, java.util.Set<Integer> loaded) {
        int clips = 0, fonts = 0, refs = 0;
        for (int pi = 0; pi < doc.pageCount(); pi++) {
            if (loaded != null && !loaded.contains(pi)) continue;
            OCDPage page = doc.page(pi);
            for (OCDNode n : page.nodes().toList()) {
                if (n.hasClip() && page.clip(n.clipId()) == null) { n.clipId(null); clips++; }
                if (n instanceof OCDText t && doc.findFont(t.fontId()) == null) fonts++;
            }
        }
        for (OCDStructure st : doc.structures()) refs += prune(doc, st.root(), loaded);
        if (clips > 0) JxLog.warn(OCDReader.class, clips + " clip reference(s) named a clip the page does not define — dropped");
        if (fonts > 0) JxLog.warn(OCDReader.class, fonts + " run(s) name a font the container does not carry — glyphs will not paint");
        if (refs  > 0) JxLog.warn(OCDReader.class, refs  + " structure reference(s) named a node that does not exist — dropped");
    }

    /** Drop the structure refs that name no node, depth-first; returns how many were dropped. */
    private static int prune(OCDDocument doc, OCDStructNode s, java.util.Set<Integer> loaded) {
        int n = 0;
        for (var it = s.refs().iterator(); it.hasNext(); ) {
            OCDStructNode.Ref r = it.next();
            if (loaded != null && !loaded.contains(r.page())) continue;          // not loaded ≠ not there
            OCDPage p = r.page() >= 0 && r.page() < doc.pageCount() ? doc.page(r.page()) : null;
            boolean ok = p != null && p.nodes().anyMatch(x -> r.nodeId().equals(x.id()));
            if (!ok) { it.remove(); n++; }
        }
        for (OCDStructNode k : s.children()) n += prune(doc, k, loaded);
        return n;
    }

    // ── meta / outline / struct (the shared JSON vocabulary) ────────────────────
    @SuppressWarnings("unchecked")
    private static void readMeta(Map<String, Object> m, OCDDocument doc) {
        if (has(m, "id")) doc.id(str(m, "id", ""));
        Map<String, Object> an = optObj(m, "analysis");
        if (an != null) {
            doc.textSegmented(Boolean.TRUE.equals(an.get("textSegmented")));
            doc.headingsDetected(Boolean.TRUE.equals(an.get("headingsDetected")));
        }
        for (Object o : optArr(m, "layers")) {
            Map<String, Object> l = (Map<String, Object>) o;
            OCDLayer layer = new OCDLayer(str(l, "id", ""), str(l, "name", "")).visible(!Boolean.FALSE.equals(l.get("visible"))).order(JxJson.integer(l, "order", 0));
            if (l.get("ocr") instanceof Map<?, ?> ocr && ocr.get("engine") != null)
                layer.recognition(new OCDLayer.Recognition(ocr.get("engine").toString(),
                        ocr.get("prep") == null ? null : ocr.get("prep").toString()));
            doc.add(layer);
        }
        OCDMeta md = doc.meta();
        if (has(m, "title"))    md.title(str(m, "title", ""));
        for (Object a : optArr(m, "authors"))  md.addAuthor(a.toString());
        if (has(m, "subject"))  md.subject(str(m, "subject", ""));
        for (Object k : optArr(m, "keywords")) md.addKeyword(k.toString());
        if (has(m, "creator"))  md.creator(str(m, "creator", ""));
        if (has(m, "producer")) md.producer(str(m, "producer", ""));
        if (has(m, "language")) md.language(str(m, "language", ""));
        if (has(m, "created"))  md.created(str(m, "created", ""));
        if (has(m, "modified")) md.modified(str(m, "modified", ""));
        Map<String, Object> custom = optObj(m, "custom");
        if (custom != null) for (Map.Entry<String, Object> e : custom.entrySet()) md.custom(e.getKey(), e.getValue().toString());
    }

    @SuppressWarnings("unchecked")
    private static OCDOutline outline(Map<String, Object> m) {
        OCDOutline o = new OCDOutline(str(m, "title", ""));
        if (has(m, "page")) o.pageIndex(OCDVocab.pageIndex(str(m, "page", "")));
        if (has(m, "y"))    o.y(JxJson.dbl(m, "y", Double.NaN));
        for (Object c : optArr(m, "children")) o.add(outline((Map<String, Object>) c));
        return o;
    }

    @SuppressWarnings("unchecked")
    private static OCDStructNode struct(Map<String, Object> m) {
        OCDStructNode s = new OCDStructNode(OCDStructNode.Type.valueOf(str(m, "type", "").toUpperCase(Locale.US)));
        if (has(m, "level"))   s.level(JxJson.integer(m, "level", 0));
        if (has(m, "colspan")) s.colSpan(JxJson.integer(m, "colspan", 1));
        if (has(m, "rowspan")) s.rowSpan(JxJson.integer(m, "rowspan", 1));
        if (has(m, "ordered")) s.ordered(bool(m, "ordered"));
        if (has(m, "header"))  s.header(switch (str(m, "header", "")) {
            case "col", "column" -> OCDStructNode.HeaderKind.COLUMN;
            case "row"           -> OCDStructNode.HeaderKind.ROW;
            case "both"          -> OCDStructNode.HeaderKind.BOTH;
            default              -> OCDStructNode.HeaderKind.NONE;
        });
        if (has(m, "text")) s.text(str(m, "text", ""));
        if (has(m, "lang")) s.lang(str(m, "lang", ""));
        if (has(m, "alt"))  s.alt(str(m, "alt", ""));
        for (Object r : optArr(m, "refs")) { Map<String, Object> rm = (Map<String, Object>) r; s.addRef(OCDVocab.pageIndex(str(rm, "page", "")), str(rm, "node", "")); }
        for (Object c : optArr(m, "children")) s.add(struct((Map<String, Object>) c));
        return s;
    }

    // ── annotations (the shared page-member vocabulary) ─────────────────────────
    @SuppressWarnings("unchecked")
    private static void applyAnnots(Map<String, Object> annotations, OCDPage page) {
        for (Object o : optArr(annotations, "annots")) {
            Map<String, Object> ae = (Map<String, Object>) o;
            OCDAnnotation a = new OCDAnnotation(markupOf(str(ae, "type", "")));
            if (has(ae, "rect"))  a.rect(rect(ae.get("rect")));
            if (has(ae, "color")) a.color(new JxColor(SvgOcdReader.argb(str(ae, "color", ""), null)));
            a.author(str(ae, "author", "")).modified(str(ae, "modified", ""));
            for (Object q : optArr(ae, "quads")) a.addQuad(rect(q));
            if (has(ae, "contents")) a.contents(str(ae, "contents", ""));
            page.addAnnotation(a);
        }
        for (Object o : optArr(annotations, "fields")) {
            Map<String, Object> fe = (Map<String, Object>) o;
            OCDFormField ff = new OCDFormField(fieldOf(str(fe, "type", "")))
                    .name(str(fe, "name", "")).onState(str(fe, "on", ""))
                    .readOnly(bool(fe, "readonly")).required(bool(fe, "required")).multiline(bool(fe, "multiline"));
            if (has(fe, "rect")) ff.rect(rect(fe.get("rect")));
            for (Object op : optArr(fe, "options")) ff.addOption(op.toString());
            if (has(fe, "value"))   ff.value(str(fe, "value", ""));
            if (has(fe, "default")) ff.defaultValue(str(fe, "default", ""));
            ff.rotation(JxJson.integer(fe, "rotate", 0)).align(JxJson.integer(fe, "align", 0))
              .fontSize(JxJson.dbl(fe, "size", 0)).borderWidth(JxJson.dbl(fe, "borderWidth", 1))
              .maxLen(JxJson.integer(fe, "maxlen", 0)).hidden(bool(fe, "hidden"));
            if (has(fe, "color"))  ff.textColor(SvgOcdReader.argb(str(fe, "color", ""), null));
            if (has(fe, "border")) ff.borderColor(SvgOcdReader.argb(str(fe, "border", ""), null));
            if (has(fe, "back"))   ff.backColor(SvgOcdReader.argb(str(fe, "back", ""), null));
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
    private static JxRect rect(Object v) {
        List<?> l = (List<?>) v;
        return new JxRect(((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(),
                ((Number) l.get(2)).doubleValue(), ((Number) l.get(3)).doubleValue());
    }
    /** Is this entry a member the model does not own — a viewer sidecar, a house file — that must
     *  travel through a round trip untouched? Everything the writer REBUILDS is excluded: rebuilding it
     *  and carrying the old copy would write it twice, and the two would drift. */
    private static boolean isPassenger(String name) {
        if (!name.startsWith(OPF) || name.endsWith("/")) return false;
        String rel = name.substring(OPF.length());
        if (rel.isEmpty() || rel.startsWith("pages/") || rel.startsWith("images/") || rel.startsWith("media/")
            || rel.startsWith("ocd/")) return false;                       // rebuilt from the model
        return !rel.equals("nav.xhtml") && !rel.equals("toc.ncx") && !rel.endsWith(".opf");
    }

}
