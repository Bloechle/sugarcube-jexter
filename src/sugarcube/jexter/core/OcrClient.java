package sugarcube.jexter.core;

import java.io.IOException;
import java.util.List;

/**
 * Host-bindable façade for an OCR engine — the single seam through which <i>any</i> recognizer (a local
 * Tesseract, a cloud service, …) is plugged into the engine, mirroring {@link LlmClient}.
 *
 * <p>The engine stays dependency-free: the image importer only ever sees this interface, and a concrete
 * engine is installed by the host via {@link #bind}. When nothing is bound, an image is still imported —
 * as a page with no text layer — so OCR is never on the critical path of a conversion.
 *
 * <p>An engine answers WORDS, each with its box: the importer turns every word into an invisible run
 * over the image, and the geometry-first analysis rebuilds lines, spaces and reading order from those
 * boxes as it does for any other document. An engine that only reports lines or paragraphs is not
 * enough for a faithful searchable layer.
 */
public interface OcrClient {

    /**
     * One recognized word, in the image's own pixel space — origin at the TOP-left, y down, the convention
     * every OCR engine reports in (the importer converts to the y-up page space). {@code confidence} is in
     * {@code [0, 1]}, or {@code -1} when the engine reports none. {@code line} numbers the line the ENGINE
     * read the word in (words sharing it share a line; {@code -1} = the engine did not say): the importer
     * sets every word of a line at that line's height, so a line reads as one size, not one per word.
     */
    record Word(String text, JxRect box, double confidence, int line) {}

    /**
     * Recognize the words of one image (PNG or JPEG bytes). {@code lang} is a hint in the engine's own
     * vocabulary (e.g. {@code "fra+deu"} for Tesseract); {@code null} or blank leaves the engine's default.
     * Implementations may throw on transport or engine errors; the caller treats any throw as "no text
     * layer" and keeps the image.
     */
    List<Word> read(byte[] image, String lang) throws IOException;

    /** Short identifier of the underlying engine, for logs and provenance (e.g. {@code "tesseract"}). */
    default String engine() { return "ocr"; }

    // ── façade binding: the host installs an engine, the importer reads it ────────────────────────
    /** Holder for the process-wide bound client (interfaces cannot have instance state). */
    final class Holder { private static volatile OcrClient bound; private Holder() {} }

    /** Install the process-wide client (typically once, at startup, by the host or CLI). */
    static void      bind(OcrClient c) { Holder.bound = c; }
    /** The currently bound client, or {@code null} if none. */
    static OcrClient bound()           { return Holder.bound; }
    /** Whether a client is available. */
    static boolean   isBound()         { return Holder.bound != null; }
}
