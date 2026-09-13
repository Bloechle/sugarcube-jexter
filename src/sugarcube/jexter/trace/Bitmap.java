package sugarcube.jexter.trace;

/**
 * A one-bit-per-pixel raster — the working surface for {@link Potrace}. Origin is
 * top-left, {@code (x,y)} addresses a pixel cell; reads outside the grid return
 * {@code false}, so boundary tracing needs no edge guards. The contour algorithm
 * walks the integer <i>corner</i> lattice {@code [0..w] × [0..h]} around set cells.
 */
final class Bitmap {

    final int w, h;
    private final boolean[] bits;

    Bitmap(int w, int h) {
        this.w = w; this.h = h;
        this.bits = new boolean[w * h];
    }

    boolean get(int x, int y) {
        return x >= 0 && x < w && y >= 0 && y < h && bits[y * w + x];
    }

    void set(int x, int y, boolean v) {
        if (x >= 0 && x < w && y >= 0 && y < h) bits[y * w + x] = v;
    }

    void flip(int x, int y) {
        if (x >= 0 && x < w && y >= 0 && y < h) bits[y * w + x] ^= true;
    }

    /** Index of the next set cell at or after the linear position {@code i}, or -1. */
    int nextSet(int i) {
        for (; i < bits.length; i++) if (bits[i]) return i;
        return -1;
    }
}
