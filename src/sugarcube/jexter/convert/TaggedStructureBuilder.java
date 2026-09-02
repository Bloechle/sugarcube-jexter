package sugarcube.jexter.convert;

import sugarcube.jexter.core.JxClock;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDStruct;
import sugarcube.jexter.ocd.model.OCDStructure;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ground-truth structure ingestion: builds the {@link OCDStruct} tree directly
 * from a tagged PDF's {@code StructTreeRoot} (PDF/UA), mapping standard structure
 * types to the OCD vocabulary, carrying accessibility facets (Alt / Lang / title),
 * and resolving marked-content references (MCIDs) to the content nodes captured
 * at import (ids resolved after id stamping). When a PDF is tagged this is strictly better than heuristics — it is
 * the author's own logical structure. Untagged PDFs fall back to {@link sugarcube.jexter.ocd.analysis.StructureBuilder}.
 */
public final class TaggedStructureBuilder {

    private TaggedStructureBuilder() {}

    public static void build(OCDDocument doc, PDStructureTreeRoot root, PDDocument pdf,
                             Map<Integer, Map<Integer, List<OCDNode>>> mcidByPage) {
        Map<COSDictionary, Integer> pageIndex = new HashMap<>();
        int i = 0;
        for (PDPage p : pdf.getPages()) pageIndex.put(p.getCOSObject(), i++);

        OCDStruct rootStruct = new OCDStruct(OCDStruct.Type.DOCUMENT);
        for (Object kid : root.getKids())
            if (kid instanceof PDStructureElement e) rootStruct.add(element(e, -1, pageIndex, mcidByPage));
        doc.addStructure(new OCDStructure("pdf", "PDF (tagged)", OCDStructure.Source.PDF)
                .by("TaggedStructureBuilder").at(JxClock.millis()).how("PDF/UA tag tree").root(rootStruct));
    }

    private static OCDStruct element(PDStructureElement e, int inheritedPage,
                                     Map<COSDictionary, Integer> pageIndex,
                                     Map<Integer, Map<Integer, List<OCDNode>>> mcidByPage) {
        OCDStruct.Type type = mapType(e.getStructureType());
        OCDStruct s = new OCDStruct(type).level(level(e.getStructureType()));
        String alt = e.getAlternateDescription() != null ? e.getAlternateDescription()
                   : e.getActualText() != null ? e.getActualText() : e.getTitle();
        if (alt != null)             s.alt(alt);
        if (e.getLanguage() != null) s.lang(e.getLanguage());

        int elemPage = pageOf(e.getPage(), pageIndex, inheritedPage);
        for (Object kid : e.getKids()) {
            if (kid instanceof PDStructureElement child) {
                s.add(element(child, elemPage, pageIndex, mcidByPage));
            } else if (kid instanceof PDMarkedContentReference mcr) {
                addRefs(s, pageOf(mcr.getPage(), pageIndex, elemPage), mcr.getMCID(), mcidByPage);
            } else if (kid instanceof Integer mcid) {
                addRefs(s, elemPage, mcid, mcidByPage);
            }
            // PDObjectReference (image/annot OBJR) and others: not mapped to refs here
        }
        return s;
    }

    private static void addRefs(OCDStruct s, int page, int mcid,
                                Map<Integer, Map<Integer, List<OCDNode>>> mcidByPage) {
        if (page < 0 || mcid < 0) return;
        Map<Integer, List<OCDNode>> m = mcidByPage.get(page);
        if (m == null) return;
        List<OCDNode> nodes = m.get(mcid);
        if (nodes != null) for (OCDNode n : nodes) if (n.id() != null) s.addRef(page, n.id());
    }

    private static int pageOf(PDPage p, Map<COSDictionary, Integer> idx, int fallback) {
        if (p == null) return fallback;
        return idx.getOrDefault(p.getCOSObject(), fallback);
    }

    // ── PDF standard structure types → OCD vocabulary ────────────────────────
    private static OCDStruct.Type mapType(String t) {
        if (t == null) return OCDStruct.Type.OTHER;
        String u = t.toUpperCase(Locale.US);
        if (u.length() == 2 && u.charAt(0) == 'H' && Character.isDigit(u.charAt(1))) return OCDStruct.Type.HEADING;
        return switch (u) {
            case "DOCUMENT", "PART", "ART", "ARTICLE"            -> OCDStruct.Type.SECTION;
            case "SECT", "DIV"                                   -> OCDStruct.Type.SECTION;
            case "H", "TITLE"                                    -> OCDStruct.Type.HEADING;
            case "P"                                             -> OCDStruct.Type.PARAGRAPH;
            case "L"                                             -> OCDStruct.Type.LIST;
            case "LI", "LBODY"                                   -> OCDStruct.Type.ITEM;
            case "TABLE"                                         -> OCDStruct.Type.TABLE;
            case "TR"                                            -> OCDStruct.Type.ROW;
            case "TH", "TD"                                      -> OCDStruct.Type.CELL;
            case "FIGURE", "FORMULA"                             -> OCDStruct.Type.FIGURE;
            case "CAPTION"                                       -> OCDStruct.Type.CAPTION;
            case "BLOCKQUOTE", "QUOTE"                           -> OCDStruct.Type.QUOTE;
            case "CODE"                                          -> OCDStruct.Type.CODE;
            case "NOTE", "FENOTE"                                -> OCDStruct.Type.NOTE;
            case "TOC", "TOCI"                                   -> OCDStruct.Type.TOC;
            case "SPAN", "QUOTE2", "LBL"                         -> OCDStruct.Type.SPAN;
            default                                              -> OCDStruct.Type.OTHER;
        };
    }

    private static int level(String t) {
        if (t != null && t.length() == 2 && (t.charAt(0) == 'H' || t.charAt(0) == 'h') && Character.isDigit(t.charAt(1)))
            return t.charAt(1) - '0';
        return 0;
    }
}
