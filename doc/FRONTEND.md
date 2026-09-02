# FRONTEND — the convert contract and the tool architecture

One HTTP convert contract, three tiers, one shared front-end chrome with a thin
per-environment seam (`backend.js`). The same Java facade —
`sugarcube.jexter.write.Conversion` — is the engine on every tier, so a target or
an option is added **once**, in jexter, and every tier inherits it.

```
  DESKTOP   jexter.js ─ backend.js ─▶ Prism (Java, com.sun.httpserver)          stateless convert engine (local)
  WEB       jexter.js ─ backend.js ─▶ qry-server ─▶ sugarcloud (Tomcat WAR)     gateway (auth+library) + engine
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
- `caps.openByPath` (desktop) → **Recents** in the brand menu, re-opened off disk
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
  edit.js       (tool: augmentations)       (no qry-api.js — the desktop seam doesn't use it)
  jexter.js     (tool: engine seam, imports ./backend.js)
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
   childless svg self-closes under the browser serializer).
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

3. **Tools** — one `<script>` tag each (`edit.js`, `jexter.js`);
   remove the tag and the tab is gone. A tool registers itself
   (`P.registerMode`), subscribes to chassis events (`P.on`: `'mode' · 'book' ·
   'close' · 'page' · 'frame'`, multicast), and may offer PROVIDERS to the
   chassis (`P.hooks`: `openPdf · highlight · ttsNodes` — singular by design).
   Anything a tool draws for itself carries `data-ui` (+ `data-z="under|over"`)
   and never reaches the container. The chassis owns exactly one write
   primitive, `P.putMember`; `book.put` is its hub.

Layers in pages are full-page strata (`FORMAT.md` §B4b); the engine-imported
base is the implicit source stratum — never wrapped.

Wiring: drop the shared files plus the matching `backend.js` into each web dir;
both engines already speak the contract, and the gateway relays it to sugarcloud.
The two `index.html` differ only in which panels they ship (the desktop
model-connect form is JS-injected); long-term they can converge to one with
`[data-cap]` attributes.

## Validation

`prism.js` / `jexter.js` and the seams pass `node --check`, but that is syntax only — the
chrome genuinely needs a **browser smoke test against running engines** (a local
Prism, and qry-server → sugarcloud): open a PDF (`to=ocd`), reconvert with
options (the `/api/options` round-trip), export (pdf / epub-prism / epub-fl / html / md / doctags), the
Contents panel + manual structure, Analysis + reading; then desktop-only connect a model +
refine + the F2 server log + recents, and web-only sign in + the library. The OCD
round-trip fidelity bar (≤ 4e-6 mean channel diff) is unaffected — the chrome
never touches geometry.
