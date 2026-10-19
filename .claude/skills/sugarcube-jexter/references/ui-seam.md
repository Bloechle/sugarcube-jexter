# ui-seam — the Prism tool architecture & how to gate UI work in the sandbox

The frontend (src/sugarcube/jexter/ui/) is three layers over one truth — **the
displayed DOM is the document**; the .ocd.epub file is transport (unzipped on
open, re-zipped on export).

## The three layers

1. **`shared/web/js/ocd.js`** — the client grammar: `loadOcd` (read), `parseFonts`,
   `OcdPage.adopt(svg, fonts)` (a live frame page: read, author runs, `xhtml()`),
   `pageShell(svgSrc, w, h, n)` — THE page-member shell — and `inReadingOrder` / `readingRuns`.
2. **`shared/web/js/book.js`** — the document authority. Tools speak `book.*`
   only: `open` · `bytes()` (the container as it stands) · read `get/json/isOpen/member/pagePath/
   fonts` · mutation hub `put` (memory + SW, awaitable, notifies `onChange(cb(path))`) + `declare`
   (idempotent manifest add — THE manifest authority) · resources `addImage/addMedia/addFont/freeAlias`
   · displayed DOM `page/eachFrame/frameDoc/frameCss/onFrame/refreshPage` · projection `persist/flush`.
3. **Tools** — ONE `<script>` tag each, over the engine seam `engine.js`: `augment.js`,
   `analysis.js`, `editor.js`, `redact.js`, `compare.js`, `forms.js`, `pages.js`, plus
   `trace.js` / `tts.js` / `gizmo.js`; script order defines tab order, and `experimental:
   true` hides a tab outside dev mode without unloading it. A
   tool: `P.registerTool({id,…,onEnter,onLeave})`, subscribes `P.on('tool'|
   'book'|'close'|'page'|'frame'|'mode'|'export')` (multicast bus), may provide `P.hooks.openSource/
   highlight/ttsNodes` (singular providers), guards its keys with `P.keyFor(id, e)`. Tool chrome
   carries `data-ui` (+ `data-z="under|over"`) — stripped by `OcdPage.xhtml()`, never in the
   container; a frame stylesheet goes through `book.frameCss`. The chassis's single write primitive
   is `P.putMember`. The chassis itself is `prism.js` over `events.js` (the bus), `tools.js` (the
   registry, tabs, ribbon, drawers, keys), `console.js` (F2) and `search.js`.

Hard-won rules:
- **A mode's chrome is a right drawer declared in index.html** (`<aside id="<mode>-drawer"
  class="px-drawer">`, shown by `body.mode-<id>` + `#split-right` in prism.css — edit, analysis,
  redact all do this); the tool only BINDS it. Never float a bar on `.rv-cap`: that class is a
  tooltip, `pointer-events:none` — its buttons can never be clicked (met on redact, 2026-09-03).
- **Never string-splice page members.** A childless `<svg>` self-closes under the
  browser XMLSerializer (`<svg …/>`) — `lastIndexOf('</svg>')` returns −1 and the
  write silently no-ops. Always rebuild via `pageShell`.
- Client-authored text runs must match the engine form: page-scoped `tN` ids, id
  FIRST in the tag — the text layer addresses runs by
  `/<g id="(t\d+)" data-ocd="run"…data-text=/` (grammar v2; it was `data-ocd="t"…data-u=` in v1).
- Layers are FULL-PAGE strata (`doc/FORMAT.md` §B4b); the engine-imported base is
  the implicit source stratum (layer zero) — never wrap it.
- Commit order in tools: resource `put`/`declare` AWAITED **before** the element
  referencing `../images/name` enters the DOM (the SW must already serve it).
