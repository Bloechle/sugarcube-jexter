package sugarcube.jexter.ocd.model;

/**
 * The document's output intent — the printing condition its device colours were prepared for, and the
 * ICC profile that characterises it (PDF {@code /OutputIntents}, PDF/X's {@code GTS_PDFX}). It says what
 * a {@code DeviceCMYK} value MEANS: Acrobat and MuPDF read every CMYK paint through it, and a file that
 * loses it is read through the reader's default instead. Measured on a press page carrying Coated
 * FOGRA39: the same {@code 1 0.5 0 0.2 k} background showed as 0,89,154 with it and 0,94,157 without —
 * the "slightly different blue" a customer saw. A document-level resource, carried verbatim.
 *
 * @param subtype     {@code /S} without the slash — {@code GTS_PDFX}, {@code GTS_PDFA1}, …
 * @param conditionId {@code /OutputConditionIdentifier} (may be empty)
 * @param condition   {@code /OutputCondition} (may be null)
 * @param info        {@code /Info} (may be null)
 * @param registry    {@code /RegistryName} (may be null)
 * @param profile     {@code /DestOutputProfile} bytes, or null when the intent names a registered
 *                    condition without embedding it
 * @param components  the profile's number of components (4 = CMYK, 3 = RGB), 0 when there is none
 */
public record OCDOutputIntent(String subtype, String conditionId, String condition, String info,
                              String registry, byte[] profile, int components) {

    /** True when the intent characterises CMYK, i.e. says what a {@code DeviceCMYK} value means. */
    public boolean isCmyk() { return profile != null && components == 4; }
}
