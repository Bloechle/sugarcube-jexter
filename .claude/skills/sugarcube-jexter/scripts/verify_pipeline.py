#!/usr/bin/env python3
"""
verify_pipeline.py — Unified GT<->output verification across the jexter pipeline.

It extracts normalized *facts* from a surface and compares them either to a
declared ground truth (the synthetic matrix manifest) or across stages
(round-trip). The shared GT isolates which stage broke:

    GT  ──vs──  ① OCD virtual   (PdfImporter.convert)
    ①  ──vs──  ② OCD reread     (.ocd.epub written then parsed back) = serialization
    ②  ──vs──  ③ EPUB           (projection)                      = EPUB writer

Fact backends (interchangeable, same schema):
    pdf          : poppler + pypdf            (reference / GT-derivation / self-test) — WORKS NOW
    epub|xhtml   : unzip + XHTML parse        (stage ③)                              — WORKS NOW
    ocd-virtual  : scripts/OcdFacts (Java)    (stage ①, PDF -> OCDDocument)          — needs OcdFacts built
    ocd-reread   : scripts/OcdFacts (Java)    (stage ②, load .ocd.epub)              — needs OcdFacts built

Normalized facts schema (per surface):
    { stage, source, text,
      pages: [ { index, size_pt:[w,h], rotation, text,
                 fonts:    [ {name, embedded, type, upem} ],
                 images:   [ {colorspace, bpc, w, h} ],
                 colorspaces: [...], separations: [...] } ] }

The structural assertions reuse the manifest 'expected' vocabulary
(text_contains / fonts_embedded / fonts_not_embedded / images / colorspaces /
pages / page_size_pt / info), so the synthetic GT plugs straight in.

Usage
-----
  # structural: declared GT (synthetic) vs a stage
  python verify_pipeline.py --source out/synthetic/color_cmyk.pdf \
      --manifest out/synthetic/manifest.json --gt-id color_cmyk --stage pdf

  # structural: derive GT from the source PDF, check the EPUB projection
  python verify_pipeline.py --source book.pdf --gt-from-pdf --stage epub \
      --candidate book.epub

  # round-trip: OCD virtual vs OCD reread (serialization integrity)
  python verify_pipeline.py --source book.pdf --roundtrip ocd-virtual ocd-reread \
      --ocd book.ocd.epub

  # visual leg (delegates to compare.py vs the PDFBox reference)
  python verify_pipeline.py --source book.pdf --visual --candidate page.png
"""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys, tempfile, zipfile, glob

HERE = os.path.dirname(os.path.abspath(__file__))

def _run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, timeout=120).stdout.decode("utf-8", "replace")
    except Exception as e:
        return f"__ERR__ {e}"

# ---------------------------------------------------------------- pdf backend
def facts_pdf(path):
    from pypdf import PdfReader
    r = PdfReader(path)
    raw = open(path, "rb").read().decode("latin-1", "replace")
    qdf = re.sub(r"\s+", " ", _run(["qpdf", "--qdf", "--decode-level=all", path, "-"]) + " " + raw)
    # fonts (whole-doc granularity from pdffonts)
    fout = _run(["pdffonts", path]).splitlines()[2:]
    fonts = []
    for l in fout:
        if not l.strip():
            continue
        cols = l.split()
        emb = " yes " in f" {l} "
        fonts.append({"name": cols[0], "embedded": emb, "type": cols[1] if len(cols) > 1 else "", "upem": None})
    # images
    iout = _run(["pdfimages", "-list", path]).splitlines()[2:]
    images = []
    for l in iout:
        if not l.strip():
            continue
        c = l.split()
        # poppler columns: page num type width height color comp bpc ...
        cs = next((x for x in c if x.lower() in ("rgb", "cmyk", "gray", "icc", "index")), None)
        bpc = None
        for x in c:
            if x.isdigit() and 1 <= int(x) <= 16:
                bpc = int(x)  # last small int ~ bpc heuristic; refined below
        images.append({"colorspace": (cs or "").lower(), "bpc": bpc, "w": None, "h": None})
    colorspaces = []
    for tok, label in [("/DeviceRGB", "DeviceRGB"), ("rg ", "DeviceRGB"),
                       ("/DeviceCMYK", "DeviceCMYK"), (" k ", "DeviceCMYK"),
                       ("/DeviceGray", "DeviceGray"), ("/Separation", "Separation")]:
        if tok in qdf and label not in colorspaces:
            colorspaces.append(label)
    seps = re.findall(r"/Separation\s*/([A-Za-z0-9#\-]+)", qdf) + re.findall(r"/([A-Za-z0-9\-]*PANTONE[A-Za-z0-9\-]*)", qdf)
    pages = []
    for i, p in enumerate(r.pages):
        txt = _run(["pdftotext", "-q", "-f", str(i + 1), "-l", str(i + 1), path, "-"])
        rot = int(p.get("/Rotate", 0) or 0)
        pages.append({"index": i, "size_pt": [round(float(p.mediabox.width), 1), round(float(p.mediabox.height), 1)],
                      "rotation": rot, "text": txt, "fonts": fonts if i == 0 else [],
                      "images": images if i == 0 else [], "colorspaces": colorspaces, "separations": sorted(set(seps))})
    meta = r.metadata or {}
    return {"stage": "pdf", "source": os.path.basename(path),
            "text": "\n".join(p["text"] for p in pages), "pages": pages,
            "info": {"title": str(meta.title or ""), "author": str(meta.author or "")}}

