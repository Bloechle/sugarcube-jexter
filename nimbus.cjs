#!/usr/bin/env node
/**
 * nimbus.cjs — Nimbus · the fat-jar + native app-image build deck, and the AI-bundle tool.
 *
 * Same instrument-deck design and SSE wiring as the rest of the Nimbus family
 * (_$nimbus-cloud.cjs / -template.cjs), specialised for a Java project that vendors
 * its jars in lib/. One window, click the step you want — nothing chains
 * automatically — and the real javac / jar / jpackage output streams live into the
 * console. Four actions, one authority each (runAction):
 *
 *   BUILD (run on this machine — needs a JDK ≥ RELEASE for javac/jar; jpackage for the app-image)
 *     jar      → compile (-Xlint:all -Werror, the CI gate) → stage resources → explode
 *                lib/*.jar → clean META-INF → pack the fat jar. Clears the previous
 *                deliverables first — INCLUDING the app-image.
 *     build    → jar, then jpackage the app-image: one bundled runtime, one .exe per
 *                LAUNCHERS entry (javaw), plus one <Name>.cmd each for a real console;
 *                then the app-image zipped as <APP_NAME>-<APP_VERSION>-<os>.zip — the
 *                distributable (a release asset), the folder being its unpacked form.
 *
 *   INSPECT
 *     tools    → which javac / jar / jpackage + their versions
 *
 *   CONTEXT (self-contained AI hand-off bundle — the folded-in _ai-bundle tool)
 *     context  → _ai-<project>.zip : a SKILL-shaped overview (_ai-overview.md at the
 *                archive root) + the real source tree under <project>/. Drop it in a
 *                chat; the AI reads the overview first, source on demand.
 *              → _ai-<project>-overview.md : the overview alone (card + map, ~500
 *                lines) — for Project knowledge, which refuses archives.
 *                Both land in the project root, beside the source they describe.
 *
 * No Maven, no network, no ~/.m2 — straight javac/jar/jpackage. The fat jar holds
 * every main class; the app-image emits one GUI launcher per entry in LAUNCHERS,
 * all sharing ONE bundled runtime (so the .exe needs nothing installed). The jar
 * needs a JDK ≥ RELEASE to RUN; the app-image bundles its own.
 *
 * Zero npm dependencies — pure Node (node:http + SSE, Node 18+). No CDN.
 *   node nimbus.cjs              → desktop-style window (fixed port 7333)
 *   node nimbus.cjs --serve      → headless, prints the URL
 *   node nimbus.cjs --run=build  → headless one-shot (CI): run an action, stream
 *                                      to stdout, exit with its code. <action> = any
 *                                      button id (jar|build|context|tools)
 *   node nimbus.cjs <projectDir> → point at a different project root (else .); every
 *                                   dir (src, lib, scratch, output) resolves under it
 *
 * Optional ./.env pre-fills the form (KEY=VALUE lines). Form keys (all optional):
 *   PROJECT_DIR JAR_NAME JAR_MAIN RELEASE APP_NAME APP_VERSION VENDOR
 *   SRC_DIR LIB_DIR SCRATCH_DIR OUT_DIR JPACKAGE_TYPE ICON_FILE
 *   LAUNCHERS    → Name:main.Class,Name:main.Class  (first = primary; default: APP_NAME → JAR_MAIN)
 *   JAR_PACKAGES → core,app  (top-level dirs under SRC_DIR that make the deliverable; default: all)
 *   LINT         → all  (-Xlint:<LINT>; e.g. all,-fallthrough when a warning is the lesson)
 *   KEEP_FILES   → lib/a.jar,data/fonts/,assets/*.png  (raw bytes the AI bundle must carry:
 *                  a file, a folder (trailing /), a glob — what the sandbox cannot fetch)
 *   SKIP_FILES   → *.f.svg,*.tmp  (generated files the AI bundle must not carry)
 *   PORT         → 7333  (one deck per project when two run side by side)
 *   Nothing project-specific lives in this file: the defaults are neutral, .env is the
 *   project (tracked), .env.local the machine (ignored); shell env beats both.
 *
 * @author Jean-Luc Bloechle with Claude.ai
 */

const http = require('node:http');
const path = require('node:path');
const fs = require('node:fs');
const cp = require('node:child_process');
const zlib = require('node:zlib');   // AI-context zip writer (DEFLATE)

const DIR = __dirname;
const HEADLESS = process.argv.includes('--serve');
const RUN_ARG = (process.argv.find((a) => a.startsWith('--run=')) || '').slice(6); // headless one-shot action
const VERSION = '1.5';   // 1.5 (2026-09-02): the header no longer names a JDK version — RELEASE is the project's; 1.4 (2026-09-02): audit — no project name in the tool, one glob compiler, one in/out authority for the bundle, launchers/keep/skip through cfg, OUT_DIR under the project; 1.3 (2026-09-02): build also zips the app-image as <APP_NAME>-<APP_VERSION>-<os>.zip — the distributable; 1.2 (2026-09-01): binary containers by extension (a PDF read as utf8 is corrupt); 1.1: neutral defaults, .env is the project, library jars, zip + overview.md
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
    KEEP_FILES:  env('KEEP_FILES', ''),                     // AI bundle: raw bytes to carry — file, folder/, glob
    SKIP_FILES:  env('SKIP_FILES', ''),                     // AI bundle: generated files to leave out (globs)
    // base constants (drawer — rarely change)
    SRC_DIR:       env('SRC_DIR', 'src'),
    LIB_DIR:       env('LIB_DIR', 'lib'),
    SCRATCH_DIR:   env('SCRATCH_DIR', 'target'),            // intermediate build output, under project root
    OUT_DIR:       env('OUT_DIR', '_prod'),                 // where deliverables land (created if missing)
    JPACKAGE_TYPE: env('JPACKAGE_TYPE', 'app-image'),       // folder + native launcher, no extra tooling
    ICON_FILE:     env('ICON_FILE', ''),                    // explicit .ico/.icns; else <name>.ico / app.ico in OUT_DIR
};

// Comma lists in .env (LAUNCHERS, KEEP_FILES, SKIP_FILES, JAR_PACKAGES): one splitter.
const list = (s) => String(s || '').split(',').map((x) => x.trim()).filter(Boolean);
// Glob → regex body: `**` crosses folders, `*` and `?` stay inside one segment.
// The one compiler behind KEEP_FILES, SKIP_FILES and the .gitignore rules.
const globBody = (g) => g.replace(/[.+^${}()|[\]\\]/g, '\\$&').replace(/\*\*/g, '\u0000').replace(/\*/g, '[^/]*').replace(/\?/g, '[^/]').replace(/\u0000/g, '.*');
const globRe = (g, flags = '') => new RegExp(`^${globBody(g)}$`, flags);
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
// Rough token estimate. No tokenizer, no dependency: ~3.7 chars/token is a solid
// average for source code across the GPT/Claude BPE families. Order of magnitude
// is what matters — you budget a context window, you do not bill it.
const estTokens = (chars) => Math.round(chars / 3.7);
const fmtTokens = (t) => (t < 1000 ? `${t}` : `${(t / 1000).toFixed(1)}k`);
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
        try { proc = cp.spawn(bin, args, { cwd: o.cwd || DIR, env: process.env }); }
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

