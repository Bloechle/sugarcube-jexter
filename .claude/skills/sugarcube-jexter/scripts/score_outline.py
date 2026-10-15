#!/usr/bin/env python3
"""
Structure-recovery axis: score jexter's heuristic heading tree against the PDF OUTLINE
(bookmarks) of a corpus of outline-bearing PDFs — enumerator recall + merge errors.

GT = the outline, NOT the tag tree (on Fedlex law PDFs the StructTreeRoot files most
`Art. N` headers as plain /P; many cc-* ordinances have no heading tags at all).

Config via env (all optional):
  FEDLEX_DIR   corpus root, globbed recursively for *.pdf   (default: /home/claude/fedlex)
  PDFBOX_JAR   shaded pdfbox-app jar                         (default: autodetect under FEDLEX build / cwd)
  JEXTER_CP    compiled engine classes (build/out)          (default: ./build/out)
  JAVA         java binary                                  (default: $JAVA_HOME/bin/java or `java`)
  MAX_ROWS     per-doc rows to print                        (default: 25)

StructDump must be compiled into JEXTER_CP first:
  javac -cp "$PDFBOX_JAR:$JEXTER_CP" -d "$JEXTER_CP" scripts/StructDump.java

Needs: pip install pikepdf
"""
import os, re, glob, subprocess
import pikepdf

FEDLEX = os.environ.get("FEDLEX_DIR", "/home/claude/fedlex")
JH     = os.environ.get("JAVA") or (os.path.join(os.environ["JAVA_HOME"], "bin", "java")
                                    if os.environ.get("JAVA_HOME") else "java")
JCP    = os.environ.get("JEXTER_CP", "build/out")
PJAR   = os.environ.get("PDFBOX_JAR")
if not PJAR:
    cands = glob.glob("**/pdfbox-app-*.jar", recursive=True) + glob.glob("/home/claude/**/pdfbox-app-*.jar", recursive=True)
    PJAR = cands[0] if cands else "pdfbox-app.jar"
CP = f"{PJAR}:{JCP}"
MAX_ROWS = int(os.environ.get("MAX_ROWS", "25"))

# leading legal enumerator of a title/heading line; tolerant of the 1a->"1 a" split and
# trailing footnote-ref digits; (?![a-z]) stops a capitalized title word becoming a suffix.
ENUM = re.compile(r'\b(Titre|Chapitre|Section|Art\.|§)\s*(\d+)\s*([a-z](?:bis|ter|quater)?)?(?![^\W\d_])', re.I | re.U)
def keys(t):
    out = []
    for m in ENUM.finditer(t or ""):
        kind = 'Art.' if m.group(1).lower() == 'art.' else ('§' if m.group(1) == '§' else m.group(1).capitalize())
        out.append(f"{kind} {m.group(2)}{(m.group(3) or '').lower()}")
    return out

def outline_keys(pdf):
    gt = []
    def walk(n):
        while n is not None:
            ks = keys(str(n.get('/Title', '')))
            if ks: gt.append(ks[0])
            f = n.get('/First')
            if f is not None: walk(f)
            n = n.get('/Next')
    o = pdf.Root.get('/Outlines')
    if o is not None and '/First' in o: walk(o['/First'])
    return set(gt)

pdfs = sorted(glob.glob(os.path.join(FEDLEX, "**", "*.pdf"), recursive=True))
if not pdfs:
    raise SystemExit(f"no PDFs under FEDLEX_DIR={FEDLEX}")

rows = []; tot_gt = tot_found = tot_merge = tot_heur = tot_tag = ndoc = 0
for p in pdfs:
    try:
        pdf = pikepdf.open(p)
        if '/Outlines' not in pdf.Root: continue
        gt = outline_keys(pdf)
        if not gt: continue
        out = "/tmp/_score_h.tsv"
        r = subprocess.run([JH, "-Djava.awt.headless=true", "-cp", CP,
                            "sugarcube.jexter.write.StructDump", p, out],
                           capture_output=True, text=True, timeout=300)
        heur = tag = 0
        for l in r.stdout.splitlines():
            if l.startswith("SUMMARY"):
                d = dict(x.split('=', 1) for x in l.split('\t') if '=' in x)
                heur = int(d.get('heur_head', 0)); tag = int(d.get('tagged_head', 0))
        allh = set(); merges = 0
        if os.path.exists(out):
            for l in open(out, encoding='utf-8'):
                _, _, txt = l.partition('\t'); ks = keys(txt)
                for k in ks: allh.add(k)
                if len({k.split()[0] for k in ks}) >= 2: merges += 1
        found = len(gt & allh)
        rows.append([os.path.basename(p), len(gt), found, heur, tag, merges])
        tot_gt += len(gt); tot_found += found; tot_merge += merges; tot_heur += heur; tot_tag += tag; ndoc += 1
    except Exception as e:
        rows.append([os.path.basename(p), -1, -1, -1, -1, -1])

def line(name, g, f, h, t, m):
    if g <= 0: return f"{name:30s}  (skip/err)"
    return f"{name:30s} {g:5d} {f:5d} {f/g*100:6.1f} {h:5d} {t:4d} {m:5d}"

hdr = f"{'doc':30s} {'GT':>5} {'found':>5} {'rec%':>6} {'heur':>5} {'tag':>4} {'merge':>5}"
print(hdr)
for r in rows[:MAX_ROWS]: print(line(*r))
print("-" * len(hdr))
# failure clusters: the lowest-recall docs (and any with merges)
scored = [r for r in rows if r[1] > 0]
worst = sorted(scored, key=lambda r: r[2] / r[1])[:10]
print("LOWEST-RECALL docs (failure clusters):")
for r in worst: print("  " + line(*r))
merged = [r for r in scored if r[5] > 0]
if merged:
    print(f"DOCS WITH MERGE ERRORS: {len(merged)}")
    for r in sorted(merged, key=lambda r: -r[5])[:10]: print("  " + line(*r))
print("-" * len(hdr))
if ndoc:
    print(line(f"CORPUS ({ndoc} docs)", tot_gt, tot_found, tot_heur, tot_tag, tot_merge))
