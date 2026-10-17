#!/usr/bin/env python3
"""Interop gate: does a DocTags file load into the official DoclingDocument, intact?

    python3 doctags_interop.py <file.doctags> [--pdf <source.pdf>] [--out <dir>] [--json]

Loads the DocTags with docling-core (the same code path docling uses for its own
VLM output), then reports per document:
  pages, items per label, recognised-but-dropped tags, unclosed tags,
  text characters written vs text characters that reached the model,
and writes docling's own projections next to it: <name>.docling.md,
<name>.docling.json, <name>.docling.doctags (the re-export) plus a normalised
diff count between input and re-export.

--pdf gives page sizes (blank PIL pages at the PDF's MediaBox size), so bboxes
come back in points and list items keep their provenance - the loader only
extracts list-item boxes when a page image is present.

Exit status 1 when any tag was dropped or left unclosed - those are real losses.
OCD-AI files (doc/ocdai.md, marker <ocdai_N>) are recognised: attribute and envelope
tokens are not "dropped", extension roots are reported separately, and the gate also
requires unique ids on every element and one <pg_N> per page.
"""
import argparse, json, re, sys
from pathlib import Path

from docling_core.types.doc.document import DoclingDocument, DocTagsDocument

ROOT_TAGS = {
    "title", "document_index", "checkbox_unselected", "checkbox_selected", "text",
    "page_header", "inline", "page_footer", "formula", "caption", "picture", "footnote",
    "code", "ordered_list", "unordered_list", "key_value_region", "chart", "otsl",
    *{f"section_header_level_{i}" for i in range(1, 7)},
}
# tags legal INSIDE a root chunk, never dropped on their own
NESTED_TAGS = {"list_item", "caption", "footnote", "smiles", "doctag", "page_break",
               "ched", "rhed", "fcel", "ecel", "lcel", "ucel", "xcel", "nl", "srow", "shed",
               "column_header", "row_header", "data", "key_value_map", "key", "value",
               "link", "unordered_list", "ordered_list", "inline"}
TAG_RE = re.compile(r"<(/?)([a-z]+(?:_[a-z]+)*?)(?:_([a-z0-9]+))?>")
# OCD-AI (doc/ocdai.md): a strict superset of DocTags. Extension roots are dropped by
# docling BY DESIGN; attribute/envelope tokens are stripped inside elements. Neither is a loss.
OCDAI_ROOTS = {"ocdlink", "ocdfield", "ocdrule", "ocdvector"}
OCDAI_ENVELOPE = {"ocdai", "doc", "pg", "pw", "ph", "ncol", "dfs", "dw", "dfc"}
OCDAI_ATTRS = {"id", "fs", "w", "i", "fc", "bg", "col", "hy", "cont", "sc", "lang", "ln",
               "of", "ft", "fv", "n", "br"}


def page_images(pdf: Path | None, n: int):
    """Blank PIL pages at the PDF's page sizes (points); None -> unit pages."""
    if pdf is None:
        return [None] * n
    from PIL import Image
    from pypdf import PdfReader
    r = PdfReader(str(pdf))
    ims = []
    for i in range(n):
        if i < len(r.pages):
            b = r.pages[i].mediabox
            ims.append(Image.new("RGB", (int(float(b.width)), int(float(b.height))), "white"))
        else:
            ims.append(None)
    return ims


def strip_ocdai(dt: str) -> str:
    """Remove what docling drops BY DESIGN in an OCD-AI file, leaving plain DocTags."""
    if "<ocdai_" not in dt:
        return dt
    dt = re.sub(r"<(?:%s)>.*?</(?:%s)>" % ("|".join(OCDAI_ROOTS), "|".join(OCDAI_ROOTS)), "", dt, flags=re.S)
    dt = re.sub(r"<(?:%s)(?:_[a-z0-9]+)?>" % "|".join(OCDAI_ATTRS | OCDAI_ENVELOPE), "", dt)
    return dt


def inner_text_chars(doctags: str) -> int:
    body = re.sub(r"<[^>]+>", "", strip_ocdai(doctags))
    return len(re.sub(r"\s+", "", body))


def loaded_text_chars(doc: DoclingDocument) -> int:
    n = 0
    for it, _ in doc.iterate_items():
        t = getattr(it, "text", None)
        if t:
            n += len(re.sub(r"\s+", "", t))
        data = getattr(it, "data", None)
        if data is not None and hasattr(data, "table_cells"):
            for c in data.table_cells:
                n += len(re.sub(r"\s+", "", c.text or ""))
    return n


def normalise(dt: str) -> list[str]:
    """Element-per-line view with loc tokens stripped, for a structural diff."""
    s = re.sub(r"<loc_\d+>", "", strip_ocdai(dt))
    s = re.sub(r"</?doctag>", "", s)
    s = re.sub(r">\s+<", "><", s.strip())
    parts = re.split(r"(?=<(?!/)[a-z_0-9]+>)", s)
    return [re.sub(r"\s+", " ", x).strip() for x in parts if x.strip()]


