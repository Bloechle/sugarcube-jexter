package jj2000.j2k.util;

/** The one log seam of the vendored codec — quiet by default, System.Logger when asked. */
public final class Jj2Log {
    private static final System.Logger LOG = System.getLogger("jj2000");
    private Jj2Log() { }
    public static void note(Object... parts) {
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) return;
        StringBuilder b = new StringBuilder();
        for (Object p : parts) b.append(p);
        LOG.log(System.Logger.Level.DEBUG, b.toString());
    }
}
