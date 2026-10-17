#!/usr/bin/env python3
"""Structure axis: block-level comparison of two DocTags for the same PDF.

    python3 struct_compare.py <reference.doctags> <candidate.doctags> [--json] [--show N]

Both files are loaded through docling-core (so both are judged by the same
grammar), flattened to an ordered list of (label, text) blocks, and matched on
whitespace-free text:
  1:1     same text on both sides           -> label agreement is measured here
  split   one ref block = several cand      -> candidate over-segments (the safe bias)
  merged  several ref blocks = one cand     -> candidate under-segments (the bad one)
  ref-only / cand-only                      -> missed vs invented blocks
Plus reading-order inversions among 1:1 matches, and a label confusion table
(reference label -> candidate label) - the single most useful line when the
candidate calls a docling `section_header` a `text`.

Tables and pictures carry no text; they are counted by label only.
"""
import argparse, json, re, sys
from collections import Counter, defaultdict
from pathlib import Path

from docling_core.types.doc.document import DoclingDocument, DocTagsDocument

MIN_LEN = 6  # chars: shorter blocks are only matched exactly, never by containment


def blocks(p: Path):
    raw = p.read_text(encoding="utf-8")
    n = raw.count("<page_break>") + 1
    doc = DoclingDocument.load_from_doctags(
        DocTagsDocument.from_multipage_doctags_and_images(raw, [None] * n), p.stem)
    out = []
    for it, _ in doc.iterate_items():
        t = getattr(it, "text", "") or ""
        out.append((it.label.value, re.sub(r"\s+", "", t), t))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("reference", type=Path)
    ap.add_argument("candidate", type=Path)
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--show", type=int, default=10, help="examples per category")
    a = ap.parse_args()

    R, C = blocks(a.reference), blocks(a.candidate)
    r_by_text = defaultdict(list)
    for i, (_, k, _) in enumerate(R):
        if k: r_by_text[k].append(i)

    match = {}          # cand idx -> ('exact'|'split'|'merged', [ref idx])
    used_ref = set()
    for j, (_, k, _) in enumerate(C):
        if not k: continue
        if k in r_by_text:
            i = r_by_text[k][0]; match[j] = ("exact", [i]); used_ref.add(i); continue
        if len(k) >= MIN_LEN:
            parents = [i for i, (_, rk, _) in enumerate(R) if len(rk) >= MIN_LEN and k in rk]
            if parents:
                match[j] = ("split", parents[:1]); used_ref.add(parents[0]); continue
            kids = [i for i, (_, rk, _) in enumerate(R) if len(rk) >= MIN_LEN and rk in k]
            if kids:
                match[j] = ("merged", kids); used_ref.update(kids); continue

    kinds = Counter(v[0] for v in match.values())
    cand_only = [j for j, (_, k, _) in enumerate(C) if k and j not in match]
    ref_only = [i for i, (_, k, _) in enumerate(R) if k and i not in used_ref]

    confusion = Counter()
    agree = 0
    exact = [(j, v[1][0]) for j, v in match.items() if v[0] == "exact"]
    for j, i in exact:
        confusion[(R[i][0], C[j][0])] += 1
        agree += R[i][0] == C[j][0]
    # reading order: inversions among exact matches, in candidate order
    seq = [i for j, i in sorted(exact)]
    inv = sum(1 for x in range(len(seq)) for y in range(x + 1, len(seq)) if seq[x] > seq[y])

    rep = {
        "reference": str(a.reference), "candidate": str(a.candidate),
        "blocks_ref": len(R), "blocks_cand": len(C),
        "labels_ref": dict(Counter(l for l, _, _ in R)), "labels_cand": dict(Counter(l for l, _, _ in C)),
        "exact": kinds["exact"], "split": kinds["split"], "merged": kinds["merged"],
        "ref_only": len(ref_only), "cand_only": len(cand_only),
        "label_agreement": round(agree / len(exact), 4) if exact else None,
        "confusion": {f"{r}->{c}": n for (r, c), n in confusion.most_common() if r != c},
        "order_inversions": inv, "order_pairs": len(seq) * (len(seq) - 1) // 2,
        "examples": {
            "merged": [C[j][2][:90] for j, v in match.items() if v[0] == "merged"][:a.show],
            "ref_only": [f"[{R[i][0]}] {R[i][2][:90]}" for i in ref_only][:a.show],
            "cand_only": [f"[{C[j][0]}] {C[j][2][:90]}" for j in cand_only][:a.show],
            "mislabelled": [f"{R[i][0]}->{C[j][0]}: {C[j][2][:70]}" for j, i in exact if R[i][0] != C[j][0]][:a.show],
        },
    }
    if a.json:
        print(json.dumps(rep, ensure_ascii=False)); return
    print(f"ref {rep['blocks_ref']} blocks {rep['labels_ref']}")
    print(f"cand {rep['blocks_cand']} blocks {rep['labels_cand']}")
    print(f"1:1 {rep['exact']}  split {rep['split']}  merged {rep['merged']}  "
          f"ref-only {rep['ref_only']}  cand-only {rep['cand_only']}")
    la = rep["label_agreement"]
    print(f"label agreement on 1:1: {la:.2%}" if la is not None else "label agreement: n/a",
          f"| order inversions {rep['order_inversions']}/{rep['order_pairs']}")
    if rep["confusion"]:
        print("  confusion:", ", ".join(f"{k}×{v}" for k, v in rep["confusion"].items()))
    for cat, rows in rep["examples"].items():
        for r in rows: print(f"  {cat}: {r}")


if __name__ == "__main__":
    main()
