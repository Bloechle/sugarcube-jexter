package sugarcube.jexter.write;
import sugarcube.jexter.pdfimport.PdfImporter;
import sugarcube.jexter.pdfimport.ConvertOptions;
import sugarcube.jexter.ocd.model.*;
import java.io.*;
import java.util.*;

/** args: <pdf> <out.tsv>  -> writes heuristic headings (level TAB text); prints summary line. */
public class StructDump {
    public static void main(String[] a) throws Exception {
        OCDDocument doc = PdfImporter.convert(new File(a[0]), new ConvertOptions());
        OCDIndex ix = OCDIndex.of(doc);
        OCDStructure st = doc.structureById("heuristic");
        int[] counts = new int[OCDStructNode.Type.values().length];
        List<String> headings = new ArrayList<>();
        if (st!=null && st.root()!=null) walk(st.root(), ix, headings, counts);
        try (PrintWriter w = new PrintWriter(new FileWriter(a[1]))) {
            for (String h : headings) w.println(h);
        }
        int tagged=0; OCDStructure pt=doc.structureById("pdf");
        if (pt!=null && pt.root()!=null){int[] c2=new int[OCDStructNode.Type.values().length];List<String> h2=new ArrayList<>();walk(pt.root(),ix,h2,c2);tagged=h2.size();}
        System.out.println("SUMMARY\t"+new File(a[0]).getName()+"\theur_head="+headings.size()
            +"\ttagged_head="+tagged+"\tLIST="+counts[OCDStructNode.Type.LIST.ordinal()]
            +"\tITEM="+counts[OCDStructNode.Type.ITEM.ordinal()]);
    }
    static void walk(OCDStructNode s, OCDIndex ix, List<String> H, int[] c){
        c[s.type().ordinal()]++;
        if (s.type()==OCDStructNode.Type.HEADING) H.add(s.level()+"\t"+ix.text(s).replaceAll("\\s+"," ").strip());
        for (OCDStructNode k : s.children()) walk(k, ix, H, c);
    }
}
