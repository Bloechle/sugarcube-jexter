// ============================================================================
//  jexter — Sugarcube : a technical overview.  OCD = Open Canonical Document.
//
//  SELF-CONTAINED: the fourteen hand-authored figures are inlined verbatim below,
//  so nothing outside this file is needed — except the three typefaces.
//
//    typst compile jexter.typ jexter.pdf          (Typst 0.13 or newer)
//
//  Lora      serif, body      Google Fonts, SIL OFL 1.1
//  Poppins   sans, headings   Google Fonts, SIL OFL 1.1
//  DejaVu Sans Mono  code     already on most systems
//
//  They are NOT vendored: large binaries, and shipping them would drag a
//  redistribution obligation into a repository with no other reason to carry one.
//  Install them system-wide, or  typst compile --font-path <dir> …
//  Typst SUBSTITUTES SILENTLY when a family is missing — if the output looks off,
//  check the font list first; that is almost always the cause.
//
//  The compiled PDF is not committed (a binary is re-stored whole on every
//  rebuild); it ships as a GitHub release asset.
// ============================================================================

#let ink     = rgb("#1c1e1b")
#let soft    = rgb("#5f6360")
#let faint   = rgb("#9aa09a")
#let hair    = rgb("#e6e7e2")
#let paper   = rgb("#fcfcfa")
// The Sugarcube blues, the same three the mark and the tools carry.
#let brand   = rgb("#33699f")
#let brandD  = rgb("#2b567f")
#let brandP  = rgb("#e7eff8")
#let indigo  = rgb("#3b5b7a")
#let indigoP = rgb("#e7edf4")
#let amber   = rgb("#b8801f")
#let amberP  = rgb("#f6ecd7")

#set document(title: "jexter — Sugarcube", author: "Sugarcube Information Technology Sàrl")
// The one door to image(): an inlined figure is a string, image() wants bytes.
#let svgimg(s, ..a) = image(bytes(s), format: "svg", ..a)

// ═══════════════════════════════════════════════════════════════════════════════
// ASSETS — the figures, inlined. Verbatim SVG in raw blocks; `.text` yields the
// string, `bytes()` hands it to image(). They sit here because Typst evaluates a
// file top to bottom and a `#let` must precede its use.
//
//        >>> The document itself starts at the DOCUMENT banner, line 861. <<<
// ═══════════════════════════════════════════════════════════════════════════════

#let SVG_ANALYSIS_CHAIN = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 28 780 540" font-family="Poppins, DejaVu Sans, sans-serif">
  <!-- spine -->
  <line x1="60" y1="58" x2="60" y2="492" stroke="#cdd3c8" stroke-width="2.5"/>

  <!-- ===== stage chips ===== -->
  <!-- helper layout: dot at x60; chip x 86..420 (w 334, h 46); step 58 -->
  <!-- 1 clean -->
  <circle cx="60" cy="69" r="6" fill="#33699f"/>
  <g transform="translate(86,46)"><rect width="334" height="46" rx="10" fill="#ffffff" stroke="#dfe1db" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#1c1e1b">Clean to the ink base</text>
    <text x="14" y="37" font-size="10.3" fill="#6a6f69">dissolve paragraphs, drop blanks &amp; hidden ink</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#bcc1b7">Cleaner</text></g>
  <!-- 2 lines & spaces -->
  <circle cx="60" cy="127" r="6" fill="#3b5b7a"/>
  <g transform="translate(86,104)"><rect width="334" height="46" rx="10" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#27425c">Reconstruct &amp; freeze the lines</text>
    <text x="14" y="37" font-size="10.3" fill="#4a627d">band runs by overlap · freeze word-spaces once</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#9fb2c5">Liner · Spacer</text></g>
  <!-- 3 segment paragraphs -->
  <circle cx="60" cy="185" r="6" fill="#3b5b7a"/>
  <g transform="translate(86,162)"><rect width="334" height="46" rx="10" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#27425c">Segment the paragraphs</text>
    <text x="14" y="37" font-size="10.3" fill="#4a627d">frozen lines &#8594; blocks by size, weight, leading</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#9fb2c5">Segmenter · Paragrapher</text></g>
  <!-- 4 heads/feet -->
  <circle cx="60" cy="243" r="6" fill="#3b5b7a"/>
  <g transform="translate(86,220)"><rect width="334" height="46" rx="10" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#27425c">Find running heads &amp; feet</text>
    <text x="14" y="37" font-size="10.3" fill="#4a627d">by recto/verso stack stability at the page edge</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#9fb2c5">Furniture</text></g>
  <!-- 5 cluster drawings -->
  <circle cx="60" cy="301" r="6" fill="#33699f"/>
  <g transform="translate(86,278)"><rect width="334" height="46" rx="10" fill="#ffffff" stroke="#dfe1db" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#1c1e1b">Cluster the drawings</text>
    <text x="14" y="37" font-size="10.3" fill="#6a6f69">group vector paths into one graphic</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#bcc1b7">GraphicClusterer</text></g>
  <!-- 6 ids (plumbing) -->
  <circle cx="60" cy="359" r="6" fill="#b7bcb6"/>
  <g transform="translate(86,336)"><rect width="334" height="46" rx="10" fill="#f6f7f4" stroke="#e6e7e2" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#7c817a">Stamp identities</text>
    <text x="14" y="37" font-size="10.3" fill="#9aa09a">deterministic ids — the single authority for refs</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#cdd1c8">IdStamper</text></g>
  <!-- 7 headings & structure -->
  <circle cx="60" cy="417" r="6" fill="#3b5b7a"/>
  <g transform="translate(86,394)"><rect width="334" height="46" rx="10" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.4"/>
    <text x="14" y="20" font-size="12.5" font-weight="600" fill="#27425c">Build headings &amp; structure</text>
    <text x="14" y="37" font-size="10.3" fill="#4a627d">by document-wide typographic recurrence</text>
    <text x="322" y="16" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#9fb2c5">StructureBuilder</text></g>
  <!-- 8 LLM optional -->
  <circle cx="60" cy="481" r="6" fill="#ffffff" stroke="#b8801f" stroke-width="2"/>
  <g transform="translate(86,462)"><rect width="334" height="38" rx="10" fill="#f6ecd7" stroke="#e6d3a8" stroke-width="1.4" stroke-dasharray="4 3"/>
    <text x="14" y="17" font-size="12" font-weight="600" fill="#8a5e12">Refine with an LLM</text>
    <text x="14" y="31" font-size="10" fill="#9a7321">figures · tables · captions · lists — off by default</text>
    <text x="322" y="15" text-anchor="end" font-size="8" font-family="DejaVu Sans Mono" fill="#d8c594">Refiner</text></g>

  <!-- ===== right rail ===== -->
  <g transform="translate(452,46)">
    <rect width="296" height="512" rx="14" fill="#fbfcf8" stroke="#e6e7e2" stroke-width="1.5"/>
    <text x="20" y="34" font-size="14" font-weight="600" fill="#2b567f">Nothing here moves a pixel</text>
    <text x="20" y="62" font-size="11.5" fill="#52564f">Each step only sets roles, a reading-</text>
    <text x="20" y="80" font-size="11.5" fill="#52564f">order index, and a structure tree</text>
    <text x="20" y="98" font-size="11.5" fill="#52564f"><tspan font-weight="600" fill="#1c1e1b">by reference</tspan> — never geometry, colour,</text>
    <text x="20" y="116" font-size="11.5" fill="#52564f">glyphs or paint order.</text>

    <line x1="20" y1="138" x2="276" y2="138" stroke="#e6e7e2"/>
    <text x="20" y="166" font-size="13" font-weight="600" fill="#27425c">Reading the colours</text>
    <circle cx="30" cy="190" r="6" fill="#33699f"/><text x="46" y="194" font-size="11.5" fill="#52564f">cleans / enriches the content</text>
    <circle cx="30" cy="216" r="6" fill="#3b5b7a"/><text x="46" y="220" font-size="11.5" fill="#52564f">builds the physical &amp; logical layer</text>
    <circle cx="30" cy="242" r="6" fill="#b7bcb6"/><text x="46" y="246" font-size="11.5" fill="#52564f">internal bookkeeping</text>
    <circle cx="30" cy="268" r="6" fill="#ffffff" stroke="#b8801f" stroke-width="2"/><text x="46" y="272" font-size="11.5" fill="#52564f">optional, off by default</text>

    <line x1="20" y1="292" x2="276" y2="292" stroke="#e6e7e2"/>
    <text x="20" y="320" font-size="13" font-weight="600" fill="#1c1e1b">One ordering engine</text>
    <text x="20" y="346" font-size="11.5" fill="#52564f">The same XY-Cut++ engine both</text>
    <text x="20" y="364" font-size="11.5" fill="#52564f">segments the blocks and orders the</text>
    <text x="20" y="382" font-size="11.5" fill="#52564f">reading sequence — tuned in one place.</text>

    <line x1="20" y1="404" x2="276" y2="404" stroke="#e6e7e2"/>
    <text x="20" y="432" font-size="13" font-weight="600" fill="#1c1e1b">When the PDF is tagged</text>
    <text x="20" y="458" font-size="11.5" fill="#52564f">A tagged (PDF/UA) document brings</text>
    <text x="20" y="476" font-size="11.5" fill="#52564f">its own structure — it rides alongside</text>
    <text x="20" y="494" font-size="11.5" fill="#52564f">the heuristic tree, and a consumer picks.</text>
  </g>
</svg>
```.text

#let SVG_API = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 196" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="ar" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#9aa09a"/></marker>
    <marker id="ag" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#33699f"/></marker>
  </defs>
  <text x="8" y="20" font-size="13" font-weight="600" fill="#1c1e1b">Convert over HTTP — <tspan fill="#2b567f">one key, every target</tspan></text>

  <!-- client -->
  <g transform="translate(20,72)">
    <rect width="150" height="54" rx="10" fill="#ffffff" stroke="#cfd2cc" stroke-width="1.4"/>
    <text x="75" y="24" text-anchor="middle" font-size="11" font-weight="600" fill="#1c1e1b">Your service</text>
    <text x="75" y="40" text-anchor="middle" font-size="8.5" fill="#6a6f69">server-to-server</text>
  </g>

  <!-- request -->
  <g transform="translate(176,99)">
    <rect x="2" y="-27" width="116" height="16" rx="8" fill="#e7eff8" stroke="#8ec63f"/>
    <text x="60" y="-15" text-anchor="middle" font-size="8" font-family="DejaVu Sans Mono" fill="#2b567f">Bearer &lt;key&gt;</text>
    <line x1="0" y1="0" x2="116" y2="0" stroke="#9aa09a" stroke-width="1.8" marker-end="url(#ar)"/>
    <text x="60" y="18" text-anchor="middle" font-size="8" fill="#6a6f69">POST · PDF or .ocd.epub</text>
  </g>

  <!-- cloud / engine -->
  <g transform="translate(300,72)">
    <rect width="208" height="54" rx="10" fill="#e7eff8" stroke="#33699f" stroke-width="1.6"/>
    <text x="104" y="23" text-anchor="middle" font-size="10" font-weight="600" font-family="DejaVu Sans Mono" fill="#1c1e1b">sugarcloud.ch/api/convert</text>
    <text x="104" y="40" text-anchor="middle" font-size="8.5" fill="#3c6f1a">jexter engine — same as desktop</text>
  </g>

  <!-- response -->
  <g transform="translate(514,99)">
    <text x="48" y="-9" text-anchor="middle" font-size="8" fill="#6a6f69">media type + filename</text>
    <line x1="0" y1="0" x2="92" y2="0" stroke="#9aa09a" stroke-width="1.8" marker-end="url(#ar)"/>
  </g>

  <!-- artifact -->
  <g transform="translate(616,74)">
    <rect width="52" height="50" rx="4" fill="#ffffff" stroke="#8ec63f" stroke-width="1.3"/>
    <rect x="8" y="9" width="36" height="11" rx="2" fill="#e3e5e1"/>
    <rect x="8" y="25" width="36" height="5" rx="2" fill="#d7dad4"/>
    <rect x="8" y="34" width="28" height="5" rx="2" fill="#d7dad4"/>
    <text x="26" y="62" text-anchor="middle" font-size="8.5" font-family="DejaVu Sans Mono" fill="#6a6f69">page-0.svg</text>
  </g>

  <!-- bottom strips -->
  <text x="390" y="166" text-anchor="middle" font-size="9.5" fill="#6a6f69"><tspan font-weight="600" fill="#2b567f">to = </tspan><tspan font-family="DejaVu Sans Mono" fill="#34403a">ocd · svg · pdf · epub · epub-reflow · html · md · doctags</tspan></text>
  <text x="390" y="184" text-anchor="middle" font-size="8.5" fill="#9aa09a"><tspan font-family="DejaVu Sans Mono">GET /api/health</tspan> → ok · public, no key</text>
</svg>
```.text

