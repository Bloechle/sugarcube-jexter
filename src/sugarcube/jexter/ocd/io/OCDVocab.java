package sugarcube.jexter.ocd.io;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The OCD serialization vocabulary shared by the OCD-EPUB writers and {@link OCDReader}: the format version, the
 * container's member names, canonical page ids and the cover key. One spelling of each, so the writers and the
 * reader stay in lock-step.
 */
public final class OCDVocab {

    private OCDVocab() {}

    /** THE format version this engine writes — pages, {@code fonts.svg} and {@code ocd/meta.json} all state it,
     *  from here. 4 since 2026-10-19: {@code meta.json} states the version and keys its cover to page 1, a radial
     *  gradient carries its focal radius ({@code fr}) and its two extends ({@code data-extend}), and the v2/v3
     *  tolerances (the rotation carrier, {@code data-crop}, hand-authored clips) are gone (FORMAT, Versions). */
    public static final String VERSION = "4";

    /** Whether this reader understands a container that states {@code v}: its own version, and no other. An
     *  older one is REFUSED like a newer one — the format is in full development and a container is reconverted
     *  from its source, never half-read; a missing statement is refused too. */
    public static boolean readable(String v) { return VERSION.equals(v); }

    /** Canonical page id from a 0-based index: {@code 0 → "p1"}. */
    public static String pageId(int index) { return "p" + (index + 1); }

    /** The package directory every member lives under (FORMAT §A2). */
    public static final String OPF = "OEBPS/";
    /** The model's own members ({@code meta.json}, {@code outline.json}…) — named for the FORMAT, not the engine. */
    public static final String MEMBERS = OPF + "ocd/";

    /** The page MEMBER's file stem from a 1-based number: {@code 1 → "page-001"} — the one spelling of a
     *  page name that the EPUB writers, the reader and the links all ask for.
     *
     *  <p>THREE digits, deliberately. Order is stated by the SPINE — {@code OCDReader} reads it — never by
     *  these names, which is what a 1001-page book proved: at three digits {@code page-1000} sorts between
     *  {@code page-100} and {@code page-101}. Widening the pad would rename every member of every big
     *  container for a case the spine already answers. */
    public static String pageFile(int oneBased) { return String.format("page-%03d", oneBased); }

    /** A page as the package names it, relative to {@link #OPF}: {@code 1 → "pages/page-001.xhtml"}. */
    public static String pageHref(int oneBased) { return "pages/" + pageFile(oneBased) + ".xhtml"; }

    /** A page's zip path: {@code 1 → "OEBPS/pages/page-001.xhtml"}. */
    public static String pagePath(int oneBased) { return OPF + pageHref(oneBased); }

    /** A link from one page to another, relative to the page itself (they are siblings): {@code "page-006.xhtml"}. */
    public static String pageLink(int index) { return pageFile(index + 1) + ".xhtml"; }

    /** Inverse of {@link #pageLink}: the 0-based page an internal link names, or -1 when {@code href} is not one. */
    public static int linkedPage(String href) {
        if (href == null || !href.startsWith("page-") || !href.endsWith(".xhtml")) return -1;
        try { return Integer.parseInt(href.substring(5, href.length() - 6)) - 1; } catch (NumberFormatException e) { return -1; }
    }

    /** Is this zip entry a page member? The reader's half of {@link #pagePath}. */
    public static boolean isPageMember(String entry) {
        return entry != null && entry.startsWith(OPF + "pages/page-") && entry.endsWith(".xhtml");
    }

    /** {@code meta.json}'s {@code cover} (FORMAT §A2): the page-1 member the cover was rasterized from, as
     *  {@code "sha256:<hex>"} of its bytes. A cover is kept on read only while its page is still that page. */
    public static String coverKey(byte[] page1) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(page1));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Inverse of {@link #pageId}: {@code "p1" → 0}. Returns -1 when absent/malformed. */
    public static int pageIndex(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != 'p') return -1;
        try { return Integer.parseInt(s.substring(1)) - 1; } catch (NumberFormatException e) { return -1; }
    }
}
