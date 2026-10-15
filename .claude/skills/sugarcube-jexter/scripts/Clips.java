// Clips — the clip census of a page: the table, and EVERY id referenced by a node.
// A reference that is not in the table is a dangling clip: its content repaints unclipped.
// Run on both sides of the round-trip and compare — a renamed id shows up as two
// disjoint id families (c1..cN in the references, clipN in the table).
//
//   javac -cp "lib/*:target/classes" -d out scripts/Clips.java
//   java  -cp "lib/*:target/classes:out" Clips in.pdf          # the imported model
//   java  -cp "lib/*:target/classes:out" Clips out.ocd.epub    # the model read back
import sugarcube.jexter.pdfimport.PdfImporter; import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.*; import java.io.File; import java.util.*;
public final class Clips {
  public static void main(String[] a) throws Exception {
    OCDDocument doc = a[0].endsWith(".pdf") ? PdfImporter.convert(new File(a[0])) : OCDReader.read(new File(a[0]));
    OCDPage p = doc.page(0);
    System.out.println("table de clips : " + p.clips().size() + "  ids=" + p.clips().keySet());
    List<OCDNode> all = new ArrayList<>(); flat(p.content(), all);
    Map<String,Integer> used = new TreeMap<>();
    for (OCDNode n : all) used.merge(n.clipId()==null?"(null)":n.clipId(), 1, Integer::sum);
    System.out.println("clipId references : " + used);
    System.out.println("-- texte > 25pt --");
    for (OCDNode n : all) if (n instanceof OCDText t && t.fontSize() > 25)
      System.out.printf("%-5s z=%-7.1f clip=%-7s mode=%d size=%.1f fill=%08X \"%s\" %s%n",
        t.id(), t.z(), t.clipId()==null?"-":t.clipId(), t.renderMode(), t.fontSize(), t.fill(),
        t.text()==null?"":t.text(), t.bounds());
  }
  static void flat(List<OCDNode> in, List<OCDNode> out){ for (OCDNode n: in){ out.add(n); if (n instanceof OCDGroup g) flat(g.children(), out);} }
}
