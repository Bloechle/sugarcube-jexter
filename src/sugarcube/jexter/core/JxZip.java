package sugarcube.jexter.core;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The one ZIP-container writer for all our OPC/EPUB-style formats (.ocd.epub, .epub).
 * Owns the conventions every container shares: a first, STORED {@code mimetype}
 * entry (so the type is sniffable without inflating), then DEFLATED entries.
 */
public final class JxZip implements Closeable {

    private final ZipOutputStream zip;

    public JxZip(OutputStream out) { this.zip = new ZipOutputStream(out); zip.setLevel(6); }
    public JxZip(File file) throws IOException { this(new BufferedOutputStream(new FileOutputStream(file))); }

    /** Must be the first entry. STORED, uncompressed. */
    public JxZip mimetype(String mime) throws IOException {
        return stored("mimetype", mime.getBytes(StandardCharsets.US_ASCII));
    }

    /** Every entry is stamped by the one clock authority ({@link JxClock}), so a pinned
     *  {@code SOURCE_DATE_EPOCH} makes the whole container byte-reproducible. */
    private static ZipEntry entry(String name) {
        ZipEntry e = new ZipEntry(name);
        e.setTimeLocal(java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(JxClock.millis()), java.time.ZoneOffset.UTC));
        return e;
    }

    public JxZip stored(String name, byte[] data) throws IOException {
        ZipEntry e = entry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 crc = new CRC32(); crc.update(data); e.setCrc(crc.getValue());
        zip.putNextEntry(e); zip.write(data); zip.closeEntry();
        return this;
    }

    public JxZip deflated(String name, byte[] data) throws IOException {
        zip.putNextEntry(entry(name));
        zip.write(data);
        zip.closeEntry();
        return this;
    }

    public JxZip deflated(String name, String text) throws IOException {
        return deflated(name, text.getBytes(StandardCharsets.UTF_8));
    }

    @Override public void close() throws IOException { zip.close(); }
}
