// TextFid — jexter's text read-out against PDFBox's own extraction, on the same PDF.
//
// The round-trip gates prove the text SURVIVES; none of them proves it is RIGHT. This one compares
// the product against the reference extractor: characters lost, characters invented, and — the part
// that actually decides whether a document is readable — words that disappeared or appeared.
//
// Whitespace is normalised on both sides (the two disagree about line breaks by design: PDFBox emits
// physical lines, jexter emits reading order), so what is left is real.
//
//   javac -cp "lib/*:target/classes" -d out scripts/TextFid.java
//   java  -cp "lib/*:target/classes:out" sugarcube.jexter.write.TextFid in.pdf
//
// Lives in the write package because OCDIndex — the read-out authority — is package-private by design.

package sugarcube.jexter.write;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDParagraph;

import java.io.File;
import java.text.Normalizer;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class TextFid {

    public static void main(String[] args) throws Exception {
        String ref;
        try (var pd = Loader.loadPDF(new File(args[0]))) {
            ref = new PDFTextStripper().getText(pd);
        }
        OCDDocument doc = PdfImporter.convert(new File(args[0]));
        // the read-out as every text projection sees it: one paragraph at a time, in reading order
        StringBuilder sb = new StringBuilder();
        for (int pi = 0; pi < doc.pageCount(); pi++)
            doc.page(pi).nodes().filter(OCDParagraph.class::isInstance)
               .forEach(n -> sb.append(OCDIndex.text(n)).append(' '));
        String mine = sb.toString();

        String a = norm(ref), b = norm(mine);
        System.out.printf("PDFBox : %,d chars   jexter : %,d chars%n", a.length(), b.length());

        Map<Character, Integer> ha = hist(a), hb = hist(b);
        int lost = 0, extra = 0;
        StringBuilder ls = new StringBuilder(), es = new StringBuilder();
        for (var e : ha.entrySet()) {
            int d = e.getValue() - hb.getOrDefault(e.getKey(), 0);
            if (d > 0) { lost += d; if (ls.length() < 80) ls.append(e.getKey()).append('x').append(d).append(' '); }
        }
        for (var e : hb.entrySet()) {
            int d = e.getValue() - ha.getOrDefault(e.getKey(), 0);
            if (d > 0) { extra += d; if (es.length() < 80) es.append(e.getKey()).append('x').append(d).append(' '); }
        }
        System.out.printf("chars missing : %-6d %s%n", lost, ls);
        System.out.printf("chars added   : %-6d %s%n", extra, es);

        Set<String> wa = words(a), wb = words(b);
        Set<String> miss = new TreeSet<>(wa); miss.removeAll(wb);
        Set<String> add = new TreeSet<>(wb); add.removeAll(wa);
        System.out.printf("words : %d -> %d   missing %d   invented %d%n", wa.size(), wb.size(), miss.size(), add.size());
        show("missing ", miss);
        show("invented", add);
    }

    private static void show(String label, Set<String> s) {
        if (s.isEmpty()) return;
        var b = new StringBuilder("   " + label + " : ");
        int n = 0;
        for (String w : s) { if (n++ == 14) { b.append("..."); break; } b.append(w).append(' '); }
        System.out.println(b);
    }

    private static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFC).replaceAll("\\s+", " ").trim();
    }

    private static Map<Character, Integer> hist(String s) {
        Map<Character, Integer> m = new TreeMap<>();
        for (char c : s.toCharArray()) if (c != ' ') m.merge(c, 1, Integer::sum);
        return m;
    }

    private static Set<String> words(String s) {
        Set<String> w = new TreeSet<>();
        for (String t : s.split("[^\\p{L}\\p{N}]+")) if (t.length() > 2) w.add(t.toLowerCase());
        return w;
    }
}
