package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.ocd.model.*;
import sugarcube.jexter.core.JxRect;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * The {@code stages} projection — exposes the geometry of every analysis stage so a viewer (Jexter Lab's
 * analysis layer) can scrub the pipeline on the rendered page and iterate on the heuristics live:
 *
 * <pre>  runs · lines · leaves · blocks · labeled (HEADING / PARAGRAPH / running-furniture + reading order)</pre>
 *
 * <p>Lines and leaves are transient inside {@link Segmenter#segment} and never reach the OCD, so they cannot
 * be read back from the document — this projection recomputes them exactly as the pipeline does (same
 * {@link Liner}, {@link XYCut}, {@link Segmenter}), so the dump always tracks the current heuristics.
 * The labelled stage is read from the analysed paragraphs already on the page.
 *
 * <p>All boxes are in <b>page user space</b> (Y-up, the OCD's native frame); the viewer maps them with the same
 * page transform it renders with. Output: {@code {"pages":[{"box":[x,y,w,h],"runs":[[x0,y0,x1,y1]…],"lines":…,
 * "leaves":…,"blocks":…,"labeled":[{"k":"H|P|F","i":readingIndex,"b":[…]}…]}…]}}.
 */
public final class AnalysisStages {

    private AnalysisStages() {}

    /** Method-reference target for the {@code Conversion} target registry (matches the {@code Projection} contract). */
    public static void write(OCDDocument doc, OutputStream out, ConvertOptions opt) throws IOException {
        FontProfile fp = FontProfile.of(doc);
        StringBuilder s = new StringBuilder("{\"pages\":[");
        for (int pg = 0; pg < doc.pageCount(); pg++) {
            if (pg > 0) s.append(',');
            s.append(page(doc, fp, pg));
        }
        s.append("]}");
        out.write(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String page(OCDDocument doc, FontProfile fp, int pg) {
        OCDPage page = doc.page(pg);
        List<OCDText> runs = page.texts().toList();

        List<JxRect> runB = new ArrayList<>();
        for (OCDText t : runs) if (!t.bounds().isEmpty()) runB.add(t.bounds());

        List<Liner.Line> lines = Liner.lines(runs, true);
        Map<Liner.Line, JxRect> lb = new IdentityHashMap<>(); List<JxRect> lineB = new ArrayList<>();
        for (Liner.Line ln : lines) { JxRect u = union(ln.runs()); lb.put(ln, u); lineB.add(u); }

        Function<Liner.Line, JxRect> bf = lb::get;
        List<JxRect> leafB = new ArrayList<>();
        for (List<Liner.Line> leaf : XYCut.segment(lines, bf)) {
            JxRect u = JxRect.EMPTY; for (Liner.Line ln : leaf) u = u.isEmpty() ? bf.apply(ln) : u.union(bf.apply(ln));
            leafB.add(u);
        }

        List<JxRect> blockB = new ArrayList<>();
        for (Segmenter.Block b : Segmenter.segment(doc, runs, pg)) blockB.add(b.bounds);

        // labelled — read the analysed paragraphs already on the page
        List<OCDParagraph> paras = page.nodes()
                .filter(OCDParagraph.class::isInstance).map(OCDParagraph.class::cast).toList();
        StringBuilder lab = new StringBuilder("["); int ro = 0;
        for (OCDParagraph p : paras) {
            Segmenter.Block b = Segmenter.fromParagraph(doc, p, pg);
            double sz = Math.round(b.size * 2) / 2.0;
            FontProfile.Style st = new FontProfile.Style(sz, b.bold());
            boolean run = Furniture.isRunning(p);
            boolean head = !run && (fp.isTitle(st) || fp.headingLevel(st) > 0)
                    && b.lines.size() <= 2 && b.text().matches(".*\\p{L}{2,}.*") && b.text().length() <= 160;
            String k = run ? "F" : (head ? "H" : "P");
            if (ro > 0) lab.append(',');
            lab.append("{\"k\":\"").append(k).append("\",\"i\":").append(ro++).append(",\"b\":").append(box(b.bounds)).append('}');
        }
        lab.append(']');

        JxRect eb = page.effectiveBox();
        return "{\"box\":[" + fmt(eb.x()) + ',' + fmt(eb.y()) + ',' + fmt(eb.width()) + ',' + fmt(eb.height()) + "]"
                + ",\"runs\":" + arr(runB)
                + ",\"lines\":" + arr(lineB)
                + ",\"leaves\":" + arr(leafB)
                + ",\"blocks\":" + arr(blockB)
                + ",\"labeled\":" + lab + "}";
    }

    private static JxRect union(List<OCDText> rs) {
        JxRect u = JxRect.EMPTY; for (OCDText t : rs) u = u.isEmpty() ? t.bounds() : u.union(t.bounds()); return u;
    }
    private static String box(JxRect b) {
        return "[" + fmt(b.minX()) + ',' + fmt(b.minY()) + ',' + fmt(b.maxX()) + ',' + fmt(b.maxY()) + "]";
    }
    private static String arr(List<JxRect> bs) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < bs.size(); i++) { if (i > 0) s.append(','); s.append(box(bs.get(i))); }
        return s.append(']').toString();
    }
    private static String fmt(double v) { return String.format(Locale.US, "%.1f", v); }
}
