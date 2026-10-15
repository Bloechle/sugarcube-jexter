// OcdFacts.java — emit normalized *facts* JSON from an OCDDocument, for the
// unified verifier (verify_pipeline.py). Single-file source program (JDK 21):
//
//   java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --pdf  in.pdf    > facts.json   (stage ① virtual)
//   java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --ocd  doc.ocd.epub > facts.json   (stage ② reread)
//
// Prints the SAME schema the Python backends emit, so the comparator is
// surface-agnostic:
//   { "stage":"ocd", "source":"...", "text":"...",
//     "pages":[ { "index":0, "size_pt":[w,h], "rotation":0, "text":"...",
//                 "fonts":[{"name","embedded","type","upem"}],
//                 "images":[{"colorspace","bpc","w","h"}],
//                 "colorspaces":[...], "separations":[...] } ] }
//
// ============================================================================
// STATUS: both stages work. stage ① converts PDF -> OCDDocument (PdfImporter);
// stage ② reads an .ocd.epub back via OCDReader (the round-trip writer is
// write.OcdEpubWriter; the legacy JSON .ocd container is gone). The facts
// schema is identical for both surfaces — see references/ocd-facts.md.
//
// HONEST LIMITS of the OCD facts (by design — OCD is a normalized pivot):
//   * colours are flattened to sRGB argb at convert time, so `colorspaces` is
//     DeviceRGB whenever anything is painted and `separations` is always empty
//     (Separation/CMYK are resolved away). A GT declaring a Separation will
//     correctly show as "not preserved" — the skill surfacing a real converter
//     gap, not a bug to hide.
//   * images are decoded to sRGB PNG, so `colorspace`=rgb, `bpc`=8.
//   * glyph outlines are em-normalized, so font `type`="" and `upem`=1000
//     (the units-per-em bug is caught on the OTF by glyph_probe, not here).
// ============================================================================

import java.io.File;
import java.util.*;

import sugarcube.jexter.pdfimport.PdfImporter;          // PDF -> OCDDocument (stage ①)
import sugarcube.jexter.ocd.io.OCDReader;             // .ocd.epub -> OCDDocument (stage ②)
import sugarcube.jexter.ocd.model.*;               // OCDDocument, OCDPage, OCDNode, OCDText, OCDPath, OCDImage, OCDGroup, OCDFont

public class OcdFacts {

    // ===================== ACCESSORS (real OCD API) =========================
    static OCDDocument convertPdf(File f) throws Exception { return PdfImporter.convert(f); }
    static OCDDocument readOcd(File f) throws Exception { return OCDReader.read(f); }
    static List<OCDPage> pages(OCDDocument d)   { return d.pages(); }
    static double  pageW(OCDPage p)             { return p.mediaBox().width(); }
    static double  pageH(OCDPage p)             { return p.mediaBox().height(); }
    static int     pageRot(OCDPage p)           { return p.rotation(); }
    static List<OCDNode> roots(OCDPage p)       { return p.content(); }
    static List<OCDNode> children(OCDGroup g)   { return g.children(); }

    static String  textOf(OCDText t)            { return t.text(); }
    static OCDFont fontOf(OCDDocument d, OCDText t) { return d.findFont(t.fontId()); }
    static String  fontName(OCDFont f)          { return f.name(); }
    static boolean fontEmbedded(OCDFont f)      { return f.embedded(); }
    static String  fontType(OCDFont f)          { return ""; }      // not retained (em-normalized model)
    static int     fontUpem(OCDFont f)          { return 1000; }    // text-space convention

    static String  imgColorspace(OCDImage im)   { return "rgb"; }   // decoded to sRGB PNG
    static int     imgBpc(OCDImage im)          { return 8; }
    static int     imgW(OCDImage im)            { return im.pixelWidth(); }
    static int     imgH(OCDImage im)            { return im.pixelHeight(); }
    // ========================================================================

    static final class PageAcc {
        StringBuilder text = new StringBuilder();
        Map<String,OCDFont> fonts = new LinkedHashMap<>();
        List<OCDImage> images = new ArrayList<>();
        Set<String> colorspaces = new LinkedHashSet<>();
        Set<String> separations = new LinkedHashSet<>();   // always empty: OCD flattens to sRGB
    }

