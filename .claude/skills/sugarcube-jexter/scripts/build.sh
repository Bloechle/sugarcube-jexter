#!/usr/bin/env bash
# Bootstrap a JDK if needed, then compile jexter against the pdfbox-app jar.
#   Usage:  build.sh [SRC_DIR] [OUT_DIR]
#   Env:    PDFBOX_JAR  -> pdfbox-app-3.x.jar (required)
# See references/jdk-sandbox.md for why the JDK is fetched this way.
set -e
SRC="${1:-src}"
OUT="${2:-build/out}"
: "${PDFBOX_JAR:?set PDFBOX_JAR to the pdfbox-app jar}"

# ── JDK 21: try apt, then fall back to a rootless .deb extraction ────────────────
if ! command -v javac >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1 && { sudo -n true 2>/dev/null || [ "$(id -u)" = 0 ]; }; then
    (sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-21-jdk-headless) || true
  fi
fi
if ! command -v javac >/dev/null 2>&1; then
  base="http://archive.ubuntu.com/ubuntu/pool/universe/o/openjdk-21"
  jdk=$(curl -sS "$base/" | grep -oE 'openjdk-21-jdk-headless_[^"]*_amd64\.deb' | grep -v '~' | sort | tail -1)
  jre=${jdk/jdk-headless/jre-headless}
  mkdir -p /opt/jdk21
  for p in "$jre" "$jdk"; do curl -fsSL -o "/tmp/$p" "$base/$p" && dpkg-deb -x "/tmp/$p" /opt/jdk21; done
  export JAVA_HOME=/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi
javac -version

# ── compile ──────────────────────────────────────────────────────────────────────
mkdir -p "$OUT"
mapfile -t files < <(find "$SRC" -name "*.java")
echo "compiling ${#files[@]} files against $(basename "$PDFBOX_JAR")"
javac -cp "$PDFBOX_JAR" -d "$OUT" "${files[@]}"
echo "ok: $(find "$OUT" -name '*.class' | wc -l) classes -> $OUT"