/** Compile every src/**.java against lib/*.jar into the stage. */
async function compile(cfg, c, send) {
    const sources = walkPackages(c, (fp) => fp.toLowerCase().endsWith('.java'));
    if (!sources.length) throw new Error('no .java sources found');
    const argFile = path.join(c.scratch, 'sources.txt');
    fs.mkdirSync(c.scratch, { recursive: true });
    fs.writeFileSync(argFile, sources.map((s) => `"${s.replace(/\\/g, '/')}"`).join('\n'));
    const code = await sh(c.javac, [...(c.libJars.length ? ['-cp', c.libJars.join(path.delimiter)] : []), '-d', c.stage,
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
        case 'build':   return doBuild(cfg, true,  send);   // full chain, through jpackage
        case 'jar':     return doBuild(cfg, false, send);   // stops before the app-image
        case 'tools':   return Promise.resolve(doTools(cfg, send));
        case 'context': return doContext(cfg, send);
        default:        return Promise.reject(new Error('unknown action: ' + action));
    }
}

// ════════════════════════════ AI-CONTEXT BUNDLER ════════════════════════════
// Folded in from _ai-context.cjs: scan a project into a self-contained AI-context
// bundle (SKILL-shaped overview + source), as _ai-<project>.zip (chat drop) plus the
// overview alone as _ai-<project>-overview.md (Project knowledge). Mechanism only — anything project-specific
// lives in the project's own agent card and md guides, never here.

const CONTEXT = {
    outputPrefix: '_ai-',
    // NO extension allowlist. Asking "is this extension on my list?" is the wrong
    // question — it made .typ invisible until someone noticed, and would do the same
    // to the next .kt / .sql / .svelte. The right question is "is this text?", and
    // that is decidable: no NUL byte, mostly printable over the first few KB.
    // The zip is a FILE SYSTEM, not a prompt — a reader opens what it needs — so
    // everything readable belongs, and only skipPatterns takes things back out.
    isTextFile(fp) {
        let fd;
        try { fd = fs.openSync(fp, 'r'); } catch { return false; }
        try {
            const buf = Buffer.alloc(8192);
            const n = fs.readSync(fd, buf, 0, 8192, 0);
            if (n === 0) return true;                       // empty file: harmless text
            let odd = 0;
            for (let i = 0; i < n; i++) {
                const c = buf[i];
                if (c === 0) return false;                  // NUL ⇒ binary, full stop
                if (c < 9 || (c > 13 && c < 32) || c === 127) odd++;
            }
            return odd / n < 0.05;                          // >5% control chars ⇒ binary
        } catch { return false; }
        finally { try { fs.closeSync(fd); } catch { /* ignore */ } }
    },
    // Dot-folders are skipped as IDE/VCS state — except the ones that are project
    // definition. A reader asked "why did the build fail" cannot answer without
    // seeing the workflow that runs it.
    alwaysFolders: ['.github'],
    // Bypass isHidden() for the meaningful extensionless/dot files a repo carries.
    alwaysFiles: [
        'NOTICE', 'LICENSE', 'LICENCE', 'COPYING', 'CHANGELOG', 'CONTRIBUTING', 'AUTHORS',
        '.gitignore', '.gitattributes', '.editorconfig', '.npmrc',
        '.env',                                   // the project's nimbus config — tracked, no secrets; .env.local stays hidden
        'Makefile', 'Dockerfile', 'Procfile',
    ],
    // Always-keep allowlist — exact project-relative paths that bypass BOTH the skip
    // rules and the extension filter, bundled as raw bytes (any size). Ships vendored
    // binaries like jars. Forward-slash paths.
    // A LOUD line, not a gate. Removing a big file from the zip would leave its path
    // in the Files table with nothing behind it — the one thing a file system must
    // never do. Over this, the file still ships; it is flagged so a reader knows to
    // think twice before opening it.
    // No size cap, on purpose: a zip costs only what gets opened, and a 2 MB slide SVG
    // may be exactly the file a task needs.
    // Containers whose first 8 KB can look like text (a PDF's header and xref, a font's
    // tables) — read as utf8 they come out CORRUPTED, so the extension decides and the
    // sniff never sees them.
    binaryExt: ['.pdf', '.ttf', '.otf', '.woff', '.woff2', '.eot', '.zip', '.7z', '.gz', '.tgz', '.tar', '.rar',
        '.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.ico', '.tif', '.tiff', '.psd',
        '.mp3', '.mp4', '.m4a', '.wav', '.ogg', '.mov', '.avi', '.webm',
        '.docx', '.xlsx', '.pptx', '.odt', '.ods', '.odp', '.sqlite', '.db'],
    // Folders skipped during scan. `_`/`.` folders are already dropped by isHidden().
    //   'name' → any depth · './name' → root-anchored · 'parent/name' → path substring
    skipFolders: [
        'node_modules', 'bower_components',
        'dist', 'build', 'out', 'output',
        'bin', 'obj', 'release', 'Release', 'debug', 'Debug', 'coverage',
        'target',                                // Maven (Java) + Cargo (Rust)
        '__pycache__', 'venv', 'env',
        'packages', 'TestResults',
        'Library', 'Temp', 'Logs', 'Build', 'Builds',
        'UserSettings', 'MemoryCaptures', 'Recordings',
    ],
    skipFiles: [
        'package-lock.json', 'yarn.lock', 'pnpm-lock.yaml', 'Cargo.lock',
        'Thumbs.db', '.DS_Store',
    ],

    // Last gate before a bundle leaves the machine. A hit WITHHOLDS the file from
    // the zip (it stays on disk) and is reported loudly — a bundle is meant to be
    // dropped in a chat, so a false negative costs far more than a false positive.
    // Force one through with keepFiles if it is genuinely a fixture.
    secretPatterns: [
        [/\bsk-ant-[A-Za-z0-9_-]{20,}/,                      'anthropic key'],
        [/\bsk-[A-Za-z0-9]{32,}/,                            'openai-style key'],
        [/\b(ghp|gho|ghs|ghr)_[A-Za-z0-9]{30,}/,             'github token'],
        [/\bgithub_pat_[A-Za-z0-9_]{50,}/,                   'github fine-grained token'],
        [/\bAKIA[0-9A-Z]{16}\b/,                             'aws access key id'],
        [/\bxox[abposr]-[A-Za-z0-9-]{10,}/,                  'slack token'],
        [/-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----/, 'private key'],
        [/\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/, 'jwt'],
        [/\b(api[_-]?key|secret|passwd|password|token)\s*[:=]\s*["'][^"'\s]{16,}["']/i, 'hardcoded credential'],
    ],
    skipPatterns: [
        /\.min\.(js|css)$/i, /\.map$/i, /\.bundle\.(js|css)$/i,
        /\.(class|jar|war)$/i, /\.(pyc|pyo)$/i,
        /\.(exe|dll|so|dylib|a|lib)$/i, /\.(o|obj|elf|hex|bin)$/i, /\.meta$/i,
    ],
};

// ── zero-dep zip writer (DEFLATE via zlib) ───────────────────────────────────
const CRC_TABLE = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
        t[n] = c;
    }
    return t;
})();
function crc32(buf) {
    let c = 0xFFFFFFFF;
    for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
    return (c ^ 0xFFFFFFFF) >>> 0;
}
class Zip {
    constructor() {
        this.entries = []; this.parts = []; this.offset = 0;
        const d = new Date();
        this.dosTime = ((d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1)) & 0xFFFF;
        this.dosDate = (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()) & 0xFFFF;
    }
    add(name, data) {
        const nameBuf = Buffer.from(name, 'utf8');
        const crc = crc32(data);
        const deflated = zlib.deflateRawSync(data, { level: 9 });
        const [method, payload] = deflated.length < data.length ? [8, deflated] : [0, data];
        const local = Buffer.alloc(30);
        local.writeUInt32LE(0x04034b50, 0);
        local.writeUInt16LE(20, 4);
        local.writeUInt16LE(0x0800, 6);                  // bit 11: names are UTF-8 (else CP437 mangles accents)
        local.writeUInt16LE(method, 8);
        local.writeUInt16LE(this.dosTime, 10);
        local.writeUInt16LE(this.dosDate, 12);
        local.writeUInt32LE(crc, 14);
        local.writeUInt32LE(payload.length, 18);
        local.writeUInt32LE(data.length, 22);
        local.writeUInt16LE(nameBuf.length, 26);
        local.writeUInt16LE(0, 28);
        this.entries.push({ name: nameBuf, method, crc, csize: payload.length, usize: data.length, offset: this.offset });
        this.parts.push(local, nameBuf, payload);
        this.offset += 30 + nameBuf.length + payload.length;
    }
    finalize() {
        const cdStart = this.offset;
        const cdParts = [];
        let cdSize = 0;
        for (const e of this.entries) {
            const cd = Buffer.alloc(46);
            cd.writeUInt32LE(0x02014b50, 0);
            cd.writeUInt16LE(20, 4);
            cd.writeUInt16LE(20, 6);
            cd.writeUInt16LE(0x0800, 8);
            cd.writeUInt16LE(e.method, 10);
            cd.writeUInt16LE(this.dosTime, 12);
            cd.writeUInt16LE(this.dosDate, 14);
            cd.writeUInt32LE(e.crc, 16);
            cd.writeUInt32LE(e.csize, 20);
            cd.writeUInt32LE(e.usize, 24);
            cd.writeUInt16LE(e.name.length, 28);
            cd.writeUInt16LE(0, 30);
            cd.writeUInt16LE(0, 32);
            cd.writeUInt16LE(0, 34);
            cd.writeUInt16LE(0, 36);
            cd.writeUInt32LE(0, 38);
            cd.writeUInt32LE(e.offset, 42);
            cdParts.push(cd, e.name);
            cdSize += 46 + e.name.length;
        }
        const eocd = Buffer.alloc(22);
        eocd.writeUInt32LE(0x06054b50, 0);
        eocd.writeUInt16LE(0, 4);
        eocd.writeUInt16LE(0, 6);
        eocd.writeUInt16LE(this.entries.length, 8);
        eocd.writeUInt16LE(this.entries.length, 10);
        eocd.writeUInt32LE(cdSize, 12);
        eocd.writeUInt32LE(cdStart, 16);
        eocd.writeUInt16LE(0, 20);
        return Buffer.concat([...this.parts, ...cdParts, eocd]);
    }
}

