import sugarcube.jexter.core.JxMedia;
import java.nio.file.*;

/** JxMedia is a kept capability with no caller in the tree: this is the caller a gate needs. */
public class ProbeMedia {
    public static void main(String[] a) throws Exception {
        for (String f : a) {
            byte[] b = Files.readAllBytes(Path.of(f));
            String ext = f.substring(f.lastIndexOf('.') + 1);
            JxMedia.Probe p = JxMedia.probe(ext, b);
            System.out.printf("%-12s %s → %d x %d, %.3f s%n", f.substring(f.lastIndexOf('/') + 1), ext, p.width(), p.height(), p.seconds());
        }
    }
}
