package sugarcube.jexter.ocd.analysis;

import java.util.List;

/** The per-block feature vector harvested by {@link BlockSignals} and serialized by {@link Refiner} for the
 *  LLM structure pass — id + grid box + typographic / textual / spatial / colour signals. Package-private. */
final class Block {
        String id; int page; int gx0, gy0, gx1, gy1;       // grid box (top-down)
        double size; boolean bold, italic, bullet, caps, ends, mono; // typographic / textual
        int    digits, rot, lines; String align, color, family;      // density / rotation / silhouette / colour / face
        boolean running; String bg;                         // running head/foot tag; background colour ("" = none)
        int    col, indent, width, gapAbove;                // spatial, relative to column (grid units)
        String kind, text; List<String> leaves;
    }
