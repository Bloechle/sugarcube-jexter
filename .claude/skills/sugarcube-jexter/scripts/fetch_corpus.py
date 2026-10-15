#!/usr/bin/env python3
"""
fetch_corpus.py — Pull real-world test PDFs from public, GitHub-hosted corpora.

Two ways to get files:
  1. CURATED  — a small, hand-verified set of diagnostic PDFs (works offline of
                the GitHub API; downloads come straight from raw.githubusercontent).
  2. DISCOVER — list any corpus directory via the GitHub API and pick files.
                The API is rate-limited (60 req/h unauthenticated); set
                GITHUB_TOKEN for 5000/h.

Only GitHub domains are needed (github.com / raw.githubusercontent.com /
api.github.com), so this runs in a network-restricted sandbox.

Usage
-----
    python fetch_corpus.py --list-corpora
    python fetch_corpus.py --curated --out out/corpus          # the verified set
    python fetch_corpus.py --source pdfjs --list               # API directory listing
    python fetch_corpus.py --source pdfjs --pick rotation.pdf cmykjpeg.pdf
    python fetch_corpus.py --source horrors --grep tag --max 5 # filter + cap

NOTE on other corpora not on GitHub (need a manual download, can't be reached
from a GitHub-only sandbox):
  * Isartor PDF/A-1b suite : https://www.pdfa.org/resource/isartor-test-suite/
  * Bavaria PDF/A-1b suite : via the PDF Association
  * digitalcorpora govdocs1: https://digitalcorpora.org/corpora/files/govdocs1/
"""
from __future__ import annotations
import argparse, json, os, sys, urllib.request, urllib.error

RAW = "https://raw.githubusercontent.com"
API = "https://api.github.com"

# Corpora reachable for API-based discovery -----------------------------------
CORPORA = {
    "pdfjs":   dict(repo="mozilla/pdf.js",            branch="master", dir="test/pdfs",
                    desc="Mozilla pdf.js regression corpus: real-world breakage cases."),
    "horrors": dict(repo="openpreserve/format-corpus", branch="master", dir="pdfCabinetOfHorrors",
                    desc="Open Preservation 'PDF Cabinet of Horrors': deliberately nasty files."),
    "qpdf":    dict(repo="qpdf/qpdf",                  branch="main",   dir="qpdf/qtest/qpdf",
                    desc="qpdf test suite: structurally unusual / malformed PDFs."),
    "verapdf": dict(repo="veraPDF/veraPDF-corpus",     branch="master", dir="PDF_A-1b",
                    desc="veraPDF PDF/A conformance corpus (browse subdirs via --list)."),
}

# Hand-verified diagnostic picks (all confirmed reachable). --------------------
# Each maps to the matrix feature(s) it complements with a REAL-WORLD file.
CURATED = {
    "pdfjs": [
        ("rotation.pdf",                  "page rotation (/Rotate) in the wild"),
        ("cmykjpeg.pdf",                  "CMYK JPEG image (Adobe inversion pitfalls)"),
        ("alphatrans.pdf",                "alpha transparency / blending"),
        ("transparent.pdf",               "transparency groups"),
        ("pattern_text_embedded_font.pdf","embedded font + tiling pattern fill"),
        ("franz.pdf",                     "multilingual / accented text extraction"),
        ("TAMReview.pdf",                 "complex multi-page real-world report"),
        ("issue1905.pdf",                 "known parser edge case"),
        ("issue9291.pdf",                 "known parser edge case"),
        ("bug1473809.pdf",               "known rendering bug regression"),
    ],
}

def _get(url, token=None, binary=True):
    req = urllib.request.Request(url, headers={"User-Agent": "pdf-testbench"})
    if token and url.startswith(API):
        req.add_header("Authorization", f"Bearer {token}")
        req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read() if binary else r.read().decode("utf-8")

def raw_url(corpus, name):
    c = CORPORA[corpus]
    return f"{RAW}/{c['repo']}/{c['branch']}/{c['dir']}/{name}"

def download(url, dest):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    try:
        data = _get(url)
    except urllib.error.HTTPError as e:
        print(f"  [{e.code}] {url}", file=sys.stderr); return False
    except Exception as e:
        print(f"  [ERR] {url}: {e}", file=sys.stderr); return False
    with open(dest, "wb") as f:
        f.write(data)
    print(f"  [ok] {os.path.basename(dest):34s} {len(data):>8d} B")
    return True

def api_list(corpus, token):
    c = CORPORA[corpus]
    url = f"{API}/repos/{c['repo']}/contents/{c['dir']}?ref={c['branch']}"
    data = json.loads(_get(url, token=token, binary=False))
    if not isinstance(data, list):
        raise RuntimeError(data.get("message", "unexpected API response"))
    return [(d["name"], d.get("size", 0), d.get("download_url")) for d in data
            if d.get("type") == "file"]

def cmd_curated(out):
    total = 0
    man = []
    for corpus, items in CURATED.items():
        for name, tests in items:
            dest = os.path.join(out, corpus, name)
            if download(raw_url(corpus, name), dest):
                total += 1
                man.append({"corpus": corpus, "file": f"{corpus}/{name}", "tests": tests})
    with open(os.path.join(out, "corpus_manifest.json"), "w") as f:
        json.dump({"source": "curated", "count": total, "entries": man}, f, indent=2)
    print(f"\nFetched {total} curated PDFs -> {out}")

def main():
    ap = argparse.ArgumentParser(description="Fetch real-world test PDFs from GitHub corpora")
    ap.add_argument("--list-corpora", action="store_true")
    ap.add_argument("--curated", action="store_true", help="download the verified diagnostic set")
    ap.add_argument("--source", choices=list(CORPORA))
    ap.add_argument("--list", action="store_true", help="list files in --source via GitHub API")
    ap.add_argument("--pick", nargs="+", help="download named files from --source")
    ap.add_argument("--grep", help="with --list/no --pick: substring filter to download")
    ap.add_argument("--max", type=int, default=10, help="cap downloads when using --grep")
    ap.add_argument("--out", default="out/corpus")
    a = ap.parse_args()
    token = os.environ.get("GITHUB_TOKEN")

    if a.list_corpora:
        for k, c in CORPORA.items():
            print(f"  {k:9s} {c['repo']}@{c['branch']}/{c['dir']}\n            {c['desc']}")
        print("\n  curated sets:", ", ".join(CURATED))
        return
    if a.curated:
        cmd_curated(a.out); return
    if a.source and a.pick:
        for name in a.pick:
            download(raw_url(a.source, name), os.path.join(a.out, a.source, name))
        return
    if a.source and (a.list or a.grep):
        try:
            files = api_list(a.source, token)
        except Exception as e:
            print(f"GitHub API listing failed ({e}).", file=sys.stderr)
            if a.source in CURATED:
                print("Falling back to curated names for this corpus:", file=sys.stderr)
                for n, t in CURATED[a.source]:
                    print(f"  {n:34s} {t}")
            sys.exit(2)
        if a.grep:
            files = [f for f in files if a.grep.lower() in f[0].lower()][: a.max]
            for name, _, dl in files:
                download(dl or raw_url(a.source, name), os.path.join(a.out, a.source, name))
        else:
            for name, size, _ in sorted(files):
                print(f"  {name:40s} {size:>9d} B")
            print(f"\n{len(files)} files in {a.source}.")
        return
    ap.error("nothing to do — see --help")

if __name__ == "__main__":
    main()
