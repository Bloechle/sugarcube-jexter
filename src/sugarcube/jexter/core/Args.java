package sugarcube.jexter.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A command line, split once — the one parser of every CLI and launcher.
 *
 * <p>{@code --key=value} and a bare {@code --flag} (read as {@code "true"}) are flags wherever they stand,
 * so a flag can never be mistaken for a path; everything else is positional, in order. A key named in
 * {@code valued} takes the NEXT argument as its value ({@code --port 8080}, the window's convention).
 * A caller {@link #take}s the flags it owns; what remains in {@link #flags} is the rest — typically the
 * {@link ConvertOptions} of a conversion.
 */
public final class Args {

    private final List<String> positional = new ArrayList<>();
    private final Map<String, String> flags = new LinkedHashMap<>();

    private Args() {}

    public static Args parse(String[] argv, String... valued) {
        Args a = new Args();
        Set<String> withValue = Set.of(valued);
        for (int i = 0; i < argv.length; i++) {
            String s = argv[i];
            if (!s.startsWith("--")) { a.positional.add(s); continue; }
            String k = s.substring(2);
            int eq = k.indexOf('=');
            if (eq >= 0) a.flags.put(k.substring(0, eq), k.substring(eq + 1));
            else if (withValue.contains(k) && i + 1 < argv.length) a.flags.put(k, argv[++i]);
            else a.flags.put(k, "true");
        }
        return a;
    }

    public List<String> positional() { return positional; }

    /** The {@code i}-th positional argument, or null. */
    public String positional(int i) { return i < positional.size() ? positional.get(i) : null; }

    /** The flags no one has taken yet — live and mutable. */
    public Map<String, String> flags() { return flags; }

    /** Remove the flag {@code key} and return its value, or null when it is absent. */
    public String take(String key) { return flags.remove(key); }

    /** Remove the switch {@code key}: whether it was given and not {@code =false}. */
    public boolean takeSwitch(String key) {
        String v = flags.remove(key);
        return v != null && !v.equalsIgnoreCase("false");
    }
}
