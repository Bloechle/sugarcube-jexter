# OCD facts — contract & finalization checklist

`scripts/OcdFacts.java` is the bridge from the jexter model to the surface-agnostic
verifier. It must print the **same facts JSON** the Python backends emit, so the
comparator treats an OCD surface exactly like a PDF or EPUB surface.

## Facts JSON contract

```json
{
  "stage": "ocd",
  "source": "book.ocd.epub",
  "text": "all page text concatenated",
  "pages": [
    {
      "index": 0,
      "size_pt": [595.3, 841.9],
      "rotation": 0,
      "text": "this page's extractable text",
      "fonts":  [ { "name": "DejaVu", "embedded": true, "type": "CIDFontType2", "upem": 2048 } ],
      "images": [ { "colorspace": "cmyk", "bpc": 8, "w": 200, "h": 130 } ],
      "colorspaces": ["DeviceRGB", "DeviceCMYK", "Separation"],
      "separations": ["PANTONE-1655-C"]
    }
  ]
}
```

Normalization rules (match the Python side):
- `colorspaces` use the device names `DeviceRGB` / `DeviceCMYK` / `DeviceGray` /
  `Separation`.
- image `colorspace` is the short lowercase form (`rgb` / `cmyk` / `gray`).
- `separations` holds the spot/Separation **names** (e.g. `PANTONE-1655-C`).
- `text` per page is the logical-order Unicode of the `OCDText` runs concatenated.

## Two invocations (the two probe points)

```
java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --pdf in.pdf    # ① virtual: PdfImporter.convert
java -cp "$PDFBOX_JAR:$JEXTER_CP" scripts/OcdFacts.java --ocd doc.ocd.epub  # ② reread:  OCDReader.read
```

Comparing the two facts JSONs is the **serialization round-trip** check
(`verify_pipeline.py --roundtrip ocd-virtual ocd-reread`). They must be identical;
any divergence is an OCD-EPUB writer/reader bug, not a model or converter bug.

## Status — both stages work

The accessors in `OcdFacts.java` are confirmed against the real OCD API. Stage ①
(PDF → `OCDDocument` via `PdfImporter.convert`) and stage ② (`.ocd.epub` → `OCDDocument`
via `OCDReader.read`) both run. Real mapping:

- `PdfImporter.convert(File)` → `OCDDocument` (static). ✓
- `OCDDocument.pages()` → `List<OCDPage>`; `findFont(String fontId)` → `OCDFont`. ✓
- `OCDPage`: `mediaBox()`/`effectiveBox()` (size via `.width()`/`.height()`), `displayWidth()`/`displayHeight()`, `rotation()`, `content()` (root nodes). ✓
- `OCDPage`: `toSvg()`/`fromSvg()` — THE flip, anchored on the MEDIA box — `svgWindow()` (the crop as
  the root viewBox), `cropBox(null)` clears, `copy(id)` for an independent page. In SVG-OCD the crop
  IS the `viewBox` and the rotation IS `data-rotate`; the frame is `data-mediabox`, the grammar
  `data-version`; no `data-crop`, no rotation carrier (2026-09-06, renamed 2026-09-13). ✓
- `ocd.model.Pages.apply(doc, "1,4-6,2")` — the page set (order · delete · duplicate · `+` blank), with
  the one remap of `OCDStructNode.Ref` / `OCDOutline` / `OCDLink`. Option: `--pages=`. ✓
- `OCDGroup.children()` (recursion). ✓
- `OCDText`: `text()` (unicode), `fontId()` (resolve via `doc.findFont`). ✓
- `OCDFont`: `name()`, `embedded()`. ✓
- `OCDImage`: `pixelWidth()`, `pixelHeight()`. ✓

**Honest limits (by design — OCD is a normalized sRGB pivot):**
- colours are flattened to sRGB int-argb at convert time → `colorspaces` is
  `DeviceRGB` when anything is painted, `separations` is always empty. CMYK/spot
  are *resolved away*; a GT declaring a Separation correctly shows as "not
  preserved". To carry device space to facts, the converter would have to record
  the source color space on the node — a deliberate future step, not a bug.
- images decode to sRGB PNG → `colorspace`=rgb, `bpc`=8 (CMYK/gray checked at the
  PDF stage / visually).
- glyph outlines are em-normalized → font `type`="" , `upem`=1000; the
  units-per-em trap is caught on the compiled OTF by `glyph_probe.py`.

### Stage ② (OCD-EPUB reread) — wired

The round-trip is a matched pair across two packages: **`write.OcdEpubWriter`**
writes the self-contained **`.ocd.epub`** (a valid fixed-layout EPUB 3 that is also
the model container — SVG-OCD v2 pages via `SvgOcdWriter`, the shared font file
`pages/fonts.svg`, the `ocd/` JSON members via `OcdMembers`), and
**`ocd.io.OCDReader.read(File)`** parses it back to an `OCDDocument`.

> **The legacy JSON `.ocd` container is gone**, and with it `OCDWriter` / `OCDJson`
> — do not look for them. `OCDReader` survived the format change with the same
> entry point, which is why the scripts below still work unchanged. The normative
> spec is `doc/FORMAT.md` (Part A container / Part B grammar, `data-version="2"`).

`readOcd()` is wired straight to the reader:

```java
static OCDDocument readOcd(File f) throws Exception { return OCDReader.read(f); }
```

So `verify_pipeline.py --roundtrip ocd-virtual ocd-reread` compares the two facts
JSONs; they must be identical (bounds derive from outlines, so they survive the
round-trip — don't assert stored bounds). Measured round-trip fidelity is
0.0000–0.0001 (virtual vs reread, rendered).
