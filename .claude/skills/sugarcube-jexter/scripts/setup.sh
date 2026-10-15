#!/usr/bin/env bash
# One-shot toolchain for the jexter dev + fidelity loop. Installs everything the
# two facets need:
#   JDK 21 (java + javac)   — build jexter, run RenderPdf/RenderOcd/OcdFacts
#   PDFBox jar (you supply)  — the single reference rasterizer + the PDF parser
#   python: reportlab pypdf pikepdf svglib Pillow pymupdf   (GT synthesis + facts)
#   system: qpdf, poppler-utils  (pdftotext/pdffonts/pdfimages/pdftoppm)
#           wkhtmltoimage + xvfb (WebKit render of EPUB/XHTML candidates)
#           fonts: DejaVu (TTF), IPA Gothic (CJK -> CIDFontType2 embedding)
# The sandbox ships a JRE only and has no apt/sudo, so the JDK is extracted from
# the Ubuntu .deb by hand (rootless dpkg-deb -x). See references/jdk-sandbox.md.
set -e

# ── JDK 21: apt first, then rootless .deb extraction ────────────────────────────
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
  { echo "export JAVA_HOME=$JAVA_HOME"; echo 'export PATH="$JAVA_HOME/bin:$PATH"'; } >> ~/.bashrc
fi
javac -version

# ── python deps (GT synthesis + structural facts) ───────────────────────────────
echo "== python deps =="
pip install --quiet --break-system-packages reportlab pypdf pikepdf svglib Pillow pymupdf || \
pip install --quiet reportlab pypdf pikepdf svglib Pillow pymupdf

# ── system tools (poppler facts + WebKit render + embedding fonts) ──────────────
echo "== system tools =="
APT="apt-get install -y -qq"
( sudo apt-get update -qq || apt-get update -qq ) >/dev/null 2>&1 || true
( sudo $APT qpdf poppler-utils wkhtmltopdf xvfb fonts-dejavu fonts-ipafont-gothic \
  || $APT qpdf poppler-utils wkhtmltopdf xvfb fonts-dejavu fonts-ipafont-gothic ) >/dev/null 2>&1 || \
  echo "  (note: install qpdf, poppler-utils, wkhtmltopdf, xvfb, fonts-dejavu, fonts-ipafont-gothic for full coverage)"

echo "== check =="
python3 -c "import reportlab,pypdf,pikepdf,svglib,PIL;print('  py libs ok')" 2>&1 || true
for t in javac qpdf pdftotext pdffonts pdfimages pdftoppm wkhtmltoimage; do
    command -v "$t" >/dev/null 2>&1 && echo "  $t ok" || echo "  $t MISSING"
done
echo
echo "Then set the two env vars for the OCD stages:"
echo "  export PDFBOX_JAR=/path/to/pdfbox-app-3_0_7.jar"
echo "  export JEXTER_CP=/path/to/jexter/build/out      # = OUT_DIR from build.sh"
