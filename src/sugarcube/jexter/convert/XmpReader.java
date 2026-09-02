package sugarcube.jexter.convert;

import sugarcube.jexter.core.JxXml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import sugarcube.jexter.ocd.model.OCDMeta;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads an XMP packet (RDF/XML) into an {@link OCDMeta}, overlaying the legacy
 * info-dictionary values the importer already captured. XMP is the modern,
 * authoritative metadata channel (PDF/A even mandates it), so any field present
 * here wins over the matching info-dict field. Pure JDK XML — no xmpbox jar.
 * Malformed or absent XMP is swallowed: the info-dict values simply stand.
 *
 * <p>Both serialisations are handled: the element form
 * ({@code <dc:title><rdf:Alt><rdf:li>…}) with rdf:Alt/Seq/Bag containers, and the
 * compact attribute form on {@code rdf:Description}. Core Dublin Core that OCDMeta
 * has no first-class field for — identifier, publisher, rights — is parked in the
 * custom map (which round-trips through {@code meta.xml}).
 */
final class XmpReader {
    private XmpReader() {}

    private static final String RDF  = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String DC   = "http://purl.org/dc/elements/1.1/";
    private static final String XMP  = "http://ns.adobe.com/xap/1.0/";
    private static final String PDF  = "http://ns.adobe.com/pdf/1.3/";
    private static final String MM   = "http://ns.adobe.com/xap/1.0/mm/";
    private static final String XMLNS = "http://www.w3.org/XML/1998/namespace";

    static void apply(InputStream xmp, OCDMeta m) {
        try {
            Document d = JxXml.secureBuilder().parse(xmp);   // untrusted, embedded in the PDF

            String title = null, desc = null, lang = null, created = null, modified = null,
                   tool = null, producer = null, identifier = null, publisher = null,
                   rights = null, kwLine = null;
            List<String> authors = new ArrayList<>();
            List<String> subjects = new ArrayList<>();

            NodeList descs = d.getElementsByTagNameNS(RDF, "Description");
            for (int i = 0; i < descs.getLength(); i++) {
                Element e = (Element) descs.item(i);

                // compact attribute form (simple values only)
                created    = pick(created,    attrNS(e, XMP, "CreateDate"));
                modified   = pick(modified,   attrNS(e, XMP, "ModifyDate"));
                tool       = pick(tool,       attrNS(e, XMP, "CreatorTool"));
                producer   = pick(producer,   attrNS(e, PDF, "Producer"));
                kwLine     = pick(kwLine,     attrNS(e, PDF, "Keywords"));
                identifier = pick(identifier, attrNS(e, DC,  "identifier"));
                identifier = pick(identifier, attrNS(e, MM,  "DocumentID"));

                // element form
                for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    Element p = (Element) n;
                    String ns = p.getNamespaceURI(), ln = p.getLocalName();
                    if (ns == null || ln == null) continue;
                    if (DC.equals(ns)) {
                        switch (ln) {
                            case "title"       -> title  = pick(title, alt(p));
                            case "description" -> desc   = pick(desc, alt(p));
                            case "rights"      -> rights = pick(rights, alt(p));
                            case "creator"     -> addAll(authors, list(p));
                            case "subject"     -> addAll(subjects, list(p));
                            case "language"    -> lang   = pick(lang, first(list(p), simple(p)));
                            case "publisher"   -> publisher = pick(publisher, first(list(p), simple(p)));
                            case "identifier"  -> identifier = pick(identifier, simple(p));
                            default -> { }
                        }
                    } else if (XMP.equals(ns)) {
                        switch (ln) {
                            case "CreateDate"  -> created  = pick(created, simple(p));
                            case "ModifyDate"  -> modified = pick(modified, simple(p));
                            case "CreatorTool" -> tool     = pick(tool, simple(p));
                            default -> { }
                        }
                    } else if (PDF.equals(ns)) {
                        switch (ln) {
                            case "Producer"    -> producer = pick(producer, simple(p));
                            case "Keywords"    -> kwLine   = pick(kwLine, simple(p));
                            default -> { }
                        }
                    } else if (MM.equals(ns) && "DocumentID".equals(ln)) {
                        identifier = pick(identifier, simple(p));
                    }
                }
            }
            if (subjects.isEmpty() && kwLine != null)
                for (String k : kwLine.split("[;,]")) if (!k.isBlank()) subjects.add(k.trim());

            // overlay (XMP authoritative): scalars override when present, lists replace
            if (nz(title))      m.title(title);
            if (nz(desc))       m.subject(desc);
            if (nz(lang))       m.language(lang);
            if (nz(created))    m.created(created);
            if (nz(modified))   m.modified(modified);
            if (nz(tool))       m.creator(tool);
            if (nz(producer))   m.producer(producer);
            if (nz(identifier)) m.custom("identifier", identifier);
            if (nz(publisher))  m.custom("publisher", publisher);
            if (nz(rights))     m.custom("rights", rights);
            if (!authors.isEmpty())  { m.authors().clear();  authors.forEach(m::addAuthor); }
            if (!subjects.isEmpty()) { m.keywords().clear(); subjects.forEach(m::addKeyword); }
        } catch (Exception ignore) {
            // malformed / absent XMP → keep the info-dict values
        }
    }

    /** rdf:Seq / rdf:Bag / rdf:Alt → its rdf:li text values, in document order. */
    private static List<String> list(Element prop) {
        List<String> out = new ArrayList<>();
        Element c = firstChildNS(prop, RDF, "Seq");
        if (c == null) c = firstChildNS(prop, RDF, "Bag");
        if (c == null) c = firstChildNS(prop, RDF, "Alt");
        if (c != null)
            for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling())
                if (isLi(n)) {
                    String t = n.getTextContent();
                    if (t != null && !t.isBlank()) out.add(t.trim());
                }
        return out;
    }

    /** Language-alternative value: prefer x-default, else the first rdf:li, else the plain text. */
    private static String alt(Element prop) {
        Element altC = firstChildNS(prop, RDF, "Alt");
        if (altC != null) {
            String first = null;
            for (Node n = altC.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!isLi(n)) continue;
                Element li = (Element) n;
                String t = li.getTextContent();
                if (t == null || t.isBlank()) continue;
                if ("x-default".equals(li.getAttributeNS(XMLNS, "lang"))) return t.trim();
                if (first == null) first = t.trim();
            }
            if (first != null) return first;
        }
        return simple(prop);
    }

    private static boolean isLi(Node n) {
        return n.getNodeType() == Node.ELEMENT_NODE && RDF.equals(n.getNamespaceURI()) && "li".equals(n.getLocalName());
    }
    private static Element firstChildNS(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n.getNodeType() == Node.ELEMENT_NODE && ns.equals(n.getNamespaceURI()) && local.equals(n.getLocalName()))
                return (Element) n;
        return null;
    }
    private static String simple(Element p) { String t = p.getTextContent(); return t == null ? "" : t.trim(); }
    private static String attrNS(Element e, String ns, String local) {
        String v = e.getAttributeNS(ns, local);
        return v == null || v.isBlank() ? null : v.trim();
    }
    private static String pick(String cur, String cand) { return nz(cur) ? cur : (nz(cand) ? cand : cur); }
    private static String first(List<String> l, String fallback) { return l.isEmpty() ? fallback : l.get(0); }
    private static void addAll(List<String> dst, List<String> src) { if (dst.isEmpty()) dst.addAll(src); }
    private static boolean nz(String s) { return s != null && !s.isBlank(); }
}