// ── .gitignore as the authority on "not part of the repo" ────────────────────
// The repo already answers "what does not belong here". Re-declaring it in the
// bundler config would be a second authority. We parse the root .gitignore and
// let it drive the scan; CONTEXT.skipFolders stays as the floor for projects
// that have no .gitignore at all. Supported: comments, blanks, `!` negation,
// trailing `/` (dir-only), leading `/` (root-anchored), `*`, `?`, `**`.
function gitignoreRules(rootDir) {
    let text;
    try { text = fs.readFileSync(path.join(rootDir, '.gitignore'), 'utf8'); }
    catch { return []; }
    const rules = [];
    for (let line of text.split(/\r?\n/)) {
        line = line.replace(/\s+$/, '');
        if (!line || line.startsWith('#')) continue;
        const negate = line.startsWith('!');
        if (negate) line = line.slice(1);
        const dirOnly = line.endsWith('/');
        if (dirOnly) line = line.slice(0, -1);
        const anchored = line.startsWith('/') || line.slice(0, -1).includes('/');
        if (line.startsWith('/')) line = line.slice(1);
        const body = globBody(line);
        const src = anchored ? `^${body}(/.*)?$` : `^(.*/)?${body}(/.*)?$`;
        try { rules.push({ re: new RegExp(src), negate, dirOnly }); } catch { /* skip bad pattern */ }
    }
    return rules;
}
function gitignored(rules, rel, isDir) {
    let hit = false;
    for (const r of rules) {
        if (!r.re.test(rel)) continue;
        if (r.dirOnly && !isDir && !rel.includes('/')) continue;
        hit = !r.negate;
    }
    return hit;
}

// ── the bundler (scan → overview + source → _ai-<project>.zip) ───────────────
class Bundler {
    constructor(config) {
        this.config = config;
        this.rootDir = path.resolve(DIR, config.rootDir || '.');
        this.projectName = path.basename(this.rootDir);
        this.zipFile = path.join(this.rootDir, `${config.outputPrefix}${this.projectName}.zip`);
        this.overviewName = '_ai-overview.md';               // embedded overview at the .zip root
        this.skipped = { dirs: [], files: [] };              // recorded during scan, for the summary line
        this.ignore = gitignoreRules(this.rootDir);          // the repo's own answer to "what does not belong"
        this.card = this.cardFile();                         // AGENTS.md | CLAUDE.md | _ai.md — embedded, not bundled
        this.secrets = [];                                   // files withheld by the secret gate
        // This script IS part of the project's toolchain — a reader that cannot see
        // it cannot answer questions about the build. `_`-prefixed, so opt it back in.
        this.config.alwaysFiles = [...(config.alwaysFiles || []), path.basename(__filename)];
        this.log = config.log || ((text) => console.log(text));   // (text, kind) sink; kind ∈ cmd|ok|info|log|out|err
        this.guard = config.guard || (() => {});             // abort hook — throws to cancel mid-scan
    }

    // ─── helpers ───────────────────────────────────────────────────────────
    // `_*` meta files (this script, generated `_ai-*` bundles, drafts) and `.*`
    // system/IDE files are noise for an AI context — never bundle them.
    isHidden(name) { return name.startsWith('_') || name.startsWith('.'); }

    // 'node_modules' → any depth · './build' → root-anchored · 'src/gen' → substring
    shouldSkipFolder(filePath) {
        const rel = this.rel(filePath);
        return this.config.skipFolders.some(rule => {
            if (rule.startsWith('./')) return rel.startsWith(rule.slice(2));
            if (rule.includes('/')) return rel.includes(rule);
            return rel.split('/').includes(rule);
        });
    }

    // Why a file is skipped, or null if it would be collected. 'unlisted extension'
    // is soft (just not a source type); the tree still shows non-source files.
    fileSkipReason(name) {
        if ((this.config.alwaysFiles || []).includes(name)) return null;
        if (this.isHidden(name)) return 'hidden';
        if (this.config.skipFiles.includes(name)) return 'skipFiles';
        if (this.config.skipPatterns.some(re => re.test(name))) return 'skipPattern';
        return null;
    }
    // Path-aware: the name rules decide first, then the extension, then the bytes.
    fileSkipReasonAt(fp) {
        const r = this.fileSkipReason(path.basename(fp));
        if (r) return r;
        const ext = path.extname(fp).toLowerCase();
        if (this.config.binaryExt.includes(ext)) return 'binary';
        return this.config.isTextFile(fp) ? null : 'binary';
    }
    // KEEP_FILES entries: an exact path, a folder (trailing '/', whole subtree), or a glob.
    isKept(filePath) {
        const rel = this.rel(filePath);
        return this.config.keepFiles.some((k) => k.endsWith('/') ? rel.startsWith(k) : k.includes('*') ? globRe(k).test(rel) : rel === k);
    }
    rel(fp) { return path.relative(this.rootDir, fp).replace(/\\/g, '/'); }

    // THE two verdicts — why a folder or a file stays out of the bundle, or null when it
    // is in. The scan and the tree both ask here, so they can never disagree.
    whyDir(name, fp) {
        return (this.config.alwaysFolders || []).includes(name) ? null
            : gitignored(this.ignore, this.rel(fp), true) ? 'gitignore'
            : this.isHidden(name) ? 'hidden' : this.shouldSkipFolder(fp) ? 'skipFolders' : null;
    }
    whyFile(fp) {
        const rel = this.rel(fp);
        return this.isKept(fp) ? null
            : rel === this.card ? 'embedded in the overview'
            : gitignored(this.ignore, rel, false) ? 'gitignore'
            : this.fileSkipReasonAt(fp);                                   // name rules, then extension, then the bytes
    }