def analyse(path: Path, pdf: Path | None, out: Path | None):
    raw = path.read_text(encoding="utf-8")
    pages_dt = raw.split("<page_break>")
    n_pages = len(pages_dt)

    # Tag census on the raw text (before the loader gets to drop anything)
    seen, unknown, unclosed = {}, set(), []
    ocdai = "<ocdai_" in raw
    attrs, ids, ext_roots = {}, [], {}
    for closing, name, val in TAG_RE.findall(raw):
        if name == "loc" or closing:
            continue
        if ocdai and name in OCDAI_ATTRS:
            attrs[name] = attrs.get(name, 0) + 1
            if name == "id": ids.append(val)
            continue
        if ocdai and name in OCDAI_ENVELOPE:
            continue
        if ocdai and name in OCDAI_ROOTS:
            ext_roots[name] = ext_roots.get(name, 0) + 1
            seen[name] = seen.get(name, 0) + 1
            continue
        full = f"{name}_{val}" if val else name
        seen[full] = seen.get(full, 0) + 1
        if not ({full, name} & (ROOT_TAGS | NESTED_TAGS)):
            unknown.add(full)
    for tag in seen:
        opens = len(re.findall(rf"<{tag}>", raw))
        closes = len(re.findall(rf"</{tag}>", raw))
        if opens != closes and tag not in ("page_break", "nl", "ched", "rhed", "fcel", "ecel",
                                          "lcel", "ucel", "xcel", "srow", "shed"):
            unclosed.append((tag, opens, closes))

    dtd = DocTagsDocument.from_multipage_doctags_and_images(raw, page_images(pdf, n_pages))
    doc = DoclingDocument.load_from_doctags(dtd, document_name=path.stem)

    labels = {}
    for it, _ in doc.iterate_items():
        labels[it.label.value] = labels.get(it.label.value, 0) + 1

    written, loaded = inner_text_chars(raw), loaded_text_chars(doc)
    re_export = doc.export_to_doctags()
    a, b = normalise(raw), normalise(re_export)
    import difflib
    sm = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    changed = sum(max(i2 - i1, j2 - j1) for op, i1, i2, j1, j2 in sm.get_opcodes() if op != "equal")

    leaf = (ROOT_TAGS - {"unordered_list", "ordered_list", "inline"}) | {"list_item"}
    n_elems = sum(v for k, v in seen.items() if k in leaf)
    report = {
        "file": str(path), "pages": n_pages, "items": sum(labels.values()), "labels": labels,
        "ocdai": {"attributes": attrs, "ids": len(ids), "ids_unique": len(set(ids)),
                  "elements_without_id": max(0, n_elems - len(ids)),
                  "extension_roots": ext_roots,
                  "page_tokens": len(re.findall(r"<pg_\d+>", raw))} if ocdai else None,
        "tags_written": seen, "tags_dropped_by_loader": sorted(unknown),
        "tags_unclosed": unclosed,
        "text_chars_written": written, "text_chars_loaded": loaded,
        "text_loss_ratio": round(1 - loaded / written, 4) if written else 0.0,
        "reexport_elements_changed": changed, "reexport_elements": len(a),
    }
    if out:
        out.mkdir(parents=True, exist_ok=True)
        (out / f"{path.stem}.docling.md").write_text(doc.export_to_markdown(), encoding="utf-8")
        (out / f"{path.stem}.docling.json").write_text(
            json.dumps(doc.export_to_dict(), ensure_ascii=False, indent=1), encoding="utf-8")
        (out / f"{path.stem}.docling.doctags").write_text(re_export, encoding="utf-8")
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("doctags", nargs="+", type=Path)
    ap.add_argument("--pdf", type=Path, help="source PDF (page sizes); one file only")
    ap.add_argument("--out", type=Path, help="write docling's md/json/doctags projections here")
    ap.add_argument("--json", action="store_true", help="machine-readable report")
    a = ap.parse_args()
    rc = 0
    for p in a.doctags:
        r = analyse(p, a.pdf if len(a.doctags) == 1 else None, a.out)
        if a.json:
            print(json.dumps(r, ensure_ascii=False))
        else:
            print(f"{p.name}: {r['pages']} page(s), {r['items']} items "
                  f"{r['labels']}")
            print(f"  text chars written {r['text_chars_written']}, loaded {r['text_chars_loaded']} "
                  f"(loss {r['text_loss_ratio']:.2%}); re-export changed "
                  f"{r['reexport_elements_changed']}/{r['reexport_elements']} elements")
            if r["ocdai"]:
                o = r["ocdai"]
                print(f"  OCD-AI: {o['ids']} ids ({o['ids_unique']} unique, "
                      f"{o['elements_without_id']} elements without id), page tokens {o['page_tokens']}/{r['pages']}, "
                      f"attrs {o['attributes']}, extension roots {o['extension_roots']} (dropped by docling by design)")
            if r["tags_dropped_by_loader"]:
                print(f"  DROPPED tags (loader ignores them): {r['tags_dropped_by_loader']}")
            if r["tags_unclosed"]:
                print(f"  UNCLOSED tags (open/close): {r['tags_unclosed']}")
        o = r["ocdai"]
        bad_ids = bool(o) and (o["ids"] != o["ids_unique"] or o["elements_without_id"] or o["page_tokens"] != r["pages"])
        if r["tags_dropped_by_loader"] or r["tags_unclosed"] or bad_ids:
            rc = 1
    sys.exit(rc)


if __name__ == "__main__":
    main()
