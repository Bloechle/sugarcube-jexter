#!/usr/bin/env python3
"""make_hostile.py — build the broken/malicious container corpus from a healthy .ocd.epub.

A public reader is fed garbage sooner or later. These are the six shapes that matter, and the two
that a healthy-looking read hides:

  xxe        external entity pointing at /etc/passwd     -> must never reach the parser
  billion    nested entity expansion                     -> must never reach the parser
  badclip    a clip-path naming a <clipPath> that is not there
  badfont    a run naming a font the container never carried
  badmat     matrix(nope) -- a malformed value
  nofonts    the glyph member removed entirely           -> a page that renders textless
  trunc      the zip cut in half

badclip / badfont / nofonts are the dangerous ones: without a resolution pass they read "fine",
and the loss shows up only in the rendering.

EVERY PAYLOAD IS VERIFIED TO HAVE LANDED, and the script fails if one did not. It once targeted the
grammar-v1 names (`data-u`, `data-f`, a fonts member ending in `f.svg`) long after v2 renamed them
(`data-text`, `data-font`, `pages/fonts.svg`): four of the seven variants came out as the healthy file
with at most a harmless DOCTYPE, and every gate fed with them passed — an XXE that is never referenced
and a billion-laughs bomb that is never detonated prove nothing about the reader (found 2026-09-21).
A fixture generator that cannot tell whether it produced its fixture is the harness lying.

    python make_hostile.py good.ocd.epub outdir
"""
import os
import re
import shutil
import sys
import zipfile

PAGE = "page-001.xhtml"
FONTS = "pages/fonts.svg"          # the ONE font member (FORMAT §B6) — every glyph lives here
LANDED = {}                         # variant -> how many times its payload hit


def hit(name, n):
    """Record how many substitutions a payload made. 0 means the fixture is the healthy file."""
    LANDED[name] = LANDED.get(name, 0) + n


def variant(src, out, name, fn):
    path = os.path.join(out, f"hostile_{name}.ocd.epub")
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            data = fn(info.filename, zin.read(info.filename))
            if data is not None:
                zout.writestr(info, data)
    return path


def on_page(fn):
    return lambda name, data: fn(data) if name.endswith(PAGE) else data


def main():
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)

    def sub1(name, text, old, new):
        n = text.count(old)
        hit(name, min(n, 1))
        return text.replace(old, new, 1)

    # The DOCTYPE alone proves nothing: the entity must be REFERENCED, in a value a reader will expand.
    variant(src, out, "xxe", on_page(lambda d: sub1("xxe",
            sub1("xxe", d.decode(), "<svg", '<!DOCTYPE svg [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\n<svg'),
            'data-text="', 'data-text="&xxe;').encode()))

    ents = ('<!DOCTYPE svg [<!ENTITY a "AAAAAAAAAA">'
            + "".join(f'<!ENTITY {c} "{"&" + p + ";" * 1}">'.replace('";"', '"')
                      for c, p in ())  # placeholder, expanded below
            + "]>")
    chain = ['<!ENTITY a "AAAAAAAAAA">']
    prev = "a"
    for c in "bcdef":
        chain.append(f'<!ENTITY {c} "{("&" + prev + ";") * 10}">')
        prev = c
    ents = "<!DOCTYPE svg [" + "".join(chain) + "]>"
    variant(src, out, "billion", on_page(
        lambda d: (ents + "\n" + sub1("billion", d.decode(), 'data-text="', 'data-text="&f;')).encode()))

    def resub(name, pat, rep, data, count):
        out_, n = re.subn(pat, rep, data, count=count)
        hit(name, n)
        return out_

    # A page that does not clip has nothing for badclip to break: that is NOT APPLICABLE, not a dud —
    # most documents never clip, and refusing the whole corpus for it would hide the six that apply.
    with zipfile.ZipFile(src) as z:
        page = next((z.read(n) for n in z.namelist() if n.endswith(PAGE)), b"")
    clips = b"url(#c" in page
    if clips:
        variant(src, out, "badclip", on_page(lambda d: sub1("badclip", d.decode(), "url(#c", "url(#zz").encode()))
    variant(src, out, "badfont", on_page(lambda d: resub("badfont", rb'data-font="[^"]*"', b'data-font="NoSuchFont"', d, 1)))
    variant(src, out, "badmat", on_page(
        lambda d: resub("badmat", rb'transform="matrix\([^"]*\)"', b'transform="matrix(nope)"', d, 3)))

    def drop_fonts(name, data):
        if name.endswith(FONTS):
            hit("nofonts", 1)
            return None
        return data
    variant(src, out, "nofonts", drop_fonts)

    trunc = os.path.join(out, "hostile_trunc.ocd.epub")
    shutil.copy(src, trunc)
    with open(trunc, "r+b") as f:
        f.truncate(os.path.getsize(trunc) // 2)

    for f in sorted(os.listdir(out)):
        print(f, os.path.getsize(os.path.join(out, f)))

    # xxe needs BOTH halves (the declaration and the reference), the others one hit each at least
    need = {"xxe": 2, "billion": 1, "badfont": 1, "badmat": 1, "nofonts": 1}
    if clips: need["badclip"] = 1
    else:     print("badclip: n/a — the source page does not clip (convert a PDF that clips to exercise it)")
    dud = {k: LANDED.get(k, 0) for k, v in need.items() if LANDED.get(k, 0) < v}
    print("payloads landed:", ", ".join(f"{k}={LANDED.get(k, 0)}" for k in need))
    if dud:
        sys.exit(f"FAIL — these fixtures are the healthy file, their payload found nothing to hit: {dud}")


if __name__ == "__main__":
    main()
