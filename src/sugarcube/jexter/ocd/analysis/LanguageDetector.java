package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDText;

import java.lang.Character.UnicodeScript;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort BCP-47 primary-language guess from the page text, used <b>only</b> as a fallback
 * when the document declares no language (neither the catalog {@code /Lang} nor XMP {@code dc:language}).
 * It never overrides an explicit language, and it stays silent when unsure — a wrong language tag
 * (bad hyphenation, TTS, font choice) is worse than an undetermined one.
 *
 * <p>Two stages. <b>Script first</b>: a dominant non-Latin Unicode script is decisive and reliable
 * (Japanese is recognised by the presence of kana even amid Han, Korean by Hangul, etc.). <b>Latin
 * then</b>: short function-word ("stopword") frequency picks among the major Latin-script languages,
 * gated by a token-count floor and a margin over the runner-up. No model, no dependency, no network.
 */
public final class LanguageDetector {
    private LanguageDetector() {}

    private static final int SAMPLE_CHARS = 4000;
    private static final int MIN_LETTERS  = 24;
    private static final int MIN_TOKENS   = 30;
    private static final double MIN_FRACTION = 0.06;   // best lang's stopwords ≥ 6% of tokens
    private static final double MARGIN       = 1.25;   // best must clear 1.25× the runner-up

    /** Set {@code doc.meta().language()} from the text iff it is currently blank and a guess is confident. */
    public static void detect(OCDDocument doc) {
        if (doc.meta() == null || !doc.meta().language().isBlank()) return;
        String lang = guess(sample(doc));
        if (!lang.isEmpty()) doc.meta().language(lang);
    }

    /** A BCP-47 primary language subtag, or {@code ""} when undetermined. */
    public static String guess(String text) {
        if (text == null || text.isEmpty()) return "";

        // ── stage 1: script tally ──
        int latin = 0, cyrillic = 0, greek = 0, arabic = 0, hebrew = 0,
            han = 0, kana = 0, hangul = 0, devanagari = 0, thai = 0, letters = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            switch (UnicodeScript.of(cp)) {
                case LATIN      -> latin++;
                case CYRILLIC   -> cyrillic++;
                case GREEK      -> greek++;
                case ARABIC     -> arabic++;
                case HEBREW     -> hebrew++;
                case HAN        -> han++;
                case HIRAGANA, KATAKANA -> kana++;
                case HANGUL     -> hangul++;
                case DEVANAGARI -> devanagari++;
                case THAI       -> thai++;
                default -> { }
            }
        }
        if (letters < MIN_LETTERS) return "";

        // a clearly dominant non-Latin script decides directly
        int nonLatin = letters - latin;
        if (nonLatin > latin) {
            if (kana > 0)            return "ja";          // kana ⇒ Japanese even amid Han
            if (hangul >= max(cyrillic, han, arabic)) return "ko";
            if (han > 0)             return "zh";
            if (cyrillic >= max(greek, arabic, hebrew))   return "ru";
            if (arabic > 0)          return "ar";
            if (hebrew > 0)          return "he";
            if (greek > 0)           return "el";
            if (devanagari > 0)      return "hi";
            if (thai > 0)            return "th";
            return "";
        }

        // ── stage 2: Latin-script stopword scoring ──
        String[] tokens = text.toLowerCase().split("[^\\p{L}]+");
        int total = 0;
        Map<String, Integer> hits = new HashMap<>();
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            total++;
            for (Map.Entry<String, Set<String>> e : STOPWORDS.entrySet())
                if (e.getValue().contains(t)) hits.merge(e.getKey(), 1, Integer::sum);
        }
        if (total < MIN_TOKENS) return "";

        String best = "";  int bestN = 0, secondN = 0;
        for (Map.Entry<String, Integer> e : hits.entrySet()) {
            if (e.getValue() > bestN) { secondN = bestN; bestN = e.getValue(); best = e.getKey(); }
            else if (e.getValue() > secondN) secondN = e.getValue();
        }
        if (bestN < MIN_FRACTION * total) return "";
        if (secondN > 0 && bestN < MARGIN * secondN) return "";   // too close to call
        return best;
    }

    private static int max(int... v) { int m = Integer.MIN_VALUE; for (int x : v) m = Math.max(m, x); return m; }

    /** Concatenate page text up to {@link #SAMPLE_CHARS}. */
    private static String sample(OCDDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (OCDPage p : doc.pages()) {
            for (OCDText t : (Iterable<OCDText>) p.texts()::iterator) {
                sb.append(t.text()).append(' ');
                if (sb.length() >= SAMPLE_CHARS) return sb.toString();
            }
        }
        return sb.toString();
    }

    // Compact, distinctive function-word sets for the major Latin-script languages. Overlaps
    // (de/la/le across Romance languages) are tolerated: the full-set hit counts still separate,
    // and the margin gate abstains when they don't.
    private static final Map<String, Set<String>> STOPWORDS = new HashMap<>();
    static {
        STOPWORDS.put("en", Set.of("the","and","of","to","in","is","that","it","for","was","with","as",
                "on","be","at","by","this","had","not","are","but","from","or","have","an","they","which","you","were","his"));
        STOPWORDS.put("fr", Set.of("le","la","les","de","des","et","un","une","à","est","que","qui","dans","pour",
                "pas","sur","au","ce","il","elle","nous","vous","avec","plus","mais","par","son","sa","ses","ne"));
        STOPWORDS.put("de", Set.of("der","die","das","und","ist","ein","eine","zu","den","mit","von","nicht","auch",
                "sich","auf","für","dem","des","im","ich","er","sie","es","war","als","wir","aber","oder","dass"));
        STOPWORDS.put("es", Set.of("el","la","los","las","de","y","un","una","que","en","es","por","con","para",
                "su","no","se","lo","como","más","pero","sus","le","ya","o","este","porque","cuando","muy","sin"));
        STOPWORDS.put("it", Set.of("il","la","le","di","e","un","una","che","in","è","per","con","non","si","lo",
                "come","più","ma","sono","anche","della","nel","gli","dei","alla","su","da","questo","sua","suo"));
        STOPWORDS.put("pt", Set.of("o","a","os","as","de","e","um","uma","que","em","é","por","com","para","não",
                "se","na","no","dos","das","mais","mas","como","ao","ou","sua","seu","isso","quando","muito"));
        STOPWORDS.put("nl", Set.of("de","het","een","en","van","is","dat","op","te","in","niet","met","zijn","voor",
                "aan","er","maar","om","ook","als","dan","dit","naar","of","door","uit","over","worden","heeft","wordt"));
    }
}