- `state.aug` (edit's augmentation model) lives on `P.state` — book-level state.

## Gating UI work (the JS side of the fidelity doctrine)

**1 · Syntax gates** — every client file is a module: `cp file.js /tmp/check.mjs && node --check
/tmp/check.mjs` (what `build.yml` does for every `src/**/*.js`). Nothing is fetched any more: qry 3
(`/shared/vendor/qry/qry-app.js`, which imports `./qry-web.js`) and fflate (`/shared/vendor/fflate.js`)
are vendored, so a linkedom run maps those absolute paths onto the vendored files (or a stub that
exports the imported names).

**2 · Seam suite (linkedom)** — shim the chassis, run the tool flow, feed the
result to the ENGINE (Conversion → PDF pixel check, write(read(x)) byte-stable):
```js
const subs = new Map();
window.prism = { state: { files, root, opfDir:'OEBPS/', opfPath:'OEBPS/content.opf',
                          pages:[{href: root+'OEBPS/pages/page-001.xhtml'}] },
  putMember: async (p, b) => { files[p] = b; swPuts.push(p); },
  on: (e, cb) => { (subs.get(e) || subs.set(e, new Set()).get(e)).add(cb); return () => subs.get(e).delete(cb); },
  emitFrame: (f, i) => { for (const cb of subs.get('frame') || []) cb(f, i); },
  openEpub: async (buf) => { reopened = buf; }, goTo: () => {}, hooks: {} };
```
linkedom pitfalls (bench-only artifacts, NOT bugs):
- camelCase selectors (`clipPath`) don't match — use tag-free `[id="…"]` lookups;
- attribute serialization order is REVERSED — write order-agnostic assertions;
- read hrefs via a resilient helper (getAttributeNS + `xlink:href` + `href`).

**3 · Real-browser bench (the decisive gate)** — Playwright Chromium ships in the
sandbox at `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`:
```sh
cd /tmp/jstest && PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers npm i playwright-core
# nothing to vendor: qry 3 and fflate are in shared/web/vendor/, the icon set in shared/web/js/ —
# copy ui/prism/web to site/ and ui/shared/web to site/shared/. Serve + drive:
node server.mjs > /tmp/server.log 2>&1 & SRV=$!; sleep 1.5
timeout 90 node drive.mjs; kill $SRV        # one shell — servers die between tool calls
```
`chromium.launch({ executablePath: …, args: ['--no-sandbox'] })`; extract results
as base64 through `page.evaluate`, then run the engine on the browser-built
container. This is what caught the self-closing-SVG persist bug — linkedom cannot.

For a written PAGE (not the chassis), `scripts/ocd_page_bench.mjs` is the ready
harness: it serves an unzipped `.ocd.epub` over HTTP and screenshots page 1 at a
given pixel width, so the shot overlays the PDFBox reference. Use it whenever a
rendering claim is about the container — the Java reader and an SVG engine are
DIFFERENT surfaces and a page can be right for one and wrong for the other (a
`data-*` attribute the reader honours is invisible to a browser). Two fixes were
declared done on Java evidence alone before this bench settled it.

**4 · Extraction/refactor sweeps** — after moving code between modules, grep the
chassis for EVERY top-level identifier defined in the new module (exhaustive, not
a hand-picked list), and content-locate slice boundaries with assertions before
cutting (never cut on remembered line numbers).


## Rendering contracts learned the hard way (v1.0.0 audit)

- **Stored pages reference glyphs externally** (`<use href="fonts.svg#fN-gid">`).
  A Service-Worker-served frame (Prism reader) resolves that relatively; ANY
  consumer that injects the page svg **inline** (`innerHTML` — PDFInspector's
  stage) has no base URL, `fonts.svg` 404s at the server root, and text silently
  vanishes while graphics render. `ocd.js pageSvg()` therefore localizes the
  refs (`fonts.svg#` → `#`) and embeds the `pages/fonts.svg` `<defs>` into the page
  string — same rationale as the existing blob: rewrite for images. Never
  remove either.
- **Named access shadows APIs**: a frame's `<div id="stage">` IS
  `contentWindow.stage` until stage.js assigns the real object — guard the ONE
  accessor by duck-typing (`typeof s?.relayout === 'function'`), never each
  call site.
- **One brand asset**: `shared/web/jexter-mark.svg` (the J, distilled from
  doc/typst/fig, pixel-identical) serves favicon + header + about for all three
  apps; `WebApp` probes shared resources against THAT file. Wordmarks stay
  plain (PRISM, PDF Inspector, Jexter) — no `J.` prefix.
- **Rail/header tabs are segmented pills** (the `.px-seg` language), not
  Material underlines; keep `flex:1 1 auto` on `.tab-btn` or labels truncate.
- **Screenshot audits**: judge by pixel sampling (`PIL.getpixel`), never by the
  downscaled preview — it once made a (19,28,45) dark band look white.


## The Prism tool architecture (2026-09-05 — the session that built it)

A tool is a REGISTRATION, not a chassis edit. `P.registerTool({id, label, icon, drawer, title,
experimental, onEnter, onLeave})` gives it three surfaces keyed on `body.tool-<id>`: a tab rendered
from the registry, a ribbon declared with `P.ribbon(id, groups)`, and `#<id>-drawer` if the markup
has one. `setTool` is the ONE authority; adding a tool touches no chassis code.

**Ribbon = what you trigger; drawer = what you read.** Home declares no ribbon (layout and zoom live
in the footer). Don't build "a drawer per ribbon action" — no case needs it and it multiplies states.

**The ribbon declaration carries CONTROLS as well as commands** (2026-09-07) —
`P.ribbon(id, groups, help)`, an item being either `{icon, label, title, on, active, disabled}` or
`{field: 'search'|'color'|'number'|'switch', value, on, live, enter, list, unit, min, max, step}`.
Before that, a tool with a setting appended its own markup to the returned slot, and **the chassis
empties the slot on every declaration** — so the settings were rebuilt FROM THEIR DEFAULTS each time,
and sat at the far right whatever they configured. Redact's colour was worse still: its own `input`
handler called `refresh()`, which re-declared the ribbon and destroyed the field being changed.
Measured A/B (set the pattern, the colour and the switch, then read them back *immediately*): before
`"" / #000000 / false`, after `\d{3}\.\d{4} / #ff0000 / true`. A control's value comes from the
DECLARATION, which is what makes the tool's state the one authority. Focus AND the caret are restored
across a re-declaration — and `selectionStart` **throws** on a number or colour input, so ask for it
behind a guard rather than testing the type. The help line is the third argument now; nothing appends
to the slot any more.

**A tool whose whole interaction is implicit must SAY SO.** Reveal and Augment both looked broken for
exactly this reason: no ribbon, no drawer, nothing on screen naming the gesture. One `.px-rb-help`
line in the ribbon fixes it — "Drag a rectangle on the page to place it", "Point at anything — click
to pin, Esc to release". A tooltip is not discoverable.

**A distinction the render does not honour is not a distinction.** Augment shipped "Fade" and "Zoom"
curtains differing by `scale(1.08)` — invisible. The fix was to drop one, not to explain both.

### The side panes are ONE mechanic (2026-09-13)

The rail on the left and the tool pane on the right are the SAME object: a column of a remembered,
draggable width, foldable from the footer corner it sits in, with one gutter and the same blocks
inside. They were declared twice, 600 lines apart, word for word — which is why every fix reached one
side only. Now: `.px-pane` states the shell once; `.px-pane-l` / `.px-pane-r` keep only a width
variable and the edge they border on.

**The right side is a HOST, not a stack of panes.** There were six `<aside>`s, one per tool, each
carrying its own width, head and gutter — so the splitter had to *find* the visible one, and three
gutters had drifted (`.6` / `.5` / **`.1rem`**, the numeric fields flush against the edge). Now
`#pane-right` owns width, head, scrolling and fold, and a tool owns a `.px-pane-slot` inside it —
the ribbon's own pattern, one row down. `drawer(id)` (tools.js) returns the SLOT; the head is rendered from the
registry on every `setTool`. Adding a tool touches no shell.

One drag serves all three (rail, pane, console): `grow` says which way the pane grows, `panel()` is
asked at pointerdown, and the axis is a parameter. The console is dragged by its top edge —
`--px-log-h`, persisted like the other two.

**A pane's blocks share the pane's gutter** (`--px-gutter`), and `white-space:nowrap` on an info line
is a bug: those lines are sentences, and nowrap silently truncates them mid-word.

**A phone (≤ 760 px, 2026-10-19)**: the rail starts folded (prism.js `wireChrome`) and both panes open
OVER the stage, opaque (prism.css, last block); the tabs keep their icons, the footer keeps the rail, the
pager and the zoom; a book whose first page is wider than the stage opens at the stage's width, not
100%. Prism is also served by a host that is not an engine (a web reader of results): `?epub=<url>`
fetches the book, takes its name from the response's Content-Disposition, and with no `ok` at
`/api/health` the engine-only affordances stay hidden.

### Forms — a tool for data that has no ink (2026-09-13)

`forms.js` is the surface for the one thing a reader shows nothing of: form fields live in
`ocd/annots.json`, and no projection paints them into a page. The tool draws them from their own
rectangles as `data-ui` chrome and is the one place a value is typed.

**Edit IN PLACE, inside the page's own space.** A `<foreignObject>` the size of the box, carrying a
real `<input>`/`<textarea>`, inherits the page rotation, the widget's own `/MK /R` and the zoom without
mapping a single screen coordinate — an editor floating over the frame would have to redo all three.
A box drawn with `cursor:text` that only accepts typing somewhere else is an affordance that lies.

**Typing must not repaint.** The layer is rebuilt on every paint, and rebuilding the element you are
typing into takes the focus and the caret with it: update the model on each keystroke, the page and
the ribbon on commit.

**Commit on a debounce, not only on blur.** The export reads the MEMBER: a reader who fills the last
field and exports immediately shipped a form missing exactly what they just wrote (measured: 4 of 5
values in the member, the fifth still on screen).

**A pane must not echo the page.** The first version listed each field's value back — the box already
shows it, larger and in place. What the page cannot show is what is LEFT: how many remain, which are
required, and where they are. The pane is a worklist; the ribbon's useful verb is "next EMPTY field",
not "next field".

### Traps measured in the browser, on real OCD pages

- **Text is NOT hit-testable.** A run's glyphs are `<use href="f.svg#…">` — EXTERNAL references,
  which Chrome does not hit-test. A click straight on a word returns nothing at all. The only thing
  that answers is the page BACKDROP, a full-bleed path covering every point. Any picker must
  therefore work by GEOMETRY: walk the candidates, keep those whose screen rect contains the point,
  take the smallest, and skip anything covering >85% of the page.
- **Measure in SCREEN space, not `getBBox()`.** A bbox is in the element's own user space before its
  transform: a placed image reads 1 × 1 there, its size living in its matrix.
- **A transform composed in FRONT acts in the PARENT's space.** Every gesture must be computed there,
  with the bbox corners mapped through the element's own matrix first. Measuring with
  `getBoundingClientRect` (always axis-aligned) is why a rotated element scales along the page's axes.
- **Never copy the element's transform onto the gizmo.** It follows rotation for free — and a
  non-uniform scale then squashes the handles and the stroke with it. Map the CORNERS instead: the
  frame is a polygon on the element's axes, the handles stay square.
- **SVG paints in document order**, so a frame inserted beside its target is covered by everything
  drawn after it — handles invisible AND unclickable, the click falling through to the move gesture.
  Append to the SVG root and carry the parent's CTM.
- **Pointer events with capture, not mouse events.** A drag that leaves the page — and it always
  does — stops being delivered to that document, so the gesture freezes and the mouseup never comes.
- **A tool's listeners OUTLIVE its tab.** A gizmo bound to a page keeps answering under the next
  tool unless it has an `enabled` flag the host flips on leave. Augment inherited Edit's handles
  this way.
- **`setTool` must REPLAY the `frame` event** for mounted frames: a tool activated after the pages
  loaded has never seen them, and its handlers were never installed. Handlers mark their own
  document (`__pxAugBound`, `__jxInspect`, `__rvBound`, `svg.__rd`) so the replay is idempotent.
- **Keyboard shortcuts must be bound in the frames too.** A frame is a separate document; once focus
  is inside a page, F2/Esc/arrows never reach the chassis. Same-origin, so forward the keydown.
- **Emit the tool event FIRST.** `bindFrameInput` had `emit('frame')` at the end of a try that also
  wires wheel, touch and drop — any one of those throwing left the whole tool layer unbound on that
  page, silently, with the catch swallowing the reason.

### The gizmo (gizmo.js) — a reusable component, options read per gesture

`createGizmo(svg, {pick, onSelect, onChange, onCommit, enabled, move, scale, rotate, uniform, snap,
hover})`. **`onSelect` is the edge a pure CLICK produces**: nothing moves, so neither `onChange` nor
`onCommit` fires, and a host keeping its own notion of "what is selected" never learns about it. It
fires for the host's own `select`/`clear` too — one event for one fact, whoever caused it — so a host
that calls back into the gizmo from it must guard its own re-entrancy (a flag; the component does not
guess). Two hosts today: Pages adjusts a crop, Redact a zone, and the four numbers of either live in
the drawer on the shared `.px-fields` grid.
It knows GEOMETRY and nothing else: no document model, no persistence, no undo, no ribbon. Options
are read at EVERY gesture, so a host flips one from a ribbon button and the next drag obeys. Turning
a capability off also removes its handles — an affordance that does nothing is worse than an absent
one. A pure selector is the same component with `{move:false, scale:false, rotate:false}`; there is
no second component, because the three space traps above live in the shared half.

Everything it draws is `data-ui`, which `book.persist` strips before writing a member: the chrome of
editing cannot leak into the document, by construction rather than by care.

### Name collisions are the recurring defect of this codebase

Six in one session, all from introducing a name without checking who already holds it: `.px-tools`
(was Edit's "Add:" bar), `.px-tabs` (was the rail's tab strip), `const STAGES` (already in
`jexter.js` — a module SyntaxError that made the whole tool seam dark), three ids removed as
"orphans" that were referenced DYNAMICALLY (`$('#' + btn.attr('data-tab'))` — a grep for `#tab-toc`
finds nothing), and `const o = outlineOf(...)` shadowing the gizmo's options object (the frame drew,
every handle silently did not).

