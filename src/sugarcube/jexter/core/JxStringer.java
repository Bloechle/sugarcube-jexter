package sugarcube.jexter.core;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * JxStringer — a fluent, scope-aware {@link StringBuilder} for emitting JSON
 * without the usual hand-rolled noise: no {@code "key":} string concatenation,
 * no {@code boolean first} / {@code if (i>0) sb.append(',')} separator dance, no
 * manual brace/bracket balancing, and one correct string escaper for everyone.
 *
 * <p>It is a <em>streaming</em> writer — it wraps a single {@code StringBuilder}
 * and tracks only a small scope stack — so it stays allocation-light on the large
 * COS / OCD node trees (no intermediate object model).
 *
 * <pre>{@code
 *   String json = new JxStringer()
 *       .obj()
 *         .str("name", name)
 *         .num("fonts", fontCount)
 *         .arr("pages");
 *           for (var p : pages) js.obj().num("w", p.w).num("h", p.h).end();
 *         js.end()        // pages
 *       .end()            // root object
 *       .toString();
 * }</pre>
 *
 * Object members take a key ({@code str("k", v)}); array elements omit it
 * ({@code str(v)}). {@link #raw} embeds already-serialized JSON verbatim, and
 * {@link #nul} writes a JSON {@code null} literal. A {@code null} string passed
 * to {@link #str} is written as an empty string {@code ""} (not {@code null}) —
 * use {@code nul} when you actually mean the JSON null literal.
 */
public final class JxStringer {

    private enum Kind { OBJ('}'), ARR(']'); final char close; Kind(char c) { close = c; } }
    private static final class Scope { final Kind kind; boolean first = true; Scope(Kind k) { kind = k; } }

    private final StringBuilder sb;
    private final Deque<Scope> scopes = new ArrayDeque<>();

    public JxStringer()            { this.sb = new StringBuilder(256); }
    public JxStringer(int capacity){ this.sb = new StringBuilder(capacity); }

    // ── containers ────────────────────────────────────────────────────────────
    public JxStringer obj()              { return open(Kind.OBJ, null); }
    public JxStringer obj(String key)    { return open(Kind.OBJ, key); }
    public JxStringer arr()              { return open(Kind.ARR, null); }
    public JxStringer arr(String key)    { return open(Kind.ARR, key); }
    public JxStringer end() {
        if (scopes.isEmpty()) throw new IllegalStateException("JxStringer.end(): no open object/array");
        Scope s = scopes.pop();
        sb.append(s.kind.close);
        return this;
    }

    // ── members (with key) ──────────────────────────────────────────────────
    public JxStringer str(String key, String v)  { sep(); key(key); quote(v);                  return this; }
    public JxStringer num(String key, long v)     { sep(); key(key); sb.append(v);              return this; }
    public JxStringer num(String key, double v)   { sep(); key(key); sb.append(JxNum.fmt(v));        return this; }
    public JxStringer bool(String key, boolean v) { sep(); key(key); sb.append(v);              return this; }
    public JxStringer nul(String key)             { sep(); key(key); sb.append("null");         return this; }
    public JxStringer raw(String key, String json){ sep(); key(key); sb.append(json);           return this; }

    // ── elements (array values, no key) ───────────────────────────────────────
    public JxStringer str(String v)   { sep(); quote(v);          return this; }
    public JxStringer num(long v)      { sep(); sb.append(v);      return this; }
    public JxStringer num(double v)    { sep(); sb.append(JxNum.fmt(v)); return this; }
    public JxStringer bool(boolean v)  { sep(); sb.append(v);      return this; }
    public JxStringer nul()            { sep(); sb.append("null"); return this; }
    public JxStringer raw(String json) { sep(); sb.append(json);   return this; }

    @Override public String toString() { return sb.toString(); }

    // ── internals ──────────────────────────────────────────────────────────────
    private JxStringer open(Kind k, String key) {
        sep();
        if (key != null) key(key);
        sb.append(k == Kind.OBJ ? '{' : '[');
        scopes.push(new Scope(k));
        return this;
    }

    /** Comma before any sibling within the current container (no-op at root / first child). */
    private void sep() {
        Scope s = scopes.peek();
        if (s == null) return;
        if (s.first) s.first = false;
        else sb.append(',');
    }

    private void key(String k) { sb.append('"'); esc(k); sb.append("\":"); }
    private void quote(String v) { sb.append('"'); esc(v); sb.append('"'); }

    /** Append {@code s} JSON-escaped (no surrounding quotes). All control chars < 0x20 are escaped. */
    private void esc(String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
    }

    // ── static helper (shared JSON string escaping) ──────────────────────────
    /** A JSON string literal, quoted and escaped — the one true escaper (replaces ad-hoc jstr/esc). */
    public static String quoted(String s) {
        JxStringer j = new JxStringer(s == null ? 2 : s.length() + 2);
        j.quote(s == null ? "" : s);
        return j.toString();
    }
}