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
import sugarcube.jexter.ocd.model.OCDText;
import sugarcube.jexter.pdfimport.PdfStreamEngine.Mcid;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /** Lift the tag tree into the {@code pdf} structure — only when it marks at least {@link #MIN_COVERAGE} of the
     *  document's text. A tree that tags nothing (a layout program's skeleton, written with tagging off) or a few
     *  objects of a long document (what an export happened to mark) is no structure of the text: lifted, it won
     *  over the heuristic and every text target came out blank, or holding a few captions.
     *  @return whether a {@code pdf} structure was added */
    public static boolean build(OCDDocument doc, PDStructureTreeRoot root, PDDocument pdf,
                                Map<Integer, Map<Mcid, List<OCDNode>>> mcidByPage) {
        Map<COSDictionary, Integer> pageIndex = new HashMap<>();
        int i = 0;
        for (PDPage p : pdf.getPages()) pageIndex.put(p.getCOSObject(), i++);
        Tree t = new Tree(pageIndex, mcidByPage, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

        OCDStructNode rootStruct = new OCDStructNode(OCDStructNode.Type.DOCUMENT);
        for (Object kid : root.getKids())
            if (kid instanceof PDStructureElement e && t.enter(e, 1)) rootStruct.add(t.element(e, -1, false, 1));
        if (coverage(doc, rootStruct) < MIN_COVERAGE) return false;
        doc.addStructure(new OCDStructure("pdf", "PDF (tagged)", OCDStructure.Source.PDF)
                .by("TaggedStructureBuilder").at(JxClock.millis()).how("PDF/UA tag tree").root(rootStruct));
        return true;
    }

    /** The least share of the document's text (in glyphs) a tag tree must mark to be its structure. */
    static final double MIN_COVERAGE = 0.5;

    /** The share of the document's glyphs that the tree's elements mark (the runs they reach). A document with
     *  no text at all (tagged figures only) has no glyph to weigh: any mark makes the tree its structure. */
    private static double coverage(OCDDocument doc, OCDStructNode root) {
        Set<String> marked = new HashSet<>();
        collect(root, marked);
        long all = 0, in = 0;
        for (int p = 0; p < doc.pageCount(); p++)
            for (OCDText t : doc.page(p).texts().toList()) {
                all += t.count();
                if (marked.contains(p + "/" + t.id())) in += t.count();
            }
        return all == 0 ? (marked.isEmpty() ? 0 : 1) : (double) in / all;
    }

    private static void collect(OCDStructNode n, Set<String> marked) {
        for (OCDStructNode.Ref r : n.refs()) marked.add(r.page() + "/" + r.nodeId());
        for (OCDStructNode c : n.children()) collect(c, marked);
    }

    /** The deepest tag tree read; a real one is a few levels deep. A tree is attacker-controlled input: deeper,
     *  or an element met twice (a kid that is its own ancestor), is cut there and reported, never left to the
     *  stack — a cyclic tree was a {@code StackOverflowError}. */
    static final int MAX_DEPTH = 256;

    private record Tree(Map<COSDictionary, Integer> pageIndex, Map<Integer, Map<Mcid, List<OCDNode>>> mcidByPage,
                        java.util.Set<COSDictionary> seen) {

        /** Whether element {@code e} may be read at {@code depth}: first meeting, within {@link #MAX_DEPTH}. */
        boolean enter(PDStructureElement e, int depth) {
            if (depth <= MAX_DEPTH && seen.add(e.getCOSObject())) return true;
            sugarcube.jexter.core.JxLog.warn(TaggedStructureBuilder.class,
                    "tag tree cut: an element met twice or nested past " + MAX_DEPTH + " levels");
            return false;
        }

        /** An element, typed by its STANDARD type: a producer's own types (Word's, InDesign's) reach one through
         *  the RoleMap, which {@code getStandardStructureType} follows — the raw type made them all OTHER. */
        OCDStructNode element(PDStructureElement e, int inheritedPage, boolean inText, int depth) {
            String type = e.getStandardStructureType();
            OCDStructNode.Type kind = mapType(type);
            // Inside a heading, a paragraph or a caption, a grouping element (Div, Sect…) groups nothing: the block
            // holds words, so it is an inline span of them. Typst wraps every heading's words in a Div; kept as a
            // SECTION, the heading read as nothing — and every EPUB's contents said « Heading 1 ».
            if (inText && kind == OCDStructNode.Type.SECTION) kind = OCDStructNode.Type.SPAN;
            OCDStructNode s = new OCDStructNode(kind).level(level(type));
            String alt = e.getAlternateDescription() != null ? e.getAlternateDescription()
                       : e.getActualText() != null ? e.getActualText() : e.getTitle();
            if (alt != null)             s.alt(alt);
            if (e.getLanguage() != null) s.lang(e.getLanguage());
            kids(s, e, pageOf(e.getPage(), pageIndex, inheritedPage), inText || TEXT_BLOCKS.contains(kind), depth);
            return s;
        }

        /** The element's kids into {@code s}. An {@code LBody} is not a node of its own: its content IS the
         *  item's body, beside the item's label — kept as an ITEM, it nested an item in an item and the
         *  writers lost the body or wrote {@code <li>} in {@code <li>}. */
        void kids(OCDStructNode s, PDStructureElement e, int page, boolean inText, int depth) {
            for (Object kid : e.getKids()) {
                if (kid instanceof PDStructureElement child) {
                    if (!enter(child, depth + 1)) continue;
                    if ("LBody".equals(child.getStandardStructureType())) kids(s, child, pageOf(child.getPage(), pageIndex, page), inText, depth + 1);
                    else s.add(element(child, page, inText, depth + 1));
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

    /** The blocks that hold words, not other blocks: inside one, grouping elements fold into spans. */
    private static final java.util.Set<OCDStructNode.Type> TEXT_BLOCKS =
            java.util.EnumSet.of(OCDStructNode.Type.HEADING, OCDStructNode.Type.PARAGRAPH, OCDStructNode.Type.CAPTION);

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
