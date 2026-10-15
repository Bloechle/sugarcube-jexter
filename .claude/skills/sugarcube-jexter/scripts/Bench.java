// Bench - where the time goes: import + analysis, then each target.
//
// Two warm-up conversions run first, and they are not decoration: cold, a page render costs 440 ms and
// warm it costs 113 - measured on the same page, same size. A cold number will send you optimising the
// wrong thing. Report medians of a warm loop, or say plainly that the figure is one-shot CLI cost.
//
//   javac -cp "lib/*:target/classes" -d out scripts/Bench.java
//   java -Xmx2g -cp "lib/*:target/classes:out" Bench in.pdf
import sugarcube.jexter.pdfimport.*; import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.write.Conversion;
import java.io.File; import java.nio.file.*; import java.util.HashMap;
/** Ou passe le temps : import, analyse, ecriture. */
public final class Bench { public static void main(String[] a) throws Exception {
  byte[] src = Files.readAllBytes(Path.of(a[0]));
  for (int w = 0; w < 2; w++) { PdfImporter.convert(new File(a[0])); }   // chauffe
  long t0 = System.nanoTime();
  OCDDocument doc = PdfImporter.convert(new File(a[0]));
  long t1 = System.nanoTime();
  System.out.printf("  import + analyse : %6.0f ms   (%d pages, %d noeuds)%n",
      (t1-t0)/1e6, doc.pageCount(), doc.pages().stream().mapToLong(p -> p.nodes().count()).sum());
  for (String t : new String[]{"ocd","pdf","html","md","doctags","epub"}) {
    long s = System.nanoTime();
    byte[] o = Conversion.convert(src, t, new HashMap<String,String>()).bytes();
    System.out.printf("  pdf -> %-8s : %6.0f ms   %,9d o%n", t, (System.nanoTime()-s)/1e6, o.length);
  } } }
