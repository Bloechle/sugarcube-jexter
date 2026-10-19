package sugarcube.jexter.tool;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.LlmClient;

/**
 * The language model a host binds at startup, from the environment — the one place that decides whether
 * the {@code JEXTER_LLM_*} variables name a usable model, so every host (the Prism workbench, a conversion
 * service) binds the same way. The twin of {@link OcrEngines}.
 *
 * <p>A model is bound when a key is set ({@code JEXTER_LLM_KEY}, {@code ANTHROPIC_API_KEY} or
 * {@code OPENAI_API_KEY}) or the provider needs none ({@code ollama}); {@link HttpLlmClient} documents
 * every variable it reads.
 */
public final class LlmEngines {

    private LlmEngines() { }

    /** Bind the model the environment names, unless one is bound already, and return the bound client
     *  ({@code null} when none). Never throws: a host still starts without its model. */
    public static LlmClient bindFromEnv() {
        if (LlmClient.isBound()) return LlmClient.bound();
        try {
            HttpLlmClient c = HttpLlmClient.fromEnv();
            if (c != null) {
                LlmClient.bind(c);
                JxLog.info(LlmEngines.class, "LLM bound from environment — provider=" + c.providerLabel() + " model=" + c.model());
            }
            return c;
        } catch (RuntimeException e) {
            JxLog.warn(LlmEngines.class, "LLM not bound: " + e.getMessage());
            return null;
        }
    }
}
