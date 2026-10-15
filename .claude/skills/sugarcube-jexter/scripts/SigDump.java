// SigDump — the full LLM signal harvest, one line per block, diffable between two builds.
//
// BlockSignals feeds the Refiner. Change anything upstream — the paragraph split, the line
// authority, the flow rejoin — and the signals move without any pixel or byte gate noticing.
// Dump before, dump after, diff: a refactor must produce ZERO differing lines, and a fix must
// produce differences ONLY where it was meant to.
//
// Lives in the analysis package because BlockSignals is package-private by design.
//
//   javac -cp "lib/*:target/classes" -d out scripts/SigDump.java
//   java  -cp "lib/*:target/classes:out" sugarcube.jexter.ocd.analysis.SigDump in.pdf > before.txt

package sugarcube.jexter.ocd.analysis;
import sugarcube.jexter.pdfimport.PdfImporter; import sugarcube.jexter.ocd.model.*;
import java.io.File;
/** Le dump complet des signaux vus par le pass LLM — diffable entre deux builds. */
public final class SigDump { public static void main(String[] a) throws Exception {
  OCDDocument d = PdfImporter.convert(new File(a[0]));
  for (Block b : BlockSignals.harvest(d))
    System.out.printf("%s|%s|p%d|%d,%d,%d,%d|sz=%.1f|b=%b|lines=%d|align=%s|run=%b|%s%n",
      b.id, b.kind, b.page, b.gx0, b.gy0, b.gx1, b.gy1, b.size, b.bold, b.lines, b.align, b.running,
      b.text == null ? "" : b.text);
 } }
