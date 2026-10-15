// Hostile — what the reader does with a container that is broken, hand-edited or malicious.
//
// Feed it the variants scripts/make_hostile.py builds. The contract (FORMAT "What a reader must do
// with a broken container") is liberal but never silent:
//   REFUSED with context   a malformed value — the grammar it broke and what was read instead,
//                          never a bare NumberFormatException six frames down.
//   REFUSED at the parser  any DOCTYPE, so external entities (XXE) and entity expansion
//                          (billion laughs) never reach the document.
//   READ + a warning       a reference that resolves to nothing: it is DROPPED, not carried.
//                          A dangling reference is worse than none — it survives into the model and
//                          silently drops a clip or blanks a page's text with every gate green.
//
// This probe also asserts the two things a warning cannot: no local file leaked into the text, and
// no entity expansion.
//
//   javac -cp "lib/*:target/classes" -d out scripts/Hostile.java
//   python scripts/make_hostile.py good.ocd.epub /tmp/hostile
//   for f in /tmp/hostile/*.ocd.epub; do java -cp "lib/*:target/classes:out" Hostile "$f"; done
import sugarcube.jexter.ocd.io.OCDReader; import sugarcube.jexter.ocd.model.*;
import java.io.File;
public final class Hostile { public static void main(String[] a) throws Exception {
  long t0 = System.currentTimeMillis();
  try {
    OCDDocument d = OCDReader.read(new File(a[0]));
    long n = d.page(0).nodes().count();
    String txt = d.page(0).texts().map(OCDText::text).filter(s -> s != null).reduce("", String::concat);
    System.out.printf("LU sans erreur : %d noeuds, %d car. de texte, %d ms%n",
        n, txt.length(), System.currentTimeMillis()-t0);
    if (txt.contains("root:") || txt.contains("/bin/")) System.out.println("   !!! FUITE DE FICHIER LOCAL DANS LE TEXTE !!!");
    if (txt.length() > 2_000_000) System.out.println("   !!! EXPANSION D ENTITES !!!");
  } catch (Throwable e) {
    System.out.printf("REFUSE (%s) : %s [%d ms]%n", e.getClass().getSimpleName(),
        String.valueOf(e.getMessage()).replaceAll("\\s+"," ").substring(0, Math.min(90, String.valueOf(e.getMessage()).length())),
        System.currentTimeMillis()-t0);
  } } }
