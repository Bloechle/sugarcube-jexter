// Straddle — paragraphs that wrap a NON-contiguous paint span, and the nodes that
// intrude. Such a wrapper cannot be stored atomically (SvgOcdWriter) without inverting
// the paint interleave: the intruder serializes after the whole paragraph and covers
// what should sit on top of it. Must print STRADDLING=0.
//
//   javac -cp "lib/*:target/classes" -d out scripts/Straddle.java
//   java  -cp "lib/*:target/classes:out" Straddle in.pdf [out.ocd.epub]
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.ocd.model.*;
import sugarcube.jexter.write.Conversion;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Two facts about one document, both mechanical:
 *   STRADDLE — an OCDParagraph whose children's z span contains a node that is not its child.
 *              Such a wrapper cannot be stored atomically without inverting the paint interleave.
 *   ORDER    — the model's global paint order (OCDNode.inPaintOrder), as node ids, so it can be
 *              diffed against the document order of the emitted OCD-EPUB page.
 */
public final class Straddle {

    public static void main(String[] args) throws Exception {
        OCDDocument doc = PdfImporter.convert(new File(args[0]));
        int total = 0, bad = 0;

        for (int pi = 0; pi < doc.pageCount(); pi++) {
            OCDPage page = doc.page(pi);

            // every node on the page, flattened, with its z
            List<OCDNode> all = new ArrayList<>();
            flatten(page.content(), all);

            for (OCDNode n : all) {
                if (!(n instanceof OCDParagraph p)) continue;
                total++;
                List<OCDNode> kids = new ArrayList<>();
                flatten(p.children(), kids);
                Set<OCDNode> mine = Collections.newSetFromMap(new IdentityHashMap<>());
                mine.addAll(kids);
                float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                for (OCDNode k : kids) if (!(k instanceof OCDBreak)) { lo = Math.min(lo, k.z()); hi = Math.max(hi, k.z()); }
                if (lo > hi) continue;
                List<String> intruders = new ArrayList<>();
                for (OCDNode o : all) {
                    if (mine.contains(o) || o instanceof OCDParagraph || o instanceof OCDBreak) continue;
                    if (o.z() > lo && o.z() < hi) intruders.add(o.id() + "@z" + o.z() + "/" + o.getClass().getSimpleName());
                }
                if (!intruders.isEmpty()) {
                    bad++;
                    System.out.println("STRADDLE p" + (pi + 1) + " " + p.id() + " z[" + lo + ".." + hi + "] over " + intruders);
                }
            }

            System.out.print("PAINT p" + (pi + 1) + ":");
            for (OCDNode n : OCDNode.inPaintOrder(page.content())) {
                if (n instanceof OCDBreak) continue;
                System.out.print(" " + n.id());
            }
            System.out.println();
        }
        System.out.println("PARAGRAPHS=" + total + " STRADDLING=" + bad);

        if (args.length > 1) {
            var out = Conversion.convert(doc, "ocd", new HashMap<String, String>());
            Files.write(Path.of(args[1]), out.bytes());
            System.out.println("WROTE " + args[1] + " (" + out.bytes().length + " bytes)");
        }
    }

    private static void flatten(List<OCDNode> in, List<OCDNode> out) {
        for (OCDNode n : in) {
            out.add(n);
            if (n instanceof OCDGroup g) flatten(g.children(), out);
        }
    }
}
