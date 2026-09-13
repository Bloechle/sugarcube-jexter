package sugarcube.jexter.redact;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxPoint;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDPath;
import sugarcube.jexter.ocd.model.OCDText;

import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Is this document <em>actually</em> redacted? A pure geometric query on the OCD model — no rendering, no
 * OCR, no pixel darkness: every content-bearing node is there, in paint order.
 *
 * <p>The failure this catches is the classic one: a black (or white) rectangle drawn <b>over</b> the text
 * instead of the text being removed. On screen the two are identical; in the file the glyphs are still
 * selectable, searchable, copyable. In the model it is an opaque, box-shaped {@link OCDPath} painted
 * <em>after</em> ({@code z} above) a node it covers. Paint order is the whole test: a background drawn
 * before the text (a table cell, a highlight) is legitimate; the same box drawn after it hides something.
 *
 * <p>Three kinds of finding, each carrying its proof:
 * <ul>
 *   <li>{@link Kind#TEXT_UNDER_BOX} — glyphs under an opaque box; the finding carries the hidden text
 *       verbatim. Invisible OCR text (render mode 3) counts: a scan "redacted" with a box keeps its
 *       recognised text layer underneath.</li>
 *   <li>{@link Kind#IMAGE_UNDER_BOX} — an image partly or wholly covered by an opaque box; the pixels under
 *       the box are still in the image bytes. A scanned page redacted with a rectangle is this.</li>
 *   <li>{@link Kind#PENDING_REDACT} — a {@code /Redact} annotation that was marked but never applied; what it
 *       points at is untouched.</li>
 * </ul>
 *
 * <p>Out of scope, on purpose: metadata, bookmarks and form-field values that may quote redacted text —
 * they are document members a redaction <em>tool</em> purges; this audit answers the page-level question.
 * The report is the one shape shared with {@link Redactor}: its findings are the zones of a repair.
 */
public final class RedactionAudit {

    private RedactionAudit() {}

    /** What was found under, or instead of, a redaction — or, for {@link #MATCH}, text a search asked to redact
     *  ({@link Redactor#match}): the same shape carries findings and zone requests. */
    public enum Kind { TEXT_UNDER_BOX, IMAGE_UNDER_BOX, PENDING_REDACT, MATCH }

    /**
     * One leak. {@code page} is 1-based; {@code box} the id of the covering path (empty for a pending
     * annotation); {@code rect} its bounds in page space; {@code color} its fill as {@code #rrggbb}; {@code node}
     * the id of the covered node; {@code text} the hidden text, or the covered fraction of an image.
     */
    public record Finding(int page, Kind kind, String box, JxRect rect, String color, String node, String text) {

        String line() {
            String proof = kind == Kind.IMAGE_UNDER_BOX ? "covered=" + text : '"' + text + '"';
            return "p" + page + " " + kind + " " + box + " " + color + " " + node + " " + proof;
        }
    }

    /** The whole answer: no findings means no box hides anything that survives. */
    public record Report(int pages, List<Finding> findings) {

        public boolean clean() { return findings.isEmpty(); }

        /** One line per finding, then the verdict — the CLI read-out. */
        public String toText() {
            StringBuilder sb = new StringBuilder();
            for (Finding f : findings) sb.append(f.line()).append('\n');
            return sb.append(clean() ? "CLEAN: nothing recoverable under any box"
                    : findings.size() + " recoverable item(s) over " + pages + " page(s)").toString();
        }

        public String toJson() {
            JxStringer js = new JxStringer().obj().num("pages", pages).bool("clean", clean()).arr("findings");
            for (Finding f : findings) {
                js.obj().num("page", f.page()).str("kind", f.kind().name().toLowerCase(Locale.US)).str("box", f.box());
                js.arr("rect").num(f.rect().x()).num(f.rect().y()).num(f.rect().width()).num(f.rect().height()).end();
                js.str("color", f.color()).str("node", f.node()).str("text", f.text()).end();
            }
            return js.end().end().toString();
        }

        /** The inverse of {@link #toJson}: a report, or a bare findings array, back into the shape. */
        public static Report fromJson(String json) {
            Object root = JxJson.parse(json);
            List<Object> items = root instanceof Map<?, ?> m ? JxJson.arr(JxJson.asObj(m), "findings") : JxJson.asArr(root);
            List<Finding> out = new ArrayList<>();
            for (Object o : items) {
                Map<String, Object> f = JxJson.asObj(o);
                List<Object> r = JxJson.arr(f, "rect");
                out.add(new Finding((int) JxJson.lng(f, "page"), kindOf(JxJson.str(f, "kind")),
                        nz(JxJson.str(f, "box")),
                        new JxRect(num(r.get(0)), num(r.get(1)), num(r.get(2)), num(r.get(3))),
                        nz(JxJson.str(f, "color")), nz(JxJson.str(f, "node")), nz(JxJson.str(f, "text"))));
            }
            return new Report(0, out);
        }

        /** A client's own zone kinds ({@code zone}, {@code block}, {@code page}…) are requests, like a match. */
        private static Kind kindOf(String s) {
            try { return s == null ? Kind.MATCH : Kind.valueOf(s.toUpperCase(Locale.US)); } catch (IllegalArgumentException e) { return Kind.MATCH; }
        }
        private static String nz(String s)  { return s == null ? "" : s; }
        private static double num(Object o) { return ((Number) o).doubleValue(); }
    }

    // ── thresholds ─────────────────────────────────────────────────────────────
    private static final double OPAQUE   = 0.9;   // fill alpha × node alpha at or above this hides what is under
    private static final double BOXY     = 0.6;   // path area / bounds area: a rectangle 1, a circle 0.785, a glyph or logo far below
    private static final double MIN_SIDE = 2.0;   // page units: rules and underlines are not boxes

    // ── the query ───────────────────────────────────────────────────────────────

    /** Audit every page. */
    public static Report audit(OCDDocument doc) {
        List<Finding> out = new ArrayList<>();
        int no = 0;
        for (OCDPage page : doc.pages()) auditPage(page, ++no, out);
        return new Report(no, out);
    }

    private static void auditPage(OCDPage page, int no, List<Finding> out) {
        List<OCDPath> boxes = page.paths().filter(RedactionAudit::isOpaqueBox).toList();
        if (!boxes.isEmpty()) {
            List<OCDText>  texts  = page.texts().toList();
            List<OCDImage> images = page.images().toList();
            for (OCDPath box : boxes) {
                JxRect r = box.bounds();
                String color = new JxColor(box.fill()).css();
                for (OCDText t : texts) {
                    if (!isUnder(t, box)) continue;
                    String hidden = covered(t, r);
                    if (!hidden.isEmpty()) out.add(new Finding(no, Kind.TEXT_UNDER_BOX, box.id(), r, color, t.id(), hidden));
                }
                for (OCDImage im : images) {
                    if (!isUnder(im, box)) continue;
                    JxRect ib = im.bounds(), x = ib.intersection(r);
                    if (x.isEmpty()) continue;
                    String frac = String.format(Locale.US, "%.2f", (x.width() * x.height()) / (ib.width() * ib.height()));
                    out.add(new Finding(no, Kind.IMAGE_UNDER_BOX, box.id(), r, color, im.id(), frac));
                }
            }
        }
        for (OCDAnnotation a : page.annotations())
            if (a.type() == OCDAnnotation.Markup.REDACT)
                out.add(new Finding(no, Kind.PENDING_REDACT, "", a.rect() == null ? JxRect.EMPTY : a.rect(),
                        a.color() == null ? "" : a.color().css(), "", a.contents()));
    }

    /** Painted before the box: the box is over it. */
    private static boolean isUnder(OCDNode n, OCDPath box) { return n.z() < box.z(); }

    /** A filled path that hides what is under it: opaque, box-shaped, bigger than a rule. */
    static boolean isOpaqueBox(OCDPath p) {
        if (!p.isFilled() || p.hasGradient() || p.geometry() == null) return false;
        if (new JxColor(p.fill()).alpha() * p.alpha() < OPAQUE) return false;
        JxRect b = p.bounds();
        if (b.width() < MIN_SIDE || b.height() < MIN_SIDE) return false;
        JxRect local = JxRect.of(p.geometry().bounds());          // the area ratio is affine-invariant: measure in path space
        return !local.isEmpty() && area(p.geometry().area()) / (local.width() * local.height()) >= BOXY;
    }

    /** The glyphs of {@code t} under {@code r}, as text; empty when none is. */
    static String covered(OCDText t, JxRect r) {
        if (!t.bounds().intersects(r)) return "";
        StringBuilder sb = new StringBuilder();
        for (OCDText.Glyph g : t.glyphs())
            if (under(t, g, r) && g.unicode() != null) sb.append(g.unicode());
        return sb.toString().strip();                                 // a Spacer sentinel at the seam is not hidden content
    }

    /** Whether glyph {@code g} of run {@code t} lies under {@code r}: the glyph's <i>body</i> is sampled — a quarter
     *  em in, a third em up the x-height — not its origin. THE definition of "under", shared with {@link Redactor}
     *  so what the audit reveals is exactly what the redactor removes. */
    public static boolean under(OCDText t, OCDText.Glyph g, JxRect r) {
        double fs = t.fontSize();
        JxPoint p = t.transform().apply(g.x() + 0.25 * fs, 0.35 * fs);
        return r.contains(p);
    }

    /** Polygon area of an {@link Area} outline — the shoelace formula on the flattened path. */
    private static double area(Area a) {
        PathIterator it = a.getPathIterator(null, 0.25);
        double[] c = new double[6];
        double fx = 0, fy = 0, px = 0, py = 0, sum = 0;
        for (; !it.isDone(); it.next()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> { fx = px = c[0]; fy = py = c[1]; }
                case PathIterator.SEG_LINETO -> { sum += px * c[1] - c[0] * py; px = c[0]; py = c[1]; }
                case PathIterator.SEG_CLOSE  -> { sum += px * fy - fx * py; px = fx; py = fy; }
                default -> { }
            }
        }
        return Math.abs(sum) / 2;
    }

    // ── the projection: Conversion target "audit" ───────────────────────────────

    /** The JSON report as a {@link sugarcube.jexter.write.Projection}. */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        out.write(audit(doc).toJson().getBytes(StandardCharsets.UTF_8));
    }
}
