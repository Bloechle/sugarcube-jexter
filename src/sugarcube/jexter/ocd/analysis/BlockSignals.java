package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.core.ListMarker;
import sugarcube.jexter.ocd.model.OCDBreak;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFont;
import sugarcube.jexter.ocd.model.OCDGraphic;
import sugarcube.jexter.ocd.model.OCDGroup;
import sugarcube.jexter.ocd.model.OCDImage;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDParagraph;
import sugarcube.jexter.ocd.model.OCDText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Geometric / typographic signal harvest for the LLM structure pass: walks the OCD flow (reading order)
 *  and emits one {@link Block} per content unit — grid box, dominant typography, density, alignment, colour,
 *  background and per-column spatial position. Pure read-only analysis: no model, no mutation. */
final class BlockSignals {

    private BlockSignals() {}

    private static final int    GRID         = 1000;  // normalised coordinate grid (top-down), à la DocTags loc / LMDX coords
    private static final int    MIN_GUTTER   = 28;    // grid: a vertical empty band this wide separates columns
    private static final double SPAN_FRAC    = 0.62;  // a block wider than this fraction of the page spans columns (excluded from gutter search)
    private static final int    ROT_MIN_DEG  = 5;     // emit `rot` only beyond this angle
    private static final double BG_COVER      = 0.70; // a filled rect must cover this fraction of a block to count as its background
    private static final double BG_PAGE_MAX   = 0.88; // …and not span more than this fraction of the page (that is the page background)

    static List<Block> harvest(OCDDocument doc) {
        List<Block> out = new ArrayList<>();
        List<OCDPage> pages = doc.pages();
        for (int pi = 0; pi < pages.size(); pi++) {
            OCDPage page = pages.get(pi);
            List<Block> pageBlocks = new ArrayList<>();
            collect(doc, page.content(), pi, page, pageBlocks);
            // collect() walks the flow, which is reading order — no re-sort needed.
            enrichSpatial(pageBlocks);                                   // columns, indent, width, gap-above
            enrichBackground(pageBlocks, page);                          // background colour from filled rects
            out.addAll(pageBlocks);
        }
        return out;
    }

    /** Walk the level in reading order and emit one UNIT per content entity. A unit is a list because a
     *  single text block can reach the page as SEVERAL {@link OCDParagraph} fragments — {@code Paragrapher}
     *  must split a block whose runs interleave in paint order (FORMAT §B3), and that is a presentation
     *  fact. The LLM must read the paragraph, not the pieces the renderer forced, so the fragments of one
     *  {@link OCDParagraph#flow()} are gathered back into one unit here — the same rule
     *  {@code StructureBuilder} applies to the logical tree. */
    private static void collect(OCDDocument doc, List<OCDNode> nodes, int pi, OCDPage page, List<Block> out) {
        List<List<OCDNode>> units = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        unitize(nodes, units, kinds, new HashMap<>());
        for (int i = 0; i < units.size(); i++) out.add(block(doc, units.get(i), pi, page, kinds.get(i)));
    }

    private static void unitize(List<OCDNode> nodes, List<List<OCDNode>> units, List<String> kinds,
                                Map<Integer, List<OCDNode>> byFlow) {
        for (OCDNode n : nodes) {
            if (n instanceof OCDParagraph p) {
                List<OCDNode> unit = p.isFragment() ? byFlow.get(p.flow()) : null;
                if (unit == null) {
                    unit = new ArrayList<>(); units.add(unit); kinds.add("text");
                    if (p.isFragment()) byFlow.put(p.flow(), unit);
                }
                unit.add(p);
            }
            else if (n instanceof OCDImage im)   { units.add(new ArrayList<>(List.of(im))); kinds.add("image"); }
            else if (n instanceof OCDGraphic gr) { units.add(new ArrayList<>(List.of(gr))); kinds.add("graphic"); }
            else if (n instanceof OCDGroup g)    unitize(g.children(), units, kinds, byFlow);   // layer / wrapper → descend
        }
    }

