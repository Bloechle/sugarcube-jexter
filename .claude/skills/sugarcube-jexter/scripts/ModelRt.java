// ModelRt — read(write(x)) == x at the MODEL level: every node, every field.
//
// Byte-stability of the container proves the writer is deterministic. It proves NOTHING about a field
// dropped on BOTH sides: write it wrong, read it back wrong the same way, and the bytes agree while the
// model has quietly changed. This is the gate that catches that — it found the font size being inferred
// from the run matrix determinant (silent, because the product still paints correctly).
//
// Reports three things separately, because only the first is a defect:
//   ECARTS      — a field that changed. Must be 0, apart from the render-transparent OCDGraphic
//                 wrappers, which are spliced by design and re-derived by a re-analysis.
//   TRANSFORM   — deviation under tolerance. Must sit at export precision (4 decimals, ~6e-5).
//   ORDRE       — z is re-derived from document order, so its VALUES change; the RANK must not.
//
//   javac -cp "lib/*:target/classes" -d out scripts/ModelRt.java
//   java  -cp "lib/*:target/classes:out" ModelRt in.pdf
import sugarcube.jexter.pdfimport.PdfImporter; import sugarcube.jexter.ocd.io.OCDReader;
import sugarcube.jexter.ocd.model.*; import sugarcube.jexter.core.JxTransform; import sugarcube.jexter.write.Conversion;
import java.io.File; import java.nio.file.*; import java.util.*;

/** read(write(model)) == model — au niveau du MODELE, champ par champ.
 *  La byte-stabilite du conteneur ne dit rien d un champ perdu a l ecriture ET a la lecture. */
