package sugarcube.jexter.ocd.render;

import sugarcube.jexter.core.JxBlend;
import java.awt.*;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * AWT {@link Composite} implementing all 16 PDF-standard blend modes
 * (PDF Reference §11.3.5) plus alpha support.
 * <p>
 * Usage (as {@link OCDRenderer} does):
 * <pre>
 *     g2.setComposite(BlendComposite.getInstance("Multiply", 0.8f));
 *     g2.fill(shape);
 * </pre>
 * <p>
 * Separable modes: Normal, Multiply, Screen, Overlay, Darken, Lighten,
 * ColorDodge, ColorBurn, HardLight, SoftLight, Difference, Exclusion.
 * <p>
 * Non-separable (HSL-based): Hue, Saturation, Color, Luminosity.
 */
public final class BlendComposite implements Composite {

    /** Normal at full alpha: the composite that needs no blending. */
    public static final BlendComposite Normal     = new BlendComposite(JxBlend.NORMAL);

    private final JxBlend mode;
    private final float alpha;

    // ── Constructors ────────────────────────────────────────────

    private BlendComposite(JxBlend mode) {
        this(mode, 1.0f);
    }

    private BlendComposite(JxBlend mode, float alpha) {
        this.mode = mode;
        this.alpha = Math.clamp(alpha, 0f, 1f);
    }

    // ── Factory methods ─────────────────────────────────────────

    /**
     * Get a BlendComposite from a PDF blend mode name string and alpha.
     * Unknown modes fall back to Normal.
     */
    public static BlendComposite getInstance(String pdfName, float alpha) {
        JxBlend m = JxBlend.of(pdfName);
        if (m == JxBlend.NORMAL && alpha >= 1f) return Normal;
        return new BlendComposite(m, alpha);
    }

    public JxBlend mode() { return mode; }
    public float alpha() { return alpha; }

    // ── AWT Composite interface ─────────────────────────────────

    @Override
    public CompositeContext createContext(ColorModel srcCM, ColorModel dstCM, RenderingHints hints) {
        return new BlendContext(this);
    }

    // ── CompositeContext — per-pixel blending ────────────────────

    private static final class BlendContext implements CompositeContext {

        private final BlendComposite composite;

        BlendContext(BlendComposite composite) {
            this.composite = composite;
        }

        @Override
        public void dispose() {}

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            if (src.getSampleModel().getDataType() != DataBuffer.TYPE_INT
                    || dstIn.getSampleModel().getDataType() != DataBuffer.TYPE_INT
                    || dstOut.getSampleModel().getDataType() != DataBuffer.TYPE_INT)
                throw new IllegalStateException("Source and destination must store pixels as INT.");

            int w = Math.min(src.getWidth(), dstIn.getWidth());
            int h = Math.min(src.getHeight(), dstIn.getHeight());
            float alpha = composite.alpha;
            JxBlend mode = composite.mode;

            int[] srcPx = new int[4];
            int[] dstPx = new int[4];
            int[] result = new int[4];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    src.getPixel(x, y, srcPx);
                    dstIn.getPixel(x, y, dstPx);

