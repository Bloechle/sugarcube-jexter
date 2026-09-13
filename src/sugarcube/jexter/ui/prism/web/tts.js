// tts.js — "Read aloud", a Prism tool module
//
// Extracted from the chassis on 2026-09-05. The rule that put it here: the chassis holds what EVERY tool
// needs; a feature that nothing else uses is a module. Read aloud passed that test on its own merits —
// its only ties to the chassis were goTo, centreOn, markRect and whenFrameReady, all four already on the
// public seam, so it needed no new API to leave. That is the point of the move as much as the 97 lines:
// it proves the seam is complete enough for a real feature to live outside.
//
// The pages carry real <text> in reading order, so we can narrate them and track the spoken position: the
// page text is concatenated with offsets, and the utterance's boundary events (charIndex) map back to the
// <text> element being read — highlighted live, scrolled into view, auto-advancing like an audiobook.

const P = window.prism;

const tts = { on: false, cur: null };

function ttsToggle() {
  if (tts.on) { ttsStop(); return; }
  if (P.state.current < 0 || !('speechSynthesis' in window)) return;
  tts.on = true;
  $('#b-tts').cls('+active');
  ttsPage(P.state.current);
}

function ttsStop() {
  tts.on = false;
  try { speechSynthesis.cancel(); } catch {}
  clearTtsMarks();
  $.opt('#b-tts')?.cls('-active');
}

function eachLoadedFrame(cb) {
  $.all('.prism-frame').forEach(f => {
    try {
      const doc = f.contentDocument; if (!doc || !doc.querySelector('svg')) return;
      const idx = +f.closest('.prism-page')?.getAttribute('data-index');
      if (idx >= 0) cb(idx, doc, f);
    } catch { /* not ready */ }
  });
}
const clearTtsMarks = () => eachLoadedFrame((idx, doc) => doc.querySelectorAll('[data-px-tts]').forEach(n => n.remove()));

function ttsPage(idx) {
  if (!tts.on) return;
  tts.cur = null;
  P.goTo(idx);
  P.whenFrameReady(idx, doc => {
    if (!tts.on) return;
    const svg = doc.querySelector('svg');
    let nodes = window.prism.hooks.ttsNodes?.(doc, idx);   // jexter: text-layer runs (SVG-OCD pages carry no <text>)
    if (!nodes) nodes = (svg ? [...svg.querySelectorAll('text')].filter(t => t.textContent.trim()) : [])
        .map(el => ({ el, text: el.textContent }));
    if (!nodes.length) { ttsNext(idx); return; }

    // Paragraphs mode (array of arrays, texts pre-shaped by the hook): one utterance
    // per paragraph, queued with a short breath between — the synthesizer resets its
    // prosody per utterance, so sentences fall at periods and paragraphs breathe.
    const paras = Array.isArray(nodes[0]) ? nodes.filter(g => g.length)
        : [nodes.map(({ el, text }) => ({ el, text: text.replace(/\s+/g, ' ').trim() + ' ' }))];
    const lang = (svg && (svg.getAttribute('xml:lang') || svg.getAttribute('lang'))) || P.state.lang;
    let k = 0;
    const speakPara = () => {
      if (!tts.on) return;
      if (k >= paras.length) { ttsNext(idx); return; }
      const group = paras[k++];
      const spans = []; let full = '';
      group.forEach(({ el, text }) => {
        spans.push({ start: full.length, end: full.length + text.length, el });
        full += text;                                       // the hook owns spacing (hyphen repair)
      });
      if (!full.trim()) { speakPara(); return; }
      const u = new SpeechSynthesisUtterance(full);
      u.lang = lang;
      u.onboundary = e => { if (e.charIndex != null) ttsMark(doc, spans, e.charIndex, idx); };
      u.onend = () => { if (tts.on) setTimeout(speakPara, 340); };   // the paragraph breath
      u.onerror = () => ttsStop();
      speechSynthesis.speak(u);
    };
    speechSynthesis.cancel();
    speakPara();
  });
}

function ttsNext(idx) {
  clearTtsMarks();
  if (idx + 1 < P.state.pages.length) ttsPage(idx + 1);
  else ttsStop();
}

// Highlight the <text> containing the spoken charIndex; keep it in view.
function ttsMark(doc, spans, at, idx) {
  const span = spans.find(sp => at >= sp.start && at < sp.end);
  if (!span || span.el === tts.cur) return;
  tts.cur = span.el;
  doc.querySelectorAll('[data-px-tts]').forEach(n => n.remove());
  P.markRect(doc, span.el, 'data-px-tts', 'rgba(116,183,62,.30)');
  const f = $.opt(`#page-${idx} .prism-frame`), s = $('#epub-scroll');
  if (!f) return;
  const er = span.el.getBoundingClientRect(), fr = f.getBoundingClientRect(), sr = s.getBoundingClientRect();
  const top = fr.top + er.top * P.state.zoom, bot = fr.top + (er.top + er.height) * P.state.zoom;
  if (top < sr.top + 24 || bot > sr.bottom - 24) P.centreOn(idx, span.el);
}

// The footer button owns the toggle; closing a book must silence it.
$('#b-tts')?.on('click', ttsToggle);
P.on('close', ttsStop);

// The chassis stops narration when it needs to (closing, re-opening): one exported verb, no state shared.
P.ttsStop = ttsStop;
