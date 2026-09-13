package sugarcube.jexter.tool;

import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.core.LlmClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency {@link LlmClient} over the JDK HTTP client. One class, many providers: the
 * <b>provider</b> selects the wire format, the default endpoint/model and whether a key is required.
 * <ul>
 *   <li><b>anthropic</b> — Anthropic Messages wire (x-api-key + anthropic-version);</li>
 *   <li><b>openai</b> — OpenAI Chat Completions wire (Authorization: Bearer).</li>
 * </ul>
 * Every provider below speaks the OpenAI wire and has a <b>free tier</b>, listed best-first for this
 * task (recovering a logical tree from a possibly long document):
 * <ol>
 *   <li><b>gemini</b> — Google AI Studio. 1M-token context (a whole document, no chunking) at
 *       frontier quality; ~1.5k req/day free, no card. Best fit. <i>(Free prompts may train Google's
 *       models unless billing is enabled.)</i></li>
 *   <li><b>cerebras</b> — wafer-scale inference: very fast, ~1M tokens/day free, typically no-training.
 *       Great for batch document work. <i>The free model roster rotates — override the model if it 404s.</i></li>
 *   <li><b>openrouter</b> — one key, many {@code :free} models (Qwen3, DeepSeek, Llama…) with failover.
 *       Best for trying models. <i>Free slugs rotate — override as needed.</i></li>
 *   <li><b>groq</b> — LPU, extremely fast, but a tight tokens/minute cap: ideal for short documents,
 *       it chokes on large payloads.</li>
 *   <li><b>mistral</b> — La Plateforme free tier, EU-hosted (GDPR-friendly).</li>
 *   <li><b>ollama</b> — a local server on {@code localhost:11434}, <i>no key</i> — fully free/offline.</li>
 * </ol>
 * Any provider other than {@code anthropic} speaks the OpenAI wire, so a new OpenAI-compatible backend
 * is just a provider id + endpoint + model. Configurable from the environment or at run time from the
 * AI panel (POST /api/ai/config). Calls are deterministic (temperature 0); the key is held in memory only.
 *
 * <pre>
 *   JEXTER_LLM_PROVIDER  anthropic | openai | gemini | cerebras | openrouter | groq | mistral | ollama | …   (default anthropic)
 *   JEXTER_LLM_ENDPOINT  (default per provider)
 *   JEXTER_LLM_KEY       (or ANTHROPIC_API_KEY / OPENAI_API_KEY; unused for ollama)
 *   JEXTER_LLM_MODEL     (default per provider)
 *   JEXTER_LLM_EFFORT    low | medium | high | max   (anthropic wire only; unset = deterministic, temp 0)
 *   JEXTER_LLM_VERSION   anthropic-version header                          (default 2023-06-01)
 *   JEXTER_LLM_MAX_TOKENS                                                    (default 32768; raise for big trees / thinking models)
 * </pre>
 */
