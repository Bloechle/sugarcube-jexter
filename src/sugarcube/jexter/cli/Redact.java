package sugarcube.jexter.cli;

import sugarcube.jexter.Conversion;
import sugarcube.jexter.core.Args;
import sugarcube.jexter.core.ConvertOptions;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.redact.RedactionAudit;

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
        Args a = Args.parse(args);
        if (a.positional().size() < 2) usage();
        switch (a.positional(0)) {
            case "audit" -> audit(a);
            case "apply" -> apply(a);
            default -> usage();
        }
    }

    private static void audit(Args a) throws Exception {
        boolean json = a.takeSwitch("json");
        RedactionAudit.Report rep = RedactionAudit.audit(Conversion.load(new File(a.positional(1)), ConvertOptions.defaults()));
        System.out.println(json ? rep.toJson() : rep.toText());
        System.exit(rep.clean() ? 0 : 1);
    }

    private static void apply(Args a) throws Exception {
        String outPath = a.positional(2);
        if (outPath == null) usage();
        Map<String, String> opts = a.flags();
        opts.putIfAbsent("redact", "audit");
        opts.putIfAbsent("selectable", "true");                // a redacted PDF should stay searchable
        ConvertOptions o = ConvertOptions.fromMap(opts);
        OCDDocument doc = Conversion.load(new File(a.positional(1)), o);
        Conversion.Target target = Conversion.Target.ofFilename(outPath);
        if (target == null) target = Conversion.Target.PDF;
        try {
            Conversion.Output out = Conversion.convert(doc, target.id, o);   // redacts, proves the zones, then projects
            Files.write(new File(outPath).toPath(), out.bytes());
            RedactionAudit.Report rest = RedactionAudit.audit(doc);          // pre-existing leaks outside the zones, if any
            System.out.println("REDACTED " + outPath + " (" + out.bytes().length + " bytes) — zones proven clean"
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