    private static Block block(OCDDocument doc, List<OCDNode> unit, int pi, OCDPage page, String kind) {
        OCDNode head = unit.get(0);
        Sig sig = new Sig();
        for (OCDNode n : unit) {
            if (n instanceof OCDGroup g) gather(doc, g, sig);
            else if (idOf(n) != null)    sig.leaves.add(idOf(n));    // a standalone leaf (image) references itself
        }
        // Visual lines come from Liner — THE line authority — over the unit's runs, not from counting
        // OCDBreaks: a fragment boundary is not necessarily a line boundary, so breaks alone would miscount
        // a merged flow. One derivation for the count AND the per-line extents the alignment reads.
        List<Liner.Line> lines = Liner.lines(runsOf(unit), false);
        String text = JxText.collapse(sig.sb.toString());

        OCDFont f = doc.font(sig.domFontId);
        boolean bold   = sig.anyBold || (f != null && "bold".equalsIgnoreCase(f.weight()));
        boolean italic = f != null && f.style() != null && !"normal".equalsIgnoreCase(f.style());
        String  family = f != null && f.family() != null ? f.family() : (f != null ? f.name() : "");

        Block b = new Block();
        b.id = idOf(head); b.page = pi; b.kind = kind;
        b.leaves = sig.leaves; b.text = text;
        b.size = sig.domSize;
        b.bold = bold; b.italic = italic;
        b.bullet = !text.isEmpty() && ListMarker.isItemLine(text);
        b.caps = isCaps(text);
        b.ends = endsSentence(text);
        b.digits = digitPct(text);
        b.mono = f != null && f.isMono();
        b.family = family == null ? "" : family;
        b.rot = rotationDeg(sig.domA, sig.domC);
        b.lines = Math.max(1, lines.size());
        b.align = alignOf(lines);
        b.color = colorHex(sig.domFill);
        b.running = Furniture.isRunning(head);
        b.bg = "";

        double pw = page.displayWidth()  <= 0 ? 1 : page.displayWidth();
        double ph = page.displayHeight() <= 0 ? 1 : page.displayHeight();
        JxRect bounds = JxRect.EMPTY;
        for (OCDNode n : unit) { JxRect r = n.bounds(); if (r != null && !r.isEmpty()) bounds = bounds.isEmpty() ? r : bounds.union(r); }
        int[] g = gridBox(bounds, pw, ph);
        b.gx0 = g[0]; b.gy0 = g[1]; b.gx1 = g[2]; b.gy1 = g[3];
        return b;
    }

    /** The unit's inked text runs, in paint order — the input the line authority reads. */
    private static List<OCDText> runsOf(List<OCDNode> unit) {
        return unit.stream().flatMap(OCDNode::stream)
                   .filter(OCDText.class::isInstance).map(OCDText.class::cast)
                   .filter(t -> t.count() > 0).toList();
    }

    private static final class Sig {
        final StringBuilder sb = new StringBuilder();
        final List<String> leaves = new ArrayList<>();
        int domChars; double domSize; String domFontId; int domFill = 0xFF000000;
        double domA = 1, domC = 0;                                 // dominant run transform (for rotation)
        boolean anyBold;
    }

    /** Accumulate one unit's text, dominant run and leaf ids. {@link OCDNode#stream} is the traversal
     *  authority — pre-order, i.e. paint order — so nothing here re-codes the descent; groups are the
     *  containers it yields on the way and carry no signal of their own. */
    private static void gather(OCDDocument doc, OCDNode root, Sig s) {
        for (OCDNode n : root.stream().toList()) {
            if (n instanceof OCDGroup) continue;
            if (n instanceof OCDText t) {
                String run = t.text();
                if (run != null && !run.isEmpty()) {
                    if (s.sb.length() > 0) s.sb.append(' ');
                    s.sb.append(run);
                    if (run.length() > s.domChars) {                       // longest run drives size/font/colour/angle
                        s.domChars = run.length();
                        s.domSize = t.fontSize(); s.domFontId = t.fontId(); s.domFill = t.fill();
                        JxTransform tr = t.transform();
                        if (tr != null) { s.domA = tr.a(); s.domC = tr.c(); }
                    }
                    OCDFont f = doc.font(t.fontId());
                    if (f != null && "bold".equalsIgnoreCase(f.weight())) s.anyBold = true;
                }
                if (t.id() != null && !t.id().isEmpty()) s.leaves.add(t.id());
            } else if (n instanceof OCDBreak) {
                if (s.sb.length() > 0) s.sb.append(' ');                    // a break separates words; the LINE
                                                                            // count comes from Liner, not from here
            } else if (n.id() != null && !n.id().isEmpty()) {
                s.leaves.add(n.id());                                       // image / path / media leaf inside the block
            }
        }
    }