    // ─── one-line purpose, harvested from a file's leading doc-comment ───────
    summarize(content, ext) {
        const head = content.slice(0, 12000);
        const e = ext.replace('.', '');
        let s = '';
        const m = head.match(/\/\*\*?([\s\S]*?)\*\//);   // leading block comment (Java/JS/Rust/C#/C)
        if (m) {
            const before = head.slice(0, m.index)
                .replace(/^\s*#!.*$/m, '')
                .replace(/^\s*(package|import|use|using|#include)\b.*$/gm, '')
                .replace(/^\s*['"]use strict['"];?\s*$/gm, '')
                .trim();
            if (before === '') s = m[1].replace(/^[ \t]*\*+/gm, ' ');
        }
        if (!s && /^(py|sh|bash|zsh|yaml|yml|toml|ini|cfg|conf|r)$/.test(e)) {   // docstring / leading #
            const ds = head.match(/("""|''')([\s\S]*?)\1/);
            if (ds && head.slice(0, ds.index).replace(/^#!.*\n/, '').trim() === '') s = ds[2];
            else s = this.#leadingLineComments(head, /^#+\s?/);
        }
        if (!s) s = this.#leadingLineComments(head, /^\/\/+\s?/);   // leading // or ///
        if (!s && /^(html?|xml|svg)$/.test(e)) {   // first comment, else <title>
            const c = head.match(/<!--([\s\S]*?)-->/);
            const t = head.match(/<title[^>]*>([^<]+)<\/title>/i);
            s = c ? c[1] : (t ? t[1] : '');
        }
        if (!s && e === 'md') {   // first heading, else first line
            const lines = head.split('\n').map(l => l.trim()).filter(Boolean);
            const h = lines.find(l => /^#{1,3}\s/.test(l));
            s = h ? h.replace(/^#+\s*/, '') : (lines[0] || '');
        }
        return this.#tidy(s);
    }
    #leadingLineComments(head, strip) {
        const out = [];
        for (let line of head.split('\n')) {
            line = line.trim();
            if (line.startsWith('#!')) continue;
            if (strip.test(line)) out.push(line.replace(strip, ''));
            else if (out.length) break;
            else if (line !== '') break;
        }
        return out.join(' ');
    }
    #tidy(s) {
        s = (s || '')
            .replace(/\{@\w+\s+([^}]*)\}/g, '$1')
            .replace(/<\/?[a-z][^>]*>/gi, '')
            .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
            .replace(/&#0?39;|&apos;/g, "'").replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&')
            .replace(/[`*_]/g, '')
            .replace(/\s+/g, ' ')
            .replace(/^[-—:\s]+/, '')
            .trim();
        // First sentence — but not at an abbreviation: "A placed video resource (e.g." is not a purpose.
        const cut = s.search(/(?<!\b(?:e\.g|i\.e|vs|etc|cf|ca|approx|incl|resp|Ref))\.(\s|$)/);
        if (cut > 0 && cut < 160) s = s.slice(0, cut);
        if (s.length > 140) s = s.slice(0, 137).replace(/\s+\S*$/, '') + '…';
        return s.trim();
    }

    // ─── scan ────────────────────────────────────────────────────────────────
    async collectFiles(dir) {
        const files = [];
        let entries;
        try { entries = await fs.promises.readdir(dir, { withFileTypes: true }); }
        catch { return files; }
        for (const e of entries) {
            this.guard();   // abort point — the deck's stop lands here
            const fp = path.join(dir, e.name);
            const rel = this.rel(fp);
            if (e.isDirectory()) {
                const why = this.whyDir(e.name, fp);
                if (why) { this.skipped.dirs.push([rel + '/', why, dirStats(fp).bytes]); continue; }
                files.push(...await this.collectFiles(fp));
                continue;
            }
            const why = this.whyFile(fp);
            if (why) { this.skipped.files.push([rel, why, statSize(fp)]); continue; }
            const ext = path.extname(fp).toLowerCase();
            const mtime = await fs.promises.stat(fp).then(st => st.mtime, () => new Date(0));
            if (this.isKept(fp)) {
                const buf = await fs.promises.readFile(fp);          // raw bytes
                files.push({ path: rel, ext, lines: 0, sizeKB: buf.length / 1024, tokens: 0, mtime, content: buf, isBinary: true });
                continue;
            }
            const content = await fs.promises.readFile(fp, 'utf8');
            const leak = this.scanSecrets(content);
            if (leak) {
                this.secrets.push([rel, leak.label, leak.line]);
                this.skipped.files.push([rel, `withheld · ${leak.label}`, statSize(fp)]);
                continue;
            }
            const sizeKB = Buffer.byteLength(content, 'utf8') / 1024;
            files.push({
                path: rel, ext,
                lines: content.split('\n').length,
                sizeKB, content, mtime, tokens: estTokens(content.length),
                summary: this.summarize(content, ext),
            });
        }
        return files;
    }

    // First line/column of the first secret-looking match, or null.
    scanSecrets(content) {
        for (const [re, label] of (this.config.secretPatterns || [])) {
            const m = content.match(re);
            if (m) return { label, line: content.slice(0, m.index).split('\n').length };
        }
        return null;
    }

    // ─── stats ─────────────────────────────────────────────────────────────
    buildStats(files) {
        const byType = {};
        for (const f of files) {
            const t = f.ext ? f.ext.slice(1).toUpperCase() : '(no ext)';
            byType[t] = byType[t] || { count: 0, lines: 0, sizeKB: 0, tokens: 0 };
            byType[t].count++; byType[t].lines += f.lines; byType[t].sizeKB += f.sizeKB; byType[t].tokens += f.tokens || 0;
        }
        const totalLines = files.reduce((s, f) => s + f.lines, 0);
        const totalKB = files.reduce((s, f) => s + f.sizeKB, 0);
        const totalTokens = files.reduce((s, f) => s + (f.tokens || 0), 0);
        const typeSummary = Object.entries(byType).sort((a, b) => b[1].tokens - a[1].tokens)
            .map(([t, s]) => `${t}: ${s.count} (~${fmtTokens(s.tokens)})`).join(' | ');
        return { totalLines, totalKB, totalTokens, typeSummary, byType };
    }

    // ─── guidelines — the floor when the project has no agent card ───────────
    buildGuidelinesSection() {
        return '\n## Guidelines\n\n' + [
            '- **DRY** — Don\'t Repeat Yourself',
            '- **KISS** — Keep It Simple & Smart',
            '- **No Overengineering** — Avoid complexity and verbosity',
            '- **Clean & Consistent** — OO where useful, follow codebase patterns',
            '- **Efficient** — Optimize without sacrificing readability',
            '- **Minimal Docs** — Code should be self-explanatory, comment only tricky parts',
            '- **English** — All code and documentation',
        ].join('\n') + '\n';
    }

    // ─── section builders ────────────────────────────────────────────────────
    buildHeader({ files, stats, zipKB }) {
        const timestamp = new Date().toISOString().replace('T', ' ').slice(0, 16);
        let header = `# ${this.projectName} — ${timestamp}\n\n`;
        header += `> ${files.length} files · ${stats.totalLines} lines · ${stats.totalKB.toFixed(1)} KB source · ~${fmtTokens(stats.totalTokens)} tokens`;
        if (zipKB) header += ` · ${zipKB} KB zipped`;
        header += `\n> Types: ${stats.typeSummary}`;
        header += `\n> Token estimates are approximate (~3.7 chars/token). Read the overview, then open only the files you need — the whole tree does not fit a context window.`;
        return header + '\n';
    }
    // The tree IS the structure section: it carries the shape AND, inline, every
    // reason a file is absent. Two partial listings collapsed into one complete one.
    buildTreeSection(tree) {
        return `\n## Tree\n\n*Every file on disk. Lines marked \u2190 are NOT in this bundle; the reason follows`
            + ` the arrow. Collapsed dirs show their weight and file count instead of their contents.*\n\n`
            + '```\n' + tree.lines.join('\n') + '\n```\n';
    }

    // Verbatim authored head from the project's agent card — the one place the
    // PROJECT speaks (frontmatter, one-glance model, conventions, gotchas).
    // AGENTS.md is the cross-tool convention (Claude Code, Codex, Cursor, Aider…);
    // _ai.md is the legacy name, still read so old trees keep working. Whichever
    // is found is skipped from the file list — it is already here, verbatim.
    cardFile() {
        for (const n of ['AGENTS.md', 'CLAUDE.md', '_ai.md'])
            if (fs.existsSync(path.join(this.rootDir, n))) return n;
        return null;
    }
    aiCard() {
        if (!this.card) return '';
        try { const card = fs.readFileSync(path.join(this.rootDir, this.card), 'utf8').trim(); return card ? card + '\n' : ''; }
        catch { return ''; }
    }

    /** The architecture as the COMPILER sees it, not as prose claims it: package →
     *  the packages it imports, counted, straight from the `import` statements. Exact
     *  by construction, so it cannot drift — and it surfaces what no document says,
     *  like a layer that reaches back into one above it. Java only; empty otherwise. */
    buildDependencySection(files) {
        return this.#depsJava(files) + this.#depsByFolder(files, ['.js', '.mjs', '.cjs', '.jsx', '.ts'], 'JS module (folder)',
                /(?:\brequire\(\s*|\bimport\s+[^;]*?from\s+|\bimport\s*\(\s*)['"]([^'"]+)['"]/g)
            + this.#depsByFolder(files, ['.py'], 'Python module (folder)',
                /^\s*(?:from\s+(\.*[\w.]+)\s+import|import\s+([\w.]+))/gm);
    }
    // JS and Python have no package statement — the FOLDER is the module. Relative
    // imports resolve to a folder; absolute ones that match a top-level folder are
    // internal; the rest are external and listed as such (they cannot lie either).
    #depsByFolder(files, exts, label, importRe) {
        const src = files.filter(f => exts.includes(f.ext) && typeof f.content === 'string');
        if (src.length < 5) return '';
        const dirOf = (p) => path.posix.dirname(p);
        const dirs = new Set(src.map(f => dirOf(f.path)));
        const count = new Map(), edges = new Map(), external = new Map();
        for (const d of dirs) count.set(d, src.filter(f => dirOf(f.path) === d).length);
        for (const f of src) {
            const from = dirOf(f.path);
            for (const m of f.content.matchAll(importRe)) {
                const spec = (m[1] || m[2] || '').replace(/\.[a-z]+$/, '');
                let tgt = null;
                if (spec.startsWith('.')) {
                    tgt = path.posix.normalize(path.posix.join(from, spec));
                    while (tgt && !dirs.has(tgt)) tgt = tgt.includes('/') ? path.posix.dirname(tgt) : null;
                } else {
                    const top = spec.split(/[./]/)[0];
                    tgt = [...dirs].find(d => d === top || d.split('/').includes(top)) || null;
                    if (!tgt) { external.set(top, (external.get(top) || 0) + 1); continue; }
                }
                if (!tgt || tgt === from) continue;
                if (!edges.has(from)) edges.set(from, new Map());
                const e = edges.get(from); e.set(tgt, (e.get(tgt) || 0) + 1);
            }
        }
        const rows = [...count.keys()].sort((a, b) => (edges.get(a)?.size || 0) - (edges.get(b)?.size || 0) || a.localeCompare(b));
        let out = `\n## Dependencies — ${label}\n\n| Module | Files | Imports |\n|--------|------:|---------|\n`;
        out += rows.map((p) => {
            const e = [...(edges.get(p) || new Map())].sort((a, b) => b[1] - a[1]);
            return `| ${p} | ${count.get(p)} | ${e.length ? e.map(([t, n]) => `${t} (${n})`).join(' · ') : '**leaf**'} |`;
        }).join('\n');
        if (external.size) out += `\n\n*External: ` + [...external].sort((a, b) => b[1] - a[1]).map(([k, n]) => `${k} (${n})`).join(' · ') + '*';
        return out + '\n';
    }
    #depsJava(files) {
        const java = files.filter(f => f.ext === '.java' && typeof f.content === 'string');
        if (java.length < 5) return '';
        const pkgOf = new Map();                    // file → its declared package
        const known = new Set();
        for (const f of java) {
            const m = f.content.match(/^\s*package\s+([\w.]+)\s*;/m);
            if (m) { pkgOf.set(f, m[1]); known.add(m[1]); }
        }
        if (!known.size) return '';
        // Longest known prefix wins, so `a.b.Outer.Inner` resolves to package `a.b`.
        const resolve = (fq) => {
            const seg = fq.split('.');
            for (let i = seg.length - 1; i > 0; i--) {
                const cand = seg.slice(0, i).join('.');
                if (known.has(cand)) return cand;
            }
            return null;
        };
        const edges = new Map(), count = new Map();
        for (const f of java) {
            const src = pkgOf.get(f); if (!src) continue;
            count.set(src, (count.get(src) || 0) + 1);
            for (const m of f.content.matchAll(/^\s*import\s+(?:static\s+)?([\w.]+)\s*;/gm)) {
                const tgt = resolve(m[1]);
                if (!tgt || tgt === src) continue;
                if (!edges.has(src)) edges.set(src, new Map());
                const e = edges.get(src);
                e.set(tgt, (e.get(tgt) || 0) + 1);
            }
        }
        // Strip the common root so `com.acme.app.core.model` reads as `core.model`.
        const all = [...count.keys()];
        let root = all[0].split('.');
        for (const p of all) { const s2 = p.split('.'); let i = 0; while (i < root.length && root[i] === s2[i]) i++; root = root.slice(0, i); }
        const short = (p) => p.split('.').slice(root.length).join('.') || '(root)';
        const rows = all.sort((a, b) => (edges.get(a)?.size || 0) - (edges.get(b)?.size || 0) || short(a).localeCompare(short(b)));
        let out = `\n## Dependencies\n\n*Package → packages it imports, counted, read from the \`import\` statements. Leaves first.*\n\n`;
        out += `| Package | Files | Imports |\n|---------|------:|---------|\n`;
        out += rows.map((p) => {
            const e = [...(edges.get(p) || new Map())].sort((a, b) => b[1] - a[1]);
            return `| ${short(p)} | ${count.get(p)} | ${e.length ? e.map(([t, n]) => `${short(t)} (${n})`).join(' · ') : '**leaf**'} |`;
        }).join('\n');
        return out + '\n';
    }

    // Orientation, and it must not lie. The old hardcoded name list
    // (readme|spec|workflow|guide…) went stale the day the documents were renamed —
    // the same allowlist mistake the extension filter used to make. This is
    // STRUCTURAL: prose at the root, then prose one level down, each carrying the
    // purpose harvested from itself, plus the real entry points.
    buildReadFirstSection(files) {
        const skip = /^(CONTRIBUTING|SECURITY|CODE_OF_CONDUCT|CHANGELOG|AUTHORS)\./i;
        const depth = f => f.path.split('/').length;
        const isReadme = f => /^readme\./i.test(path.basename(f.path));
        const docs = files
            .filter(f => /\.(md|txt)$/i.test(f.path) && depth(f) <= 2 && !skip.test(path.basename(f.path)))
            .sort((a, b) => (isReadme(b) - isReadme(a)) || depth(a) - depth(b) || a.path.localeCompare(b.path));
        const mains = files
            .filter(f => typeof f.content === 'string'
                && ((f.ext === '.java' && /\bpublic\s+static\s+void\s+main\s*\(/.test(f.content))
                 || (f.ext === '.py' && /__name__\s*==\s*['"]__main__['"]/.test(f.content))))
            .map(f => f.path).sort();
        for (const pj of files.filter(f => path.basename(f.path) === 'package.json' && typeof f.content === 'string')) {
            try {
                const j = JSON.parse(pj.content);
                for (const v of [j.main, ...(typeof j.bin === 'string' ? [j.bin] : Object.values(j.bin || {}))])
                    if (v) mains.push(path.posix.join(path.posix.dirname(pj.path), v).replace(/^\.\//, ''));
            } catch { /* not our JSON to judge */ }
        }
        if (!docs.length && !mains.length) return '';
        let out = `\n## Read first\n\n`;
        if (docs.length) out += docs.map(f => `- \`${f.path}\`${f.summary ? ' — ' + f.summary : ''}`).join('\n') + '\n';
        if (mains.length) out += `\n**Entry points**: ` + [...new Set(mains)].sort().map(p => `\`${p}\``).join(' · ') + '\n';
        return out;
    }

    // One table, one row per file: what it is FOR, what it COSTS, when it last
    // changed. Map and Manifest were two views of the same 161 paths — the paths
    // are listed once here, and once as a tree in Structure. Never a third time.
    buildFilesSection(all) {
        const rows = all.slice().sort((a, b) => a.path.localeCompare(b.path));
        if (!rows.length) return '';
        let out = `\n## Files\n\n*Purpose is harvested from each file's own leading doc-comment. \`~Tok\` is an estimate — use it to budget what you open. The time axis lives in Recently changed, not here.*\n\n`;
        out += `| File | Purpose | Lines | ~Tok |\n|------|---------|------:|-----:|\n`;
        out += rows.map(f => `| ${f.path} | ${(f.summary || '').replace(/\|/g, '\\|')} | ${f.lines || '—'} | ${f.tokens ? fmtTokens(f.tokens) : '—'} |`).join('\n');
        return out + '\n';
    }

    // What is alive right now. The full table is path-ordered for lookup, so this
    // is the only place the time axis is visible — a dozen rows, not a second table.
    buildRecentSection(files, n = 12) {
        const rows = files.filter(f => !f.isBinary)
            .sort((a, b) => new Date(b.mtime) - new Date(a.mtime)).slice(0, n);
        if (!rows.length) return '';
        const day = d => new Date(d).toISOString().slice(0, 10);
        return `\n## Recently changed\n\n*The ${rows.length} most recently modified files — where the work is.*\n\n`
            + rows.map(f => `- \`${f.path}\` — ${day(f.mtime)}`).join('\n') + '\n';
    }

    // Recent commits — the one thing a file tree cannot tell you: the DIRECTION of
    // the work. Repomix ships this as --include-logs; it is cheap and high-signal.
    buildCommitsSection(n = 12) {
        const r = cp.spawnSync('git', ['log', `-${n}`, '--no-merges', '--date=short',
            '--pretty=format:%ad %s'], { cwd: this.rootDir, encoding: 'utf8' });
        if (r.error || r.status !== 0 || !r.stdout.trim()) return '';
        const lines = r.stdout.trim().split(/\r?\n/).map(l => `- ${l}`);
        return `\n## Recent commits\n\n*The last ${lines.length}, newest first.*\n\n` + lines.join('\n') + '\n';
    }

    /** The FULL on-disk tree, every entry tagged with why it is in or out. This is
     *  the single listing: it says what `Structure` said (the shape) AND what
     *  `Skipped` said (what never reaches the bundle, and why), inline, in one pass.
     *  Noise dirs collapse to one line with their weight and file count. */
    treeLines() {
        const root = this.rootDir;
        const lines = [];
        const walk = (dir, prefix) => {
            let entries; try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return; }
            const items = entries.map((e) => ({ name: e.name, fp: path.join(dir, e.name), isDir: e.isDirectory() }))
                .sort((a, b) => (b.isDir - a.isDir) || a.name.localeCompare(b.name));
            items.forEach((it, i) => {
                const last = i === items.length - 1;
                const branch = prefix + (last ? '└── ' : '├── ');
                if (it.isDir) {
                    const why = this.whyDir(it.name, it.fp);
                    if (why) {
                        const { files, bytes } = dirStats(it.fp);
                        lines.push(`${branch}${it.name}/   ← ${why} · ${fmtSize(bytes)}, ${files} file(s)`);
                    } else {
                        lines.push(`${branch}${it.name}/`);
                        walk(it.fp, prefix + (last ? '    ' : '│   '));
                    }
                    return;
                }
                const why = this.whyFile(it.fp);
                lines.push(`${branch}${it.name} (${fmtSize(statSize(it.fp))})${why ? `   ← ${why}` : ''}`);
            });
        };

        lines.push(`${path.basename(root)}/`);
        walk(root, '');
        return { lines };
    }

    // ─── output builders ─────────────────────────────────────────────────────
    // Header first (what this file is, when, how heavy), then the card verbatim —
    // the one place the PROJECT speaks — then the harvested map. One H1.
    buildOverview({ files, tree, stats, zipKB }) {
        const card = this.aiCard();
        return this.buildHeader({ files, stats, zipKB })
            + (card ? `\n${card.startsWith('---') ? '' : '---\n\n'}${card}\n---\n` : '')   // a frontmatter card brings its own rule
            + this.buildReadFirstSection(files)
            + this.buildDependencySection(files)
            + (card ? '' : this.buildGuidelinesSection())   // the card owns the doctrine when present
            + this.buildCommitsSection()
            + this.buildTreeSection(tree)
            + this.buildFilesSection(files)
            + this.buildRecentSection(files);
    }
    buildZip(files, overviewMd) {
        const zip = new Zip();
        zip.add(this.overviewName, Buffer.from(overviewMd, 'utf8'));
        for (const f of files) zip.add(`${this.projectName}/${f.path}`, f.isBinary ? f.content : Buffer.from(f.content, 'utf8'));
        return zip.finalize();
    }

    // ─── entry point ─────────────────────────────────────────────────────────
    async generate() {
        this.log('scanning ' + this.rootDir, 'info');
        const files = await this.collectFiles(this.rootDir);
        files.sort((a, b) => {
            const da = a.path.split('/').length, db = b.path.split('/').length;
            return da - db || a.path.localeCompare(b.path);
        });
        const tree = this.treeLines();
        const stats = this.buildStats(files);

        // zipKB rides in the header, so size once then rebuild with the real number. Cheap.
        let overview = this.buildOverview({ files, tree, stats, zipKB: '?' });
        let zipBuf = this.buildZip(files, overview);
        overview = this.buildOverview({ files, tree, stats, zipKB: (zipBuf.length / 1024).toFixed(1) });
        zipBuf = this.buildZip(files, overview);
        await fs.promises.writeFile(this.zipFile, zipBuf);
        this.log(`${path.basename(this.zipFile)}  ${(zipBuf.length / 1024).toFixed(1)} KB  (${files.length} files + ${this.overviewName})`, 'ok');

        // The overview alone, beside the zip: 500 lines, structural, always relevant —
        // the file for Project knowledge (which refuses archives). The source stays in
        // the zip, dropped in a chat when a task needs it.
        const ovFile = this.zipFile.replace(/\.zip$/, '-overview.md');
        await fs.promises.writeFile(ovFile, overview, 'utf8');
        this.log(`${path.basename(ovFile)}  ${(Buffer.byteLength(overview, 'utf8') / 1024).toFixed(1)} KB  (~${fmtTokens(estTokens(overview.length))} tokens — Project knowledge)`, 'ok');
        this.log(`${files.length} files · ${stats.totalLines} lines · ${stats.totalKB.toFixed(1)} KB source`, 'out');
        for (const [rel, label, line] of this.secrets)
            this.log(`WITHHELD  ${rel}:${line}  — looks like a ${label}`, 'err');
        if (this.secrets.length) this.log(`${this.secrets.length} file(s) withheld by the secret gate — review before sharing`, 'err');
        const { dirs, files: skf } = this.skipped;
        if (dirs.length || skf.length) this.log(`skipped ${dirs.length} dir(s), ${skf.length} file(s)`, 'out');
    }
}

/** Generate the AI-context bundle for the project dir, streaming into the deck. */
/** Full project tree — EVERYTHING on disk, each entry tagged with why it is (or
 *  is not) bundled. The bundle's own Structure section shows only what it keeps;
 *  this shows what is actually there, so leftovers have nowhere to hide.
 *  Noise dirs (.git, node_modules, build output) collapse to one line with their
 *  weight and file count. Classification is delegated to the Bundler — one
 *  authority decides what is context and what is not.
 *  Streams to the console; the same tree is a section of the overview. */
async function doContext(cfg, send) {
    const root = path.resolve(DIR, cfg.PROJECT_DIR || '.');
    if (!fs.existsSync(root)) throw new Error(`no project dir: ${root}`);
    send('line', { text: `ai-context → ${path.basename(root)}`, kind: 'cmd' });
    await new Bundler({
        ...CONTEXT, rootDir: root, guard, log: (text, kind) => send('line', { text, kind }),
        keepFiles: list(cfg.KEEP_FILES),                                          // raw bytes the bundle must carry
        skipPatterns: [...CONTEXT.skipPatterns, ...list(cfg.SKIP_FILES).map((g) => globRe(g, 'i'))],   // project-generated files, by glob
    }).generate();
    send('line', { text: `bundle written to ${root}`, kind: 'ok' });
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

const server = http.createServer(async (req, res) => {
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
<title>nimbus · build</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><rect width='24' height='24' rx='5' fill='%2314181e'/><path d='M6 16a3.4 3.4 0 0 1 .8-6.7A4.7 4.7 0 0 1 16 8a3 3 0 0 1 .4 6' fill='none' stroke='%23a6ee52' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/></svg>">
<style>
/* ════════════ TOKENS — dark instrument, one lime accent ════════════ */
:root{
 --bg:#14181e; --bg-2:#0f1318;                 /* anthracite — lifted off pure black */
 --panel:#1a1f28; --panel-2:#1e242e;           /* control panels / field face */
 --line:rgba(231,238,250,.08); --line-2:rgba(231,238,250,.15);
 --txt:#cfd6e1; --dim:#8d97a6; --mut:#5b6573;   /* soft off-white text, never #fff on black */
 --accent:#a6ee52; --accent-fill:#97e157; --accent-dim:#6fa53c; --accent-ink:#0f1604; --accent-soft:rgba(166,238,82,.16);
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
.logo{width:36px;height:36px;flex:none;display:grid;place-items:center;color:var(--accent);
 border:1px solid var(--accent);border-radius:7px;box-shadow:inset 0 0 0 1px rgba(166,238,82,.12)}
.logo .ic{width:20px;height:20px}
.title{display:flex;flex-direction:column;gap:3px;line-height:1}
.title b{font-size:18px;font-weight:750;letter-spacing:.18em;color:#dde3ec}
.title small{font:10px var(--mono);letter-spacing:.18em;text-transform:uppercase;color:var(--mut)}
.hdr .spacer{margin-left:auto}
.readout{display:inline-flex;align-items:center;gap:8px;font:10.5px var(--mono);letter-spacing:.14em;text-transform:uppercase;
 color:var(--dim);padding:7px 12px;border:1px solid var(--line-2);border-radius:6px;background:var(--bg-2)}
.dot{width:7px;height:7px;border-radius:50%;background:var(--mut);transition:background .2s}
.dot.on{background:var(--accent);box-shadow:0 0 0 0 var(--accent-soft);animation:pulse 2.4s ease-in-out infinite}
.dot.off{background:var(--err)}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(166,238,82,0)}50%{box-shadow:0 0 0 4px rgba(166,238,82,.18)}}
.ver{font:10.5px var(--mono);letter-spacing:.14em;color:var(--mut);padding:7px 11px;border:1px solid var(--line);border-radius:6px}
.readout.jdk{letter-spacing:.06em;text-transform:none;color:var(--mut)}
.readout.jdk b{font-weight:600;color:var(--dim)}
.readout.jdk.bad{color:var(--err);border-color:rgba(224,90,90,.35)}
.cog{appearance:none;cursor:pointer;display:inline-flex;align-items:center;gap:7px;color:var(--dim);
 font:10.5px var(--mono);letter-spacing:.14em;text-transform:uppercase;padding:7px 12px;border-radius:6px;
 background:var(--panel-2);border:1px solid var(--line-2);transition:color .15s,border-color .15s,background .15s}
.cog:hover{color:var(--accent);border-color:var(--accent)}

/* ───── two-column operating layout ───── */
.main{display:grid;grid-template-columns:430px 1fr;gap:13px;flex:1;min-height:0}
.left{display:flex;flex-direction:column;gap:16px;overflow:auto;padding-right:4px}
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
.acts{display:grid;grid-template-columns:1fr;gap:7px}
.btn .lab{display:flex;flex-direction:column;align-items:flex-start;gap:2px;line-height:1.25;text-align:left}
.btn .lab em{font:9.5px var(--mono);font-style:normal;letter-spacing:.02em;color:var(--mut);text-transform:none}
.btn:hover .lab em{color:var(--dim)}
.dsub{grid-column:1/-1;display:flex;align-items:center;gap:10px;margin:9px 0 -1px;
 font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.2em;color:var(--mut);white-space:nowrap}
.dsub::after{content:"";flex:1;height:1px;background:linear-gradient(90deg,var(--line-2),transparent)}
.dsub em{font-style:normal;letter-spacing:.02em;text-transform:none;color:var(--line-2)}
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
.con-head .copy{appearance:none;cursor:pointer;display:inline-flex;align-items:center;gap:6px;color:var(--mut);
 font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.14em;padding:5px 10px;border-radius:5px;
 background:transparent;border:1px solid transparent;transition:color .15s,border-color .15s}
.con-head .copy:hover{color:var(--accent);border-color:rgba(166,238,82,.3)}
.con-head .copy.done{color:var(--accent);border-color:rgba(166,238,82,.45)}
.con-head .copy .ic{width:12px;height:12px}
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
.drawer{position:fixed;top:15px;right:15px;bottom:15px;width:452px;max-width:92vw;z-index:91;border-radius:8px;
 display:grid;grid-template-columns:1fr 1fr;align-content:start;gap:11px 12px;padding:18px 20px;overflow:auto;
 border:1px solid var(--line-2);background:linear-gradient(180deg,var(--panel),#0c1016);
 box-shadow:-20px 0 60px -30px rgba(0,0,0,.9);transform:translateX(calc(100% + 24px));transition:transform .3s cubic-bezier(.4,0,.2,1)}
.drawer.open{transform:translateX(0)}
.dh{grid-column:1/-1;display:flex;align-items:baseline;gap:11px;padding-bottom:12px;border-bottom:1px solid var(--line)}
.dh .no{font:750 12px var(--mono);color:var(--accent-dim)}
.dh b{font-size:11px;font-weight:600;text-transform:uppercase;letter-spacing:.24em;color:var(--dim)}
.dh .x{margin-left:auto}
.df{display:flex;flex-direction:column;gap:5px;min-width:0}
.df.full{grid-column:1/-1}
.df label{font:9.5px var(--mono);text-transform:uppercase;letter-spacing:.12em;color:var(--mut)}
.note{grid-column:1/-1;font:10px/1.5 var(--mono);color:var(--warn);padding:9px 11px;border-radius:5px;
 background:rgba(224,162,58,.07);border:1px solid rgba(224,162,58,.2)}
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
 <symbol id="i-cog" viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></symbol>
 <symbol id="i-package" viewBox="0 0 24 24"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.3 7 12 12l8.7-5"/><path d="M12 22V12"/></symbol>
 <symbol id="i-layers" viewBox="0 0 24 24"><path d="M12 2 2 7l10 5 10-5z"/><path d="m2 17 10 5 10-5"/><path d="m2 12 10 5 10-5"/></symbol>
 <symbol id="i-cpu" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6"/><path d="M9 2v2M15 2v2M9 20v2M15 20v2M2 9h2M2 15h2M20 9h2M20 15h2"/></symbol>
 <symbol id="i-refresh" viewBox="0 0 24 24"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></symbol>
 <symbol id="i-stop" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2.5"/></symbol>
 <symbol id="i-trash" viewBox="0 0 24 24"><path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></symbol> <symbol id="i-copy" viewBox="0 0 24 24"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></symbol>
</defs></svg>

<div class="wrap">

  <div class="hdr">
    <div class="logo"><svg class="ic"><use href="#i-package"/></svg></div>
    <div class="title"><b>NIMBUS</b><small id="deck">Build Deck</small></div>
    <div class="spacer"></div>
    <span class="readout"><span class="dot" id="dot"></span><span id="conn">link…</span></span>
    <span class="readout jdk" id="jdk" title="JDK toolchain">probing…</span>
    <span class="ver" id="ver">—</span>
    <button class="cog" id="b-cfg" title="build constants — set once"><svg class="ic"><use href="#i-cog"/></svg> config</button>
  </div>

  <div class="main">
    <div class="left">

      <section>
        <div class="sec-h"><span class="no">01</span><span class="ti">Configuration</span></div>
        <div class="fields">
          <div class="f full"><label>project dir</label><input id="f-PROJECT_DIR" placeholder="."></div>
          <div class="f full"><label>main class · java -jar</label><input id="f-JAR_MAIN"></div>
          <div class="f"><label>jar name</label><input id="f-JAR_NAME"></div>
        </div>
      </section>

      <section>
        <div class="sec-h"><span class="no">02</span><span class="ti">Build</span></div>
        <div class="acts">
          <button class="btn" id="b-jar"><svg class="ic"><use href="#i-layers"/></svg><span class="lab">compile to jar<em>javac, resources, lib jars exploded → one fat jar</em></span></button>
          <button class="btn go" id="b-build"><svg class="ic"><use href="#i-package"/></svg><span class="lab">build application<em>the jar, wrapped by jpackage with its own runtime</em></span></button>
        </div>
      </section>

      <section>
        <div class="sec-h"><span class="no">03</span><span class="ti">Context</span></div>
        <div class="acts">
          <button class="btn" id="b-context"><svg class="ic"><use href="#i-cpu"/></svg><span class="lab">ai bundle<em>scan the tree → _ai-&lt;project&gt;.zip + -overview.md</em></span></button>
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
          <button class="copy" id="b-copy"><svg class="ic"><use href="#i-copy"/></svg><span>copy</span></button>
          <button class="clear" id="b-clear"><svg class="ic"><use href="#i-trash"/></svg> clear</button>
        </div>
        <div class="log" id="log"></div>
      </div>
      <div class="transport">
        <button class="btn danger" id="b-stop" disabled><svg class="ic"><use href="#i-stop"/></svg>stop</button>
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
  <div class="dh"><span class="no">00</span><b>base config · build constants</b><button class="cog x" id="cfg-close">esc</button></div>
  <div class="note">Set once, then forget. Paths are relative to the project dir — nimbus.cjs sits in the project root, so src/ and lib/ are right beside it; no Maven, no ~/.m2.</div>

  <div class="dsub">identity <em>· stamped into the jar manifest and the app-image</em></div>
  <div class="df full"><label>app name</label><input id="f-APP_NAME"><div class="hint">names the app-image folder in the output dir</div></div>
  <div class="df"><label>app version</label><input id="f-APP_VERSION"></div>
  <div class="df"><label>vendor</label><input id="f-VENDOR"></div>
  <div class="df full"><label>release</label><input id="f-RELEASE"><div class="hint">javac --release — the bytecode target</div></div>

  <div class="dsub">paths <em>· relative to the project dir</em></div>
  <div class="df"><label>src dir</label><input id="f-SRC_DIR"></div>
  <div class="df"><label>lib dir</label><input id="f-LIB_DIR"></div>
  <div class="df"><label>scratch dir</label><input id="f-SCRATCH_DIR"><div class="hint">intermediate build output</div></div>
  <div class="df"><label>output dir</label><input id="f-OUT_DIR"><div class="hint">where the jar, the app-image and its zip land</div></div>

  <div class="dsub">packaging</div>
  <div class="df full"><label>jpackage type</label><input id="f-JPACKAGE_TYPE"><div class="hint">app-image → folder + native launcher, no installer tooling</div></div>
  <div class="df full"><label>icon file <span style="text-transform:none;letter-spacing:.02em">(optional)</span></label><input id="f-ICON_FILE"><div class="hint">blank → &lt;name&gt;.ico / app.ico in the output dir</div></div>

  <div class="dsub">stored locally</div>
  <div class="df full"><div class="hint">Values you change are kept in this browser (localStorage); everything else follows the defaults shipped with nimbus.cjs.</div>
    <button class="btn danger" id="b-reset" style="justify-content:center;margin-top:6px"><svg class="ic"><use href="#i-refresh"/></svg>reset saved values</button></div>
</div>

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
var BTNS=['jar','build','context'];
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
  var l=$('#log');
  var d=document.createElement('div'); if(kind)d.className=kind;
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
  es.onopen   =function(){ $('#conn').txt('link ok'); $('#dot').className='dot on'; };
  es.onmessage=function(e){ handle(JSON.parse(e.data)); };
  es.onerror  =function(){ $('#conn').txt('no link'); $('#dot').className='dot off'; };
}

// ── wiring ──────────────────────────────────────────────────────────────────
function showTools(t){
  if(!t||!t.length)return;
  var missing=t.filter(function(p){ return !p.ok; });
  $('#jdk').innerHTML=t.map(function(p){ return p.tool+' <b>'+(p.ok?p.version:'—')+'</b>'; }).join(' · ');
  $('#jdk').cls('bad',missing.length>0);
  $('#jdk').title=missing.length? 'missing: '+missing.map(function(p){return p.tool;}).join(', ')+' — check JAVA_HOME'
                                : 'JDK toolchain ready';
  if(missing.length)line('toolchain incomplete — '+missing.map(function(p){return p.tool;}).join(', ')+' not found','err');
}
fetch('/api/defaults').then(function(r){ return r.json(); }).then(function(j){ DEFAULTS=j.defaults; $('#deck').txt(j.app+' Build Deck'); fill(DEFAULTS); applySaved(); showTools(j.tools); });
KEYS.forEach(function(k){ $('#f-'+k).on('input',save).on('change',save); });
BTNS.forEach(function(a){ $('#b-'+a).on('click',function(){ send(a); }); });
$('#b-stop').on('click',function(){ fetch('/stop',{method:'POST'}); });
$('#b-clear').on('click',function(){ $('#log').innerHTML=''; $('#con-act').txt('idle'); $('#m-stat').txt('ready'); });
// ── console copy (self-contained, no regex, no helper deps) ──
(function () {
  var btn = document.getElementById('b-copy');
  var log = document.getElementById('log');
  if (!btn || !log) return;
  var label = btn.querySelector('span');
  function flash() {
    btn.classList.add('done');
    if (label) label.textContent = 'copied';
    setTimeout(function () { btn.classList.remove('done'); if (label) label.textContent = 'copy'; }, 1100);
  }
  function legacy(text) {
    try {
      var ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.left = '-9999px';
      document.body.appendChild(ta); ta.focus(); ta.select();
      var ok = document.execCommand('copy'); document.body.removeChild(ta); return ok;
    } catch (e) { return false; }
  }
  btn.addEventListener('click', function () {
    var t = (log.innerText || '').trim();
    if (!t) return;
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(t).then(flash, function () { if (legacy(t)) flash(); });
    } else if (legacy(t)) { flash(); }
  });
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
