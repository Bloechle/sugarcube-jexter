// console.js — the F2 console: console.* and uncaught errors captured into a panel, beside what the engine
// (its SSE log) and the Service Worker say. One line writer, logLine, for all three.
import { toast, copy } from '/shared/vendor/qry/qry-app.js';

const LOG_MAX = 2000;

export function logLine(level, msg, src = 'client') {
  const body = $.opt('#log-body'); if (!body) return;
  const atBottom = body.scrollTop + body.clientHeight >= body.scrollHeight - 8;
  const row = $.create('div', { class: `log-row lv-${level}` });
  row.innerHTML = `<span class="log-lv">${$.esc(level)}</span><span class="log-src">${$.esc(src)}</span><span class="log-msg">${$.esc(msg)}</span>`;
  body.appendChild(row);
  while (body.childElementCount > LOG_MAX) body.firstChild.remove();
  if (atBottom) body.scrollTop = body.scrollHeight;
}

export function toggleLog(force) {
  const c = $.opt('#log-console'); if (!c) return;
  const open = force === undefined ? !c.classList.contains('open') : force;
  c.cls(open ? '+open' : '-open');
}

function clearLog() { $.opt('#log-body')?.empty(); }

function copyLog() {
  const body = $.opt('#log-body'); if (!body) return;
  const rows = [...body.querySelectorAll('.log-row')].filter(r => r.offsetParent !== null);
  const text = rows.map(r => `${r.querySelector('.log-lv').textContent} ${r.querySelector('.log-msg').textContent}`.trim()).join('\n');
  if (!text) { toast('Console is empty', { tone: 'warn' }); return; }
  copy(text); toast(`Copied ${rows.length} line${rows.length === 1 ? '' : 's'}`, { tone: 'ok' });
}

const _fmt = a => { try { return typeof a === 'string' ? a : a instanceof Error ? a.message : JSON.stringify(a); } catch { return String(a); } };

export function installLogCapture() {
  const map = { log: 'info', info: 'info', warn: 'warn', error: 'error', debug: 'debug' };
  for (const m of Object.keys(map)) {
    const orig = console[m] ? console[m].bind(console) : () => {};
    console[m] = (...a) => { orig(...a); try { logLine(map[m], a.map(_fmt).join(' ')); } catch {} };
  }
  window.addEventListener('error', e => { try { logLine('error', (e.message || 'error') + (e.filename ? ` @ ${e.filename.split('/').pop()}:${e.lineno}` : '')); } catch {} });
  window.addEventListener('unhandledrejection', e => { try { logLine('error', 'unhandled: ' + (e.reason?.message || e.reason || '')); } catch {} });
}

/** The console's own controls: close, clear, copy, the level filter. */
export function wireConsole() {
  $('#log-close').on('click', () => toggleLog(false));
  $('#log-title').on('click', () => toggleLog(false));
  $('#log-clear').on('click', clearLog);
  $('#log-copy').on('click', copyLog);
  $.all('.log-f').forEach(b => b.on('click', () => {
    $.all('.log-f').forEach(x => x.cls('-active')); b.cls('+active');
    $('#log-console').attr('data-filter', b.attr('data-lvl'));
  }));
}