CI now carries the gates: ES-module parse (`cp f.js /tmp/check.mjs && node --check` — different
parse rules), a TDZ detector (a top-level call reaching a `const` declared further down), and
`data-tab`/`data-panel` targets must resolve. Add the shadowing scan when it earns its place.

---

## Browser truths — measured on the real chassis (2026-09-06)

Every one of these was invisible to `node --check`, to the container gates and to reasoning about the
code. They came out of running a full Prism in Chromium (vendored qry/Shoelace/Lucide, the Service
Worker serving a real book, and a bench `/api/convert` shelling out to the engine). Build that
harness before debugging a tool: it is the only place these speak.

**`openFrame` must not spend its listener on `about:blank`.** An iframe fires `load` for its INITIAL
`about:blank`, before it navigates, and a `{once:true}` listener is consumed by it — `onReady` then
runs against an empty document and never again for the real page. Since `bindFrameInput` raises the
`frame` event from `onReady`, every tool bound its picker to a blank document, and a page that mounted
while a tool was active was DEAD to that tool: no picker, no gizmo, no crop, silently. The only reason
tools worked at all is `onEnter`'s sweep over frames already loaded. Measured: three frames, three
events, all `url=about:blank svg=false`. Wait for a real document, and stay subscribed — a frame
reloads (`book.refreshPage`) and must be re-bound; handlers mark their own document, so a repeat is
idempotent by contract.

