package sugarcube.jexter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JxJson — the reading counterpart to {@link JxStringer}: a small, zero-dependency
 * recursive-descent JSON parser. The JDK ships no JSON reader (JSON-P is Jakarta EE,
 * Nashorn was removed), so this closes the loop — write with {@code JxStringer},
 * read back with {@code JxJson} — without pulling in {@code org.json} or Jackson.
 *
 * <p>{@link #parse} returns a plain Java tree, the natural mapping of JSON values:
 * <ul>
 *   <li>object → {@link LinkedHashMap}{@code <String,Object>} (insertion order kept)</li>
 *   <li>array  → {@link ArrayList}{@code <Object>}</li>
 *   <li>string → {@link String}</li>
 *   <li>number → {@link Long} if integral and in range, else {@link Double}</li>
 *   <li>true / false → {@link Boolean}; null → {@code null}</li>
 * </ul>
 *
 * <pre>{@code
 *   Map<String,Object> root = JxJson.asObj(JxJson.parse(json));
 *   String name  = JxJson.str(root, "name");
 *   int    fonts = (int) JxJson.lng(root, "fonts");
 *   for (Object pg : JxJson.arr(root, "pages")) {
 *       Map<String,Object> p = JxJson.asObj(pg);
 *       double w = JxJson.dbl(p, "w"), h = JxJson.dbl(p, "h");
 *   }
 * }</pre>
 *
 * Malformed input throws {@link Error} with the offending offset. Parsing is
 * recursive, so depth is bounded by the stack; this is for our own documents,
 * not adversarial input.
 */
public final class JxJson {

    /** Thrown on malformed JSON, with the character offset. */
    public static final class Error extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public Error(String msg, int pos) { super(msg + " at offset " + pos); }
    }

    private final String s;
    private int i;

    private JxJson(String s) { this.s = s; }

    /** Parse a JSON document into a Java tree (Map / List / String / Long / Double / Boolean / null). */
    public static Object parse(String json) {
        if (json == null) throw new Error("null input", 0);
        JxJson p = new JxJson(json);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != json.length()) throw new Error("trailing content", p.i);
        return v;
    }

    // ── grammar ────────────────────────────────────────────────────────────────

    private Object value() {
        if (i >= s.length()) throw new Error("unexpected end", i);
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return lit("true", Boolean.TRUE);
            case 'f': return lit("false", Boolean.FALSE);
            case 'n': return lit("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw new Error("unexpected '" + c + "'", i);
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;                                              // consume '{'
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw new Error("expected string key", i);
            String key = string();
            ws();
            if (peek() != ':') throw new Error("expected ':'", i);
            i++;
            ws();
            m.put(key, value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == '}') { i++; return m; }
            throw new Error("expected ',' or '}'", i);
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;                                              // consume '['
        ws();
        if (peek() == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; return a; }
            throw new Error("expected ',' or ']'", i);
        }
    }

    private String string() {
        i++;                                              // consume opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw new Error("unterminated string", i);
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                if (i >= s.length()) throw new Error("unterminated escape", i);
                char e = s.charAt(i++);
                switch (e) {
                    case '"':  b.append('"');  break;
                    case '\\': b.append('\\'); break;
                    case '/':  b.append('/');  break;
                    case 'b':  b.append('\b'); break;
                    case 'f':  b.append('\f'); break;
                    case 'n':  b.append('\n'); break;
                    case 'r':  b.append('\r'); break;
                    case 't':  b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new Error("bad \\u escape", i);
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw new Error("bad escape '\\" + e + "'", i - 1);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        boolean isDouble = false;
        if (i < s.length() && s.charAt(i) == '.') { isDouble = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            isDouble = true; i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        }
        String tok = s.substring(start, i);
        if (!isDouble) {
            try { return Long.parseLong(tok); } catch (NumberFormatException ignore) { /* overflow → double */ }
        }
        return Double.parseDouble(tok);
    }

    private Object lit(String word, Object val) {
        if (!s.startsWith(word, i)) throw new Error("invalid literal", i);
        i += word.length();
        return val;
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++; else break;
        }
    }

    // ── typed accessors (ergonomic re-reading) ─────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObj(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        throw new Error("expected object, got " + typeOf(o), 0);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArr(Object o) {
        if (o instanceof List) return (List<Object>) o;
        throw new Error("expected array, got " + typeOf(o), 0);
    }

    public static String  str(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? null : v.toString(); }
    public static long    lng(Map<String, Object> m, String k) { return ((Number) req(m, k)).longValue(); }
    public static double  dbl(Map<String, Object> m, String k) { return ((Number) req(m, k)).doubleValue(); }
    public static boolean bool(Map<String, Object> m, String k){ return Boolean.TRUE.equals(m.get(k)); }
    public static Map<String, Object> obj(Map<String, Object> m, String k) { return asObj(req(m, k)); }
    public static List<Object>        arr(Map<String, Object> m, String k) { return asArr(req(m, k)); }
    public static boolean has(Map<String, Object> m, String k) { return m.containsKey(k) && m.get(k) != null; }

    private static Object req(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new Error("missing key '" + k + "'", 0);
        return v;
    }

    private static String typeOf(Object o) { return o == null ? "null" : o.getClass().getSimpleName(); }

    // ── path access (e.g. "pages[0]/w", "meta/title", "[2]/name") ──────────────

    /** Navigate a {@code /}-separated path with {@code [i]} array indices. Throws on a missing/typed step. */
    public static Object path(Object root, String path) { return walk(root, path, true); }

    /** Like {@link #path} but returns {@code null} instead of throwing when a step is absent. */
    public static Object opt(Object root, String path) { return walk(root, path, false); }

    public static String  pathStr (Object root, String p) { Object v = path(root, p); return v == null ? null : v.toString(); }
    public static long    pathLng (Object root, String p) { return ((Number) path(root, p)).longValue(); }
    public static double  pathDbl (Object root, String p) { return ((Number) path(root, p)).doubleValue(); }
    public static boolean pathBool(Object root, String p) { return Boolean.TRUE.equals(path(root, p)); }

    @SuppressWarnings("unchecked")
    private static Object walk(Object cur, String path, boolean strict) {
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals("root") || seg.equals("$")) continue;   // tolerate leading "/" or "root/"
            int br = seg.indexOf('[');
            String key = br < 0 ? seg : seg.substring(0, br);
            if (!key.isEmpty()) {
                if (!(cur instanceof Map)) { if (strict) throw new Error("not an object at '" + key + "'", 0); return null; }
                cur = ((Map<String, Object>) cur).get(key);
                if (cur == null) { if (strict) throw new Error("missing '" + key + "'", 0); return null; }
            }
            int q = br;                                          // parse zero or more [index]
            while (q >= 0) {
                int close = seg.indexOf(']', q);
                if (close < 0) throw new Error("unclosed '[' in '" + seg + "'", 0);
                int idx;
                try { idx = Integer.parseInt(seg.substring(q + 1, close).trim()); }
                catch (NumberFormatException e) { throw new Error("bad index in '" + seg + "'", 0); }
                if (!(cur instanceof List)) { if (strict) throw new Error("not an array at '" + seg + "'", 0); return null; }
                List<Object> list = (List<Object>) cur;
                if (idx < 0 || idx >= list.size()) { if (strict) throw new Error("index " + idx + " out of range", 0); return null; }
                cur = list.get(idx);
                q = seg.indexOf('[', close);
            }
        }
        return cur;
    }

    // ── write a tree back to JSON (parse → modify Map/List → write) ─────────────

    /** Serialize a Java tree (Map / List / String / Number / Boolean / null) back to JSON via {@link JxStringer}. */
    public static String write(Object tree) {
        JxStringer j = new JxStringer();
        elem(j, tree);
        return j.toString();
    }

    @SuppressWarnings("unchecked")
    private static void elem(JxStringer j, Object v) {
        if (v == null)               j.nul();
        else if (v instanceof Map)   { j.obj(); members(j, (Map<String, Object>) v); j.end(); }
        else if (v instanceof List)  { j.arr(); for (Object e : (List<Object>) v) elem(j, e); j.end(); }
        else if (v instanceof String)  j.str((String) v);
        else if (v instanceof Boolean) j.bool((Boolean) v);
        else if (isIntegral(v))        j.num(((Number) v).longValue());
        else if (v instanceof Number)  j.num(((Number) v).doubleValue());
        else                           j.str(v.toString());
    }

    @SuppressWarnings("unchecked")
    private static void members(JxStringer j, Map<String, Object> m) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            String k = e.getKey(); Object v = e.getValue();
            if (v == null)               j.nul(k);
            else if (v instanceof Map)   { j.obj(k); members(j, (Map<String, Object>) v); j.end(); }
            else if (v instanceof List)  { j.arr(k); for (Object x : (List<Object>) v) elem(j, x); j.end(); }
            else if (v instanceof String)  j.str(k, (String) v);
            else if (v instanceof Boolean) j.bool(k, (Boolean) v);
            else if (isIntegral(v))        j.num(k, ((Number) v).longValue());
            else if (v instanceof Number)  j.num(k, ((Number) v).doubleValue());
            else                           j.str(k, v.toString());
        }
    }

    private static boolean isIntegral(Object v) {
        return v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte;
    }
}
