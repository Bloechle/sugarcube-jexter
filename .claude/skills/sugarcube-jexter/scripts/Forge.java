import sugarcube.jexter.font.Foundry;
import java.nio.file.*;

/** Foundry is a kept capability with no caller in the tree: this is the caller a gate needs. */
public class Forge {
    public static void main(String[] a) throws Exception {
        Foundry.Font f = Foundry.build(Files.readString(Path.of(a[0])));
        Files.write(Path.of(a[1]), f.bytes());
        System.out.printf("%s · %s · %d bytes%n", f.filename(), f.mediaType(), f.bytes().length);
    }
}
