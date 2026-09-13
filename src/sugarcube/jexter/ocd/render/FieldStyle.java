package sugarcube.jexter.ocd.render;

import sugarcube.jexter.core.JxRect;

import java.awt.Color;

/**
 * THE appearance of a form field — one palette, one geometry, consumed by every renderer.
 *
 * <p>A field is <b>live data</b>, not baked ink: the widget's PDF appearance stream is deliberately not
 * imported, so a renderer draws the control itself. Which means the control needs a design, and the design
 * needs one home — the two renderers previously carried the same colours written twice, as AWT triples on
 * one side and hex on the other, already drifting apart.
 *
 * <p>The design is deliberately <b>neutral greyscale</b>: a field belongs to the document it sits in, not to
 * jexter, and any hue would be a brand claiming a page it does not own. Flat surface, hairline border,
 * generous corner radius, no bevel and no inset shadow — a control drawn today rather than a reproduction of
 * a 1996 widget.
 *
 * <p><b>Every control keeps the same surface, checked or not.</b> Ticking a box changes what is ON the
 * control, not what the control IS, so the state is carried by ink alone — the tick — exactly as a value is
 * in a text field. Filling the box dark instead would substitute value for the accent hue this palette
 * refuses to have, and a solid dark block is the loudest mark on a page it does not own: in a document,
 * a form mark is ink on paper.
 *
 * <p>Every length is in POINTS, the page's own unit, so a field looks the same at any raster scale.
 */
public final class FieldStyle {

    private FieldStyle() {}

    /** Surface of an empty control — near-white, so a value stays legible over any page background. */
    public static final String SURFACE_HEX = "#fafbfc";
    /** Face of a BUTTON. A text field is a hollow you write in, a button is a solid you press, and in a
     *  greyscale palette only the value of the fill can say which. Deliberately a LIGHT neutral rather than
     *  a dark one: a dark button asserts a primary action, and a generic renderer has no business deciding
     *  that a document's button is the important one. */
    public static final String FACE_HEX    = "#e7eaee";
    /** Hairline border. Light enough to disappear into the page, dark enough to bound the control. */
    public static final String BORDER_HEX  = "#c9ced4";
    /** Value text, and the fill of a checked box. */
    public static final String INK_HEX     = "#2b3138";
    /** Unused hue slot kept out of the palette on purpose — see the class note. */

    public static final Color SURFACE = new Color(0xFA, 0xFB, 0xFC, 0xF2);   // barely translucent: the page shows through
    public static final Color FACE    = new Color(0xE7, 0xEA, 0xEE, 0xF7);   // a button is more solid than a hollow
    public static final Color BORDER  = new Color(0xC9, 0xCE, 0xD4, 0xFF);
    public static final Color INK     = new Color(0x2B, 0x31, 0x38, 0xFF);

    public static final double BORDER_W = 0.75;   // a hairline at 96 dpi
    public static final double TICK_W   = 1.75;   // round caps and joins; dark-on-light reads thinner
    public static final double PAD_X    = 4.5;    // text inset from the left edge

    /** Corner radius: generous but never more than the control can carry. */
    public static double radius(JxRect r) {
        return Math.min(3.5, Math.min(r.width(), r.height()) * 0.28);
    }

    /** A BUTTON carries a softer corner than an input — the one shape cue that survives at any size, and
     *  the reason a chip reads as pressable where a rectangle reads as a slot. */
    public static double buttonRadius(JxRect r) {
        return Math.min(r.height() * 0.42, 6.0);
    }

    /** A button's label sits a touch tighter than a value: it is a word, not a datum. */
    public static double buttonTextSize(JxRect r) {
        return Math.min(r.height() * 0.46, 9.5);
    }

    /** Baseline for a centred button label. */
    public static double buttonBaseline(JxRect r) {
        return r.y() + (r.height() - buttonTextSize(r) * 0.72) / 2;
    }

    /** Value text size: fills the control without touching its edges. */
    public static double textSize(JxRect r) {
        return Math.min(r.height() * 0.56, 10.5);
    }

    /** Baseline for vertically centred value text (page space, Y up). */
    public static double baseline(JxRect r) {
        return r.y() + (r.height() - textSize(r) * 0.72) / 2;
    }

    /** The tick, as three points {@code x0,y0, x1,y1, x2,y2} in page space (Y up) — a short down-stroke
     *  into a long up-stroke, set off-centre the way a drawn check is, never a symmetrical V. */
    public static double[] tick(JxRect r) {
        double w = r.width(), h = r.height();
        return new double[] {
                r.x() + w * 0.26, r.y() + h * 0.52,
                r.x() + w * 0.44, r.y() + h * 0.32,
                r.x() + w * 0.76, r.y() + h * 0.70 };
    }

    /** Checkbox and radio share the control; only the corner radius differs (a radio is fully round). */
    public static double radioRadius(JxRect r) {
        return Math.min(r.width(), r.height()) / 2;
    }

    /** A selected RADIO is a filled dot, never a tick: one of a set is chosen, not asserted. Returns
     *  {@code cx, cy, radius} in page space. */
    public static double[] dot(JxRect r) {
        return new double[] { r.x() + r.width() / 2, r.y() + r.height() / 2,
                              Math.min(r.width(), r.height()) * 0.23 };
    }

    /** The caret of a CHOICE, as three points — the one mark that separates a closed list from a text
     *  field. Without it a reader cannot tell that the control holds a choice, which is information the
     *  DOCUMENT carries, not decoration. Sits in the right inset, vertically centred. */
    public static double[] chevron(JxRect r) {
        double s = Math.min(r.height() * 0.22, 3.0);
        double cx = r.x() + r.width() - PAD_X - s, cy = r.y() + r.height() / 2 + s * 0.35;
        return new double[] { cx - s, cy, cx, cy - s, cx + s, cy };
    }

    /** A SIGNATURE field is a place to sign: a hairline rule sitting where the pen would, so it reads as
     *  an invitation rather than as an empty text input. Returns {@code x0, y, x1, y}. */
    public static double[] rule(JxRect r) {
        double y = r.y() + r.height() * 0.28;
        return new double[] { r.x() + PAD_X * 1.5, y, r.x() + r.width() - PAD_X * 1.5, y };
    }
}
