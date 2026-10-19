package sugarcube.jexter.ocd.analysis;

/**
 * The instructions {@link Refiner} sends a language model — text, not logic, so it lives apart from the
 * code that builds the payload and reads the answer. {@link #DOCUMENT} asks for the whole document's tree
 * in one call; {@link #WINDOW} for one page at a time, its neighbours given as context.
 */
final class RefinerPrompts {

    private RefinerPrompts() {}

    // ── the per-page task framing (current page only; the host renormalises depth globally) ──────

    static final String WINDOW = """
        You reconstruct the LOGICAL STRUCTURE of ONE PAGE of a document at a time.

        You cannot see the page; each block carries the visual signals a reader would use. The input is
        a JSON object:
          documentProfile — document-wide typographic anchor (constant across pages):
            bodySizePt — the body text size in points;  topSizesPctOfBody — the most common sizes as a
            percentage of body (100 = body);  families — the dominant font families;  boldPct — share of
            bold text;  mono — present if the document uses monospaced text;  medianGap — the typical gap.
            Use it to calibrate what "large/small" means IN THIS DOCUMENT (a 16pt line is a heading only
            if the body is ~10pt).
          context — the PREVIOUS (prev) and NEXT (next) page, READ-ONLY, condensed. Use them only to
            judge whether this page's first/last block continues a neighbour. NEVER reference a context
            id: they are not valid grounding targets.
          page — the blocks of the CURRENT page, in reading order, each with:
            id (reference ONLY these, by exact id), box [x0,y0,x1,y1] on a 0..1000 top-left grid,
            size (% of body, >120 = likely heading), col, indent, width, gapAbove (whitespace above —
            large = a section opens), lines, bold, italic, caps, ends (sentence punctuation — typical of
            body, rare for headings), bullet, mono, face (display/heading typeface), running (DROP these),
            digits, rot, align, color, bg, kind (text|image|graphic), text (possibly truncated; leading
            "Chapter 3", "2.1", "Figure 4:" are strong cues).

        TASK: emit the logical subtree for the CURRENT page only. Combine the signals as a reader would —
        a short, large, bold, capitalised or coloured line with a big gapAbove and no sentence-ending
        opens a section; following blocks nest under it. Group bulleted/indented blocks under a list,
        rows/cells under a table, attach a caption to its figure. Send every "running" block to "dropped".
        For headings set "level" by RELATIVE size WITHIN THIS PAGE (1 = the largest heading here) — do NOT
        try to make levels consistent with other pages; the host renormalises heading depth across the
        whole document afterwards.

        OUTPUT: a single minified JSON object, NOTHING ELSE (no prose, no markdown fences):
          { "structure": { "type":"document", "children":[ <node>, ... ] },
            "dropped":   [ { "ref":"<id>", "reason":"running header" }, ... ] }
        where <node> is:
          { "type":"section|heading|paragraph|list|item|table|row|cell|figure|caption|quote|code|note|toc|span|other",
            "level":<int, headings only, 1 = largest on this page>, "ordered":<bool, lists only>,
            "refs":[ "<block id>", ... ], "children":[ <node>, ... ] }

        RULES:
          - refs MUST be ids from the "page" array; ids from context or invented ids are discarded.
          - Every CURRENT-page block should appear once, in "structure" or in "dropped".
          - refs are ids ONLY. NEVER echo block text or any field not listed above (it truncates the reply).
          - Emit ONE minified JSON object on a single line. Output deterministically.
        """;

    // ── the task framing (answer grammar + grounding contract) ──────────────────────────────────

    static final String DOCUMENT = """
        You reconstruct the LOGICAL STRUCTURE of a document from its content blocks.

        You cannot see the page, but each block carries the visual signals a reader would use.
        INPUT: a JSON array of blocks in reading order. Each block has:
          id      — stable identifier; reference blocks ONLY by this exact id, never invent ids
          page    — 0-based page index (parity = page%2 tells recto/verso for mirrored layouts)
          box     — [x0,y0,x1,y1] on a 0..1000 grid, origin top-left (smaller y = higher on the page)
          size    — font size relative to body text (100 = body, >120 = likely a heading)
          col     — 0-based column index the block sits in (multi-column pages)
          indent  — left offset from its column's left margin, 0..1000 (large = indented / quote / item body)
          width   — block width as a fraction of its column, 0..1000 (1000 = full column width)
          gapAbove— vertical whitespace above the block in grid units (large = a new section opens here); absent if top of column
          lines   — number of visual text lines (1–2 short lines often = a heading)
          bold    — present(=1) if bold
          italic  — present(=1) if italic (emphasis, captions, quotes)
          caps    — present(=1) if (near) ALL CAPS (heading or label)
          ends    — present(=1) if the text ends with sentence punctuation (. ! ?) — typical of BODY prose, rare for a heading
          bullet  — present(=1) if the text begins with a list marker
          mono    — present(=1) if a monospace font (code / preformatted)
          face    — present(=1) if the font family differs from the document body face (display / heading typeface)
          running — present(=1) if already identified as a running header/footer — DROP these
          digits  — percentage of digit characters when high (numeric-dense: table cell, data row, page number)
          rot     — text rotation in degrees when notably rotated (vertical labels, watermarks)
          align   — left | center | right | justify (justify/left long blocks = body; center short = title/caption)
          color   — hex of the text colour when not default black (a hue often marks a heading or link)
          bg      — hex of the block's background colour when shaded (callout box, code block, table header, highlight)
          kind    — text | image | graphic
          text    — the (possibly truncated) block text; leading words/numbering ("Chapter 3", "2.1", "Figure 4:") are strong cues

        TASK: emit the document's logical tree. Combine the signals as a reader would — a short, large,
        bold, capitalised or coloured line with a big gapAbove and no sentence-ending opens a section;
        following blocks nest under it until a same-or-higher heading appears. Use the leading text
        ("Chapter", "Section", "Art.", "1.2.3") to fix heading DEPTH and recover the GLOBAL hierarchy
        across pages (the hardest part). A `bg` or `mono` block is often a code block, callout or table
        header; group bulleted/indented blocks under a list, rows/cells under a table, and attach a
        caption to the figure/image it describes. Send every "running" block to "dropped", plus any
        other running header/footer/page number you spot.

        OUTPUT: a single JSON object, NOTHING ELSE (no prose, no markdown fences):
          {
            "structure": { "type": "document", "children": [ <node>, ... ] },
            "dropped": [ { "ref": "<id>", "reason": "running header" }, ... ]
          }
        where <node> is:
          {
            "type": "section|heading|paragraph|list|item|table|row|cell|figure|caption|quote|code|note|toc|span|other",
            "level": <int, headings only, 1 = top>,
            "ordered": <bool, lists only>,
            "refs": [ "<block id>", ... ],
            "children": [ <node>, ... ]
          }

        RULES:
          - refs MUST be ids that appear in the input; any other id will be discarded.
          - Every content block should appear exactly once, either in "structure" or in "dropped".
          - A node has refs, or children, or both. Headings/paragraphs/items/cells usually carry refs;
            document/section/list/table/figure usually carry children.
          - refs are ids ONLY. NEVER echo block text: do not emit text, text_content, label, content,
            or ANY field not listed above. Echoing text overflows the token budget and truncates the reply.
          - Emit ONE minified JSON object on a single line: no indentation, no line breaks, no markdown.
          - Output deterministically. Return only the JSON object.
        """;}
