package sugarcube.jexter.redact;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxPath;
import sugarcube.jexter.core.JxPoint;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.IdStamper;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.ocd.model.OCDText;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static sugarcube.jexter.redact.RedactionAudit.Finding;
import static sugarcube.jexter.redact.RedactionAudit.Report;

/**
 * Real redaction — nothing is covered, what is under a zone is <em>removed</em> from the model, and
 * {@link RedactionAudit} proves it: {@link #apply} re-audits the result and throws when anything is still
 * recoverable, so nothing is ever written half-done. Audit and redactor share the one definition of "under"
 * ({@link RedactionAudit#under}), so what the audit reveals is exactly what the redactor takes away.
 *
 * <p>Per zone, on its page:
 * <ul>
 *   <li><b>Text</b> — the glyphs under it. A run wholly under is deleted; one partly under is replaced by a copy
 *       carrying only the surviving glyphs ({@link OCDText#copyState}: same paint state, same id, so the page
 *       repaints identically outside the zone).</li>
 *   <li><b>Vector paths</b> — a fill is cut by area subtraction; a stroke-only path (a rule, a frame) is outlined
 *       first and becomes a fill of its stroke colour minus the zone: same pixels outside, nothing inside.</li>
 *   <li><b>Images</b> — the <em>pixels</em> under the zone are painted in the image bytes (a fresh PNG under a
 *       fresh resource ref: a shared image is never altered for its other placements). An image that cannot be
 *       decoded is an error, never a silent leave-alone.</li>
 *   <li><b>Annotations, links, form fields</b> whose rect touches it — the {@code /Redact} marks included, now applied.</li>
 *   <li><b>Structure text</b> — a logical node's denormalized {@link OCDStructNode#text} is cleared when it references
 *       a touched node; refs to deleted nodes are dropped.</li>
 * </ul>
 * Then the mark — one opaque box — is painted <em>under</em> everything ({@code z} below the page's lowest
 * node): the conventional look, and a background to the audit rather than a cover.
 *
 * <p>Two sources of zones, one rule each. A zone <b>drawn</b> (by hand, by a regex on the read-out) removes
 * everything in it and gets a new box. A zone <b>from an audit</b> names the box that already covers it:
 * only what was painted <em>before</em> that box — what it hid — is removed, content drawn on top of it stays,
 * and the box itself keeps its colour and is sent to the bottom of the paint order. Pixel-identical, since
 * nothing the box covered survives: a badly redacted PDF is repaired without changing its look.
 *
 * <p>Out of scope: metadata and outline titles (they may quote redacted text — that is a purge of document
 * members, not a rectangle) and font programs (glyph outlines are not content).
 */
public final class Redactor {

    private Redactor() {}

    /** Black, the convention. */
    public static final int BLACK = 0xFF000000;

    // ── zones ───────────────────────────────────────────────────────────────────

    /** One zone: its page rectangle; the colour of the box that already covers it ({@code null} = none: the
     *  caller's fill); the id of that box ({@code null} = drawn zone: everything in it goes, a new box is painted). */
    public record Zone(JxRect rect, Integer fill, String box) {

        static Zone drawn(JxRect rect) { return new Zone(rect, null, null); }

        static Zone of(Finding f) {
            return new Zone(f.rect(), f.color().isEmpty() ? null : JxColor.ofHex(f.color()).argb(),
                    f.box().isEmpty() ? null : f.box());
        }

        int fillOr(int fallback) { return fill == null ? fallback : fill; }
    }

    /** Zones per 1-based page. */
    public record Zones(Map<Integer, List<Zone>> byPage) {

        public static Zones empty() { return new Zones(new LinkedHashMap<>()); }

        public Zones add(int page, Zone z) { byPage.computeIfAbsent(page, k -> new ArrayList<>()).add(z); return this; }

        public boolean isEmpty() { return byPage.values().stream().allMatch(List::isEmpty); }

        /** Whether a finding lies in one of these zones — the proof's scope. */
        boolean covers(Finding f) {
            for (Zone z : byPage.getOrDefault(f.page(), List.of())) if (z.rect().intersects(f.rect())) return true;
            return false;
        }

