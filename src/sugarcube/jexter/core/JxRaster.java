package sugarcube.jexter.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * The one decode of a stored picture, and it is bounded.
 *
 * <p>A PNG states its size in its header and a few bytes of deflate can back it: 1.5 MB declaring
 * 40000 × 40000 pixels decodes to 6.4 GB. The engine stores pictures as they come (a scan byte for byte, a
 * container's images as given) and decodes them later — to render, to export, to redact — so the size is read
 * from the HEADER first, and a picture past {@link #MAX_PIXELS} is refused with its dimensions instead of
 * being decoded. A 600 dpi A3 scan is about 70 million pixels.
 */
public final class JxRaster {

    private JxRaster() {}

    /** The most pixels a picture may declare. */
    public static final long MAX_PIXELS = 100_000_000L;

    /** Refuse {@code w × h} past {@link #MAX_PIXELS}, naming {@code what}. */
    public static void check(long w, long h, String what) throws IOException {
        if (w * h > MAX_PIXELS)
            throw new IOException(what + " is " + w + " × " + h + " pixels, past the " + MAX_PIXELS / 1_000_000
                    + " million a picture may have — refused");
    }

    /** Width and height from the header alone, or null when no reader knows the bytes. */
    public static int[] dims(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> it = in == null ? null : ImageIO.getImageReaders(in);
            if (it == null || !it.hasNext()) return null;
            ImageReader r = it.next();
            try { r.setInput(in); return new int[]{ r.getWidth(0), r.getHeight(0) }; }
            finally { r.dispose(); }
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** The picture decoded, after its header passed {@link #check}; null when no reader knows the bytes. */
    public static BufferedImage decode(byte[] data, String what) throws IOException {
        int[] wh = dims(data);
        if (wh == null) return null;
        check(wh[0], wh[1], what);
        return ImageIO.read(new ByteArrayInputStream(data));
    }
}
