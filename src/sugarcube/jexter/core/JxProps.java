package sugarcube.jexter.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JxProps — a tiny typed view over a {@code String → String} map. It captures loose
 * key/value pairs once (CLI {@code --key[=value]} flags, GUI control values, config
 * lines) and reads them back as {@code boolean} / {@code int} / {@code double} /
 * {@code String} with caller-supplied defaults, so parsing and coercion live in one
 * place instead of being re-derived at each call site. Insertion order is preserved.
 *
 * <pre>{@code
 *   JxProps p = JxProps.ofArgs(args, 2);   // --selectable --grid=300
 *   boolean sel = p.flag("selectable");    // true
 *   int grid    = p.integer("grid", 500);  // 300
 * }</pre>
 */
public final class JxProps {

    private final Map<String, String> m;

    public JxProps() { this.m = new LinkedHashMap<>(); }
    public JxProps(Map<String, String> m) { this.m = m != null ? m : new LinkedHashMap<>(); }

    /** Parse {@code --key=value} (and bare {@code --flag} → {@code "true"}) from index {@code from}. */
    public static JxProps ofArgs(String[] args, int from) {
        JxProps p = new JxProps();
        for (int i = from; i < args.length; i++) {
            String a = args[i].startsWith("--") ? args[i].substring(2) : args[i];
            int eq = a.indexOf('=');
            if (eq >= 0) p.put(a.substring(0, eq), a.substring(eq + 1));
            else p.put(a, "true");
        }
        return p;
    }

    public JxProps put(String key, String value) { m.put(key, value); return this; }
    public boolean has(String key)               { return m.containsKey(key); }
    public Map<String, String> map()             { return m; }

    public String get(String key)             { return m.get(key); }
    public String str(String key, String def) { String v = m.get(key); return v != null ? v : def; }

    /** Truthy presence: set and not {@code "false"} / {@code "0"}. */
    public boolean flag(String key)              { String v = m.get(key); return v != null && !v.equalsIgnoreCase("false") && !v.equals("0"); }
    public boolean bool(String key, boolean def) { return m.get(key) == null ? def : flag(key); }
    public int integer(String key, int def)      { try { return Integer.parseInt(m.get(key)); } catch (Exception e) { return def; } }
    public double dbl(String key, double def)    { try { return Double.parseDouble(m.get(key)); } catch (Exception e) { return def; } }
}
