package sugarcube.jexter.pdfimport;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.state.PDSoftMask;
import sugarcube.jexter.core.JxLog;

/**
 * A soft mask applied to a rendered group, the one way for the importer (which bakes the group into the model)
 * and the direct renderer (which composites it): the content's alpha multiplied by the mask's value, pixel by
 * pixel (PDF 11.6.5).
 *
 * <p>The value follows the mask's subtype. {@code /Luminosity}: the mask group painted over its backdrop
 * {@code /BC} — given in the group's OWN colour space, so a CMYK {@code [0 0 0 0]} is white — then its
 * luminosity. {@code /Alpha}: the group's alpha, backdrop unused. Then the transfer function {@code /TR}, when
 * there is one. Read before as a luminosity always, with {@code /BC}'s first number taken for a gray, an alpha
 * mask came out as the shape of its colours and a CMYK white backdrop masked everything away.
 */
final class SoftMasks {

    private SoftMasks() {}

    /** {@code content[i]}'s alpha times the mask's value at {@code mask[i]}; both ARGB, same grid. The mask is its
     *  group rendered on a TRANSPARENT canvas — the backdrop is composited here, where its colour space is known. */
    static void apply(int[] content, int[] mask, PDSoftMask sm) {
        boolean alpha = COSName.ALPHA.equals(sm.getSubType());
        int bg = alpha ? 0 : backdrop(sm);
        int[] tr = transfer(sm);
        int br = (bg >> 16) & 255, bgG = (bg >> 8) & 255, bb = bg & 255;
        int n = Math.min(content.length, mask.length);
        for (int i = 0; i < n; i++) {
            int m = mask[i], ma = m >>> 24, v;
            if (alpha) v = ma;
            else {                                              // the mask over its backdrop, then 0.30R + 0.59G + 0.11B
                int r = (((m >> 16) & 255) * ma + br * (255 - ma)) / 255;
                int g = (((m >> 8) & 255) * ma + bgG * (255 - ma)) / 255;
                int b = ((m & 255) * ma + bb * (255 - ma)) / 255;
                v = (77 * r + 150 * g + 29 * b) >> 8;
            }
            if (tr != null) v = tr[v];
            int a = ((content[i] >>> 24) * v) / 255;
            content[i] = (a << 24) | (content[i] & 0x00FFFFFF);
        }
    }

    /** {@code /BC} as an opaque sRGB colour, read in the mask group's colour space; black when absent (the
     *  default) or unreadable. */
    private static int backdrop(PDSoftMask sm) {
        COSArray bc = sm.getBackdropColor();
        if (bc == null || bc.size() == 0) return 0xFF000000;
        try {
            float[] c = new float[bc.size()];
            for (int i = 0; i < c.length; i++) c[i] = bc.getObject(i) instanceof COSNumber num ? num.floatValue() : 0;
            var attrs = sm.getGroup() == null ? null : sm.getGroup().getGroup();
            PDColorSpace cs = attrs == null ? null : attrs.getColorSpace();
            if (cs == null || cs.getNumberOfComponents() != c.length)       // no group space: the device one of that size
                cs = switch (c.length) { case 1 -> PDDeviceGray.INSTANCE; case 3 -> PDDeviceRGB.INSTANCE; case 4 -> PDDeviceCMYK.INSTANCE; default -> null; };
            if (cs == null) return 0xFF000000;
            float[] rgb = cs.toRGB(c);
            return 0xFF000000 | (Math.round(rgb[0] * 255) << 16) | (Math.round(rgb[1] * 255) << 8) | Math.round(rgb[2] * 255);
        } catch (Exception e) {
            JxLog.debug(SoftMasks.class, "soft-mask backdrop unreadable", e);
            return 0xFF000000;
        }
    }

    /** {@code /TR} sampled on 256 entries, or null for the identity (absent, {@code /Identity}, unreadable). */
    private static int[] transfer(PDSoftMask sm) {
        try {
            PDFunction f = sm.getTransferFunction();
            if (f == null) return null;
            int[] lut = new int[256];
            for (int i = 0; i < 256; i++) {
                float[] out = f.eval(new float[] { i / 255f });
                lut[i] = Math.max(0, Math.min(255, Math.round(out[0] * 255)));
            }
            return lut;
        } catch (Exception e) {
            JxLog.debug(SoftMasks.class, "soft-mask transfer function unreadable", e);
            return null;
        }
    }
}
