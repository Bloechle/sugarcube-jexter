package sugarcube.jexter.ocd.model;

import sugarcube.jexter.core.JxRect;

import java.util.ArrayList;
import java.util.List;

/**
 * An interactive form field on a page — one widget of a PDF AcroForm field, flattened to its
 * essentials: {@link Field kind}, fully-qualified {@link #name}, current {@link #value} (and
 * {@link #defaultValue}), the selectable {@link #options} for choice/radio kinds, the common
 * flags, and the widget {@link #rect}. A field with several widgets (e.g. a radio group) yields
 * one {@code OCDFormField} per widget, all sharing the name.
 *
 * <p>Self-contained value object stored in {@link OCDPage#fields()} alongside {@link OCDLink} and
 * {@link OCDAnnotation}. Geometry is page content space (origin bottom-left).
 */
public final class OCDFormField {

    /** The field kind — pure classification, mirrors the AcroForm field types. */
    public enum Field { TEXT, CHECKBOX, RADIO, CHOICE, BUTTON, SIGNATURE, OTHER }

    private Field   type = Field.OTHER;
    private JxRect  rect;
    private String  name         = "";     // fully-qualified field name
    private String  value        = "";     // current value (as string)
    private String  defaultValue = "";     // /DV
    private final List<String> options = new ArrayList<>();   // choice/radio export values
    private String  onState      = "";     // THIS widget's export name (/AP /N key other than Off)
    private boolean readOnly, required, multiline;

    // ── what the WIDGET is. A field is live data, so nothing here is ink: these are the facts a renderer
    // needs to draw the control and the PDF writer needs to state it back as a real AcroForm. A colour of
    // 0 (fully transparent) means NOT STATED — the source said nothing and a writer must not invent one.
    // The format states, it never infers.
    private int     rotation;              // /MK /R — the widget's own quarter turn INSIDE its rect
    private int     align;                 // /Q — 0 left, 1 centred, 2 right
    private double  fontSize;              // /DA size, points; 0 = auto-size, exactly as PDF states it
    private int     textColor;             // /DA colour, argb
    private int     borderColor;           // /MK /BC, argb
    private int     backColor;             // /MK /BG, argb
    private double  borderWidth = 1;       // /BS /W, points — PDF's own default
    private int     maxLen;                // /MaxLen; 0 = unbounded
    private boolean hidden;                // /F bit 2 — a widget no surface may paint

    public OCDFormField() {}
    public OCDFormField(Field type) { this.type = type; }

    public Field        type()              { return type; }
    public OCDFormField type(Field t)       { this.type = t == null ? Field.OTHER : t; return this; }
    public JxRect       rect()              { return rect; }
    public OCDFormField rect(JxRect r)      { this.rect = r; return this; }
    public String       name()              { return name; }
    public OCDFormField name(String v)      { this.name = nz(v); return this; }
    public String       value()             { return value; }
    public OCDFormField value(String v)     { this.value = nz(v); return this; }
    public String       defaultValue()      { return defaultValue; }
    public OCDFormField defaultValue(String v){ this.defaultValue = nz(v); return this; }
    /** THIS widget's own export name — the {@code /AP /N} key that is not {@code Off}.
     *
     *  <p>A field's {@link #value()} belongs to the FIELD; a radio group has one value and as many widgets
     *  as buttons, so the value alone cannot say WHICH button carries it. Without the widget's own name
     *  every button of a group reads as selected (measured). Empty when the widget has no on-state — a text
     *  field, a signature — in which case {@link #isOn()} falls back to reading the value itself. */
    public String       onState()           { return onState; }
    public OCDFormField onState(String v)   { this.onState = nz(v); return this; }

    /** Is THIS widget the one that is on? The field's value carries the answer, but only when compared
     *  against the widget's own export name. */
    public boolean isOn() {
        if (!onState.isEmpty()) return onState.equals(value);
        return !value.isEmpty() && !"Off".equalsIgnoreCase(value) && !"0".equals(value) && !"false".equalsIgnoreCase(value);
    }

    public List<String> options()           { return options; }
    public OCDFormField addOption(String o) { if (o != null && !o.isEmpty()) options.add(o); return this; }
    public boolean      readOnly()          { return readOnly; }
    public OCDFormField readOnly(boolean b) { this.readOnly = b; return this; }
    public boolean      required()          { return required; }
    public OCDFormField required(boolean b) { this.required = b; return this; }
    public boolean      multiline()         { return multiline; }
    public OCDFormField multiline(boolean b){ this.multiline = b; return this; }

    /** The widget's own rotation ({@code /MK /R}), a quarter turn inside {@link #rect()} — INDEPENDENT of
     *  the page's. A form laid out on a page turned 90 degrees carries it on every widget, and a writer
     *  that drops it writes the values across the boxes. */
    public int          rotation()          { return rotation; }
    public OCDFormField rotation(int deg)   { this.rotation = ((deg % 360) + 360) % 360; return this; }
    /** Quadding ({@code /Q}): 0 left, 1 centred, 2 right. */
    public int          align()             { return align; }
    public OCDFormField align(int q)        { this.align = q < 0 || q > 2 ? 0 : q; return this; }
    /** The value's size in points, from {@code /DA}. {@code 0} is PDF's own word for AUTO-SIZE — a stated
     *  value, not a missing one. */
    public double       fontSize()          { return fontSize; }
    public OCDFormField fontSize(double pt) { this.fontSize = pt < 0 ? 0 : pt; return this; }
    /** The value's colour, argb; {@code 0} = not stated. */
    public int          textColor()         { return textColor; }
    public OCDFormField textColor(int argb) { this.textColor = argb; return this; }
    /** {@code /MK /BC}, argb; {@code 0} = no border colour stated, so none is drawn. */
    public int          borderColor()       { return borderColor; }
    public OCDFormField borderColor(int c)  { this.borderColor = c; return this; }
    /** {@code /MK /BG}, argb; {@code 0} = no background — the page shows through. */
    public int          backColor()         { return backColor; }
    public OCDFormField backColor(int c)    { this.backColor = c; return this; }
    /** {@code /BS /W} in points. */
    public double       borderWidth()       { return borderWidth; }
    public OCDFormField borderWidth(double w){ this.borderWidth = w < 0 ? 0 : w; return this; }
    /** {@code /MaxLen}; 0 = unbounded. */
    public int          maxLen()            { return maxLen; }
    public OCDFormField maxLen(int n)       { this.maxLen = n < 0 ? 0 : n; return this; }
    /** The widget's hidden flag ({@code /F} bit 2): stated by the document, honoured by every surface. */
    public boolean      hidden()            { return hidden; }
    public OCDFormField hidden(boolean b)   { this.hidden = b; return this; }

    private static String nz(String v) { return v == null ? "" : v; }

    @Override public String toString() {
        return "OCDFormField[" + type + " " + name + (value.isEmpty() ? "" : "=" + value) + "]";
    }

    /** A copy — the option list is duplicated, everything else is a value. */
    public OCDFormField copy() {
        OCDFormField f = new OCDFormField();
        f.type = type; f.rect = rect; f.name = name; f.value = value; f.defaultValue = defaultValue;
        f.onState = onState; f.readOnly = readOnly; f.required = required; f.multiline = multiline;
        f.rotation = rotation; f.align = align; f.fontSize = fontSize; f.textColor = textColor;
        f.borderColor = borderColor; f.backColor = backColor; f.borderWidth = borderWidth;
        f.maxLen = maxLen; f.hidden = hidden;
        f.options.addAll(options);
        return f;
    }
}