#let SVG_CANON = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 280" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="ca" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#9aa09a"/></marker>
    <marker id="cag" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#33699f"/></marker>
  </defs>
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">Searchable text without touching the page — <tspan fill="#2b567f">NFKC at read-out</tspan></text>

  <!-- source run -->
  <g transform="translate(40,70)">
    <text x="0" y="-12" font-size="10.5" fill="#6a6f69">the stored run</text>
    <rect width="190" height="86" rx="10" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <text x="95" y="46" text-anchor="middle" font-size="30" fill="#1c1e1b">O&#xFB03;ce</text>
    <text x="95" y="70" text-anchor="middle" font-size="10" font-family="DejaVu Sans Mono" fill="#9aa09a">glyphs: O · &#xFB03; · c · e</text>
  </g>

  <line x1="234" y1="113" x2="282" y2="113" stroke="#9aa09a" stroke-width="2" marker-end="url(#ca)"/>

  <!-- the ligature glyph zoom -->
  <g transform="translate(290,58)">
    <rect width="150" height="110" rx="10" fill="#f6f7f4" stroke="#e6e7e2" stroke-width="1.5"/>
    <text x="75" y="-10" text-anchor="middle" font-size="10.5" fill="#6a6f69">one ligature glyph</text>
    <text x="44" y="64" text-anchor="middle" font-size="40" fill="#1c1e1b">&#xFB03;</text>
    <text x="110" y="48" text-anchor="middle" font-size="11" font-family="DejaVu Sans Mono" fill="#3b5b7a">gid 271</text>
    <text x="110" y="64" text-anchor="middle" font-size="9.5" font-family="DejaVu Sans Mono" fill="#3b5b7a">U+FB03</text>
    <text x="75" y="96" text-anchor="middle" font-size="9.5" fill="#9aa09a">one glyph · kept in the OCD-EPUB</text>
  </g>

  <!-- split into two tracks -->
  <g transform="translate(470,113)">
    <line x1="0" y1="0" x2="48" y2="-42" stroke="#33699f" stroke-width="2" marker-end="url(#cag)"/>
    <line x1="0" y1="0" x2="48" y2="42" stroke="#33699f" stroke-width="2" marker-end="url(#cag)"/>
  </g>

  <!-- paint track (unchanged) -->
  <g transform="translate(524,42)">
    <rect width="244" height="68" rx="10" fill="#e7eff8" stroke="#8ec63f" stroke-width="1.5"/>
    <text x="14" y="20" font-size="10.5" font-weight="600" fill="#2b567f">what paints — SVG · PDF · OCD-EPUB</text>
    <text x="14" y="46" font-size="22" fill="#1c1e1b">O&#xFB03;ce</text>
    <text x="150" y="40" font-size="9.5" font-family="DejaVu Sans Mono" fill="#6a8a4a">by glyph id</text>
    <text x="150" y="54" font-size="9.5" font-family="DejaVu Sans Mono" fill="#6a8a4a">source kept</text>
  </g>

  <!-- read-out track (NFKC) -->
  <g transform="translate(524,128)">
    <rect width="244" height="68" rx="10" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <text x="14" y="20" font-size="10.5" font-weight="600" fill="#1c1e1b">what extracts — Markdown · HTML · search</text>
    <text x="14" y="46" font-size="18" font-family="DejaVu Sans" fill="#1c1e1b">"Office"</text>
    <text x="150" y="40" font-size="9.5" font-family="DejaVu Sans Mono" fill="#9aa09a">OCDIndex · NFKC</text>
    <text x="150" y="54" font-size="9.5" font-family="DejaVu Sans Mono" fill="#9aa09a">U+FB03 &#8594; f f i</text>
  </g>
  <text x="40" y="252" font-size="10.5" fill="#9aa09a">Folded only at read-out; the model keeps the source codepoint. Left intact: real letters &#339; &#230; &#223;.</text>
</svg>
```.text

#let SVG_CLEANING = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 300" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="cl" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#33699f"/></marker>
  </defs>
  <text x="390" y="22" text-anchor="middle" font-size="13.5" font-weight="600" fill="#1c1e1b">Flattening to the ink base — <tspan fill="#2b567f">same image, lighter model</tspan></text>

  <!-- PAGE A: as imported (a nested, noisy model) -->
  <g transform="translate(78,46)">
    <text x="135" y="-6" text-anchor="middle" font-size="11" fill="#6a6f69">as imported</text>
    <rect x="0" y="0" width="270" height="214" rx="6" fill="#fbfcf8" stroke="#dfe1db" stroke-width="1.5"/>

    <!-- paragraph wrapper (render-neutral) -->
    <rect x="12" y="12" width="246" height="118" rx="6" fill="#eef0ee" stroke="#d7dad2" stroke-width="1.2" stroke-dasharray="4 3"/>
    <text x="22" y="27" font-size="9.5" font-family="DejaVu Sans Mono" fill="#8b9182">¶ OCDParagraph  (identity wrapper)</text>
    <g font-size="11" font-family="DejaVu Sans Mono">
      <text x="30" y="46" fill="#1c1e1b">run  "Lorem"</text>
      <text x="30" y="64" fill="#b08a8a">␣    blank glyph</text>
      <text x="30" y="82" fill="#1c1e1b">run  "ipsum"</text>
      <text x="30" y="100" fill="#b08a8a">↵    OCDBreak</text>
      <text x="30" y="118" fill="#1c1e1b">run  "dolor"</text>
    </g>

    <!-- render-bearing group (kept) -->
    <rect x="12" y="140" width="246" height="60" rx="6" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.2"/>
    <text x="22" y="156" font-size="9.5" font-family="DejaVu Sans Mono" fill="#3b5b7a">▦ OCDGroup  (Form XObject · render-bearing)</text>
    <text x="30" y="178" font-size="11" font-family="DejaVu Sans Mono" fill="#27425c">path · path</text>
    <text x="135" y="196" text-anchor="middle" font-size="9" fill="#7c9ab8">kept &amp; recursed into</text>
  </g>

  <!-- arrow -->
  <g transform="translate(360,150)">
    <line x1="0" y1="0" x2="48" y2="0" stroke="#33699f" stroke-width="2" marker-end="url(#cl)"/>
    <text x="24" y="-10" text-anchor="middle" font-size="10" font-weight="600" fill="#2b567f">clean</text>
    <text x="24" y="20" text-anchor="middle" font-size="8.5" fill="#6a8a4a">Cleaner</text>
  </g>

  <!-- PAGE B: cleaned (flat pure ink) -->
  <g transform="translate(434,46)">
    <text x="135" y="-6" text-anchor="middle" font-size="11" fill="#6a6f69">after cleaning</text>
    <rect x="0" y="0" width="270" height="214" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <g font-size="11" font-family="DejaVu Sans Mono">
      <text x="22" y="34" fill="#1c1e1b">run  "Lorem"</text>
      <text x="22" y="56" fill="#1c1e1b">run  "ipsum"</text>
      <text x="22" y="78" fill="#1c1e1b">run  "dolor"</text>
    </g>
    <rect x="12" y="96" width="246" height="58" rx="6" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.2"/>
    <text x="22" y="112" font-size="9.5" font-family="DejaVu Sans Mono" fill="#3b5b7a">▦ OCDGroup  (kept)</text>
    <text x="30" y="134" font-size="11" font-family="DejaVu Sans Mono" fill="#27425c">path · path</text>
    <text x="135" y="186" text-anchor="middle" font-size="9" fill="#a7aaa3">wrappers spliced · breaks dropped · blanks stripped</text>
    <text x="135" y="200" text-anchor="middle" font-size="9" fill="#a7aaa3">&#8594; pure-ink runs</text>
  </g>

  <!-- identical pixels badge -->
  <g transform="translate(322,270)">
    <rect width="136" height="24" rx="12" fill="#e7eff8" stroke="#8ec63f"/>
    <text x="68" y="16" text-anchor="middle" font-size="11" font-weight="600" fill="#2b567f">= identical pixels</text>
  </g>
</svg>
```.text

#let SVG_FIDELITY = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 244" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="fd" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#9aa09a"/></marker>
    <marker id="fg" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#33699f"/></marker>
  </defs>
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">The round-trip bar is <tspan fill="#2b567f">measured, not assumed</tspan></text>

  <!-- source PDF -->
  <g transform="translate(30,90)">
    <rect width="78" height="96" rx="8" fill="#ffffff" stroke="#cfd2cc" stroke-width="1.5"/>
    <path d="M52 0 L78 26 L52 26 Z" fill="#eef0ec"/>
    <line x1="12" y1="44" x2="66" y2="44" stroke="#d7dad4" stroke-width="3.5" stroke-linecap="round"/>
    <line x1="12" y1="56" x2="66" y2="56" stroke="#e2e4df" stroke-width="3.5" stroke-linecap="round"/>
    <line x1="12" y1="68" x2="52" y2="68" stroke="#e2e4df" stroke-width="3.5" stroke-linecap="round"/>
    <text x="39" y="114" text-anchor="middle" font-size="11" font-weight="600" fill="#1c1e1b">source PDF</text>
  </g>

  <!-- fork: source splits into the two pipelines (orthogonal) -->
  <g stroke="#9aa09a" stroke-width="1.8" fill="none">
    <line x1="108" y1="138" x2="130" y2="138"/>
    <line x1="130" y1="96"  x2="130" y2="180"/>
    <line x1="130" y1="96"  x2="144" y2="96"  marker-end="url(#fd)"/>
    <line x1="130" y1="180" x2="144" y2="180" marker-end="url(#fd)"/>
  </g>

  <!-- top path: PDFBox (one wide stage) -->
  <g transform="translate(150,74)">
    <rect width="234" height="44" rx="9" fill="#e7edf4" stroke="#cdd9e6" stroke-width="1.4"/>
    <text x="117" y="19" text-anchor="middle" font-size="11.5" font-weight="600" fill="#27425c">PDFBox</text>
    <text x="117" y="34" text-anchor="middle" font-size="9.5" fill="#4a627d">the reference rasterizer</text>
  </g>
  <line x1="386" y1="96" x2="396" y2="96" stroke="#9aa09a" stroke-width="1.8" marker-end="url(#fd)"/>

  <!-- bottom path: jexter -> OCDRenderer (two stages, same total width) -->
  <g transform="translate(150,158)">
    <rect width="96" height="44" rx="9" fill="#e7eff8" stroke="#8ec63f" stroke-width="1.4"/>
    <text x="48" y="19" text-anchor="middle" font-size="11.5" font-weight="600" fill="#2b567f">jexter</text>
    <text x="48" y="34" text-anchor="middle" font-size="9.5" fill="#6a8a4a">parse → OCD</text>
  </g>
  <line x1="248" y1="180" x2="262" y2="180" stroke="#9aa09a" stroke-width="1.8" marker-end="url(#fd)"/>
  <g transform="translate(264,158)">
    <rect width="120" height="44" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.4"/>
    <text x="60" y="19" text-anchor="middle" font-size="11" font-weight="600" fill="#1c1e1b">OCDRenderer</text>
    <text x="60" y="34" text-anchor="middle" font-size="9.5" fill="#6a6f69">model → raster</text>
  </g>
  <line x1="386" y1="180" x2="396" y2="180" stroke="#9aa09a" stroke-width="1.8" marker-end="url(#fd)"/>

  <!-- the two rasters: identical interiors, frame colour names the producer -->
  <!-- reference image (PDFBox / indigo frame) -->
  <g transform="translate(400,70)">
    <rect width="58" height="52" rx="4" fill="#ffffff" stroke="#3b5b7a" stroke-width="1.3"/>
    <rect x="8" y="9"  width="42" height="13" rx="2" fill="#e3e5e1"/>
    <rect x="8" y="28" width="42" height="5" rx="2" fill="#d7dad4"/>
    <rect x="8" y="38" width="34" height="5" rx="2" fill="#d7dad4"/>
    <text x="29" y="64" text-anchor="middle" font-size="9" fill="#6a6f69">reference image</text>
  </g>
  <!-- jexter image (jexter / green frame) — same pixels -->
  <g transform="translate(400,154)">
    <rect width="58" height="52" rx="4" fill="#ffffff" stroke="#8ec63f" stroke-width="1.3"/>
    <rect x="8" y="9"  width="42" height="13" rx="2" fill="#e3e5e1"/>
    <rect x="8" y="28" width="42" height="5" rx="2" fill="#d7dad4"/>
    <rect x="8" y="38" width="34" height="5" rx="2" fill="#d7dad4"/>
    <text x="29" y="64" text-anchor="middle" font-size="9" fill="#6a6f69">jexter image</text>
  </g>

  <!-- merge: both rasters meet at the comparator (orthogonal, mirrors the fork) -->
  <g stroke="#9aa09a" stroke-width="1.8" fill="none">
    <line x1="458" y1="96"  x2="486" y2="96"/>
    <line x1="458" y1="180" x2="486" y2="180"/>
    <line x1="486" y1="96"  x2="486" y2="180"/>
    <line x1="486" y1="138" x2="500" y2="138" marker-end="url(#fd)"/>
  </g>
  <circle cx="518" cy="138" r="16" fill="#1c1e1b"/>
  <text x="518" y="144" text-anchor="middle" font-size="15" fill="#ffffff">=</text>
  <line x1="536" y1="138" x2="556" y2="138" stroke="#33699f" stroke-width="2" marker-end="url(#fg)"/>

  <!-- result -->
  <g transform="translate(560,98)">
    <rect width="190" height="80" rx="12" fill="#e7eff8" stroke="#8ec63f" stroke-width="1.5"/>
    <text x="95" y="30" text-anchor="middle" font-size="13" font-weight="600" fill="#2b567f">pixel-identical</text>
    <text x="95" y="52" text-anchor="middle" font-size="12" font-family="DejaVu Sans Mono" fill="#3c6f1a">&#916; &#8776; 0.000004</text>
    <text x="95" y="68" text-anchor="middle" font-size="9" fill="#6a8a4a">mean channel difference</text>
  </g>
