jexter (Sugarcube jexter) — technical overview · Typst source
=============================================================

Build:  pip install typst   (or the typst CLI)
        typst compile jexter.typ jexter-overview.pdf

OCD = Open Canonical Document.
Brand mark: fig/jexter-mark.svg (green — THE jexter identity).
Figures: fig/*.svg — hand-authored, embedded via image(); edit freely.

Fonts are NOT vendored here: they are large binaries, and shipping them drags a
redistribution obligation into a repository that has no other reason to carry
one. The document names three families; install them once and Typst finds them:

  Lora        serif, body text      Google Fonts — SIL OFL 1.1
  Poppins     sans, headings + UI   Google Fonts — SIL OFL 1.1
  DejaVu Sans Mono   code           already on most systems

  fonts.google.com/specimen/Lora · fonts.google.com/specimen/Poppins

Install them system-wide, or drop the .ttf files in a folder and point Typst at
it:  typst compile --font-path <dir> jexter.typ jexter-overview.pdf

Typst substitutes silently when a family is missing, so if the output looks off,
check the font list first — that is almost always the cause.

The compiled PDF is not committed: a binary in git is re-stored whole on every
rebuild. It ships as a GitHub release asset instead.
