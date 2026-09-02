package sugarcube.jexter.core;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * The one XML-parsing authority. Every XML jexter reads — XMP packets inside a PDF, page SVG
 * inside an OCD-EPUB — is attacker-controlled input (see SECURITY.md), so parsing is uniformly
 * hardened here: DOCTYPE declarations are rejected outright, which closes both XXE (internal-subset
 * {@code SYSTEM} entities included) and entity-expansion bombs. jexter's own writers never emit a
 * DOCTYPE, so nothing legitimate is lost.
 */
public final class JxXml {

    private JxXml() {}

    /** A namespace-aware, XXE-hardened builder. Fails fast if the JAXP implementation cannot
     *  honor the hardening — parsing untrusted XML without it is not an option. */
    public static DocumentBuilder secureBuilder() {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            return dbf.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("JAXP cannot be hardened against XXE", e);
        }
    }
}