</svg>
```.text

#let SVG_GRAPHICIZER = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 300" font-family="Poppins, DejaVu Sans, sans-serif">
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">Vector clustering — a <tspan fill="#2b567f">contiguous run</tspan> in paint order becomes one graphic</text>

  <!-- paint-order axis -->
  <line x1="40" y1="120" x2="740" y2="120" stroke="#cdd3c8" stroke-width="2"/>

  <!-- path tokens along axis -->
  <g font-size="10">
    <!-- 1: full-page background (furniture, rejected) -->
    <g transform="translate(60,86)"><rect width="64" height="64" rx="6" fill="#f6f7f4" stroke="#e0a0a0" stroke-width="1.6" stroke-dasharray="4 3"/>
      <rect x="6" y="6" width="52" height="52" fill="#f0f1ed"/>
      <text x="32" y="80" text-anchor="middle" fill="#c1452f">background</text></g>
    <!-- 2..5: a real drawing (clustered) -->
    <g transform="translate(150,86)">
      <rect width="300" height="64" rx="10" fill="#e7eff8" stroke="#33699f" stroke-width="2"/>
      <!-- mini chart paths -->
      <g transform="translate(14,10)">
        <polyline points="0,40 18,24 36,30 54,10" fill="none" stroke="#33699f" stroke-width="2.5"/>
        <circle cx="0" cy="40" r="3" fill="#33699f"/><circle cx="18" cy="24" r="3" fill="#33699f"/><circle cx="36" cy="30" r="3" fill="#33699f"/><circle cx="54" cy="10" r="3" fill="#33699f"/>
        <rect x="84" y="20" width="14" height="24" fill="#8ec63f"/><rect x="102" y="8" width="14" height="36" fill="#8ec63f"/><rect x="120" y="28" width="14" height="16" fill="#8ec63f"/>
        <circle cx="200" cy="22" r="20" fill="none" stroke="#33699f" stroke-width="2.5"/>
        <path d="M200 22 L200 2 A20 20 0 0 1 217 32 Z" fill="#8ec63f"/>
      </g>
      <text x="150" y="86" text-anchor="middle" fill="#2b567f">→ OCDGraphic (paths 2–5, contiguous)</text>
    </g>
    <!-- 6: page frame (furniture) -->
    <g transform="translate(476,86)"><rect width="64" height="64" rx="6" fill="#ffffff" stroke="#e0a0a0" stroke-width="1.6" stroke-dasharray="4 3"/>
      <rect x="8" y="8" width="48" height="48" fill="none" stroke="#cfd2cc" stroke-width="2"/>
      <text x="32" y="80" text-anchor="middle" fill="#c1452f">frame</text></g>
    <!-- 7: lone dot (degenerate) -->
    <g transform="translate(566,86)"><rect width="64" height="64" rx="6" fill="#ffffff" stroke="#e0a0a0" stroke-width="1.6" stroke-dasharray="4 3"/>
      <circle cx="32" cy="32" r="2.4" fill="#9aa09a"/>
      <text x="32" y="80" text-anchor="middle" fill="#c1452f">lone path</text></g>
    <!-- 8: table grid (uniform) -->
    <g transform="translate(656,86)"><rect width="64" height="64" rx="6" fill="#ffffff" stroke="#e0a0a0" stroke-width="1.6" stroke-dasharray="4 3"/>
      <g stroke="#cfd2cc" stroke-width="1.4"><line x1="10" y1="22" x2="54" y2="22"/><line x1="10" y1="34" x2="54" y2="34"/><line x1="10" y1="46" x2="54" y2="46"/><line x1="26" y1="14" x2="26" y2="54"/><line x1="42" y1="14" x2="42" y2="54"/></g>
      <text x="32" y="80" text-anchor="middle" fill="#c1452f">grid</text></g>
  </g>

  <!-- guard notes -->
  <g transform="translate(40,196)" font-size="11.5" fill="#52564f">
    <text x="0" y="0" font-size="12.5" font-weight="600" fill="#1c1e1b">Guards (after pymupdf4llm)</text>
    <text x="0" y="22">· candidate filter — a path spanning ≈ a whole page dimension is furniture; degenerate dots dropped</text>
    <text x="0" y="42">· significance — reject if all paths share one width/height (grid / rules / frame); ≥ 1 path must fill the interior</text>
    <text x="0" y="62">· contiguity — only a contiguous paint-order run is wrapped, so painting is never reordered (MIN_PATHS = 2)</text>
  </g>
</svg>
```.text