# --------------------------------------------------------------- epub backend
def facts_epub(path):
    tmp = None
    if path.lower().endswith((".xhtml", ".html", ".htm")):
        page_files = [path]; root = os.path.dirname(path)
    else:
        tmp = tempfile.mkdtemp(prefix="epubfacts_")
        with zipfile.ZipFile(path) as z:
            z.extractall(tmp)
        root = tmp
        page_files = sorted(glob.glob(os.path.join(tmp, "**", "*.xhtml"), recursive=True) or
                            glob.glob(os.path.join(tmp, "**", "*.html"), recursive=True))
    pages = []
    all_fonts = set(); embedded_fonts = set()
    for i, pf in enumerate(page_files):
        html = open(pf, encoding="utf-8", errors="replace").read()
        text = re.sub(r"<[^>]+>", " ", re.sub(r"(?is)<(script|style).*?</\1>", " ", html))
        text = re.sub(r"\s+", " ", text).strip()
        # @font-face url() -> embedded if the file resolves
        for m in re.findall(r"url\(([^)]+)\)", html):
            fn = m.strip("'\" ")
            all_fonts.add(os.path.basename(fn))
            cand = os.path.normpath(os.path.join(os.path.dirname(pf), fn))
            if os.path.exists(cand) or glob.glob(os.path.join(root, "**", os.path.basename(fn)), recursive=True):
                embedded_fonts.add(os.path.basename(fn))
        pages.append({"index": i, "size_pt": None, "rotation": 0, "text": text,
                      "fonts": [], "images": [], "colorspaces": [], "separations": []})
    fonts = [{"name": f, "embedded": f in embedded_fonts, "type": "@font-face", "upem": None} for f in sorted(all_fonts)]
    if pages:
        pages[0]["fonts"] = fonts
    return {"stage": "epub", "source": os.path.basename(path),
            "text": "\n".join(p["text"] for p in pages), "pages": pages, "info": {}}

# ---------------------------------------------------------------- ocd backend
def facts_ocd(mode, source=None, ocd=None):
    """Stage ① / ②. Delegates to the Java OcdFacts dumper which converts a PDF to
    an OCDDocument (mode=virtual) or loads an .ocd.epub (mode=reread) and prints facts
    JSON on stdout. Requires OcdFacts compiled against jexter+PDFBox (see
    references/ocd-facts.md). Returns None (with a clear message) if unavailable."""
    jar = os.environ.get("PDFBOX_JAR")
    jexter = os.environ.get("JEXTER_CP")  # compiled jexter classes
    facts_class = os.path.join(HERE, "OcdFacts.java")
    if not (jar and jexter and os.path.exists(facts_class)):
        print("  [ocd backend unavailable] set PDFBOX_JAR and JEXTER_CP, and build "
              "scripts/OcdFacts.java — see references/ocd-facts.md", file=sys.stderr)
        return None
    arg = ("--pdf", source) if mode == "virtual" else ("--ocd", ocd)
    out = _run(["java", "-cp", f"{jar}:{jexter}", facts_class, *arg])
    if out.startswith("__ERR__") or not out.strip().startswith("{"):
        print(f"  [ocd backend error] {out[:200]}", file=sys.stderr)
        return None
    f = json.loads(out)
    f["stage"] = f"ocd-{mode}"
    return f

