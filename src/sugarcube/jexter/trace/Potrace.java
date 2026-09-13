package sugarcube.jexter.trace;

import sugarcube.jexter.core.JxPath;

import java.util.ArrayList;
import java.util.List;

/**
 * Bi-level vectorizer — a faithful, clean-room implementation of Peter
 * Selinger's Potrace (<i>Potrace: a polygon-based tracing algorithm</i>, 2003),
 * built from the published algorithm (not the GPL source).
 *
 * <p>Five stages per connected region:
 * <ol>
 *   <li><b>Decomposition</b> (§2.1) — boundary following on the corner lattice;
 *       the interior is XOR-inverted so nested holes surface on later scans.</li>
 *   <li><b>Optimal polygon</b> (§2.2) — longest straight subpaths via the
 *       constraint-corridor test ({@link #calcLon}), then the fewest-segment
 *       polygon, ties broken by the standard-deviation penalty
 *       ({@link #bestPolygon}). This is the non-local step that gives Potrace
 *       its quality.</li>
 *   <li><b>Vertex adjustment</b> (§2.3.1) — each vertex placed by least squares:
 *       best-fit line per segment, vertex at the clamped intersection inside the
 *       unit square → sub-pixel corners.</li>
 *   <li><b>Smoothing</b> (§2.3.3) — flatness vs {@code alphaMax} chooses a sharp
 *       corner or a rounded cubic Bézier.</li>
 *   <li><b>Curve optimization</b> (§2.4) — adjacent Béziers fused into one via
 *       area matching + a tangency check within {@code optTolerance}.</li>
 * </ol>
 *
 * <p>Output paths are pixel space (top-left origin, y down); the caller places
 * them. Geometry only — no colour, no model coupling.
 */
final class Potrace {

    private Potrace() {}

    private static final int CORNER = 1, CURVE = 2;

    /** One traced region: integer boundary, then the polygon/curve derived from it. */
    private static final class Path {
        int[] x, y;            // boundary corner points (unit steps), length n
        int   n;
        long  area;            // signed area ×2 (sign distinguishes outer/hole; magnitude despeckles)
        // optimal-polygon working set
        long[] sx, sy, sxy, sx2, sy2;   // prefix sums (length n+1, relative to pt[0])
        int[]  lon;            // longest straight reach per index (cyclic absolute)
        int    m;              // polygon vertex count
        int[]  po;             // polygon vertex indices into the boundary
        // curve
        int[]      tag;        // CORNER | CURVE per segment
        double[][] c0, c1, c2; // control0, control1, endpoint per segment
        double[][] vertex;     // adjusted polygon vertices (pixel space, sub-pixel)
    }

    static List<JxPath> trace(Bitmap bm, TraceOptions o) {
        List<Path> paths = decompose(bm, o);
        List<JxPath> out = new ArrayList<>(paths.size());
        for (Path p : paths) {
            calcSums(p);
            calcLon(p);
            bestPolygon(p);
            adjustVertices(p);
            smooth(p, o.alphaMax);
            if (o.optimize) optiCurve(p, o.optTolerance);
            JxPath jx = toJx(p);
            if (jx != null) out.add(jx);
        }
        return out;
    }

    private static int mod(int a, int n) { return ((a % n) + n) % n; }
    private static int sign(double v) { return v > 0 ? 1 : v < 0 ? -1 : 0; }
    private static long xprod(long x1, long y1, long x2, long y2) { return x1 * y2 - x2 * y1; }

    // ── stage 1: decomposition ────────────────────────────────────────────────

    private static List<Path> decompose(Bitmap bm, TraceOptions o) {
        Bitmap work = new Bitmap(bm.w, bm.h);                 // XOR is destructive — copy first
        for (int y = 0; y < bm.h; y++)
            for (int x = 0; x < bm.w; x++)
                work.set(x, y, bm.get(x, y));

        List<Path> list = new ArrayList<>();
        int scan = 0, lastClearedAt = -1, samePos = 0;
        while ((scan = work.nextSet(scan)) >= 0) {
            int x = scan % work.w, y = scan / work.w;
            Path p = findPath(work, x, y, o.turnPolicy);
            xorPath(work, p);
            if (Math.abs(p.area) > o.turdSize) list.add(p);
            // termination guard: ensure forward progress even on pathological input
            if (scan == lastClearedAt) { if (++samePos > 2) { scan++; samePos = 0; } }
            else { lastClearedAt = scan; samePos = 0; }
        }
        return list;
    }

