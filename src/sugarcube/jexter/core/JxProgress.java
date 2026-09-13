package sugarcube.jexter.core;

/**
 * One step of a conversion, as the engine reports it — the single progress vocabulary shared by every
 * stage and every host.
 *
 * <p>A conversion is one long call: on a thousand-page book the import alone runs ~26 s without a word,
 * which is exactly what "it seems stuck" means to whoever is waiting. This is the channel that says
 * otherwise. It carries what a viewer needs to lay a book out before it has one — the page count and the
 * first page's size arrive with {@link Stage#OPEN}, about a tenth of a second in — then counts the work
 * down.
 *
 * <p>It is deliberately <b>not</b> a static sink. A {@link Sink} is bound per conversion, on the
 * {@code ConvertOptions} that already travel through every stage, so two documents converting side by
 * side never cross: one document per thread, many documents in parallel, is the engine's concurrency
 * contract and nothing here weakens it. A conversion with no sink bound reports into a no-op.
 *
 * @param stage  which half of the pipeline is speaking
 * @param done   units finished (pages, mostly); 0 when the stage only announces itself
 * @param total  units to do, or 0 when the stage cannot say
 * @param detail free text for the stage that has something to add — the page size at {@link Stage#OPEN},
 *               the target at {@link Stage#WRITE}; never parsed, only shown
 */
public record JxProgress(Stage stage, int done, int total, String detail) {

    /** The pipeline as a waiting user experiences it: the document opens, its fonts are checked, then it
     *  is imported, analysed, written. {@link #FONTS} is its own stage because it is document-wide work
     *  that runs before a single page is touched — measured at 1.7 s on a 1014-page book, and silent
     *  until it was named. */
    public enum Stage { OPEN, FONTS, IMPORT, ANALYSIS, WRITE }

    /** Where a host receives them. Bound per conversion; must tolerate being called from the worker thread. */
    @FunctionalInterface
    public interface Sink {
        void at(JxProgress p);

        /** The sink of a conversion nobody is watching. */
        Sink NONE = p -> { };
    }

    public JxProgress(Stage stage, int done, int total) { this(stage, done, total, ""); }

    /** The wire shape: one JSON object, the same keys as the record. */
    public String toJson() {
        return new JxStringer().obj()
                .str("stage", stage.name().toLowerCase(java.util.Locale.US))
                .num("done", done).num("total", total)
                .str("detail", detail == null ? "" : detail)
                .end().toString();
    }
}
