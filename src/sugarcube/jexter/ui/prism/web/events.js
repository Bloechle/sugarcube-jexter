// events.js — the chassis bus: multicast events, published to the tools as `P.on` (prism.js lists them).
const subs = new Map();                          // event → Set<cb>
export function on(evt, cb) {
  if (!subs.has(evt)) subs.set(evt, new Set());
  subs.get(evt).add(cb);
  return () => subs.get(evt).delete(cb);
}
export function emit(evt, ...args) {
  for (const cb of subs.get(evt) || []) { try { cb(...args); } catch (e) { console.error(e); } }
}

