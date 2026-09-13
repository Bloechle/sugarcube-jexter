package sugarcube.jexter.ocd.io;

/**
 * The OCD serialization vocabulary shared by the OCD-EPUB members ({@code OcdMembers}) and {@link OCDReader}: canonical page
 * ids and the self-describing names for render-mode, line-cap and line-join. Keeping the int↔name
 * mappings in one place guarantees the writer and reader stay in lock-step.
 */
public final class OCDVocab {

    private OCDVocab() {}

    /** Canonical page id from a 0-based index: {@code 0 → "p1"}. */
    public static String pageId(int index) { return "p" + (index + 1); }

    /** The page MEMBER's file stem from a 1-based number: {@code 1 → "page-001"}.
     *
     *  <p>THREE digits, deliberately. Order is stated by the SPINE — {@code OCDReader} reads it — never
     *  by these names, which is what a 1001-page book proved: at three digits {@code page-1000} sorts
     *  between {@code page-100} and {@code page-101}. Widening the pad would rename every member of
     *  every big container for a case the spine already answers, and the one place name order still
     *  decides is a container that states no spine, where a four-digit book is degenerate input.
     * The container names
     *  its pages {@code pages/page-NNN.xhtml} (FORMAT §A2) — a fact of the format, and one the two EPUB
     *  writers and the reader each used to state for themselves. Three copies of a name is how a
     *  four-digit book stops being readable by the tool that wrote it. */
    public static String pageFile(int oneBased) { return String.format("page-%03d", oneBased); }

    /** Is this zip entry a page member of {@code opfDir}? The reader's half of {@link #pageFile}. */
    public static boolean isPageMember(String entry, String opfDir) {
        return entry != null && entry.startsWith(opfDir + "pages/page-") && entry.endsWith(".xhtml");
    }

    /** Inverse of {@link #pageId}: {@code "p1" → 0}. Returns -1 when absent/malformed. */
    public static int pageIndex(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'p') return -1;
        try { return Integer.parseInt(s.substring(1)) - 1; } catch (NumberFormatException e) { return -1; }
    }

    // ── PDF text render mode (Tr) ↔ name ────────────────────────────────────────
    public static String mode(int m) {
        return switch (m) {
            case 1 -> "stroke"; case 2 -> "fillstroke"; case 3 -> "invisible";
            case 4 -> "fill-clip"; case 5 -> "stroke-clip"; case 6 -> "fillstroke-clip"; case 7 -> "clip";
            default -> "fill";
        };
    }
    public static int mode(String s) {
        return switch (s == null ? "" : s) {
            case "stroke" -> 1; case "fillstroke" -> 2; case "invisible" -> 3;
            case "fill-clip" -> 4; case "stroke-clip" -> 5; case "fillstroke-clip" -> 6; case "clip" -> 7;
            default -> 0;
        };
    }

    // ── stroke line cap ↔ name ──────────────────────────────────────────────────
    public static String cap(int c)   { return switch (c) { case 1 -> "round"; case 2 -> "square"; default -> "butt"; }; }
    public static int    cap(String s){ return switch (s == null ? "" : s) { case "round" -> 1; case "square" -> 2; default -> 0; }; }

    // ── stroke line join ↔ name ─────────────────────────────────────────────────
    public static String join(int j)   { return switch (j) { case 1 -> "round"; case 2 -> "bevel"; default -> "miter"; }; }
    public static int    join(String s){ return switch (s == null ? "" : s) { case "round" -> 1; case "bevel" -> 2; default -> 0; }; }
}
