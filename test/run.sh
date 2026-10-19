#!/usr/bin/env bash
# The functional gate, exactly as CI runs it: build the fixture corpus, then run every gate over it.
#
#   bash test/run.sh                       # after compiling the engine into target/classes
#   CLASSES=target/ci-classes bash test/run.sh
#
# Needs python3 with the packages in test/fixtures/requirements.txt, and the fonts the matrix embeds
# (DejaVu, IPA Gothic): a feature that cannot be built fails the run, it is never skipped.
# The corpus is PRODUCED, so it lives in target/corpus and is never committed.
set -euo pipefail
cd "$(dirname "$0")/.."

CLASSES=${CLASSES:-target/classes}
OUT=target/corpus
rm -rf "$OUT" && mkdir -p "$OUT"
python3 test/fixtures/generate_matrix.py --all --out "$OUT" > /dev/null
python3 test/fixtures/make_rich.py "$OUT/rich.pdf" > /dev/null
python3 test/fixtures/make_tagged.py "$OUT/tagged.pdf" > /dev/null

export SOURCE_DATE_EPOCH=1000000000              # an unpinned clock alone fails REPRO
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";" ;; esac
java -cp "$CLASSES${SEP}lib/*" test/Gates.java "$OUT"/*.pdf src/sugarcube/jexter/ui/pdf/web/welcome.pdf
