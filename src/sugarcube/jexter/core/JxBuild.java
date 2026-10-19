package sugarcube.jexter.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which build of the engine this is: the version, the git commit and the build time the build deck
 * stamps into the jar ({@code nimbus-jexter.cjs}, {@code BUILD_INFO} in {@code .env}). A service running
 * the jar says it from here, so a deploy can prove the build it shipped is the one answering. Run from
 * classes (an IDE, the gates), there is no stamp: {@code dev}.
 *
 * <p>{@code java -cp <jar> sugarcube.jexter.core.JxBuild} prints the identity as JSON — what a deck
 * compares the live answer with.
 */
public record JxBuild(String version, String commit, String built) {

    /** Where the deck writes it, inside the jar — {@code BUILD_INFO} in {@code .env}. */
    public static final String RESOURCE = "/sugarcube/jexter/build.properties";

    private static final JxBuild CURRENT = read();

    /** This engine's build. */
    public static JxBuild current() { return CURRENT; }

    /** One line for a person: {@code 1.0.0 f8948fd 2026-10-19T19:50:12Z}, or {@code dev}. */
    public String label() {
        return (version + " " + commit + " " + built).replaceAll("\\s+", " ").strip();
    }

    /** {@code {"version": …, "commit": …, "built": …}}. */
    public String toJson() {
        return new JxStringer().obj().str("version", version).str("commit", commit).str("built", built).end().toString();
    }

    private static JxBuild read() {
        try (InputStream in = JxBuild.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return new JxBuild("dev", "", "");
            Properties p = new Properties();
            p.load(in);
            return new JxBuild(p.getProperty("version", "dev"), p.getProperty("commit", ""), p.getProperty("built", ""));
        } catch (IOException e) {
            return new JxBuild("dev", "", "");
        }
    }

    /** Prints {@link #toJson()}.
     *  @param args none */
    public static void main(String[] args) { System.out.println(current().toJson()); }
}
