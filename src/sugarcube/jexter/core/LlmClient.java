package sugarcube.jexter.core;

/**
 * Host-bindable façade for a text-completion model — the single seam through which <i>any</i>
 * LLM (Claude, GPT, a local server, …) is plugged into the engine, mirroring {@link JxLog}.
 *
 * <p>The engine stays dependency-free: analysis code only ever sees this interface. A concrete
 * provider (e.g. an HTTP client) is installed by the host or CLI via {@link #bind}. When nothing
 * is bound, model-gated passes degrade gracefully — they simply skip, leaving the heuristic
 * result untouched. The model is never on the critical path of conversion or fidelity.
 *
 * <p>Implementations should run <b>deterministically</b> (temperature 0) so that two identical
 * documents yield identical structures, and should be safe to call from a single analysis thread.
 */
public interface LlmClient {

    /**
     * Run one completion. {@code system} frames the task and the answer grammar; {@code user}
     * carries the payload (the serialized document blocks). Returns the model's raw text reply —
     * the caller is responsible for parsing and validating it. Implementations may throw on
     * transport/HTTP errors; callers treat any throw as "model unavailable" and fall back.
     */
    String complete(String system, String user);

    /** Short identifier of the underlying model, recorded as structure provenance (e.g. the model name). */
    default String model() { return "llm"; }

    // ── façade binding: the host installs a provider, the engine reads it ────────────────────────
    /** Holder for the process-wide bound client (interfaces cannot have instance state). */
    final class Holder { private static volatile LlmClient bound; private Holder() {} }

    /** Install the process-wide client (typically once, at startup, by the host/CLI). */
    static void      bind(LlmClient c) { Holder.bound = c; }
    /** The currently bound client, or {@code null} if none. */
    static LlmClient bound()           { return Holder.bound; }
    /** Whether a client is available — model-gated passes check this before running. */
    static boolean   isBound()         { return Holder.bound != null; }
}
