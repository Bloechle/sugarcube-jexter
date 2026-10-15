#!/usr/bin/env python3
"""
verify_matrix.py — Check generated matrix PDFs against the ground truth in
manifest.json. This is the closed loop you plug into a converter's
fidelity-check: every assertion is something a correct parser/extractor must
reproduce.

Checks per entry:
  * page count + page size           (pypdf)
  * text_contains substrings          (pdftotext)
  * fonts_embedded / fonts_not_embedded (pdffonts)
  * images colorspace / bpc           (pdfimages -list)
  * raw_tokens present                (qpdf --qdf decoded stream search)
  * info dict title/author            (pypdf)

Usage:
    python verify_matrix.py --dir out/synthetic
    python verify_matrix.py --dir out/synthetic --verbose
Exit code is non-zero if any check fails (CI-friendly).
"""
from __future__ import annotations
import argparse, json, os, re, subprocess, sys

def _run(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, timeout=60).stdout.decode("utf-8", "replace")
    except Exception as e:
        return f"__ERR__ {e}"

def pdftotext(path):
    return _run(["pdftotext", "-q", path, "-"])

def pdffonts(path):
    return _run(["pdffonts", path])

def pdfimages_list(path):
    return _run(["pdfimages", "-list", path])

def decoded(path):
    # qpdf --qdf decompresses content streams so operator tokens are searchable.
    # Combine with the raw bytes so dictionary-level tokens (/DCTDecode, /Separation,
    # which live in object dicts, not content streams) are also covered.
    qdf = _run(["qpdf", "--qdf", "--decode-level=all", path, "-"])
    raw = open(path, "rb").read().decode("latin-1", "replace")
    return re.sub(r"\s+", " ", qdf + "\n" + raw)

def page_info(path):
    from pypdf import PdfReader
    r = PdfReader(path)
    sizes = [(round(float(p.mediabox.width), 1), round(float(p.mediabox.height), 1)) for p in r.pages]
    meta = r.metadata or {}
    return len(r.pages), sizes, {"title": str(meta.title or ""), "author": str(meta.author or "")}

def check_entry(d, e, verbose):
    path = os.path.join(d, e["file"])
    exp = e["expected"]
    res = []  # (ok, label)
    if not os.path.exists(path):
        return [(False, f"file missing: {e['file']}")]

    npages, sizes, info = page_info(path)
    if "pages" in exp:
        res.append((npages == exp["pages"], f"pages={npages} (exp {exp['pages']})"))
    if "page_size_pt" in exp and sizes:
        w, h = sizes[0]; ew, eh = exp["page_size_pt"]
        ok = abs(w - ew) < 2 and abs(h - eh) < 2
        res.append((ok, f"page0 size={w}x{h}"))

    txt = pdftotext(path)
    for s in exp.get("text_contains", []):
        res.append((s in txt, f"text~ {s!r}"))

    fonts = pdffonts(path)
    flines = [l for l in fonts.splitlines()[2:] if l.strip()]
    any_embedded = any(re.search(r"\b(yes)\b", l) for l in flines)  # 'emb' column
    if exp.get("fonts_embedded"):
        res.append((any_embedded, f"embedded font present ({len(flines)} font rows)"))
    for nf in exp.get("fonts_not_embedded", []):
        # base-14 names should appear as not-embedded; check none of these are 'yes'
        bad = any(nf.lower() in l.lower() and " yes " in f" {l} " for l in flines)
        res.append((not bad, f"{nf} not embedded"))

    imgs = pdfimages_list(path)
    ilines = [l for l in imgs.splitlines()[2:] if l.strip()]
    for spec in exp.get("images", []):
        if "colorspace" in spec:
            ok = any(spec["colorspace"] in l.lower() for l in ilines)
            res.append((ok, f"image colorspace {spec['colorspace']}"))
        if "bpc" in spec:
            ok = any(re.search(rf"\b{spec['bpc']}\b", l) for l in ilines)
            res.append((ok, f"image bpc {spec['bpc']} ({len(ilines)} images)"))

    if exp.get("raw_tokens"):
        dec = decoded(path)
        for tok in exp["raw_tokens"]:
            alts = [re.sub(r"\s+", " ", t).strip() for t in tok.split("||")]
            res.append((any(a in dec for a in alts), f"token {tok!r}"))

    if "info" in exp:
        for k, v in exp["info"].items():
            res.append((v in info.get(k, ""), f"info.{k}~ {v!r}"))

    return res

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="out/synthetic")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    man = json.load(open(os.path.join(a.dir, "manifest.json")))
    total = passed = 0
    failed_entries = []
    for e in man["entries"]:
        checks = check_entry(a.dir, e, a.verbose)
        ok_n = sum(1 for ok, _ in checks if ok)
        total += len(checks); passed += ok_n
        status = "PASS" if ok_n == len(checks) else "FAIL"
        if status == "FAIL":
            failed_entries.append(e["id"])
        if status == "FAIL" or a.verbose:
            print(f"[{status}] {e['id']:24s} {ok_n}/{len(checks)}")
            for ok, lab in checks:
                if not ok or a.verbose:
                    print(f"        {'ok ' if ok else 'XX '} {lab}")
    print(f"\n{passed}/{total} checks passed across {len(man['entries'])} PDFs.")
    if failed_entries:
        print("Entries with failures:", ", ".join(failed_entries))
        sys.exit(1)
    print("ALL GREEN ✓")

if __name__ == "__main__":
    main()