**The chassis replays `frame` on the way OUT of a tool too.** A handler that dresses a page
unconditionally puts its chrome back the instant `onLeave` removed it. Dress only while the tool is
up; clean otherwise.

**`ocd.js pageShell` must not write a `<!DOCTYPE html>`.** The engine's reader is XXE-hardened
(DOCTYPE disallowed outright, FORMAT §A7), so a container holding a page ANY tool had persisted died
on the next export with `SAXParseException: DOCTYPE is disallowed` — the tool blamed for the writer's
byte. It is optional in XHTML5 and the engine's own writer never emitted one.

**An `<img>` is draggable by default, and a native drag hijacks a pointer gesture.** Raster
thumbnails made every tile a native drag source: the browser took the pointer, `pointermove`/`up`
never arrived (so nothing moved), and the body drop zone lit up with "Drop your .epub" over the
reorder. Measured: one `dragstart` and ten `dragover` per attempt; zero after `draggable=false`,
`-webkit-user-drag:none`, a `dragstart` that preventDefaults, and `preventDefault()` on pointerdown.

**Selection and drag are ONE gesture, decided at `pointerup`.** Splitting them across `click` and a
captured pointer sequence breaks selection: with the pointer captured on the host, the click that
follows is dispatched to the HOST, so `e.target.closest('.tile')` finds nothing. Capture only once a
drag has really started.

