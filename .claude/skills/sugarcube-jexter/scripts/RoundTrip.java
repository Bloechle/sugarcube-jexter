// RoundTrip — the three container gates in one run, on any source.
//
//   REPRO      same input, converted twice, byte-identical output — for every target.
//              Pin SOURCE_DATE_EPOCH, or the clock alone will fail it.
//   DOCTAGS    pdf -> doctags  ==  pdf -> ocd -> doctags. The text projection must not depend
//              on whether it came straight from the PDF or through the container.
//   STABLE     write(read(x)) == x. Proves the writer is deterministic AND that the reader gave
//              back what the writer wrote — at the BYTE level only: a field written wrong and
//              read back wrong the same way passes this and fails ModelRt. Run both.
//
//   javac -cp "lib/*:target/classes" -d out scripts/RoundTrip.java
//   SOURCE_DATE_EPOCH=1000000000 java -cp "lib/*:target/classes:out" RoundTrip in.pdf [out.ocd.epub]
//
// A failing STABLE is a drift, not a crash: use ContainerDiff to see WHICH entry moved and where.

import sugarcube.jexter.write.Conversion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;

public final class RoundTrip {

    private static final String[] TARGETS = { "ocd", "epub", "epub-reflow", "pdf", "html", "md", "doctags", "svg" };

    public static void main(String[] args) throws Exception {
        byte[] src = Files.readAllBytes(Path.of(args[0]));
        int bad = 0;

        System.out.println("REPRO — same input twice, byte-identical?");
        for (String t : TARGETS) {
            try {
                byte[] a = to(src, t), b = to(src, t);
                boolean ok = Arrays.equals(a, b);
                if (!ok) bad++;
                System.out.printf("   %-12s %-14s %,10d bytes%n", t, ok ? "ok" : "NOT REPRODUCIBLE", a.length);
            } catch (Exception e) {
                bad++;
                System.out.printf("   %-12s FAILED: %s%n", t, e);
            }
        }

        byte[] ocd = to(src, "ocd");
        boolean dt = Arrays.equals(to(src, "doctags"), to(ocd, "doctags"));
        if (!dt) bad++;
        System.out.println("DOCTAGS — pdf->doctags == pdf->ocd->doctags : " + (dt ? "ok" : "DIVERGENT"));

        byte[] again = to(ocd, "ocd");
        boolean stable = Arrays.equals(ocd, again);
        if (!stable) bad++;
        System.out.println("STABLE  — write(read(x)) == x : " + (stable ? "ok" : "DRIFT"));
        if (!stable) {
            Files.write(Path.of("/tmp/rt_a.ocd.epub"), ocd);
            Files.write(Path.of("/tmp/rt_b.ocd.epub"), again);
            System.out.println("          wrote /tmp/rt_a.ocd.epub and /tmp/rt_b.ocd.epub — run ContainerDiff on them");
        }

        if (args.length > 1) { Files.write(Path.of(args[1]), ocd); System.out.println("wrote " + args[1]); }

        System.out.println(bad == 0 ? "ALL GREEN" : bad + " GATE(S) FAILED");
        if (bad > 0) System.exit(1);
    }

    private static byte[] to(byte[] in, String target) throws Exception {
        return Conversion.convert(in, target, new HashMap<String, String>()).bytes();
    }
}
