import org.apache.pdfbox.Loader; import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*; import org.apache.fontbox.FontBoxFont;
import java.io.File;
public class MapProbe { public static void main(String[] a) throws Exception {
  try (PDDocument d = Loader.loadPDF(new File(a[0]))) {
    PDPage p = d.getPage(0);
    for (var name : p.getResources().getFontNames()) {
      PDFont f = p.getResources().getFont(name);
      if (f == null) continue;
      String base = f.getName();
      if (f.isEmbedded()) { System.out.printf("  %-26s EMBARQUÉE%n", base); continue; }
      FontMapping<FontBoxFont> m = FontMappers.instance().getFontBoxFont(base, f.getFontDescriptor());
      FontBoxFont sub = m.getFont();
      System.out.printf("  %-26s ABSENTE → substitut PDFBox = %s (%s)%s%n",
          base, sub.getName(), sub.getClass().getSimpleName(), m.isFallback() ? "  [fallback drapeaux]" : "  [table/système]");
    }
  } } }
