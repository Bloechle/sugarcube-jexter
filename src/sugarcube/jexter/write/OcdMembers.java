package sugarcube.jexter.write;

import sugarcube.jexter.core.JxColor;
import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.JxStringer;
import sugarcube.jexter.ocd.io.OCDVocab;
import sugarcube.jexter.ocd.model.OCDAnnotation;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDMeta;
import sugarcube.jexter.ocd.model.OCDOutline;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;

import java.util.Map;

/**
 * The {@code jexter/} JSON members of an OCD-EPUB — only what has NO home in the pages. Since
 * grammar v2 the SVG-OCD pages are self-contained (text, reading order, lines, links, boxes), so
 * the members reduce to {@code meta.json}, {@code outline.json}, {@code structures.json} (refs by
 * page + node id) and a sparse {@code annots.json} (PDF annotations and form fields).
 */
public final class OcdMembers {

    private OcdMembers() {}

    // ── annots member: jexter/annots.json (sparse — only pages that carry any) ──

    /** Non-paintable page payloads that have no SVG home: PDF annotations (notes, highlight
     *  quads) and form fields. Links are NOT here — they are native {@code <a>} in the pages. */
    public static String annotsJson(OCDDocument doc) {
        JxStringer js = new JxStringer(1024).obj().obj("pages");
        for (OCDPage p : doc.pages()) {
            if (p.annotations().isEmpty() && p.fields().isEmpty()) continue;
            js.obj(p.id());
            js.arr("annots");
            for (OCDAnnotation a : p.annotations()) {
                js.obj().str("type", a.type().name().toLowerCase());
                if (a.rect() != null)        rect(js, "rect", a.rect());
                if (a.color() != null)       js.str("color", new JxColor(a.color().argb()).hex());
                if (!a.author().isEmpty())   js.str("author", a.author());
                if (!a.modified().isEmpty()) js.str("modified", a.modified());
                if (!a.contents().isEmpty()) js.str("contents", a.contents());
                if (!a.quads().isEmpty()) {
                    js.arr("quads");
                    for (JxRect q : a.quads()) { js.arr().num(q.x()).num(q.y()).num(q.width()).num(q.height()).end(); }
                    js.end();
                }
                js.end();
            }
            js.end();
            js.arr("fields");
            for (OCDFormField f : p.fields()) {
                js.obj().str("type", f.type().name().toLowerCase());
                if (f.rect() != null)            rect(js, "rect", f.rect());
                if (!f.name().isEmpty())         js.str("name", f.name());
                if (!f.value().isEmpty())        js.str("value", f.value());
                if (!f.defaultValue().isEmpty()) js.str("default", f.defaultValue());
                if (!f.onState().isEmpty())      js.str("on", f.onState());   // WHICH button of the group
                if (!f.options().isEmpty())      { js.arr("options"); for (String o : f.options()) js.str(o); js.end(); }
                if (f.readOnly())                js.bool("readonly", true);
                if (f.required())                js.bool("required", true);
                if (f.multiline())               js.bool("multiline", true);
                js.end();
            }
            js.end();
            js.end();
        }
        return js.end().end().toString();
    }

    /** True when {@link #annotsJson} would carry anything. */
    public static boolean hasAnnots(OCDDocument doc) {
        for (OCDPage p : doc.pages())
            if (!p.annotations().isEmpty() || !p.fields().isEmpty()) return true;
        return false;
    }

    // ── document members ─────────────────────────────────────────────────────────

