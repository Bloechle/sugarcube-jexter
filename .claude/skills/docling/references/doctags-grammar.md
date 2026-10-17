# DocTags — the grammar as docling-core reads it

Everything here was verified by executing `DoclingDocument.load_from_doctags` in
docling-core **2.92.0** (docling 2.123.0). The loader is a regex over root
elements (`document.py`, `load_from_doctags`), not an XML parser: what it does not
recognise it **silently drops**. That is the contract a DocTags *writer* must hit —
the token vocabulary in `tokens.py` is wider than what the loader accepts.

## Envelope and pages

```
<doctag> … <page_break> … </doctag>
```

- Pages are separated by a bare `<page_break>`. Split with
  `DocTagsDocument.from_multipage_doctags_and_images(text, images)` — one image (or
  `None`) per page. `from_doctags_and_image_pairs([text], [img])` treats the **whole
  string as one page** and only page 1 exists afterwards.
- Without an image a page is 1×1 and every bbox stays normalised (0–1). Pass blank
  PIL pages at the PDF's MediaBox size to get points back (`doctags_interop.py --pdf`).

## Location tokens

`<loc_x0><loc_y0><loc_x1><loc_y1>` immediately after the opening tag, then the text.

- Grid 0…500 on both axes: `round(500 · x / page_width)`, **top-left origin**
  (y grows downward). The re-export clamps 500 → 499.
- Only the **first four** `<loc_…>` in a chunk are read; extra ones are ignored.
- A chunk without loc loads with no provenance (item exists, no bbox, no page).

## Root elements the loader accepts

| tag | DocItemLabel | notes |
|---|---|---|
| `title` | title | Markdown `#` |
| `section_header_level_N` (N=1…6) | section_header, `level=N` | Markdown `#`×(N+1) — level 1 is `##` |
| `text` | text | the workhorse paragraph |
| `caption` | caption | root or nested in `picture`/`otsl` |
| `footnote` | footnote | |
| `page_header`, `page_footer` | page_header/page_footer | loaded and re-exported, **omitted from Markdown** (furniture) |
| `formula` | formula | text = LaTeX; Markdown `$$…$$` |
| `code` | code | optional language token first: `<code><loc…><_Java_>int x;</code>`; the `<_…_>` form is the only tag kept as inner text |
| `picture` | picture | may nest `<caption>`; Markdown `<!-- image -->` |
| `otsl` | table | OTSL cell tokens (below), may nest `<caption>` |
| `chart` | picture (chart) | OTSL data inside |
| `unordered_list` / `ordered_list` | group list/ordered_list | wraps `<list_item>` children |
| `document_index` | document_index | table of contents (OTSL body) |
| `checkbox_selected` / `checkbox_unselected` | checkbox_* | |
| `key_value_region` | key_value_region | graph of `key`/`value` cells |
| `inline` | group inline | |

## Silently dropped (they are DocumentToken values, NOT loader roots)

`<paragraph>`, `<reference>`, `<handwritten_text>`, and anything not in the table.
Measured: a `<paragraph>` chunk vanishes with its text, no warning — 20 % text loss
on a 5-block fixture. **Write `<text>`, never `<paragraph>`.**

Also lossy:
- An **unclosed** root tag is recovered as an empty item; its text is lost.
- `<list_item>` outside a list wrapper is not a root tag → dropped.
- `<list_item>` bboxes are only extracted when the page has an image (`li_bbox =
  … if image else None`) — with `None` pages list items load with **no provenance**.
  This is a loader quirk, not a writer error.
- Inner text strips every `<…>` except `<_…_>`, so a literal `<` in text needs care;
  the loader only deletes spans that look like a tag (`<letter…>` or `</…>`).

## Lists

```
<unordered_list><list_item><loc…>item one</list_item><list_item><loc…>item two</list_item></unordered_list>
```
Ordered lists use `<ordered_list>`; the marker is not carried in the text.

## Tables — OTSL

```
<otsl><loc_x0><loc_y0><loc_x1><loc_y1>
<ched>A<ched>B<nl>
<fcel>1<fcel>2<nl>
<caption><loc…>Table 1</caption>
</otsl>
```
One token per cell, `<nl>` ends a row:
`<ched>` column header, `<rhed>` row header, `<fcel>` data cell with text,
`<ecel>` empty cell, `<lcel>` merged with the cell to the left, `<ucel>` merged
with the cell above, `<xcel>` merged both ways, `<srow>` section row.
Rows must be rectangular; the loader builds `TableData` from the grid.

## Markdown projection (docling's, `export_to_markdown`)

title `#`, section level N → `#`×(N+1), text paragraphs separated by blank lines,
lists `- item`, tables as pipe tables (numeric cells right-padded), pictures
`<!-- image -->`, code fenced, formula `$$…$$`, footnotes as plain paragraphs,
page header/footer omitted. Compare jexter's Markdown against **this**, not
against a hand-written expectation.

## Export knobs (`export_to_doctags`)

`minified=True` (no newlines), `add_location=False`, `add_page_index`,
`xsize/ysize` (grid), `pages={…}`, `labels={…}` (subset). Default output puts
one root element per line and indents nothing.
