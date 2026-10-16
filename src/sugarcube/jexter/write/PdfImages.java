package sugarcube.jexter.write;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The model's lossless images (PNG bytes) written back as PDF image XObjects that every reader decodes
 * the same way: 8-bit DeviceRGB or DeviceGray samples, an alpha channel as a separate {@code /SMask},
 * Flate with the PNG predictor restricted to ONE row filter, Sub.
 *
 * <p>Why not hand the PNG to PDFBox ({@code createFromByteArray})? Because the predictor rows then keep
 * the filters the PNG encoder chose — measured on a press page, three quarters of the rows Paeth, the rest
 * Up, Average and None. The PDF is correct and Acrobat, PDFBox, poppler and MuPDF agree on it; a
 * downstream PDF→OCD converter did not, and painted flat colours as drifting cyan and magenta stripes —
 * the signature of a Paeth or Average decoder that is subtly wrong. The source it read correctly used Sub
 * on every row (the iText encoder). Sub is the one filter that needs nothing but the current row, so it is
 * the one every decoder gets right; it costs a little compression on photographs and nothing on the flat
 * areas where the stripes showed.
 *
 * <p>A black-and-white image (a 1-bit PNG: every bilevel scan, every TIFF G4 page re-encoded at import)
 * is written as it always was, 1-bit DeviceGray, Flate, no predictor — 24× less data than RGB, and no row
 * filter at all to get wrong.
 *
 * <p>JPEG bytes are not touched: they go through PDFBox as before and stay DCT. A 16-bit PNG goes through
 * PDFBox too, which writes it 8-bit DeviceRGB without a predictor — the behaviour before this class.
 */
final class PdfImages {

    private PdfImages() {}

    /** The XObject for one model image: JPEG passes through, PNG is re-encoded as above, anything else
     *  falls back to PDFBox's own reading of the bytes. */
    static PDImageXObject create(PDDocument pdf, byte[] bytes, String name) throws IOException {
        if (isPng(bytes)) {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi != null && bilevel(bi)) return bilevelImage(pdf, bi);
            if (bi != null && eightBit(bi)) return lossless(pdf, bi);
        }
        return PDImageXObject.createFromByteArray(pdf, bytes, name);
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /** Every band 8 bits deep — what {@link #lossless} writes. A 16-bit image keeps PDFBox's own path. */
    private static boolean eightBit(BufferedImage bi) {
        for (int bits : bi.getColorModel().getComponentSize()) if (bits != 8) return false;
        return true;
    }

    /** A 1-bit image, opaque: a 1-bit grey PNG, or a palette of at most two entries that are black and
     *  white. Decided from the PNG's own colour model, never from the pixels — an RGB image that merely
     *  looks black and white stays RGB, because a press reader with a CMYK output intent paints DeviceGray
     *  (black only) and DeviceRGB (rich black) differently. */
    private static boolean bilevel(BufferedImage bi) {
        ColorModel cm = bi.getColorModel();
        if (cm.hasAlpha()) return false;
        if (cm instanceof IndexColorModel icm) {
            if (icm.getMapSize() > 2 || icm.getTransparentPixel() >= 0) return false;
            for (int i = 0; i < icm.getMapSize(); i++) {
                int rgb = icm.getRGB(i) & 0xFFFFFF;
                if (rgb != 0 && rgb != 0xFFFFFF) return false;
            }
            return true;
        }
        return cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY && cm.getComponentSize(0) == 1;
    }

