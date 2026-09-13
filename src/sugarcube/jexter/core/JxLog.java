package sugarcube.jexter.core;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Thin logging facade over {@link Logger} — the JDK-native, zero-dependency
 * logging primitive (since JDK 9). Each call resolves a per-source logger keyed by the
 * source class name, so a host app can filter / route by name and bind any backend
 * (SLF4J, Log4j2, …) through a {@link System.LoggerFinder}; with no binding the JDK
 * falls back to {@code java.util.logging}.
 *
 * <p>The conversion engine logs diagnostics through here. CLI / UI surfaces
 * ({@code PdfNormalizer}, {@code WebApp}) keep {@code System.out} / {@code System.err}
 * as their user-facing output — that is program output, not logging.
 *
 * <p>Levels are {@link Level}; filtering is per-logger and lives in the
 * host's logging configuration, not in a global flag. The {@code src} may be a
 * {@link Class} or any instance (its class is used). Messages are built only when the
 * level is enabled; hot-path code may hold its own {@code System.getLogger(name)} with a
 * lazy {@code Supplier} to skip even the per-call lookup.
 */
public final class JxLog {

    private JxLog() {}

    public static void debug(Object src, Object msg)              { log(Level.DEBUG,   src, msg, null); }
    public static void debug(Object src, Object msg, Throwable t) { log(Level.DEBUG,   src, msg, t);   }
    public static void info (Object src, Object msg)              { log(Level.INFO,    src, msg, null); }
    public static void warn (Object src, Object msg)              { log(Level.WARNING, src, msg, null); }
    public static void warn (Object src, Object msg, Throwable t) { log(Level.WARNING, src, msg, t);   }
    public static void error(Object src, Object msg, Throwable t) { log(Level.ERROR,   src, msg, t);   }

    private static void log(Level level, Object src, Object msg, Throwable t) {
        Logger log = logger(src);
        if (!log.isLoggable(level)) return;          // gate before materializing the message
        if (t != null) log.log(level, String.valueOf(msg), t);
        else           log.log(level, String.valueOf(msg));
    }

    private static Logger logger(Object src) {
        Class<?> c = (src == null)               ? JxLog.class
                   : (src instanceof Class<?> k)  ? k
                   :                                src.getClass();
        return System.getLogger(c.getName());
    }
}
