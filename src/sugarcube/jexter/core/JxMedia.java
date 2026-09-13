package sugarcube.jexter.core;

import java.util.Set;

/**
 * Dependency-free metadata probe for the two media containers OCD embeds: MP4
 * (video) and MP3 (audio). Reads only structural headers — never decodes a
 * sample — to recover intrinsic {@code width}/{@code height} (video) and
 * {@code duration} in seconds. Anything it cannot determine comes back as 0.
 *
 * <ul>
 *   <li><b>MP4</b> walks the ISO-BMFF box tree: {@code moov/trak/tkhd} carries the
 *       track display width/height (16.16 fixed-point, last 8 bytes of the box);
 *       {@code moov/mvhd} carries timescale + duration.</li>
 *   <li><b>MP3</b> skips an ID3v2 tag, parses the first MPEG audio frame header
 *       (version, layer, bitrate, sample rate); uses a Xing/Info VBR frame count
 *       when present, else a CBR estimate from the byte length.</li>
 * </ul>
 */
public final class JxMedia {

    private JxMedia() {}

    /** width/height in pixels (0 for audio), duration in seconds (0 if unknown). */
    public record Probe(int width, int height, double seconds) {
        public static final Probe NONE = new Probe(0, 0, 0);
    }

    public static Probe probe(String ext, byte[] data) {
        if (data == null || ext == null) return Probe.NONE;
        return switch (ext.toLowerCase()) {
            case "mp4", "m4v", "mov" -> mp4(data);
            case "mp3" -> mp3(data);
            default -> Probe.NONE;
        };
    }

    // ── MP4 / ISO-BMFF ────────────────────────────────────────────────────────

    private static final Set<String> CONTAINERS =
            Set.of("moov", "trak", "mdia", "minf", "stbl", "udta", "edts");

    private static int u32(byte[] b, int i) {
        return ((b[i] & 0xff) << 24) | ((b[i + 1] & 0xff) << 16) | ((b[i + 2] & 0xff) << 8) | (b[i + 3] & 0xff);
    }
    private static long u64(byte[] b, int i) {
        return ((long) u32(b, i) << 32) | (u32(b, i + 4) & 0xffffffffL);
    }

    private static Probe mp4(byte[] b) {
        int[] wh = {0, 0};
        double[] dur = {0};
        walk(b, 0, b.length, wh, dur);
        return new Probe(wh[0], wh[1], dur[0]);
    }

    private static void walk(byte[] b, int start, int end, int[] wh, double[] dur) {
        int p = start;
        while (p + 8 <= end) {
            long size = u32(b, p) & 0xffffffffL;
            String type = new String(b, p + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) { if (p + 16 > end) return; size = u64(b, p + 8); hdr = 16; }
            else if (size == 0) size = end - p;
            if (size < hdr || p + size > end) return;
            int payload = p + hdr, payEnd = (int) (p + size);

            if (CONTAINERS.contains(type)) walk(b, payload, payEnd, wh, dur);
            else if (type.equals("tkhd") && payEnd - payload >= 8) {
                int w = u32(b, payEnd - 8) >>> 16;        // 16.16 fixed-point
                int h = u32(b, payEnd - 4) >>> 16;
                if ((long) w * h > (long) wh[0] * wh[1]) { wh[0] = w; wh[1] = h; }  // keep the video track
            } else if (type.equals("mvhd") && payEnd - payload >= 20) {
                int ver = b[payload] & 0xff;
                if (ver == 1 && payEnd - payload >= 28) {
                    long ts = u32(b, payload + 20) & 0xffffffffL, d = u64(b, payload + 24);
                    if (ts > 0) dur[0] = (double) d / ts;
                } else {
                    long ts = u32(b, payload + 12) & 0xffffffffL, d = u32(b, payload + 16) & 0xffffffffL;
                    if (ts > 0) dur[0] = (double) d / ts;
                }
            }
            p = payEnd;
        }
    }

    // ── MP3 / MPEG audio ────────────────────────────────────────────────────────

    private static final int[][] BITRATE = {  // [versionGroup][bitrateIndex] kbps, Layer III
        {0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,0},  // MPEG1 L3
        {0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,0}       // MPEG2/2.5 L3
    };
    private static final int[][] SAMPLERATE = {
        {44100,48000,32000,0},  // MPEG1
        {22050,24000,16000,0},  // MPEG2
        {11025,12000,8000,0}    // MPEG2.5
    };

    private static Probe mp3(byte[] b) {
        int off = 0;
        if (b.length >= 10 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {       // skip ID3v2
            int sz = ((b[6] & 0x7f) << 21) | ((b[7] & 0x7f) << 14) | ((b[8] & 0x7f) << 7) | (b[9] & 0x7f);
            off = 10 + sz;
        }
        // first frame sync
        int i = off;
        while (i + 4 <= b.length && !((b[i] & 0xff) == 0xff && (b[i + 1] & 0xe0) == 0xe0)) i++;
        if (i + 4 > b.length) return Probe.NONE;

        int verBits = (b[i + 1] >> 3) & 3;                  // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
        int brIdx = (b[i + 2] >> 4) & 0xf, srIdx = (b[i + 2] >> 2) & 3;
        int vg = verBits == 3 ? 0 : 1;
        int srGroup = verBits == 3 ? 0 : verBits == 2 ? 1 : 2;
        int bitrate = BITRATE[vg][brIdx] * 1000;
        int sampleRate = SAMPLERATE[srGroup][srIdx];
        if (sampleRate == 0) return Probe.NONE;
        int spf = verBits == 3 ? 1152 : 576;                // samples per frame, Layer III

        // Xing / Info VBR header → exact frame count
        int xing = indexOf(b, i, Math.min(b.length, i + 200), "Xing");
        if (xing < 0) xing = indexOf(b, i, Math.min(b.length, i + 200), "Info");
        if (xing >= 0 && xing + 12 <= b.length) {
            int flags = u32(b, xing + 4);
            if ((flags & 1) != 0) {
                long frames = u32(b, xing + 8) & 0xffffffffL;
                return new Probe(0, 0, (double) frames * spf / sampleRate);
            }
        }
        // CBR estimate
        double sec = bitrate > 0 ? (double) (b.length - off) * 8 / bitrate : 0;
        return new Probe(0, 0, sec);
    }

    private static int indexOf(byte[] b, int from, int to, String tag) {
        byte[] t = tag.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        outer:
        for (int i = from; i <= to - t.length; i++) {
            for (int j = 0; j < t.length; j++) if (b[i + j] != t[j]) continue outer;
            return i;
        }
        return -1;
    }
}