#let SVG_HEADERFOOTER = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 820 380" font-family="Poppins, DejaVu Sans, sans-serif">
<text x="28" y="24" font-size="13.5" font-weight="600" fill="#1c1e1b">Running heads &amp; feet by <tspan fill="#33699f">recto/verso stack stability</tspan></text>
<rect x="92" y="64" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="104" y="74" width="80" height="15" rx="3" fill="#e7edf4"/><rect x="104" y="74" width="80" height="15" rx="3" fill="none" stroke="#3b5b7a" stroke-width="1.2"/><text x="144.0" y="85" text-anchor="middle" font-size="9.5" fill="#27425c">title</text><line x1="104" y1="102" x2="192" y2="102" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="111.5" x2="184" y2="111.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="121.0" x2="196" y2="121.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="130.5" x2="174" y2="130.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="140.0" x2="190" y2="140.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="149.5" x2="164" y2="149.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="159.0" x2="188" y2="159.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="128.0" y="162" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="128.0" y="162" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="144.0" y="198" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p1</text><text x="144.0" y="210" text-anchor="middle" font-size="9" fill="#9aa09a">recto</text>
<rect x="322" y="64" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="334" y="74" width="80" height="15" rx="3" fill="#e7eff8"/><rect x="334" y="74" width="80" height="15" rx="3" fill="none" stroke="#8ec63f" stroke-width="1.2"/><text x="374.0" y="85" text-anchor="middle" font-size="9.5" fill="#2b567f">head</text><line x1="334" y1="102" x2="422" y2="102" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="111.5" x2="414" y2="111.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="121.0" x2="426" y2="121.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="130.5" x2="404" y2="130.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="140.0" x2="420" y2="140.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="149.5" x2="394" y2="149.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="159.0" x2="418" y2="159.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="358.0" y="162" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="358.0" y="162" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="374.0" y="198" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p3</text><text x="374.0" y="210" text-anchor="middle" font-size="9" fill="#9aa09a">recto</text>
<rect x="552" y="64" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="564" y="74" width="80" height="15" rx="3" fill="#e7eff8"/><rect x="564" y="74" width="80" height="15" rx="3" fill="none" stroke="#8ec63f" stroke-width="1.2"/><text x="604.0" y="85" text-anchor="middle" font-size="9.5" fill="#2b567f">head</text><line x1="564" y1="102" x2="652" y2="102" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="111.5" x2="644" y2="111.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="121.0" x2="656" y2="121.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="130.5" x2="634" y2="130.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="140.0" x2="650" y2="140.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="149.5" x2="624" y2="149.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="159.0" x2="648" y2="159.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="588.0" y="162" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="588.0" y="162" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="604.0" y="198" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p5</text><text x="604.0" y="210" text-anchor="middle" font-size="9" fill="#9aa09a">recto</text>
<rect x="92" y="222" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="104" y="232" width="80" height="15" rx="3" fill="#e7eff8"/><rect x="104" y="232" width="80" height="15" rx="3" fill="none" stroke="#8ec63f" stroke-width="1.2"/><text x="144.0" y="243" text-anchor="middle" font-size="9.5" fill="#2b567f">head</text><line x1="104" y1="260" x2="192" y2="260" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="269.5" x2="184" y2="269.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="279.0" x2="196" y2="279.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="288.5" x2="174" y2="288.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="298.0" x2="190" y2="298.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="307.5" x2="164" y2="307.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="104" y1="317.0" x2="188" y2="317.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="128.0" y="320" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="128.0" y="320" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="144.0" y="356" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p2</text><text x="144.0" y="368" text-anchor="middle" font-size="9" fill="#9aa09a">verso</text>
<rect x="322" y="222" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="334" y="232" width="80" height="15" rx="3" fill="#e7eff8"/><rect x="334" y="232" width="80" height="15" rx="3" fill="none" stroke="#8ec63f" stroke-width="1.2"/><text x="374.0" y="243" text-anchor="middle" font-size="9.5" fill="#2b567f">head</text><line x1="334" y1="260" x2="422" y2="260" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="269.5" x2="414" y2="269.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="279.0" x2="426" y2="279.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="288.5" x2="404" y2="288.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="298.0" x2="420" y2="298.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="307.5" x2="394" y2="307.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="334" y1="317.0" x2="418" y2="317.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="358.0" y="320" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="358.0" y="320" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="374.0" y="356" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p4</text><text x="374.0" y="368" text-anchor="middle" font-size="9" fill="#9aa09a">verso</text>
<rect x="552" y="222" width="104" height="118" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><rect x="564" y="232" width="80" height="15" rx="3" fill="#e7eff8"/><rect x="564" y="232" width="80" height="15" rx="3" fill="none" stroke="#8ec63f" stroke-width="1.2"/><text x="604.0" y="243" text-anchor="middle" font-size="9.5" fill="#2b567f">head</text><line x1="564" y1="260" x2="652" y2="260" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="269.5" x2="644" y2="269.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="279.0" x2="656" y2="279.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="288.5" x2="634" y2="288.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="298.0" x2="650" y2="298.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="307.5" x2="624" y2="307.5" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><line x1="564" y1="317.0" x2="648" y2="317.0" stroke="#e3e6df" stroke-width="3" stroke-linecap="round"/><rect x="588.0" y="320" width="32" height="11" rx="3" fill="#e7eff8"/><rect x="588.0" y="320" width="32" height="11" rx="3" fill="none" stroke="#8ec63f" stroke-width="1"/><text x="604.0" y="356" text-anchor="middle" font-size="10.5" font-weight="600" fill="#1c1e1b">p6</text><text x="604.0" y="368" text-anchor="middle" font-size="9" fill="#9aa09a">verso</text>
<text x="26" y="123.0" font-size="10" font-weight="600" fill="#3b5b7a" transform="rotate(-90 26 123.0)" text-anchor="middle">recto stack</text>
<text x="26" y="281.0" font-size="10" font-weight="600" fill="#3b5b7a" transform="rotate(-90 26 281.0)" text-anchor="middle">verso stack</text>
<path d="M 144.0 74 C 144.0 52, 374.0 52, 374.0 74" fill="none" stroke="#b8801f" stroke-width="1.6" stroke-dasharray="5 3"/><circle cx="259.0" cy="56" r="10" fill="#f7eede" stroke="#b8801f" stroke-width="1.3"/><path d="M 255.6 52.6 l 6.8 6.8 M 262.4 52.6 l -6.8 6.8" fill="none" stroke="#b8801f" stroke-width="1.8" stroke-linecap="round"/><text x="259.0" y="42" text-anchor="middle" font-size="8.5" fill="#b8801f">p1 vs p3</text>
<path d="M 374.0 74 C 374.0 52, 604.0 52, 604.0 74" fill="none" stroke="#33699f" stroke-width="1.6" stroke-dasharray="5 3"/><circle cx="489.0" cy="56" r="10" fill="#e7eff8" stroke="#33699f" stroke-width="1.3"/><path d="M 484.8 56 l 2.6 3 l 5.2 -6.4" fill="none" stroke="#33699f" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><text x="489.0" y="42" text-anchor="middle" font-size="8.5" fill="#33699f">p3 vs p5</text>
<path d="M 144.0 232 C 144.0 210, 374.0 210, 374.0 232" fill="none" stroke="#33699f" stroke-width="1.6" stroke-dasharray="5 3"/><circle cx="259.0" cy="214" r="10" fill="#e7eff8" stroke="#33699f" stroke-width="1.3"/><path d="M 254.8 214 l 2.6 3 l 5.2 -6.4" fill="none" stroke="#33699f" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><text x="259.0" y="200" text-anchor="middle" font-size="8.5" fill="#33699f">p2 vs p4</text>
<path d="M 374.0 232 C 374.0 210, 604.0 210, 604.0 232" fill="none" stroke="#33699f" stroke-width="1.6" stroke-dasharray="5 3"/><circle cx="489.0" cy="214" r="10" fill="#e7eff8" stroke="#33699f" stroke-width="1.3"/><path d="M 484.8 214 l 2.6 3 l 5.2 -6.4" fill="none" stroke="#33699f" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/><text x="489.0" y="200" text-anchor="middle" font-size="8.5" fill="#33699f">p4 vs p6</text>
<rect x="89" y="61" width="110" height="124" rx="8" fill="none" stroke="#b8801f" stroke-width="1.4" stroke-dasharray="4 3"/>
<text x="144.0" y="224" text-anchor="middle" font-size="9" fill="#b8801f">unstable: kept as body</text>
<rect x="688" y="70" width="118" height="240" rx="8" fill="#fafbf8" stroke="#dfe1db"/>
<text x="698" y="92" font-size="9.5" fill="#2b567f" font-weight="600">same-side only</text>
<text x="698" y="110" font-size="9.5" fill="#1c1e1b">compare each edge</text>
<text x="698" y="128" font-size="9.5" fill="#1c1e1b">line to p-2 / p+2</text>
<text x="698" y="146" font-size="9.5" fill="#9aa09a">(whole raw string,</text>
<text x="698" y="164" font-size="9.5" fill="#9aa09a">digits kept).</text>
<text x="698" y="182" font-size="9.5" fill="#1c1e1b"></text>
<text x="698" y="200" font-size="9.5" fill="#33699f" font-weight="600">Levenshtein >= SIM_MIN</text>
<text x="698" y="218" font-size="9.5" fill="#33699f" font-weight="600">= furniture.</text>
<text x="698" y="236" font-size="9.5" fill="#1c1e1b"></text>
<text x="698" y="254" font-size="9.5" fill="#9aa09a">p +/-1 fallback when</text>
<text x="698" y="272" font-size="9.5" fill="#9aa09a">the stack is thin.</text>
</svg>
```.text

#let SVG_IMPORT = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 320" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="im" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#9aa09a"/></marker>
  </defs>
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">Reading the page into <tspan fill="#2b567f">primitive nodes</tspan> — faithful, but still flat</text>

  <!-- content stream -->
  <g transform="translate(28,44)">
    <rect width="250" height="232" rx="8" fill="#1c1e1b"/>
    <text x="16" y="26" font-size="11" font-family="DejaVu Sans Mono" fill="#7d8a74">% PDF content stream</text>
    <g font-size="11" font-family="DejaVu Sans Mono">
      <text x="16" y="50" fill="#cfe6b0">BT /F1 11 Tf</text>
      <text x="16" y="68" fill="#cfe6b0">(Office) Tj ET</text>
      <text x="16" y="92" fill="#a9c0d8">10 20 80 40 re f</text>
      <text x="16" y="110" fill="#a9c0d8">2 w 0 0 m 90 60 l S</text>
      <text x="16" y="134" fill="#e6c98a">/Im0 Do</text>
      <text x="16" y="158" fill="#c9a0d8">W n</text>
    </g>
    <text x="16" y="194" font-size="10.5" font-family="DejaVu Sans Mono" fill="#7d8a74">% operators in paint order,</text>
    <text x="16" y="210" font-size="10.5" font-family="DejaVu Sans Mono" fill="#7d8a74">% with no notion of meaning</text>
  </g>

  <g transform="translate(296,150)">
    <line x1="0" y1="0" x2="34" y2="0" stroke="#9aa09a" stroke-width="2" marker-end="url(#im)"/>
  </g>

  <!-- primitive nodes -->
  <g transform="translate(346,44)" font-size="11.5">
    <text x="0" y="2" font-size="11" fill="#6a6f69">primitive OCD nodes</text>
    <!-- text -->
    <g transform="translate(0,12)"><rect width="406" height="46" rx="9" fill="#ffffff" stroke="#dfe1db"/>
      <rect x="0" y="0" width="6" height="46" rx="3" fill="#33699f"/>
      <text x="18" y="20" font-weight="600" fill="#1c1e1b">OCDText</text>
      <text x="18" y="37" font-size="10" fill="#6a6f69">glyph run · face · size · colour · kerning · z</text>
      <text x="396" y="29" text-anchor="end" font-size="14" fill="#1c1e1b">Office</text></g>
    <!-- path -->
    <g transform="translate(0,68)"><rect width="406" height="46" rx="9" fill="#ffffff" stroke="#dfe1db"/>
      <rect x="0" y="0" width="6" height="46" rx="3" fill="#3b5b7a"/>
      <text x="18" y="20" font-weight="600" fill="#1c1e1b">OCDPath</text>
      <text x="18" y="37" font-size="10" fill="#6a6f69">fills · strokes · béziers · dashes · blend</text>
      <g transform="translate(350,11)" stroke="#3b5b7a" fill="none" stroke-width="1.8" stroke-linecap="round">
        <path d="M4 21 C 13 4, 31 4, 40 19"/>
        <line x1="4" y1="21" x2="13" y2="4"/>
        <line x1="40" y1="19" x2="31" y2="4"/>
        <circle cx="13" cy="4" r="2.2" fill="#ffffff"/>
        <circle cx="31" cy="4" r="2.2" fill="#ffffff"/>
        <rect x="1.5" y="18.5" width="5" height="5" rx="0.6" fill="#3b5b7a" stroke="none"/>
        <rect x="37.5" y="16.5" width="5" height="5" rx="0.6" fill="#3b5b7a" stroke="none"/>
      </g></g>
    <!-- image -->
    <g transform="translate(0,124)"><rect width="406" height="46" rx="9" fill="#ffffff" stroke="#dfe1db"/>
      <rect x="0" y="0" width="6" height="46" rx="3" fill="#b8801f"/>
      <text x="18" y="20" font-weight="600" fill="#1c1e1b">OCDImage</text>
      <text x="18" y="37" font-size="10" fill="#6a6f69">inline &amp; XObject · JBIG2 · alpha</text>
      <g transform="translate(358,13)"><rect width="34" height="22" rx="2" fill="#f6ecd7" stroke="#e6d3a8"/>
        <circle cx="9" cy="7" r="2.5" fill="#d8b86a"/>
        <path d="M3 19 l7 -8 5 5 6 -7 7 10 z" fill="#d8b86a"/></g></g>
    <!-- clip -->
    <g transform="translate(0,180)"><rect width="406" height="46" rx="9" fill="#ffffff" stroke="#dfe1db"/>
      <rect x="0" y="0" width="6" height="46" rx="3" fill="#9aa09a"/>
      <text x="18" y="20" font-weight="600" fill="#1c1e1b">Clip region</text>
      <text x="18" y="37" font-size="10" fill="#6a6f69">deferred, attached to the nodes it bounds</text>
      <rect x="360" y="14" width="30" height="20" rx="2" fill="none" stroke="#9aa09a" stroke-dasharray="3 2"/></g>
  </g>

  <text x="28" y="296" font-size="10.5" fill="#6a6f69">Every mark is placed and paints correctly — but there are no paragraphs, headings, columns or reading order yet.</text>
</svg>
```.text

