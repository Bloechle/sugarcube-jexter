# Contributing to jexter

Thanks for taking the time. Bug reports, reproductions and focused patches are
all welcome.

## Before you write code

**Open an issue first** for anything beyond a typo or an obvious one-line fix.
The project has a small number of load-bearing invariants (see `AGENTS.md`), and a
patch that violates one of them cannot be merged however good it is. A short
discussion up front saves the work.

The most useful contribution is often not a patch at all: **a PDF that jexter
gets wrong**, with what you expected and what you got. Attach the file if you
can share it, or a minimal one that reproduces the same failure.

## The invariants

These are not style preferences — a change that breaks one is reverted:

- **Fidelity is a gate, not a goal.** The analysis layer is *additive*: it sets
  roles, reorders the content array into reading order, and builds an `OCDStruct`
  tree by reference. It never repaints. The pixel diff against the PDFBox
  reference raster must stay at zero.
- **One authority per concern.** One pass owns lines, one owns word spaces, one
  owns reading order, one owns running furniture. If your change means two places
  decide the same thing, it is the wrong change.
- **Geometry first, no lexicon.** The heuristic pass reads geometry only — no
  word lists, no regexes on content. This is what keeps it robust on OCR output
  and on languages nobody tested.
- **Measure before committing.** `javac` at 0 errors / 0 warnings (`-Xlint:all`),
  then a runtime check on a real document. Claims without a measurement do not
  land.

## Building

No Maven required to try it — the two dependencies are vendored in `lib/`:

```bash
javac -d out $(find src -name '*.java') -cp "lib/*"
java -cp "out:lib/*" sugarcube.jexter.ui.prism.Prism
```

`node nimbus.cjs` opens a local build deck that does the same thing plus
packaging. `mvn -q package` works too; both target Java 21 — the floor, so a jar sent to anyone runs on the JDK they already have.

## Sign-off — this one is required

Sugarcube jexter is dual-licensed: AGPL-3.0 for open use, and a commercial licence for
those who cannot accept copyleft. That second half only works if a single entity
holds the rights to the whole codebase — so contributions need an explicit grant.

Add a `Signed-off-by` line to every commit (`git commit -s`), which certifies the
[Developer Certificate of Origin](https://developercertificate.org/): you wrote
the patch, or you have the right to submit it.

Then, in your first pull request, include this sentence:

> I grant Sugarcube Information Technology Sàrl a perpetual, worldwide,
> non-exclusive, royalty-free licence to use, modify, sublicense and relicense my
> contribution, including under commercial terms, and I confirm I have the right
> to grant it.

You keep the copyright on what you wrote. Without that grant, the contribution
can be merged only under the AGPL and would make the commercial licence
unenforceable — so it cannot be accepted.

## Pull requests

- One concern per PR. A refactor and a fix in the same branch cannot be reviewed.
- Say what you measured, not just what you changed.
- Code, comments and documentation in English.
- Documentation lives in `doc/`, one file per subsystem. If your change makes a
  document wrong, fix the document in the same PR — a stale document is a bug.

## Security

Do not open a public issue for a vulnerability. See [SECURITY.md](SECURITY.md).
