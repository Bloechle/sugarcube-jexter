#!/bin/bash
# fetch_wild_corpus.sh [outDir] — assemble the diverse real-world PDF corpus (~77 files, 23 MB)
# from reader test suites reachable in the sandbox (github/codeload/raw allowed).
set -u
OUT=${1:-/tmp/wildcorpus}; W=/tmp/_wildsrc; mkdir -p "$OUT" "$W"; cd "$W"
# 1 · py-pdf/sample-files — curated: forms, arabic, CCITT/LZW/ASCII85, CMYK, PDF/A, passwords, outlines, XMP…
curl -sL --max-time 120 -o sf.tgz https://codeload.github.com/py-pdf/sample-files/tar.gz/refs/heads/main && tar xzf sf.tgz
cp sample-files-main/*/*.pdf "$OUT"/ 2>/dev/null
# 2 · PDF Association pdf20examples — genuine PDF 2.0 (offset start, incremental save, UTF-8, output intents)
curl -sL --max-time 90 -o p20.tgz https://codeload.github.com/pdf-association/pdf20examples/tar.gz/refs/heads/master && tar xzf p20.tgz
for f in pdf20examples-*/*.pdf; do cp "$f" "$OUT/pdf20-$(basename "$f" | tr ' ' '_')"; done
# 3 · openpreserve pdfCabinetOfHorrors — encrypted, corrupted, JP2K, embedded video, signed 3D, non-embedded fonts
#     (file list scraped from the github tree page — the API is rate-limited unauthenticated)
for f in balloon_a1b_jp2k.pdf calistoMTNoFontsEmbedded.pdf corruptionOneByteMissing.pdf \
         digitally_signed_3D_Portfolio.pdf embedded_video_avi.pdf embedded_video_quicktime.pdf \
         encryption_nocopy.pdf encryption_noprinting.pdf encryption_notextaccess.pdf \
         encryption_openpassword.pdf externalLink.pdf fileAttachment.pdf \
         fileAttachment_fileAttachmentAnnotation.pdf javascript.pdf pdf-17-header18.pdf \
         test_fontArialNotEmbedded.pdf text_only_fontsEmbeddedAll.pdf text_only_fontsEmbeddedSubset.pdf \
         text_only_fontsNotEmbedded.pdf text_only_pdfa1b.pdf; do
  curl -sL --max-time 40 -o "$OUT/hh_$f" "https://raw.githubusercontent.com/openpreserve/format-corpus/master/pdfCabinetOfHorrors/$f" || rm -f "$OUT/hh_$f"
done
# 4 · mozilla/pdf.js test/pdfs — targeted real files (many entries are .link stubs: verify %PDF- magic!)
for f in franz.pdf alphatrans.pdf cmykjpeg.pdf devicen.pdf sizes.pdf arial_unicode_ab_cidfont.pdf \
         bug1020226.pdf visibility_expressions.pdf helloworld-bad.pdf personwithdog.pdf issue2948.pdf \
         issue5549.pdf pattern_text_embedded_font.pdf issue4573.pdf issue6541.pdf zerowidthline.pdf \
         transparent.pdf issue4402_reduced.pdf TrueType_without_cmap.pdf; do
  curl -sL --max-time 40 -o "$OUT/js_$f" "https://raw.githubusercontent.com/mozilla/pdf.js/master/test/pdfs/$f"
  [ "$(head -c 5 "$OUT/js_$f" 2>/dev/null)" = "%PDF-" ] || rm -f "$OUT/js_$f"
done
# 5 · pikepdf tests/resources — cyclic TOC, sandwich, JBIG2, palettes, inline images, fuzz survivor
curl -sL --max-time 120 -o pk.tgz https://codeload.github.com/pikepdf/pikepdf/tar.gz/refs/heads/main && tar xzf pk.tgz
find pikepdf-main/tests/resources -name "*.pdf" -exec sh -c 'cp "$1" "$0/pk_$(basename "$1" | tr " " _)"' "$OUT" {} \;
# 6 · pdf-association/safedocs — hand-forged parser traps: dialects, Type3-in-Type3, dual startxref,
#     stream/dict confusions, unicode SASLprep password tests (those refuse: AES-256 R6 with unicode passwords)
curl -sL --max-time 120 -o sd.tgz https://codeload.github.com/pdf-association/safedocs/tar.gz/refs/heads/main && tar xzf sd.tgz
find safedocs-main -name "*.pdf" -exec sh -c 'cp "$1" "$0/sd_$(basename "$1" | tr " " _)"' "$OUT" {} \;
# 7 · qpdf qtest — xref loops, object streams, AES, linearized, deliberately damaged (bad*.pdf refuse cleanly)
curl -sL --max-time 150 -o qp.tgz https://codeload.github.com/qpdf/qpdf/tar.gz/refs/heads/main && tar xzf qp.tgz
for f in append-xref-loop.pdf bad-encryption-length.pdf bad-jpeg.pdf bad-xref.pdf bad7.pdf bad14.pdf \
         damaged-stream.pdf object-stream.pdf lin-special.pdf V5-aes256.pdf V4-aes.pdf minimal-linearized.pdf \
         deleted-and-reused.pdf digitally-signed.pdf form-fields-and-annotations.pdf good13.pdf; do
  [ -f "qpdf-main/qpdf/qtest/qpdf/$f" ] && cp "qpdf-main/qpdf/qtest/qpdf/$f" "$OUT/qp_$f"
done
# 8 · veraPDF-corpus — stratified PDF/A subset (tarball is 138 MB / 2694 files: sample, don't mirror)
curl -sL --max-time 180 -o vp.tgz https://codeload.github.com/veraPDF/veraPDF-corpus/tar.gz/refs/heads/master
tar tzf vp.tgz | grep "\.pdf$" | awk 'NR % 150 == 7' | head -18 > /tmp/_vpsel.txt
tar xzf vp.tgz -T /tmp/_vpsel.txt
i=0; while read f; do i=$((i+1)); cp "$f" "$OUT/vp_$(printf %02d $i)_$(basename "$f" | tr ' ' _ | cut -c1-58)"; done < /tmp/_vpsel.txt
# 9 · pdf.js volley 2 — vertical Japanese, shading, annotation edge cases
for f in issue3323.pdf issue6298.pdf vertical.pdf issue3885.pdf issue2840.pdf issue4801.pdf; do
  curl -sL --max-time 40 -o "$OUT/js2_$f" "https://raw.githubusercontent.com/mozilla/pdf.js/master/test/pdfs/$f"
  [ "$(head -c 5 "$OUT/js2_$f" 2>/dev/null)" = "%PDF-" ] || rm -f "$OUT/js2_$f"
done
# sanity: everything must be a real PDF
for f in "$OUT"/*.pdf; do [ "$(head -c 5 "$f")" = "%PDF-" ] || { echo "drop $(basename $f)"; rm -f "$f"; }; done
echo "wild corpus → $OUT : $(ls "$OUT" | wc -l) PDFs, $(du -sh "$OUT" | cut -f1)"
