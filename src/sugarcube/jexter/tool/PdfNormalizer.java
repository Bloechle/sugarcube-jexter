package sugarcube.jexter.tool;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.write.Conversion;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PDF → OCD → PDF: regenerates a <em>normalized</em> PDF through jexter's canonical OCD model.
 *
 * <p>Whatever the source's authoring quirks, the output inherits every normalization the pipeline
 * performs: repaired embedded fonts ({@code post}-table fix), per-glyph self-clips dropped, a single
 * regular content structure, consistent color/transform handling. Two text strategies:
 * a selectable embedded-font layer (default) or outlined glyphs.
 *
 * <p>Runnable as a CLI, on a single file. With no arguments it prints its usage: the GUI for this job
 * is the `Jexter` web app — it ships in LAUNCHERS, it is packaged, it renders the {@link ConvertOptions}
 * registry generically, and it is what a 211-line Swing window here used to duplicate.
 */
public final class PdfNormalizer {

    private PdfNormalizer() {}

    /**
     * @param opts       conversion settings (see {@link ConvertOptions})
     * @param selectable {@code true} → embedded-font text layer (searchable); {@code false} → outlined glyphs
     */
    public static OCDDocument normalize(File in, File out, ConvertOptions opts, boolean selectable) throws IOException {
        OCDDocument doc = PdfImporter.convert(in, opts);
        Map<String, String> m = new LinkedHashMap<>();
        opts.toMap().forEach((k, v) -> m.put(k, String.valueOf(v)));   // carry the import/export settings
        m.put("selectable", String.valueOf(selectable));
        Files.write(out.toPath(), Conversion.convert(doc, "pdf", m).bytes());
        return doc;
    }

    public static OCDDocument normalize(File in, File out) throws IOException {
        return normalize(in, out, ConvertOptions.defaults(), true);
    }

    // ── CLI ─────────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        // No arguments → say how to use it. There WAS a 211-line Swing window here, a third way to
        // normalize a PDF beside this CLI and the `Jexter` web app — undocumented, not in LAUNCHERS,
        // and duplicating a surface that ships, is packaged and is tested. One job, one GUI.
        if (args.length == 0) { usage(System.err); System.exit(2); }

        File in = null, out = null;
        boolean selectable = true;
        Map<String, String> opt = new LinkedHashMap<>();
        for (String a : args) {
            if (a.equals("--outline")) selectable = false;
            else if (a.equals("--selectable")) selectable = true;
            else if (a.startsWith("--")) {                       // --<convertOption>=<value>
                int eq = a.indexOf('=');
                if (eq > 2) opt.put(a.substring(2, eq), a.substring(eq + 1));
            } else if (in == null) in = new File(a);
            else if (out == null) out = new File(a);
        }
        if (in == null) { usage(System.err); System.exit(2); }
        if (out == null) out = defaultOut(in);
        OCDDocument doc = normalize(in, out, ConvertOptions.fromMap(opt), selectable);
        System.out.println("normalized " + in.getName() + " -> " + out.getName()
                + (selectable ? " (selectable)" : " (outline)") + "  " + doc);
    }

    private static void usage(PrintStream e) {
        e.println("usage: PdfNormalizer [<in.pdf> [out.pdf]] [--outline] [--<option>=<value>]");
        e.println("  (no arguments)        this usage — the GUI for this job is the Jexter web app");
        e.println("  --outline             outline glyphs instead of a selectable text layer");
        for (ConvertOptions.Opt<?> o : ConvertOptions.ALL)
            e.printf("  --%s=<%s>   (def %s)  %s%n",
                    o.key(), o.type().name().toLowerCase(), o.def(), o.label());
    }

    /** {@code foo.pdf} → {@code foo-normalized.pdf} next to the source. */
    private static File defaultOut(File in) {
        return new File(in.getAbsoluteFile().getParentFile(), baseName(in) + "-normalized.pdf");
    }

    private static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