    private static Path findPath(Bitmap bm, int x0, int y0, TraceOptions.TurnPolicy tp) {
        List<int[]> pts = new ArrayList<>();
        int x = x0, y = y0, dirx = 0, diry = 1;
        long area = 0;
        int guard = 8 * (bm.w + bm.h) + 16, steps = 0;
        do {
            pts.add(new int[]{x, y});
            x += dirx; y += diry;
            area += (long) x * diry;
            if (x == x0 && y == y0) break;
            boolean l = bm.get(x + (dirx + diry - 1) / 2, y + (diry - dirx - 1) / 2);
            boolean r = bm.get(x + (dirx - diry - 1) / 2, y + (diry + dirx - 1) / 2);
            if (r && !l) {
                if (turnRight(tp, bm, x, y)) { int t = dirx; dirx = -diry; diry = t; }
                else                         { int t = dirx; dirx = diry;  diry = -t; }
            } else if (r) {
                int t = dirx; dirx = -diry; diry = t;
            } else if (!l) {
                int t = dirx; dirx = diry; diry = -t;
            }
        } while (++steps < guard);

        Path p = new Path();
        p.n = pts.size();
        p.x = new int[p.n]; p.y = new int[p.n];
        for (int k = 0; k < p.n; k++) { p.x[k] = pts.get(k)[0]; p.y[k] = pts.get(k)[1]; }
        p.area = area;
        return p;
    }

    private static boolean turnRight(TraceOptions.TurnPolicy tp, Bitmap bm, int x, int y) {
        return switch (tp) {
            case RIGHT    -> true;
            case LEFT     -> false;
            case BLACK    -> majority(bm, x, y);     // bias toward connecting set (foreground) pixels
            case WHITE    -> !majority(bm, x, y);    // bias toward connecting unset (background) pixels
            case MAJORITY -> majority(bm, x, y);
            case MINORITY -> !majority(bm, x, y);
        };
    }

    private static boolean majority(Bitmap bm, int x, int y) {
        for (int i = 2; i < 5; i++) {
            int ct = 0;
            for (int a = -i + 1; a <= i - 1; a++) {
                ct += bm.get(x + a, y + i - 1) ? 1 : -1;
                ct += bm.get(x + i - 1, y + a - 1) ? 1 : -1;
                ct += bm.get(x + a - 1, y - i) ? 1 : -1;
                ct += bm.get(x - i, y + a) ? 1 : -1;
            }
            if (ct > 0) return true;
            if (ct < 0) return false;
        }
        return false;
    }

    private static void xorPath(Bitmap bm, Path p) {
        if (p.n <= 0) return;
        int maxX = 0;
        for (int k = 0; k < p.n; k++) maxX = Math.max(maxX, p.x[k]);
        int y1 = p.y[p.n - 1];
        for (int k = 0; k < p.n; k++) {
            int xx0 = p.x[k], yy = p.y[k];
            if (yy != y1) {
                int row = Math.min(yy, y1);
                for (int xx = xx0; xx < maxX; xx++) bm.flip(xx, row);
                y1 = yy;
            }
        }
    }

    // ── stage 2a: prefix sums (relative to pt[0]) ─────────────────────────────

    private static void calcSums(Path p) {
        int n = p.n, ox = p.x[0], oy = p.y[0];
        p.sx = new long[n + 1]; p.sy = new long[n + 1];
        p.sxy = new long[n + 1]; p.sx2 = new long[n + 1]; p.sy2 = new long[n + 1];
        for (int k = 0; k < n; k++) {
            long dx = p.x[k] - ox, dy = p.y[k] - oy;
            p.sx[k + 1]  = p.sx[k]  + dx;
            p.sy[k + 1]  = p.sy[k]  + dy;
            p.sxy[k + 1] = p.sxy[k] + dx * dy;
            p.sx2[k + 1] = p.sx2[k] + dx * dx;
            p.sy2[k + 1] = p.sy2[k] + dy * dy;
        }
    }

