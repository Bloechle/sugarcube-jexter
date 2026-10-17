# OCD-AI — the LLM-facing projection of OCD

Status: v1 — decisions taken, to implement as `write/` projection. One page on purpose.

## 1. Contract

OCD-AI is a **write-only projection** of an OCD-EPUB document, in the same sense as
Markdown or DocTags: OCD is the sole authority, OCD-AI carries nothing OCD does not
already hold. It is **a strict superset of DocTags**: a file that is valid OCD-AI is
valid DocTags — docling-core's loader drops every token it does not know inside an
element and every root element it does not know, keeping text and bbox intact
(verified, docling-core 2.92). One writer, two readers: docling sees DocTags, an OCD
reader sees everything.

Three purposes, in order of delivery: (1) input of the LlmRefiner (replaces
SigDump, which must diff to zero after migration), (2) an **edit contract** — every
block carries a stable OCD id, so a model can return edits that jexter applies in the
model and re-projects to PDF/EPUB, (3) layout-aware chunking and ground-truth export.

## 2. Envelope and pages

```
<doctag><ocdai_1><doc_5f2a…>
<pg_1><pw_595><ph_842><ncol_1><dfs_11><dw_400><dfc_1f2937>
…elements…
<page_break>
<pg_2><pw_595><ph_842><ncol_2><dfs_11><dw_400><dfc_1f2937>
…
</doctag>
```

