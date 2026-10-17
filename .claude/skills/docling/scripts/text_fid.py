#!/usr/bin/env python3
"""Text axis: characters lost AND invented between two readings of the same PDF.

    python3 text_fid.py <reference> <candidate> [--json]

Either side may be: a parse_text.py JSON dump, a .doctags, a .md, a .txt/.text.
Tags and Markdown syntax are stripped, whitespace is ignored, so the measure is
purely "which characters did one side read that the other did not".

Reports
  chars ref / cand, lost (in ref, not in cand), invented (in cand, not in ref),
  the top offending characters both ways (a lost 'ﬁ' vs an invented 'fi' is a
  ligature policy, a lost '€' is a real defect), and line recall: the share of
  reference lines whose text appears verbatim in the candidate (order-free) -
  a line missing there is where to look first.
"""
import argparse, json, re, sys
from collections import Counter
from pathlib import Path


def load_lines(p: Path) -> list[str]:
    s = p.read_text(encoding="utf-8", errors="replace")
    if p.suffix == ".json":
        d = json.loads(s)
        return [c["text"] for c in d["cells"]]
    if p.suffix == ".doctags":
        s = re.sub(r"<[^>]+>", "\n", s)
    elif p.suffix == ".md":
        s = re.sub(r"^\s{0,3}(#{1,6}\s+|[-*+]\s+|\d+\.\s+|>\s?|\|)", "", s, flags=re.M)
        s = re.sub(r"[|*_`]", "", s)
        s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
        s = re.sub(r"^\s*[-:| ]+\s*$", "", s, flags=re.M)
    return [l.strip() for l in s.splitlines() if l.strip()]


def squash(s: str) -> str:
    return re.sub(r"\s+", "", s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reference", type=Path)
    ap.add_argument("candidate", type=Path)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    ref, cand = load_lines(a.reference), load_lines(a.candidate)
    cr, cc = Counter(squash("".join(ref))), Counter(squash("".join(cand)))
    lost, invented = cr - cc, cc - cr
    cand_blob = squash("".join(cand))
    hits = [l for l in ref if squash(l) and squash(l) in cand_blob]
    missing = [l for l in ref if squash(l) and squash(l) not in cand_blob]

    rep = {
        "reference": str(a.reference), "candidate": str(a.candidate),
        "chars_ref": sum(cr.values()), "chars_cand": sum(cc.values()),
        "lost": sum(lost.values()), "invented": sum(invented.values()),
        "lost_top": lost.most_common(12), "invented_top": invented.most_common(12),
        "lines_ref": len(ref), "lines_found": len(hits),
        "line_recall": round(len(hits) / len(ref), 4) if ref else 1.0,
        "lines_missing_sample": missing[:15],
    }
    if a.json:
        print(json.dumps(rep, ensure_ascii=False))
        return
    print(f"ref {rep['chars_ref']} chars / cand {rep['chars_cand']} chars; "
          f"lost {rep['lost']}, invented {rep['invented']}")
    if lost: print("  lost   :", " ".join(f"{repr(c)}×{n}" for c, n in rep["lost_top"]))
    if invented: print("  invented:", " ".join(f"{repr(c)}×{n}" for c, n in rep["invented_top"]))
    print(f"line recall {rep['line_recall']:.2%} ({rep['lines_found']}/{rep['lines_ref']})")
    for l in rep["lines_missing_sample"]:
        print("  missing:", l[:100])
    sys.exit(0 if not lost and not invented else 1)


if __name__ == "__main__":
    main()