    // ── stage 2b: longest straight subpaths (constraint corridor, §2.2.1) ─────

    private static void calcLon(Path p) {
        int n = p.n;
        int[] lon = new int[n];
        int[] ct = new int[4];
        for (int i = n - 1; i >= 0; i--) {
            ct[0] = ct[1] = ct[2] = ct[3] = 0;
            // accumulate from i outward, maintaining a direction set and a constraint cone
            long c0x = 0, c0y = 0, c1x = 0, c1y = 0;
            int dir = (3 + 3 * sign(p.x[mod(i + 1, n)] - p.x[i]) + sign(p.y[mod(i + 1, n)] - p.y[i])) / 2;
            ct[dir]++;
            int foundk = mod(i + 1, n);
            int kk = mod(i + 1, n);
            while (true) {
                int kprev = kk;
                kk = mod(kk + 1, n);
                if (kk == i) break;                        // wrapped fully
                int d = (3 + 3 * sign(p.x[kk] - p.x[kprev]) + sign(p.y[kk] - p.y[kprev])) / 2;
                ct[d]++;
                if (ct[0] != 0 && ct[1] != 0 && ct[2] != 0 && ct[3] != 0) { break; }  // all 4 dirs → not straight
                long ox = p.x[kk] - p.x[i], oy = p.y[kk] - p.y[i];
                // violate current cone?
                if (xprod(c0x, c0y, ox, oy) < 0 || xprod(c1x, c1y, ox, oy) > 0) break;
                if (!(Math.abs(ox) <= 1 && Math.abs(oy) <= 1)) {
                    long ax = ox + ((oy >= 0 && (oy > 0 || ox < 0)) ? 1 : -1);
                    long ay = oy + ((ox <= 0 && (ox < 0 || oy < 0)) ? 1 : -1);
                    if (xprod(c0x, c0y, ax, ay) >= 0) { c0x = ax; c0y = ay; }
                    long bx = ox + ((oy <= 0 && (oy < 0 || ox < 0)) ? 1 : -1);
                    long by = oy + ((ox >= 0 && (ox > 0 || oy < 0)) ? 1 : -1);
                    if (xprod(c1x, c1y, bx, by) <= 0) { c1x = bx; c1y = by; }
                }
                foundk = kk;
            }
            lon[i] = foundk;
        }
        p.lon = lon;
    }

    // ── stage 2c: optimal polygon (fewest segments, penalty tiebreak, §2.2.4) ──

    private static void bestPolygon(Path p) {
        int n = p.n;
        if (n < 3) { p.m = n; p.po = new int[n]; for (int i = 0; i < n; i++) p.po[i] = i; return; }

        // possible-segment reach: from i, farthest j with p_{i-1,j+1} straight
        int[] reach = new int[n];        // farthest boundary index reachable from i (cyclic absolute, i<reach<i+n)
        for (int i = 0; i < n; i++) {
            int base = mod(i - 1, n);
            int far = p.lon[base];                          // straight up to far from i-1
            int j = mod(far - 1, n);                         // clip one from the end → possible segment i..j
            int dist = mod(j - i, n);
            if (dist < 1) dist = 1;                          // i→i+1 always possible (§2.2.2)
            reach[i] = dist;
        }

        // Cyclic DP forced through the most-constrained index (smallest reach — almost always a true
        // corner). Measured equal to the exact windowed search on the test corpus, so kept simple.
        int pivot = 0;
        for (int i = 1; i < n; i++) if (reach[i] < reach[pivot]) pivot = i;

        int[]    cnt = new int[n + 1];
        double[] pen = new double[n + 1];
        int[]    prev = new int[n + 1];
        for (int t = 1; t <= n; t++) { cnt[t] = Integer.MAX_VALUE; pen[t] = Double.MAX_VALUE; prev[t] = -1; }
        cnt[0] = 0; pen[0] = 0;
        for (int t = 0; t < n; t++) {
            if (cnt[t] == Integer.MAX_VALUE) continue;
            int b = mod(pivot + t, n);
            int jump = Math.min(reach[b], n - t);            // don't overshoot the closure
            for (int s = 1; s <= jump; s++) {
                int t2 = t + s, b2 = mod(pivot + t2, n);
                double pp = penalty(p, b, b2);
                int nc = cnt[t] + 1;
                if (nc < cnt[t2] || (nc == cnt[t2] && pen[t] + pp < pen[t2])) {
                    cnt[t2] = nc; pen[t2] = pen[t] + pp; prev[t2] = t;
                }
            }
        }
        List<Integer> rev = new ArrayList<>();
        for (int t = n; t > 0; t = prev[t]) { rev.add(mod(pivot + t, n)); if (prev[t] < 0) break; }
        p.m = rev.size();
        p.po = new int[p.m];
        for (int i = 0; i < p.m; i++) p.po[i] = rev.get(p.m - 1 - i);
        removeCollinear(p);                                  // drop spurious mid-edge vertices (e.g. at the forced pivot)
        if (p.m < 3) {                                       // safety for degenerate tiny loops
            p.m = Math.min(3, n); p.po = new int[p.m];
            for (int i = 0; i < p.m; i++) p.po[i] = (int) ((long) i * n / p.m);
        }
    }

