# The Chromium bench — 22 scenarios, and what each one HOLDS

Prism is half the product and none of it is reachable from a Java gate: a module can parse, a container
can round-trip to the byte, every projection can be identical — and the tool can still be dark, or be
writing text at size zero, or leaking its own chrome into the document. Everything in this folder was
written because something silently broke, and each file exists to keep that thing from breaking again.

**None of these replaces the engine gates** (`test/run.sh` in the repo, the byte A/B, epubcheck). They cover
the other half: the browser.

## Running them

```bash
npm i playwright-core                       # + a Chromium; PLAYWRIGHT_BROWSERS_PATH points at it
python3 ../make_bench_fixtures.py /tmp/fix  # the documents (see its header)
for f in /tmp/fix/*.pdf; do java -cp "$CP" sugarcube.jexter.write.Conversion \
                                "$f" "${f%.pdf}.ocd.epub"; done
cp /tmp/fix/*.ocd.epub $BENCH_ROOT/site/    # the bench serves the site itself

BENCH_ROOT=/tmp/bench BENCH_FIX=/tmp/fix \
JEXTER_CP_FULL="$OUT:$REPO/lib/*" JAVA_BIN=$JDK21/bin/java \
PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node sweep.mjs
```

`prism_bench.mjs` is the harness: it serves `site/` (a copy of `ui/prism/web`, with `ui/shared/web` as
`site/shared/` — qry 3, fflate and the icons are vendored there, nothing to rewrite), answers `/api/convert`
and `/api/font` by shelling out to the engine, and exports `openPrism` / `drag` / `serve` / `watchToasts`.
`openPrism` opens in DEV mode unless told otherwise: Redact and Augment are dev tabs, and a scenario
driving one outside dev mode clicks nothing. `watchToasts(page)` collects qry's toasts as they appear
(`{ text, tone }`) — install it before the action. `formsvalue` needs a form: `BENCH_BOOK=/rich.ocd.epub`.

`inspect2.mjs` and `jexterapp.mjs` are the exception: they drive the OTHER two apps, which serve
themselves. Start the app and drive it **in one shell** — a background server does not survive between
tool calls:

```bash
java -cp "$CP" sugarcube.jexter.ui.pdf.PDFInspector --serve --port 7346 --web src/.../pdf/web &
sleep 9 && node inspect2.mjs
```

## What each one holds

| scenario | the thing it keeps from breaking |
|---|---|
| `sweep` | every command of every tool, clicked with a book and with none: 0 console errors, and **nothing is armed when no document is open** |
| `leaveclean` | every tool leaves the page as it found it — `data-ui` back to 0, injected stylesheets gone |
| `leak` | a page dressed with EVERY overlay at once, then exported: no chrome in the container, and it still round-trips |
| `gestures` | the gizmo on a zone: 8 handles, move, an anchored corner drag, a typed field |
| `pagescrop` | the same on a crop, whose numbers become a page's `viewBox` — 4 decimals, not 2 |
| `blockhover` | Block-mode hover, and Shift narrowing a block to the run under the pointer |
| `reveal` | the hiding techniques of `hidden.pdf` — under a box, white on white, transparent, invisible render mode — each found and each named |
| `falsepos` | ordinary documents: **0 flagged**. A checker that cries wolf gets ignored |
| `revealreload` | Reveal still tells the truth after the engine has rebuilt the frame |
| `cost` | what a per-run check costs — the number that decides an optimisation, on the 190-run page |
| `meta` | the metadata purge actually reaches the container (a patch must return every member it changed) |
| `repair` | a scan: the box is baked into new image bytes and the second audit is empty |
| `refusal` | a zone that only PARTLY covers a box: refused, with a message that says what to do |
| `authortext` | `ocd.js` authors a run and the ENGINE reads it back whole — `data-size`, `data-blanks`, `fonts.svg#`. It PROVES it now: it re-exports the container with the engine and compares the re-written run against what was typed (it used to print two dead attribute names and assert nothing) |
| `icons` | every `<i data-icon>` a ribbon or the chrome asks for actually DRAWS a `.qry-icon` — a name the set (`shared/web/js/icons.js`) does not register draws qry's dashed square and logs a console error; a hole in a row of line icons reads as a layout wobble, not as a typo |
| `groupstyle` | every pane that groups its rows uses its OWN heading class, and each of those classes is styled — checked by computed style, because a heading that lost its rule still shows its text |
| `fontmetrics` | the metrics `ocd.js` reads out of `pages/fonts.svg` ARE the ones the member states — they were all 0 for as long as the short v1 names sat in the parser, and both font panels drew fewer guides for it |
| `redactaudit` | the Audit tab folded into Redact: the verdict runs by itself, writes NOTHING to the container, Repair is the one crossing into the work list, and Apply makes the verdict be asked again |
| `writetext` | the same from the UI: type, add a font file, Place, click |
| `retype` | the font picker, rewriting a run in place, and ONE Ctrl+Z putting the old one back |
| `fontroute` | `/api/font` → `book.addFont` → a run in a font the container never had |
| `seamcut` | the `engine.js` / `analysis.js` boundary: every name that crosses it is live |
| `heights2` | the ribbon stays one height at every width, on every tool — it ASSERTS it now, and names the offending tool and the tallest item in its row. It used to print `forms ribbon=84px` next to seven 72s |
| `devtabs` | an unfinished tool is HIDDEN, never removed: absent from the bar outside dev mode, amber-dotted inside it, and `setTool` on a hidden id lands on home rather than stranding the reader |
| `formsvalue` | Forms' Value control keeps the selected field's name (as its placeholder, since a wide control takes no caption) and writes through |
| `readingorder` | read-aloud AND the search index read a page in READING order — `data-order`, not the DOM's — on a page painted bottom-up |
| `indexcost` | what the re-sort costs at open: the regex against a full DOM parse, and how many pages actually state an order (the substring test is the whole optimisation) |
| `title` | the window title follows the open document, and lets go when it closes |
| `augcheck` | Augment records a link, writes `prism/augment.json`, and the link is baked into the delivered EPUB |
| `animcheck` | Animate finds content in a zone at all — it looked for `<text>` on pages that carry none |
| `animleak` | the animation wrapper does NOT reach the member when another tool persists the page |
| `animcontent` | and unwrapping keeps the content: 18 runs and 822 `<use>` before and after |
| `efficacy` | every command of every tool: does anything OBSERVABLE change, or is it a no-op? |
| `inspect2` | PDF Inspector opens a PDF and answers on all four tabs |
| `jexterapp` | Jexter normalizes one, end to end |

