// ContainerDiff — WHERE two OCD-EPUBs differ: the entry, and the first byte that moved, in context.
//
// A failing byte-stability gate says only "drift". This says which member, and shows ~150 characters
// around the divergence on both sides — which is what turns a red gate into a diagnosis. Measured cases
// it settled: a clip path coming back 164.007 -> 164.0069 (float round-trip), and a data-o reading index
// shifting by three (an image's order read off its wrapper instead of the image).
//
//   (from the repo root, the engine compiled into target/classes; no compile step: single-file mode)
//   java -cp "target/classes:lib/*" test/ContainerDiff.java a.ocd.epub b.ocd.epub
//   java -cp "target/classes:lib/*" test/ContainerDiff.java x.ocd.epub   // x vs write(read(x))

import sugarcube.jexter.write.Conversion;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ContainerDiff {

    public static void main(String[] args) throws Exception {
        byte[] a = Files.readAllBytes(Path.of(args[0]));
        byte[] b = args.length > 1 ? Files.readAllBytes(Path.of(args[1]))
                                   : Conversion.convert(a, "ocd", new HashMap<String, String>()).bytes();
        Map<String, byte[]> A = entries(a), B = entries(b);
        int bad = 0;

        for (var e : A.entrySet()) {
            byte[] x = e.getValue(), y = B.get(e.getKey());
            if (y == null) { System.out.println("MISSING  " + e.getKey()); bad++; continue; }
            if (Arrays.equals(x, y)) continue;
            bad++;
            System.out.printf("DIFFERS  %s  %,d -> %,d bytes%n", e.getKey(), x.length, y.length);
            String s1 = new String(x), s2 = new String(y);
            int i = 0;
            while (i < Math.min(s1.length(), s2.length()) && s1.charAt(i) == s2.charAt(i)) i++;
            System.out.println("   at " + i);
            System.out.println("   a  …" + window(s1, i));
            System.out.println("   b  …" + window(s2, i));
        }
        for (String k : B.keySet()) if (!A.containsKey(k)) { System.out.println("EXTRA    " + k); bad++; }

        System.out.println(bad == 0 ? "IDENTICAL" : bad + " entry/entries differ");
        if (bad > 0) System.exit(1);
    }

    private static String window(String s, int at) {
        return s.substring(Math.max(0, at - 60), Math.min(s.length(), at + 90)).replace('\n', ' ');
    }

    private static Map<String, byte[]> entries(byte[] zip) throws Exception {
        var out = new LinkedHashMap<String, byte[]>();
        try (var zi = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = zi.getNextEntry()) != null; ) out.put(e.getName(), zi.readAllBytes());
        }
        return out;
    }
}