#let SVG_LLM = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 310" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="ll" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#b8801f"/></marker>
  </defs>
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">Optional <tspan fill="#8a5e12">LLM refinement</tspan> — windowed, grounded, off by default</text>

  <!-- page strip with sliding window -->
  <g transform="translate(40,44)">
    <text x="0" y="-2" font-size="10.5" fill="#6a6f69">pages, processed in a sliding window</text>
    <g>
      <rect x="0"   y="6" width="34" height="46" rx="4" fill="#f6f7f4" stroke="#e6e7e2"/>
      <rect x="44"  y="6" width="34" height="46" rx="4" fill="#f1ede2" stroke="#e6d3a8"/>
      <rect x="88"  y="6" width="34" height="46" rx="4" fill="#f6ecd7" stroke="#b8801f" stroke-width="2"/>
      <rect x="132" y="6" width="34" height="46" rx="4" fill="#f1ede2" stroke="#e6d3a8"/>
      <rect x="176" y="6" width="34" height="46" rx="4" fill="#f6f7f4" stroke="#e6e7e2"/>
      <rect x="220" y="6" width="34" height="46" rx="4" fill="#f6f7f4" stroke="#e6e7e2"/>
    </g>
    <g font-size="9" fill="#9aa09a" text-anchor="middle">
      <text x="17" y="64">p3</text><text x="61" y="64">p4</text><text x="105" y="64">p5</text><text x="149" y="64">p6</text><text x="193" y="64">p7</text><text x="237" y="64">p8</text>
    </g>
  </g>

  <!-- prompt assembly -->
  <g transform="translate(320,44)">
    <text x="0" y="-2" font-size="10.5" fill="#6a6f69">what the model is shown, in order</text>
    <g transform="translate(0,8)">
      <rect width="436" height="26" rx="6" fill="#e7edf4" stroke="#cdd9e6"/>
      <text x="12" y="17" font-size="10.5" font-weight="600" fill="#27425c">documentProfile</text>
      <text x="426" y="17" text-anchor="end" font-size="9" fill="#4a627d">static · whole-doc summary</text>
    </g>
    <g transform="translate(0,40)">
      <rect width="436" height="22" rx="6" fill="#f6f7f4" stroke="#e6e7e2"/>
      <text x="12" y="15" font-size="10" fill="#7c817a">previous page — condensed</text>
    </g>
    <g transform="translate(0,68)">
      <rect width="436" height="34" rx="6" fill="#f6ecd7" stroke="#b8801f" stroke-width="1.6"/>
      <text x="12" y="15" font-size="11" font-weight="600" fill="#8a5e12">CURRENT page — full detail</text>
      <text x="12" y="29" font-size="9" fill="#9a7321">placed last, where attention is highest</text>
    </g>
    <g transform="translate(0,108)">
      <rect width="436" height="22" rx="6" fill="#f6f7f4" stroke="#e6e7e2"/>
      <text x="12" y="15" font-size="10" fill="#7c817a">next page — condensed</text>
    </g>
  </g>

  <!-- precomputed signals note -->
  <g transform="translate(40,150)">
    <rect width="240" height="120" rx="10" fill="#fbfcf8" stroke="#e6e7e2" stroke-width="1.5"/>
    <text x="16" y="26" font-size="11.5" font-weight="600" fill="#1c1e1b">No page images</text>
    <text x="16" y="48" font-size="10" fill="#52564f">The model sees pre-computed</text>
    <text x="16" y="63" font-size="10" fill="#52564f">perceptual signals, not pixels:</text>
    <text x="16" y="84" font-size="10" fill="#3b5b7a">· spacing · indentation</text>
    <text x="16" y="99" font-size="10" fill="#3b5b7a">· emphasis · colour · background</text>
    <text x="16" y="114" font-size="9" font-style="italic" fill="#9aa09a">grounded in the Dolores taxonomy</text>
  </g>

  <!-- assembled prompt feeds the model (placed below the stack) -->
  <line x1="432" y1="178" x2="432" y2="196" stroke="#b8801f" stroke-width="2" marker-end="url(#ll)"/>
  <g transform="translate(372,200)">
    <rect width="120" height="48" rx="10" fill="#1c1e1b"/>
    <text x="60" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#e6c98a">LLM</text>
    <text x="60" y="38" text-anchor="middle" font-size="8.5" fill="#a9a18a">any wired provider</text>
  </g>
  <g transform="translate(500,224)">
    <line x1="0" y1="0" x2="40" y2="0" stroke="#b8801f" stroke-width="2" marker-end="url(#ll)"/>
  </g>
  <g transform="translate(548,200)">
    <rect width="208" height="48" rx="10" fill="#e7eff8" stroke="#8ec63f"/>
    <text x="14" y="21" font-size="11" font-weight="600" fill="#2b567f">refined structure</text>
    <text x="14" y="38" font-size="9" fill="#6a8a4a">grounded — a failure is a no-op</text>
  </g>

  <text x="40" y="292" font-size="10.5" fill="#6a6f69">Windowing keeps each call short, so the current page never gets &#8220;lost in the middle&#8221; of a long context.</text>
</svg>
```.text

#let SVG_OCD_HUB = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 360" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="d2" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#b7bcb6"/></marker>
  </defs>

  <!-- center -->
  <g transform="translate(300,140)">
    <rect width="180" height="80" rx="16" fill="#1c1e1b"/>
    <text x="90" y="38" text-anchor="middle" font-size="22" font-weight="600" fill="#ffffff">OCD model</text>
    <text x="90" y="60" text-anchor="middle" font-size="11" fill="#a9b0a4">format-neutral · in-memory</text>
  </g>

  <!-- presentation (green) -->
  <g transform="translate(40,20)">
    <rect width="236" height="118" rx="12" fill="#e7eff8" stroke="#33699f" stroke-width="1.6"/>
    <text x="16" y="28" font-size="14.5" font-weight="600" fill="#2b567f">Presentation</text>
    <text x="16" y="50" font-size="11" fill="#3c5a25">OCDText · OCDPath · OCDImage</text>
    <text x="16" y="68" font-size="11" fill="#3c5a25">OCDGroup · OCDMedia · OCDBreak</text>
    <text x="16" y="86" font-size="11" fill="#3c5a25">OCDFont/Glyph · Gradient · Clip</text>
    <text x="16" y="104" font-size="11" fill="#3c5a25">OCDLayer · OCDPage · OCDDocument</text>
  </g>
  <line x1="276" y1="120" x2="298" y2="150" stroke="#b7bcb6" stroke-width="1.5" marker-end="url(#d2)"/>

  <!-- logical (indigo) -->
  <g transform="translate(504,20)">
    <rect width="236" height="118" rx="12" fill="#e7edf4" stroke="#3b5b7a" stroke-width="1.6"/>
    <text x="16" y="28" font-size="14.5" font-weight="600" fill="#27425c">Logical</text>
    <text x="16" y="50" font-size="11" fill="#33506e">OCDStruct (tree node)</text>
    <text x="16" y="68" font-size="11" fill="#33506e">OCDStructure (named, provenance)</text>
    <text x="16" y="86" font-size="11" fill="#33506e">OCDOutline · OCDMeta</text>
    <text x="16" y="104" font-size="11" fill="#33506e">reading order · headings · lists</text>
  </g>
  <line x1="504" y1="120" x2="482" y2="150" stroke="#b7bcb6" stroke-width="1.5" marker-end="url(#d2)"/>

  <!-- annotations (amber) -->
  <g transform="translate(272,250)">
    <rect width="236" height="86" rx="12" fill="#f6ecd7" stroke="#b8801f" stroke-width="1.6"/>
    <text x="16" y="28" font-size="14.5" font-weight="600" fill="#8a5e12">Annotations</text>
    <text x="16" y="50" font-size="11" fill="#7a560f">OCDLink · OCDAnnotation</text>
    <text x="16" y="68" font-size="11" fill="#7a560f">OCDFormField (AcroForm widgets)</text>
  </g>
  <line x1="390" y1="248" x2="390" y2="222" stroke="#b7bcb6" stroke-width="1.5" marker-end="url(#d2)"/>

  <!-- invariants strip -->
  <g transform="translate(40,162)" font-size="11" fill="#6a6f69">
    <text x="0" y="0" font-size="12" font-weight="600" fill="#1c1e1b">Invariants</text>
    <text x="0" y="20">· user space is Y-up</text>
    <text x="0" y="38">· geometry local + per-node transform</text>
    <text x="0" y="56">· colours: sRGB int argb, alpha folded</text>
  </g>
  <g transform="translate(575,162)" font-size="11" fill="#6a6f69">
    <text x="0" y="20">· bounds derived, never stored</text>
    <text x="0" y="38">· ids 1-based, 0 reserved</text>
    <text x="0" y="56">· logical refs content, alters no pixel</text>
  </g>
</svg>
```.text

#let SVG_PIPELINE = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 330" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="ah" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto">
      <path d="M0,0 L6.5,3 L0,6 Z" fill="#9aa09a"/>
    </marker>
    <marker id="ahg" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto">
      <path d="M0,0 L6.5,3 L0,6 Z" fill="#33699f"/>
    </marker>
  </defs>

  <!-- source PDF -->
  <g transform="translate(20,118)">
    <rect x="0" y="0" width="92" height="112" rx="8" fill="#ffffff" stroke="#cfd2cc" stroke-width="1.5"/>
    <path d="M62 0 L92 30 L62 30 Z" fill="#eef0ec"/>
    <line x1="14" y1="46" x2="78" y2="46" stroke="#d7dad4" stroke-width="4" stroke-linecap="round"/>
    <line x1="14" y1="58" x2="78" y2="58" stroke="#e2e4df" stroke-width="4" stroke-linecap="round"/>
    <line x1="14" y1="70" x2="60" y2="70" stroke="#e2e4df" stroke-width="4" stroke-linecap="round"/>
    <text x="46" y="98" text-anchor="middle" font-size="15" font-weight="600" fill="#1c1e1b">PDF</text>
  </g>
  <line x1="116" y1="174" x2="176" y2="174" stroke="#9aa09a" stroke-width="2" marker-end="url(#ah)"/>

  <!-- engine -->
  <g transform="translate(182,128)">
    <rect x="0" y="0" width="170" height="92" rx="16" fill="#e7eff8" stroke="#33699f" stroke-width="2"/>
    <text x="85" y="42" text-anchor="middle" font-size="26" font-weight="600" fill="#2b567f">jexter</text>
    <text x="85" y="68" text-anchor="middle" font-size="13" letter-spacing="3" fill="#33699f">O C D</text>
    <text x="85" y="-12" text-anchor="middle" font-size="11.5" fill="#6a6f69">one normalized model</text>
  </g>

  <!-- fan-out hub -->
  <line x1="352" y1="174" x2="430" y2="174" stroke="#33699f" stroke-width="2.5"/>
  <circle cx="430" cy="174" r="4" fill="#33699f"/>

  <!-- outputs -->
  <g font-size="14" font-weight="600">
    <!-- rows: y centers -->
    <g>
      <line x1="430" y1="174" x2="560" y2="34"  stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="80"  stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="127" stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="174" stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="221" stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="268" stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
      <line x1="430" y1="174" x2="560" y2="314" stroke="#cdd3c8" stroke-width="1.5" marker-end="url(#ahg)"/>
    </g>
    <g fill="#1c1e1b">
      <g transform="translate(566,16)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">EPUB</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">fixed-layout facsimile</text></g>
      <g transform="translate(566,62)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">HTML</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">reflowable, semantic</text></g>
      <g transform="translate(566,109)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">SVG</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">vector, text stays text</text></g>
      <g transform="translate(566,156)"><rect width="196" height="36" rx="9" fill="#e7eff8" stroke="#8ec63f" stroke-width="1.5"/><text x="16" y="24" fill="#2b567f">Markdown</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#6a8a4a">structured text</text></g>
      <g transform="translate(566,203)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">DocTags</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">LLM / RAG</text></g>
      <g transform="translate(566,250)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">PDF</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">normalized, outlined</text></g>
      <g transform="translate(566,296)"><rect width="196" height="36" rx="9" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/><text x="16" y="24">OCD-EPUB</text><text x="180" y="24" text-anchor="end" font-weight="400" font-size="11.5" fill="#9aa09a">round-trippable</text></g>
    </g>
  </g>
</svg>
```.text

#let SVG_RECOMPOSE = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 280" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="re" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#3b5b7a"/></marker>
  </defs>
  <text x="28" y="22" font-size="13.5" font-weight="600" fill="#1c1e1b">Grouping scattered runs into <tspan fill="#27425c">lines &amp; paragraphs</tspan></text>

  <!-- LEFT: flat runs -->
  <g transform="translate(40,46)">
    <text x="150" y="-6" text-anchor="middle" font-size="11" fill="#6a6f69">flat text runs (as painted)</text>
    <rect width="300" height="196" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <!-- runs as separate ticks with gaps, showing independence -->
    <g fill="#cfd2cc">
      <rect x="22" y="24" width="70" height="9" rx="3"/><rect x="100" y="24" width="40" height="9" rx="3"/><rect x="148" y="24" width="90" height="9" rx="3"/>
      <rect x="22" y="44" width="54" height="9" rx="3"/><rect x="84" y="44" width="120" height="9" rx="3"/>
      <!-- a heading-ish run -->
      <rect x="22" y="78" width="110" height="11" rx="3" fill="#bcd49a"/>
      <rect x="22" y="106" width="80" height="9" rx="3"/><rect x="110" y="106" width="60" height="9" rx="3"/><rect x="178" y="106" width="58" height="9" rx="3"/>
      <rect x="22" y="126" width="120" height="9" rx="3"/><rect x="150" y="126" width="80" height="9" rx="3"/>
      <rect x="22" y="158" width="40" height="9" rx="3"/>
    </g>
    <text x="150" y="186" text-anchor="middle" font-size="9" fill="#a7aaa3">independent show-text ops on baselines</text>
  </g>

  <!-- arrow -->
  <g transform="translate(352,144)">
    <line x1="0" y1="0" x2="40" y2="0" stroke="#3b5b7a" stroke-width="2" marker-end="url(#re)"/>
    <text x="20" y="-10" text-anchor="middle" font-size="9.5" fill="#27425c">segment</text>
    <text x="20" y="20" text-anchor="middle" font-size="8.5" fill="#4a627d">TextBlocks</text>
  </g>

  <!-- RIGHT: blocks -->
  <g transform="translate(408,46)">
    <text x="150" y="-6" text-anchor="middle" font-size="11" fill="#6a6f69">blocks: paragraphs + breaks</text>
    <rect width="300" height="196" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>

    <!-- isolated heading block (over-segmentation) -->
    <rect x="14" y="16" width="272" height="26" rx="6" fill="#e7eff8" stroke="#8ec63f"/>
    <rect x="24" y="26" width="120" height="9" rx="3" fill="#7aa83a"/>
    <text x="278" y="32" text-anchor="end" font-size="8.5" fill="#6a8a4a">own block</text>

    <!-- paragraph block with line breaks -->
    <rect x="14" y="52" width="272" height="92" rx="6" fill="#f4f7fb" stroke="#cdd9e6"/>
    <g fill="#cfd2cc">
      <rect x="24" y="66" width="240" height="9" rx="3"/>
      <rect x="24" y="88" width="252" height="9" rx="3"/>
      <rect x="24" y="110" width="180" height="9" rx="3"/>
    </g>
    <!-- break tokens -->
    <g font-family="DejaVu Sans Mono" font-size="9" fill="#3b5b7a">
      <text x="270" y="83">&#8617;</text><text x="270" y="105">&#8617;</text>
    </g>
    <text x="20" y="135" font-size="8.5" fill="#7e93a8">OCDParagraph · &#8617; = OCDBreak</text>

    <!-- another paragraph -->
    <rect x="14" y="152" width="272" height="34" rx="6" fill="#f4f7fb" stroke="#cdd9e6"/>
    <rect x="24" y="166" width="200" height="9" rx="3" fill="#cfd2cc"/>
  </g>

  <text x="40" y="266" font-size="10.5" fill="#6a6f69">The segmenter deliberately over-segments: a heading or marker line always opens its own block, so it is never glued to its body.</text>
</svg>
```.text