    private static void enrichSpatial(List<Block> page) {
        if (page.isEmpty()) return;
        int[] bands = columnBands(page);
        for (Block b : page) {
            int col = 0;
            for (int i = 0; i < bands.length - 1; i++)
                if (b.gx0 >= bands[i] && (b.gx1 + b.gx0) / 2 < bands[i + 1]) { col = i; break; }
            int bandLeft  = bands[col];
            int bandRight = bands[Math.min(col + 1, bands.length - 1)];
            int bandWidth = Math.max(1, bandRight - bandLeft);
            b.col    = col;
            b.indent = clampGrid(Math.round((b.gx0 - bandLeft) * (float) GRID / bandWidth));
            b.width  = clampGrid(Math.round((b.gx1 - b.gx0)     * (float) GRID / bandWidth));
            b.gapAbove = gapAbove(page, b);
        }
    }

    private static int[] columnBands(List<Block> page) {
        boolean[] covered = new boolean[GRID + 1];
        boolean any = false;
        for (Block b : page) {
            if (!"text".equals(b.kind)) continue;
            if (b.gx1 - b.gx0 > SPAN_FRAC * GRID) continue;            // a spanning title/banner never defines a gutter
            for (int x = Math.max(0, b.gx0); x <= Math.min(GRID, b.gx1); x++) covered[x] = true;
            any = true;
        }
        if (!any) return new int[] { 0, GRID };
        List<Integer> lefts = new ArrayList<>();
        int x = 0;
        while (x <= GRID && !covered[x]) x++;
        lefts.add(x > GRID ? 0 : x);
        while (x <= GRID) {
            if (!covered[x]) {
                int start = x;
                while (x <= GRID && !covered[x]) x++;
                if (x - start >= MIN_GUTTER && x <= GRID) lefts.add(x);
            } else x++;
        }
        lefts.add(GRID);
        int[] out = new int[lefts.size()];
        for (int i = 0; i < out.length; i++) out[i] = lefts.get(i);
        return out;
    }

    private static int gapAbove(List<Block> page, Block b) {
        int nearestBottom = -1;
        for (Block o : page) {
            if (o == b || o.gy1 > b.gy0) continue;                     // must be strictly above (smaller y = higher)
            int ov = Math.min(b.gx1, o.gx1) - Math.max(b.gx0, o.gx0);
            if (ov <= 0) continue;                                     // must overlap horizontally
            if (o.gy1 > nearestBottom) nearestBottom = o.gy1;
        }
        return nearestBottom < 0 ? -1 : clampGrid(b.gy0 - nearestBottom);
    }

    private static void enrichBackground(List<Block> page, OCDPage ocdPage) {
        List<int[]> rects = new ArrayList<>();    // {gx0,gy0,gx1,gy1,argb}
        double pw = ocdPage.displayWidth()  <= 0 ? 1 : ocdPage.displayWidth();
        double ph = ocdPage.displayHeight() <= 0 ? 1 : ocdPage.displayHeight();
        collectFills(ocdPage, pw, ph, rects);
        if (rects.isEmpty()) return;
        long pageArea = (long) GRID * GRID;
        for (Block b : page) {
            if (!"text".equals(b.kind)) continue;
            long blockArea = Math.max(1L, (long) (b.gx1 - b.gx0) * (b.gy1 - b.gy0));
            int[] best = null; long bestArea = Long.MAX_VALUE;
            for (int[] r : rects) {
                long rArea = (long) (r[2] - r[0]) * (r[3] - r[1]);
                if (rArea > BG_PAGE_MAX * pageArea) continue;          // that is the page background, not a callout
                long ix = Math.max(0, Math.min(b.gx1, r[2]) - Math.max(b.gx0, r[0]));
                long iy = Math.max(0, Math.min(b.gy1, r[3]) - Math.max(b.gy0, r[1]));
                if (ix * iy < BG_COVER * blockArea) continue;          // must cover most of the block
                if (rArea < bestArea) { best = r; bestArea = rArea; }  // tightest enclosing fill wins
            }
            if (best != null) {
                String hex = bgHex(best[4]);
                if (!hex.isEmpty()) b.bg = hex;
            }
        }
    }

