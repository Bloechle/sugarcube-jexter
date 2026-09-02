package sugarcube.jexter.core;

/**
 * The one clock authority for every timestamp that reaches <b>output bytes</b> — structure
 * provenance ({@code at}), zip entry times, EPUB {@code dcterms:modified} fallbacks, PDF dates.
 *
 * <p>Honors the reproducible-builds convention: when the {@code SOURCE_DATE_EPOCH} environment
 * variable is set (seconds since the epoch), every such timestamp is pinned to it, and two runs
 * over the same input produce byte-identical artifacts. Unset, this is the wall clock — real
 * provenance is the correct default; reproducibility is opt-in, exactly as in gcc or dpkg.
 *
 * <p>Runtime bookkeeping (elapsed-time measurements, idle timers, log lines) deliberately stays on
 * {@link System#currentTimeMillis()} — pinning it would be wrong, and it never reaches an artifact.
 */
public final class JxClock {

    private JxClock() {}

    private static final Long PINNED = parse(System.getenv("SOURCE_DATE_EPOCH"));

    private static Long parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Milliseconds for output-facing timestamps: {@code SOURCE_DATE_EPOCH} when set, else now. */
    public static long millis() { return PINNED != null ? PINNED * 1000L : System.currentTimeMillis(); }

    /** True when {@code SOURCE_DATE_EPOCH} pins the clock (reproducible mode). */
    public static boolean pinned() { return PINNED != null; }
}
