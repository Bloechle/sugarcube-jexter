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

    private static String nz(String v) { return v == null ? "" : v; }

    @Override public String toString() {
        return "OCDFormField[" + type + " " + name + (value.isEmpty() ? "" : "=" + value) + "]";
    }
}
