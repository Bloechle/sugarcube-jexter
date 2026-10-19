package sugarcube.jexter.pdfimport;

import sugarcube.jexter.core.JxClock;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;

import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDNode;
import sugarcube.jexter.ocd.model.OCDStructNode;
import sugarcube.jexter.ocd.model.OCDStructure;
import sugarcube.jexter.pdfimport.PdfStreamEngine.Mcid;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ground-truth structure ingestion: builds the {@link OCDStructNode} tree directly
 * from a tagged PDF's {@code StructTreeRoot} (PDF/UA), mapping standard structure
 * types to the OCD vocabulary, carrying accessibility facets (Alt / Lang / title),
 * and resolving marked-content references (MCIDs) to the content nodes captured
 * at import (ids resolved after id stamping). When a PDF is tagged this is strictly better than heuristics — it is
 * the author's own logical structure. Untagged PDFs fall back to {@link sugarcube.jexter.ocd.analysis.StructureBuilder}.
 */
public final class TaggedStructureBuilder {

    private TaggedStructureBuilder() {}

    private static final COSName STM = COSName.getPDFName("Stm");     // the content stream an MCR's MCID lives in

    public static void build(OCDDocument doc, PDStructureTreeRoot root, PDDocument pdf,
                             Map<Integer, Map<Mcid, List<OCDNode>>> mcidByPage) {
        Map<COSDictionary, Integer> pageIndex = new HashMap<>();
        int i = 0;
        for (PDPage p : pdf.getPages()) pageIndex.put(p.getCOSObject(), i++);
        Tree t = new Tree(pageIndex, mcidByPage);

        OCDStructNode rootStruct = new OCDStructNode(OCDStructNode.Type.DOCUMENT);
        for (Object kid : root.getKids())
            if (kid instanceof PDStructureElement e) rootStruct.add(t.element(e, -1));
        doc.addStructure(new OCDStructure("pdf", "PDF (tagged)", OCDStructure.Source.PDF)
                .by("TaggedStructureBuilder").at(JxClock.millis()).how("PDF/UA tag tree").root(rootStruct));
    }

    private record Tree(Map<COSDictionary, Integer> pageIndex, Map<Integer, Map<Mcid, List<OCDNode>>> mcidByPage) {

        /** An element, typed by its STANDARD type: a producer's own types (Word's, InDesign's) reach one through
         *  the RoleMap, which {@code getStandardStructureType} follows — the raw type made them all OTHER. */
        OCDStructNode element(PDStructureElement e, int inheritedPage) {
            String type = e.getStandardStructureType();
            OCDStructNode s = new OCDStructNode(mapType(type)).level(level(type));
            String alt = e.getAlternateDescription() != null ? e.getAlternateDescription()
                       : e.getActualText() != null ? e.getActualText() : e.getTitle();
            if (alt != null)             s.alt(alt);
            if (e.getLanguage() != null) s.lang(e.getLanguage());
            kids(s, e, pageOf(e.getPage(), pageIndex, inheritedPage));
            return s;
        }

        /** The element's kids into {@code s}. An {@code LBody} is not a node of its own: its content IS the
         *  item's body, beside the item's label — kept as an ITEM, it nested an item in an item and the
         *  writers lost the body or wrote {@code <li>} in {@code <li>}. */
        void kids(OCDStructNode s, PDStructureElement e, int page) {
            for (Object kid : e.getKids()) {
                if (kid instanceof PDStructureElement child) {
                    if ("LBody".equals(child.getStandardStructureType())) kids(s, child, pageOf(child.getPage(), pageIndex, page));
                    else s.add(element(child, page));
                } else if (kid instanceof PDMarkedContentReference mcr) {
                    COSStream stm = mcr.getCOSObject().getCOSStream(STM);
                    refs(s, pageOf(mcr.getPage(), pageIndex, page), new Mcid(stm, mcr.getMCID()));
                } else if (kid instanceof Integer mcid) {
                    refs(s, page, new Mcid(null, mcid));
                }
                // PDObjectReference (image/annot OBJR) and others: not mapped to refs here
            }
        }

        void refs(OCDStructNode s, int page, Mcid mcid) {
            if (page < 0 || mcid.id() < 0) return;
            Map<Mcid, List<OCDNode>> m = mcidByPage.get(page);
            List<OCDNode> nodes = m == null ? null : m.get(mcid);
            if (nodes != null) for (OCDNode n : nodes) if (n.id() != null) s.addRef(page, n.id());
        }
    }

    private static int pageOf(PDPage p, Map<COSDictionary, Integer> idx, int fallback) {
        if (p == null) return fallback;
        return idx.getOrDefault(p.getCOSObject(), fallback);
    }

    // ── PDF standard structure types → OCD vocabulary ────────────────────────
    private static OCDStructNode.Type mapType(String t) {
        if (t == null) return OCDStructNode.Type.OTHER;
        String u = t.toUpperCase(Locale.US);
        if (u.length() == 2 && u.charAt(0) == 'H' && Character.isDigit(u.charAt(1))) return OCDStructNode.Type.HEADING;
        return switch (u) {
            case "DOCUMENT", "PART", "ART", "ARTICLE"            -> OCDStructNode.Type.SECTION;
            case "SECT", "DIV"                                   -> OCDStructNode.Type.SECTION;
            case "H", "TITLE"                                    -> OCDStructNode.Type.HEADING;
            case "P"                                             -> OCDStructNode.Type.PARAGRAPH;
            case "L"                                             -> OCDStructNode.Type.LIST;
            case "LI"                                            -> OCDStructNode.Type.ITEM;
            case "TABLE"                                         -> OCDStructNode.Type.TABLE;
            case "TR"                                            -> OCDStructNode.Type.ROW;
            case "TH", "TD"                                      -> OCDStructNode.Type.CELL;
            case "FIGURE", "FORMULA"                             -> OCDStructNode.Type.FIGURE;
            case "CAPTION"                                       -> OCDStructNode.Type.CAPTION;
            case "BLOCKQUOTE", "QUOTE"                           -> OCDStructNode.Type.QUOTE;
            case "CODE"                                          -> OCDStructNode.Type.CODE;
            case "NOTE", "FENOTE"                                -> OCDStructNode.Type.NOTE;
            case "TOC", "TOCI"                                   -> OCDStructNode.Type.TOC;
            case "SPAN", "QUOTE2", "LBL"                         -> OCDStructNode.Type.SPAN;
            default                                              -> OCDStructNode.Type.OTHER;
        };
    }

    private static int level(String t) {
        if (t != null && t.length() == 2 && (t.charAt(0) == 'H' || t.charAt(0) == 'h') && Character.isDigit(t.charAt(1)))
            return t.charAt(1) - '0';
        return 0;
    }
}
