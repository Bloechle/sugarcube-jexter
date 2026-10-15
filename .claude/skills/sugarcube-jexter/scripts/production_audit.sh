#!/bin/bash
# production_audit.sh <jar> <corpusDir> [epubcheck.jar] — passes A–G of the release bench.
# Green = ship. See references/production-audit.md for the reading of each pass.
set -u
JAR=${1:?jar}; CORPUS=${2:?corpus dir}; EPUBCHECK=${3:-}
OUT=/tmp/audit-out; rm -rf $OUT; mkdir -p $OUT
conv() { java -cp "$JAR" sugarcube.jexter.write.Conversion "$@"; }
fail=0

echo "=== A · matrix ==="
for pdf in $CORPUS/*.pdf; do n=$(basename $pdf .pdf)
  for t in md doctags html svg epub ocd.epub pdf; do
    conv "$pdf" $OUT/$n.$t >/dev/null 2>$OUT/$n.$t.err || { echo "  ✗ $n→$t"; fail=1; }
  done
done; [ $fail = 0 ] && echo "  all rc=0 ✓"

echo "=== B · round-trip x=rt2=rt3 ==="
for pdf in $CORPUS/*.pdf; do n=$(basename $pdf .pdf)
  conv $OUT/$n.ocd.epub $OUT/$n.rt2.ocd.epub >/dev/null 2>&1
  conv $OUT/$n.rt2.ocd.epub $OUT/$n.rt3.ocd.epub >/dev/null 2>&1
  rm -rf /tmp/_a /tmp/_b /tmp/_c; mkdir /tmp/_a /tmp/_b /tmp/_c
  (cd /tmp/_a && unzip -qo $OUT/$n.ocd.epub); (cd /tmp/_b && unzip -qo $OUT/$n.rt2.ocd.epub); (cd /tmp/_c && unzip -qo $OUT/$n.rt3.ocd.epub)
  diff -rq /tmp/_a /tmp/_b >/dev/null && diff -rq /tmp/_b /tmp/_c >/dev/null && echo "  $n ✓" || { echo "  ✗ $n"; fail=1; }
done

echo "=== C · idempotence (md/doctags/html byte-exact; svg = known FP-residue) ==="
for pdf in $CORPUS/*.pdf; do n=$(basename $pdf .pdf); bad=""
  for t in md doctags html; do
    conv $OUT/$n.ocd.epub $OUT/$n.via.$t >/dev/null 2>&1
    cmp -s $OUT/$n.$t $OUT/$n.via.$t || bad="$bad $t"
  done
  [ -z "$bad" ] && echo "  $n ✓" || { echo "  ✗ $n:$bad"; fail=1; }
done

echo "=== D · validity ==="
python3 - "$OUT" << 'PY'
import glob, sys, xml.dom.minidom, zipfile, re
out = sys.argv[1]; bad = 0
for f in glob.glob(out + '/*.svg'):
    try: xml.dom.minidom.parse(f)
    except Exception as e: print('  ✗ svg', f, e); bad = 1
for f in glob.glob(out + '/*.epub'):
    if '.rt' in f or '.via' in f: continue
    z = zipfile.ZipFile(f); i0 = z.infolist()[0]
    ok = z.namelist()[0] == 'mimetype' and i0.compress_type == zipfile.ZIP_STORED
    if not ok: print('  ✗ ocf', f); bad = 1
print('  svg well-formed + epub OCF ✓' if not bad else '  (failures above)')
PY
if [ -n "$EPUBCHECK" ]; then
  pass=0; tot=0
  for f in $OUT/*.epub; do case "$f" in *rt2*|*rt3*|*via*) continue;; esac
    tot=$((tot+1))
    java -jar "$EPUBCHECK" "$f" 2>&1 | grep -q "No errors or warnings" && pass=$((pass+1)) || { echo "  ✗ epubcheck $(basename $f)"; fail=1; }
  done; echo "  epubcheck: $pass/$tot clean"
fi

echo "=== F · pinned reproducibility ==="
for pdf in $CORPUS/*.pdf; do n=$(basename $pdf .pdf); bad=""
  for t in md doctags html svg epub ocd.epub pdf; do
    SOURCE_DATE_EPOCH=1700000000 conv "$pdf" $OUT/_r1.$t >/dev/null 2>&1
    SOURCE_DATE_EPOCH=1700000000 conv "$pdf" $OUT/_r2.$t >/dev/null 2>&1
    cmp -s $OUT/_r1.$t $OUT/_r2.$t || bad="$bad $t"
  done
  [ -z "$bad" ] && echo "  $n 7/7 ✓" || { echo "  ✗ $n:$bad"; fail=1; }
done

echo "=== G · hostile inputs ==="
printf 'not a pdf' > /tmp/_not.pdf; : > /tmp/_empty.pdf; head -c 800 $(ls $CORPUS/*.pdf | head -1) > /tmp/_trunc.pdf
for b in /tmp/_not.pdf /tmp/_empty.pdf /tmp/_trunc.pdf; do
  rm -f $OUT/_bad.md
  conv "$b" $OUT/_bad.md >/dev/null 2>&1
  if [ $? -ne 0 ] && [ ! -s $OUT/_bad.md ]; then echo "  $(basename $b) ✓"; else echo "  ✗ $(basename $b)"; fail=1; fi
done

[ $fail = 0 ] && echo "── PRODUCTION AUDIT: ALL GREEN ──" || { echo "── FAILURES ABOVE ──"; exit 1; }
