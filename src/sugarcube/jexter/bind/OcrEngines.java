package sugarcube.jexter.bind;

import sugarcube.jexter.core.JxLog;
import sugarcube.jexter.core.OcrClient;

/**
 * The OCR engine a host binds at startup, chosen by the environment — the one place that reads
 * {@code JEXTER_OCR}, so every host (the Prism workbench, a conversion service) binds the same way.
 *
 * <p>{@code JEXTER_OCR=azure} binds {@link AzureOcrClient}, {@code JEXTER_OCR=tesseract} binds
 * {@link TesseractOcrClient}, anything else binds nothing: an image then imports as a page with no text
 * layer. Each client reads its own settings from the environment ({@code fromEnv}).
 */
public final class OcrEngines {

    private OcrEngines() { }

    /** Bind the engine the environment names, unless one is bound already, and return the bound client
     *  ({@code null} when none). Never throws: an engine named but incomplete (an endpoint without a key)
     *  is logged with its reason and left unbound, so a host still starts. */
    public static OcrClient bindFromEnv() {
        if (OcrClient.isBound()) return OcrClient.bound();
        try {
            OcrClient c = AzureOcrClient.fromEnv();
            if (c == null) c = TesseractOcrClient.fromEnv();
            if (c != null) { OcrClient.bind(c); JxLog.info(OcrEngines.class, "OCR bound from environment — " + c.engine()); }
            return c;
        } catch (RuntimeException e) {
            JxLog.warn(OcrEngines.class, "OCR not bound: " + e.getMessage());
            return null;
        }
    }
}
