#!/usr/bin/env python3
"""Render an (X)HTML page — or a page inside an EPUB — to PNG using a real
browser engine (WebKit, via `wkhtmltoimage` under a virtual X display).

This renders the FULL page exactly as a reader/browser would: the <head>, any
linked CSS and @font-face rules, inline SVG, and images. It is NOT an isolated
SVG render — that is the whole point. Relative resources (../fonts, ../images,
fonts.css) resolve because the EPUB is unzipped with its directory structure
intact and `--enable-local-file-access` is passed.

Notes on this WebKit build (wkhtmltoimage 0.12.6, unpatched Qt):
  * `--zoom` and `--disable-smart-width` are IGNORED -> output renders ~1x at
    the page's CSS pixel width. Pass `--width` = the page width in CSS px.
  * It honors multi-value SVG `x` lists and @font-face url() to local fonts.

Usage:
    render_html.py page.xhtml out.png --width 612
    render_html.py book.epub  out.png --width 612 --page 1
"""
import argparse, glob, os, shutil, subprocess, sys, tempfile, zipfile


def find_epub_page(epub, page, workdir):
    with zipfile.ZipFile(epub) as z:
        z.extractall(workdir)
    pages = (sorted(glob.glob(os.path.join(workdir, "**", "pages", "page-*.xhtml"), recursive=True))
             or sorted(glob.glob(os.path.join(workdir, "**", "*.xhtml"), recursive=True))
             or sorted(glob.glob(os.path.join(workdir, "**", "*.html"), recursive=True)))
    if not pages:
        sys.exit("render_html: no (x)html page found inside the EPUB")
    return pages[min(max(page, 1) - 1, len(pages) - 1)]


def render(html, out, width):
    cmd = ["xvfb-run", "-a", "wkhtmltoimage", "--enable-local-file-access",
           "--width", str(width), "-q", "-f", "png", html, out]
    subprocess.run(cmd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def main():
    ap = argparse.ArgumentParser(description="Render (X)HTML/EPUB page to PNG via WebKit")
    ap.add_argument("input", help="an .xhtml/.html file, or an .epub")
    ap.add_argument("output", help="output .png path")
    ap.add_argument("--width", type=int, default=612, help="page width in CSS px")
    ap.add_argument("--page", type=int, default=1, help="EPUB page number (1-based)")
    a = ap.parse_args()

    if a.input.lower().endswith(".epub"):
        wd = tempfile.mkdtemp(prefix="render_epub_")
        try:
            render(find_epub_page(a.input, a.page, wd), a.output, a.width)
        finally:
            shutil.rmtree(wd, ignore_errors=True)
    else:
        render(a.input, a.output, a.width)
    print("rendered ->", a.output)


if __name__ == "__main__":
    main()