#let SVG_SUGARCUBE_MARK = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" role="img" aria-label="Sugarcube">
  <title>Sugarcube</title>
  <!-- THE Sugarcube mark, one asset for every surface (Prism, Inspector, Jexter).
       The isometric cube of the nimbus decks, same geometry and same three blues,
       so the build tool and the apps carry one identity. No authoring metadata.

       Drawn EDGE TO EDGE inside the viewBox: the mark is placed on a disc by the
       surfaces that want one (.px-about-disc, .px-empty-mark), so a keyline here
       would fight that container and shrink the cube at 16px. The dark outline is
       part of the mark — it is what keeps the three faces readable once the whole
       thing is 16 pixels wide and the blues collapse into one another. -->
  <path d="M50 8 L88 28 L50 48 L12 28 Z" fill="#BFD9F2"/>
  <path d="M12 28 L50 48 L50 92 L12 72 Z" fill="#5E9BD6"/>
  <path d="M88 28 L50 48 L50 92 L88 72 Z" fill="#33699F"/>
  <path d="M50 8 L88 28 L50 48 L12 28 Z M12 28 L50 48 L50 92 L12 72 Z M88 28 L50 48 L50 92 L88 72 Z"
        fill="none" stroke="#0F1318" stroke-width="3" stroke-linejoin="round"/>
</svg>
```.text

#let SVG_XYCUT = ```
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 780 320" font-family="Poppins, DejaVu Sans, sans-serif">
  <defs>
    <marker id="ord" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#3b5b7a"/></marker>
    <marker id="bad" markerWidth="9" markerHeight="9" refX="6.5" refY="3" orient="auto"><path d="M0,0 L6.5,3 L0,6 Z" fill="#c1452f"/></marker>
  </defs>

  <!-- LEFT: plain XY-cut fails -->
  <g transform="translate(28,20)">
    <text x="160" y="0" text-anchor="middle" font-size="13.5" font-weight="600" fill="#c1452f">Plain XY-cut — L-shape failure</text>
    <rect x="0" y="14" width="320" height="250" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <!-- banner spanning full width -->
    <rect x="18" y="32" width="284" height="38" rx="5" fill="#e7edf4" stroke="#cdd9e6"/>
    <text x="160" y="56" text-anchor="middle" font-size="12" fill="#27425c">full-width banner erases the gutter</text>
    <!-- two columns -->
    <rect x="18" y="86" width="130" height="160" rx="5" fill="#f6f7f4" stroke="#e6e7e2"/>
    <rect x="172" y="86" width="130" height="160" rx="5" fill="#f6f7f4" stroke="#e6e7e2"/>
    <g stroke="#d7dad4" stroke-width="4" stroke-linecap="round">
      <line x1="32" y1="104" x2="134" y2="104"/><line x1="32" y1="118" x2="134" y2="118"/><line x1="32" y1="132" x2="120" y2="132"/>
      <line x1="186" y1="104" x2="288" y2="104"/><line x1="186" y1="118" x2="288" y2="118"/><line x1="186" y1="132" x2="276" y2="132"/>
    </g>
    <!-- wrong serpentine order crossing the gutter -->
    <path d="M83 150 H 237" fill="none" stroke="#c1452f" stroke-width="2" stroke-dasharray="5 4" marker-end="url(#bad)"/>
    <path d="M237 175 H 83" fill="none" stroke="#c1452f" stroke-width="2" stroke-dasharray="5 4" marker-end="url(#bad)"/>
    <text x="160" y="225" text-anchor="middle" font-size="11.5" fill="#c1452f">rows read across columns — wrong</text>
  </g>

  <!-- RIGHT: XY-Cut++ -->
  <g transform="translate(432,20)">
    <text x="160" y="0" text-anchor="middle" font-size="13.5" font-weight="600" fill="#2b567f">XY-Cut++ — cross-layout masking</text>
    <rect x="0" y="14" width="320" height="250" rx="6" fill="#ffffff" stroke="#dfe1db" stroke-width="1.5"/>
    <!-- banner masked out -->
    <rect x="18" y="32" width="284" height="38" rx="5" fill="#e7eff8" stroke="#8ec63f" stroke-dasharray="5 4"/>
    <text x="160" y="50" text-anchor="middle" font-size="11" fill="#33699f">banner pulled out (width &gt; β·median)</text>
    <text x="160" y="64" text-anchor="middle" font-size="11" fill="#33699f">re-inserted by vertical position</text>
    <!-- columns now cut cleanly -->
    <rect x="18" y="86" width="130" height="160" rx="5" fill="#f6f7f4" stroke="#e6e7e2"/>
    <rect x="172" y="86" width="130" height="160" rx="5" fill="#f6f7f4" stroke="#e6e7e2"/>
    <line x1="160" y1="86" x2="160" y2="246" stroke="#8ec63f" stroke-width="2" stroke-dasharray="3 3"/>
    <text x="160" y="262" text-anchor="middle" font-size="10.5" fill="#6a8a4a">valley by relative depth</text>
    <g stroke="#d7dad4" stroke-width="4" stroke-linecap="round">
      <line x1="32" y1="104" x2="134" y2="104"/><line x1="32" y1="118" x2="134" y2="118"/><line x1="32" y1="132" x2="120" y2="132"/>
      <line x1="186" y1="104" x2="288" y2="104"/><line x1="186" y1="118" x2="288" y2="118"/><line x1="186" y1="132" x2="276" y2="132"/>
    </g>
    <!-- correct order: banner -> left down -> right down -->
    <circle cx="284" cy="51" r="9" fill="#3b5b7a"/><text x="284" y="55" text-anchor="middle" font-size="11" font-weight="600" fill="#fff">1</text>
    <path d="M83 150 V 200" fill="none" stroke="#3b5b7a" stroke-width="2" marker-end="url(#ord)"/>
    <circle cx="83" cy="148" r="9" fill="#3b5b7a"/><text x="83" y="152" text-anchor="middle" font-size="11" font-weight="600" fill="#fff">2</text>
    <path d="M237 150 V 200" fill="none" stroke="#3b5b7a" stroke-width="2" marker-end="url(#ord)"/>
    <circle cx="237" cy="148" r="9" fill="#3b5b7a"/><text x="237" y="152" text-anchor="middle" font-size="11" font-weight="600" fill="#fff">3</text>
  </g>