- `<ocdai_1>` format version; `<doc_…>` document id (the OCD-EPUB's).
- Page tokens follow `<page_break>` (or the opening `<doctag>`): page number, size in
  points, column count from `XYCut.order`, and the **page defaults** (`d*`) that
  compact mode omits from blocks: body font size, weight, text colour. All are
  ignored by docling, which has no page-size notion at all (pages with no image are
  1×1 there) — with `pw/ph` an OCD reader de-normalises `loc` back to points.
- Element order in the file **is** the reading order (`XYCut.order`); there is no
  order token.

## 3. Elements

DocTags root tags, used with DocTags meaning (`text`, `title`,
`section_header_level_N`, `caption`, `footnote`, `page_header`, `page_footer`,
`formula`, `code`, `picture`, `otsl`, `unordered_list`/`ordered_list` +
`list_item`), always `<tag><loc_x0><loc_y0><loc_x1><loc_y1>` then attributes then
text. `loc` on the 500 grid, top-left origin, `round(500·x/pw)`. Never emit
`<paragraph>` or `<reference>` (docling drops them).

Extension roots (docling drops them silently; that is the design):

| element | carries | text |
|---|---|---|
| `ocdlink` | `<of_b12>` owning block, `<loc…>` | target URL or `#`-anchor |
| `ocdfield` | `<of_…>`, `<ft_text\|check\|radio\|choice>`, `<fv_…>` value if alnum | field name |
| `ocdrule` | `<loc…>` | — (horizontal/vertical rule; table & section cue) |
| `ocdvector` | `<loc…>`, `<n_42>` path count | — (a drawing docling cannot see) |

## 4. Block attributes

All `<key_value>`, value `[A-Za-z0-9]+`, placed after the four `loc` tokens.

| key | value | source in OCD | when emitted (compact) |
|---|---|---|---|
| `id` | block id, e.g. `b4` | OCD node id | **always** |
| `fs` | font size, pt, integer | dominant run | ≠ page `dfs` |
| `w` | 100–900 | `OCDFont.weight()` — never family-name matching | ≠ `dw` |
| `i` | `1` | dominant run italic | italic |
| `fc` | rrggbb | text fill | ≠ `dfc` |
| `bg` | rrggbb | background detection | any non-page background |
| `col` | 1…ncol | `XYCut` | `ncol > 1` |
| `hy` | `1` | last line ends in a hyphenation | present |
| `cont` | `1` | continues the previous block (column/page break) | present |
| `sc` | `cjk` \| `rtl` | `JxText.isLtrWordScript` | non-LTR-word script |
| `lang` | bcp47 alnum, `frCH` | document / structure language | ≠ document default |
| `ln` | line count | Liner | > 1 (cheap paragraph-vs-heading cue) |

Full mode emits every attribute on every block (debug, SigDump equivalence);
compact mode is the default for models. Rule: **state the deviation, never the
norm.** Whitespace inside text is normalised to single spaces; a hard break inside a
block is written ` <br>` — space **then** tag — because docling strips the tag and
would otherwise glue the two words (measured: `choice<br>to` → `choiceto`).

Example (compact, page 2 of the DocEng proposal):

```
<section_header_level_1><loc_59><loc_66><loc_294><loc_92><id_b3><fs_32><w_300><fc_0a58a3>Welcome to Fribourg</section_header_level_1>
<text><loc_72><loc_160><loc_275><loc_170><id_b5><w_700><bg_eef3f8>A City of Culture, Innovation, and Collaboration</text>
<text><loc_72><loc_183><loc_426><loc_212><id_b6><bg_eef3f8><ln_2>Fribourg offers a unique blend of tradition, innovation, and natural beauty, creating an inspiring environment for international collaboration.</text>
<text><loc_59><loc_246><loc_440><loc_293><id_b7><ln_3><hy_1>Fribourg is an exceptional choice to host the ACM Symposium on Document Engineering 2026. Nestled in western Switzerland, this scenic and historic city offers excellent infra-</text>
```

## 5. Edits — the return channel

A model answers with one JSON object per line; unknown verbs are rejected, ids that
do not resolve in the OCD-EPUB are rejected, nothing is applied partially.

```
{"op":"label",   "id":"b5",  "label":"section_header", "level":2}
{"op":"caption", "id":"b19", "of":"b18"}
{"op":"merge",   "ids":["b7","b8"]}
{"op":"split",   "id":"b12", "at":"The nearby Sarine"}
{"op":"order",   "id":"b21", "before":"b17"}
{"op":"alt",     "id":"b18", "text":"Two people in medieval costume on a cobbled street"}
{"op":"table",   "ids":["b30","b31","b32","b33"], "cols":2}
```

`label` values are the DocTags labels; `level` only with `section_header`. `table`
is the only way an LLM creates an `<otsl>` from text blocks (columns by x-alignment
inside jexter). Every applied edit is a normal OCD mutation, so `write(read(x))`
and Fid2=0 cover it unchanged — edits touch structure, never glyphs.

## 6. Budget and gates

Cost estimate per block over bare DocTags: full mode ≈ +8 tokens, compact ≈ +1.5
(the id plus one deviation on a third of blocks). On the 12-page proposal (~150
blocks) that is ~+1200 vs ~+230 tokens — to **measure** with a tokenizer before
freezing, on Fedlex too.

Gates before shipping the writer:

1. `doctags_interop.py` green with the four extension roots whitelisted: zero text
   loss, zero dropped *standard* elements, re-export unchanged.
2. `SigDump` regenerated from the OCD-AI writer diffs to zero on the corpus.
3. Every `id` in an OCD-AI file resolves in its OCD-EPUB; every edit in §5 applied on
   a fixture re-projects to a byte-stable OCD-EPUB and a pixel-identical PDF.
4. Token count per page ≤ 1.15 × bare DocTags in compact mode on Fedlex.

Decisions taken (2026-08-27):
- Extension `.ocdai`, marker `<ocdai_1>`; the file stays loadable as DocTags by
  anything that takes the string, which is how docling consumes DocTags anyway.
- `fs` is an integer point size: a block's dominant size is a classification cue,
  super/subscript is a run property and stays in OCD, not in the projection.
- No `cf` in v1: nothing computes a confidence yet, and a token nobody produces is
  a token everybody has to explain. Add it the day a producer exists.
- Full mode is a `--full` switch on the writer, not a second writer.
