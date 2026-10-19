#!/usr/bin/env node
/**
 * nimbus-jexter.cjs — Nimbus · the fat-jar + native app-image build deck.
 *
 * Same instrument deck, same SSE wiring and the same chrome as the rest of the Nimbus
 * family (nimbus-cloud.cjs), specialised for a Java project that vendors its jars in
 * lib/. One window, click the step you want — nothing chains automatically — and the
 * real javac / jar / jpackage output streams live into the console. Four actions, one
 * authority each (runAction):
 *
 *   02 BUILD (run on this machine — needs a JDK ≥ RELEASE for javac/jar; jpackage for the app-image)
 *     test     → compile src/ into SCRATCH_DIR/classes (the jar stage is left alone) and TEST_DIR/*.java
 *                against it into SCRATCH_DIR/test-classes, both at the CI gate, then run TEST_MAIN (a
 *                class in TEST_DIR) from the project root, with SOURCE_DATE_EPOCH pinned (the
 *                reproducible-builds clock) unless the shell already sets it. No TEST_MAIN → no test.
 *     jar      → compile (-Xlint:all -Werror, the CI gate) → stage resources → explode
 *                lib/*.jar → clean META-INF → pack the fat jar. Clears the previous
 *                deliverables first — INCLUDING the app-image.
 *     build    → jar, then jpackage the app-image: one bundled runtime, one .exe per
 *                LAUNCHERS entry (javaw), plus one <Name>.cmd each for a real console;
 *                then the app-image zipped as <APP_NAME>-<APP_VERSION>-<os>.zip — the
 *                distributable (a release asset), the folder being its unpacked form.
 *
 *   03 TOOLS
 *     tools    → which javac / jar / jpackage + their versions (the header readout
 *                probes the same authority on connect).
 *
 * No Maven, no network, no ~/.m2 — straight javac/jar/jpackage. The fat jar holds
 * every main class; the app-image emits one GUI launcher per entry in LAUNCHERS,
 * all sharing ONE bundled runtime (so the .exe needs nothing installed). The jar
 * needs a JDK ≥ RELEASE to RUN; the app-image bundles its own.
 *
 * Zero npm dependencies — pure Node (node:http + SSE, Node 18+). No CDN.
 *   node nimbus-jexter.cjs              → desktop-style window (fixed port 7333)
 *   node nimbus-jexter.cjs --serve      → headless, prints the URL
 *   node nimbus-jexter.cjs --run=build  → headless one-shot (CI): run an action, stream
 *                                      to stdout, exit with its code. <action> = any
 *                                      button id (test|jar|build|tools)
 *   node nimbus-jexter.cjs <projectDir> → point at a different project root (else .); every
 *                                   dir (src, lib, scratch, output) resolves under it
 *
 * Optional ./.env pre-fills the form (KEY=VALUE lines). Form keys (all optional):
 *   PROJECT_DIR JAR_NAME JAR_MAIN RELEASE APP_NAME APP_VERSION VENDOR
 *   SRC_DIR LIB_DIR SCRATCH_DIR OUT_DIR JPACKAGE_TYPE ICON_FILE
 *   LAUNCHERS    → Name:main.Class,Name:main.Class  (first = primary; default: APP_NAME → JAR_MAIN)
 *   JAR_PACKAGES → core,app  (top-level dirs under SRC_DIR that make the deliverable; default: all)
 *   LINT         → all  (-Xlint:<LINT>; e.g. all,-fallthrough when a warning is the lesson)
 *   PORT         → 7333  (one deck per project when two run side by side)
 *   Nothing project-specific lives in this file: the defaults are neutral, .env is the
 *   project (tracked), .env.local the machine (ignored); shell env beats both. Only the
 *   FILE NAME says which project it sits in, the way nimbus-cloud.cjs does.
 *
 * @author Jean-Luc Bloechle with Claude.ai
 */
const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const cp = require('node:child_process');

const DIR = __dirname;
const HEADLESS = process.argv.includes('--serve');
const RUN_ARG = (process.argv.find((a) => a.startsWith('--run=')) || '').slice(6); // headless one-shot action
const VERSION = '1.9';   // 1.9 (2026-10-19): requests from another origin or host are refused (any web page could POST /run); the `test` action — compile src/ and TEST_DIR into SCRATCH_DIR, run TEST_MAIN with SOURCE_DATE_EPOCH pinned: one command for the project's functional gate on any OS, the one CI runs too; 1.8 (2026-10-15): the AI bundler is gone (the `context` action, KEEP_FILES, SKIP_FILES) — the repo is the hand-off, AGENTS.md and the skill in .claude/skills/ travel with every clone; 1.7 (2026-10-05): OUT_DIR defaults to _artifacts, the convention every deck shares (it holds the PRODUCTS, not "production"); 1.6 (2026-09-11): the deck wears the cloud deck's chrome - a hint line under every section header, ONE tooltip system (every title is rendered by the deck: keyboard-reachable, scroll-following, styled), a grouped config drawer, a focus ring on every control, and a copy that strips the console prefixes; the blue palette, the cube mark and the tooltip of nimbus-server.cjs (whose #tip font named --sans, a token that does not exist - the shorthand was invalid and the box fell back to the body font); named after its project, like nimbus-cloud.cjs. Engine, bundler and actions unchanged; 1.5 (2026-09-02): the header no longer names a JDK version — RELEASE is the project's; 1.4 (2026-09-02): audit — no project name in the tool, one glob compiler, one in/out authority for the bundle, launchers/keep/skip through cfg, OUT_DIR under the project; 1.3 (2026-09-02): build also zips the app-image as <APP_NAME>-<APP_VERSION>-<os>.zip — the distributable; 1.2 (2026-09-01): binary containers by extension (a PDF read as utf8 is corrupt); 1.1: neutral defaults, .env is the project, library jars, zip + overview.md
// ── tiny .env loader (no dependency). Precedence: shell env > .env.local > .env ──
// .env is the project's configuration and is tracked; .env.local is the machine's
// (paths, a JDK, secrets) and is gitignored. Comments (#) and blank lines are fine.
(function loadEnv() {
    for (const name of ['.env.local', '.env']) {
        const f = path.join(DIR, name);
        if (!fs.existsSync(f)) continue;
        for (const line of fs.readFileSync(f, 'utf8').split(/\r?\n/)) {
            const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
            if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2].replace(/\s+#.*$/, '').replace(/^["']|["']$/g, '');
        }
    }
})();

const PORT = Number(process.env.PORT) || 7333;   // after .env: PORT= there gives each project its own deck

// Positional <projectDir> (first non-flag arg) overrides the default project root.
const ARG_PROJECT = process.argv.slice(2).find((a) => !a.startsWith('--'));

const env = (k, d = '') => (process.env[k] ?? d);
const DEFAULTS = {
    PROJECT_DIR: ARG_PROJECT || env('PROJECT_DIR', '.'),    // project root = this script's dir
    JAR_NAME:    env('JAR_NAME', ''),                       // '' → <project>.jar (lower-case root folder name)
    JAR_MAIN:    env('JAR_MAIN', ''),                       // `java -jar` entry point; '' → library jar (no Main-Class), and no app-image
    RELEASE:     env('RELEASE', '21'),                      // --release passed to javac (21 = the current LTS floor)
    LINT:        env('LINT', 'all'),                        // -Xlint:<LINT> (e.g. all,-fallthrough when a bug IS the lesson)
    APP_NAME:    env('APP_NAME', ''),                       // '' → root folder name; app-image folder + primary launcher
    APP_VERSION: env('APP_VERSION', '1.0.0'),               // numeric only (no -SNAPSHOT); keep equal to the declared version
    VENDOR:      env('VENDOR', ''),                         // '' → APP_NAME
    JAR_PACKAGES: env('JAR_PACKAGES', ''),                  // top-level dirs under SRC_DIR to compile+pack ('' = all): keeps e.g. solutions out of a student jar
    LAUNCHERS:   env('LAUNCHERS', ''),                      // Name:main.Class,… ('' → APP_NAME → JAR_MAIN); LAUNCHERS[0] is the primary
    // base constants (drawer — rarely change)
    SRC_DIR:       env('SRC_DIR', 'src'),
    LIB_DIR:       env('LIB_DIR', 'lib'),
    SCRATCH_DIR:   env('SCRATCH_DIR', 'target'),            // intermediate build output, under project root
    OUT_DIR:       env('OUT_DIR', '_artifacts'),                 // where deliverables land (created if missing)
    JPACKAGE_TYPE: env('JPACKAGE_TYPE', 'app-image'),       // folder + native launcher, no extra tooling
    ICON_FILE:     env('ICON_FILE', ''),                    // explicit .ico/.icns; else <name>.ico / app.ico in OUT_DIR
    TEST_DIR:      env('TEST_DIR', 'test'),                 // the test sources, compiled against src/ for the `test` action
    TEST_MAIN:     env('TEST_MAIN', ''),                    // the class the `test` action runs ('' = no test)
};