</svg>
```.text


// ═══════════════════════════════════════════════════════════════════════════════
// DOCUMENT
// ═══════════════════════════════════════════════════════════════════════════════

#set page(
  paper: "a4",
  margin: (top: 2.4cm, bottom: 2.2cm, x: 2.3cm),
  fill: paper,
  header: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8.5pt, fill: faint, font: "Poppins")
      #grid(columns: (1fr, 1fr),
        align(left)[#box(baseline: 25%, svgimg(SVG_SUGARCUBE_MARK, height: 10pt)) #h(4pt) Sugarcube jexter],
        align(right)[The OCD pipeline],
      )
      #v(-6pt)
      #line(length: 100%, stroke: 0.5pt + hair)
    ]
  },
  footer: context {
    if counter(page).get().first() > 1 [
      #set text(size: 8.5pt, fill: faint, font: "Poppins")
      #line(length: 100%, stroke: 0.5pt + hair)
      #v(-2pt)
      #grid(columns: (1fr, 1fr),
        align(left)[© 2026 Sugarcube Information Technology Sàrl],
        align(right)[#counter(page).display() / #context counter(page).final().first()],
      )
    ]
  },
)

#set text(font: "Lora", size: 10.5pt, fill: ink, lang: "en")
#set par(justify: true, leading: 0.72em, spacing: 1.35em, first-line-indent: 0pt)

// ---- headings ----------------------------------------------------------------
#set heading(numbering: "1.1")
#show heading: set text(font: "Poppins", fill: ink)
#show heading.where(level: 1): it => block(above: 1.7em, below: 0.9em, sticky: true)[
  #set text(size: 16pt, weight: 600)
  #grid(columns: (auto, 1fr), gutter: 10pt, align: horizon,
    box(fill: brandP, inset: (x: 7pt, y: 3pt), radius: 5pt)[
      #set text(fill: brandD, size: 12pt)
      #counter(heading).display("1")
    ],
    it.body,
  )
]
#show heading.where(level: 2): it => block(above: 2.0em, below: 0.95em, sticky: true)[
  #set text(size: 12pt, weight: 600, fill: brandD)
  #counter(heading).display("1.1") #h(6pt) #it.body
]

// ---- inline code / raw -------------------------------------------------------
#show raw.where(block: false): it => box(
  fill: rgb("#f3f4f0"), inset: (x: 3pt, y: 0pt), outset: (y: 2pt), radius: 3pt,
)[#text(font: "DejaVu Sans Mono", size: 8.8pt, fill: rgb("#34403a"))[#it]]

// ---- block code (dark terminal) ----------------------------------------------
#show raw.where(block: true): it => block(
  width: 100%, fill: rgb("#1c1e1b"), radius: 6pt, inset: (x: 14pt, y: 12pt),
)[
  #set text(font: "DejaVu Sans Mono", size: 8.4pt, fill: rgb("#cfe6b0"))
  #set par(leading: 0.82em, justify: false)
  #it
]

// ---- figures -----------------------------------------------------------------
#set figure(numbering: "1")
#show figure.caption: it => block(width: 86%)[
  #set text(size: 8.8pt, fill: soft, font: "Poppins")
  #text(fill: brand, weight: 600)[#it.supplement #context it.counter.display()] #h(4pt) #it.body
]
#let fig(svg, cap, w: 100%) = figure(
  block(width: w, inset: 0pt, svgimg(svg, width: w)),
  caption: cap,
  gap: 0.7em,
)
#let figpin(svg, cap, w: 100%) = figure(
  block(width: w, inset: 0pt, svgimg(svg, width: w)),
  caption: cap,
  gap: 0.7em,
)

// ---- callout -----------------------------------------------------------------
#let callout(title, body, accent: brand, bg: brandP) = block(
  width: 100%, fill: bg, radius: 6pt, inset: (x: 12pt, y: 10pt),
  stroke: (left: 2.5pt + accent),
)[
  #set text(size: 9.6pt)
  #text(font: "Poppins", weight: 600, fill: accent.darken(15%), size: 9.8pt)[#title] \
  #body
]

// ============================================================================
//  COVER
// ============================================================================
#v(2.3cm)
#align(center)[
  #svgimg(SVG_SUGARCUBE_MARK, width: 62pt)
  #v(0.3cm)
  #text(font: "Poppins", size: 12pt, fill: brand, weight: 500)[Sugarcube]
  #v(0.08cm)
  #text(font: "Poppins", size: 52pt, weight: 700, fill: ink)[jexter]
  #v(0.1cm)
  #block(width: 13cm)[
    #set text(font: "Lora", size: 13pt, fill: soft, style: "italic")
    #set par(justify: false, leading: 0.6em)
    A faithful, structured document engine built on one normalized model — the Open Canonical Document.
  ]
]
#v(1.0cm)
#align(center, box(width: 15cm, svgimg(SVG_PIPELINE, width: 100%)))
#v(1.1cm)
#align(center)[
  #set text(font: "Poppins", size: 9.5pt, fill: faint)
  PDF ↔ OCD ↔ EPUB · HTML · SVG · Markdown · DocTags · PDF #h(10pt)|#h(10pt) Apache PDFBox 3.x · JDK 21+
  #v(0.3cm)
  A technical overview — with a tour of the analysis pass
]
#v(1fr)
#align(center)[
  #set text(font: "Poppins", size: 8.5pt, fill: faint)
  © 2026 Sugarcube Information Technology Sàrl · dual-licensed AGPL-3.0 / commercial
]
#pagebreak()

// ============================================================================
= What jexter is

Built on Apache PDFBox, *jexter* opens a PDF and lifts it into a single in-memory
model called the *Open Canonical Document* (OCD), then writes that
model back out to PDF, fixed-layout EPUB3, reflowable HTML, vector SVG, Markdown, or
LLM-oriented DocTags. The same engine can also serialize the model itself to a self-contained
*OCD-EPUB* (`.ocd.epub`) — a valid fixed-layout EPUB that is also the lossless
model container — and re-open it later, unchanged.

The point of the design is the model in the middle. Rather than translating each
input directly to each output — an $N times M$ tangle of converters — jexter
normalizes *once* into OCD, and every exporter reads from that one representation.
Positions, fonts, colours, clipping and reading order are decided a single time and
survive intact into every projection. A round-trip stays faithful by construction:
what you see on the page is what reaches the EPUB or the HTML.

Beyond faithful presentation, jexter recovers a *logical structure layer*. Reading
order and headings come from pure geometry; the fuller role set — lists, figures and
captions, tables, code — comes from a tagged (PDF/UA) ground truth when one is present,
or from the optional LLM-refine layer. Crucially, PDFBox is both the
parser *and* the reference rasterizer, so the raster fallback and the fidelity check
agree by definition rather than by luck.

#callout("Two layers, kept apart")[
  A document carries a *presentation* layer (what paints) and a *logical* layer (what
  it means). They never mix: the logical tree references content by id and never moves
  a pixel. This separation is what lets the same source become both a pixel-faithful
  replica *and* a clean, reflow-friendly structure.
]

// ============================================================================
= The Open Canonical Document

At the centre sits a small, sealed model. Everything that paints is an `OCDNode` —
one of `OCDText`, `OCDPath`, `OCDImage`, `OCDGroup`, `OCDMedia`, or `OCDBreak`.
Container subtypes give it structure: `OCDParagraph` (a text block with line breaks),
`OCDLayerContent` (bound to an optional-content layer), and `OCDGraphic` (a clustered
vector drawing). Fonts live as `OCDFont` + `OCDGlyph` with em-normalized outlines;
media as `OCDVideo` / `OCDAudio`; the page and document tie it together.

#fig(SVG_OCD_HUB)[The OCD model has three facets — presentation, logical, and
annotations — around one format-neutral core, governed by a few strict invariants.]

The invariants are what keep the model honest across so many projections. Page user
space is *Y-up*. Geometry is local plus a per-node transform — glyphs in em units,
images and media in the unit square, paths in page space. Colours are sRGB `int`
argb with alpha folded in. Bounding boxes are *derived*, never stored, so they cannot
drift out of sync. Identifiers are 1-based with `0` reserved as a sentinel. And the
logical `OCDStruct` tree only ever *references* content — it cannot alter what paints.

#callout("Native text without embedded fonts", accent: indigo, bg: indigoP)[
  The OCD-EPUB container is deliberately TrueType-free: every font lives once in a
  shared `pages/fonts.svg` — outlines, metrics and cmap in one SVG, the single font
  representation — and the pages reference glyphs from it. An embeddable
  `.ttf`/`.otf` is recompiled on demand — by `GlyfOtf` (glyf) or `CffOtf` (CFF) —
  when a PDF or EPUB is written, so outputs carry real, searchable text rather than
  traced shapes, while the model stays compact.
]

// ============================================================================
= From PDF to model

On the import path, the only PDFBox-coupled layer is `convert`. `PdfStreamEngine` walks each content
stream and emits primitive OCD nodes: glyph runs (with faces, sizes, colours, TJ
kerning, rotation and z-order), vector paths (fills, strokes,
dashes, caps/joins, béziers, opacity, blend modes, even-odd winding), images (inline
and XObject, including JBIG2), and deferred clip regions. `FontExtractor` rebuilds
each font; corrupt embedded TrueType `post` tables are repaired so the real glyphs
render instead of falling back to Arial. If the PDF is tagged, `TaggedStructureBuilder`
ingests the PDF/UA tree as logical ground truth.

#fig(SVG_IMPORT)[`PdfStreamEngine` walks the content stream and emits primitive
OCD nodes — text runs, vector paths, images, and the clip regions that bound them.]

What lands at the end of import is a faithful but *flat* OCD document: every mark is in
place and paints correctly, but it has no notion of paragraphs, headings, columns,
tables, or reading order yet. Recovering that meaning is the job of the analysis pass.

// ============================================================================
= The analysis pass

The analysis layer turns the flat, paint-ordered document into a structured one. It is
a fixed sequence of small, single-purpose passes, each gated by `ConvertOptions` and
each operating purely on the OCD model. The whole pass runs on *any* in-memory
document, whether it came from a PDF or was read back from an OCD-EPUB.

Two principles hold the layer together. It is *geometry-first*: the visual line is
reconstructed and frozen before any text or font signal is read, so detection is
OCR-robust and never leans on a vocabulary. And it keeps *one authority per concern* —
one pass owns lines, one owns spaces, one owns reading order, one owns running furniture,
one owns headings — so each rule lives in exactly one place.

#fig(SVG_ANALYSIS_CHAIN)[The analysis pipeline, in order. Each pass is
render-neutral by construction: it sets roles, reorders the content into reading order, and
builds a structure tree by reference, and never touches geometry, z-order, glyphs, paths, or colours.]

The single most important property is in that caption: *nothing here moves a pixel.*
Every pass sets `OCDNode.role`, reorders the page `content` into reading order, or builds an
`OCDStruct` tree by reference. The renderer still paints by `z` — reading order *is* the content
array order, paint order is `z`, and the two are independent. So the sacred round-trip fidelity
bar cannot regress no matter how aggressive the structure heuristics become — a freedom
that lets the analysis be bold without risk.

A second design choice runs through the whole layer: there is *one* layout engine, `XYCut`.
`XYCut.segment` groups leaves into blocks; `XYCut.order` flattens them into reading order.
Segmentation and reading-order recovery share the same code, so tuning happens in a single place.

The other consequence is doctrinal: the pass *detects the obvious and defers the ambiguous.*
It commits only structure that pure geometry establishes with high confidence — a type style
that recurs, a column the whitespace cleanly cuts, furniture stable across the recto/verso
stack. Anything genuinely ambiguous (a bare section number, an unusual layout) or lexical
(figures, tables, captions, lists, code) is left to the optional LLM-refine layer or to a human
in the interactive editor, rather than guessed at. A wrong structure is worse than no structure.
A document therefore ends up carrying *two* structures side by side: the `heuristic` tree this
layer recovers, and the `pdf` tree — the document's own PDF/UA tag tree, when it has one.

== Cleaning to the ink base — `Cleaner`

Before anything is interpreted, the document is flattened to the simplest base every later
pass can share — a pure change of representation, fidelity-exact, that removes only
non-painting noise. `Cleaner` does four things. It keeps every *inked* run and its glyphs
intact — the geometric evidence — since the run boundary the PDF emitted is never trusted as a
word boundary here. It drops whitespace-only runs and strips every blank glyph from a run — a
real space glyph *and* any prior space sentinel alike — so runs become *pure ink*; the word
spaces are not trusted from the source but re-derived later from glyph geometry. It drops the
`OCDBreak` line markers (they paint nothing and are regenerated downstream). And it splices
away render-neutral paragraph and graphic wrappers, while keeping render-bearing groups (Form
XObjects, transparency groups) and recursing into them. It is *structure-preserving* — a
spliced wrapper's references are rewritten onto its content nodes through the single
`IdStamper` authority, so the PDF/UA ground truth survives untouched — and idempotent:
re-running on an already-clean document is a no-op.

#fig(SVG_CLEANING)[Cleaning is a change of representation, not of pixels: render-neutral
paragraph/graphic wrappers are spliced, line-break tokens dropped and blank glyphs stripped,
leaving flat *pure-ink* runs (render-bearing groups are kept and recursed into). The image is
identical; the model is lighter.]

== Lines and spaces — `Liner` + `Spacer`

The geometry-first heart of the pass. `Liner` is the *line authority*, pure geometry: it bands
runs into lines by *vertical overlap alone* — a run joins a line when it overlaps it by at least
a third of the smaller band — with no size gate, so a raised or shrunk sub/superscript naturally
belongs to its line. A gutter wider than one em splits columns into proto-lines for the cut. No
font, no text, no lexicon is consulted, which is exactly what makes it OCR-robust.

The moment a clean line is rebuilt, `Spacer` — the *space authority* — freezes it *once* and it
is never touched again. The line's runs are ordered left-to-right (reading order is intrinsic to a
frozen line); word spaces are inferred by one uniform rule, where the advance slack past half the
font's space width is a space; and the line is sealed on a word boundary (`endLine`), leaving a
trailing hyphen painted so a split word can be rejoined at read time. Every space — intra-run,
inter-run, inter-line — is a render-neutral *sentinel* glyph (`gid −1`, unicode `" "`): outline-less,
so every render path skips it, yet extraction and structure read the `" "`. `OCDBreak` stays a
layout flag only, marking visual line boundaries for reflow; it is *never* consulted for spacing.

Extraction-quality text needs no analysis pass either: a presentation-form ligature keeps its
single outline (`gid`) *and* its source codepoint, so the page — and the OCD-EPUB — stay unchanged.
The folding happens only at *read-out*. `OCDIndex` applies NFKC as it assembles the text, so a
compatibility ligature (the single `U+FB03` glyph) reads as `ffi`; _“office”_ stored with one
`ffi` glyph extracts as searchable `office`, for the reflowable, searchable projections
(Markdown · DocTags · HTML · EPUB-reflow · search). SVG and PDF paint by glyph id and are
untouched, and the model keeps the source codepoint — so fidelity is never affected.

#fig(SVG_CANON)[A ligature is one glyph in the font. Its outline (`gid`) and source
codepoint are kept, so the page and the OCD-EPUB are unchanged; only the read-out folds it with
NFKC, so the extracted text reads _Office_.]

== Physical recomposition — `Segmenter` + `Paragrapher`

With lines frozen and spaced, the page's runs are grouped into `OCDParagraph`s. `Paragrapher` is
a thin, render-neutral projection over `Segmenter`: `Segmenter` rebuilds each clean line with
`Liner`, has `Spacer.freeze` seal it, then `splitLeaf` groups the frozen lines into blocks by
*typography* — half-point size and whole-line weight — and by *interline* leading judged
adaptively against the region's own median baseline step, so loosely- and tightly-set paragraphs
both segment correctly. `Paragrapher` then wraps each block's nodes — in their original paint
order — into one transparent `OCDParagraph` and inserts an `OCDBreak` at each visual-line change.
Nodes are never reordered, so the page paints byte-identically.

`Segmenter` deliberately *over-segments*: a marker or enumerator line always opens its own block,
so a heading is never glued to the next heading or to its body. Over-segmentation is the safe bias
because every line was already sealed on a word boundary by `Spacer.endLine`, so the text stays
correct across the cut. A light dust-absorption step folds a detached accent or an isolated stray
mark into the block that contains it, leaving drop caps and list markers intact.

#fig(SVG_RECOMPOSE)[Independent show-text runs are grouped into paragraph blocks
with line-break tokens. A heading or marker line always opens its own block, so it is
never glued to its body.]

== Reading order — XY-Cut++

Plain recursive XY-cut (Nagy & Seth, 1984) splits a region along its widest empty
whitespace band and recurses. It fails on the *L-shape problem*: a full-width element —
a banner, a spanning figure — crosses the column gutter and erases the valley, so the
columns collapse into one mis-ordered run.

#fig(SVG_XYCUT)[The L-shape problem and its fix. A spanning banner erases the
gutter, so plain XY-cut reads across the columns; XY-Cut++ masks the banner out before
the cut and re-inserts it by vertical position.]

XY-Cut++ (Liu et al., _Advanced Layout Ordering via Hierarchical Mask Mechanism_,
arXiv:2504.10258, 2025) fixes this with three moves. *Cross-layout masking* pulls out elements wider
than $beta dot.op "median"$ that overlap two or more others, then re-inserts them by
vertical position, so they never block a gutter. *Relative-depth valleys* score a cut
by its depth against the surrounding peaks (with a 5% noise floor), not by raw width,
so a near-empty corridor still cuts and page margins never do. *Adaptive thresholds*
derive the minimum gap per axis from the document's own median block dimensions. The cut runs
on *text only* — graphics are clustered apart and placed by overlap — because folding a page's
drawings into the same cut bridges the whitespace valleys and collapses the leaves. Pure
geometry, deterministic, no machine learning.

== Headings — `FontProfile` + `StructureBuilder`

Headings are *discovered, not matched against a vocabulary.* `FontProfile` reads the whole
document once and builds a typographic alphabet: per type-style signature — half-point size plus
whole-line bold — it accumulates the total ink, the number of pages the style appears on, and its
occurrence count, excluding running furniture. The *body* is the signature with the most ink. The
canonical rule for a heading is a single line:

#callout("The heading rule")[
  A style is a heading only if it is *elevated* — larger than the body, or bold at body size —
  *and it recurs*, appearing on at least two pages or at least three times.
]

Recurrence is the load-bearing discriminant. Without it a one-off large decoration or a figure's
internal label would masquerade as a heading; with it, only real heading styles survive. The
elevated, recurring styles are ranked by size into H1…HN, and the single largest elevated style
above everything is the title. There is *no lexicon and no enumerator regex* — a bare `§ 4`, a lone
`1`, an alinéa number are all genuinely ambiguous, so they are left as plain content for a human in
the interactive editor rather than guessed at. Running heads and feet are excluded through the one
authority for that, `Furniture` (via `Furniture.isRunning`).

== Vector clustering — `GraphicClusterer`

A drawing — a logo, an icon, a chart body — is several vector paths that form one visual
unit, but the PDF gives it no marker, so the clustering is heuristic and prone to
swallowing page furniture.

#fig(SVG_GRAPHICIZER)[Only a contiguous run of the content array is wrapped into an
`OCDGraphic`. Full-page backgrounds, frames, uniform grids and lone paths are filtered out
as furniture.]

The guards follow the battle-tested pymupdf4llm recipe plus a contiguity rule of jexter's
own: a path spanning almost a whole page dimension is furniture; a run whose paths all
share one width or height is a grid or set of rules; at least one path must genuinely
occupy the cluster's interior; and only a contiguous span of the content array is wrapped. A single lone path is never a graphic. Within a
contiguous run, paths group by *scale-adaptive* proximity — a small floor plus a fraction
of the smaller path's own size — so a large, spread-out schematic coalesces into one
graphic while neighbouring drawings never chain, where a fixed tolerance would do neither.

A graphic is a *model* entity and never reaches the serialized page. Its paths are contiguous
in the content array, but they do **not** occupy a contiguous range of paint order: on a press
cover, one drawing's children carry z 287, 225, 255 and 316 while neighbouring content paints at
131, 196, 283 and 221 — other ink is laid down between them. A page writes its children in paint
order, so nesting those paths inside one `<g>` would force them into a single depth slot and a
path would land over the text. The writer therefore *dissolves* a render-neutral `OCDGraphic`
(identity transform, no clip, no blend, full alpha) and emits its children in place; the grouping
is re-derivable by re-running the pass. Measured in a browser: nesting cost a logo and four lines
of type on the first page tried.

A drawing exists at two levels, and the heuristic pass stops at the lower one. `OCDGraphic`
is the *presentation* entity — the visual drawing on the page — and it is always produced here.
Promoting a graphic to a logical `FIGURE` is reserved for captioned or obviously significant
content and is *deferred to the `Refiner`*, so the geometric pass never over-promotes a header
logo or a callout banner into a figure.

== Running heads & feet — recto/verso stack stability

A running head or foot *is* a line that stays the same from one page to the *next page of the
same side*. Odd and even pages are two separate stacks — recto and verso: a recto header reappears
on the next recto, a verso header on the next verso, whether or not the two sides carry the same
words. `Furniture` reads exactly that — one idea, no lexicon, no global threshold to tune beyond
the page edge itself.

#fig(SVG_HEADERFOOTER, w: 90%)[For a candidate edge line on page _p_, its whole raw string —
upper-cased, whitespace collapsed, digits kept — is compared to the same edge position on its
same-side neighbours _p−2_ and _p+2_ by a normalised Levenshtein similarity. A line is furniture when
the mean clears `SIM_MIN`; an inconclusive same-side stack falls back to the immediate _p±1_ pair.]

The two stacks are what make it clean. Recto and verso never cancel, so a one-sided running head is
still caught, and a title page falls out for free: a page-1 title or bare law number differs from the
running header on page 3, so its single neighbour pair is unstable and it is never tagged. Stability
is *local* — judged per page against its own neighbours — so a head that changes at a section break is
followed, not lost. Text re-enters only at the end, to *name* what the stack found: a page-number
shape, a header, a footer. `Furniture.isRunning` is then the single authority every later stage
consults to keep furniture out of the document body.

== Deferred enrichment & language — `Refiner`

The geometric pass commits only what pure geometry establishes — lines, spaces, segmentation and
reading order, running furniture, headings. Everything that needs a texture opinion or a lexical
pattern — figures, tables, captions, lists, code — is *deferred*. An *optional* LLM pass (`Refiner`,
off by default) recovers it downstream: it is fed pre-computed perceptual signals — spacing,
indentation, emphasis, colour — rather than page images, runs page-windowed to dodge
lost-in-the-middle degradation, and is grounded so that a failure is a no-op rather than a
corruption. Storing manual corrections and re-injecting them as few-shot exemplars is a planned extension. Separately,
`LanguageDetector` runs at export time — not as a pipeline stage — voting with stopword frequencies
over a sample and tagging the language only when a clear winner clears a margin over the runner-up.

#fig(SVG_LLM)[The optional LLM pass works one page at a time, in a sliding window:
a static document profile, the neighbouring pages condensed, and the current page in
full detail placed last — so it never gets lost in a long context.]

// ============================================================================
= Projections out

Every writer shares one contract and reads the same model, so the outputs stay mutually
consistent:

#block(above: 0.8em, below: 1em)[
  #set text(size: 9.6pt)
  #table(
    columns: (auto, 1fr),
    stroke: none,
    inset: (x: 8pt, y: 6pt),
    fill: (_, row) => if row == 0 { brandP } else if calc.odd(row) { rgb("#f6f7f4") } else { white },
    table.header(
      [#text(font: "Poppins", weight: 600, fill: brandD)[Target]],
      [#text(font: "Poppins", weight: 600, fill: brandD)[Best for]],
    ),
    [EPUB (fixed-layout)], [A page-faithful, epubcheck-validated replica for any e-reader.],
    [HTML (reflowable)], [Semantic HTML5 — headings, lists, tables, figures, media — for the web and accessibility.],
    [SVG], [Resolution-independent vector pages; text stays selectable text.],
    [Markdown], [Lightweight structured text for notes, wikis and pipelines.],
    [DocTags], [A compact tagged form aimed at LLM / RAG ingestion.],
    [PDF (normalized)], [A clean PDF with repaired fonts and a generated outline from detected headings.],
    [OCD-EPUB (`.ocd.epub`)], [The model itself as a valid fixed-layout EPUB — self-contained SVG-OCD pages (text, reading order, lines, links as data), fonts shared in `pages/fonts.svg`; re-openable, round-trippable, and Prism's native format.],
  )
]

Reflowable HTML and EPUB are generated from the *structure tree*; the fixed-layout EPUB
and SVG are facsimiles of the *presentation* layer. Because both come from one model,
they never disagree about what the document says.

// ============================================================================
= The conversion API — `sugarcloud.ch`

Every projection above is also reachable over HTTP. `sugarcloud.ch` is a stateless front
for the *same* jexter engine: a client sends a PDF (or an `.ocd.epub`) together with a
per-client API key and gets the converted artifact straight back. It is meant for
server-to-server use — each consumer holds its own key and never exposes it to a browser.

#fig(SVG_API)[One authenticated `POST` carries the source and a chosen target; the
same engine that produced every projection in this overview answers with the artifact,
its media type and a filename.]

Two endpoints matter to a client. `GET /api/health` is a public probe that returns `ok`.
`POST /api/convert?to=<target>` does the work, where `to` is one of the projection ids —
`ocd`, `svg`, `pdf`, `epub`, `epub-reflow`, `html`, `md`, `doctags` (it defaults to
`svg`). The key travels as `Authorization: Bearer <key>` or `X-API-Key: <key>`; a missing
or invalid key is a `401`. Keys are stored hashed and hot-reloaded, so granting or
revoking a client never needs a restart.

The source is a raw `application/pdf` body or a multipart `file` field; an `.ocd.epub` body is
re-exported without re-parsing. A few writer options ride along as query parameters —
`page` (SVG, 0-based), `selectable` (PDF), `renderAnnotations` (SVG/EPUB), `grid` (DocTags) —
and any other key passes straight through to jexter's `ConvertOptions`. The response
carries jexter's own media type and `Content-Disposition` filename.

```
# health — public, no key
curl https://sugarcloud.ch/api/health                       # -> ok