**A pointer gesture must carry its own page.** In scroll mode the page under the pointer and the page
the observer calls current are routinely different — measured, dragging a crop on page 4 cropped
page 3. Ribbon commands have no pointer and act on the current page; a gesture passes its index.

**`book.js` captures `window.prism` at module scope.** A static `import` of it from the CHASSIS puts
it in the chassis's own module graph, evaluated before the chassis body — so the seam does not exist
yet (`Cannot read properties of undefined (reading 'state')`, rail silently empty). Tools get away
with a static import because their `<script>` tag runs after the chassis published the seam. The
chassis imports shared modules **dynamically**, inside `main()`.

**A point inside a frame reaches the chassis through the frame's OWN transform.** Four places did
`frameRect.left + x * zoom`, which holds while a frame is only scaled and breaks the moment one is
turned: `getBoundingClientRect()` then returns the axis-aligned box of a rotated element, so the
offset AND the factor are wrong. Measured on the same page point: drift 0 unrotated, **561 px** at
90°. `P.frameToClient(f, x, y)` is the one rule — `new DOMMatrix(getComputedStyle(f).transform)`
applied to the point, plus the card's origin (untransformed, frame at its 0,0, `transform-origin: top
left`). Consumers: Reveal's caption, the ctrl-wheel zoom anchor, the touch anchor, the search jump.

**A grid whose columns stretch cannot compute a pixel height.** With `minmax(150px, 1fr)` a tile is
not 150 px wide, so a height derived from that nominal width is right only by accident and every page
wears the same box. Give the tile an `aspect-ratio` from the page's VISUAL size.

**`ocd.js` writes the ENGINE's numbers.** `F` is `JxNum.fmt`: four decimals, trailing zeros trimmed,
an integer without a point, no `-0`. It rounded to two until 2026-09-06, so the client and the engine
stated the same geometry differently and a client-placed element could not survive a round trip —
measured, a run authored at `x=123.45678 size=11.13579` was written `123.46 / 11.14` and read back
0.0032 / 0.0042 pt off; at four decimals it reads back exactly and the authored container satisfies
`write(read(x)) == x`.

**A cache key must state what the thing IS, not merely which page it is of.** Keyed on the member path
alone, a picture drawn from a page's old shape satisfied a request for its new one: a rotation writes
the member, a watcher repaints from state not yet updated, the stale picture goes back in the cache,
and the correct repaint finds a hit. Key on `path | w×h @ rot / width`.

**The client's AUTHORING path had never been exercised, and it was broken in three places.**
`OcdPage.text()` has no caller in Prism, so nothing had ever written a run and read it back. Driving it
end to end (author → persist → export → engine → PDF) found, one at a time: `book.page()` handed a shim
doc with **no fonts** (`no font registered`, so the API could not even be reached — it parses
`pages/f.svg` once per book now); the run carried no **`data-fs`**, so the reader inferred 0 and the
text was written INVISIBLE while its glyphs and its `data-u` round-tripped perfectly; glyphs with no
outline were skipped without a **`data-b`** entry, truncating the tail; and `book.persist` had its OWN
serializer, lacking `OcdPage.xhtml()`'s rewrite of `#fN-gid` → **`f.svg#fN-gid`**, so the run pointed at
defs the same pass had just stripped. Each failure is silent, and each survives every gate that looks
at bytes or at the model. An authoring API with no caller is an API nobody has ever checked.