## A fifth way this harness has lied

**A scenario that reads an attribute reads the CLIENT's opinion of itself.** `authortext` printed
`data-u` and `data-b` — names that died with grammar v2 — so it reported `null, null`, stayed green, and
let a real truncation bug live in the tree (SKILL.md, 2026-09-14). A client-side scenario has to close
the loop through the engine: post the container, read what comes back. And pick the right probe — `to=md`
projects the logical tree and never mentions an authored run, so it says nothing whatever the reader did.

## A fourth way this harness has lied

**An in-page dialog is not a browser dialog.** Augment's prompts are `sl-dialog`s, so a
`page.on('dialog')` handler never fires and the interaction stays half-finished — which reads exactly
like a tool that records nothing. It cost a wrong verdict ("Augment writes nothing") before the ids
`#ask-dialog` / `#ask-ok` closed the loop. Fill the dialog and click its OK.

## Reaching the EXPORT path — three things the harness must do

It was unreachable for a long time, and every export test passed by never running. Three gates, in order:

1. **`/api/health` must answer exactly `ok`** — `engine` is keyed on it, `exportAs` starts with
   `if (!engine) return`, and the jx-only affordances stay hidden. Answering JSON put the whole path,
   redaction-on-export included, out of reach.
2. **The jx-only buttons must be revealed** — `b.removeAttribute('hidden')` in the same `evaluate` as
   the click.
3. **`P.pickSave` must be stubbed** — it opens a NATIVE file picker, which throws headless, so
   `exportAs` returns before anything under test runs. Stub the chassis plumbing, never the subject.

`augexport3` (a non-OCD target warns and cancelling keeps the work) and `augexport4` (OCD does NOT warn,
because the engine carries passengers) are the two that hold this.

One benign artifact remains: the heartbeat `EventSource` logs a MIME error in this bench. It aborts and
nothing depends on it — but it is the bench's, not the app's.

## The one console error `sweep` reports, explained

Pages' **Left / Right / 180°** rewrites the page and rebuilds its frame, and the frame's in-flight
request for the PREVIOUS `blob:` URL is aborted — Chromium logs `net::ERR_ABORTED` (sometimes
`ERR_FILE_NOT_FOUND`, same family: a blob nobody references any more). It needs the tool to be entered
after others have run on the same book, which is why clicking Pages alone never shows it.

**Benign**: the rotation itself is correct (`efficacy` sees the DOM and the members move, `pagescrop`
and `gestures` stay green). It is named here so nobody spends an afternoon on it twice — and so that
a DIFFERENT error in that slot is visible instead of being filed under "the usual one".

## One thing left open

Driving Pages' whole ribbon at speed (350 ms a command) raised two `ERR_FILE_NOT_FOUND` once, on a
freshly built fixture set. Clicking the same commands one at a time with a pause raises none, so it
looks like a thumbnail frame reloading while its member is being rewritten — **but that is a guess, and
it has not been reproduced.** If `sweep` shows it again, that is the thread to pull.

## Three ways this harness has lied

Each cost real time, and each is now impossible to repeat by accident.

**A toast is transient.** Querying `sl-alert` after the action finds a dismissed element and reports
"no message". Watch with a `MutationObserver` set up BEFORE the click.

**Shoelace controls are not `<button>`.** `querySelectorAll('button')` finds none of PDF Inspector's
or Jexter's controls, so a driver clicks nothing and reports the app stuck. Ask for `button, sl-button`.

**Never pass text through a process argument.** A JVM decodes `argv` with the platform charset, so an
accented character arrives as U+FFFD: an `É` dropped out of a font the bench asked for and the printed
line read `crit dans Prism`, while the product (`URLDecoder`, UTF-8) was fine. Characters go through a
file. *Check which side is lying before believing a defect.*

## And one about aiming

Testing Block-mode Shift means aiming at a RUN, not at the centre of a block — between two lines there
is no run, so Shift correctly changes nothing and looks exactly like a bug.
