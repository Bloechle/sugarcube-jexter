package sugarcube.jexter.tool;

import sugarcube.jexter.core.JxRect;
import sugarcube.jexter.core.OcrClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Zero-dependency {@link OcrClient} over a local Tesseract (5.x) command line: the image goes through a
 * temporary file, the words come back as Tesseract's TSV (level-5 rows: one word, its box, its confidence).
 * Nothing leaves the machine — the local, offline engine, and the one the gates run on.
 *
 * <p>Environment: {@code JEXTER_OCR=tesseract} selects it ({@link #fromEnv}); {@code JEXTER_TESSERACT}
 * names the executable when it is not on the path; {@code JEXTER_OCR_LANG} is the default language
 * ({@code eng} when unset — use what {@code tesseract --list-langs} shows).
 */
public final class TesseractOcrClient implements OcrClient {

    private static final long TIMEOUT_S = 120;

    private final String exe;
    private final String defaultLang;

    public TesseractOcrClient(String exe, String defaultLang) {
        this.exe = exe == null || exe.isBlank() ? "tesseract" : exe;
        this.defaultLang = defaultLang == null || defaultLang.isBlank() ? "eng" : defaultLang;
    }

    /** The client the environment asks for, or {@code null} when {@code JEXTER_OCR} does not name Tesseract. */
    public static TesseractOcrClient fromEnv() {
        if (!"tesseract".equalsIgnoreCase(System.getenv("JEXTER_OCR"))) return null;
        return new TesseractOcrClient(System.getenv("JEXTER_TESSERACT"), System.getenv("JEXTER_OCR_LANG"));
    }

    @Override public String engine() { return "tesseract"; }

    @Override public List<Word> read(byte[] image, String lang) throws IOException {
        Path in = Files.createTempFile("jexter-ocr-", ".img");
        try {
            Files.write(in, image);
            Process p = new ProcessBuilder(exe, in.toString(), "stdout", "-l",
                    lang == null || lang.isBlank() ? defaultLang : lang, "tsv")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String tsv = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("tesseract timed out"); }
            if (p.exitValue() != 0) throw new IOException("tesseract exited with " + p.exitValue());
            return parse(tsv);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            Files.deleteIfExists(in);
        }
    }

    /** Tesseract TSV → words. Columns: level page block par line word left top width height conf text;
     *  level 5 is a word, and (page, block, par, line) names the line it was read in. A row with blank text
     *  is a layout node, not a word. */
    static List<Word> parse(String tsv) {
        List<Word> out = new ArrayList<>();
        java.util.Map<String, Integer> lines = new java.util.HashMap<>();
        for (String row : tsv.split("\r?\n")) {
            String[] c = row.split("\t", -1);
            if (c.length < 12 || !"5".equals(c[0])) continue;
            String text = c[11].strip();
            if (text.isEmpty()) continue;
            try {
                double conf = Double.parseDouble(c[10]);
                int line = lines.computeIfAbsent(c[1] + '.' + c[2] + '.' + c[3] + '.' + c[4], k -> lines.size());
                out.add(new Word(text, new JxRect(Double.parseDouble(c[6]), Double.parseDouble(c[7]),
                        Double.parseDouble(c[8]), Double.parseDouble(c[9])), conf < 0 ? -1 : conf / 100.0, line));
            } catch (NumberFormatException ignore) { /* a malformed row is skipped, never guessed */ }
        }
        return out;
    }
}
