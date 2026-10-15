// Rich — what the model keeps of the things a page carries BESIDE its ink: links, form fields,
// annotations, layers and the outline, before and after a round-trip.
//
// These survive on their own tracks (page lists, jexter/ members), not in the SVG content tree, so
// nothing in the paint or text gates touches them: a whole track can vanish with every other gate
// green. Feed it a PDF that actually has them — most corpus pages have none.
//
//   javac -cp "lib/*:target/classes" -d out scripts/Rich.java
//   java  -cp "lib/*:target/classes:out" Rich in.pdf

import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.OCDDocument;
import sugarcube.jexter.ocd.model.OCDFormField;
import sugarcube.jexter.ocd.model.OCDLink;
import sugarcube.jexter.ocd.model.OCDPage;
import sugarcube.jexter.write.Conversion;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public final class Rich {

    public static void main(String[] args) throws Exception {
        OCDDocument src = PdfImporter.convert(new File(args[0]));
        Path tmp = Path.of("/tmp/_rich.ocd.epub");
        Files.write(tmp, Conversion.convert(src, "ocd", new HashMap<String, String>()).bytes());
        OCDDocument back = OCDReader.read(tmp.toFile());
        dump("PDF ", src);
        dump("OCD ", back);
    }

    private static void dump(String tag, OCDDocument d) {
        int links = 0, fields = 0, annots = 0;
        for (int pi = 0; pi < d.pageCount(); pi++) {
            OCDPage p = d.page(pi);
            links += p.links().size();
            fields += p.fields().size();
            annots += p.annotations().size();
        }
        System.out.printf("%s pages=%d  links=%d  fields=%d  annotations=%d  layers=%d  outline=%s%n",
                tag, d.pageCount(), links, fields, annots, d.layers().size(),
                d.outline() == null ? "none" : String.valueOf(d.outline().size()));
        for (int pi = 0; pi < d.pageCount(); pi++) {
            for (OCDLink l : d.page(pi).links())
                System.out.printf("     link  p%d -> %s%n", pi + 1, l.uri() != null ? l.uri() : "page " + (l.pageIndex() + 1));
            for (OCDFormField f : d.page(pi).fields())
                System.out.printf("     field p%d  %s = %s%n", pi + 1, f.name(), f.value());
        }
    }
}