    static void walk(OCDNode n, PageAcc acc, OCDDocument doc) {
        switch (n) {
            case OCDText t -> {
                String s = textOf(t);
                if (s != null) acc.text.append(s);
                OCDFont f = fontOf(doc, t);
                if (f != null) acc.fonts.putIfAbsent(fontName(f), f);
                acc.colorspaces.add("DeviceRGB");
            }
            case OCDImage im -> {
                acc.images.add(im);
                acc.colorspaces.add(deviceFor(imgColorspace(im)));
            }
            case OCDPath p  -> acc.colorspaces.add("DeviceRGB");
            case OCDGroup g -> { for (OCDNode c : children(g)) walk(c, acc, doc); }
            default -> {}
        }
    }

    static String deviceFor(String cs) {
        String c = cs.toLowerCase();
        if (c.contains("cmyk")) return "DeviceCMYK";
        if (c.contains("gray") || c.contains("grey")) return "DeviceGray";
        if (c.contains("sep")) return "Separation";
        return "DeviceRGB";
    }

    public static void main(String[] args) throws Exception {
        String mode = null, path = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--pdf")) { mode = "pdf"; path = args[i+1]; }
            if (args[i].equals("--ocd")) { mode = "ocd"; path = args[i+1]; }
        }
        if (mode == null) { System.err.println("usage: OcdFacts --pdf in.pdf | --ocd doc.ocd.epub"); System.exit(2); }

        OCDDocument doc = mode.equals("pdf") ? convertPdf(new File(path)) : readOcd(new File(path));

        StringBuilder out = new StringBuilder();
        out.append("{\"stage\":\"ocd\",\"source\":").append(jstr(new File(path).getName()));
        StringBuilder allText = new StringBuilder();
        StringBuilder pagesJson = new StringBuilder("[");
        List<OCDPage> ps = pages(doc);
        for (int i = 0; i < ps.size(); i++) {
            OCDPage p = ps.get(i);
            PageAcc acc = new PageAcc();
            for (OCDNode n : roots(p)) walk(n, acc, doc);
            allText.append(acc.text).append('\n');
            if (i > 0) pagesJson.append(',');
            pagesJson.append("{\"index\":").append(i)
                     .append(",\"size_pt\":[").append(round(pageW(p))).append(',').append(round(pageH(p))).append(']')
                     .append(",\"rotation\":").append(pageRot(p))
                     .append(",\"text\":").append(jstr(acc.text.toString()))
                     .append(",\"fonts\":[");
            int k = 0;
            for (OCDFont f : acc.fonts.values()) {
                if (k++ > 0) pagesJson.append(',');
                pagesJson.append("{\"name\":").append(jstr(fontName(f)))
                         .append(",\"embedded\":").append(fontEmbedded(f))
                         .append(",\"type\":").append(jstr(fontType(f)))
                         .append(",\"upem\":").append(fontUpem(f)).append('}');
            }
            pagesJson.append("],\"images\":[");
            for (int j = 0; j < acc.images.size(); j++) {
                OCDImage im = acc.images.get(j);
                if (j > 0) pagesJson.append(',');
                pagesJson.append("{\"colorspace\":").append(jstr(imgColorspace(im)))
                         .append(",\"bpc\":").append(imgBpc(im))
                         .append(",\"w\":").append(imgW(im)).append(",\"h\":").append(imgH(im)).append('}');
            }
            pagesJson.append("],\"colorspaces\":").append(jarr(acc.colorspaces))
                     .append(",\"separations\":").append(jarr(acc.separations)).append('}');
        }
        pagesJson.append(']');
        out.append(",\"text\":").append(jstr(allText.toString()))
           .append(",\"pages\":").append(pagesJson).append('}');
        System.out.println(out);
    }

    // --- tiny JSON helpers ---
    static String round(double d) { return String.valueOf(Math.round(d * 10.0) / 10.0); }
    static String jarr(Collection<String> c) {
        StringBuilder b = new StringBuilder("["); int i = 0;
        for (String s : c) { if (i++ > 0) b.append(','); b.append(jstr(s)); }
        return b.append(']').toString();
    }
    static String jstr(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) switch (ch) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> { if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch)); else b.append(ch); }
        }
        return b.append('"').toString();
    }
}