public final class HttpLlmClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String provider, wire, endpoint, key, model, version, effort;
    private final int maxTokens;

    public HttpLlmClient() {
        this(env("JEXTER_LLM_PROVIDER", env("JEXTER_LLM_SHAPE", "anthropic")),
             env("JEXTER_LLM_ENDPOINT", ""),
             env("JEXTER_LLM_KEY", env("ANTHROPIC_API_KEY", env("OPENAI_API_KEY", ""))),
             env("JEXTER_LLM_MODEL", ""),
             env("JEXTER_LLM_EFFORT", ""));
    }

    /** Build a client with the default effort (env {@code JEXTER_LLM_EFFORT}, else none = deterministic). */
    public HttpLlmClient(String provider, String endpoint, String key, String model) {
        this(provider, endpoint, key, model, env("JEXTER_LLM_EFFORT", ""));
    }

    /** Build a client for a given provider. Blank {@code endpoint}/{@code model} fall back to the
     *  provider default. Any provider id other than "anthropic" uses the OpenAI Chat wire.
     *  {@code effort} (low | medium | high | max) is the optional adaptive-thinking depth: it only
     *  applies to the anthropic wire, and when set the call drops {@code temperature 0} (the API
     *  rejects temperature ≠ 1 once thinking is enabled), trading determinism for deeper reasoning. */
    public HttpLlmClient(String provider, String endpoint, String key, String model, String effort) {
        this.provider  = (provider == null || provider.isBlank()) ? "anthropic" : provider.trim().toLowerCase();
        this.wire      = "anthropic".equals(this.provider) ? "anthropic" : "openai";
        this.endpoint  = blank(endpoint) ? defaultEndpoint(this.provider) : endpoint.trim();
        this.key       = key == null ? "" : key.trim();
        this.model     = blank(model) ? defaultModel(this.provider) : model.trim();
        this.version   = env("JEXTER_LLM_VERSION", "2023-06-01");
        this.maxTokens = (int) Math.max(1024, longEnv("JEXTER_LLM_MAX_TOKENS", 32768));
        this.effort    = normEffort(effort);
    }

    @Override public String model() { return model; }

    /** Adaptive-thinking effort, or "" when unset (deterministic, temperature 0). */
    public String effort() { return effort; }

    /** Accept only the documented effort levels; anything else (incl. blank) → "" = unset.
     *  "none" is meaningful on the OpenAI/Gemini wire (thinking off); on the anthropic wire it is
     *  treated as unset (deterministic). */
    private static String normEffort(String e) {
        if (e == null) return "";
        String s = e.trim().toLowerCase();
        return switch (s) { case "none", "low", "medium", "high", "max" -> s; default -> ""; };
    }

    /** Reasoning depth to send on the OpenAI wire as {@code reasoning_effort}. An explicit user
     *  {@code effort} wins (max → high, the wire's top level). Otherwise, for the structure task we
     *  want the JSON, not deep reasoning: thinking-by-default models (GPT-5.x, Gemini 3.x, …) spend
     *  {@code max_tokens} on reasoning and silently truncate the reply (finish_reason=length), so we
     *  pin the smallest accepted level per provider — {@code none} on OpenAI (thinking off), {@code low}
     *  on Gemini (3.x rejects "none"). Other providers are left at their default ("" = field omitted,
     *  so models that don't accept reasoning_effort never receive it). Override with JEXTER_LLM_EFFORT. */
    private String openaiEffort() {
        if (!effort.isEmpty()) return "max".equals(effort) ? "high" : effort;
        if ("openai".equals(provider)) return "none";
        if ("gemini".equals(provider)) return "low";
        return "";
    }
    /** The provider id, e.g. "anthropic" | "openai" | "groq" | "gemini" | "ollama". */
    public  String provider() { return provider; }
    /** A friendly provider name for display/provenance (falls back to the id). */
    public  String providerLabel() {
        return switch (provider) {
            case "anthropic" -> "Anthropic";
            case "openai"    -> "OpenAI";
            case "gemini"    -> "Google Gemini";
            case "cerebras"  -> "Cerebras";
            case "openrouter"-> "OpenRouter";
            case "groq"      -> "Groq";
            case "mistral"   -> "Mistral";
            case "ollama"    -> "Ollama";
            default          -> provider;
        };
    }

    @Override public String complete(String system, String user) {
        boolean openai = "openai".equals(wire);
        String reff      = openai ? openaiEffort() : "";          // reasoning_effort on the OpenAI wire (Gemini thinking control)
        boolean thinking = openai ? (!reff.isEmpty() && !"none".equals(reff))
                                  : (!effort.isEmpty() && !"none".equals(effort));   // a "thinking" call on either wire
        JxStringer js = new JxStringer(user.length() + 512).obj().str("model", model)
                .num("max_tokens", maxTokens);
        if (!thinking) js.num("temperature", 0);                  // deterministic; omitted when thinking may engage (API rejects temp ≠ 1 then)
        if (!openai && thinking)        js.obj("output_config").str("effort", effort).end();   // anthropic adaptive thinking
        if (openai && !reff.isEmpty())  js.str("reasoning_effort", reff);                       // OpenAI/Gemini reasoning depth (none = thinking off)
        if (openai) {
            js.arr("messages")
                .obj().str("role", "system").str("content", system).end()
                .obj().str("role", "user").str("content", user).end()
              .end();
        } else {
            js.str("system", system).arr("messages")
                .obj().str("role", "user").str("content", user).end()
              .end();
        }
        String body = js.end().toString();

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!key.isBlank()) {                                  // local providers (ollama) need no auth
            if (openai) rb.header("authorization", "Bearer " + key);
            else        rb.header("x-api-key", key).header("anthropic-version", version);
        } else if (!openai) {
            rb.header("anthropic-version", version);
        }

        try {
            HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2)
                throw new RuntimeException("HTTP " + res.statusCode() + " — " + brief(res.body()));
            return openai ? extractOpenAi(res.body()) : extractAnthropic(res.body());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM request failed: " + e.getMessage(), e);
        }
    }

    /** Anthropic Messages reply: {@code { "content": [ { "type":"text", "text":"…" }, … ] }}. */
    private static String extractAnthropic(String json) {
        Object content = JxJson.opt(JxJson.parse(json), "content");
        if (!(content instanceof List<?> arr)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object b : arr) {
            Map<String, Object> m = JxJson.asObj(b);
            if (m != null && "text".equals(JxJson.str(m, "type"))) {
                String t = JxJson.str(m, "text");
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    /** OpenAI / Groq / Gemini / Ollama Chat reply: {@code { "choices":[ { "message":{ "content":"…" } } ] }}. */
    private static String extractOpenAi(String json) {
        Object c = JxJson.opt(JxJson.parse(json), "choices[0]/message/content");
        return c == null ? "" : c.toString();
    }

    // ── provider defaults (env path; the AI panel sends explicit endpoint+model) ─────────────────
    private static String defaultEndpoint(String p) {
        return switch (p) {
            case "openai"     -> "https://api.openai.com/v1/chat/completions";
            case "gemini"     -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
            case "cerebras"   -> "https://api.cerebras.ai/v1/chat/completions";
            case "openrouter" -> "https://openrouter.ai/api/v1/chat/completions";
            case "groq"       -> "https://api.groq.com/openai/v1/chat/completions";
            case "mistral"    -> "https://api.mistral.ai/v1/chat/completions";
            case "ollama"     -> "http://localhost:11434/v1/chat/completions";
            default           -> "https://api.anthropic.com/v1/messages";
        };
    }
    private static String defaultModel(String p) {
        return switch (p) {
            case "openai"     -> "gpt-4o-mini";
            case "gemini"     -> "gemini-2.5-flash";              // 2.0-flash retired 2026-06-01
            case "cerebras"   -> "gpt-oss-120b";                  // free roster rotates — override if it 404s
            case "openrouter" -> "qwen/qwen3-coder:free";         // free slugs rotate — override as needed
            case "groq"       -> "openai/gpt-oss-120b";           // llama-3.3-70b-versatile deprecated 2026-06-17; Groq's recommended replacement (note: openai/ prefix on Groq vs bare on Cerebras)
            case "mistral"    -> "mistral-small-latest";
            case "ollama"     -> "llama3.2";
            default           -> "claude-sonnet-4-6";
        };
    }

    private static boolean blank(String s)          { return s == null || s.isBlank(); }
    private static String env(String k, String d)   { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    private static long   longEnv(String k, long d) { try { return Long.parseLong(env(k, "")); } catch (Exception e) { return d; } }
    private static String brief(String s)           { return s == null ? "" : (s.length() <= 300 ? s : s.substring(0, 300) + "…"); }
}