def get_facts(stage, source, candidate, ocd):
    if stage == "pdf":
        return facts_pdf(candidate or source)
    if stage == "epub":
        return facts_epub(candidate)
    if stage == "ocd-virtual":
        return facts_ocd("virtual", source=source)
    if stage == "ocd-reread":
        return facts_ocd("reread", ocd=ocd)
    raise SystemExit(f"unknown stage {stage}")

# ----------------------------------------------------------------- GT loading
def load_gt(manifest, gt_id):
    man = json.load(open(manifest))
    for e in man["entries"]:
        if e["id"] == gt_id:
            return e["expected"]
    raise SystemExit(f"gt-id {gt_id} not in manifest")

def gt_from_pdf(source):
    """Derive a (looser) GT from the source PDF via the pdf backend, for real
    PDFs that have no declared manifest."""
    f = facts_pdf(source)
    p0 = f["pages"][0]
    return {"pages": len(f["pages"]), "page_size_pt": p0["size_pt"],
            "text_contains": [w for w in re.findall(r"\w{6,}", f["text"])[:8]],
            "fonts_embedded": [x["name"] for x in p0["fonts"] if x["embedded"]][:3],
            "colorspaces": p0["colorspaces"], "_derived": True}

# ----------------------------------------------------------------- comparator
def _union(facts, key):
    out = []
    for p in facts["pages"]:
        for v in p.get(key, []):
            if v not in out:
                out.append(v)
    return out

def compare_gt_vs_facts(gt, facts):
    stage = facts["stage"]; res = []
    if "pages" in gt:
        res.append((len(facts["pages"]) == gt["pages"], f"pages={len(facts['pages'])} (exp {gt['pages']})"))
    if gt.get("page_size_pt") and facts["pages"] and facts["pages"][0]["size_pt"]:
        w, h = facts["pages"][0]["size_pt"]; ew, eh = gt["page_size_pt"]
        res.append((abs(w - ew) < 2 and abs(h - eh) < 2, f"page0 size={w}x{h}"))
    for s in gt.get("text_contains", []):
        res.append((s in facts["text"], f"text~ {s!r}"))
    fonts = [x for p in facts["pages"] for x in p["fonts"]]
    if gt.get("fonts_embedded"):
        res.append((any(x["embedded"] for x in fonts), f"embedded font present ({len(fonts)} fonts)"))
    for nf in gt.get("fonts_not_embedded", []):
        bad = any(nf.lower() in x["name"].lower() and x["embedded"] for x in fonts)
        res.append((not bad, f"{nf} not embedded"))
    csu = _union(facts, "colorspaces")
    for cs in gt.get("colorspaces", []):
        res.append((cs in csu, f"colorspace {cs}"))
        if cs == "Separation":
            res.append((bool(_union(facts, "separations")), "separation name present"))
    imgs = [x for p in facts["pages"] for x in p["images"]]
    for spec in gt.get("images", []):
        if "colorspace" in spec:
            res.append((any(spec["colorspace"] in (x["colorspace"] or "") for x in imgs), f"image cs {spec['colorspace']}"))
        if "bpc" in spec:
            res.append((any(x["bpc"] == spec["bpc"] for x in imgs), f"image bpc {spec['bpc']}"))
    # raw_tokens & info are PDF-only / model-only; note when skipped
    skipped = []
    if gt.get("raw_tokens") and stage != "pdf":
        skipped.append("raw_tokens (operator-level, pdf-only)")
    if gt.get("info") and stage in ("epub",):
        skipped.append("info dict")
    if gt.get("info") and stage not in ("epub",):
        for k, v in gt["info"].items():
            res.append((v in facts.get("info", {}).get(k, ""), f"info.{k}~ {v!r}"))
    return res, skipped