                    blend(mode, srcPx, dstPx, result);
                    // PDF 32000 §11.3.6, non-premultiplied. The blend function only has a meaning
                    // where there IS a backdrop, so it enters in proportion to the backdrop's alpha:
                    // over nothing, a Multiply paints the source colour itself. Reading a transparent
                    // pixel as black instead turned every yellow drop shadow of a press page — a
                    // yellow rectangle multiplied under a soft mask, baked on a transparent canvas —
                    // into a dark grey one. Over an opaque backdrop this is exactly the former mix:
                    // the blended colour laid over the destination by the source's own coverage
                    // (src alpha × constant alpha), so src alpha 0 leaves the destination unchanged.
                    float as = (srcPx[3] / 255f) * alpha;
                    if (as <= 0f) { dstOut.setPixel(x, y, dstPx); continue; }
                    float ab = dstPx[3] / 255f;
                    float ar = ab + as - ab * as;
                    float k = as / ar;
                    for (int i = 0; i < 3; i++) {
                        float mixed = (1 - ab) * srcPx[i] + ab * result[i];
                        dstPx[i] = Math.round(dstPx[i] + (mixed - dstPx[i]) * k);
                    }
                    dstPx[3] = Math.round(ar * 255f);
                    dstOut.setPixel(x, y, dstPx);
                }
            }
        }
    }

    // ── Per-pixel blend functions ───────────────────────────────

    private static void blend(JxBlend mode, int[] src, int[] dst, int[] out) {
        switch (mode) {
            case NORMAL -> {
                out[0] = src[0]; out[1] = src[1]; out[2] = src[2];
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case MULTIPLY -> {
                out[0] = (src[0] * dst[0]) >> 8;
                out[1] = (src[1] * dst[1]) >> 8;
                out[2] = (src[2] * dst[2]) >> 8;
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case SCREEN -> {
                out[0] = 255 - ((255 - src[0]) * (255 - dst[0]) >> 8);
                out[1] = 255 - ((255 - src[1]) * (255 - dst[1]) >> 8);
                out[2] = 255 - ((255 - src[2]) * (255 - dst[2]) >> 8);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case OVERLAY -> {
                out[0] = dst[0] < 128 ? dst[0] * src[0] >> 7 : 255 - ((255 - dst[0]) * (255 - src[0]) >> 7);
                out[1] = dst[1] < 128 ? dst[1] * src[1] >> 7 : 255 - ((255 - dst[1]) * (255 - src[1]) >> 7);
                out[2] = dst[2] < 128 ? dst[2] * src[2] >> 7 : 255 - ((255 - dst[2]) * (255 - src[2]) >> 7);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case DARKEN -> {
                out[0] = Math.min(src[0], dst[0]);
                out[1] = Math.min(src[1], dst[1]);
                out[2] = Math.min(src[2], dst[2]);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case LIGHTEN -> {
                out[0] = Math.max(src[0], dst[0]);
                out[1] = Math.max(src[1], dst[1]);
                out[2] = Math.max(src[2], dst[2]);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case COLOR_DODGE -> {
                out[0] = src[0] == 255 ? 255 : Math.min((dst[0] << 8) / (255 - src[0]), 255);
                out[1] = src[1] == 255 ? 255 : Math.min((dst[1] << 8) / (255 - src[1]), 255);
                out[2] = src[2] == 255 ? 255 : Math.min((dst[2] << 8) / (255 - src[2]), 255);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case COLOR_BURN -> {
                out[0] = src[0] == 0 ? 0 : Math.max(0, 255 - (((255 - dst[0]) << 8) / src[0]));
                out[1] = src[1] == 0 ? 0 : Math.max(0, 255 - (((255 - dst[1]) << 8) / src[1]));
                out[2] = src[2] == 0 ? 0 : Math.max(0, 255 - (((255 - dst[2]) << 8) / src[2]));
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case HARD_LIGHT -> {
                out[0] = src[0] < 128 ? dst[0] * src[0] >> 7 : 255 - ((255 - src[0]) * (255 - dst[0]) >> 7);
                out[1] = src[1] < 128 ? dst[1] * src[1] >> 7 : 255 - ((255 - src[1]) * (255 - dst[1]) >> 7);
                out[2] = src[2] < 128 ? dst[2] * src[2] >> 7 : 255 - ((255 - src[2]) * (255 - dst[2]) >> 7);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case SOFT_LIGHT -> {
                int m0 = src[0] * dst[0] / 255;
                int m1 = src[1] * dst[1] / 255;
                int m2 = src[2] * dst[2] / 255;
                out[0] = m0 + src[0] * (255 - ((255 - src[0]) * (255 - dst[0]) / 255) - m0) / 255;
                out[1] = m1 + src[1] * (255 - ((255 - src[1]) * (255 - dst[1]) / 255) - m1) / 255;
                out[2] = m2 + src[2] * (255 - ((255 - src[2]) * (255 - dst[2]) / 255) - m2) / 255;
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case DIFFERENCE -> {
                out[0] = Math.abs(dst[0] - src[0]);
                out[1] = Math.abs(dst[1] - src[1]);
                out[2] = Math.abs(dst[2] - src[2]);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case EXCLUSION -> {
                out[0] = dst[0] + src[0] - (dst[0] * src[0] >> 7);
                out[1] = dst[1] + src[1] - (dst[1] * src[1] >> 7);
                out[2] = dst[2] + src[2] - (dst[2] * src[2] >> 7);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            // ── Non-separable HSL modes ─────────────────────────
            case HUE -> {
                float[] srcHSL = new float[3], dstHSL = new float[3];
                rgbToHsl(src, srcHSL);
                rgbToHsl(dst, dstHSL);
                hslToRgb(srcHSL[0], dstHSL[1], dstHSL[2], out);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case SATURATION -> {
                float[] srcHSL = new float[3], dstHSL = new float[3];
                rgbToHsl(src, srcHSL);
                rgbToHsl(dst, dstHSL);
                hslToRgb(dstHSL[0], srcHSL[1], dstHSL[2], out);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case COLOR -> {
                float[] srcHSL = new float[3], dstHSL = new float[3];
                rgbToHsl(src, srcHSL);
                rgbToHsl(dst, dstHSL);
                hslToRgb(srcHSL[0], srcHSL[1], dstHSL[2], out);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
            case LUMINOSITY -> {
                float[] srcHSL = new float[3], dstHSL = new float[3];
                rgbToHsl(src, srcHSL);
                rgbToHsl(dst, dstHSL);
                hslToRgb(dstHSL[0], dstHSL[1], srcHSL[2], out);
                out[3] = Math.min(255, src[3] + dst[3] - (src[3] * dst[3]) / 255);
            }
        }
    }

    // ── HSL conversion helpers ──────────────────────────────────

    private static void rgbToHsl(int[] rgb, float[] hsl) {
        float r = rgb[0] / 255f, g = rgb[1] / 255f, b = rgb[2] / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float d = max - min;

        float h, s, l = (max + min) / 2f;

        if (d < 0.01f) {
            h = s = 0;
        } else {
            s = l < 0.5f ? d / (max + min) : d / (2f - max - min);
            float dr = (((max - r) / 6f) + (d / 2f)) / d;
            float dg = (((max - g) / 6f) + (d / 2f)) / d;
            float db = (((max - b) / 6f) + (d / 2f)) / d;
            if (r == max)      h = db - dg;
            else if (g == max) h = (1f / 3f) + dr - db;
            else               h = (2f / 3f) + dg - dr;
            if (h < 0) h += 1f;
            if (h > 1) h -= 1f;
        }
        hsl[0] = h; hsl[1] = s; hsl[2] = l;
    }

    private static void hslToRgb(float h, float s, float l, int[] rgb) {
        if (s < 0.01f) {
            int v = (int) (l * 255f);
            rgb[0] = v; rgb[1] = v; rgb[2] = v;
        } else {
            float v2 = l < 0.5f ? l * (1f + s) : (l + s) - (s * l);
            float v1 = 2f * l - v2;
            rgb[0] = (int) (255f * hueToRgb(v1, v2, h + 1f / 3f));
            rgb[1] = (int) (255f * hueToRgb(v1, v2, h));
            rgb[2] = (int) (255f * hueToRgb(v1, v2, h - 1f / 3f));
        }
    }

    private static float hueToRgb(float v1, float v2, float h) {
        if (h < 0f) h += 1f;
        if (h > 1f) h -= 1f;
        if (6f * h < 1f) return v1 + (v2 - v1) * 6f * h;
        if (2f * h < 1f) return v2;
        if (3f * h < 2f) return v1 + (v2 - v1) * (2f / 3f - h) * 6f;
        return v1;
    }

    @Override
    public int hashCode() {
        return Float.floatToIntBits(alpha) * 31 + mode.ordinal();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BlendComposite bc)) return false;
        return mode == bc.mode && alpha == bc.alpha;
    }

    @Override
    public String toString() {
        return "BlendComposite[" + mode + ", alpha=" + alpha + "]";
    }
}
