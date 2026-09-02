package sugarcube.jexter.ocd.analysis;

import sugarcube.jexter.core.JxClock;
import sugarcube.jexter.convert.ConvertOptions;
import sugarcube.jexter.core.JxJson;
import sugarcube.jexter.core.JxNum;
import sugarcube.jexter.core.JxText;
import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.core.LlmClient;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * <h2>LLM-based logical-structure refinement — a render-orthogonal analysis pass.</h2>
 *
 * Builds a second {@link OCDStruct} tree (alongside the heuristic one) by asking a bound
 * {@link LlmClient} to re-derive the document's logical structure. It is engineered around the
 * SOTA <i>any-LLM</i> recipe (cf. LMDX, Perot et al. 2024) rather than a specialised doc model —
 * but where a vision model would <i>see</i> the page, we instead <b>give the page back to the
 * model as data</b>: the cheap perceptual channels the eye reads in one glance (typographic
 * emphasis, vertical rhythm, indentation, alignment, block silhouette, colour, background) are
 * computed from the OCD model and serialised as compact scalars, so a plain text LLM reasons about
 * layout without the token cost of an image and without breaking the any-LLM contract.
 *
 * <p>The per-block signal set descends directly from the feature taxonomy of the author's PhD
 * thesis (Bloechle 2010, <i>Dolores</i>, §7.3.3), which fed morphological / structural / textual
 * features to an ANN to classify logical labels. The key shift: that classifier saw each block in
 * isolation, so it baked neighbour context into <i>cross-related</i> features (north/south sizes,
 * etc.); an LLM sees the whole block sequence at once and gets that context for free — so only the
 * <i>self-related</i> signals (and the one genuinely non-local cue, the gap above) are serialised.
 *
 * <ol>
 *   <li><b>Grounded serialization</b> — each block carries its stable id and a 0..1000
 *       top-down box (the grounding key; LMDX shows coords beat a bare index by ~12–15 % F1).</li>
 *   <li><b>Vision-by-signal</b> — size relative to body, bold/italic (from the font's own
 *       weight/style), caps, sentence-ending, numeric density, monospace, a display-face flag,
 *       text colour and background colour, line count, indent/width relative to the column, the
 *       gap above (the whitespace that opens a section), alignment, column, rotation, and the
 *       upstream "running head/foot" tag.</li>
 *   <li><b>Holistic reasoning</b> — the model resolves the global decisions heuristics get wrong,
 *       chiefly multi-page heading depth / ToC hierarchy (the hardest axis on READoc).</li>
 *   <li><b>Hierarchical JSON output</b> shaped like {@link OCDStruct} — leaves reference blocks by id.</li>
 *   <li><b>Anti-hallucination by construction</b> — any invented id is dropped at parse time; the
 *       pass can only regroup / relabel existing leaves, never move a pixel. A coverage floor and a
 *       parse guard make the whole step a safe no-op on failure (the heuristic structure remains).</li>
 * </ol>
 *
 * The result is appended as a distinct {@link OCDStructure.Source#MODEL} structure with provenance,
 * so it sits side-by-side with the heuristic one and the user picks. Nothing here touches geometry,
 * paint order, or fidelity.
 *
 * <p><b>Two modes, one output.</b> Small documents run one-shot (the whole block sequence in a single
 * prompt — the "sees everything at once" assumption above). From {@value #WINDOW_PAGE_MIN} pages (or
 * {@value #WINDOW_BLOCK_MIN} blocks) it switches to a <b>page-windowed</b> pass: each page is
 * structured in its own bounded prompt — a static {@code documentProfile} anchor + the previous/next
 * page as read-only context + the current page in full, the current page placed last (high-attention)
 * — so the task is invariant to document length (no lost-in-the-middle, no JSON truncation). The model
 * returns each page's subtree; {@link StructureReconciler} then re-derives a consistent global heading
 * hierarchy from the headings' actual sizes — the one decision a per-page model cannot make.
 */
public final class Refiner {

    private Refiner() {}

    private static final int    TEXT_CAP     = 240;   // per-block text budget in the prompt (chars)
    private static final double MIN_COVERAGE = 0.50;  // floor: ≥ this fraction of blocks must be grounded, else reject
    private static final String STRUCT_ID    = "model";

    private static final int    DIGIT_MIN    = 25;    // emit `digits` only above this % (numeric-dense blocks)

    private static final int    WINDOW_PAGE_MIN  = 4;  // \u2265 this many pages \u2192 process page-by-page (scale-invariant), else one-shot
    private static final int    WINDOW_BLOCK_MIN = 80; // \u2026or this many content blocks, whichever triggers first

    /** Run the refinement. Returns {@link Result} with {@code ok} and, on failure, a short human
     *  reason. Never throws: any failure is logged and leaves {@code doc} exactly as it was. */
    public static Result refine(OCDDocument doc, ConvertOptions opts, LlmClient client) {
        return refine(doc, opts, client, null, null);
    }

    public static Result refine(OCDDocument doc, ConvertOptions opts, LlmClient client, Progress progress) {
        return refine(doc, opts, client, progress, null);
    }

    /** Functional progress sink so the AI panel can stream staged progress (the model call blocks). */
    @FunctionalInterface public interface Progress { void at(String stage, String detail); }

    /**
     * Run the refinement.
     *
     * @param cancel polled between pages to stop early — a partial structure is kept; the pass also
     *               honours {@link Thread#interrupt() thread interruption}, so a host can stop a run by
     *               interrupting its worker thread with no extra wiring. {@code null} = never cancel.
     */
    public static Result refine(OCDDocument doc, ConvertOptions opts, LlmClient client, Progress progress, BooleanSupplier cancel) {
        Progress p = progress != null ? progress
                : (s, d) -> JxLog.info(Refiner.class, "refine \u00b7 " + s + (d == null || d.isEmpty() ? "" : " \u00b7 " + d));
        if (doc == null || client == null) return new Result(false, "no document or model");
        try {
            if (cancelled(cancel)) return new Result(false, "cancelled before start");
            List<Block> blocks = BlockSignals.harvest(doc);
            p.at("harvest", blocks.size() + " content blocks");
            if (blocks.size() < 2) { JxLog.debug(Refiner.class, "structure refine skipped \u2014 too few blocks", null); return new Result(false, "too few content blocks to refine"); }

            if (doc.pages().size() >= WINDOW_PAGE_MIN || blocks.size() >= WINDOW_BLOCK_MIN)
                return refineWindowed(doc, opts, client, p, blocks, cancel);   // scale-invariant page-by-page path

            double median = bodyMedian(blocks);
            String bodyFam = bodyFamily(blocks, median);
            String user   = serialize(blocks, median, bodyFam);
            p.at("prompt", user.length() + " chars prepared");
            p.at("request", "calling " + client.model() + "\u2026");
            String reply  = client.complete(SYSTEM, user);
            p.at("reply", (reply == null ? 0 : reply.length()) + " chars received");
            if (reply == null || reply.isBlank()) { JxLog.warn(Refiner.class, "structure refine \u2014 empty model reply"); return new Result(false, "the model returned an empty reply"); }

            String json = extractJson(reply);
            Object root;
            try { root = JxJson.parse(json); }
            catch (RuntimeException pe) {
                JxLog.warn(Refiner.class, "structure refine \u2014 model reply was not valid JSON ("
                        + pe.getMessage() + "); reply=" + reply.length() + " chars, extracted=" + json.length()
                        + " chars. Likely truncated (raise JEXTER_LLM_MAX_TOKENS \u2014 a thinking model such as "
                        + "Gemini 2.5 can spend its budget before emitting the JSON) or an unescaped quote in a value."
                        + "\n  head: " + snippet(json, 0, 400)
                        + "\n  tail: " + snippet(json, json.length() - 400, json.length()));
                return new Result(false, "the model reply was not valid JSON \u2014 " + pe.getMessage());
            }
            Object structure = JxJson.opt(root, "structure");
            if (structure == null) { JxLog.warn(Refiner.class, "structure refine \u2014 reply has no 'structure'"); return new Result(false, "the model reply had no structure"); }

            Map<String, Block> byId = new LinkedHashMap<>();
            for (Block b : blocks) byId.put(b.id, b);
            Set<String> used = new HashSet<>();

            OCDStruct tree = buildNode(structure, byId, used);
            if (tree == null || tree.isEmpty()) { JxLog.warn(Refiner.class, "structure refine \u2014 empty tree"); return new Result(false, "the model produced an empty structure"); }

            double cov = used.size() / (double) blocks.size();
            p.at("ground", pct(cov) + " of " + blocks.size() + " blocks grounded");
            if (cov < MIN_COVERAGE) {
                JxLog.warn(Refiner.class, "structure refine rejected \u2014 coverage " + pct(cov) + " < " + pct(MIN_COVERAGE));
                return new Result(false, "structure rejected \u2014 only " + pct(cov) + " of blocks grounded (floor " + pct(MIN_COVERAGE) + ")");
            }

            String model = opts != null ? opts.get(ConvertOptions.LLM_MODEL) : "";
            if (model == null || model.isBlank()) model = client.model();

            doc.structures().removeIf(s -> STRUCT_ID.equals(s.id()));   // idempotent re-run
            doc.addStructure(new OCDStructure(STRUCT_ID, "Model \u2014 " + model, OCDStructure.Source.MODEL)
                    .root(tree)
                    .by(model)
                    .at(JxClock.millis())
                    .how("llm-structure/v3")
                    .purpose("LLM logical-structure refinement (grounded by block id, vision-by-signal)"));

            JxLog.info(Refiner.class, "structure refine ok \u2014 " + model + ", coverage " + pct(cov)
                    + " over " + blocks.size() + " blocks");
            return new Result(true, "");
        } catch (Throwable t) {
            JxLog.warn(Refiner.class, "structure refine failed \u2014 falling back to heuristic", t);
            return new Result(false, briefReason(t));
        }
    }

    /** Outcome of a refinement attempt: {@code ok} plus a short human reason when it failed. */
    public record Result(boolean ok, String reason) {}

    /** Pull the actionable provider message out of a transport failure (HTTP body's error.message). */
    private static String briefReason(Throwable t) {
        String m = t.getMessage() != null ? t.getMessage() : t.toString();
        int brace = m.indexOf('{');
        if (brace >= 0) {
            try {
                Object msg = JxJson.opt(JxJson.parse(m.substring(brace)), "error/message");
                if (msg != null) {
                    String head = m.substring(0, brace).replaceAll("[\\u2014\\u2013-]+\\s*$", "").trim();
                    return (head.isEmpty() ? "" : head + " \u2014 ") + msg;
                }
            } catch (Exception ignore) {}
        }
        return m.length() > 200 ? m.substring(0, 200) + "\u2026" : m;
    }

    // ── windowed path: one page at a time, profile anchor + read-only neighbours ────────────────

    /**
     * Scale-invariant refinement: structure each page in its own bounded prompt — a static document
     * profile (global anchor) + the previous/next page as read-only context + the current page's
     * blocks in full — so the task shape never grows with document length (no lost-in-the-middle, no
     * truncation). The model returns the current page's subtree only; {@link StructureReconciler}
     * then owns the one global decision a per-page model cannot make: a consistent heading hierarchy.
     * A page that fails (transport / bad JSON) is skipped and counts against the coverage floor.
     */
    private static Result refineWindowed(OCDDocument doc, ConvertOptions opts, LlmClient client, Progress p, List<Block> blocks, BooleanSupplier cancel) {
        double median  = bodyMedian(blocks);
        String bodyFam = bodyFamily(blocks, median);
        String profile = profileHeader(blocks, median, bodyFam);
        double med     = median <= 0 ? 1 : median;

        Map<Integer, List<Block>> byPage = new TreeMap<>();
        for (Block b : blocks) byPage.computeIfAbsent(b.page, k -> new ArrayList<>()).add(b);
        List<Integer> nums = new ArrayList<>(byPage.keySet());

        Map<String, Integer> sizeByRef = new HashMap<>();          // page#leaf \u2192 size as % of body (for the reconciler)
        for (Block b : blocks) {
            int spct = (int) Math.round(b.size / med * 100);
            for (String leaf : b.leaves) sizeByRef.put(b.page + "#" + leaf, spct);
        }

        OCDStruct root = new OCDStruct(OCDStruct.Type.DOCUMENT);
        Set<String> used = new HashSet<>();
        int okPages = 0, attempted = 0, total = nums.size();
        boolean stopped = false;
        p.at("windowed", total + " pages \u00b7 one window each \u00b7 calling " + client.model() + "\u2026");

        for (int i = 0; i < nums.size(); i++) {
            if (cancelled(cancel)) { stopped = true; break; }                 // stop between pages — keep what's done
            int pageNo = nums.get(i);
            List<Block> cur  = byPage.get(pageNo);
            List<Block> prev = i > 0               ? byPage.get(nums.get(i - 1)) : List.of();
            List<Block> next = i < nums.size() - 1 ? byPage.get(nums.get(i + 1)) : List.of();
            attempted += cur.size();
            p.at("page", "page " + (pageNo + 1) + " (" + (i + 1) + "/" + total + ")");

            String reply;
            try { reply = client.complete(SYSTEM_PAGE, serializeWindow(profile, prev, cur, next, median, bodyFam)); }
            catch (Throwable t) {
                if (isInterruption(t) || cancelled(cancel)) { stopped = true; break; }   // interrupted mid-call → stop, keep partial
                JxLog.warn(Refiner.class, "structure refine \u2014 page " + (pageNo + 1) + " failed, skipped", t);
                continue;
            }
            if (reply == null || reply.isBlank()) continue;

            Object replyRoot;
            try { replyRoot = JxJson.parse(extractJson(reply)); }
            catch (RuntimeException pe) { JxLog.warn(Refiner.class, "structure refine \u2014 page " + (pageNo + 1) + " invalid JSON, skipped (" + pe.getMessage() + ")"); continue; }
            Object structure = JxJson.opt(replyRoot, "structure");
            if (structure == null) continue;

            Map<String, Block> byId = new LinkedHashMap<>();
            for (Block b : cur) byId.put(b.id, b);                  // ground only to this page; neighbour ids are ignored by construction
            OCDStruct sub = buildNode(structure, byId, used);
            if (sub == null || sub.isEmpty()) continue;

            if (!sub.children().isEmpty()) sub.children().forEach(root::add);  // splice the page's children (unwrap its document/section wrapper)
            else root.add(sub);                                               // a flat page carrying only refs
            okPages++;
        }

        if (stopped) Thread.interrupted();   // consume the interrupt so the rest of convert (OCD write) runs cleanly

        if (root.isEmpty()) {
            JxLog.warn(Refiner.class, "structure refine \u2014 " + (stopped ? "cancelled before any page completed" : "empty windowed tree"));
            return new Result(false, stopped ? "cancelled" : "the model produced an empty structure");
        }
        int denom = stopped ? Math.max(1, attempted) : blocks.size();        // partial run: score over the pages we reached
        double cov = used.size() / (double) denom;
        p.at(stopped ? "stopped" : "ground", pct(cov) + " of " + denom + " blocks over " + okPages + "/" + total
                + " pages" + (stopped ? " \u2014 stopped early, keeping partial structure" : ""));
        if (cov < MIN_COVERAGE) { JxLog.warn(Refiner.class, "structure refine rejected \u2014 coverage " + pct(cov) + " < " + pct(MIN_COVERAGE)); return new Result(false, "structure rejected \u2014 only " + pct(cov) + " of blocks grounded (floor " + pct(MIN_COVERAGE) + ")"); }

        StructureReconciler.normalizeHeadingLevels(root, sizeByRef);          // Java owns the global heading hierarchy
        commit(doc, opts, client, root, cov, denom, stopped ? "llm-structure/v4-windowed-partial" : "llm-structure/v4-windowed");
        return new Result(true, stopped ? "stopped at " + okPages + "/" + total + " pages \u2014 partial structure kept" : "");
    }

    /** Stop signal: an explicit canceller from the host, or this worker thread being interrupted. */
    private static boolean cancelled(BooleanSupplier cancel) {
        return Thread.currentThread().isInterrupted() || (cancel != null && cancel.getAsBoolean());
    }

    /** True if {@code t} is (or wraps) an {@link InterruptedException} — a blocking model call cut short. */
    private static boolean isInterruption(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause())
            if (c instanceof InterruptedException) return true;
        return false;
    }

    /** Append the finished tree as the single {@code MODEL} structure, with provenance. Shared by both paths. */
    private static Result commit(OCDDocument doc, ConvertOptions opts, LlmClient client, OCDStruct tree, double cov, int n, String how) {
        String model = opts != null ? opts.get(ConvertOptions.LLM_MODEL) : "";
        if (model == null || model.isBlank()) model = client.model();
        doc.structures().removeIf(s -> STRUCT_ID.equals(s.id()));            // idempotent re-run
        doc.addStructure(new OCDStructure(STRUCT_ID, "Model \u2014 " + model, OCDStructure.Source.MODEL)
                .root(tree).by(model).at(JxClock.millis()).how(how)
                .purpose("LLM logical-structure refinement (grounded by block id, vision-by-signal)"));
        JxLog.info(Refiner.class, "structure refine ok (" + how + ") \u2014 " + model + ", coverage " + pct(cov) + " over " + n + " blocks");
        return new Result(true, "");
    }

    /** The static document profile (global anchor): char-weighted size histogram \u2192 body size, the top
     *  sizes as multiples of body, the dominant families + bold share, mono presence, the typical gap.
     *  Identical in every window, so it never breaks the per-page task's stationarity. */
    private static String profileHeader(List<Block> blocks, double median, String bodyFam) {
        double med = median <= 0 ? 1 : median;
        Map<Integer, Long> sizeChars = new TreeMap<>();
        Map<String, Long>  famChars  = new LinkedHashMap<>();
        long boldChars = 0, totalChars = 0; boolean mono = false;
        List<Integer> gaps = new ArrayList<>();
        for (Block b : blocks) {
            if (!"text".equals(b.kind)) continue;
            long w = Math.max(1, b.text.length());                 // weight by characters, not by block (PDFBoT BASE_FS)
            sizeChars.merge((int) Math.round(b.size), w, Long::sum);
            if (b.family != null && !b.family.isEmpty()) famChars.merge(b.family, w, Long::sum);
            if (b.bold) boldChars += w;
            if (b.mono) mono = true;
            totalChars += w;
            if (b.gapAbove > 0) gaps.add(b.gapAbove);
        }
        List<Map.Entry<Integer, Long>> bySize = new ArrayList<>(sizeChars.entrySet());
        bySize.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        StringBuilder sizes = new StringBuilder("[");
        for (int i = 0; i < Math.min(5, bySize.size()); i++) {
            if (i > 0) sizes.append(',');
            sizes.append(Math.round(bySize.get(i).getKey() / med * 100));   // relative to body (100 = body)
        }
        sizes.append(']');
        List<Map.Entry<String, Long>> byFam = new ArrayList<>(famChars.entrySet());
        byFam.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));

        JxStringer js = new JxStringer(256).obj();
        js.num("bodySizePt", Math.round(med));
        js.raw("topSizesPctOfBody", sizes.toString());
        js.arr("families");
        for (int i = 0; i < Math.min(3, byFam.size()); i++) js.str(byFam.get(i).getKey());
        js.end();
        js.num("boldPct", totalChars == 0 ? 0 : Math.round(100.0 * boldChars / totalChars));
        if (mono) js.num("mono", 1);
        js.num("medianGap", medianOf(gaps));
        return js.end().toString();
    }

    private static int medianOf(List<Integer> xs) {
        return (int) Math.round(JxNum.median(xs.stream().mapToDouble(Integer::doubleValue).toArray()));
    }

    /** One page's window: profile + read-only neighbours (condensed) + the current page in full, the
     *  current page LAST so it lands in the prompt's high-attention slot (vs lost-in-the-middle). */
    private static String serializeWindow(String profile, List<Block> prev, List<Block> cur, List<Block> next, double median, String bodyFam) {
        JxStringer js = new JxStringer(cur.size() * 160 + 1024).obj();
        js.raw("documentProfile", profile);
        js.obj("context");
        js.raw("prev", condense(prev, median, bodyFam));
        js.raw("next", condense(next, median, bodyFam));
        js.end();
        js.raw("page", serialize(cur, median, bodyFam));           // full per-block signals, reused from the one-shot serializer
        return js.end().toString();
    }

    /** A neighbour page, read-only: just enough to resolve boundaries (id, relative size, a few flags, short text). */
    private static String condense(List<Block> blocks, double median, String bodyFam) {
        double med = median <= 0 ? 1 : median;
        JxStringer js = new JxStringer(Math.max(16, blocks.size() * 48)).arr();
        for (Block b : blocks) {
            js.obj().str("id", b.id).num("size", Math.round(b.size / med * 100));
            if (b.bold)    js.num("bold", 1);
            if (b.caps)    js.num("caps", 1);
            if (b.running) js.num("running", 1);
            js.str("kind", b.kind).str("text", capN(b.text, 80)).end();
        }
        return js.end().toString();
    }

    private static String capN(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "\u2026"; }

    // ── the per-page task framing (current page only; the host renormalises depth globally) ──────

    private static final String SYSTEM_PAGE = """
        You reconstruct the LOGICAL STRUCTURE of ONE PAGE of a document at a time.

        You cannot see the page; each block carries the visual signals a reader would use. The input is
        a JSON object:
          documentProfile — document-wide typographic anchor (constant across pages):
            bodySizePt — the body text size in points;  topSizesPctOfBody — the most common sizes as a
            percentage of body (100 = body);  families — the dominant font families;  boldPct — share of
            bold text;  mono — present if the document uses monospaced text;  medianGap — the typical gap.
            Use it to calibrate what "large/small" means IN THIS DOCUMENT (a 16pt line is a heading only
            if the body is ~10pt).
          context — the PREVIOUS (prev) and NEXT (next) page, READ-ONLY, condensed. Use them only to
            judge whether this page's first/last block continues a neighbour. NEVER reference a context
            id: they are not valid grounding targets.
          page — the blocks of the CURRENT page, in reading order, each with:
            id (reference ONLY these, by exact id), box [x0,y0,x1,y1] on a 0..1000 top-left grid,
            size (% of body, >120 = likely heading), col, indent, width, gapAbove (whitespace above —
            large = a section opens), lines, bold, italic, caps, ends (sentence punctuation — typical of
            body, rare for headings), bullet, mono, face (display/heading typeface), running (DROP these),
            digits, rot, align, color, bg, kind (text|image|graphic), text (possibly truncated; leading
            "Chapter 3", "2.1", "Figure 4:" are strong cues).

        TASK: emit the logical subtree for the CURRENT page only. Combine the signals as a reader would —
        a short, large, bold, capitalised or coloured line with a big gapAbove and no sentence-ending
        opens a section; following blocks nest under it. Group bulleted/indented blocks under a list,
        rows/cells under a table, attach a caption to its figure. Send every "running" block to "dropped".
        For headings set "level" by RELATIVE size WITHIN THIS PAGE (1 = the largest heading here) — do NOT
        try to make levels consistent with other pages; the host renormalises heading depth across the
        whole document afterwards.

        OUTPUT: a single minified JSON object, NOTHING ELSE (no prose, no markdown fences):
          { "structure": { "type":"document", "children":[ <node>, ... ] },
            "dropped":   [ { "ref":"<id>", "reason":"running header" }, ... ] }
        where <node> is:
          { "type":"section|heading|paragraph|list|item|table|row|cell|figure|caption|quote|code|note|toc|span|other",
            "level":<int, headings only, 1 = largest on this page>, "ordered":<bool, lists only>,
            "refs":[ "<block id>", ... ], "children":[ <node>, ... ] }

        RULES:
          - refs MUST be ids from the "page" array; ids from context or invented ids are discarded.
          - Every CURRENT-page block should appear once, in "structure" or in "dropped".
          - refs are ids ONLY. NEVER echo block text or any field not listed above (it truncates the reply).
          - Emit ONE minified JSON object on a single line. Output deterministically.
        """;

    // Block harvesting, spatial enrichment and the perceptual signals now live in
    // BlockSignals / Block — this pass only serializes what they produce.

    private static double bodyMedian(List<Block> blocks) {
        List<Double> sizes = new ArrayList<>();
        for (Block b : blocks) if ("text".equals(b.kind) && b.size > 0) sizes.add(b.size);
        if (sizes.isEmpty()) return 1;
        sizes.sort(Double::compare);
        return sizes.get(sizes.size() / 2);
    }

    /** The most common font family among body-size text blocks — the reference for the `face` flag. */
    private static String bodyFamily(List<Block> blocks, double median) {
        Map<String, Integer> freq = new HashMap<>();
        for (Block b : blocks) {
            if (!"text".equals(b.kind) || b.family == null || b.family.isEmpty()) continue;
            if (b.size < 0.85 * median || b.size > 1.2 * median) continue;
            freq.merge(b.family.toLowerCase(), 1, Integer::sum);
        }
        String best = ""; int n = -1;
        for (Map.Entry<String, Integer> e : freq.entrySet())
            if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        return best;
    }

    // ── 2 · serialise blocks for the model (grounded, vision-by-signal) ─────────────────────────

    private static String serialize(List<Block> blocks, double median, String bodyFam) {
        double med = median <= 0 ? 1 : median;
        JxStringer js = new JxStringer(blocks.size() * 144).arr();
        for (Block b : blocks) {
            boolean face = !b.family.isEmpty() && !b.family.toLowerCase().equals(bodyFam) && !bodyFam.isEmpty();
            js.obj()
              .str("id", b.id)
              .num("page", b.page)
              .raw("box", "[" + b.gx0 + "," + b.gy0 + "," + b.gx1 + "," + b.gy1 + "]")
              .num("size", Math.round(b.size / med * 100))   // 100 = body, 160 = 1.6× (heading-ish)
              .num("col", b.col)
              .num("indent", b.indent)
              .num("width", b.width);
            if (b.gapAbove >= 0) js.num("gapAbove", b.gapAbove);
            js.num("lines", b.lines);
            if (b.bold)            js.num("bold", 1);
            if (b.italic)          js.num("italic", 1);
            if (b.caps)            js.num("caps", 1);
            if (b.ends)            js.num("ends", 1);
            if (b.bullet)          js.num("bullet", 1);
            if (b.mono)            js.num("mono", 1);
            if (face)              js.num("face", 1);
            if (b.running)         js.num("running", 1);
            if (b.digits >= DIGIT_MIN) js.num("digits", b.digits);
            if (b.rot != 0)        js.num("rot", b.rot);
            if (!b.align.isEmpty()) js.str("align", b.align);
            if (!b.color.isEmpty()) js.str("color", b.color);
            if (!b.bg.isEmpty())    js.str("bg", b.bg);
            js.str("kind", b.kind)
              .str("text", cap(b.text))
              .end();
        }
        return js.end().toString();
    }

    // ── 3 · parse the model reply back into a grounded OCDStruct tree ────────────────────────────

    private static OCDStruct buildNode(Object o, Map<String, Block> byId, Set<String> used) {
        Map<String, Object> m = JxJson.asObj(o);
        if (m == null) return null;
        OCDStruct s = new OCDStruct(typeOf(JxJson.str(m, "type")));
        if (JxJson.has(m, "level"))   s.level((int) JxJson.lng(m, "level"));
        if (JxJson.has(m, "ordered")) s.ordered(JxJson.bool(m, "ordered"));
        if (JxJson.has(m, "text"))    s.text(JxJson.str(m, "text"));
        if (JxJson.has(m, "refs"))
            for (Object rr : JxJson.arr(m, "refs")) {
                Block b = byId.get(String.valueOf(rr));                      // grounding: a known block id only
                if (b == null) continue;
                for (String leaf : b.leaves) s.addRef(b.page, leaf);         // expand to leaves
                used.add(b.id);
            }
        if (JxJson.has(m, "children"))
            for (Object c : JxJson.arr(m, "children")) {
                OCDStruct ch = buildNode(c, byId, used);
                if (ch != null && !ch.isEmpty()) s.add(ch);
            }
        return s;
    }

    private static OCDStruct.Type typeOf(String s) {
        if (s == null) return OCDStruct.Type.OTHER;
        try { return OCDStruct.Type.valueOf(s.trim().toUpperCase()); }
        catch (Exception e) { return OCDStruct.Type.OTHER; }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────


    private static String pct(double f)    { return Math.round(f * 100) + "%"; }
    private static String cap(String s)    { return s.length() <= TEXT_CAP ? s : s.substring(0, TEXT_CAP) + "…"; }
    private static String collapse(String s) { return JxText.collapse(s); }

    /** Pull the first complete top-level JSON object out of a model reply, tolerant of markdown
     *  fences, a prose preamble, and trailing commentary. Scans brace depth while respecting strings
     *  and escapes, so quotes (or braces) inside values never fool it. If the object never closes
     *  (a truncated reply), returns the tail from the first brace so the parser reports it and the
     *  caller can log the cut. */
    private static String extractJson(String s) {
        if (s == null) return "";
        int start = s.indexOf('{');
        if (start < 0) return s.strip();
        boolean inStr = false, esc = false; int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
            } else if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(start, i + 1);
        }
        return s.substring(start);                 // unbalanced (truncated) → let the parser report
    }

    /** A single-line, bounded slice of a reply for diagnostics (control chars rendered as spaces). */
    private static String snippet(String s, int from, int to) {
        if (s == null) return "";
        from = Math.max(0, from); to = Math.min(s.length(), to);
        if (from >= to) return "";
        return s.substring(from, to).replaceAll("[\\u0000-\\u001F]", " ");
    }

    // ── the task framing (answer grammar + grounding contract) ──────────────────────────────────

    private static final String SYSTEM = """
        You reconstruct the LOGICAL STRUCTURE of a document from its content blocks.

        You cannot see the page, but each block carries the visual signals a reader would use.
        INPUT: a JSON array of blocks in reading order. Each block has:
          id      — stable identifier; reference blocks ONLY by this exact id, never invent ids
          page    — 0-based page index (parity = page%2 tells recto/verso for mirrored layouts)
          box     — [x0,y0,x1,y1] on a 0..1000 grid, origin top-left (smaller y = higher on the page)
          size    — font size relative to body text (100 = body, >120 = likely a heading)
          col     — 0-based column index the block sits in (multi-column pages)
          indent  — left offset from its column's left margin, 0..1000 (large = indented / quote / item body)
          width   — block width as a fraction of its column, 0..1000 (1000 = full column width)
          gapAbove— vertical whitespace above the block in grid units (large = a new section opens here); absent if top of column
          lines   — number of visual text lines (1–2 short lines often = a heading)
          bold    — present(=1) if bold
          italic  — present(=1) if italic (emphasis, captions, quotes)
          caps    — present(=1) if (near) ALL CAPS (heading or label)
          ends    — present(=1) if the text ends with sentence punctuation (. ! ?) — typical of BODY prose, rare for a heading
          bullet  — present(=1) if the text begins with a list marker
          mono    — present(=1) if a monospace font (code / preformatted)
          face    — present(=1) if the font family differs from the document body face (display / heading typeface)
          running — present(=1) if already identified as a running header/footer — DROP these
          digits  — percentage of digit characters when high (numeric-dense: table cell, data row, page number)
          rot     — text rotation in degrees when notably rotated (vertical labels, watermarks)
          align   — left | center | right | justify (justify/left long blocks = body; center short = title/caption)
          color   — hex of the text colour when not default black (a hue often marks a heading or link)
          bg      — hex of the block's background colour when shaded (callout box, code block, table header, highlight)
          kind    — text | image | graphic
          text    — the (possibly truncated) block text; leading words/numbering ("Chapter 3", "2.1", "Figure 4:") are strong cues

        TASK: emit the document's logical tree. Combine the signals as a reader would — a short, large,
        bold, capitalised or coloured line with a big gapAbove and no sentence-ending opens a section;
        following blocks nest under it until a same-or-higher heading appears. Use the leading text
        ("Chapter", "Section", "Art.", "1.2.3") to fix heading DEPTH and recover the GLOBAL hierarchy
        across pages (the hardest part). A `bg` or `mono` block is often a code block, callout or table
        header; group bulleted/indented blocks under a list, rows/cells under a table, and attach a
        caption to the figure/image it describes. Send every "running" block to "dropped", plus any
        other running header/footer/page number you spot.

        OUTPUT: a single JSON object, NOTHING ELSE (no prose, no markdown fences):
          {
            "structure": { "type": "document", "children": [ <node>, ... ] },
            "dropped": [ { "ref": "<id>", "reason": "running header" }, ... ]
          }
        where <node> is:
          {
            "type": "section|heading|paragraph|list|item|table|row|cell|figure|caption|quote|code|note|toc|span|other",
            "level": <int, headings only, 1 = top>,
            "ordered": <bool, lists only>,
            "refs": [ "<block id>", ... ],
            "children": [ <node>, ... ]
          }

        RULES:
          - refs MUST be ids that appear in the input; any other id will be discarded.
          - Every content block should appear exactly once, either in "structure" or in "dropped".
          - A node has refs, or children, or both. Headings/paragraphs/items/cells usually carry refs;
            document/section/list/table/figure usually carry children.
          - refs are ids ONLY. NEVER echo block text: do not emit text, text_content, label, content,
            or ANY field not listed above. Echoing text overflows the token budget and truncates the reply.
          - Emit ONE minified JSON object on a single line: no indentation, no line breaks, no markdown.
          - Output deterministically. Return only the JSON object.
        """;
}
