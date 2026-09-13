# FRONTEND — the convert contract and the tool architecture

One HTTP convert contract, three tiers, one shared front-end chrome with a thin
per-environment seam (`backend.js`). The same Java facade —
`sugarcube.jexter.write.Conversion` — is the engine on every tier, so a target or
an option is added **once**, in jexter, and every tier inherits it.

```
  DESKTOP   engine.js ─ backend.js ─▶ Prism (Java, com.sun.httpserver)          stateless convert engine (local)
  WEB       engine.js ─ backend.js ─▶ qry-server ─▶ sugarcloud (Tomcat WAR)     gateway (auth+library) + engine
```

## The convert contract

### Shared routes — desktop Prism **and** sugarcloud, identical

| Method | Route | Body / query | Returns |
|--------|-------|--------------|---------|
| `POST` | `/api/convert?to=<target>[&<opt>=<val>…]` | a **PDF** (`%PDF`) → import+export · or an **OCD-EPUB** (`PK` zip) → re-export | the artifact bytes (media type + `Content-Disposition` from jexter) |
| `GET`  | `/api/options` | — | `{ aiBound, aiModel, aiProvider, aiEffort, options:[ {key,label,help,type,group,def} … ] }` |
| `GET`  | `/api/targets` | — | `{ targets:[ "svg","pdf","epub","epub-reflow","html","md","doctags","ocd","stages" ] }` |
| `GET`  | `/api/health`  | — | `"ok"` (liveness) |

- **`to`** is the only consumed query key; every other key is forwarded verbatim
  to `ConvertOptions` — the introspectable registry, serialized once by
  `ConvertOptions.optionsJson` and shared by both engines, so they emit a
  byte-identical option shape.
- **Input is sniffed, never declared:** `%PDF…` ⇒ import + export; `PK…` (OCD-EPUB)
  ⇒ re-export. So `to=ocd` on a PDF is *open*; `to=ocd&<opts>` on a PDF is
  *reconvert*; `to=pdf|epub|…` on either is *export*. There is no separate
  open / ocd / reimport / export route.
- **Stateless.** Neither engine retains the document; the front-end holds the
  source bytes and the `.ocd.epub` and posts what each call needs. `defaultStructureId`
  is likewise a client-side property carried in the `.ocd.epub` the front holds.
- **Refine is not its own route** — logical-structure refinement rides the shared
  call, `POST /api/convert?to=ocd&refineStructure=true[&llmModel=…]`, and the
  bound LLM runs inside the engine, identically on desktop and cloud.

### Desktop-only routes (Prism — the local Java process)

| Method | Route | Body / query | Returns |
|--------|-------|--------------|---------|
| `POST` | `/api/ai/config` | `{ provider, endpoint, key, model, effort, keyless, clear }` | bind / unbind a model |
| `GET`  | `/api/log` | — | Server-Sent Events: `JxLog` records (the **F2** console) |
| `GET`  | `/api/alive` | — | SSE heartbeat. `WebApp` runs a watchdog that exits the process if no client ever connects; the chrome opens this at boot so the local server stays up. |

`/api/ai/config` is desktop-only because the cloud's model is configured
server-side. The desktop `/api/convert` also accepts `?url=<http(s)>` (the engine
fetches it server-side — no browser CORS) and
`?path=<local file>` (re-open a recent file off disk — `caps.openByPath`). The
cloud reads the request body only.

### Web-only routes (qry-server gateway)

The gateway relays the four shared routes to sugarcloud (adding the per-client
key, which never reaches the browser) and adds the user layer:

| Method | Route | Purpose |
|--------|-------|---------|
| `GET`  | `/api/health` | gateway probe — `{ ok, backend, requireAuth, configured }` (also pings the engine) |
| `GET`  | `/api/targets` · `/api/options` | proxied from sugarcloud |
| `POST` | `/api/convert` | proxied from sugarcloud (streamed both ways) |
| `*`    | `/api/auth/*` | session + OAuth (qry-server, Infomaniak kAuth) |
| `GET`/`POST`/`DELETE` | `/api/data/documents[/:id]` | per-user library **metadata** (owner-scoped) |
| `PUT`/`GET` | `/api/library/source/:id` | the document's source PDF (owner-gated) |
| `DELETE` | `/api/library/doc/:id` | source file + metadata together |

The cloud has no LLM and no in-page log: the web tier reports `ai:false`,
`logStream:false`, and adds `auth:true`, `library:true`.

## The seam — `backend.js`

