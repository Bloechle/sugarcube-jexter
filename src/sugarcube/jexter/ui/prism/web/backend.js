// backend.js — Prism's transport seam.
//
// The front (engine.js and the tools) imports this and never names a route or a transport
// itself. The local Prism Java server is a stateless convert engine, on the same contract as
// the conversion service (doc/FRONTEND.md); the front holds the book bytes and posts to
// /api/convert for everything.

const API = ''; // same-origin

// The transport reports what only the transport can see. The engine has its own channel (JxProgress over
// /api/progress) and it says nothing until the bytes have arrived — on a large book, or over anything
// slower than a loopback, the upload and the download are a real part of the wait. Both steps carry the
// SAME shape as the engine's, {stage, done, total, detail}, so one renderer serves both and the front
// never has to know which side a step came from.
async function artifact(path, { method = 'POST', body, onStep } = {}) {
    const step = onStep || (() => {});
    const { res, streamed } = await send(API + path, method, body, step);
    if (!res.ok) throw new Error((await res.text().catch(() => '')) || `HTTP ${res.status}`);
    const blob = streamed ? await res.blob() : await download(res, step);
    return {
        blob,
        bytes: new Uint8Array(await blob.arrayBuffer()),
        filename: fileNameOf(res.headers.get('content-disposition')),
        mediaType: res.headers.get('content-type') || '',
    };
}

// fetch() cannot see its own request body go out; XHR can, on both sides — `upload.onprogress` for the
// bytes leaving and `onprogress` for the bytes coming back — and it does so over plain HTTP/1.1, which
// streamed fetch request bodies do not. Falls back to fetch when there is nothing to watch (an empty
// body) or when XHR is unavailable.
function send(url, method, body, step) {
    const size = body ? (body.byteLength ?? body.size ?? 0) : 0;
    if (!size || typeof XMLHttpRequest === 'undefined') return fetch(url, { method, body }).then(res => ({ res, streamed: false }));
    return new Promise((resolve, reject) => {
        const x = new XMLHttpRequest();
        x.open(method, url, true);
        x.responseType = 'blob';
        x.upload.onprogress = e => step({ stage: 'upload', done: e.loaded, total: e.total || size });
        x.onprogress = e => { if (e.lengthComputable) step({ stage: 'download', done: e.loaded, total: e.total }); };
        x.onerror = () => reject(new Error('network'));
        x.onload = () => resolve({                     // already reported above; nothing left to stream
            res: new Response(x.response, { status: x.status, headers: headersOf(x.getAllResponseHeaders()) }),
            streamed: true,
        });
        x.send(body);
    });
}

// Fetch fallback only: read the response as it arrives when the length is known.
async function download(r, step) {
    const total = Number(r.headers.get('content-length') || 0);
    if (!total || !r.body || typeof r.body.getReader !== 'function') return r.blob();
    const reader = r.body.getReader();
    const chunks = [];
    let done = 0;
    for (;;) {
        const c = await reader.read();
        if (c.done) break;
        chunks.push(c.value);
        done += c.value.length;
        step({ stage: 'download', done, total });
    }
    return new Blob(chunks, { type: r.headers.get('content-type') || '' });
}

function headersOf(raw) {
    const h = new Headers();
    for (const line of (raw || '').trim().split(/[\r\n]+/)) {
        const i = line.indexOf(':');
        if (i > 0) h.append(line.slice(0, i).trim(), line.slice(i + 1).trim());
    }
    return h;
}
const qs = (to, opts) => '?' + new URLSearchParams({ to, ...opts }).toString();

// The window-alive heartbeat: the local server self-exits when no client holds this open.
export const aliveUrl = '/api/alive';

// The one workhorse. Source bytes (PDF or .ocd) → target artifact { bytes, filename, mediaType }.
//   open       = convert(pdfBytes, 'ocd')
//   reconvert  = convert(pdfBytes, 'ocd', importOpts)
//   export     = convert(srcBytes, 'pdf'|'epub'|…, exportOpts)
export const convert = (src, to = 'ocd', opts = {}, onStep) =>
    artifact('/api/convert' + qs(to, opts), { body: src, onStep });

// Analysis pipeline stages (per page, page-space boxes) for the renderer's analysis layer. Rides the one
// engine route with to=stages; src is the .ocd blob (or PDF bytes). null when unavailable (older engine).
export const stages = async (src) => {
    try { const r = await fetch(API + '/api/convert?to=stages', { method: 'POST', body: src }); return r.ok ? r.json() : null; }
    catch { return null; }
};

export const options = async () => (await fetch('/api/options')).json();
export const health  = async () => (await fetch('/api/health')).text();

// A font file (TTF/OTF bytes) → the container's own font block (a one-font fonts.svg) for `chars`, under
// the alias `id` — what book.addFont merges.
export const font = async (bytes, chars, id) => {
    const r = await fetch(API + '/api/font' + '?' + new URLSearchParams({ chars, id }), { method: 'POST', body: bytes });
    if (!r.ok) throw new Error((await r.json().catch(() => ({}))).error || `HTTP ${r.status}`);
    return r.text();
};

// The front opens an EventSource on this for the F2 console.
export const logStreamUrl = '/api/log';

// The front opens an EventSource on this while a conversion runs: {stage,done,total,detail}.
export const progressUrl = '/api/progress';

/** The file name a Content-Disposition carries: the exact UTF-8 one (RFC 5987) first, else the plain one. */
export function fileNameOf(cd) {
    const exact = /filename\*=UTF-8''([^;]+)/i.exec(cd || '');
    if (exact) try { return decodeURIComponent(exact[1]); } catch { /* a malformed escape: take the plain name */ }
    return /filename="?([^";]+)"?/i.exec(cd || '')?.[1] || '';
}