    /** Remove a polygon vertex when it lies (within ½ px) on the chord between its neighbours. */
    private static void removeCollinear(Path p) {
        boolean changed = true;
        int guard = p.m + 2;
        while (changed && p.m > 3 && guard-- > 0) {
            changed = false;
            for (int k = 0; k < p.m; k++) {
                int a = p.po[mod(k - 1, p.m)], b = p.po[k], c = p.po[mod(k + 1, p.m)];
                double abx = p.x[b] - p.x[a], aby = p.y[b] - p.y[a];
                double acx = p.x[c] - p.x[a], acy = p.y[c] - p.y[a];
                double base = Math.hypot(acx, acy);
                double dev = base > 1e-9 ? Math.abs(abx * acy - aby * acx) / base : 0;  // ⟂ dist of b from line a→c
                if (dev < 0.5) {
                    int[] np = new int[p.m - 1];
                    for (int i = 0, j = 0; i < p.m; i++) if (i != k) np[j++] = p.po[i];
                    p.po = np; p.m--; changed = true; break;
                }
            }
        }
    }

    /** Penalty of segment b..b2 (cyclic): chord length × stddev of point distances to the chord (§2.2.3). */
    private static double penalty(Path p, int b, int b2) {
        int n = p.n;
        int len = mod(b2 - b, n) + 1;                        // point count i..j inclusive
        double x = rng(p.sx, b, b2, n), y = rng(p.sy, b, b2, n);
        double xy = rng(p.sxy, b, b2, n), x2 = rng(p.sx2, b, b2, n), y2 = rng(p.sy2, b, b2, n);
        double ex = x / len, ey = y / len;
        double exy = xy / len, ex2 = x2 / len, ey2 = y2 / len;
        // chord vector and midpoint (relative coords)
        double vx = (p.x[b2] - p.x[0]) - (p.x[b] - p.x[0]);
        double vy = (p.y[b2] - p.y[0]) - (p.y[b] - p.y[0]);
        double mx = ((p.x[b] - p.x[0]) + (p.x[b2] - p.x[0])) / 2.0;
        double my = ((p.y[b] - p.y[0]) + (p.y[b2] - p.y[0])) / 2.0;
        double a = ex2 - 2 * mx * ex + mx * mx;
        double bb = exy - mx * ey - my * ex + mx * my;
        double c = ey2 - 2 * my * ey + my * my;
        double s = c * vx * vx - 2 * bb * vx * vy + a * vy * vy;
        return Math.sqrt(Math.max(0, s));
    }

    /** Sum of arr over cyclic range b..b2 inclusive, using prefix sums (length n+1). */
    private static double rng(long[] arr, int b, int b2, int n) {
        if (b <= b2) return arr[b2 + 1] - arr[b];
        return (arr[n] - arr[b]) + (arr[b2 + 1] - arr[0]);   // wraps past 0
    }

