package sugarcube.jexter.core;

import java.util.regex.Pattern;

/**
 * The single canonical vocabulary of list-item markers. Both structure <i>detection</i>
 * (the analysis layer: line splitting, item recognition, ordered-vs-bulleted) and marker
 * <i>stripping</i> (the writers, where list-ness is carried by the tag/prefix and the content
 * must not repeat the bullet) derive from the one definition here, so the two can never drift.
 *
 * <p>A marker is a bullet glyph, a bare 1–3 digit number, or a short alphanumeric enumerator
 * such as {@code a.} {@code iv)} {@code (b)}.
 */
public final class ListMarker {

    private ListMarker() {}

    private static final String BULLET = "[\u2022\u2023\u25E6\u2043\u00B7\u2219\u2027*\u2013\u2014-]";
    private static final String MARKER = "(" + BULLET + "|\\d{1,3}|\\(?\\p{Alnum}{1,3}[.)])";

    private static final Pattern TOKEN    = Pattern.compile("^" + MARKER + "$");          // the whole token IS a marker
    private static final Pattern LINE     = Pattern.compile("^\\s*" + MARKER + "\\s+.*");  // line starts with a marker
    private static final Pattern LEADING  = Pattern.compile("^\\s*" + MARKER + "\\s+");    // the marker to strip off
    private static final Pattern BULLETED = Pattern.compile("^\\s*" + BULLET + "\\s+.*");  // marker is a bullet glyph

    /** {@code true} when {@code s} is a standalone marker token (used to split lines). */
    public static boolean isToken(String s)    { return s != null && TOKEN.matcher(s.strip()).matches(); }

    /** {@code true} when {@code s} is a line that begins with a marker followed by content. */
    public static boolean isItemLine(String s) { return s != null && LINE.matcher(s).matches(); }

    /** {@code true} when an item is enumerated (1. a) iv. …) rather than bulleted → its list is ordered. */
    public static boolean enumerated(String s) { return s != null && !BULLETED.matcher(s).matches(); }

    /** {@code s} with its leading marker removed (content carries no bullet). */
    public static String strip(String s)       { return s == null ? "" : LEADING.matcher(s).replaceFirst("").strip(); }
}
