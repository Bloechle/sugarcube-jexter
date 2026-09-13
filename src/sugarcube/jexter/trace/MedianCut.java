package sugarcube.jexter.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Median-cut colour quantization: reduces an RGB image to a small palette by
 * recursively splitting the colour box along its longest axis at the median.
 * Produces an opaque palette plus a per-pixel index map ({@code -1} for pixels
 * treated as transparent). Cheap, deterministic, good enough for flat-colour
 * line art and logos — the typical input for tracing.
 */
final class MedianCut {

    /** Palette colours as opaque {@code 0xFFrrggbb}. */
    final int[] palette;
    /** Per-pixel palette index, row-major; {@code -1} = transparent (not traced). */
    final int[] index;

    private MedianCut(int[] palette, int[] index) { this.palette = palette; this.index = index; }

    static MedianCut quantize(int[] argb, int k, int alphaThreshold) {
        // collect opaque pixels' rgb
        List<int[]> box = new ArrayList<>();
        int[] pxColor = new int[argb.length];        // packed rgb per opaque pixel, -1 if transparent
        for (int i = 0; i < argb.length; i++) {
            int a = (argb[i] >>> 24) & 0xFF;
            if (a < alphaThreshold) { pxColor[i] = -1; continue; }
            int rgb = argb[i] & 0xFFFFFF;
            pxColor[i] = rgb;
        }
        // unique-ish sampling: feed all opaque rgb into the cut
        List<Integer> pool = new ArrayList<>();
        for (int v : pxColor) if (v >= 0) pool.add(v);
        if (pool.isEmpty()) return new MedianCut(new int[]{0xFF000000}, filled(argb.length, -1));

        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(pool);
        while (boxes.size() < k) {
            // pick the box with the largest colour range to split
            int bi = -1; int bestRange = -1;
            for (int i = 0; i < boxes.size(); i++) {
                int r = range(boxes.get(i));
                if (r > bestRange) { bestRange = r; bi = i; }
            }
            if (bi < 0 || boxes.get(bi).size() < 2 || bestRange == 0) break;
            List<List<Integer>> split = split(boxes.get(bi));
            boxes.remove(bi);
            boxes.addAll(split);
        }

        int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) palette[i] = 0xFF000000 | dominant(boxes.get(i));

        // assign each pixel to nearest palette entry
        int[] index = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            if (pxColor[i] < 0) { index[i] = -1; continue; }
            index[i] = nearest(palette, pxColor[i]);
        }
        return new MedianCut(palette, index);
    }

    private static int[] filled(int n, int v) { int[] a = new int[n]; java.util.Arrays.fill(a, v); return a; }

    private static int range(List<Integer> box) {
        int rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
        for (int v : box) {
            int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
            rmin = Math.min(rmin, r); rmax = Math.max(rmax, r);
            gmin = Math.min(gmin, g); gmax = Math.max(gmax, g);
            bmin = Math.min(bmin, b); bmax = Math.max(bmax, b);
        }
        return Math.max(rmax - rmin, Math.max(gmax - gmin, bmax - bmin));
    }

    private static List<List<Integer>> split(List<Integer> box) {
        // longest axis
        int rmin = 255, rmax = 0, gmin = 255, gmax = 0, bmin = 255, bmax = 0;
        for (int v : box) {
            int r = (v >> 16) & 0xFF, g = (v >> 8) & 0xFF, b = v & 0xFF;
            rmin = Math.min(rmin, r); rmax = Math.max(rmax, r);
            gmin = Math.min(gmin, g); gmax = Math.max(gmax, g);
            bmin = Math.min(bmin, b); bmax = Math.max(bmax, b);
        }
        int dr = rmax - rmin, dg = gmax - gmin, db = bmax - bmin;
        final int axis = (dr >= dg && dr >= db) ? 16 : (dg >= db ? 8 : 0);
        box.sort((p, q) -> Integer.compare((p >> axis) & 0xFF, (q >> axis) & 0xFF));
        int mid = box.size() / 2;
        List<List<Integer>> out = new ArrayList<>(2);
        out.add(new ArrayList<>(box.subList(0, mid)));
        out.add(new ArrayList<>(box.subList(mid, box.size())));
        return out;
    }

    /** Most frequent exact colour in the box — keeps flat art's colours crisp (no muddy averages). */
    private static int dominant(List<Integer> box) {
        java.util.HashMap<Integer, Integer> freq = new java.util.HashMap<>();
        int best = box.isEmpty() ? 0 : box.get(0), bestN = 0;
        for (int v : box) {
            int c = freq.merge(v, 1, Integer::sum);
            if (c > bestN) { bestN = c; best = v; }
        }
        return best;
    }

    private static int nearest(int[] palette, int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int best = 0; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int pr = (palette[i] >> 16) & 0xFF, pg = (palette[i] >> 8) & 0xFF, pb = palette[i] & 0xFF;
            long d = (long) (r - pr) * (r - pr) + (long) (g - pg) * (g - pg) + (long) (b - pb) * (b - pb);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
}