        /** Every finding becomes a zone — the "repair a badly redacted PDF" path. */
        public static Zones of(Report report) {
            Zones z = empty();
            for (Finding f : report.findings()) if (!f.rect().isEmpty()) z.add(f.page(), Zone.of(f));
            return z;
        }

        /** The {@code redact} option: {@code audit} (the document's own audit), the audit's JSON (a report or a bare
         *  findings array), or drawn zones as {@code page:x,y,w,h;…} (1-based page, page units, y-up). Blank = none.
         *  Only the {@code audit} form needs the document. */
        public static Zones parse(String spec, OCDDocument doc) {
            String s = spec == null ? "" : spec.trim();
            if (s.isEmpty())                            return empty();
            if (isAudit(s))                             return of(RedactionAudit.audit(doc));
            if (s.startsWith("[") || s.startsWith("{")) return of(Report.fromJson(s));
            return drawn(s);
        }

        public static boolean isAudit(String spec) { return spec != null && spec.trim().equalsIgnoreCase("audit"); }

        /** The 1-based pages a spec names, without a document; {@code null} when it needs one ({@code audit}) or
         *  names none — what a page-scoped read and the patch projection work from. */
        public static Set<Integer> pagesOf(String spec) {
            if (spec == null || spec.isBlank() || isAudit(spec)) return null;
            Zones z = parse(spec, null);
            return z.isEmpty() ? null : z.byPage().keySet();
        }