public final class ModelRt {
  public static void main(String[] a) throws Exception {
    OCDDocument src = PdfImporter.convert(new File(a[0]));
    Path tmp = Path.of("/tmp/_mrt.ocd.epub");
    Files.write(tmp, Conversion.convert(src, "ocd", new HashMap<String,String>()).bytes());
    OCDDocument back = OCDReader.read(tmp.toFile());

    System.out.println("pages     : " + src.pageCount() + " -> " + back.pageCount());
    System.out.println("polices   : " + src.fonts().size() + " -> " + back.fonts().size());
    System.out.println("structures: " + src.structures().size() + " -> " + back.structures().size());
    // A render-transparent OCDGraphic wrapper is spliced on write BY DESIGN (it is analysis
    // clustering, not content; a re-analysis re-derives it), so it is the ONE documented
    // exception. Counting it as an ecart would make a third of a normal corpus report red
    // and train the reader to ignore the number. Counted apart, so 0 can mean 0.
    int diff = 0, seen = 0, spliced = 0;
    for (int pi = 0; pi < src.pageCount(); pi++) {
      Map<String,OCDNode> A = byId(src.page(pi)), B = byId(back.page(pi));
      System.out.println("page " + (pi+1) + " : noeuds " + A.size() + " -> " + B.size()
                       + " | clips " + src.page(pi).clips().size() + " -> " + back.page(pi).clips().size()
                       + " | liens " + src.page(pi).links().size() + " -> " + back.page(pi).links().size());
      for (var e : A.entrySet()) {
        OCDNode x = e.getValue(), y = B.get(e.getKey());
        if (y == null) {
          if (x instanceof OCDGraphic) { spliced++; continue; }        // expected splice, not a defect
          // An empty-geometry path (no segments at all) serializes to d="" and is not written:
          // it paints nothing on any surface and carries no geometry to restore. Expected, not lost.
          if (x instanceof OCDPath px && (px.geometry() == null || px.geometry().getPathIterator(null).isDone()))
            { spliced++; continue; }
          if (diff++ < 12) System.out.println("   PERDU  " + e.getKey() + " " + x.getClass().getSimpleName());
          continue;
        }
        seen++;
        for (String d : cmp(x, y)) if (diff++ < 12) System.out.println("   DIFF   " + e.getKey() + " " + d);
      }
      for (String k : B.keySet()) if (!A.containsKey(k) && diff++ < 12) System.out.println("   EN TROP " + k);
    }
    System.out.println("noeuds compares : " + seen + "   ecarts hors z : " + diff
                     + (spliced > 0 ? "   (+" + spliced + " wrapper(s) OCDGraphic splices, attendu)" : ""));
    System.out.printf("plus grand ecart de transform sous tolerance : %.7f%n", worst[0]);
    for (int pi = 0; pi < src.pageCount(); pi++) {
      List<String> ra = rank(src.page(pi)), rb = rank(back.page(pi));
      ra.retainAll(new HashSet<>(rb)); rb.retainAll(new HashSet<>(ra));
      System.out.println("page " + (pi+1) + " : ordre de peinture " + (ra.equals(rb) ? "PRESERVE" : "ROMPU")
                       + " (" + ra.size() + " noeuds)");
      if (!ra.equals(rb)) for (int i = 0; i < Math.min(ra.size(), rb.size()); i++)
        if (!ra.get(i).equals(rb.get(i))) { System.out.println("   premier ecart au rang " + i + " : " + ra.get(i) + " vs " + rb.get(i)); break; }
    }
  }
  static List<String> cmp(OCDNode x, OCDNode y) {
    List<String> d = new ArrayList<>();
    if (!x.getClass().equals(y.getClass())) d.add("classe " + x.getClass().getSimpleName() + "/" + y.getClass().getSimpleName());
    if (!Objects.equals(nz(x.role()), nz(y.role())))   d.add("role " + x.role() + "/" + y.role());
    if (!Objects.equals(nz(x.name()), nz(y.name())))   d.add("name " + x.name() + "/" + y.name());
    if (!Objects.equals(nz(x.blend()), nz(y.blend()))) d.add("blend " + x.blend() + "/" + y.blend());
    if (!Objects.equals(nz(x.clipId()), nz(y.clipId()))) d.add("clip " + x.clipId() + "/" + y.clipId());
    if (Math.abs(x.alpha() - y.alpha()) > 1e-3) d.add("alpha " + x.alpha() + "/" + y.alpha());
    double md = maxDelta(x.transform(), y.transform());
    if (md > 1e-3) d.add(String.format("transform ecart %.6f : %s / %s", md, x.transform(), y.transform()));
    else if (md > 0) worst[0] = Math.max(worst[0], md);
    if (x instanceof OCDText tx && y instanceof OCDText ty) {
      if (!Objects.equals(tx.text(), ty.text())) d.add("texte [" + tx.text() + "]/[" + ty.text() + "]");
      if (Math.abs(tx.fontSize() - ty.fontSize()) > 1e-3) d.add("fontSize " + tx.fontSize() + "/" + ty.fontSize());
      if (tx.fill() != ty.fill()) d.add(String.format("fill %08X/%08X", tx.fill(), ty.fill()));
      if (tx.renderMode() != ty.renderMode()) d.add("renderMode " + tx.renderMode() + "/" + ty.renderMode());
      if (!Objects.equals(tx.fontId(), ty.fontId())) d.add("fontId " + tx.fontId() + "/" + ty.fontId());
    }
    if (x instanceof OCDPath px && y instanceof OCDPath py) {
      if (px.fill() != py.fill()) d.add(String.format("fill %08X/%08X", px.fill(), py.fill()));
      if (px.stroke() != py.stroke()) d.add(String.format("stroke %08X/%08X", px.stroke(), py.stroke()));
      if (Math.abs(px.strokeWidth() - py.strokeWidth()) > 1e-3) d.add("strokeWidth " + px.strokeWidth() + "/" + py.strokeWidth());
      if (!px.geometry().toSvg().equals(py.geometry().toSvg())) d.add("geometrie");
    }
    return d;
  }
  static final double[] worst = { 0 };
  static double maxDelta(JxTransform a, JxTransform b) {
    return Math.max(Math.max(Math.abs(a.a()-b.a()), Math.abs(a.b()-b.b())),
           Math.max(Math.max(Math.abs(a.c()-b.c()), Math.abs(a.d()-b.d())),
                    Math.max(Math.abs(a.tx()-b.tx()), Math.abs(a.ty()-b.ty()))));
  }

  static List<String> rank(OCDPage p) {
    List<OCDNode> ns = new ArrayList<>(p.nodes().filter(n -> n.id() != null && !n.id().isEmpty()).toList());
    ns.sort(Comparator.comparingDouble(OCDNode::z));
    List<String> out = new ArrayList<>();
    for (OCDNode n : ns) out.add(n.id());
    return out;
  }

  static String nz(String s) { return s == null || s.isEmpty() ? null : s; }
  static Map<String,OCDNode> byId(OCDPage p) {
    Map<String,OCDNode> m = new LinkedHashMap<>();
    p.nodes().filter(n -> n.id() != null && !n.id().isEmpty()).forEach(n -> m.put(n.id(), n));
    return m;
  }
}