// Comma lists in .env (LAUNCHERS, JAR_PACKAGES): one splitter.
const list = (s) => String(s || '').split(',').map((x) => x.trim()).filter(Boolean);
// Resources copied from src/ onto the classpath (UI web assets ride along; .java,
// the big source archives and notes do not).
const RESOURCE_SKIP_EXT = ['.java', '.zip', '.md'];

// ════════════════════════════════ ENGINE ════════════════════════════════
let busy = false, child = null, cancelled = false;

// ── JDK tools + small fs helpers ─────────────────────────────────────────────
function jdkTool(name) {
    const exe = process.platform === 'win32' ? `${name}.exe` : name;
    if (process.env.JAVA_HOME) {
        const p = path.join(process.env.JAVA_HOME, 'bin', exe);
        if (fs.existsSync(p)) return p;
    }
    return exe;                                              // found on PATH
}
function walk(dir, test) {
    const out = [];
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const fp = path.join(dir, e.name);
        if (e.isDirectory()) out.push(...walk(fp, test));
        else if (test(fp)) out.push(fp);
    }
    return out;
}
const OS = { win32: 'win', darwin: 'mac' }[process.platform] || process.platform;   // the app-image is native: one zip per OS
const rmrf = (p) => fs.rmSync(p, { recursive: true, force: true });
const statSize = (p) => { try { return fs.statSync(p).size; } catch { return 0; } };
function dirStats(dir) {                                   // { files, bytes } under a folder (missing folder → 0/0)
    const t = { files: 0, bytes: 0 };
    let entries; try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return t; }
    for (const e of entries) {
        const fp = path.join(dir, e.name);
        if (e.isDirectory()) { const s = dirStats(fp); t.files += s.files; t.bytes += s.bytes; }
        else { t.files++; t.bytes += statSize(fp); }
    }
    return t;
}
function fmtSize(b) {
    if (b < 1024) return `${b} B`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`;
    return `${(b / (1024 * 1024)).toFixed(1)} MB`;
}

// Resolve every name/path/tool for a build from the (form-overridable) config.
// Pure: the derived values live here, the config is never written back to.
// Every dir (scratch, output, lib, src, icon) sits under the PROJECT root, so a
// deck pointed at another project leaves its deliverables in that project.
function ctx(cfg) {
    const root = path.resolve(DIR, cfg.PROJECT_DIR || '.');
    const appName = cfg.APP_NAME || path.basename(root);
    const jarName = cfg.JAR_NAME || `${path.basename(root).toLowerCase()}.jar`;
    // One app-image, several launchers, ONE bundled runtime: the fat jar holds every main
    // class, each launcher is just another .exe on a different main-class, all GUI (javaw).
    // launchers[0] is the primary (jpackage names it after APP_NAME); the rest ride along via
    // --add-launcher. No LAUNCHERS → one launcher, APP_NAME → JAR_MAIN. No JAR_MAIN → a
    // LIBRARY jar (no Main-Class, `java -jar` is not a thing) and no app-image.
    const launchers = list(cfg.LAUNCHERS).map((e) => { const [name, mainClass] = e.split(':'); return { name, mainClass }; });
    if (cfg.JAR_MAIN && !launchers.length) launchers.push({ name: appName, mainClass: cfg.JAR_MAIN });
    const outDir = path.resolve(root, cfg.OUT_DIR || '.');
    const scratch = path.join(root, cfg.SCRATCH_DIR || 'target');
    const lib = path.join(root, cfg.LIB_DIR || 'lib');
    const libJars = fs.existsSync(lib)
        ? fs.readdirSync(lib).filter((f) => f.toLowerCase().endsWith('.jar')).map((f) => path.join(lib, f))
        : [];
    return {
        root, outDir, scratch, lib, libJars, appName, jarName, launchers,
        vendor: cfg.VENDOR || appName,
        icon: cfg.ICON_FILE ? path.resolve(root, cfg.ICON_FILE) : '',
        src: path.join(root, cfg.SRC_DIR || 'src'),
        packages: list(cfg.JAR_PACKAGES),
        stage: path.join(scratch, 'jar-stage'),
        jpInput: path.join(scratch, 'jpackage-input'),
        jpLaunchers: path.join(scratch, 'jpackage-launchers'),
        outJar: path.join(outDir, jarName),
        appDir: path.join(outDir, appName),
        appZip: path.join(outDir, `${appName}-${cfg.APP_VERSION}-${OS}.zip`),   // the distributable: nobody ships a folder
        javac: jdkTool('javac'), jar: jdkTool('jar'), jpackage: jdkTool('jpackage'),
    };
}

const ANSI = /\x1b\[[0-9;]*m/g;   // strip SGR colour codes on display

// One streamed child process → Promise<exitCode>. Mirrors the cloud deck's runner:
// spawn (no shell), pump stdout/stderr line-by-line as SSE 'line' events.
function sh(bin, args, o, send) {
    o = o || {};
    return new Promise((resolve) => {
        send('line', { text: o.show || (path.basename(bin) + ' ' + args.join(' ')), kind: 'cmd' });
        let proc;
        try { proc = cp.spawn(bin, args, { cwd: o.cwd || DIR, env: o.env || process.env }); }
        catch (e) { send('line', { text: String(e.message || e), kind: 'err' }); return resolve(127); }
        child = proc;
        let tail = '';
        const pump = (buf) => {
            tail += buf.toString().replace(ANSI, '');
            const lines = tail.split(/\r?\n/);
            tail = lines.pop();
            for (const ln of lines) if (ln.length) send('line', { text: ln, kind: 'out' });
        };
        proc.stdout.on('data', pump);
        proc.stderr.on('data', pump);
        proc.on('error', (e) => { send('line', { text: String(e.message || e), kind: 'err' }); });
        proc.on('close', (code) => {
            if (tail.length) send('line', { text: tail, kind: 'out' });
            child = null;
            resolve(code == null ? 1 : code);
        });
    });
}
const guard = () => { if (cancelled) throw new Error('cancelled'); };

// ── build steps (async, streaming) ───────────────────────────────────────────

/** Fresh stage; clear previous deliverables. */
function prepare(c, send) {
    if (!fs.existsSync(c.src)) throw new Error(`no src dir: ${c.src}`);
    if (!fs.existsSync(c.lib)) send('line', { text: `no ${path.basename(c.lib)}/ — pure-JDK project, nothing to explode`, kind: 'log' });
    [c.stage, c.jpInput, c.jpLaunchers, c.outJar, c.appDir, c.appZip].forEach(rmrf);
    fs.mkdirSync(c.stage, { recursive: true });
    fs.mkdirSync(c.outDir, { recursive: true });
    send('line', { text: `project ${c.root}`, kind: 'info' });
    send('line', { text: `output  ${c.outDir}`, kind: 'info' });
}

/** Compile every src/**.java (or {@code sources}) against lib/*.jar (+ {@code extraCp}) into the stage
 *  (or {@code dest}). */
async function compile(cfg, c, send, dest = c.stage, sources = walkPackages(c, (fp) => fp.toLowerCase().endsWith('.java')), extraCp = []) {
    if (!sources.length) throw new Error('no .java sources found');
    const argFile = path.join(c.scratch, 'sources.txt');
    fs.mkdirSync(c.scratch, { recursive: true });
    fs.writeFileSync(argFile, sources.map((s) => `"${s.replace(/\\/g, '/')}"`).join('\n'));
    const cpath = [...extraCp, ...c.libJars];
    const code = await sh(c.javac, [...(cpath.length ? ['-cp', cpath.join(path.delimiter)] : []), '-d', dest,
            '--release', cfg.RELEASE, '-encoding', 'UTF-8', `-Xlint:${cfg.LINT}`, '-Werror', `@${argFile}`],   // the CI gate, verbatim: a warning is a defect
        { cwd: c.root, show: `javac --release ${cfg.RELEASE}  (${sources.length} sources)` }, send);
    fs.rmSync(argFile, { force: true });
    if (code !== 0) throw new Error(`javac exited ${code}`);
    send('line', { text: `compiled ${sources.length} sources`, kind: 'ok' });
}

/** src/ or only the JAR_PACKAGES subtrees of it — the deliverable's perimeter, one place. */
function walkPackages(c, test) {
    if (!c.packages.length) return walk(c.src, test);
    return c.packages.flatMap((p) => walk(path.join(c.src, p), test));
}

/** Copy non-Java resources from src/ into the stage, preserving layout. */
function stageResources(c, send) {
    const res = walkPackages(c, (fp) => !RESOURCE_SKIP_EXT.includes(path.extname(fp).toLowerCase()));
    for (const fp of res) {
        const dst = path.join(c.stage, path.relative(c.src, fp));
        fs.mkdirSync(path.dirname(dst), { recursive: true });
        fs.copyFileSync(fp, dst);
    }
    send('line', { text: `staged ${res.length} resource files`, kind: 'ok' });
}

/** Explode each lib jar into the stage (a shaded lib jar extracts flat like any other). */
async function explodeDeps(c, send) {
    for (const j of c.libJars) {
        guard();
        const code = await sh(c.jar, ['xf', j], { cwd: c.stage, show: `jar xf ${path.basename(j)}` }, send);
        if (code !== 0) throw new Error(`jar xf ${path.basename(j)} exited ${code}`);
    }
    send('line', { text: `exploded ${c.libJars.length} lib jars`, kind: 'ok' });
}

/** Drop stale META-INF that would break a merged jar (signatures, manifest, index). */
function cleanMeta(c, send) {
    const metaInf = path.join(c.stage, 'META-INF');
    if (!fs.existsSync(metaInf)) return;
    let n = 0;
    for (const f of fs.readdirSync(metaInf)) {
        if (/\.(SF|DSA|RSA)$/i.test(f) || f === 'MANIFEST.MF' || f === 'INDEX.LIST') {
            fs.rmSync(path.join(metaInf, f), { force: true }); n++;
        }
    }
    if (n) send('line', { text: `cleaned ${n} stale META-INF entries`, kind: 'ok' });
}

/** Assemble the fat jar — `--main-class` only when JAR_MAIN names one (else a library jar). */
async function pack(cfg, c, send) {
    const code = await sh(c.jar, ['--create', '--file', c.outJar, ...(cfg.JAR_MAIN ? ['--main-class', cfg.JAR_MAIN] : []), '-C', c.stage, '.'],
        { cwd: c.root, show: `jar --create ${c.jarName}` }, send);
    if (code !== 0) throw new Error(`jar --create exited ${code}`);
    send('line', { text: `jar ${c.jarName}  ${fmtSize(statSize(c.outJar))}`, kind: 'ok' });
}

/** Icon for a launcher: explicit ICON_FILE, else <name>.ico, else app.ico in OUT_DIR. */
function iconFor(c, name) {
    return [c.icon, path.join(c.outDir, `${name}.ico`), path.join(c.outDir, 'app.ico')].find((p) => p && fs.existsSync(p)) || null;
}

/** Wrap the fat jar into ONE native app-image holding every launcher in LAUNCHERS. */
async function packageExe(cfg, c, send) {
    const probe = cp.spawnSync(c.jpackage, ['--version'], { stdio: 'ignore' });
    if (probe.error || probe.status !== 0) {
        send('line', { text: 'jpackage not found — skipped (.exe); the jar is still built', kind: 'log' });
        return false;
    }
    if (!c.launchers.length) throw new Error('no launcher: set JAR_MAIN (or LAUNCHERS) in .env — a library jar has no app-image');
    const [primary, ...extra] = c.launchers;
    rmrf(c.jpInput); fs.mkdirSync(c.jpInput, { recursive: true });
    fs.copyFileSync(c.outJar, path.join(c.jpInput, c.jarName));   // jpackage bundles the whole --input dir
    rmrf(c.jpLaunchers); fs.mkdirSync(c.jpLaunchers, { recursive: true });
    rmrf(c.appDir);                                                 // jpackage refuses to overwrite

    const args = ['--type', cfg.JPACKAGE_TYPE, '--name', c.appName, '--app-version', cfg.APP_VERSION,
        '--input', c.jpInput, '--main-jar', c.jarName, '--main-class', primary.mainClass, '--dest', c.outDir, '--vendor', c.vendor];
    const pIcon = iconFor(c, primary.name);
    if (pIcon) args.push('--icon', pIcon);
    for (const L of extra) {   // each extra launcher → one .properties (same jar, its own main-class), all GUI
        const props = [`main-class=${L.mainClass}`];
        const icon = iconFor(c, L.name);
        if (icon) props.push(`icon=${icon.replace(/\\/g, '/')}`);
        const pf = path.join(c.jpLaunchers, `${L.name}.properties`);
        fs.writeFileSync(pf, props.join('\n') + '\n');
        args.push('--add-launcher', `${L.name}=${pf}`);
    }
    const code = await sh(c.jpackage, args, { cwd: c.root, show: `jpackage --type ${cfg.JPACKAGE_TYPE} ${c.appName}` }, send);
    rmrf(c.jpInput); rmrf(c.jpLaunchers);
    if (code !== 0) throw new Error(`jpackage exited ${code}`);
    if (process.platform === 'win32') {   // one .cmd per launcher: same main class, bundled runtime, the caller's console.
        // The .exe launchers are javaw — fine for the windows, MUTE for a headless run
        // (`Tool in.pdf out.pdf` would run with no output and no visible error).
        for (const L of c.launchers) {
            const cmd = ['@echo off', `"%~dp0runtime\\bin\\java.exe" -cp "%~dp0app\\${c.jarName}" ${L.mainClass} %*`, ''].join('\r\n');
            try { fs.writeFileSync(path.join(c.appDir, `${L.name}.cmd`), cmd); } catch { /* best-effort */ }
        }
    }
    send('line', { text: `app-image ${c.appName}  ${fmtSize(dirStats(c.appDir).bytes)}`, kind: 'ok' });
    // The zip IS the deliverable (a 100+ MB folder is not something one attaches to a
    // release). `jar` is a zip writer the JDK already gives us: streams, no manifest,
    // the app folder as the single top-level entry — what Compress-Archive would do.
    const zcode = await sh(c.jar, ['--create', '--no-manifest', '--file', c.appZip, '-C', c.outDir, c.appName],
        { cwd: c.root, show: `jar --create ${path.basename(c.appZip)}` }, send);
    if (zcode !== 0) throw new Error(`jar (zip) exited ${zcode}`);
    send('line', { text: `zip ${path.basename(c.appZip)}  ${fmtSize(statSize(c.appZip))}`, kind: 'ok' });
    return true;
}

// ── actions (each resolves to an exit code; throw → non-zero) ─────────────────
async function doBuild(cfg, exe, send) {
    const c = ctx(cfg);
    prepare(c, send);
    await compile(cfg, c, send); guard();
    stageResources(c, send); guard();
    await explodeDeps(c, send); guard();
    cleanMeta(c, send);
    await pack(cfg, c, send); guard();
    let exeBuilt = false;
    if (exe) exeBuilt = await packageExe(cfg, c, send);
    send('line', { text: `deliverables in ${c.outDir}`, kind: 'ok' });
    send('line', { text: `    ${c.jarName}`, kind: 'out' });
    if (exeBuilt) {   // jpackage names the PRIMARY launcher after --name, not after its label; every .cmd keeps the label
        c.launchers.forEach((L, i) => send('line', { text: `    ${c.appName}/${i ? L.name : c.appName}  (+ ${L.name}.cmd for the console)`, kind: 'out' }));
        send('line', { text: `    ${path.basename(c.appZip)}`, kind: 'out' });
    }
}
// The project's own test, on fresh classes: the jar's stage and the deliverables are not touched.
async function doTest(cfg, send) {
    if (!cfg.TEST_MAIN) throw new Error('no TEST_MAIN in .env — this project declares no test');
    const c = ctx(cfg);
    const classes = path.join(c.scratch, 'classes'), tests = path.join(c.scratch, 'test-classes');
    for (const d of [classes, tests]) { rmrf(d); fs.mkdirSync(d, { recursive: true }); }
    await compile(cfg, c, send, classes); guard();
    await compile(cfg, c, send, tests, walk(path.join(c.root, cfg.TEST_DIR), (fp) => fp.endsWith('.java')), [classes]); guard();
    const env = Object.assign({}, process.env, { SOURCE_DATE_EPOCH: process.env.SOURCE_DATE_EPOCH || '1000000000' });
    const code = await sh(jdkTool('java'), ['-cp', [tests, classes, ...c.libJars].join(path.delimiter), cfg.TEST_MAIN],
        { cwd: c.root, env, show: `java ${cfg.TEST_MAIN}` }, send);
    if (code !== 0) throw new Error(`${cfg.TEST_MAIN} exited ${code}`);
    send('line', { text: 'test passed', kind: 'ok' });
}
// Probe the three JDK binaries once. Shared by the header readout (passive, on
// connect) and the headless `--run=tools` diagnostic. One authority, two readers.
function probeTools(cfg) {
    const c = ctx(cfg);
    return ['javac', 'jar', 'jpackage'].map((t) => {
        const r = cp.spawnSync(c[t], ['--version'], { encoding: 'utf8' });
        if (r.error || r.status !== 0) return { tool: t, ok: false, version: null };
        const line = (r.stdout || r.stderr || '').trim().split(/\r?\n/)[0];
        const m = line.match(/\b(\d+[\d._]*)/);
        return { tool: t, ok: true, version: m ? m[1] : line };
    });
}
function doTools(cfg, send) {
    send('line', { text: `JAVA_HOME ${process.env.JAVA_HOME || '(unset — using PATH)'}`, kind: 'info' });
    return (async () => {
        for (const p of probeTools(cfg))
            send('line', p.ok ? { text: `${p.tool.padEnd(9)} ${p.version}`, kind: 'out' }
                              : { text: `${p.tool.padEnd(9)} not found`, kind: 'err' });
        send('line', { text: 'tools probed', kind: 'ok' });
    })();
}
// Dispatch one action → Promise (resolves when finished). Sync actions are wrapped.
// Incoming config is merged over DEFAULTS so a partial form payload can't leave a
// path undefined in ctx().
function runAction(action, cfg, send) {
    cfg = Object.assign({}, DEFAULTS, cfg || {});
    switch (action) {
        case 'test':    return doTest(cfg, send);           // the project's own gate, on fresh classes
        case 'build':   return doBuild(cfg, true,  send);   // full chain, through jpackage
        case 'jar':     return doBuild(cfg, false, send);   // stops before the app-image
        case 'tools':   return Promise.resolve(doTools(cfg, send));
        default:        return Promise.reject(new Error('unknown action: ' + action));
    }
}

// ══════════════════════════════════ SSE host ══════════════════════════════════
const clients = new Set();
let everConnected = false, exitTimer = null;
function send(type, data = {}) {
    const m = 'data: ' + JSON.stringify(Object.assign({ type }, data)) + '\n\n';
    for (const res of clients) { try { res.write(m); } catch { /* gone */ } }
}
function armExit() {
    if (HEADLESS || !everConnected || clients.size || busy) return;
    clearTimeout(exitTimer);
    exitTimer = setTimeout(() => { if (!clients.size && !busy) { console.log('bye'); process.exit(0); } }, 3000);
}
function readBody(req) {
    return new Promise((res) => { let b = ''; req.on('data', (c) => b += c); req.on('end', () => res(b)); });
}

// Only this deck's own page may drive it. Binding 127.0.0.1 keeps other machines out, not other web
// pages: any site can POST to a local port (a "simple" request needs no preflight), and /run compiles,
// runs and deletes the folders its body names. So a request whose Host is not this deck (DNS
// rebinding), whose Origin is another site, or that the browser flags cross-site is refused unread.
const SELF = new Set([`127.0.0.1:${PORT}`, `localhost:${PORT}`]);
function foreign(req) {
    if (!SELF.has(req.headers.host)) return true;
    const origin = req.headers.origin;
    if (origin !== undefined && !(origin.startsWith('http://') && SELF.has(origin.slice(7)))) return true;
    const site = req.headers['sec-fetch-site'];
    return site !== undefined && site !== 'same-origin' && site !== 'none';
}

const server = http.createServer(async (req, res) => {
    if (foreign(req)) { res.writeHead(403).end('forbidden'); return; }
    if (req.url === '/') { res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }); return res.end(PAGE); }
    if (req.url === '/api/defaults') { res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ defaults: DEFAULTS, app: ctx(DEFAULTS).appName, version: VERSION, tools: probeTools(DEFAULTS) })); }
    if (req.url === '/events') {
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
        res.write('retry: 2000\n\n');
        res.write('data: ' + JSON.stringify({ type: 'hello', version: VERSION, running: busy }) + '\n\n');
        clients.add(res); everConnected = true; clearTimeout(exitTimer);
        req.on('close', () => { clients.delete(res); armExit(); });
        return;
    }
    if (req.url === '/run' && req.method === 'POST') {
        let body;
        try { body = JSON.parse((await readBody(req)) || '{}'); }
        catch { res.writeHead(400).end('bad json'); return; }
        if (busy) { res.writeHead(409).end('busy'); return; }
        busy = true; cancelled = false;
        send('start', { action: body.action });
        Promise.resolve().then(() => runAction(body.action, body.config, send))
            .then(() => send('done', { code: cancelled ? 130 : 0 }))
            .catch((e) => { send('line', { text: String((e && e.message) || e), kind: 'err' }); send('done', { code: 1 }); })
            .finally(() => { busy = false; armExit(); });
        res.writeHead(202).end('ok');
        return;
    }
    if (req.url === '/stop' && req.method === 'POST') {
        cancelled = true;
        if (child) { try { child.kill(); } catch { /* already gone */ } }
        send('line', { text: 'stopped', kind: 'err' });
        res.writeHead(200).end('ok');
        return;
    }
    res.writeHead(404).end();
});

// ── headless one-shot (CI): run one action, stream to stdout, exit with its code ──
if (RUN_ARG) {
    const PRE = { cmd: '$ ', err: '! ', ok: '\u2713 ', log: '\u2502 ', info: '\u00b7 ', out: '  ' };
    const out = (type, d = {}) => { if (type === 'line') process.stdout.write((PRE[d.kind] || '  ') + d.text + '\n'); };
    busy = true; cancelled = false;
    Promise.resolve().then(() => runAction(RUN_ARG, DEFAULTS, out))
        .then(() => process.exit(0))
        .catch((e) => { process.stderr.write('build failed: ' + ((e && e.message) || e) + '\n'); process.exit(1); });
} else {
    server.listen(PORT, '127.0.0.1', function () {
        const url = 'http://127.0.0.1:' + this.address().port;
        console.log(`nimbus · ${ctx(DEFAULTS).appName} build deck v${VERSION} \u2192 ${url}`);
        if (!HEADLESS) openWindow(url);
    });
}

function openWindow(url) {
    const find = {
        win32: () => [
            process.env['ProgramFiles'] + '\\Google\\Chrome\\Application\\chrome.exe',
            process.env['ProgramFiles(x86)'] + '\\Google\\Chrome\\Application\\chrome.exe',
            process.env['LocalAppData'] + '\\Google\\Chrome\\Application\\chrome.exe',
            process.env['ProgramFiles(x86)'] + '\\Microsoft\\Edge\\Application\\msedge.exe',
            process.env['ProgramFiles'] + '\\Microsoft\\Edge\\Application\\msedge.exe',
        ].filter((p) => fs.existsSync(p)),
        darwin: () => ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'].filter((p) => fs.existsSync(p)),
        linux: () => ['google-chrome', 'chromium', 'chromium-browser', 'microsoft-edge'].map((b) => { try { return cp.execSync('which ' + b).toString().trim(); } catch { return ''; } }).filter(Boolean),
    }[process.platform];
    const cand = find ? find() : [];
    if (cand.length) cp.spawn(cand[0], ['--app=' + url, '--window-size=1140,1000'], { detached: true, stdio: 'ignore' }).unref();
    else { const o = { win32: 'start ""', darwin: 'open' }[process.platform] || 'xdg-open'; cp.exec(o + ' ' + url); }
}

// ─── PAGE: self-contained instrument UI (no CDN) ──────────────────────────────
const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>nimbus</title>
<!-- the same cube as the header logo, inline: a deck is one file and fetches nothing -->
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><path d='M50 8 L88 28 L50 48 L12 28 Z' fill='%23BFD9F2'/><path d='M12 28 L50 48 L50 92 L12 72 Z' fill='%235E9BD6'/><path d='M88 28 L50 48 L50 92 L88 72 Z' fill='%2333699F'/><path d='M50 8 L88 28 L50 48 L12 28 Z M12 28 L50 48 L50 92 L12 72 Z M88 28 L50 48 L50 92 L88 72 Z' fill='none' stroke='%230F1318' stroke-width='3' stroke-linejoin='round'/></svg>">
<style>
/* ════════════ TOKENS — dark instrument, one blue accent ════════════ */
:root{
 --bg:#14181e; --bg-2:#0f1318;                 /* anthracite — lifted off pure black */
 --panel:#1a1f28; --panel-2:#1e242e;           /* control panels / field face */
 --line:rgba(231,238,250,.08); --line-2:rgba(231,238,250,.15);
 --txt:#cfd6e1; --dim:#8d97a6; --mut:#5b6573;   /* soft off-white text, never #fff on black */
 --accent:#63b3ed; --accent-fill:#4a9fe0; --accent-dim:#3d7ca8; --accent-ink:#06121c; --accent-soft:rgba(99,179,237,.16);
 --warn:#e0a23a; --err:#ff7a7a;
 --ui:"SF Pro Display","Segoe UI Variable","Segoe UI",system-ui,-apple-system,sans-serif;
 --mono:"JetBrains Mono","SF Mono","Cascadia Code","Consolas",ui-monospace,monospace;
}
*{box-sizing:border-box}
html,body{height:100%}
body{
 margin:0;color:var(--txt);font:13px/1.55 var(--ui);overflow:hidden;-webkit-font-smoothing:antialiased;
 background:
  radial-gradient(120% 70% at 50% -10%,rgba(231,238,250,.025),transparent 60%),
  var(--bg);
}
/* faint blueprint grid */
body::before{
 content:"";position:fixed;inset:0;z-index:-1;pointer-events:none;
 background-image:
  linear-gradient(rgba(231,238,250,.022) 1px,transparent 1px),
  linear-gradient(90deg,rgba(231,238,250,.022) 1px,transparent 1px);
 background-size:34px 34px;
 -webkit-mask-image:radial-gradient(135% 100% at 50% 0%,#000,transparent 88%);
 mask-image:radial-gradient(135% 100% at 50% 0%,#000,transparent 88%);
}
.ic{width:14px;height:14px;flex:none;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
.mono{font-family:var(--mono)}

.wrap{display:flex;flex-direction:column;height:100vh;padding:15px 16px;gap:13px}

/* ───── header plate ───── */
.hdr{display:flex;align-items:center;gap:14px;border:1px solid var(--line-2);border-radius:8px;
 background:linear-gradient(180deg,var(--panel),#0f131a);padding:12px 15px;position:relative}
.logo{width:36px;height:36px;flex:none;display:grid;place-items:center;color:var(--accent)}
.logo .ic{width:36px;height:36px}
.title{display:flex;flex-direction:column;gap:3px;line-height:1}
.title b{font-size:18px;font-weight:750;letter-spacing:.18em;color:#dde3ec}
.title small{font:10px var(--mono);letter-spacing:.18em;text-transform:uppercase;color:var(--mut)}
.hdr .spacer{margin-left:auto}
.readout{display:inline-flex;align-items:center;gap:8px;font:10.5px var(--mono);letter-spacing:.14em;text-transform:uppercase;
 color:var(--dim);padding:7px 12px;border:1px solid var(--line-2);border-radius:6px;background:var(--bg-2)}
.dot{width:7px;height:7px;border-radius:50%;background:var(--mut);transition:background .2s}
.dot.on{background:var(--accent);box-shadow:0 0 0 0 var(--accent-soft);animation:pulse 2.4s ease-in-out infinite}
.dot.off{background:var(--err)}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(99,179,237,0)}50%{box-shadow:0 0 0 4px rgba(99,179,237,.18)}}
.ver{font:10.5px var(--mono);letter-spacing:.14em;color:var(--mut);padding:7px 11px;border:1px solid var(--line);border-radius:6px}
/* The toolchain readout is the one live fact this deck cannot do without: no javac, no jar. */
.readout.jdk{letter-spacing:.06em;text-transform:none;color:var(--mut)}
.readout.jdk b{font-weight:600;color:var(--dim)}
.readout.jdk.bad{color:var(--err);border-color:rgba(224,90,90,.35)}
.cog{appearance:none;cursor:pointer;display:inline-flex;align-items:center;gap:7px;color:var(--dim);
 font:10.5px var(--mono);letter-spacing:.14em;text-transform:uppercase;padding:7px 12px;border-radius:6px;
 background:var(--panel-2);border:1px solid var(--line-2);transition:color .15s,border-color .15s,background .15s}
.cog:hover{color:var(--accent);border-color:var(--accent)}
.cog.x{padding:7px;width:30px;height:30px;justify-content:center}   /* an icon button is square */

/* ───── two-column operating layout ───── */
.main{display:grid;grid-template-columns:430px 1fr;gap:13px;flex:1;min-height:0}
.left{display:flex;flex-direction:column;gap:16px;overflow:auto;padding:0 4px 4px 0;min-height:0}
.right{display:flex;flex-direction:column;gap:11px;min-height:0}

/* section header — numbered, editorial */
.sec-h{display:flex;align-items:center;gap:11px;margin-bottom:11px}
.sec-h .no{font:750 12px var(--mono);color:var(--accent-dim);letter-spacing:.05em}
.sec-h .ti{font-size:10px;font-weight:600;text-transform:uppercase;letter-spacing:.28em;color:var(--dim)}
.sec-h::after{content:"";flex:1;height:1px;background:linear-gradient(90deg,var(--line-2),transparent)}

/* fields */
.fields{display:grid;grid-template-columns:1fr 1fr;gap:11px}
.f{display:flex;flex-direction:column;gap:5px;min-width:0}
.f.full{grid-column:span 2}
.f label{font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.12em;color:var(--mut)}
input,select{width:100%;color:var(--txt);font:12.5px var(--mono);padding:8px 11px;outline:none;
 background:var(--bg-2);border:1px solid var(--line-2);border-radius:5px;
 box-shadow:inset 0 1px 2px rgba(0,0,0,.4);transition:border-color .15s,box-shadow .15s,background .15s}
select{cursor:pointer}
input::placeholder{color:var(--mut)}
input:hover,select:hover{border-color:rgba(231,238,250,.24)}
input:focus,select:focus{border-color:var(--accent);background:#151b22;box-shadow:0 0 0 3px var(--accent-soft)}

/* actions */
.hint{margin:-2px 0 10px; font-size:12.5px; line-height:1.5; color:var(--dim); max-width:54ch}
/* One focus ring for everything that takes focus — a deck driven at the keyboard must show where
   it is. */
.btn:focus-visible,.clear:focus-visible,.cog:focus-visible,input:focus-visible,select:focus-visible,
a:focus-visible{outline:2px solid var(--accent); outline-offset:2px}
.acts{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.btn{appearance:none;cursor:pointer;display:inline-flex;align-items:center;gap:9px;
 font:600 10.5px var(--mono);text-transform:uppercase;letter-spacing:.1em;color:var(--txt);
 background:var(--panel-2);border:1px solid var(--line-2);border-radius:5px;padding:10px 12px;
 transition:color .15s,border-color .15s,background .15s,filter .12s,transform .1s,box-shadow .15s}
.btn .ic{color:var(--mut);transition:color .15s}
.btn:not(.go):hover{color:#e7ebf2;border-color:var(--accent);background:#222a34}
.btn:not(.go):hover .ic{color:var(--accent)}
.btn:active{transform:translateY(1px)}
.btn:disabled{opacity:.35;cursor:not-allowed;filter:none;background:var(--panel-2);border-color:var(--line-2)}
.btn:disabled .ic{color:var(--mut)}
.btn.go{color:var(--accent-ink);font-weight:700;background:var(--accent-fill);border-color:transparent}
.btn.go .ic{color:var(--accent-ink)}
.btn.go:hover{filter:brightness(1.07)}
.btn.danger{color:var(--err);border-color:rgba(255,122,122,.28)}
.btn.danger:hover{color:#e7ebf2;background:rgba(255,122,122,.12);border-color:var(--err)}
.btn.danger:hover .ic{color:var(--err)}

/* ───── console frame (viewfinder corners) ───── */
.con-frame{position:relative;flex:1;min-height:0;display:flex;flex-direction:column;
 border:1px solid var(--line-2);border-radius:7px;background:var(--bg-2);overflow:hidden}
.cn{position:absolute;width:11px;height:11px;border:1.5px solid var(--accent);opacity:.55;pointer-events:none;z-index:2}
.cn.tl{top:7px;left:7px;border-right:0;border-bottom:0}
.cn.tr{top:7px;right:7px;border-left:0;border-bottom:0}
.cn.bl{bottom:7px;left:7px;border-right:0;border-top:0}
.cn.br{bottom:7px;right:7px;border-left:0;border-top:0}
.con-head{display:flex;align-items:center;gap:11px;padding:10px 16px 10px 20px;border-bottom:1px solid var(--line)}
.con-head .lbl{font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.26em;color:var(--mut)}
.con-head .act{font:10.5px var(--mono);letter-spacing:.06em;color:var(--accent-dim);
 padding:3px 10px;border:1px solid var(--line-2);border-radius:99px}
.con-head .spacer{margin-left:auto}
.con-head .clear{appearance:none;cursor:pointer;display:inline-flex;align-items:center;gap:6px;color:var(--mut);
 font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.14em;padding:5px 10px;border-radius:5px;
 background:transparent;border:1px solid transparent;transition:color .15s,border-color .15s}
.con-head .clear:hover{color:var(--err);border-color:rgba(255,122,122,.3)}
.con-head .clear .ic{width:12px;height:12px}
.log{flex:1;min-height:0;overflow:auto;padding:13px 18px;font:12.5px/1.7 var(--mono);white-space:pre-wrap;word-break:break-word}
.log:empty::before{content:"\u258e ready";color:var(--accent-dim);animation:blink 1.15s step-end infinite}
@keyframes blink{50%{opacity:.25}}
.log div{padding:1px 0}
.log .t{color:var(--mut);margin-right:13px;user-select:none}
.log .out{color:var(--txt)} .log .cmd{color:var(--accent)}
.log .ok{color:var(--accent)} .log .err{color:var(--err)}
.log .info{color:var(--dim)} .log .log{color:var(--warn)}

/* transport */
.transport{display:flex;align-items:center;gap:12px}
.gauge{flex:1;height:11px;border:1px solid var(--line-2);border-radius:3px;background:var(--bg-2);position:relative;overflow:hidden;
 background-image:repeating-linear-gradient(90deg,var(--line) 0 1px,transparent 1px 26px)}
.gauge>i{display:block;height:100%;width:0;background:var(--accent-dim);transition:width .2s}
.gauge.run>i{width:100%;background:linear-gradient(90deg,transparent,var(--accent),transparent);
 background-size:240% 100%;animation:scan 2.4s linear infinite}
@keyframes scan{from{background-position:240% 0}to{background-position:-240% 0}}
.clock{font:11px var(--mono);color:var(--dim);min-width:50px;text-align:right;letter-spacing:.04em}

/* footer */
.foot{display:flex;align-items:center;gap:14px;font:10px var(--mono);letter-spacing:.06em;color:var(--mut);padding:0 3px}
.foot .credit{margin-left:auto;text-transform:uppercase;letter-spacing:.16em}

/* drawer */
.scrim{position:fixed;inset:0;z-index:90;background:rgba(5,7,10,.6);backdrop-filter:blur(2px);opacity:0;pointer-events:none;transition:opacity .22s}
.scrim.open{opacity:1;pointer-events:auto}
.drawer{position:fixed;top:15px;right:15px;bottom:15px;width:640px;max-width:94vw;z-index:91;border-radius:8px;
 display:flex;flex-direction:column;gap:13px;padding:18px 20px;overflow:auto;
 border:1px solid var(--line-2);background:linear-gradient(180deg,var(--panel),#0c1016);
 box-shadow:-20px 0 60px -30px rgba(0,0,0,.9);transform:translateX(calc(100% + 24px));transition:transform .3s cubic-bezier(.4,0,.2,1)}
.drawer.open{transform:translateX(0)}
.dgrp{margin-bottom:16px}
.dgt{font:10.5px var(--mono);letter-spacing:.14em;text-transform:uppercase;color:var(--accent);
     padding-bottom:5px;margin-bottom:9px;border-bottom:1px solid var(--line)}
.dgrid{display:grid;grid-template-columns:1fr 1fr;gap:10px 12px}
.dgrid .full{grid-column:1/-1}
.df .hint{margin-top:3px;font-size:10.5px;line-height:1.4;color:var(--mut)}
.df .hint.warn{color:var(--warn)}
.dfoot{display:flex;align-items:center;gap:12px;flex-wrap:wrap;
       border-top:1px solid var(--line);padding-top:12px;margin-top:4px}
.dfoot .hint{flex:1 1 200px;font-size:10.5px;line-height:1.45;color:var(--mut);margin:0}
.fields.tight{margin-top:9px;gap:8px}

/* ONE explanation system. The native title fires after a delay you cannot control, is invisible to
   the keyboard, and cannot be styled — so every title becomes a data-tip and this box renders it,
   next to the control it belongs to rather than in a strip at the far end of the panel. */
::selection{background:var(--accent-soft);color:var(--txt)}
@media (prefers-reduced-motion:reduce){ *{transition:none !important} }
#tip{position:fixed;z-index:120;max-width:320px;padding:9px 11px;pointer-events:none;
  background:var(--panel-2);border:1px solid var(--line-2);border-radius:6px;
  font:12px/1.5 var(--ui);color:var(--txt);
  box-shadow:0 8px 24px rgba(0,0,0,.45);
  opacity:0;transform:translateX(-4px);transition:opacity .12s,transform .12s}
#tip.on{opacity:1;transform:translateX(0)}
#tip b{display:block;color:var(--accent);font:10px var(--mono);letter-spacing:.13em;
  text-transform:uppercase;margin-bottom:4px}
.dh{display:flex;align-items:baseline;gap:11px;padding-bottom:12px;border-bottom:1px solid var(--line)}
.dh .no{font:750 12px var(--mono);color:var(--accent-dim)}
.dh b{font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.24em;color:var(--dim)}
.dh .x{margin-left:auto}
.df{display:flex;flex-direction:column;gap:5px}
.df label{font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.12em;color:var(--mut)}
.note{font:10.5px/1.55 var(--mono);color:var(--warn);padding:11px 13px;border-radius:5px;
 background:rgba(224,162,58,.08);border:1px solid rgba(224,162,58,.26)}
.hint{font:9.5px var(--mono);color:var(--mut);letter-spacing:.02em}

::-webkit-scrollbar{width:10px;height:10px}
::-webkit-scrollbar-thumb{background:var(--line-2);border-radius:99px;border:3px solid transparent;background-clip:padding-box}
::-webkit-scrollbar-thumb:hover{background:var(--mut);background-clip:padding-box}
::-webkit-scrollbar-track{background:transparent}
</style>
</head>
<body>

<!-- inline icon sprite (lucide-style, zero deps) -->
<svg width="0" height="0" style="position:absolute" aria-hidden="true"><defs>
 <symbol id="i-cube" viewBox="0 0 100 100"><path d="M50 8 L88 28 L50 48 L12 28 Z" style="fill:#BFD9F2;stroke:none"/><path d="M12 28 L50 48 L50 92 L12 72 Z" style="fill:#5E9BD6;stroke:none"/><path d="M88 28 L50 48 L50 92 L88 72 Z" style="fill:#33699F;stroke:none"/><path d="M50 8 L88 28 L50 48 L12 28 Z M12 28 L50 48 L50 92 L12 72 Z M88 28 L50 48 L50 92 L88 72 Z" style="fill:none;stroke:#0F1318;stroke-width:3;stroke-linejoin:round"/></symbol>
 <symbol id="i-cog" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></symbol>
 <symbol id="i-package" viewBox="0 0 24 24"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.3 7 12 12l8.7-5"/><path d="M12 22V12"/></symbol>
 <symbol id="i-layers" viewBox="0 0 24 24"><path d="M12 2 2 7l10 5 10-5z"/><path d="m2 17 10 5 10-5"/><path d="m2 12 10 5 10-5"/></symbol>
 <symbol id="i-check" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><path d="m9 12 2 2 4-4"/></symbol>
 <symbol id="i-tool" viewBox="0 0 24 24"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></symbol>
 <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></symbol>
 <symbol id="i-stop" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2.5"/></symbol>
 <symbol id="i-x" viewBox="0 0 24 24"><path d="M18 6 6 18"/><path d="m6 6 12 12"/></symbol>
 <symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></symbol>
 <symbol id="i-trash" viewBox="0 0 24 24"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></symbol>
</defs></svg>

<div class="wrap">

  <div class="hdr">
    <div class="logo"><svg class="ic"><use href="#i-cube"/></svg></div>
    <div class="title"><b>NIMBUS</b><small id="deck">Build Deck</small></div>
    <div class="spacer"></div>
    <span class="readout"><span class="dot" id="dot"></span><span id="conn">link…</span></span>
    <span class="readout jdk" id="jdk" title="The three JDK binaries this deck shells out to, as found through JAVA_HOME or the PATH. No javac, no jar — nothing builds; no jpackage — the jar is still built and the app-image is skipped.">probing…</span>
    <span class="ver" id="ver">—</span>
    <button class="cog" id="b-cfg" title="Build constants — identity, paths, packaging. Set once, then forget."><svg class="ic"><use href="#i-cog"/></svg> config</button>
  </div>

  <div class="main">
    <div class="left">

      <section>
        <div class="sec-h"><span class="no">01</span><span class="ti">Project</span></div>
        <p class="hint">What is built, and where. Everything else — identity, paths, packaging —
           is behind <b>config</b>, top right.</p>
        <div class="fields">
          <div class="f full"><label>project dir</label><input id="f-PROJECT_DIR" title="The project root. Blank or '.' means the folder this deck sits in; src/, lib/ and the output dir all resolve under it, so a deck pointed elsewhere leaves its deliverables there." placeholder="."></div>
          <div class="f full"><label>main class · java -jar</label><input id="f-JAR_MAIN" title="The class java -jar starts. Leave it blank for a LIBRARY jar: no Main-Class, and no app-image."></div>
          <div class="f"><label>jar name</label><input id="f-JAR_NAME" title="Name of the fat jar in the output dir. Blank names it after the project folder, lower-case."></div>
        </div>
      </section>

      <section>
        <div class="sec-h"><span class="no">02</span><span class="ti">Build</span></div>
        <p class="hint">javac at the CI gate — <b>-Xlint -Werror</b>, a warning is a defect — then the
           resources, the lib jars exploded in, and one fat jar. Both actions clear the previous
           deliverables first, the app-image included.</p>
        <div class="acts">
          <button class="btn" id="b-test" title="Compile src/ and test/ into target/, then run the project's own test (TEST_MAIN in .env) with the reproducible clock pinned — the gate CI runs on every push. Touches neither the jar stage nor the deliverables."><svg class="ic"><use href="#i-check"/></svg>test</button>
          <button class="btn" id="b-jar" title="Compile src/ against lib/*.jar, stage the resources, explode the lib jars, clean META-INF and pack the fat jar. Stops there — and DELETES the app-image left by the last build, so there is no .exe until the next one."><svg class="ic"><use href="#i-layers"/></svg>compile jar</button>
          <button class="btn go" id="b-build" title="The jar, then jpackage: one bundled runtime, one .exe per LAUNCHERS entry (plus a .cmd each, because the .exe are javaw and mute), then the whole app-image zipped as the distributable."><svg class="ic"><use href="#i-package"/></svg>build app</button>
        </div>
      </section>

      <section>
        <div class="sec-h"><span class="no">03</span><span class="ti">Tools</span></div>
        <p class="hint">What this machine actually offers to build with.</p>
        <div class="acts">
          <button class="btn" id="b-tools" title="Print JAVA_HOME and the version of each of javac, jar and jpackage — the same probe the header readout shows, written out in the console."><svg class="ic"><use href="#i-tool"/></svg>toolchain</button>
        </div>
      </section>

    </div>

    <div class="right">
      <div class="con-frame">
        <span class="cn tl"></span><span class="cn tr"></span><span class="cn bl"></span><span class="cn br"></span>
        <div class="con-head">
          <span class="lbl">output</span>
          <span class="act" id="con-act">idle</span>
          <span class="spacer"></span>
          <button class="clear" id="b-copy" title="Copy the whole output to the clipboard — plain text, without the leading pipes, ready to paste in a message or a ticket."><svg class="ic"><use href="#i-copy"/></svg> <span id="copyTxt">copy</span></button>
          <button class="clear" id="b-clear" title="Wipe the output pane. It does not stop a running action."><svg class="ic"><use href="#i-trash"/></svg> clear</button>
        </div>
        <div class="log" id="log"></div>
      </div>
      <div class="transport">
        <button class="btn danger" id="b-stop" title="Stop the running action: the child process is killed and the step throws. Whatever was already written stays on disk." disabled><svg class="ic"><use href="#i-stop"/></svg>stop</button>
        <div class="gauge" id="progw"><i></i></div>
        <span class="clock" id="elapsed">0.0s</span>
      </div>
    </div>
  </div>

  <div class="foot">
    <span id="m-stat">ready</span>
    <span class="credit">jean-luc bloechle · claude.ai</span>
  </div>
</div>

<div class="scrim" id="scrim"></div>
<div class="drawer" id="drawer" role="dialog" aria-label="base config">
  <div class="dh"><span class="no">00</span><b>Config · set once, then forget</b><button class="cog x" id="cfg-close" aria-label="close" title="Close the drawer. Esc does the same, and so does clicking outside it."><svg class="ic"><use href="#i-x"/></svg></button></div>

  <div class="note">Paths are relative to the project dir — this deck sits in the project root, so src/ and lib/ are right beside it. No Maven, no ~/.m2, no network.</div>

  <div class="dgrp">
    <div class="dgt">Identity · stamped into the jar and the app-image</div>
    <div class="dgrid">
      <div class="df full"><label>app name</label><input id="f-APP_NAME" title="Names the app-image folder in the output dir AND its primary launcher — jpackage names that one after --name, never after its LAUNCHERS label."><div class="hint">blank → the project folder name</div></div>
      <div class="df"><label>app version</label><input id="f-APP_VERSION" title="jpackage refuses anything but a numeric version on Windows: 1.0.0 is fine, 1.0-SNAPSHOT is not. Keep it equal to the declared version."></div>
      <div class="df"><label>vendor</label><input id="f-VENDOR" title="Vendor string jpackage stamps into the app-image. Blank → the app name."></div>
      <div class="df full"><label>release</label><input id="f-RELEASE" title="javac --release — the bytecode floor, and the version the jar needs to RUN. The app-image carries its own runtime and does not care."><div class="hint">the CI gate compiles at this exact value</div></div>
    </div>
  </div>

  <div class="dgrp">
    <div class="dgt">Paths · relative to the project dir</div>
    <div class="dgrid">
      <div class="df"><label>src dir</label><input id="f-SRC_DIR" title="Root of the sources. Every .java under it is compiled, every other file is staged as a resource — unless JAR_PACKAGES narrows the perimeter in .env."></div>
      <div class="df"><label>lib dir</label><input id="f-LIB_DIR" title="Vendored jars. Every .jar here is on the compile classpath and exploded into the fat jar. A runtime-only dependency that is not here is not in the jar either."></div>
      <div class="df"><label>scratch dir</label><input id="f-SCRATCH_DIR" title="Intermediate build output — the compile stage and the jpackage input. Erasable at any time; each run wipes what it needs."></div>
      <div class="df"><label>output dir</label><input id="f-OUT_DIR" title="Where the deliverables land: the jar, the app-image folder and its zip. Nothing in it is precious."></div>
    </div>
  </div>

  <div class="dgrp">
    <div class="dgt">Packaging</div>
    <div class="dgrid">
      <div class="df full"><label>jpackage type</label><input id="f-JPACKAGE_TYPE" title="app-image = a folder with a bundled runtime and native launchers, and no installer tooling to install. The other types need WiX, dpkg and friends."></div>
      <div class="df full"><label>icon file — optional</label><input id="f-ICON_FILE" title="Explicit .ico/.icns for the launchers. Blank falls back to <Launcher>.ico, then app.ico, looked up in the OUTPUT dir."><div class="hint">blank → &lt;name&gt;.ico / app.ico in the output dir</div></div>
    </div>
  </div>

  <div class="dfoot">
    <button class="btn danger" id="b-reset" title="Clear the values saved in this browser and restore the defaults this deck was started with."><svg class="ic"><use href="#i-refresh"/></svg>reset saved values</button>
    <span class="hint">Only what you CHANGE is saved, in this browser (localStorage). The defaults come from <code>.env</code>.</span>
  </div>
</div>

<div id="tip" role="tooltip" aria-hidden="true"></div>

<script>
// ── mini-qry (inline, zero deps) ──────────────────────────────────────────────
function $(s,c){ return (c||document).querySelector(s); }
EventTarget.prototype.on=function(ev,fn){ this.addEventListener(ev,fn); return this; };
(function(P){
  P.val=function(v){ if(v===undefined)return this.value; this.value=v; return this; };
  P.txt=function(v){ if(v===undefined)return this.textContent; this.textContent=(v==null?'':v); return this; };
  P.cls=function(n,on){ this.classList.toggle(n,on); return this; };
})(Element.prototype);

var TEXT=['PROJECT_DIR','JAR_NAME','RELEASE','JAR_MAIN','APP_NAME','APP_VERSION','VENDOR',
  'SRC_DIR','LIB_DIR','SCRATCH_DIR','OUT_DIR','JPACKAGE_TYPE','ICON_FILE'];
var BTNS=['test','jar','build','tools'];
var PRE={cmd:'$ ',err:'! ',ok:'\u2713 ',log:'\u2502 ',info:'\u00b7 '};
var es=null,timer=null,t0=0,running=false;
var STORE='nimbus.build.v2', DEFAULTS=null, KEYS=TEXT;

function readForm(){ var c={}; TEXT.forEach(function(k){ c[k]=$('#f-'+k).val().trim(); }); return c; }
function fill(d){ TEXT.forEach(function(k){ $('#f-'+k).val(d[k]||''); }); }
function save(){ try{ var o={}; KEYS.forEach(function(k){ var v=$('#f-'+k).val(); if(!DEFAULTS||v!==DEFAULTS[k])o[k]=v; });
  if(Object.keys(o).length)localStorage.setItem(STORE,JSON.stringify(o)); else localStorage.removeItem(STORE); }catch(e){} }
function applySaved(){ var o,n=0; try{ o=JSON.parse(localStorage.getItem(STORE)||'null'); }catch(e){ o=null; } if(!o)return;
  KEYS.forEach(function(k){ if(o[k]!==undefined&&o[k]!==DEFAULTS[k]){ $('#f-'+k).val(o[k]); n++; } });
  if(n)line(n+' field(s) overridden locally — config ▸ reset to restore defaults','info'); }
function resetSaved(){ try{ localStorage.removeItem(STORE); }catch(e){} if(DEFAULTS)fill(DEFAULTS); line('saved values cleared — defaults restored','info'); }

function stamp(){ return new Date().toTimeString().slice(0,8); }
function line(text,kind){
  var l=$('#log'), d=document.createElement('div'); d.className=kind||'out';
  var t=document.createElement('span'); t.className='t'; t.textContent=stamp();
  var s=document.createElement('span'); s.textContent=(PRE[kind]||'  ')+text;
  d.appendChild(t); d.appendChild(s); l.appendChild(d); l.scrollTop=l.scrollHeight;
}
function setRun(on){ BTNS.forEach(function(a){ $('#b-'+a).disabled=on; }); $('#b-stop').disabled=!on; $('#progw').cls('run',on); }
function tick(){ $('#elapsed').txt(((Date.now()-t0)/1000).toFixed(1)+'s'); }
function begin(action){
  running=true;
  $('#log').innerHTML=''; $('#con-act').txt(action); $('#m-stat').txt('running '+action+' …');
  line('starting '+action+' …','info');
  setRun(true); t0=Date.now(); clearInterval(timer); timer=setInterval(tick,100); tick();
}
function handle(m){
  if(m.type==='hello'){ $('#ver').txt('v'+m.version); running=m.running; setRun(m.running); }
  else if(m.type==='start'){ if(!running) begin(m.action); }   // optimistic begin() already ran on click; only catch server-initiated runs
  else if(m.type==='line'){ line(m.text,m.kind); }
  else if(m.type==='done'){ running=false; setRun(false); clearInterval(timer); tick();
    var el=(((Date.now()-t0)/1000).toFixed(1))+'s';
    $('#m-stat').txt((m.code===0?'done':'exit '+m.code)+' · '+el);
    line(m.code===0?'done':'exited '+m.code, m.code===0?'ok':'err'); }
}
function send(action){
  begin(action);                                              // instant feedback — don't wait for the server's 'start'
  fetch('/run',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({action:action,config:readForm()})})
    .then(function(r){ if(r.status===409) line('already running','err'); },
          function(e){ running=false; setRun(false); clearInterval(timer); $('#m-stat').txt('error'); line('request failed: '+((e&&e.message)||e),'err'); });
}
function drawer(open){ $('#drawer').cls('open',open); $('#scrim').cls('open',open); }
function connect(){
  es=new EventSource('/events');
  es.onopen=function(){ $('#dot').cls('on',true).cls('off',false); $('#conn').txt('linked'); };
  es.onerror=function(){ $('#dot').cls('on',false).cls('off',true); $('#conn').txt('not linked'); };
  es.onmessage=function(e){ try{ handle(JSON.parse(e.data)); }catch(x){} };
}

// ── wiring ──────────────────────────────────────────────────────────────────
// The toolchain readout writes a data-tip, never a title: the tooltip engine below converts every
// title ONCE at boot, so a title set afterwards would show the platform bubble on top of ours.
function showTools(t){
  if(!t||!t.length)return;
  var missing=t.filter(function(p){ return !p.ok; });
  $('#jdk').innerHTML=t.map(function(p){ return p.tool+' <b>'+(p.ok?p.version:'—')+'</b>'; }).join(' · ');
  $('#jdk').cls('bad',missing.length>0);
  $('#jdk').setAttribute('data-tip', missing.length
    ? 'missing: '+missing.map(function(p){return p.tool;}).join(', ')+' — check JAVA_HOME, or the PATH this deck was started from.'
    : 'The three JDK binaries this deck shells out to, as found through JAVA_HOME or the PATH.');
  if(missing.length)line('toolchain incomplete — '+missing.map(function(p){return p.tool;}).join(', ')+' not found','err');
}
fetch('/api/defaults').then(function(r){ return r.json(); }).then(function(j){ DEFAULTS=j.defaults; $('#deck').txt(j.app+' Build Deck'); fill(DEFAULTS); applySaved(); showTools(j.tools); });
KEYS.forEach(function(k){ $('#f-'+k).on('input',save).on('change',save); });
BTNS.forEach(function(a){ $('#b-'+a).on('click',function(){ send(a); }); });
$('#b-stop').on('click',function(){ fetch('/stop',{method:'POST'}); });
$('#b-clear').on('click',function(){ $('#log').innerHTML=''; $('#con-act').txt('idle'); $('#m-stat').txt('ready'); });
$('#b-copy').on('click',function(){
  // What lands on the clipboard is what the eye READS: the prefixes are decoration of the pane, not
  // part of the output, so they are stripped rather than pasted into someone's ticket.
  // No backslash anywhere in here on purpose — this script is emitted from a template literal, which
  // eats escapes before the browser ever sees them.
  var PRE = ' ' + String.fromCharCode(9, 9474, 10003, 183) + '$!';      // tab, box-rule, tick, dot
  var NL = String.fromCharCode(10);
  function bare(t){ var i = 0; while (i < t.length && PRE.indexOf(t.charAt(i)) >= 0) i++; return t.slice(i); }
  var lines = [].map.call($('#log').children, function (l) { return bare(l.textContent); });
  var txt = lines.join(NL).trim();
  var say = function (w) { $('#copyTxt').txt(w); setTimeout(function(){ $('#copyTxt').txt('copy'); }, 1500); };
  if (!txt) { say('empty'); return; }
  navigator.clipboard.writeText(txt).then(
    function(){ say(lines.length + ' lines'); },
    function(){ say('blocked'); });
});
(function(){
  var tip=$('#tip'), hideAt=null;
  // Every title in the deck becomes ours: same texts, one renderer, no native bubble on top of it.
  document.querySelectorAll('[title]').forEach(function(el){
    el.setAttribute('data-tip', el.getAttribute('title')); el.removeAttribute('title');
  });
  function label(el){
    var b=el.querySelector&&el.querySelector('svg')?el.textContent.trim():null;
    return b || (el.tagName==='INPUT'||el.tagName==='SELECT' ? (el.closest('.f,.df')||el).querySelector('label') &&
                 (el.closest('.f,.df')||el).querySelector('label').textContent.trim() : el.textContent.trim());
  }
  var cur=null;
  function place(){
    if(!cur) return;
    var r=cur.getBoundingClientRect(), t2=tip.getBoundingClientRect();
    // Beside the control by default, above it when the right edge is too close — then CLAMPED, so
    // a corner control can never push the box off screen.
    var x=r.right+10, y=r.top;
    if(x+t2.width>innerWidth-10){ x=r.left; y=r.top-t2.height-8; }
    if(y<10) y=r.bottom+8;
    x=Math.min(Math.max(10,x), innerWidth-t2.width-10);
    y=Math.min(Math.max(10,y), innerHeight-t2.height-10);
    tip.style.left=Math.round(x)+'px'; tip.style.top=Math.round(y)+'px';
  }
  function show(el){
    var t=el.getAttribute('data-tip'); if(!t) return;
    clearTimeout(hideAt); cur=el;
    tip.innerHTML=''; var h=document.createElement('b'); h.textContent=label(el)||'';
    tip.appendChild(h); tip.appendChild(document.createTextNode(t));
    tip.classList.add('on'); tip.setAttribute('aria-hidden','false');
    place();
  }
  function hide(){ hideAt=setTimeout(function(){ cur=null; tip.classList.remove('on'); tip.setAttribute('aria-hidden','true'); }, 60); }
  document.addEventListener('mouseover',function(e){ var el=e.target.closest&&e.target.closest('[data-tip]'); if(el) show(el); });
  document.addEventListener('mouseout',function(e){ if(e.target.closest&&e.target.closest('[data-tip]')) hide(); });
  document.addEventListener('focusin',function(e){ var el=e.target.closest&&e.target.closest('[data-tip]'); if(el) show(el); });
  document.addEventListener('focusout',hide);
  document.addEventListener('scroll',place,true);   // the panel scrolls: follow, do not vanish
  document.addEventListener('keydown',function(e){ if(e.key==='Escape') hide(); });
})();
$('#b-cfg').on('click',function(){ drawer(!$('#drawer').classList.contains('open')); });
$('#scrim').on('click',function(){ drawer(false); });
$('#cfg-close').on('click',function(){ drawer(false); });
$('#b-reset').on('click',resetSaved);
document.on('keydown',function(e){ if(e.key==='Escape')drawer(false); });
connect();
</script>
</body>
</html>`;
