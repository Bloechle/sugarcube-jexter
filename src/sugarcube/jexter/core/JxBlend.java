package sugarcube.jexter.core;

import java.util.Locale;

/**
 * The PDF blend modes (ISO 32000-1, Table 136) — THE name table of every layer that speaks one.
 *
 * <p>The model stores a mode by its PDF name ({@link #pdf}) and stores no Normal ({@link #model});
 * the SVG writers spell it as CSS ({@link #css}); the renderer composites by the constant; PDF import
 * and export cross to PDFBox through the name. One table, so no layer can know a mode another drops.
 */
public enum JxBlend {
    NORMAL("Normal"), MULTIPLY("Multiply"), SCREEN("Screen"), OVERLAY("Overlay"),
    DARKEN("Darken"), LIGHTEN("Lighten"), COLOR_DODGE("ColorDodge"), COLOR_BURN("ColorBurn"),
    HARD_LIGHT("HardLight"), SOFT_LIGHT("SoftLight"), DIFFERENCE("Difference"), EXCLUSION("Exclusion"),
    HUE("Hue"), SATURATION("Saturation"), COLOR("Color"), LUMINOSITY("Luminosity");

    /** The PDF name, as the model stores it. */
    public final String pdf;

    JxBlend(String pdf) { this.pdf = pdf; }

    /** The CSS spelling: {@code ColorDodge} → {@code color-dodge}. Lower-casing alone gives
     *  {@code colordodge}, which no browser knows: it drops the declaration and paints Normal. */
    public String css() { return pdf.replaceAll("(?<=[a-z])(?=[A-Z])", "-").toLowerCase(Locale.ROOT); }

    /** The mode {@code name} spells, in its PDF or its CSS form (case and hyphens ignored); Normal for
     *  null, blank, {@code Compatible} (PDF 1.4's alias of Normal) or a name no table knows. */
    public static JxBlend of(String name) {
        if (name == null) return NORMAL;
        String key = name.replace("-", "").trim();
        for (JxBlend b : values()) if (b.pdf.equalsIgnoreCase(key)) return b;
        return NORMAL;
    }

    /** The model's value for {@code name}: its PDF spelling, or null for Normal — a node without a
     *  blend IS Normal, so the model never stores one. */
    public static String model(String name) {
        JxBlend b = of(name);
        return b == NORMAL ? null : b.pdf;
    }
}
