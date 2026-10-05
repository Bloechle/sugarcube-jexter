package sugarcube.jexter.tool;

import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.OcrClient;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Zero-dependency {@link OcrClient} over Azure Document Intelligence, model {@code prebuilt-read}, REST
 * API v4.0 ({@code 2024-11-30}). The image is POSTed as is; the service answers {@code 202} with an
 * {@code Operation-Location}, polled until the analysis has {@code succeeded}. Words come from
 * {@code analyzeResult.pages[].words[]}: {@code content}, a four-corner {@code polygon} in PIXELS for an
 * image (taken as its axis-aligned box — a skewed photo gets the box that encloses each word) and a
 * {@code confidence} in {@code [0, 1]}.
 *
 * <p>Environment ({@link #fromEnv}): {@code JEXTER_OCR=azure} selects it;
 * {@code JEXTER_AZURE_OCR_ENDPOINT} and {@code JEXTER_AZURE_OCR_KEY} are the resource's endpoint and key —
 * never in the repository. The key is held in memory only and sent in the {@code Ocp-Apim-Subscription-Key}
 * header. The language hint, when given, travels as the service's own {@code locale} (e.g. {@code fr}).
 */
public final class AzureOcrClient implements OcrClient {

    static final String API = "2024-11-30";
    private static final Duration POLL = Duration.ofSeconds(1);
    private static final int MAX_POLLS = 120;

    private final String endpoint;
    private final String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public AzureOcrClient(String endpoint, String key) {
        if (endpoint == null || endpoint.isBlank() || key == null || key.isBlank())
            throw new IllegalArgumentException("Azure OCR needs an endpoint and a key");
        this.endpoint = endpoint.strip().replaceAll("/+$", "");
        this.key = key.strip();
    }

    /** The client the environment asks for, or {@code null} when {@code JEXTER_OCR} does not name Azure.
     *  Named but incomplete (no endpoint or no key) is an error, said in those words. */
    public static AzureOcrClient fromEnv() {
        if (!"azure".equalsIgnoreCase(System.getenv("JEXTER_OCR"))) return null;
        return new AzureOcrClient(System.getenv("JEXTER_AZURE_OCR_ENDPOINT"), System.getenv("JEXTER_AZURE_OCR_KEY"));
    }

    @Override public String engine() { return "azure-read"; }

    @Override public List<Word> read(byte[] image, String lang) throws IOException {
        String url = endpoint + "/documentintelligence/documentModels/prebuilt-read:analyze?api-version=" + API
                + (lang == null || lang.isBlank() ? "" : "&locale=" + URLEncoder.encode(lang.strip(), StandardCharsets.UTF_8));
        HttpResponse<String> posted = send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(image)));
        if (posted.statusCode() != 202) throw failure("analyze", posted);
        String op = posted.headers().firstValue("Operation-Location")
                .orElseThrow(() -> new IOException("Azure answered 202 without an Operation-Location"));
        for (int i = 0; i < MAX_POLLS; i++) {
            pause(posted.headers().firstValue("Retry-After").map(AzureOcrClient::seconds).orElse(POLL));
            HttpResponse<String> got = send(HttpRequest.newBuilder(URI.create(op)).GET());
            if (got.statusCode() != 200) throw failure("result", got);
            Object root = JxJson.parse(got.body());
            String status = JxJson.pathStr(root, "status");
            if ("succeeded".equals(status)) return parse(root);
            if ("failed".equals(status) || "canceled".equals(status))
                throw new IOException("Azure analysis " + status + ": " + JxJson.write(JxJson.opt(root, "error")));
        }
        throw new IOException("Azure analysis did not finish in " + MAX_POLLS + " polls");
    }

    /** {@code analyzeResult.pages[].words[]} → words. One image is one page; should a service ever split
     *  it, every page's words are returned, in the order the service gave them. A word's LINE is the
     *  {@code lines[]} entry whose span holds the word's span offset — the service states lines by the
     *  text they cover, not by listing their words. */
    static List<Word> parse(Object root) {
        List<Word> out = new ArrayList<>();
        Object pages = JxJson.opt(root, "analyzeResult/pages");
        if (pages == null) return out;
        int lineBase = 0;
        for (Object p : JxJson.asArr(pages)) {
            List<long[]> spans = new ArrayList<>();                 // [offset, end, line]
            Object ls = JxJson.asObj(p).get("lines");
            if (ls != null) {
                List<Object> la = JxJson.asArr(ls);
                for (int li = 0; li < la.size(); li++) {
                    Object sp = JxJson.asObj(la.get(li)).get("spans");
                    if (sp == null) continue;
                    for (Object o : JxJson.asArr(sp)) {
                        Map<String, Object> s = JxJson.asObj(o);
                        if (!(s.get("offset") instanceof Number off) || !(s.get("length") instanceof Number len)) continue;
                        spans.add(new long[]{off.longValue(), off.longValue() + len.longValue(), lineBase + li});
                    }
                }
                lineBase += la.size();
            }
            Object words = JxJson.asObj(p).get("words");
            if (words == null) continue;
            for (Object o : JxJson.asArr(words)) {
                Map<String, Object> w = JxJson.asObj(o);
                String text = JxJson.str(w, "content");
                Object poly = w.get("polygon");
                if (text == null || text.isBlank() || poly == null) continue;
                List<Object> xy = JxJson.asArr(poly);
                if (xy.size() < 8 || xy.size() % 2 != 0) continue;
                double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
                for (int i = 0; i < xy.size(); i += 2) {
                    double x = ((Number) xy.get(i)).doubleValue(), y = ((Number) xy.get(i + 1)).doubleValue();
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
                double conf = w.get("confidence") instanceof Number n ? n.doubleValue() : -1;
                int line = -1;
                if (w.get("span") instanceof Map<?, ?> span && span.get("offset") instanceof Number off)
                    for (long[] s : spans) if (off.longValue() >= s[0] && off.longValue() < s[1]) { line = (int) s[2]; break; }
                out.add(new Word(text, new JxRect(x0, y0, x1 - x0, y1 - y0), conf, line));
            }
        }
        return out;
    }

    private HttpResponse<String> send(HttpRequest.Builder rb) throws IOException {
        try {
            return http.send(rb.header("Ocp-Apim-Subscription-Key", key).timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** The service's own error message, never the request: the key travels in a header and stays out. */
    private static IOException failure(String step, HttpResponse<String> r) {
        String msg = r.body();
        try { msg = JxJson.pathStr(JxJson.parse(r.body()), "error/message"); } catch (RuntimeException ignore) { /* not JSON */ }
        return new IOException("Azure " + step + " failed: HTTP " + r.statusCode() + (msg == null || msg.isBlank() ? "" : " \u2014 " + msg));
    }

    private static Duration seconds(String s) {
        try { return Duration.ofSeconds(Math.max(1, Math.min(10, Long.parseLong(s.strip())))); }
        catch (NumberFormatException e) { return POLL; }
    }

    private static void pause(Duration d) throws IOException {
        try { Thread.sleep(d.toMillis()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
    }
}