**Never pass text through a process ARGUMENT in the bench.** A JVM decodes argv with the platform
charset, so an accented character arrives as U+FFFD: an `É` silently dropped out of a font the harness
asked for, and the printed line read `crit dans Prism`. The product was fine — `WebApp.query` decodes
UTF-8 — so the whole finding was the harness lying about the thing it was measuring. Characters go
through a FILE (`@path`, read as UTF-8). Checking which side is at fault before believing a defect is
the only reason this cost minutes instead of a wrong fix in the engine.

**A CSS class prefix is HALF of a contract.** `trace.js` draws a caption whose rules
live in `prism.css`, chassis-level because it floats over the page frame. Renaming the module's prefix
alone left the element unstyled — and an unstyled tooltip is not invisible: measured `display:block`,
**1500 × 438**, `pointer-events:auto`, opacity 1 whether on or off, i.e. a block over the app that
swallows clicks. Rename both sides or neither, and check the computed style rather than the screenshot.
(The module is `trace.js` since 2026-09-07: the tab folded into Analysis as *Trace* long ago, and Redact
now ships a *Reveal* that does strip paint off hidden content. Augment's `type:"reveal"` spoiler keeps
the word — it is PERSISTED in containers, so renaming it is a format change.)

**The OTHER two apps serve themselves** (everything vendored under `/shared/`, nothing fetched): start
the server and drive it in ONE shell — a background server does not survive between tool calls, and
stop it by its PID. Measured once each: the Inspector opens a PDF and
answers on all four tabs (COS trailer, `Helvetica Type1 54 glyphs not embedded`, no image XObjects, the
four rasterizers), Jexter normalises one (`1.9 KB → 9.8 KB (+429%) · selectable text READY`), 0 console
errors either side.