The **only** file that differs between environments. It exports `env`
(`'desktop'` | `'web'`) as the single discriminator, plus `caps` for feature
gating. The chrome imports it (`import * as backend from './backend.js'`) and
gates UI on `env` / `caps` and the presence of each panel's DOM — never on a
route or transport.

```js
env       // 'desktop' | 'web'
caps      // { reconvert, options, targets, health, ai, auth, library, logStream, openByPath }
convert(src, to='ocd', opts={})  ->  { blob, bytes, filename, mediaType }   // the one workhorse
options() · targets() · health()
// desktop extras
ai?       { config(cfg) }            // refine itself = convert?to=ocd&refineStructure=true
convertUrl? · convertPath? · logStreamUrl? · aliveUrl?
// web extras
auth?     { me, login, logout, password, register, config, oauthUrl }
library?  { list, create, get, update, removeDoc, getSource, putSource }
```

`open`, `reconvert`, `export` are all just `convert(...)` with a different `to` /
body — no dedicated methods, no dedicated routes. One verb, sniffed input,
capability-gated extras.

### One options model

The options panel calls `backend.options()` and builds its controls from the
returned registry (grouped by `group`, seeded from `def`, overridden by the
front's `state.opts`). Both engines return the same registry
(`ConvertOptions.optionsJson`), so there is no hardcoded client-side option spec.

### Capability gating

The shared chrome reads `env` / `caps` and toggles panels (`applyCaps`); it never
reads a cap to choose a transport (that is the seam's job).

- `caps.ai` (desktop) → the **AI** tab: connect/unbind a model
  (`backend.ai.config`) and refine (`convert?refineStructure=true`). On the web
  the AI tab is hidden; the structure **editor** and the `manual` structure stay
  (they are client-side and shared).
- `caps.logStream` (desktop) → the **F2** console also subscribes to
  `backend.logStreamUrl` (server log) on top of the shared JS capture.
- `caps.openByPath` (desktop) → **Recents** in the header menu, re-opened off disk
  via `POST /api/convert?path=…`. Paths are recorded only when the desktop webview
  exposes `file.path`; a plain browser records none (web sets `openByPath:false`,
  so the slots stay hidden).
- `caps.auth` + `caps.library` (web) → the auth dialogs, the per-user library, and
  "Save to library" in the structure editor. Hidden on desktop, where the editor's
  Export-structures.json stays.

## Deployment

The only per-environment file is `js/backend.js`; everything else is
byte-identical.

```
SHARED (both sides)                      PER ENVIRONMENT
  index.html · prism.css                  DESKTOP — served by Prism from .../ui/prism/web/
  prism.js      (the chassis)               backend.js   ← desktop seam (local engine; caps: ai, logStream, openByPath)
  augment.js       (tool: augmentations)       (no qry-api.js — the desktop seam doesn't use it)
  engine.js     (the SEAM: PDF import, text layer, fonts, structures, export)
  analysis.js   (tool: overlays, inspect, node tree — imports what it needs from engine.js)
  trace.js      (Analysis' Trace overlay: point at anything, read-only)
  redact.js     (tool: audit · pick zone/block/page · find via to=zones&match · items persist as ocd/redact.json,
                 previewed opaque at once, applied by the engine on Export or Apply; an unclean result is refused)
  prism-sw.js   (Service Worker)
  /shared/js/ocd.js  (the grammar: read, create, adopt, build)
  /shared/js/book.js (THE document authority — see below)
                                            WEB — served by qry-server from public/
                                            backend.js   ← web seam (gateway → sugarcloud; caps: auth, library)
                                            qry-api.js   ← imported by the web seam (auth + documents client)
```

## The tool architecture — three layers, one authority

The displayed DOM is the source of truth; the epub file is transport. Three layers:

1. **`ocd.js`** — the client grammar: `loadOcd` (read), `OcdDoc.create/open`
   (author), `OcdPage.adopt` (live page), `pageShell` (THE page member shell —
   pages are REBUILT around the serialized `<svg>`, never string-spliced: a
   childless svg self-closes under the browser serializer), `pageSvg` (a page made
   STANDALONE: glyph refs localized, font defs embedded, for a consumer with no
   base URL). The shell carries **no `<!DOCTYPE html>`**: the engine's reader is
   XXE-hardened and refuses one outright, so a container holding a page written
   with a DOCTYPE dies on the next export. Numbers go through `F`, which is
   `JxNum.fmt` — four decimals, trailing zeros trimmed: the client and the engine
   are two writers of one format and have to agree on the digits.

   A page's GEOMETRY lives on its root and nowhere else (`FORMAT.md` §B2): the
   crop IS the `viewBox`, the rotation IS `data-rotate`, and the coordinate frame is
   the media box (`data-mediabox`). So a client that crops or turns a page rewrites
   one element, and one that PLACES something must take its flip from the frame,
   never from the viewBox.
2. **`book.js`** — the document authority. Every tool speaks `book.*` and never
   touches the chassis for document operations:

   | Concern       | Verbs |
   |---------------|-------|
   | Lifecycle     | `open · close · reload` (reload = re-zip members → openEpub, the canonical rebuild after structural change) |
   | Read          | `get · json · isOpen · isOcd · member · pagePath` |
   | Mutation hub  | `put` (commits memory + SW, notifies `onChange(cb(path))`) `· opf · declare` (idempotent manifest add — THE manifest authority) |
   | Resources     | `addImage · addMedia · removeResource` |
   | Structure     | `addPage · registerLayer` |
   | Displayed DOM | `page (adopted OcdPage) · eachPage · eachFrame · frameDoc · onFrame` |
   | Projection    | `persist · flush` (edited pages only; data-ui chrome stripped) |

3. **Tools** — one `<script>` tag each (`augment.js`, `editor.js`, `analysis.js`,
   `redact.js`, `pages.js`); remove the tag and the tab is gone. A tool registers
   itself (`P.registerTool`, `experimental: true` while it is not ready — shipping
   it is deleting one word), declares its commands (`P.ribbon(id, groups)`), keeps
   what it READS in its drawer, subscribes to chassis events (`P.on`: `'tool' ·
   'book' · 'close' · 'page' · 'frame'`, multicast), and may offer PROVIDERS to the
   chassis (`P.hooks`: `openPdf · highlight · ttsNodes` — singular by design).
   Anything a tool draws for itself carries `data-ui` (+ `data-z="under|over"`)
   and never reaches the container. The chassis owns exactly one write
   primitive, `P.putMember`; `book.put` is its hub.

   The chassis lends three things no tool should re-derive:

   | Verb | For |
   |------|-----|
   | `P.setPageBox(idx, {pw, ph, rot})` | a page's geometry changed — the chassis re-states what the layout reserves for it (page record, stage card, rail thumbnail) |
   | `P.frameToClient(f, x, y)` | a point inside a page frame, in chassis coordinates — through the frame's OWN computed transform, because a frame can be scaled AND turned |
   | `shot.js` `shots.url(idx, geom)` | a small picture of a page, drawn once and cached per member — the rail and the Pages light table share it, and a thumbnail is never a live document |

   `frame` is replayed for every mounted page when the tool changes — on the way
   OUT as well as in — so a handler must dress a page only while its tool is up.

   **The ribbon is DECLARED, commands and controls alike** —
   `P.ribbon(id, groups, help)`. A group's items are commands
   (`{icon, label, title, on, active, disabled}`) or controls
   (`{field: 'search' | 'color' | 'number' | 'select', value, label, options, on, live,
   enter, list, unit, min, max, step}`, built on the command's rhythm: the control where an icon goes,
   its caption where a label goes — a BOOLEAN is a command that stays lit, not a switch), so a setting sits in the group whose commands
   read it instead of at the far right of the row. A control's VALUE comes from
   the declaration: the tool's state is the one authority, and since the chassis
   rebuilds the row on every declaration, a value read back from the DOM is a
   value that reverts. `help` is the one line a tool whose interaction is
   implicit owes the reader. Focus AND the caret survive a re-declaration
   (matched on `title`, which is unique within a ribbon).

   **Authoring a run: three clauses, each silent when missed.** `OcdPage.text()` is the client's
   writing surface, and it must state what the format states — measured, one by one, the day it was
   first exercised end to end: (1) **`data-size`** — without it the reader gets size 0 and the text is
   written invisible, saved, and correct in every other respect; (2) **every character accounted for**
   — painted as a `<use>` or recorded in `data-blanks`, or the reader aligns N−1 glyphs against N characters
   and truncates the tail; (3) **the SHARED library** — `fonts.svg#fN-gid`, never the page-local `#fN-gid`,
   whose `defs` are `data-ui` and are stripped on the way out. `book.persist` no longer serializes on
   its own: it calls `OcdPage.xhtml()`, because two serializers for one contract is one of them missing
   a clause — this one lacked (3).

   **Adding a font the container does not carry.** `POST /api/font` with the font file as the body,
   `?chars=` (blank = printable ASCII) and `?id=` the alias the CLIENT picks — it alone knows which are
   free (`book.freeAlias()`). The answer is a one-font `pages/fonts.svg`, emitted by the SAME writer the
   container came from, so `book.addFont()` merges a `<g>` into the shared library rather than learning
   a second grammar. A route and not a `ConvertOptions` because `/api/convert`'s body is the container:
   a second payload has nowhere to travel; the CLI keeps `--addFont=<path>`, same code, other door.
   **Never key anything on the alias**: the engine re-derives `fN` alphabetically on every write (a
   client's `f2` came back as `f0`), and the authority for *which font* is `data-font`.

   **Writing text is Edit's, not Augment's.** Augment attaches a SIDECAR baked at export; a run is page
   CONTENT, read by every projection — so it belongs to the tool that already changes the document, and
   the gizmo bound there picks the new run up the moment it lands. The ribbon's *Write* group is the
   text, its size, **Place** (arms one click: the text lands where you point) and **Font…** (a font file
   → `/api/font` → `book.addFont`, for the characters you typed). The click is taken in the CAPTURE
   phase, before the picker: the point is to write where you point, not to select what is already there.

   A container's font is a **SUBSET** — it carries the glyphs its pages used and nothing else — so
   writing *Premier jet* in a font imported for *Bonjour, Jean-Luc!* prints `re erje`, correctly and
   silently: the text is in the model, the ink is not. Edit names the characters that will not paint
   and points at Font…, which adds a file for exactly the characters typed.

   **A tool may change what the page SHOWS without changing the page.** Redact's *Reveal* is the
   pattern: a generated stylesheet (`data-ui`, so `persist()` strips it) whose rules are keyed on the
   ids the page already states, plus a `data-ui` overlay for anything that has no element to restyle.
   An ATTRIBUTE set on a content node would survive into the member on the next persist — only
   elements are stripped — so a view mode never writes one. A viewing question ("is this text really
   gone?") is answered by a view; an export option (`redactFill`'s alpha) is not the place for it.

   **A ribbon arms nothing without a document.** Every command and every mode of every tool is
   `disabled` while no book is open — a mode for a gesture you cannot make is as dead as the command it
   feeds. The footer already collapses to a status line for the same reason; this is that rule in the
   ribbon, and it is one rule in five tools rather than three tools stating it three ways.

   **`gizmo.js` is the shared handle set** — `createGizmo(svg, {pick, onSelect,
   onChange, onCommit, enabled, move, scale, rotate, uniform, snap, hover})`.
   It knows geometry and nothing else; the host says what may be picked and what
   the result means. `onSelect` is the edge a pure CLICK produces (neither
   `onChange` nor `onCommit` fires when nothing moves), and it fires for the
   host's own `select`/`clear` too — a host that calls back into the gizmo from
   it guards its own re-entrancy. Pages adjusts a crop with it, Redact a zone:
   one component, one set of space rules, and the four numbers of either live in
   the drawer on the shared `.px-fields` grid. The RECTANGLE is shared too —
   `boxOf · setBox · outerBox · clampTo · normBox · svgPointOf` are exported from
   the same module, because a host that puts one under the handles always needs
   exactly those. `setBox` writes FOUR decimals (`JxNum`'s rule), not the two the
   handles are drawn with: a handle is chrome, a rectangle is geometry a host
   commits to a page.

Layers in pages are full-page strata (`FORMAT.md` §B4b); the engine-imported
base is the implicit source stratum — never wrapped.

Wiring: drop the shared files plus the matching `backend.js` into each web dir;
both engines already speak the contract, and the gateway relays it to sugarcloud.
The two `index.html` differ only in which panels they ship (the desktop
model-connect form is JS-injected); long-term they can converge to one with
`[data-cap]` attributes.

## Validation

`prism.js` / `engine.js` / `analysis.js` and the seams pass `node --check`, but that is syntax only — the
chrome genuinely needs a **browser smoke test against running engines**. Everything the
client has got wrong so far was invisible to a syntax check and to reading the code: an
iframe's initial `about:blank` consuming a `{once:true}` load listener, a native `<img>`
drag hijacking a pointer gesture, a stretched grid column, a cache key that did not say
what it cached. Run the real chassis in a real browser (a local
Prism, and qry-server → sugarcloud): open a PDF (`to=ocd`), reconvert with
options (the `/api/options` round-trip), export (pdf / epub-prism / epub-fl / html / md / doctags), the
Contents panel + manual structure, Analysis + reading; then desktop-only connect a model +
refine + the F2 server log + recents, and web-only sign in + the library. The OCD
round-trip fidelity bar (≤ 4e-6 mean channel diff) is unaffected — the chrome
never touches geometry.
