#!/usr/bin/env bash
# gates.sh — every container gate on one source, in one command.
#
#   bash scripts/gates.sh in.pdf [OUT_DIR]
#
# Compiles the probes once, then runs them in the order that makes a failure diagnosable:
#
#   1  RoundTrip   reproducibility per target, doctags idempotence, byte stability
#   2  ModelRt     read(write(x)) == x at the MODEL level — the gate byte-stability cannot see
#   3  Straddle    paragraphs must wrap a CONTIGUOUS paint span (must print STRADDLING=0)
#   4  Clips       every clip reference must resolve (references vs table)
#   5  Hostile     broken / malicious containers — liberal but never silent
#
# A red 1 tells you a byte moved; ContainerDiff then tells you which one. A red 2 with a green 1 is
# the interesting case: a field written wrong and read back wrong the same way.
#
# Needs JEXTER_CP (classes) and the PDFBox jars on the path; SOURCE_DATE_EPOCH is pinned here,
# because an unpinned clock alone fails REPRO.
set -u

PDF=${1:?usage: gates.sh in.pdf [OUT_DIR]}
OUT=${2:-/tmp/gates}
CP="${JEXTER_CP:?set JEXTER_CP to the compiled classes}:${PDFBOX_JAR:-lib/*}"
HERE=$(cd "$(dirname "$0")" && pwd)
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-1000000000}

mkdir -p "$OUT/classes"
javac -nowarn -cp "$CP" -d "$OUT/classes" \
      "$HERE/RoundTrip.java" "$HERE/ModelRt.java" "$HERE/Straddle.java" \
      "$HERE/Clips.java" "$HERE/Hostile.java" "$HERE/ContainerDiff.java" || exit 1
# PDFBox and the engine both log to stderr on every conversion; a gate runner must show verdicts,
# not a font-substitution log. Failures still surface: each probe reports on stdout and exits non-zero.
RUN="java -cp $CP:$OUT/classes"
run() { $RUN "$@" 2>/dev/null; }
fail=0

echo "=== 1  RoundTrip ==="
run RoundTrip "$PDF" "$OUT/good.ocd.epub" || fail=1

echo "=== 2  ModelRt ==="
# ModelRt is a REPORT tool: it prints its findings and exits 0 whatever it found. Piping it
# into grep would mask its status anyway ($? is grep's). So the verdict is read from the
# report, exactly as gate 3 reads STRADDLING — otherwise this gate can never go red, and a
# field written wrong and read back wrong the same way sails through with the bytes agreeing.
M=$(run ModelRt "$PDF")
echo "$M" | grep -v '^   DIFF\|^   PERDU'
E=$(echo "$M" | sed -n 's/.*ecarts hors z : \([0-9]*\).*/\1/p' | tail -1)
if [ "${E:-x}" != "0" ]; then
   echo "   MODEL DIVERGENCE: ecarts hors z = ${E:-unreadable}"
   echo "$M" | grep '^   DIFF\|^   PERDU' | head -10
   fail=1
fi

echo "=== 3  Straddle ==="
S=$(run Straddle "$PDF" | tail -1); echo "   $S"
case "$S" in *STRADDLING=0*) ;; *) fail=1 ;; esac

echo "=== 4  Clips ==="
run Clips "$PDF" | head -2

echo "=== 5  Hostile ==="
# make_hostile prints the corpus it built; only the verdicts matter here

# This gate could never go red: any failure of the generator printed "(no good.ocd.epub…)" and moved
# on, and a variant read in SILENCE — no refusal, no warning — passed as well. Both are defects of the
# contract ("liberal but never silent"), so both now fail the run. (2026-09-21)
# Start from an EMPTY corpus: a variant left over from an earlier run is measured as if built now —
# met the first time this gate could go red, on a badclip fixture written by the generator's old
# version against a page that does not clip.
rm -rf "$OUT/hostile"
if python3 "$HERE/make_hostile.py" "$OUT/good.ocd.epub" "$OUT/hostile" > "$OUT/hostile.log" 2>&1; then
  grep '^payloads landed\|n/a' "$OUT/hostile.log" | sed 's/^/   /'
  for f in "$OUT"/hostile/*.ocd.epub; do
     R=$(timeout 60 $RUN Hostile "$f" 2>&1 | grep -o 'WARNING.*\|REFUSE.*\|LU sans erreur.*' | tr '\n' ' ')
     printf '   %-28s %s\n' "$(basename "$f")" "$(echo "$R" | cut -c1-110)"
     case "$R" in *REFUSE*|*WARNING*) ;; *) echo "      SILENT: read with no refusal and no warning"; fail=1 ;; esac
  done
else
  echo "   the hostile corpus could not be built:"; tail -2 "$OUT/hostile.log" | sed 's/^/     /'; fail=1
fi

echo
[ $fail -eq 0 ] && echo "ALL GATES GREEN" || echo "SOME GATES FAILED"
exit $fail
