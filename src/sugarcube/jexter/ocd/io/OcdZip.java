package sugarcube.jexter.ocd.io;

import java.io.Closeable;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An OCD-EPUB as the reader opens it: the ONE door to its members, and that door is bounded.
 *
 * <p>A container is attacker-controlled input — a host converts whatever {@code PK} file it is sent — and a
 * zip states sizes it does not have to honour: 1.5 MB of deflate inflates to 1.5 GB of zeros. So every byte
 * a member yields is counted as it inflates, against two ceilings, and the read is REFUSED with the member's
 * name the moment either is crossed: {@link #MEMBER_MAX} per member, {@link #CONTAINER_MAX} over the whole
 * read. The declared sizes are never trusted; the count is.
 *
 * <p>Legitimate members are far below both: images are stored in their own codec (PNG, JPEG), so they
 * inflate barely at all, and the largest member of a real book is its font sheet.
 */
final class OcdZip implements Closeable {

    /** The most one member may inflate to. */
    static final long MEMBER_MAX = 256L << 20;
    /** The most a whole read may inflate to, every member together. */
    static final long CONTAINER_MAX = 1L << 30;

    private final ZipFile zip;
    private long left = CONTAINER_MAX;

    OcdZip(File file) throws IOException { this.zip = new ZipFile(file); }

    /** The member at {@code name}, or null. */
    ZipEntry entry(String name) { return zip.getEntry(name); }

    /** Every member, in the zip's own order. */
    List<? extends ZipEntry> entries() { return Collections.list(zip.entries()); }

    /** The member's bytes, counted against both ceilings. */
    byte[] bytes(ZipEntry e) throws IOException {
        try (InputStream in = open(e)) { return in.readAllBytes(); }
    }

    /** The member as a stream that refuses to inflate past either ceiling. */
    InputStream open(ZipEntry e) throws IOException {
        return new FilterInputStream(zip.getInputStream(e)) {
            private long member;

            private void count(long n) throws IOException {
                if (n <= 0) return;
                member += n;
                left -= n;
                if (member > MEMBER_MAX)
                    throw new IOException("member " + e.getName() + " inflates past " + (MEMBER_MAX >> 20) + " MB — refused");
                if (left < 0)
                    throw new IOException("container inflates past " + (CONTAINER_MAX >> 20) + " MB at " + e.getName() + " — refused");
            }
            @Override public int read() throws IOException {
                int b = super.read();
                if (b >= 0) count(1);
                return b;
            }
            @Override public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                count(n);
                return n;
            }
            @Override public long skip(long n) throws IOException {
                long s = super.skip(n);
                count(s);
                return s;
            }
        };
    }

    @Override public void close() throws IOException { zip.close(); }
}