def compare_roundtrip(a, b):
    """① vs ② equality (serialization integrity). Reports concrete divergences."""
    res = []
    res.append((len(a["pages"]) == len(b["pages"]), f"page count {len(a['pages'])} vs {len(b['pages'])}"))
    for i in range(min(len(a["pages"]), len(b["pages"]))):
        pa, pb = a["pages"][i], b["pages"][i]
        ta = re.sub(r"\s+", " ", pa["text"]).strip(); tb = re.sub(r"\s+", " ", pb["text"]).strip()
        res.append((ta == tb, f"p{i} text identical"))
        res.append((pa.get("size_pt") == pb.get("size_pt"), f"p{i} size {pa.get('size_pt')} vs {pb.get('size_pt')}"))
        fa = {(x['name'], x['embedded']) for x in pa['fonts']}; fb = {(x['name'], x['embedded']) for x in pb['fonts']}
        res.append((fa == fb, f"p{i} font set"))
        res.append((sorted(pa.get('colorspaces', [])) == sorted(pb.get('colorspaces', [])), f"p{i} colorspaces"))
        res.append((sorted(pa.get('separations', [])) == sorted(pb.get('separations', [])), f"p{i} separations"))
    return res

def _print(title, checks, skipped=None):
    ok = sum(1 for o, _ in checks if o)
    print(f"\n=== {title} : {ok}/{len(checks)} ===")
    for o, lab in checks:
        print(f"   {'ok ' if o else 'XX '} {lab}")
    for s in (skipped or []):
        print(f"   -- skipped: {s}")
    return ok == len(checks)

def main():
    ap = argparse.ArgumentParser(description="Unified GT<->output verification across the jexter pipeline")
    ap.add_argument("--source", required=True, help="source PDF (the GT anchor)")
    ap.add_argument("--manifest"); ap.add_argument("--gt-id")
    ap.add_argument("--gt-from-pdf", action="store_true")
    ap.add_argument("--stage", choices=["pdf", "epub", "ocd-virtual", "ocd-reread"])
    ap.add_argument("--candidate", help="surface to inspect (epub/xhtml/png/...)")
    ap.add_argument("--ocd", help=".ocd.epub file for ocd-reread")
    ap.add_argument("--roundtrip", nargs=2, metavar=("A", "B"),
                    help="compare two stages for serialization integrity, e.g. ocd-virtual ocd-reread")
    ap.add_argument("--visual", action="store_true", help="delegate to compare.py vs the PDFBox reference")
    a = ap.parse_args()
    all_ok = True

    if a.visual:
        if not a.candidate:
            ap.error("--visual needs --candidate (png/epub/xhtml/ocd)")
        cmd = ["python3", os.path.join(HERE, "compare.py"), a.source, a.candidate, "-o", "compare.png"]
        print("  visual compare ->", " ".join(cmd))
        print(_run(cmd))

    if a.roundtrip:
        fa = get_facts(a.roundtrip[0], a.source, a.candidate, a.ocd)
        fb = get_facts(a.roundtrip[1], a.source, a.candidate, a.ocd)
        if fa and fb:
            all_ok &= _print(f"round-trip {a.roundtrip[0]} vs {a.roundtrip[1]}", compare_roundtrip(fa, fb))
        else:
            print("  round-trip skipped (a stage backend was unavailable)"); all_ok = False

    if a.stage:
        facts = get_facts(a.stage, a.source, a.candidate, a.ocd)
        if facts is None:
            print(f"  stage {a.stage} backend unavailable"); sys.exit(2)
        if a.gt_from_pdf:
            gt = gt_from_pdf(a.source)
        elif a.manifest and a.gt_id:
            gt = load_gt(a.manifest, a.gt_id)
        else:
            ap.error("provide --manifest+--gt-id or --gt-from-pdf for a structural check")
        checks, skipped = compare_gt_vs_facts(gt, facts)
        all_ok &= _print(f"GT vs {a.stage}" + (" (derived GT)" if gt.get("_derived") else ""), checks, skipped)

    if not (a.visual or a.roundtrip or a.stage):
        ap.error("nothing to do: pass --stage, --roundtrip, and/or --visual")
    print("\n" + ("ALL GREEN ✓" if all_ok else "DIVERGENCES — see above (the failing stage localizes the bug)"))
    sys.exit(0 if all_ok else 1)

if __name__ == "__main__":
    main()
