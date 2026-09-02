package sugarcube.jexter.core;

/** The single XML-escaping rule for hand-built string output (the templated writers). */
public final class JxText {
    private JxText() {}

    /** Escape for element text: {@code & < >}. */
    public static String text(CharSequence s) { return esc(s, false); }

    /** Collapse all whitespace runs to single spaces and trim — the one text-normalisation helper. */
    public static String collapse(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").strip(); }

    /** Does this text read RIGHT to LEFT? Arabic, Hebrew, Syriac, Thaana. Strong characters only —
     *  digits, punctuation and spaces are neutral and take the direction of what surrounds them, so a run
     *  of "1998" is called neither way. A mixed run answers by majority. */
    public static boolean isRtl(String s) {
        if (s == null || s.isEmpty()) return false;
        int rtl = 0, ltr = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            switch (Character.getDirectionality(cp)) {
                case Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                     Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> rtl++;
                case Character.DIRECTIONALITY_LEFT_TO_RIGHT -> ltr++;
                default -> { }                                    // neutral: takes its side from context
            }
        }
        return rtl > ltr;
    }

    /** Is this text the kind the GEOMETRIC rules were written for — left to right, words parted by spaces?
     *
     *  <p>THE precondition of every rule that reads a horizontal gap as meaning: the ordering of runs on a
     *  line, the slack that becomes a word space, the space that seals a line's end. Each is a statement
     *  about a left-to-right alphabetic flow, and outside it they do not merely mis-order — they corrupt:
     *  a line-ending space belongs on the rightmost run in Latin and on the leftmost in Hebrew, and putting
     *  it on the wrong one drops a space into the middle of a word (measured: ירושלים split in two). CJK
     *  parts no words with spaces at all, so a gap rule there either invents a space between every pair of
     *  glyphs or none, and both are wrong.
     *
     *  <p>The answer is NOT to guess harder: proper bidi and CJK line handling are their own work. Until
     *  then the rules simply stand back — the runs keep the order and the text the producer gave them,
     *  which is the one thing certain to be right. Say no rather than corrupt. */
    public static boolean isLtrWordScript(String s) {
        if (s == null || s.isEmpty()) return true;                // nothing to get wrong
        if (isRtl(s)) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) return false;
        }
        return true;
    }

    /** CJK and kana: scripts that mark no word boundary, so no gap can be read as one. */
    private static boolean isCjk(int cp) {
        return (cp >= 0x3040 && cp <= 0x30FF)      // hiragana, katakana
            || (cp >= 0x3400 && cp <= 0x4DBF)      // CJK ext A
            || (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK unified
            || (cp >= 0xF900 && cp <= 0xFAFF)      // compatibility ideographs
            || (cp >= 0xAC00 && cp <= 0xD7AF)      // hangul syllables
            || (cp >= 0x20000 && cp <= 0x2FA1F);   // CJK ext B..
    }
    public static void   text(StringBuilder b, CharSequence s) { esc(b, s, false); }

    /** Escape for an attribute value: {@code & < > "}. */
    public static String attr(CharSequence s) { return esc(s, true); }

    private static String esc(CharSequence s, boolean quotes) {
        if (s == null) return "";
        var b = new StringBuilder(s.length());
        esc(b, s, quotes);
        return b.toString();
    }

    /** XML 1.0 valid character: 0x9 | 0xA | 0xD | [0x20-0xD7FF] | [0xE000-0xFFFD]. Lone surrogates
     *  and the 0xFFFE/0xFFFF noncharacters are invalid; CID fonts without a usable ToUnicode map
     *  produce exactly these (U+0000, U+FFFF). Supplementary planes pass as surrogate pairs. */
    public static boolean xmlChar(char c) {
        return c == '\t' || c == '\n' || c == '\r'
            || (c >= 0x20 && c <= 0xD7FF) || (c >= 0xD800 && c <= 0xDFFF)   // pair halves — validated below
            || (c >= 0xE000 && c <= 0xFFFD);
    }

    /** Replace every XML-invalid char (and lone surrogate) by U+FFFD — length preserved, so the
     *  glyph↔char alignment of OCD text runs survives. The one sanitizer for model-boundary text. */
    public static String sanitize(String s) {
        if (s == null || s.isEmpty()) return "";
        for (int i = 0; i < s.length(); i++) if (!valid(s, i)) {
            var b = new StringBuilder(s.length());
            for (int j = 0; j < s.length(); j++) b.append(valid(s, j) ? s.charAt(j) : '\uFFFD');
            return b.toString();
        }
        return s;
    }

    private static boolean valid(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c))  return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
        return xmlChar(c);
    }

    private static void esc(StringBuilder b, CharSequence s, boolean quotes) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!xmlChar(c) && !Character.isSurrogate(c)) continue;          // safety net at the serializer
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> { if (quotes) b.append("&quot;"); else b.append(c); }
                default  -> b.append(c);
            }
        }
    }
}