        private static Zones drawn(String spec) {
            Zones z = empty();
            for (String item : spec.split(";")) {
                if (item.isBlank()) continue;
                String[] pr = item.split(":", 2);
                String[] v  = pr.length == 2 ? pr[1].split(",") : new String[0];
                if (v.length != 4) throw new IllegalArgumentException("zone must be page:x,y,w,h — got '" + item.trim() + "'");
                z.add(Integer.parseInt(pr[0].trim()), Zone.drawn(new JxRect(
                        Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]), Double.parseDouble(v[3]))));
            }
            return z;
        }
    }

    // ── zones from a search ─────────────────────────────────────────────────────

    /**
     * Every match of {@code regex} in the page text, as a report of {@link RedactionAudit.Kind#MATCH} findings —
     * one per matched glyph span, its rect the span's box in page space, so a match is redacted exactly, not its
     * whole run. Text is matched per <em>unit</em>: a paragraph (its runs joined in content order, a line break as
     * {@code '\n'}) or a lone run — so a name split over two runs by a font change still matches; a match that
     * crosses runs yields one finding per run it touches, all carrying the whole matched text.
     */
    public static Report match(OCDDocument doc, String regex) {
        Pattern p = Pattern.compile(regex);
        List<Finding> out = new ArrayList<>();
        int no = 0;
        for (OCDPage page : doc.pages()) {
            no++;
            for (List<OCDText> unit : units(page.content())) {
                StringBuilder sb = new StringBuilder();
                List<int[]> at = new ArrayList<>();                       // char offset → {run, glyph}
                for (int r = 0; r < unit.size(); r++) {
                    OCDText t = unit.get(r);
                    if (r > 0 && t == null) { sb.append('\n'); at.add(null); continue; }
                    List<OCDText.Glyph> gs = t.glyphs();
                    for (int g = 0; g < gs.size(); g++) {
                        String u = gs.get(g).unicode() == null ? "" : gs.get(g).unicode();
                        for (int k = 0; k < u.length(); k++) at.add(new int[] { r, g });
                        sb.append(u);
                    }
                }
                Matcher m = p.matcher(sb);
                while (m.find()) if (m.end() > m.start()) spans(unit, at, m, no, out);
            }
        }
        return new Report(no, out);
    }

    /** The text units of a content tree: each paragraph as its runs in order ({@code null} marks a line break),
     *  every other run alone. */
    private static List<List<OCDText>> units(List<OCDNode> nodes) {
        List<List<OCDText>> out = new ArrayList<>();
        for (OCDNode n : nodes) {
            if (n instanceof OCDParagraph par) {
                List<OCDText> runs = new ArrayList<>();
                for (OCDNode c : par.stream().toList()) { if (c instanceof OCDText t) runs.add(t); else if (c instanceof OCDBreak) runs.add(null); }
                if (!runs.isEmpty()) out.add(runs);
            }
            else if (n instanceof OCDGroup g) out.addAll(units(g.children()));
            else if (n instanceof OCDText t) out.add(List.of(t));
        }
        return out;
    }

    /** One finding per run a match touches: the glyph span of that run, the whole matched text as label. */
    private static void spans(List<OCDText> unit, List<int[]> at, Matcher m, int page, List<Finding> out) {
        int run = -1, from = -1, to = -1;
        for (int ch = m.start(); ch <= m.end(); ch++) {
            int[] pos = ch < m.end() ? at.get(ch) : null;
            if (pos != null && pos[0] == run) { to = pos[1]; continue; }
            if (run >= 0) out.add(new Finding(page, RedactionAudit.Kind.MATCH, "", span(unit.get(run), from, to), "", unit.get(run).id(), m.group()));
            if (pos == null) { run = -1; continue; }
            run = pos[0]; from = to = pos[1];
        }
    }

    /** The page-space box of glyphs {@code from..to} of a run: from the first glyph's origin to the origin of the next
     *  <em>inked</em> glyph after the last (a Spacer-derived blank sits at its predecessor's x, so it is skipped; at the
     *  run's end, a body-width past the last), descender to ascender — the box the audit's
     *  {@link RedactionAudit#under} sampling point falls in. */
    private static JxRect span(OCDText t, int from, int to) {
        double fs = t.fontSize();
        List<OCDText.Glyph> gs = t.glyphs();
        int next = to + 1;
        while (next < gs.size() && gs.get(next).isBlank()) next++;
        double x0 = gs.get(from).x(), x1 = next < gs.size() ? gs.get(next).x() : gs.get(to).x() + 0.6 * fs;
        JxRect local = new JxRect(Math.min(x0, x1), -0.25 * fs, Math.abs(x1 - x0), 1.1 * fs);
        return t.transform().apply(local);
    }

    // ── document members that may quote redacted text ──────────────────────────

    /** Purge the metadata a redacted document must not carry: title, subject, authors, keywords and every custom
     *  field (they routinely quote the very names a page redacts). Producer, dates and language stay — they name
     *  nothing. The outline is left as is: its titles are page content the redaction of that page removes. */
    public static void purgeMeta(OCDDocument doc) {
        OCDMeta m = doc.meta();
        m.title("").subject("");
        m.authors().clear(); m.keywords().clear(); m.custom().clear();
    }

    // ── apply, then prove ───────────────────────────────────────────────────────

    /**
     * Redact in place and prove it: returns the audit of the result. The proof is scoped to the zones —
     * nothing may remain recoverable <em>inside</em> any of them, else {@link IllegalStateException} lists it
     * (a shape this pass cannot cut). Findings elsewhere are pre-existing leaks the caller did not ask about;
     * they come back in the report (not clean), for the caller to show, not to block on.
     *
     * @param fill {@code 0xAARRGGBB} for the boxes; {@code null} = {@link #BLACK} for drawn zones, and the
     *             covering box's own colour for audit zones. A non-null fill overrides both.
     */
    public static Report apply(OCDDocument doc, Zones zones, Integer fill) throws IOException {
        for (Map.Entry<Integer, List<Zone>> e : zones.byPage().entrySet()) {
            OCDPage page = doc.page(e.getKey() - 1);
            if (page == null) throw new IllegalArgumentException("no page " + e.getKey());
            Touched t = new Touched();
            for (Zone z : e.getValue()) redact(doc, page, z, fill != null ? fill : z.fillOr(BLACK), t);
            purge(doc, e.getKey() - 1, t);
        }
        IdStamper.fill(doc);                                          // the new boxes get their ids from the one authority
        Report proof = RedactionAudit.audit(doc);
        List<Finding> inside = proof.findings().stream().filter(f -> zones.covers(f)).toList();
        if (!inside.isEmpty()) throw new IllegalStateException("redaction incomplete — still recoverable: " + inside);
        return proof;
    }

    /** The node ids a page's redaction altered (trimmed, cut, repainted) and the ones it removed. */
    private static final class Touched {
        final Set<String> altered = new HashSet<>(), removed = new HashSet<>();
        void remove(OCDNode n) { removed.add(n.id()); }
        void alter(OCDNode n)  { altered.add(n.id()); }
    }

    private static void redact(OCDDocument doc, OCDPage page, Zone z, int fill, Touched t) throws IOException {
        JxRect zone = z.rect();
        OCDNode cover = z.box() == null ? null : page.nodes().filter(n -> z.box().equals(n.id())).findFirst().orElse(null);
        float ceiling = cover == null ? Float.MAX_VALUE : cover.z();
        cut(doc, page.content(), zone, ceiling, fill, t);
        page.annotations().removeIf(a -> touches(a.rect(), zone));
        page.links().removeIf(l -> touches(l.rect(), zone));
        page.fields().removeIf(f -> touches(f.rect(), zone));
        mark(page, zone, cover, fill);
    }

    /** The mark, painted under everything. Repair: the existing box goes to the bottom (nothing it covered
     *  survives, so the pixels do not move); else a new box. */
    private static void mark(OCDPage page, JxRect zone, OCDNode cover, int fill) {
        float under = (float) page.nodes().mapToDouble(OCDNode::z).min().orElse(0) - 1;
        if (cover != null) { cover.z(under); return; }
        OCDPath box = new OCDPath(new JxPath().move(zone.x(), zone.y()).line(zone.right(), zone.y())
                .line(zone.right(), zone.maxY()).line(zone.x(), zone.maxY()).close()).fill(fill);
        box.z(under).role("redaction");
        page.content().add(0, box);
    }

    private static boolean touches(JxRect r, JxRect zone) { return r != null && r.intersects(zone); }

    // ── cutting, one rule per node kind ─────────────────────────────────────────

    /** Walk a node list (groups recursed): drop what is under the zone and painted below the ceiling, trim what
     *  straddles it. */
    private static void cut(OCDDocument doc, List<OCDNode> nodes, JxRect zone, float ceiling, int fill, Touched t)
            throws IOException {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            OCDNode n = nodes.get(i);
            if (n instanceof OCDGroup g) {
                cut(doc, g.children(), zone, ceiling, fill, t);
                if (g.children().isEmpty() && g.bounds().isEmpty()) { nodes.remove(i); t.remove(g); }
                continue;
            }
            if (n.z() >= ceiling || !n.bounds().intersects(zone)) continue;
            OCDNode kept = switch (n) {                                // each cutter records what it altered
                case OCDText  text  -> trim(text, zone, t);
                case OCDPath  path  -> clip(path, zone, t);
                case OCDImage image -> paint(doc, image, zone, fill, t);
                default             -> n;                             // media, breaks: nothing recoverable in the paint
            };
            if (kept == null) { nodes.remove(i); t.remove(n); } else if (kept != n) nodes.set(i, kept);
        }
    }

    /** The run without its glyphs under the zone; the run itself when none is; {@code null} when none survives. */
    private static OCDText trim(OCDText run, JxRect zone, Touched t) {
        List<OCDText.Glyph> keep = new ArrayList<>();
        for (OCDText.Glyph g : run.glyphs()) if (!RedactionAudit.under(run, g, zone)) keep.add(g);
        if (keep.size() == run.count()) return run;
        t.alter(run);
        if (keep.stream().allMatch(OCDText.Glyph::isBlank)) return null;
        OCDText r = run.copyState();
        r.id(run.id());
        for (OCDText.Glyph g : keep) r.add(g);
        return r;
    }

    /** The path minus the zone (in place); {@code null} when nothing is left. A fill is cut by area subtraction; a
     *  stroke-only path is outlined first — its stroked shape, in page space — and becomes a fill of the stroke colour. */
    private static OCDPath clip(OCDPath p, JxRect zone, Touched t) {
        t.alter(p);
        if (zone.contains(p.bounds())) return null;
        JxTransform tr = p.transform();
        JxPath inPage = p.geometry().transformed(tr.awt());          // the zone's space
        boolean outlined = !p.isFilled();
        Area a = outlined ? new Area(stroke(p, tr.scaleX()).createStrokedShape(inPage)) : inPage.area();
        a.subtract(area(zone));
        if (a.isEmpty()) return null;
        JxPath g = new JxPath(a);
        if (!outlined && p.isEvenOdd()) g.evenOdd(); else g.nonZero();
        p.geometry(g).transform(JxTransform.IDENTITY);
        if (outlined) { p.fill(p.stroke()); p.stroke(0, 0); p.fillGradient(null); }
        return p;
    }

    private static BasicStroke stroke(OCDPath p, double scale) {
        float[] dash = null;
        if (p.hasDash()) { dash = new float[p.dash().length]; for (int i = 0; i < dash.length; i++) dash[i] = (float) p.dash()[i]; }
        return new BasicStroke((float) Math.max(p.strokeWidth() * scale, 0.1),                // hairline → the thinnest visible
                p.cap(), p.join(), (float) Math.max(1, p.miterLimit()), dash, (float) p.dashPhase());
    }

    private static Area area(JxRect r) { return new Area(new Rectangle2D.Double(r.x(), r.y(), r.width(), r.height())); }

    /** Paint the zone into the image bytes — fresh PNG, fresh ref — so the pixels are gone, not hidden. */
    private static OCDImage paint(OCDDocument doc, OCDImage im, JxRect zone, int fill, Touched t) throws IOException {
        byte[] src = doc.image(im.resourceRef());
        BufferedImage img = src == null ? null : ImageIO.read(new ByteArrayInputStream(src));
        if (img == null) throw new IOException("cannot decode image " + im.resourceRef() + " for redaction");
        Rectangle2D px = pixels(zone, im.transform().inverse(), img.getWidth(), img.getHeight());
        if (px.isEmpty()) return im;
        t.alter(im);
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.setColor(new Color(fill, true));
        g.fill(px);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(out, "png", png);
        String ref = doc.newImageRef("png");
        doc.addImage(ref, png.toByteArray());
        im.resourceRef(ref);
        return im;
    }

    /** The zone's pixel rectangle: its corners through the inverse placement into the unit square (y-up), then
     *  into rows/columns (row 0 = top), clamped to the image. */
    private static Rectangle2D pixels(JxRect zone, JxTransform inv, int w, int h) {
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (JxPoint c : List.of(new JxPoint(zone.x(), zone.y()), new JxPoint(zone.right(), zone.y()),
                                 new JxPoint(zone.x(), zone.maxY()), new JxPoint(zone.right(), zone.maxY()))) {
            JxPoint u = inv.apply(c);
            double px = u.x() * w, py = (1 - u.y()) * h;
            x0 = Math.min(x0, px); x1 = Math.max(x1, px); y0 = Math.min(y0, py); y1 = Math.max(y1, py);
        }
        double ix0 = Math.max(0, Math.floor(x0)), iy0 = Math.max(0, Math.floor(y0));
        double ix1 = Math.min(w, Math.ceil(x1)),  iy1 = Math.min(h, Math.ceil(y1));
        return new Rectangle2D.Double(ix0, iy0, Math.max(0, ix1 - ix0), Math.max(0, iy1 - iy0));
    }

    // ── the logical layer ───────────────────────────────────────────────────────

    /** Denormalized structure text is a copy of page text: clear it wherever an altered node is referenced, and
     *  forget the references to removed nodes. */
    private static void purge(OCDDocument doc, int pageIndex, Touched t) {
        for (OCDStructure s : doc.structures()) if (s.root() != null) purge(s.root(), pageIndex, t);
    }

    private static void purge(OCDStructNode n, int page, Touched t) {
        if (n.refs().stream().anyMatch(r -> r.page() == page && t.altered.contains(r.nodeId()))) n.text("");
        n.refs().removeIf(r -> r.page() == page && t.removed.contains(r.nodeId()));
        for (OCDStructNode c : n.children()) purge(c, page, t);
    }
}
