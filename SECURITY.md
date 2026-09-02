# Security policy

## Reporting a vulnerability

**Do not open a public issue.**

Use GitHub's [private vulnerability
reporting](https://github.com/Bloechle/sugarcube-jexter/security/advisories/new)
(the *Security* tab → *Report a vulnerability*), or write to
**contact [at] sugarcube.ch** with `jexter security` in the subject.

Please include the affected version or commit, what an attacker gains, and a
file or snippet that reproduces it. A malformed PDF that triggers the issue is
worth more than a description of it.

Expect an acknowledgement within a few working days. This is a small project —
there is no 24/7 rotation, and no bug bounty. Credit in the advisory is offered
unless you prefer otherwise.

## What is in scope

The engine parses untrusted input by design: a PDF is an attacker-controlled file
format, and the parser is the attack surface. In scope:

- Anything that makes parsing a crafted PDF escape the process: file writes or
  reads outside the output path, command execution, or a network call.
- Unbounded resource consumption from a small input — decompression bombs, deeply
  nested structures, pathological font or path data.
- Anything that lets embedded content reach a viewer with more privilege than it
  should have, including through the OCD-EPUB output.
- Any credential or key leaking into an output document, a log, or an
  `_ai-*` context bundle.

Out of scope: vulnerabilities in Apache PDFBox itself — report those to the
[Apache PDFBox project](https://pdfbox.apache.org/) — and findings that require
an attacker who already controls the machine.

## Two things worth knowing

**No JavaScript is ever embedded in a generated EPUB.** Pages are data;
behaviour lives in the viewer. If you find a way to make jexter emit script into
an output document, that is a vulnerability, not a feature request.

**The LLM refinement pass is optional and off unless a model is bound.** It reads
the document and never executes anything from it, and the structure it returns is
applied by reference only — it cannot repaint. If you find a path where model
output reaches disk or the renderer unfiltered, report it.

## Supported versions

The project has no release branches yet: fixes land on `main`, and the latest
commit is the supported version.
