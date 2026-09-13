package sugarcube.jexter.ocd.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed document metadata — the canonical, lossless vocabulary of the structured
 * format (in place of an ad-hoc string map). Multi-valued fields (authors,
 * keywords) are real lists; dates are ISO-8601 strings; anything outside the
 * vocabulary lives in {@link #custom()}.
 *
 * <p>Serialized natively in {@code meta.xml} (round-trips exactly); projected to
 * Dublin Core only where an external format needs it (EPUB OPF).
 */
public final class OCDMeta {

    private String title    = "";
    private String subject  = "";
    private String creator  = "";   // authoring application
    private String producer = "";   // producing library
    private String language = "";   // BCP-47, e.g. "en", "fr-CH"
    private String created  = "";   // ISO-8601
    private String modified = "";   // ISO-8601
    private final List<String> authors  = new ArrayList<>();
    private final List<String> keywords = new ArrayList<>();
    private final Map<String, String> custom = new LinkedHashMap<>();

    public String  title()         { return title; }
    public OCDMeta title(String v) { this.title = nz(v); return this; }
    public String  subject()         { return subject; }
    public OCDMeta subject(String v) { this.subject = nz(v); return this; }
    public String  creator()         { return creator; }
    public OCDMeta creator(String v) { this.creator = nz(v); return this; }
    public String  producer()         { return producer; }
    public OCDMeta producer(String v) { this.producer = nz(v); return this; }
    public String  language()         { return language; }
    public OCDMeta language(String v) { this.language = nz(v); return this; }
    public String  created()         { return created; }
    public OCDMeta created(String v) { this.created = nz(v); return this; }
    public String  modified()         { return modified; }
    public OCDMeta modified(String v) { this.modified = nz(v); return this; }

    public List<String> authors()         { return authors; }
    public OCDMeta       addAuthor(String a)  { if (notBlank(a)) authors.add(a.trim()); return this; }
    public List<String> keywords()        { return keywords; }
    public OCDMeta       addKeyword(String k) { if (notBlank(k)) keywords.add(k.trim()); return this; }

    public Map<String, String> custom()           { return custom; }
    public OCDMeta             custom(String k, String v) { if (notBlank(k)) custom.put(k, nz(v)); return this; }

    /** Authors as one display string. */
    public String authorLine() { return String.join(", ", authors); }

    public boolean isEmpty() {
        return title.isEmpty() && subject.isEmpty() && creator.isEmpty() && producer.isEmpty()
                && language.isEmpty() && created.isEmpty() && modified.isEmpty()
                && authors.isEmpty() && keywords.isEmpty() && custom.isEmpty();
    }

    private static String  nz(String v)       { return v == null ? "" : v; }
    private static boolean notBlank(String v) { return v != null && !v.isBlank(); }

    @Override public String toString() {
        return "OCDMeta[title=\"" + title + "\", authors=" + authors + ", lang=" + language + "]";
    }
}