    // ── stage 3: vertex adjustment (least squares, §2.3.1) ─────────────────────

    private static void adjustVertices(Path p) {
        int m = p.m, n = p.n;
        // best-fit line for each polygon segment k: po[k]..po[k+1]
        double[][] dir = new double[m][2];     // unit direction of fit line
        double[][] ctr = new double[m][2];     // centroid (absolute coords)
        for (int k = 0; k < m; k++) {
            int from = p.po[k], to = p.po[mod(k + 1, m)];
            fitLine(p, from, to, ctr[k], dir[k]);
        }
        p.vertex = new double[m][2];
        for (int k = 0; k < m; k++) {
            // vertex k sits between segment (k-1) and segment (k)
            int a = mod(k - 1, m);
            double[] nA = {-dir[a][1], dir[a][0]};            // normals (perp to fit dir)
            double[] nB = {-dir[k][1], dir[k][0]};
            double cA = nA[0] * ctr[a][0] + nA[1] * ctr[a][1];
            double cB = nB[0] * ctr[k][0] + nB[1] * ctr[k][1];
            // minimize ((n·p)-cA)^2 + ((n·p)-cB)^2  →  (nA nAᵀ + nB nBᵀ) p = nA cA + nB cB
            double a11 = nA[0]*nA[0] + nB[0]*nB[0];
            double a12 = nA[0]*nA[1] + nB[0]*nB[1];
            double a22 = nA[1]*nA[1] + nB[1]*nB[1];
            double r1  = nA[0]*cA + nB[0]*cB;
            double r2  = nA[1]*cA + nB[1]*cB;
            double det = a11 * a22 - a12 * a12;
            double vx = p.x[p.po[k]], vy = p.y[p.po[k]];      // fallback = lattice corner
            if (Math.abs(det) > 1e-9) {
                double sx = ( a22 * r1 - a12 * r2) / det;
                double sy = (-a12 * r1 + a11 * r2) / det;
                // clamp to the unit square centred on the lattice corner (max-distance ≤ 1/2)
                vx = Math.max(vx - 0.5, Math.min(vx + 0.5, sx));
                vy = Math.max(vy - 0.5, Math.min(vy + 0.5, sy));
            }
            p.vertex[k][0] = vx;
            p.vertex[k][1] = vy;
        }
    }

    /** Best-fit line over boundary points from..to (cyclic): centroid + unit eigenvector of the larger eigenvalue. */
    private static void fitLine(Path p, int from, int to, double[] ctrOut, double[] dirOut) {
        int n = p.n;
        int len = mod(to - from, n) + 1;
        double ex = rng(p.sx, from, to, n) / len + p.x[0];
        double ey = rng(p.sy, from, to, n) / len + p.y[0];
        // central second moments
        double exr = rng(p.sx, from, to, n) / len, eyr = rng(p.sy, from, to, n) / len;
        double a = rng(p.sx2, from, to, n) / len - exr * exr;
        double b = rng(p.sxy, from, to, n) / len - exr * eyr;
        double c = rng(p.sy2, from, to, n) / len - eyr * eyr;
        ctrOut[0] = ex; ctrOut[1] = ey;
        // larger-eigenvalue eigenvector of [[a,b],[b,c]]
        double tr = a + c, dt = a * c - b * b;
        double lam = tr / 2 + Math.sqrt(Math.max(0, tr * tr / 4 - dt));
        double dx, dy;
        if (Math.abs(b) > 1e-9) { dx = lam - c; dy = b; }
        else if (a >= c)        { dx = 1; dy = 0; }
        else                    { dx = 0; dy = 1; }
        double len2 = Math.hypot(dx, dy);
        if (len2 < 1e-12) { dx = 1; dy = 0; len2 = 1; }
        dirOut[0] = dx / len2; dirOut[1] = dy / len2;
    }

    // ── stage 4: smoothing (corner vs Bézier, §2.3.3) ──────────────────────────

