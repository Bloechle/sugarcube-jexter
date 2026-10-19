# Bootstrapping a full JDK (with `javac`) in the sandbox

The sandbox ships a **JRE only** — `java` exists, `javac` does not — and
`apt`/`sudo` are unavailable. But the bash network allows **`archive.ubuntu.com`**,
so the working method is to fetch the OpenJDK `.deb` packages from the Ubuntu pool
and extract them by hand (no install, no root).

> **JDK 21 vs the project's `release 25`.** The Maven build sets
> `maven.compiler.release=25`, but the engine uses **no post-21 language feature**, so the
> bootstrapped **JDK 21** compiles the whole tree (raw `javac`, no `--release`). `openjdk-25`
> is not in the Ubuntu 24.04 pool this bootstrap pulls from — staying on 21 is deliberate;
> bump only if the source ever adopts 22+ syntax.

## What does NOT work
- `apt-get install default-jdk` → no `sudo`/`apt` privileges.
- `pip install jdk4py` → installs, but it's a **trimmed runtime** (no `javac`, no
  `jdk.compiler` module), so neither `javac` nor the multi-file source launcher
  works. True across all versions (11 → 25).
- ~~GitHub release tarballs → 403~~ — **no longer true** (verified 2026-09-01):
  `release-assets.githubusercontent.com` is on the allowlist, so the Temurin 25 tarball
  in SKILL.md §1 downloads (140 MB) and `javac 25` runs the CI gate byte-for-byte.
  The `.deb` route below stays the fast path (JDK 21, ~50 MB) when no 22+ lint is needed.

## What works: extract the Ubuntu OpenJDK `.deb`s

```bash
cd /tmp
BASE="http://archive.ubuntu.com/ubuntu/pool/universe/o/openjdk-21"
# discover current filenames (versions change with security updates):
#   curl -sS "$BASE/" | grep -oE 'openjdk-21-(jdk|jre)-headless_[^"]*_amd64\.deb' | grep -v '~' | sort | tail
for p in \
  openjdk-21-jre-headless_21.0.11+10-1_amd64.deb \
  openjdk-21-jdk-headless_21.0.11+10-1_amd64.deb ; do
  curl -fsSL -o "$p" "$BASE/$p"
done

mkdir -p /opt/jdk21
dpkg-deb -x openjdk-21-jre-headless_21.0.11+10-1_amd64.deb /opt/jdk21   # runtime (libjvm, etc.)
dpkg-deb -x openjdk-21-jdk-headless_21.0.11+10-1_amd64.deb /opt/jdk21   # adds javac, jar, jlink…
```

Both `.deb`s extract into the same prefix. `dpkg-deb -x` needs no root.
`scripts/build.sh` automates exactly this when `javac` is missing.

## Use it

```bash
export JAVA_HOME=/opt/jdk21/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
javac -version   # javac 21.0.11
```

Notes:
- Each `bash_tool` call is a fresh shell — re-`export JAVA_HOME`/`PATH` every time
  (or call `javac` by absolute path).
- Use `bash -c` (not the default `sh`/dash) if you rely on `${PIPESTATUS[0]}`.
- The headless `jre` deb is required too — `javac` runs on the JVM, which lives in
  the jre package; the jdk-headless deb alone is not enough.

## Compiling jexter against PDFBox

```bash
export PDFBOX_JAR=/mnt/user-data/uploads/pdfbox-app-3.0.7.jar
javac -cp "$PDFBOX_JAR" -d build/out $(find build/src -name "*.java")
```

The `pdfbox-app-3.0.7.jar` is the shaded "app" jar — it already bundles PDFBox +
FontBox + commons-logging, so a single `-cp` entry is enough. The JDK source
launcher also runs a single-file program with the jar on the classpath, no build
step (used by the fidelity check's `RenderPdf.java`):

```bash
java -cp "$PDFBOX_JAR" RenderPdf.java in.pdf 1 out.png 144
```

## The CI gate is JDK 21 — a newer local JDK fails the build on lints 21 never had

`dangling-doc-comments` arrived after 21. On a JDK 25 workstation it fires on **62 files**, 61 of
them vendored JJ2000 whose 2002-era licence header is a `/** … */` sitting before the package
declaration, and `-Werror` turns the lot into `build failed`. The sandbox and the CI both run 21, so
neither sees it: this is a defect you only meet on the developer's own machine.

Two ways out, both legitimate:

```powershell
$env:JAVA_HOME = "<JDK 21>"          # the exact CI gate
$env:LINT = "all,-dangling-doc-comments"   # nimbus exposes the lint list for precisely this
```

Before reaching for either, check whether one of the warnings is OURS — one of the 62 was:
`OCDRenderer` carried **two consecutive doc comments** on one method, so the first attached to
nothing and its text was lost from the generated docs. The vendored 61 stay as they are; a licence
header is not ours to rewrite.
