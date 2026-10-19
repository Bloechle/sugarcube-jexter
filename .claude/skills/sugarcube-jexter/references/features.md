# Fixture corpus — catalog

`test/Fixtures.java` (in the repo) writes the corpus the functional gate runs on, with PDFBox and
nothing else: one PDF per feature, each isolating ONE trait, plus `rich` and `tagged`. Every page
carries the marker `MATRIX::<id>`. The declared truth is the method that draws the fixture — read it
(there is no manifest any more; it went with the Python generators on 2026-10-19). For an arbitrary
PDF, derive the truth from PDFBox instead (`verify_pipeline.py --gt-from-pdf`).

Fonts: Liberation Sans, from inside the PDFBox jar (Type0/CIDFontType2 subset, and once as a simple
TrueType with WinAnsi); Base-14 where the trait IS the Base-14 font; and for the scripts Liberation
lacks (CJK, Arabic) a box font compiled by the engine's own `GlyfOtf` — those features test encoding
and Unicode mapping, not glyph drawing.

| id | trait |
|---|---|
| `text_base14` | Helvetica, Helvetica-Bold, Times-Roman, Times-Italic, Courier — not embedded |
| `text_embedded_ttf` | Liberation Sans as Type0/CIDFontType2 subset and as simple TrueType (WinAnsi) |
| `text_cjk_cid` | CJK codepoints in a Type0/CIDFontType2 font (engine-compiled boxes) |
| `text_unicode` | Latin accents, Greek, Cyrillic, symbols — ToUnicode round-trip |
| `text_rtl` | Hebrew (Liberation) and Arabic codepoints (boxes), logical order, no shaping |
| `text_rotated` | text matrices at 0 / 45 / 90 / 180 / 270° |
| `text_render_modes` | Tr 0, 1, 2 and 3 (invisible, the OCR-layer case) |
| `text_clip_image` | Tr 7: glyph outlines as the clip, a JPEG painted through them |
| `text_tiny_leading` | 4.5 pt text, 4 pt leading: overlapping baselines, 40 lines |
| `text_ligature` | one glyph whose ToUnicode is `ff` (pdfTeX's ligatures), after the single `f` — the cmap keeps `f` |
| `vector_shapes` | filled+stroked rect, circle, ellipse, a stroked Bézier |
| `vector_clipping` | stripes clipped to a circle (`W n`) |
| `vector_transforms` | rotate+scale and skew through `cm` |
| `vector_gradient` | axial (Type 2) and radial (Type 3) shadings under a clip; a radial with a start radius whose start does not extend (`fr`, `data-extend`) |
| `vector_dash_caps` | four dash patterns, three caps, three joins |
| `vector_svg_like` | what a producer makes of an SVG: Y-down frame, rect, circle, polygon, curve |
| `vector_soft_mask` | a luminosity SMask over a fill, a stroke, text and an image, under text painted after; masked groups with a CMYK white `/BC` and an `/Alpha` mask; a masked fill on a `/Rotate 90` page |
| `color_rgb` / `color_cmyk` | DeviceRGB / DeviceCMYK swatches |
| `color_spot` | a real `/Separation` (PANTONE-1655-C) with a Type 2 tint transform to CMYK, tints 1 and .5 |
| `color_alpha` | ExtGState `/ca 0.5`, two overlapping discs |
| `image_jpeg_rgb` | DCTDecode, DeviceRGB |
| `image_png_alpha` | Flate RGB + `/SMask` over a coloured background |
| `image_cmyk_jpeg` | DCTDecode, DeviceCMYK — the flavour most tools mishandle |
| `image_1bit` | 1 bpc DeviceGray checkerboard |
| `page_mixed_sizes` | A4, Letter, A5 landscape: three MediaBoxes |
| `page_rotate` | `/Rotate 90` |
| `page_mediabox_cropbox` | CropBox inset 15 mm from the MediaBox |
| `doc_outline_links` | two pages, outline, an external URI link; the second bookmark and the internal link are NAMED destinations (`/Names /Dests` and the catalog `/Dests`, as hyperref and InDesign write them) |
| `doc_metadata` | Info dictionary: title, author, subject, keywords |
| `doc_running_heads` | five pages under one running head and a folio: furniture stays out of every logical tree |
| `rich` | the tracks beside the ink: a text field with a value, a checkbox on, a highlight, a note, two OCG layers (one off), two links, a two-level outline |
| `tagged` | StructTreeRoot, ParentTree, MarkInfo; a title typed `Titre` and made H1 by the RoleMap · P · H2 · ONE P holding TWO MCIDs · L > LI > (Lbl, LBody) · a P in a form XObject with its own `/StructParents`, reached by an MCR with `/Stm` (its MCID 0 is not the page's) |

Checked independently of PDFBox on 2026-10-19 (poppler `pdftotext`, `pdffonts`, `pdfimages`, qpdf): the
CJK and Arabic codepoints extract, the fonts embed as listed, the CMYK JPEG reads `cmyk 4 8 jpeg`,
the PNG carries its smask, and each structural token sits in its fixture and nowhere else.

What PDFBox-written fixtures cannot give: the quirks of OTHER producers (reportlab's CJK subset,
InDesign's content streams). That is the wild corpus' job (`scripts/fetch_wild_corpus.sh`), not CI's.
