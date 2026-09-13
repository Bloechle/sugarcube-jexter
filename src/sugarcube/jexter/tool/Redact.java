package sugarcube.jexter.tool;

import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.redact.RedactionAudit;
import sugarcube.jexter.write.Conversion;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/**
 * Redact — the two verbs of {@link sugarcube.jexter.redact} on the command line, with exit codes a
 * CI or a batch script can act on. Everything else is {@link Conversion}: {@code --to=audit} is the
 * same report, {@code --redact=…} the same removal, from any entry point.
 *
 * <pre>
 *   Redact audit  &lt;in.pdf|in.ocd.epub&gt; [--json]                 exit 0 clean · 1 recoverable · 2 usage
 *   Redact apply  &lt;in&gt; &lt;out.pdf&gt; [--redact=audit|zones] [--redactFill=#rrggbb] [--&lt;opt&gt;=&lt;val&gt;]
 *                                                                exit 0 written and proven clean · 1 refused
 * </pre>
 *
 * {@code apply} defaults to {@code --redact=audit}: repair what a previous tool only covered. The output is
 * written only when the redactor's own audit of the result is clean.
 */
public final class Redact {

    private Redact() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) usage();
        switch (args[0]) {
            case "audit" -> audit(args);
            case "apply" -> apply(args);
            default -> usage();
        }
    }

    private static void audit(String[] args) throws Exception {
        boolean json = args.length > 2 && "--json".equals(args[2]);
        RedactionAudit.Report rep = RedactionAudit.audit(Conversion.load(new File(args[1]), ConvertOptions.defaults()));
        System.out.println(json ? rep.toJson() : rep.toText());
        System.exit(rep.clean() ? 0 : 1);
    }

    private static void apply(String[] args) throws Exception {
        if (args.length < 3) usage();
        Map<String, String> opts = Conversion.parse(args, 3);
        opts.putIfAbsent("redact", "audit");
        opts.putIfAbsent("selectable", "true");                // a redacted PDF should stay searchable
        ConvertOptions o = ConvertOptions.fromMap(opts);
        OCDDocument doc = Conversion.load(new File(args[1]), o);
        Conversion.Target target = Conversion.Target.ofFilename(args[2]);
        if (target == null) target = Conversion.Target.PDF;
        try {
            Conversion.Output out = Conversion.convert(doc, target.id, o);   // redacts, proves the zones, then projects
            Files.write(new File(args[2]).toPath(), out.bytes());
            RedactionAudit.Report rest = RedactionAudit.audit(doc);          // pre-existing leaks outside the zones, if any
            System.out.println("REDACTED " + args[2] + " (" + out.bytes().length + " bytes) — zones proven clean"
                    + (rest.clean() ? "; document clean" : "; NOTE " + rest.findings().size() + " pre-existing item(s) elsewhere — run audit"));
            System.exit(0);
        } catch (IllegalStateException refused) {
            System.err.println("REFUSED: " + refused.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("usage: Redact audit <in> [--json] | Redact apply <in> <out.pdf> [--redact=audit|page:x,y,w,h;…] [--redactFill=#rrggbb]");
        System.exit(2);
    }
}