**A toast is TRANSIENT — watch for it, do not look for it.** Querying the toast (`sl-alert` then, `.qry-toast` now) after the fact found an
empty document and reported "no message" for a message that had been shown and dismissed. Install a
`MutationObserver` on `document.body` before the action and collect them as they appear. And make the
bench answer errors the way `WebApp.error` does (`{"error": <exception message>}`), or a reading of a
failure toast is a reading of the harness.

**A picker owes the reader a HOVER, and a modifier must be bound on both sides.** Clicking a paragraph
is clicking blind — the pointer is over glyphs and the thing taken is the block the analysis produced.
Outline the candidate under the pointer (solid where a placed zone is dashed; the two states of one
shape need no legend), in its OWN `data-ui` layer at the SVG root, never inside the hovered element.
The modifier that changes what a click takes must change the highlight too: bind it in the frame AND on
`window`, because a frame is its own document and the pointer being over a page while the focus sits in
the chassis is the normal case, not an edge one. Testing it: aim at a RUN, not at the centre of the
block — the centre often falls between two lines where there is no run, and Shift then correctly
changes nothing, which reads exactly like a broken modifier.

**A BOOLEAN in a ribbon is a command that stays lit, not a switch.** `active` already says "on"
everywhere else — pick modes, page scope, Reveal — so a switch is a second grammar for one fact, and the
heaviest object in a row of line icons. The `switch` field kind was removed rather than left with no
consumer. (Wiring the toggle as a command is also what surfaced that it did nothing: see the `patch`
note in SKILL.md.)

**A ribbon CONTROL is built on the command's rhythm.** The control goes where a button's icon goes and
its caption where the button's label goes — same box, same `min-width`, same type. A bare swatch or a
switch dropped into a row of two-line commands is a different grammar in the same sentence, and no
amount of spacing fixes that. The constraint is arithmetic: the caption, the gap and the padding are
the button's, so the control itself gets the ICON's height (1.15rem) and no more — at 1.9rem the row
went to **84 px**. A wide text field cannot live in 1.15rem, so it takes the full box and NO caption
(its placeholder is its name, and it stands beside its own verb). Measured: every item of Redact's row
58 × 43 px at the same top, ribbon 72 px. And style the colour input as a SWATCH — native border and
padding off, a rounded chip that is the colour, held by a 1px ring — or a white mark (a real case)
disappears into the row; checked in both themes.

**Nothing in a ribbon may WRAP — it grows the whole row.** The slot stretches its children, so one
folded item takes every group up with it: Pages' help line squeezed to 75 px of width folds onto ten
lines and the row goes **72 px → 185 px** below 1200 px of viewport (Redact, once given a help line,
259 px — its row is longer). A row that does not fit scrolls sideways; that is what the slot's
`overflow-x` is for. `white-space:nowrap` on labels, group names and switches; the help line is the one
item allowed to SHRINK (`flex:0 1 auto; min-width:0`, ellipsis, sentence kept in its `title`), so the
commands never get pushed out of reach by a note about a gesture. Verified 72 px on all five tools from
1400 px down to 520 px, drawer open or shut.

**Measure the BEFORE tree, not the one you edited.** `prism_bench.mjs`'s `serve()` hardcoded
`ROOT/site`, so two A/B runs against a second directory silently measured the same build twice — and
the first reading said a pre-existing defect was mine. It honours `BENCH_SITE` now. An A/B that cannot
name which tree it served is not an A/B.