    private static double[] interval(double t, double[] a, double[] b) {
        return new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])};
    }
    private static double dpara(double[] p0, double[] p1, double[] p2) {
        double x1 = p1[0] - p0[0], y1 = p1[1] - p0[1], x2 = p2[0] - p0[0], y2 = p2[1] - p0[1];
        return x1 * y2 - x2 * y1;
    }
    private static double ddenom(double[] p0, double[] p2) {
        int rx = -sign(p2[1] - p0[1]), ry = sign(p2[0] - p0[0]);
        return ry * (p2[0] - p0[0]) - rx * (p2[1] - p0[1]);
    }

    private static void smooth(Path p, double alphaMax) {
        int m = p.m;
        p.tag = new int[m]; p.c0 = new double[m][2]; p.c1 = new double[m][2]; p.c2 = new double[m][2];
        for (int i = 0; i < m; i++) {
            int j = mod(i + 1, m), k = mod(i + 2, m);
            double[] pi = p.vertex[i], pj = p.vertex[j], pk = p.vertex[k];
            double[] p4 = interval(0.5, pj, pk);
            double denom = ddenom(pi, pk), alpha;
            if (denom != 0) {
                double dd = Math.abs(dpara(pi, pj, pk) / denom);
                alpha = (dd > 1 ? (1 - 1.0 / dd) : 0) / 0.75;
            } else alpha = 4.0 / 3.0;
            if (alpha >= alphaMax) {
                p.tag[j] = CORNER; p.c1[j] = pj; p.c2[j] = p4;
            } else {
                alpha = Math.max(0.55, Math.min(1.0, alpha));
                p.c0[j] = interval(0.5 + 0.5 * alpha, pi, pj);
                p.c1[j] = interval(0.5 + 0.5 * alpha, pk, pj);
                p.c2[j] = p4; p.tag[j] = CURVE;
            }
        }
    }

    // ── stage 5: curve optimization (area match + tangency, §2.4) ──────────────

    private static void optiCurve(Path p, double tol) {
        int m = p.m;
        if (m < 4) return;
        List<double[]> c0 = new ArrayList<>(), c1 = new ArrayList<>(), c2 = new ArrayList<>();
        List<Integer> tag = new ArrayList<>();
        int i = 0;
        while (i < m) {
            if (p.tag[i] != CURVE) { c0.add(p.c0[i]); c1.add(p.c1[i]); c2.add(p.c2[i]); tag.add(p.tag[i]); i++; continue; }
            int lo = i, hi = i;                              // maximal run of CURVE segments
            while (hi + 1 < m && p.tag[hi + 1] == CURVE) hi++;
            // shortest-path decomposition of the run lo..hi: min #curves, then min penalty
            int len = hi - lo + 1;
            double[][][] fit = new double[len + 1][][];      // accepted fused cubic for sub-run [lo+a .. lo+b]
            int[]    cnt = new int[len + 1];
            double[] pen = new double[len + 1];
            int[]    bk  = new int[len + 1];
            for (int t = 1; t <= len; t++) { cnt[t] = Integer.MAX_VALUE; pen[t] = Double.MAX_VALUE; bk[t] = -1; }
            cnt[0] = 0;
            double[][][] cand = new double[len + 1][][];     // cand[e] = fused fit ending the current segment start
            for (int a = 0; a < len; a++) {                  // sub-run starts at segment lo+a
                if (cnt[a] == Integer.MAX_VALUE) continue;
                for (int b = a; b < len; b++) {              // ...ends at segment lo+b
                    double[] res = new double[1];
                    double[][] f = fitBezier(p, lo + a, lo + b, tol, res);
                    if (f == null) break;                    // longer runs only get worse → stop extending
                    int nc = cnt[a] + 1; double np = pen[a] + res[0];
                    if (nc < cnt[b + 1] || (nc == cnt[b + 1] && np < pen[b + 1])) {
                        cnt[b + 1] = nc; pen[b + 1] = np; bk[b + 1] = a; cand[b + 1] = f;
                    }
                }
            }
            // reconstruct
            if (cnt[len] != Integer.MAX_VALUE) {
                List<double[][]> pieces = new ArrayList<>();
                for (int t = len; t > 0; t = bk[t]) pieces.add(cand[t]);
                for (int k = pieces.size() - 1; k >= 0; k--) {
                    double[][] f = pieces.get(k);
                    c0.add(f[0]); c1.add(f[1]); c2.add(f[2]); tag.add(CURVE);
                }
            } else {                                         // shouldn't happen (single segment always fits itself)
                for (int k = lo; k <= hi; k++) { c0.add(p.c0[k]); c1.add(p.c1[k]); c2.add(p.c2[k]); tag.add(CURVE); }
            }
            i = hi + 1;
        }
        int nm = tag.size();
        p.m = nm; p.tag = new int[nm]; p.c0 = new double[nm][2]; p.c1 = new double[nm][2]; p.c2 = new double[nm][2];
        for (int k = 0; k < nm; k++) { p.tag[k] = tag.get(k); p.c0[k] = c0.get(k); p.c1[k] = c1.get(k); p.c2[k] = c2.get(k); }
    }

    /**
     * Fit one cubic to curve segments [i..j] (from b_{i-1} to b_j), §2.4:
     * apex O = intersection of the end tangents, α set by matching the enclosed area, then a tangency
     * check (every original junction within {@code tol} of the candidate). Returns {c0,c1,end} and the
     * penalty (sum of squared deviations) in {@code penOut}, or null if not acceptable.
     */
    private static double[][] fitBezier(Path p, int i, int j, double tol, double[] penOut) {
        int m = p.m;
        double[] start = p.c2[mod(i - 1, m)];                // b_{i-1}
        double[] end   = p.c2[j];                            // b_j
        double[] ai = p.vertex[i], aj = p.vertex[j];
        double[] tStart = {ai[0] - start[0], ai[1] - start[1]};   // tangent b_{i-1}→a_i
        double[] tEnd   = {end[0] - aj[0],   end[1] - aj[1]};     // tangent a_j→b_j
        double den = tStart[0] * tEnd[1] - tStart[1] * tEnd[0];
        if (Math.abs(den) < 1e-9) return null;               // parallel tangents → no apex
        double s = ((end[0] - start[0]) * tEnd[1] - (end[1] - start[1]) * tEnd[0]) / den;
        if (s <= 0) return null;
        double[] O = {start[0] + s * tStart[0], start[1] + s * tStart[1]};
        // same-convexity / <180° guard: O and every vertex on the same side of chord start→end
        double cx = end[0] - start[0], cy = end[1] - start[1];
        int sideO = sign((O[0] - start[0]) * cy - (O[1] - start[1]) * cx);
        if (sideO == 0) return null;
        for (int k = i; k <= j; k++) {
            double[] v = p.vertex[k];
            if (sign((v[0] - start[0]) * cy - (v[1] - start[1]) * cx) != sideO) return null;
        }
        // target area: signed area enclosed by the original run (sampled) closed by chord end→start
        double target = runArea(p, i, j, start, end);
        // solve α∈(0,4/3] so the fused cubic encloses the same area (monotone in α → bisection)
        double alpha = matchAlpha(start, end, O, target);
        double[] q0 = {start[0] + alpha * (O[0] - start[0]), start[1] + alpha * (O[1] - start[1])};
        double[] q1 = {end[0]   + alpha * (O[0] - end[0]),   end[1]   + alpha * (O[1] - end[1])};
        // tangency/error check + penalty: every original junction b_k within tol of the candidate
        double pen = 0;
        for (int k = i; k < j; k++) {
            double d = distToCubic(start, q0, q1, end, p.c2[k]);
            if (d > tol) return null;
            pen += d * d;
        }
        penOut[0] = pen;
        return new double[][]{q0, q1, end};
    }

    /** Signed area of the original curve run i..j (sampled) closed by the chord end→start. */
    private static double runArea(Path p, int i, int j, double[] start, double[] end) {
        int m = p.m;
        List<double[]> poly = new ArrayList<>();
        poly.add(start);
        double[] from = start;
        for (int k = i; k <= j; k++) {
            double[] z0 = from, z3 = p.c2[k];
            if (p.tag[k] == CURVE) { double[] z1 = p.c0[k], z2 = p.c1[k];
                for (int t = 1; t <= 8; t++) { double u = t / 8.0, v = 1 - u;
                    poly.add(new double[]{v*v*v*z0[0]+3*v*v*u*z1[0]+3*v*u*u*z2[0]+u*u*u*z3[0],
                                          v*v*v*z0[1]+3*v*v*u*z1[1]+3*v*u*u*z2[1]+u*u*u*z3[1]}); }
            } else poly.add(z3);
            from = z3;
        }
        return shoelace(poly);
    }

    private static double shoelace(List<double[]> pts) {
        double a = 0; int n = pts.size();
        for (int k = 0; k < n; k++) { double[] u = pts.get(k), v = pts.get((k + 1) % n);
            a += u[0] * v[1] - v[0] * u[1]; }
        return a / 2;
    }

    /** Bisect α so the fused cubic's enclosed area matches {@code target}. */
    private static double matchAlpha(double[] start, double[] end, double[] O, double target) {
        double lo = 0.01, hi = 4.0 / 3.0;
        for (int it = 0; it < 24; it++) {
            double mid = (lo + hi) / 2;
            double[] q0 = {start[0] + mid * (O[0] - start[0]), start[1] + mid * (O[1] - start[1])};
            double[] q1 = {end[0]   + mid * (O[0] - end[0]),   end[1]   + mid * (O[1] - end[1])};
            double area = cubicArea(start, q0, q1, end);
            if (Math.abs(area) < Math.abs(target)) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** Signed area enclosed by a cubic closed by the chord z3→z0 (sampled). */
    private static double cubicArea(double[] z0, double[] z1, double[] z2, double[] z3) {
        List<double[]> poly = new ArrayList<>();
        for (int t = 0; t <= 16; t++) { double u = t / 16.0, v = 1 - u;
            poly.add(new double[]{v*v*v*z0[0]+3*v*v*u*z1[0]+3*v*u*u*z2[0]+u*u*u*z3[0],
                                  v*v*v*z0[1]+3*v*v*u*z1[1]+3*v*u*u*z2[1]+u*u*u*z3[1]}); }
        return shoelace(poly);
    }

    /** Min distance from point to a cubic Bézier, by dense sampling (robust acceptance test). */
    private static double distToCubic(double[] z0, double[] z1, double[] z2, double[] z3, double[] q) {
        double best = Double.MAX_VALUE;
        for (int s = 0; s <= 24; s++) {
            double t = s / 24.0, u = 1 - t;
            double bx = u*u*u*z0[0] + 3*u*u*t*z1[0] + 3*u*t*t*z2[0] + t*t*t*z3[0];
            double by = u*u*u*z0[1] + 3*u*u*t*z1[1] + 3*u*t*t*z2[1] + t*t*t*z3[1];
            double d = Math.hypot(bx - q[0], by - q[1]);
            if (d < best) best = d;
        }
        return best;
    }

    // ── emit ───────────────────────────────────────────────────────────────────

    private static JxPath toJx(Path p) {
        int m = p.m;
        if (m < 2) return null;
        JxPath path = new JxPath();
        double[] start = p.c2[m - 1];
        path.move(start[0], start[1]);
        for (int i = 0; i < m; i++) {
            if (p.tag[i] == CORNER) { path.line(p.c1[i][0], p.c1[i][1]); path.line(p.c2[i][0], p.c2[i][1]); }
            else path.cubic(p.c0[i][0], p.c0[i][1], p.c1[i][0], p.c1[i][1], p.c2[i][0], p.c2[i][1]);
        }
        path.close();
        return path;
    }

    // ── package-private test hooks (stage validation) ──────────────────────────

    static int[] hookLon(int[] px, int[] py) {
        Path p = new Path(); p.n = px.length; p.x = px.clone(); p.y = py.clone();
        calcSums(p); calcLon(p); return p.lon;
    }
    static double hookPenalty(int[] px, int[] py, int i, int j) {
        Path p = new Path(); p.n = px.length; p.x = px.clone(); p.y = py.clone();
        calcSums(p); return penalty(p, i, j);
    }
}
