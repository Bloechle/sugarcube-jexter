package sugarcube.jexter.core;

/** The single rule for turning a model name into a safe zip-entry / file / URL stem. */
public final class JxName {
    private JxName() {}

    /** Conservative stem: keep [A-Za-z0-9._-], everything else → '_'. The real name lives in the data. */
    public static String safe(String s) {
        return (s == null || s.isEmpty()) ? "unnamed" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