**A gesture on a page the viewer TURNS lands where it looks — measured, 2026-09-07.** The stored page
is unrotated and the chassis turns the FRAME, so a drag crosses a 90° CSS matrix the page's SVG knows
nothing about. It cancels: pointer coordinates and `getScreenCTM()` are both in the frame's OWN
viewport. Measured on `/Rotate 90` and `/Rotate 270` pages — a drag asked at svg `55,116 → 195,134`
gives the zone `55,116,140×18`, **drift 0**, the anchor holds on a handle resize, and Apply removes
exactly the glyphs under it on exactly that page. Note this is the OTHER direction from
`frameToClient`, which maps frame → chassis and does NOT cancel (561 px at 90°). Build the fixture
with pikepdf (`page.Rotate = 90` on a portrait MediaBox): reportlab's `setPageRotation` swaps the
MediaBox instead, which is a different case and not the one that matters.

**A reloading frame answers nothing, and it looks like a defect.** Probing a page straight after an
Apply (`book.refreshPage`) read a document mid-reload: every string missing, and the first run
reported a neighbouring line as redacted too — a defect that did not exist. Wait for the frame to be
BACK (`waitForFunction` on its run count), never `waitForTimeout`, before believing what a page says
about itself.

## `shot.js` — one small picture of a page

`shared/web/js/shot.js` is the authority for "a thumbnail of page i", used by the rail and by the
Pages light table. A thumbnail used to be a live page frame — a whole DOCUMENT, its DOM, styles and
the book's glyph library, mounted and torn down as a list scrolls or re-renders. With it went the
1.5 s polling interval in `book.js` that reloaded dirty thumbnails: the document authority was
reaching into `#page-list` (the reader's chrome) and polling for a change `onChange` already
announces.

**The fonts are the whole difficulty.** The format shares them on purpose — once in `pages/fonts.svg`,
referenced externally — so a reader fetches the library once per book. But an SVG loaded as an IMAGE
is sandboxed and cannot reach it, so a raster must carry its glyphs inside. Measured against a 4.1 MB
library:

| a page rasterized with… | |
|---|---|
| the whole library embedded | **270 ms** |
| the same, defs decoded once for the book | ~215 ms — the cost is the browser PARSING it, not our string work |
| only the glyphs the page uses (28, 13 KB) | **10 ms**, identical ink (62 370 px) |
| building that subset by scanning the library per page | 41 ms — so INDEX the library once (id → def); the subset is then lookups |

Cache: keyed per member path + geometry, dropped on `book.onChange`, bounded (LRU, blob URLs revoked
on eviction), cleared when the book changes. Measured on a 6-page book: 0 iframes in the rail, the
light table ready in 143–300 ms because the rail had already drawn three of its pages.

On a REAL heavy book (24 pages of CJK, 1936 glyphs / 1.87 MB in `f.svg`, ~419 per page, a photograph
every third page): index 11 ms once, subset 0 ms, decode the 517 KB page SVG 32 ms, draw + encode
11 ms; end to end the rail's first three thumbnails 3.6 s from a cold load (SW boot + a 1.4 MB unzip
included), a cold raster 119–165 ms, twelve tiles 2.1–2.3 s, 0 iframes. Build such a fixture before
trusting a number taken on a light one.

**Do not downscale a page's images for the thumbnail.** It looks obviously right and the measurement
refuses it: on a page carrying a 65 KB photograph, inlining it whole cost 2 ms and 56 KB of string for
a 56 ms raster, while decoding and re-encoding it at thumbnail size cost 16 ms to save those 56 KB —
65 ms, worse. A document's pictures are already compressed; the time goes into parsing the page's
glyph outlines.


## Open / Export — the user's files

Open is the file input (Ctrl O, `#m-open`, or a drop): a PDF or an image goes to the engine
(`P.hooks.openSource`), an OCD-EPUB opens as it is. There is no save-in-place: what leaves the
machine is an Export, and an OCD export keeps everything (augmentations ride the container as a
passenger). `engine.js` `exportAs` = qry's `saveFile(build, name)`: the save dialog opens FIRST, while
the click still allows it, and `build` runs once a place is chosen — the augmentation question
inside it (a "no" throws, qry has not opened the writer, the picked file is untouched), then the
conversion with any pending redaction applied. An OCD export emits `export`, which clears Augment's
dirty flag. Headless, the native picker is refused at once and qry falls back to a download, so the
bench needs no stub (`augwarn` / `augocd`).
