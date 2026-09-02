package sugarcube.jexter.ocd.model;

/**
 * A paragraph — a first-class container of text runs ({@link OCDText}) with
 * {@link OCDBreak} line-break tokens between visual lines. Replaces the former
 * {@code OCDGroup} of kind {@code PARAGRAPH}.
 *
 * <p>There is no longer a LINE node level: a visual line is simply the maximal span
 * of runs between two breaks, recovered by reading the children in order, so the line
 * structure is deducible straight from the serialized form (the {@code <br/>} tokens)
 * without re-running baseline analysis.
 *
 * <p>Extends {@link OCDGroup} so it composites and is traversed exactly like any other
 * container — renderers and writers recurse through it via their existing group case;
 * only its serialization ({@code <paragraph>}) and the analysis layer treat it
 * specially.
 */
public final class OCDParagraph extends OCDGroup {

    /** Text-flow identity, page-scoped; {@code -1} = this paragraph is a whole flow (the normal case). */
    private int flow = -1;

    public OCDParagraph() { super(); }

    @Override public OCDParagraph add(OCDNode c) { super.add(c); return this; }

    /** The flow this paragraph is a FRAGMENT of, or {@code -1} when it is a whole one.
     *
     *  <p>A paragraph must wrap a <b>contiguous paint span</b> — stored atomically in the OCD-EPUB page, a
     *  wrapper straddling a foreign node's {@code z} would serialize that node after the whole paragraph and
     *  invert the painted result. So a text block whose runs are interleaved in paint order (a knockout label
     *  and its tile, two blocks emitted alternately) is split into several paragraphs, and they all carry the
     *  same {@code flow}: the split is a fact of the PRESENTATION layer, and this is what lets the logical
     *  layer refuse to inherit it ({@code StructureBuilder} rejoins one flow into one PARAGRAPH). */
    public int flow()           { return flow; }
    public OCDParagraph flow(int v) { this.flow = v; return this; }
    public boolean isFragment() { return flow >= 0; }
}
