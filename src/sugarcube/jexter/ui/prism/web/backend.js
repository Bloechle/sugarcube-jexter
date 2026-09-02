// backend.js — DESKTOP hub of Prism.
//
// The single per-environment seam: the front (jexter.js) imports this and never names a
// route or a transport itself. Here, the local Prism Java server is a stateless convert
// engine; the front holds the book bytes and posts to /api/convert for everything.
// A web hub with the SAME contract (QryServer gateway → sugarcloud, auth, library) can
// replace this file and the chrome doesn't change — that's the whole point.

const API = ''; // same-origin

async function artifact(path, { method = 'POST', body } = {}) {
    const r = await fetch(API + path, { method, body });
    if (!r.ok) throw new Error((await r.text().catch(() => '')) || `HTTP ${r.status}`);
    const cd = r.headers.get('content-disposition') || '';
    const m = /filename="?([^"]+)"?/.exec(cd);
    const blob = await r.blob();
    return {
        blob,
        bytes: new Uint8Array(await blob.arrayBuffer()),
        filename: m ? m[1] : '',
        mediaType: r.headers.get('content-type') || '',
    };
}
const qs = (to, opts) => '?' + new URLSearchParams({ to, ...opts }).toString();

// What this environment can do — the front gates its UI on these (no transport knowledge leaks up).
// Which environment this build runs in — the single web/desktop discriminator for the chrome
// (caps stay for individual features). One implementation, env-driven differences.
export const env = 'desktop';

// Shared-asset root: desktop serves the logo/favicon SVGs under /shared, the web front at root.
export const assetsBase = '/shared';

export const caps = {
    reconvert: true, options: true, targets: true, health: true,
    ai: true, auth: false, library: false, catalog: false, logStream: true, openByPath: true,
    repository: false, backendStatus: false,   // no /repository/ samples; local engine -> no remote health dot
};

// The window-alive heartbeat: the local server self-exits when no client holds this open.
export const aliveUrl = '/api/alive';

// The one workhorse. Source bytes (PDF or .ocd) → target artifact { bytes, filename, mediaType }.
//   open       = convert(pdfBytes, 'ocd')
//   reconvert  = convert(pdfBytes, 'ocd', importOpts)
//   export     = convert(srcBytes, 'pdf'|'epub'|…, exportOpts)
export const convert = (src, to = 'ocd', opts = {}) =>
    artifact('/api/convert' + qs(to, opts), { body: src });

// Fetch a source by URL server-side (no browser CORS), then convert.
export const convertUrl = (url, to = 'ocd', opts = {}) =>
    artifact('/api/convert' + qs(to, { ...opts, url }), { body: new Uint8Array() });

// Re-open a file straight off local disk (desktop only — the engine reads the path).
export const convertPath = (path, to = 'ocd', opts = {}) =>
    artifact('/api/convert' + qs(to, { ...opts, path }), { body: new Uint8Array() });

// Analysis pipeline stages (per page, page-space boxes) for the renderer's analysis layer. Rides the one
// engine route with to=stages; src is the .ocd blob (or PDF bytes). null when unavailable (older engine).
export const stages = async (src) => {
    try { const r = await fetch(API + '/api/convert?to=stages', { method: 'POST', body: src }); return r.ok ? r.json() : null; }
    catch { return null; }
};

export const options = async () => (await fetch('/api/options')).json();
export const targets = async () => (await (await fetch('/api/targets')).json()).targets;
export const health  = async () => (await fetch('/api/health')).text();

// AI (desktop only): bind/unbind a model, and stop an in-flight refine.
export const ai = {
    config: (cfg) => fetch('/api/ai/config', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(cfg),
    }).then((r) => r.json()),
    // Interrupt a running refine; the page-windowed pass stops at the next page and keeps a partial structure.
    stop: () => fetch('/api/ai/stop', { method: 'POST' }).then((r) => r.json()),
};

// Curated sample PDFs (open by URL via convertUrl).

// The front opens an EventSource on this for the F2 console.
export const logStreamUrl = '/api/log';