    public static String metaJson(OCDDocument doc) {
        OCDMeta m = doc.meta();
        JxStringer js = new JxStringer(512).obj()
                .str("format", "ocd-epub")
                .str("version", "2");
        if (doc.id() != null && !doc.id().isEmpty()) js.str("id", doc.id());
        if (doc.textSegmented() || doc.headingsDetected()) {
            js.obj("analysis");
            if (doc.textSegmented())    js.bool("textSegmented", true);
            if (doc.headingsDetected()) js.bool("headingsDetected", true);
            js.end();
        }
        if (!doc.layers().isEmpty()) {
            js.arr("layers");
            for (var l : doc.layers().values())
                js.obj().str("id", l.id()).str("name", l.name()).bool("visible", l.visible()).num("order", (long) l.order()).end();
            js.end();
        }
        str(js, "title", m.title());
        if (!m.authors().isEmpty()) { js.arr("authors"); for (String a : m.authors()) js.str(a); js.end(); }
        str(js, "subject", m.subject());
        if (!m.keywords().isEmpty()) { js.arr("keywords"); for (String k : m.keywords()) js.str(k); js.end(); }
        str(js, "creator", m.creator());
        str(js, "producer", m.producer());
        str(js, "language", m.language());
        str(js, "created", m.created());
        str(js, "modified", m.modified());
        if (!m.custom().isEmpty()) {
            js.obj("custom");
            for (Map.Entry<String, String> e : m.custom().entrySet()) js.str(e.getKey(), e.getValue());
            js.end();
        }
        return js.end().toString();
    }

    public static String outlineJson(OCDDocument doc) {
        JxStringer js = new JxStringer(1024).obj();
        js.arr("bookmarks");
        for (OCDOutline o : doc.outline()) bookmark(js, o);
        js.end();
        return js.end().toString();
    }

    private static void bookmark(JxStringer js, OCDOutline o) {
        js.obj().str("title", o.title() != null ? o.title() : "");
        if (o.hasDestination()) js.str("page", OCDVocab.pageId(o.pageIndex()));
        if (o.hasY())           js.num("y", o.y());
        js.arr("children");
        for (OCDOutline c : o.children()) bookmark(js, c);
        js.end();
        js.end();
    }

    public static String structuresJson(OCDDocument doc) {
        JxStringer js = new JxStringer(8192);
        js.obj();
        if (doc.defaultStructureId() != null) js.str("default", doc.defaultStructureId());
        js.arr("structures");
        for (OCDStructure s : doc.structures()) {
            js.obj()
              .str("id", s.id() == null ? "" : s.id())
              .str("label", s.label())
              .str("source", s.source().name().toLowerCase())
              .str("by", s.by());
            if (s.at() > 0)             js.num("at", s.at());
            if (!s.how().isEmpty())     js.str("how", s.how());
            if (!s.purpose().isEmpty()) js.str("purpose", s.purpose());
            if (s.root() != null)       struct(js, "root", s.root());
            js.end();
        }
        js.end();
        js.end();
        return js.toString();
    }

    private static void struct(JxStringer js, OCDStruct s) { struct(js, null, s); }

    private static void struct(JxStringer js, String key, OCDStruct s) {
        (key == null ? js.obj() : js.obj(key)).str("type", s.type().name().toLowerCase());
        if (s.level() > 0)                            js.num("level", (long) s.level());
        if (s.colSpan() > 1)                          js.num("colspan", (long) s.colSpan());
        if (s.rowSpan() > 1)                          js.num("rowspan", (long) s.rowSpan());
        if (s.ordered())                              js.bool("ordered", true);
        if (s.header() != OCDStruct.HeaderKind.NONE)  js.str("header", s.header().name().toLowerCase());
        if (!s.text().isEmpty())                      js.str("text", s.text());
        if (!s.lang().isEmpty())                      js.str("lang", s.lang());
        if (!s.alt().isEmpty())                       js.str("alt", s.alt());
        if (!s.refs().isEmpty()) {
            js.arr("refs");
            for (OCDStruct.Ref r : s.refs())
                js.obj().str("page", OCDVocab.pageId(r.page())).str("node", r.nodeId()).end();
            js.end();
        }
        if (!s.children().isEmpty()) {
            js.arr("children");
            for (OCDStruct c : s.children()) struct(js, c);
            js.end();
        }
        js.end();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static void rect(JxStringer js, String key, JxRect r) {
        js.arr(key).num(r.x()).num(r.y()).num(r.width()).num(r.height()).end();
    }

    private static void str(JxStringer js, String key, String v) {
        if (v != null && !v.isEmpty()) js.str(key, v);
    }
}
