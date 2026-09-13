package sugarcube.jexter.pdfimport;

import jj2000.j2k.codestream.HeaderInfo;
import jj2000.j2k.codestream.reader.BitstreamReaderAgent;
import jj2000.j2k.codestream.reader.HeaderDecoder;
import jj2000.j2k.decoder.DecoderSpecs;
import jj2000.j2k.entropy.decoder.EntropyDecoder;
import jj2000.j2k.fileformat.reader.FileFormatReader;
import jj2000.j2k.image.BlkImgDataSrc;
import jj2000.j2k.image.Coord;
import jj2000.j2k.image.DataBlkInt;
import jj2000.j2k.image.ImgDataConverter;
import jj2000.j2k.image.invcomptransf.InvCompTransf;
import jj2000.j2k.io.RandomAccessIO;
import jj2000.j2k.quantization.dequantizer.Dequantizer;
import jj2000.j2k.roi.ROIDeScaler;
import jj2000.j2k.util.ISRandomAccessIO;
import jj2000.j2k.util.ParameterList;
import jj2000.j2k.wavelet.synthesis.InverseWT;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.color.*;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * JPEG 2000 ({@code JPXDecode}) images, decoded with the vendored JJ2000 codec.
 *
 * <p>PDFBox does not decode JPX on its own: it asks ImageIO for a "JPEG2000" reader and, when the
 * JAI Image I/O tools are not installed, throws {@code MissingImageReaderException}. The importer
 * used to lose the image there, silently — a full-page press advertisement simply gone, with
 * whatever sat underneath showing through.
 *
 * <p>This decoder produces the raw component <b>samples</b> and nothing else; the PDF colour space
 * is then applied by PDFBox itself ({@link PDColorSpace#toRGBImage}), so DeviceN, Separation,
 * Indexed, ICCBased and CMYK behave exactly as they do for every other codec — one authority for
 * colour, here as everywhere. What this class owns is the codestream, not the colour.
 *
 * <p>Limits, stated: no progressive delivery (JJ2000 signals it; we take the block as it is), and
 * component depths above 8 bits are scaled down to 8. A JPX image carrying its own colour space
 * with no {@code /ColorSpace} entry falls back to gray/RGB/CMYK by component count.
 */
final class JpxImage {

    private JpxImage() { }

    /** True when this image is a JPEG 2000 one. The declared filter is only the first half of the
     *  question: a portfolio page in the corpus declares {@code /FlateDecode} on an image PDFBox
     *  then hands to its JPX filter, so the BYTES have the last word — a JP2 container signature or
     *  a raw codestream SOC marker. */
    static boolean isJpx(PDImageXObject xo) {
        var filters = xo.getCOSObject().getFilters();
        if (filters != null && filters.toString().contains(COSName.JPX_DECODE.getName())) return true;
        byte[] head = codestream(xo);
        return head != null && looksJpx(head);
    }

    private static final byte[] JP2_SIGNATURE = { 0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20 };

    /** A JP2 container ("jP  " box) or a raw JPEG 2000 codestream (SOC + SIZ). */
    private static boolean looksJpx(byte[] b) {
        if (b.length >= 4 && (b[0] & 255) == 0xFF && (b[1] & 255) == 0x4F
                && (b[2] & 255) == 0xFF && (b[3] & 255) == 0x51) return true;
        if (b.length < JP2_SIGNATURE.length) return false;
        for (int i = 0; i < JP2_SIGNATURE.length; i++) if (b[i] != JP2_SIGNATURE[i]) return false;
        return true;
    }

    /** The stream with every filter BUT the JPX one applied — the codestream itself. */
    private static byte[] codestream(PDImageXObject xo) {
        try (var in = xo.getStream().createInputStream(java.util.List.of(COSName.JPX_DECODE.getName()))) {
            return in.readAllBytes();
        } catch (Exception e) { return null; }
    }

    /**
     * Everything this class can do for an image PDFBox refused, in one call.
     *
     * <p>Two shapes, and the second is the one that bites: the image itself is JPEG 2000, OR the
     * image is ordinary and its SOFT MASK is JPEG 2000. In the second case {@code getImage()} dies
     * on the mask and the whole image is lost — measured on a portfolio page whose base is plain
     * FlateDecode: two images gone, the page short of a binder and its shadow. The base is then
     * taken opaque, from PDFBox, and the mask decoded here.
     */
    static BufferedImage rescue(PDImageXObject xo) {
        BufferedImage jpxBase = decode(xo);              // the image itself is a codestream (mask applied inside)
        if (jpxBase != null) return jpxBase;
        try {
            PDImageXObject mask = xo.getSoftMask();
            if (mask == null || !isJpx(mask)) return null;   // not our failure to rescue
            BufferedImage opaque = xo.getOpaqueImage();
            return opaque == null ? null : masked(opaque, mask);
        } catch (Exception | StackOverflowError e) {
            return null;
        }
    }

    /** The image as RGB, or null when the codestream cannot be read. */
    static BufferedImage decode(PDImageXObject xo) {
        try {
            // Everything BUT the JPX filter is run: a chain like [FlateDecode, JPXDecode] must be
            // unwrapped down to the codestream, and no further.
            byte[] codestream = codestream(xo);
            if (codestream == null || !looksJpx(codestream)) return null;   // not ours to decode
            WritableRaster raster = raster(codestream);
            if (raster == null) return null;
            PDColorSpace cs = colorSpace(xo, raster.getNumBands());
            if (cs == null) return null;
            return masked(cs.toRGBImage(raster), xo.getSoftMask());
        } catch (Exception | StackOverflowError e) {   // a malformed codestream must cost the image, not the page
            return null;
        }
    }

    /**
     * The soft mask applied as alpha, because {@code getImage()} would have applied it and this
     * path replaces it whole. Measured on a portfolio page whose image AND its mask are both JPX:
     * ignoring the mask left an opaque rectangle over 1.2% of the page. The mask is a grayscale
     * image in its own right — often another JPX, so it goes through this same decoder — and its
     * size need not match, hence the sampling by ratio rather than by pixel.
     */
    private static BufferedImage masked(BufferedImage rgb, PDImageXObject mask) {
        if (rgb == null || mask == null) return rgb;
        BufferedImage m;
        try { m = mask.getImage(); }
        catch (IOException | RuntimeException unreadable) { m = isJpx(mask) ? decode(mask) : null; }
        if (m == null) return rgb;
        int w = rgb.getWidth(), h = rgb.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int mw = m.getWidth(), mh = m.getHeight();
        for (int y = 0; y < h; y++) {
            int my = mh == h ? y : Math.min(mh - 1, y * mh / h);
            for (int x = 0; x < w; x++) {
                int mx = mw == w ? x : Math.min(mw - 1, x * mw / w);
                int alpha = m.getRGB(mx, my) & 0xFF;               // gray: any channel is the value
                out.setRGB(x, y, (alpha << 24) | (rgb.getRGB(x, y) & 0xFFFFFF));
            }
        }
        return out;
    }

    /** The PDF's colour space, or one deduced from the component count when the PDF states none. */
    private static PDColorSpace colorSpace(PDImageXObject xo, int bands) {
        try {
            PDColorSpace cs = xo.getColorSpace();
            if (cs != null && cs.getNumberOfComponents() == bands) return cs;
        } catch (Exception ignored) { /* JPX may carry its own: fall through */ }
        return switch (bands) {
            case 1 -> PDDeviceGray.INSTANCE;
            case 3 -> PDDeviceRGB.INSTANCE;
            case 4 -> PDDeviceCMYK.INSTANCE;
            default -> null;
        };
    }

    /** Decode the codestream into one 8-bit band per component, tile by tile, line by line. */
    private static WritableRaster raster(byte[] codestream) throws IOException {
        RandomAccessIO in = new ISRandomAccessIO(new ByteArrayInputStream(codestream));
        FileFormatReader ff = new FileFormatReader(in);
        ff.readFileFormat();
        if (ff.JP2FFUsed) in.seek(ff.getFirstCodeStreamPos());   // raw codestream inside a JP2 wrapper

        ParameterList params = defaults();
        HeaderInfo header = new HeaderInfo();
        HeaderDecoder hd = new HeaderDecoder(in, params, header);
        DecoderSpecs specs = hd.getDecoderSpecs();
        int comps = hd.getNumComps();
        int[] depth = new int[comps];
        for (int i = 0; i < comps; i++) depth[i] = hd.getOriginalBitDepth(i);

        BitstreamReaderAgent breader = BitstreamReaderAgent.createInstance(in, hd, params, specs, false, header);
        EntropyDecoder entdec = hd.createEntropyDecoder(breader, params);
        ROIDeScaler roids = hd.createROIDeScaler(entdec, params, specs);
        Dequantizer deq = hd.createDequantizer(roids, depth, specs);
        InverseWT invWT = InverseWT.createInstance(deq, specs);
        invWT.setImgResLevel(breader.getImgRes());
        BlkImgDataSrc src = new InvCompTransf(new ImgDataConverter(invWT, 0), specs, depth, params);

        int w = src.getImgWidth(), h = src.getImgHeight(), n = src.getNumComps();
        if (w <= 0 || h <= 0 || n <= 0) return null;
        for (int c = 0; c < n; c++)                                  // one plane per component, same size
            if (src.getCompImgWidth(c) != w || src.getCompImgHeight(c) != h) return null;

        WritableRaster raster = Raster.createBandedRaster(DataBuffer.TYPE_BYTE, w, h, n, null);
        DataBlkInt[] blk = new DataBlkInt[n];
        int[] shift = new int[n], max = new int[n], fixed = new int[n];
        for (int c = 0; c < n; c++) {
            blk[c] = new DataBlkInt();
            shift[c] = 1 << (src.getNomRangeBits(c) - 1);            // DC level shift back to unsigned
            max[c] = (1 << src.getNomRangeBits(c)) - 1;
            fixed[c] = src.getFixedPoint(c);
        }

        Coord tiles = src.getNumTiles(null);
        int[] line = new int[w];
        for (int ty = 0, tile = 0; ty < tiles.y; ty++)
            for (int tx = 0; tx < tiles.x; tx++, tile++) {
                src.setTile(tx, ty);
                int tw = src.getTileCompWidth(tile, 0), th = src.getTileCompHeight(tile, 0);
                int ox = src.getCompULX(0) - (int) Math.ceil(src.getImgULX() / (double) src.getCompSubsX(0));
                int oy = src.getCompULY(0) - (int) Math.ceil(src.getImgULY() / (double) src.getCompSubsY(0));
                for (int y = 0; y < th; y++)
                    for (int c = 0; c < n; c++) {
                        blk[c].ulx = 0; blk[c].uly = y; blk[c].w = tw; blk[c].h = 1;
                        src.getInternCompData(blk[c], c);
                        int[] data = blk[c].data;
                        int k = blk[c].offset;
                        for (int x = 0; x < tw; x++, k++) {
                            int v = (data[k] >> fixed[c]) + shift[c];
                            v = v < 0 ? 0 : (v > max[c] ? max[c] : v);
                            line[x] = max[c] == 255 ? v : v * 255 / max[c];   // every band ends up 8-bit
                        }
                        int py = oy + y, px = ox;
                        if (py < 0 || py >= h) continue;
                        int len = Math.min(tw, w - px);
                        if (len > 0) raster.setSamples(px, py, len, 1, c, java.util.Arrays.copyOf(line, len));
                    }
            }
        return raster;
    }

    /**
     * JJ2000's own defaults, assembled the way its decoder does: every module contributes its
     * parameter table and its default value. Hand-listing a subset looks tidy and fails on the
     * first option a module reads and the list does not carry — measured: a missing "Cer" threw
     * out of {@code createEntropyDecoder} and cost the image.
     */
    private static ParameterList defaults() {
        ParameterList p = new ParameterList();
        for (String[][] table : new String[][][] {
                BitstreamReaderAgent.getParameterInfo(),
                EntropyDecoder.getParameterInfo(),
                ROIDeScaler.getParameterInfo(),
                Dequantizer.getParameterInfo(),
                InvCompTransf.getParameterInfo(),
                HeaderDecoder.getParameterInfo(),
                jj2000.icc.ICCProfiler.getParameterInfo(),
        }) {
            if (table == null) continue;
            for (String[] def : table) if (def[3] != null) p.put(def[0], def[3]);
        }
        // The handful the decoder itself owns (jj2000.j2k.decoder.Decoder's own pinfo), defaults only.
        p.put("rate", "-1");
        p.put("nbytes", "-1");
        p.put("parsing", "on");
        p.put("ncb_quit", "-1");
        p.put("l_quit", "-1");
        p.put("m_quit", "-1");
        p.put("poc_quit", "off");
        p.put("one_tp", "off");
        p.put("comp_transf", "on");
        p.put("cdstr_info", "off");
        p.put("nocolorspace", "off");
        p.put("colorspace_debug", "off");
        return p;
    }
}