    /** 1-bit DeviceGray (1 = white, rows padded to a byte), Flate, no predictor. */
    private static PDImageXObject bilevelImage(PDDocument pdf, BufferedImage bi) throws IOException {
        int w = bi.getWidth(), h = bi.getHeight(), stride = (w + 7) / 8;
        COSStream st = pdf.getDocument().createCOSStream();
        st.setItem(COSName.TYPE, COSName.XOBJECT);
        st.setItem(COSName.SUBTYPE, COSName.IMAGE);
        st.setInt(COSName.WIDTH, w);
        st.setInt(COSName.HEIGHT, h);
        st.setInt(COSName.BITS_PER_COMPONENT, 1);
        st.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        int[] px = new int[w];
        byte[] row = new byte[stride];
        try (OutputStream out = st.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                bi.getRGB(0, y, w, 1, px, 0, w);
                java.util.Arrays.fill(row, (byte) 0);
                for (int x = 0; x < w; x++)
                    if ((px[x] & 0xFFFFFF) == 0xFFFFFF) row[x >> 3] |= (byte) (0x80 >>> (x & 7));
                out.write(row);
            }
        }
        return new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(st), null);
    }

    private static PDImageXObject lossless(PDDocument pdf, BufferedImage bi) throws IOException {
        int w = bi.getWidth(), h = bi.getHeight();
        ColorModel cm = bi.getColorModel();
        boolean gray = cm.getNumColorComponents() == 1 && cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY;
        boolean alpha = cm.hasAlpha();
        int n = gray ? 1 : 3;
        byte[] samples = new byte[w * h * n];
        byte[] mask = alpha ? new byte[w * h] : null;
        boolean translucent = false;
        if (gray && !cm.isAlphaPremultiplied()) {
            // Grey is read from the raster, never through getRGB: Java's grey space is LINEAR, so getRGB
            // would re-encode every sample to sRGB and lighten the image. The PNG's samples ARE the values.
            var r = bi.getRaster();
            int[] g = new int[w], a = alpha ? new int[w] : null;
            for (int y = 0, s = 0, m = 0; y < h; y++) {
                r.getSamples(0, y, w, 1, 0, g);
                if (alpha) r.getSamples(0, y, w, 1, 1, a);
                for (int x = 0; x < w; x++) {
                    samples[s++] = (byte) g[x];
                    if (alpha) { mask[m++] = (byte) a[x]; translucent |= a[x] != 255; }
                }
            }
        } else {
            n = 3; samples = gray ? new byte[w * h * 3] : samples; gray = false;
            int[] row = new int[w];
            for (int y = 0, s = 0, m = 0; y < h; y++) {
                bi.getRGB(0, y, w, 1, row, 0, w);   // sRGB, un-premultiplied, whatever the PNG's own layout
                for (int x = 0; x < w; x++) {
                    int argb = row[x];
                    samples[s++] = (byte) (argb >> 16); samples[s++] = (byte) (argb >> 8); samples[s++] = (byte) argb;
                    if (alpha) { int a = argb >>> 24; mask[m++] = (byte) a; translucent |= a != 255; }
                }
            }
        }
        COSStream image = stream(pdf, samples, w, h, n, gray ? COSName.DEVICEGRAY : COSName.DEVICERGB);
        if (translucent) image.setItem(COSName.SMASK, stream(pdf, mask, w, h, 1, COSName.DEVICEGRAY));
        return new PDImageXObject(new org.apache.pdfbox.pdmodel.common.PDStream(image), null);
    }

    /** One 8-bit image stream: Flate, PNG predictor 15, every row filtered with Sub (type 1). */
    private static COSStream stream(PDDocument pdf, byte[] samples, int w, int h, int n, COSName space) throws IOException {
        COSStream st = pdf.getDocument().createCOSStream();
        st.setItem(COSName.TYPE, COSName.XOBJECT);
        st.setItem(COSName.SUBTYPE, COSName.IMAGE);
        st.setInt(COSName.WIDTH, w);
        st.setInt(COSName.HEIGHT, h);
        st.setInt(COSName.BITS_PER_COMPONENT, 8);
        st.setItem(COSName.COLORSPACE, space);
        int stride = w * n;
        byte[] rowOut = new byte[stride + 1];
        rowOut[0] = 1;   // Sub
        try (OutputStream out = st.createOutputStream(COSName.FLATE_DECODE)) {
            for (int y = 0; y < h; y++) {
                int o = y * stride;
                for (int i = 0; i < stride; i++)
                    rowOut[i + 1] = (byte) (samples[o + i] - (i >= n ? samples[o + i - n] : 0));
                out.write(rowOut);
            }
        }
        COSDictionary parms = new COSDictionary();
        parms.setDirect(true);   // inline in the image, as every producer writes it — not one object each
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, n);
        parms.setInt(COSName.BITS_PER_COMPONENT, 8);
        parms.setInt(COSName.COLUMNS, w);
        st.setItem(COSName.DECODE_PARMS, parms);
        return st;
    }
}