    private static void collectFills(OCDPage page, double pw, double ph, List<int[]> out) {
        page.paths().filter(p -> p.isFilled() && !p.hasGradient()).forEach(p -> {
            int[] g = gridBox(p.bounds(), pw, ph);
            if (g[2] > g[0] && g[3] > g[1]) out.add(new int[] { g[0], g[1], g[2], g[3], p.fill() });
        });
    }

    private static boolean isCaps(String s) {
        int letters = 0, upper = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        }
        return letters >= 3 && upper >= letters * 0.8;
    }

    private static boolean endsSentence(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            return c == '.' || c == '!' || c == '?' || c == '\u2026';   // …
        }
        return false;
    }

    private static int digitPct(String s) {
        int n = 0, d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            n++; if (Character.isDigit(c)) d++;
        }
        return n == 0 ? 0 : (int) Math.round(100.0 * d / n);
    }

    private static int rotationDeg(double a, double c) {
        double deg = Math.toDegrees(Math.atan2(c, a));
        int r = (int) Math.round(deg);
        return Math.abs(r) >= ROT_MIN_DEG ? r : 0;
    }

    private static String alignOf(List<Liner.Line> visual) {
        List<double[]> lines = lineExtents(visual);
        if (lines.size() < 2) return "";
        double minL = Double.MAX_VALUE, maxL = -Double.MAX_VALUE, minR = Double.MAX_VALUE, maxR = -Double.MAX_VALUE;
        double minC = Double.MAX_VALUE, maxC = -Double.MAX_VALUE, span = 0;
        for (int i = 0; i < lines.size(); i++) {
            double l = lines.get(i)[0], r = lines.get(i)[1], c = (l + r) / 2;
            minL = Math.min(minL, l); maxL = Math.max(maxL, l);
            minC = Math.min(minC, c); maxC = Math.max(maxC, c);
            if (i < lines.size() - 1) { minR = Math.min(minR, r); maxR = Math.max(maxR, r); }
            span = Math.max(span, r - l);
        }
        if (span <= 0) return "";
        double tol = 0.06 * span;
        boolean flushL = (maxL - minL) <= tol, flushR = (maxR - minR) <= tol, centred = (maxC - minC) <= tol;
        if (flushL && flushR) return "justify";
        if (flushL)           return "left";
        if (centred)          return "center";
        if (flushR)           return "right";
        return "left";
    }

    /** Per-line horizontal extents, read off the visual lines the line authority produced. */
    private static List<double[]> lineExtents(List<Liner.Line> visual) {
        List<double[]> out = new ArrayList<>();
        for (Liner.Line ln : visual) {
            double l = Double.MAX_VALUE, r = -Double.MAX_VALUE;
            for (OCDText t : ln.runs()) {
                JxRect b = t.bounds();
                if (!b.isEmpty()) { l = Math.min(l, b.minX()); r = Math.max(r, b.right()); }
            }
            if (r > l) out.add(new double[]{ l, r });
        }
        return out;
    }

    private static String colorHex(int argb) {
        JxColor c = new JxColor(argb);
        int max = Math.max(c.r(), Math.max(c.g(), c.b())), min = Math.min(c.r(), Math.min(c.g(), c.b()));
        if (max < 48 && (max - min) < 24) return "";                   // default body ink
        return c.rgbHex();
    }

    private static String bgHex(int argb) {
        JxColor c = new JxColor(argb);
        if (c.a() < 0x40) return "";                                     // (near-)transparent → no background
        int min = Math.min(c.r(), Math.min(c.g(), c.b())), max = Math.max(c.r(), Math.max(c.g(), c.b()));
        if (min > 245 && (max - min) < 10) return "";                   // paper white
        return c.rgbHex();
    }

    private static String idOf(OCDNode n) { return n.id() == null || n.id().isEmpty() ? null : n.id(); }

    private static int[] gridBox(JxRect r, double pw, double ph) {
        return new int[] {
            clamp(r.minX() / pw), clamp((ph - r.bottom()) / ph),
            clamp(r.right() / pw), clamp((ph - r.minY())  / ph)
        };
    }

    private static int    clamp(double f)  { return (int) Math.max(0, Math.min(GRID, Math.round(f * GRID))); }

    private static int    clampGrid(int v) { return Math.max(0, Math.min(GRID, v)); }
}
