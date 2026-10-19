package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.JxBlend;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.pdmodel.common.function.PDFunction;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceN;
import org.apache.pdfbox.pdmodel.graphics.color.PDSeparation;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;
import org.apache.pdfbox.util.Matrix;
import sugarcube.jexter.core.JxCmyk;
import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxTransform;
import sugarcube.jexter.ocd.model.OCDGradient;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * How a PDF's paint becomes the model's, where no graphics state is needed: the source CMYK behind a colour,
 * an axial or radial shading as an {@link OCDGradient}, a blend mode's name. The engine asks; it keeps only
 * what reads its own state.
 */
final class PdfColors {

    private PdfColors() {}

    /** PDF blend-mode name for a PDFBox {@link BlendMode} (null for Normal/unmapped). */
    static String blendName(BlendMode bm) { return bm == null ? null : JxBlend.model(bm.getCOSName().getName()); }

    /**
     * DeviceCMYK as it is; a Separation or DeviceN whose ALTERNATE is DeviceCMYK through its own tint
     * transform — which is what a spot colour means to any reader without that ink, and exactly what a
     * press subset carries. Everything else (ICC-based CMYK included: its profile would be lost by a
     * bare {@code k}) states no CMYK and keeps the sRGB alone.
     */
    static JxCmyk cmykOf(PDColorSpace cs, PDColor color, int argb) {
        float[] v = cmykComponents(cs, color);
        return v == null ? null : new JxCmyk(v[0], v[1], v[2], v[3], argb);
    }

    /** The paint as four CMYK components, when it is one (see {@link #cmykOf}); null otherwise. */
    static float[] cmykComponents(PDColorSpace cs, PDColor color) {
        try {
            float[] v = color == null ? null : color.getComponents();
            if (v == null) return null;
            if (cs instanceof PDDeviceCMYK) {
                if (v.length != 4) return null;
            } else if (cs instanceof PDSeparation sep) {
                if (!(sep.getAlternateColorSpace() instanceof PDDeviceCMYK)) return null;
                v = sep.getCOSObject() instanceof COSArray a ? tint(a, v) : null;
            } else if (cs instanceof PDDeviceN dn) {
                if (!(dn.getAlternateColorSpace() instanceof PDDeviceCMYK)) return null;
                v = dn.getTintTransform().eval(v);
            } else return null;
            return v == null || v.length != 4 ? null : v;
        } catch (Exception e) {
            return null;                                       // unreadable tint → sRGB only, as before
        }
    }

    /** A Separation's tint transform, read off its array: {@code [/Separation name alternate tintTransform]}. */
    static float[] tint(COSArray sep, float[] v) throws IOException {
        return PDFunction.create(sep.getObject(3)).eval(v);
    }

    /** Sample a PDF axial (type 2) / radial (type 3) shading into an {@link OCDGradient}. The
     *  colour ramp is sampled from the shading's function across its domain; {@code m} maps the
     *  shading's own coordinate space to page space. Returns null for other shading types (the
     *  caller rasterises those). */
    static OCDGradient gradientOf(PDShading sh, Matrix m, float alpha) {
        try {
            int type = sh.getShadingType();
            float[] co; float[] dom; COSArray ext;
            if (type == 2) {
                PDShadingType2 s = (PDShadingType2) sh;
                co = s.getCoords().toFloatArray(); dom = toArr(s.getDomain()); ext = s.getExtend();
            } else if (type == 3) {
                PDShadingType3 s = (PDShadingType3) sh;
                co = s.getCoords().toFloatArray(); dom = toArr(s.getDomain()); ext = s.getExtend();
            } else {
                return null;   // 1 (function-based) / 4–7 (mesh) → rasterise
            }
            double[] coords = new double[co.length];
            for (int i = 0; i < co.length; i++) coords[i] = co[i];
            float d0 = (dom != null && dom.length == 2) ? dom[0] : 0f;
            float d1 = (dom != null && dom.length == 2) ? dom[1] : 1f;
            boolean e0 = ext != null && ext.size() == 2 && ext.getObject(0) instanceof COSBoolean b0 && b0.getValue();
            boolean e1 = ext != null && ext.size() == 2 && ext.getObject(1) instanceof COSBoolean b1 && b1.getValue();

            PDColorSpace cs = sh.getColorSpace();
            int a8 = JxColor.unit(alpha);
            final int N = 33;                          // linear interpolation between 33 samples
            float[] offs = new float[N];
            int[]   cols = new int[N];
            for (int i = 0; i < N; i++) {
                float f = i / (float) (N - 1);
                float[] rgb = cs.toRGB(sh.evalFunction(new float[]{ d0 + (d1 - d0) * f }));
                cols[i] = JxColor.rgba(JxColor.unit(rgb[0]), JxColor.unit(rgb[1]), JxColor.unit(rgb[2]), a8).argb();
                offs[i] = f;
            }
            OCDGradient.Kind kind = (type == 2) ? OCDGradient.Kind.LINEAR : OCDGradient.Kind.RADIAL;
            OCDGradient g = new OCDGradient(kind, coords, offs, cols,
                    JxTransform.of(m.createAffineTransform()), e0, e1);
            return g.isValid() ? g : null;
        } catch (Exception e) {
            JxLog.debug(PdfColors.class, "gradient extraction failed \u2192 fallback", e);
            return null;
        }
    }

    static float[] toArr(COSArray a) { return a == null ? null : a.toFloatArray(); }
}