# PDF -> first page as SVG
curl -H "Authorization: Bearer <key>" \
     -F file=@doc.pdf \
     "https://sugarcloud.ch/api/convert?to=svg&page=0" -o page-0.svg

# PDF -> normalized, selectable PDF (raw body)
curl -H "Authorization: Bearer <key>" -H "Content-Type: application/pdf" \
     --data-binary @doc.pdf \
     "https://sugarcloud.ch/api/convert?to=pdf&selectable=true" -o out.pdf
```

= Fidelity, by construction

The round-trip bar is sacred and measured, not assumed. Two facts make it hold. First,
PDFBox is both the parser and the reference rasterizer, so jexter's own renderer
(`OCDRenderer`) can be pixel-compared against the authority that produced the input.
Second, the entire analysis layer is additive: it annotates and references, never
repaints. The result is an engine that can afford ambitious structure recovery precisely
because none of it can ever damage the faithful image underneath.

#figpin(SVG_FIDELITY)[The same source is rasterized twice — once by PDFBox, once by
jexter's own renderer from the OCD model — and compared pixel for pixel.]

#v(1.2em)
#line(length: 100%, stroke: 0.5pt + hair)
#v(0.5em)
#block[
  #set text(size: 8.6pt, fill: faint, font: "Poppins")
  #grid(columns: (1fr, auto), align: (left, right),
    [Sugarcube jexter — built on Apache PDFBox 3.x & FontBox (Apache-2.0). \
     This overview was typeset with Typst; all figures are hand-authored SVG.],
    [© 2026 Sugarcube IT Sàrl],
  )
]
