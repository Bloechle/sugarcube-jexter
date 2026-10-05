/**
 * qry-web.js — everything a website or a web app needs on top of qry.js (ES module)
 *
 *   <link rel="stylesheet" href="qry-ui.css">
 *   <script src="qry.js"></script>
 *   <script type="module">
 *     import { makeViewer, reveal, toast, confirm, makeForm, makeTabs } from './qry-web.js';
 *   </script>
 *
 * Named exports — import what the page uses. Native first (<dialog>, IntersectionObserver,
 * pointer events), no dependency, everything styled by qry-ui.css and prefixed `qry-`.
 * Conventions:
 *   • `make*` builds something stateful and returns its CONTROLLER ({ …, destroy() });
 *     builders (infoRow, chips, sortableTable) return an ELEMENT; everything else is an
 *     action (toast, copy, reveal…) or a namespace (format.*, theme.*).
 *   • Required arguments are positional; optional settings go in ONE trailing object.
 *   • Options name what they point at (tabs, panels, header…) and take a selector or an element.
 *   • State is ARIA when ARIA has a word, else a fixed `is-*` class — never an option.
 *     Colours are tones: ok · warn · danger · purple · accent.
 *   • Every controller tears down through one AbortController: destroy() aborts it.
 *   • Importing is inert: nothing touches the DOM, window or storage until a function is
 *     called — the module can be imported before a DOM exists (a test sandbox).
 *   • Every user-visible default string comes from ONE object, `labels` — translate it once:
 *     Object.assign(labels, { ok: 'OK', cancel: 'Annuler', … }).
 *
 *   TEXT        labels (the default strings — OK, Cancel, Dismiss, Copied to clipboard…)
 *   PAGE        makeViewer · reveal · scrollSpy · toTop · films · embeds · expire · makeNav · makeSlideshow · urlParam · makeRouter (hash routes)
 *   FORMS       makeForm               (values: form.serialize() — core)
 *   DIALOGS     toast · ask (any dialog) · alert · confirm · prompt (ask, pre-set) · makeDialog (a page's own <dialog>)
 *   THEME       theme.{set,get,toggle,isDark,init} · boot
 *   DATA        format.* (incl. day · daysBetween · plural) · copy · download · readFiles      (server: $.load — core)
 *   LAYOUT      makeTabs · makeMenu · makeSplitter · makeSidebar · makeAutoHideHeader · makeZoomPan
 *   CANVAS      makeCanvas (sharp, themed, redrawn) · makeInk (freehand: signatures, notes)
 *   CHARTS      makeChart (dots, lines, bars, rules — plain SVG in the theme's tokens, redrawn by signals and resize)
 *   INPUT       makeKeyboard (add) · makeSlider (a custom slider's keys + ARIA) · makeDropZone (drop, paste and choose)
 *   EMBEDDING   loadScript · makeIframeAutoHeight · makeIframeEmbed
 *   PWA         makePwa (service worker + updates, install button)
 *   BUILDERS    infoRow · chips · sortableTable          (return an element, not a controller)
 *
 * Requires qry.js 2.x (global `$`).
 *
 * @version 2.0.0
 * @author  Jean-Luc Bloechle with Claude.ai
 * @license MIT
 */

/** @private — a controller's teardown: listeners get `on` ({ signal }), destroy() calls `off`. */
const _scope = () => {
    const ac = new AbortController();
    return { on: { signal: ac.signal }, signal: ac.signal, off: () => ac.abort() };
};

/** @private — the stack's motion, ONE signature: durations in ms from the CSS custom properties
 *  --qry-speed-fast, --qry-speed, --qry-speed-slow (qry-ui.css, or a site's :root) — else
 *  120 / 200 / 500 — and the curve --qry-ease (a soft deceleration, no bounce). */
const _MOTION = { fast: 120, '': 200, slow: 500 };
const _motion = (k = '') => {
    const v = getComputedStyle(document.documentElement).getPropertyValue('--qry-speed' + (k && '-' + k)).trim();
    const n = parseFloat(v);
    return Number.isFinite(n) ? (v.endsWith('ms') ? n : n * 1000) : _MOTION[k];
};
const _EASE = { '': 'cubic-bezier(.2, .7, .2, 1)', move: 'cubic-bezier(.4, 0, .2, 1)' };
/** @private — --qry-ease (arriving: a soft deceleration) or --qry-ease-move (crossing the screen:
 *  eases in AND out — a photo sliding by never jumps off). */
const _ease = (k = '') => getComputedStyle(document.documentElement).getPropertyValue('--qry-ease' + (k && '-' + k)).trim() || _EASE[k];

/** @private — a URI component, decoded (a malformed % sequence stays as it is). */
const _decode = (x) => { try { return decodeURIComponent(x); } catch { return x; } };
/** @private — Web Animations: when they are all over (finished or cancelled), and the two fades. */
const _settled = (moves) => Promise.all(moves.map((x) => x.finished.catch(() => {})));
const _FADE_IN = [{ opacity: 0 }, { opacity: 1 }], _FADE_OUT = [{ opacity: 1 }, { opacity: 0 }];
/** @private — the click a drag ends with is swallowed (it would "click" what is under the finger);
 *  the same rule as qry.js onSwipe, which stays standalone. */
const _swallowClick = (el, signal) => {
    const eat = (c) => { c.stopPropagation(); c.preventDefault(); };
    el.addEventListener('click', eat, { capture: true, once: true, signal });
    setTimeout(() => el.removeEventListener('click', eat, { capture: true }), 400);
};

/** @private — a new tab, a new window, a download: leave the click to the browser. */
const _modified = (e) => e.ctrlKey || e.metaKey || e.shiftKey || e.altKey || e.button > 0;

/** @private — the element an option points at: a selector or an element, or undefined. */
const _opt = (x) => (x == null ? undefined : $.opt(x));

/** @private — the elements an option points at: a selector, an element, or a list of them. */
const _list = (x) => (typeof x === 'string' ? $.all(x) : x instanceof Element ? [x] : [...(x ?? [])]);

// ════════════════════════════════════════════════════════════════════════════
// TEXT — every user-visible default string, in ONE place
// ════════════════════════════════════════════════════════════════════════════

/** The default strings of every helper, read when a helper runs (so a change applies to what
 *  comes next). An app in another language sets them once, at start-up; an option passed to a
 *  call (`confirm(msg, { ok: 'Supprimer' })`) still wins for that call.
 *  @example Object.assign(labels, { cancel: 'Annuler', dismiss: 'Fermer', copied: 'Copié', drop: 'Déposez les fichiers ici' })
 */
export const labels = {
    ok: 'OK',                               // alert, confirm, prompt
    cancel: 'Cancel',                       // confirm, prompt
    dismiss: 'Dismiss',                     // a toast's × button (its accessible name)
    copied: 'Copied to clipboard',          // copy()
    copyFailed: 'Copy failed',              // copy()
    drop: 'Drop files here',                // makeDropZone's overlay
    empty: 'No data',                       // sortableTable without rows
    embed: 'Embedded content',              // embeds(): an iframe's title without data-title
    play: 'Play',                           // films(): the play button (followed by the film's title)
    refused: 'This type of file is not accepted here',   // readFiles(): a file outside `accept`
    update: 'A new version is ready.',      // makePwa(): the update question
    reload: 'Reload',                       // makePwa(): its button
};

// ════════════════════════════════════════════════════════════════════════════
// PAGE — what websites kept re-writing by hand
// ════════════════════════════════════════════════════════════════════════════

/** Photo viewer on your own <dialog>. Links (<a href="big.webp"><img alt="…"></a>) are
 *  delegated: galleries built later by a script just work. A swipe never also closes it.
 *  @param {string|Element} dialog
 *  @param {Object} [o]
 *  @param {string} [o.links='a.zoom']   links that open it
 *  @param {string|((a:Element)=>Element[])} [o.group]   their container (a selector: one group each; default: the page) —
 *                                       or a function giving the links to browse from the clicked one
 *  @param {string} [o.img='img']        in the dialog: where the large image goes
 *  @param {string} [o.caption='figcaption']   where caption + counter go (optional)
 *  @param {string} [o.prev='[data-go="-1"]'] @param {string} [o.next='[data-go="1"]'] @param {string} [o.close='.close']
 *  @param {(a:Element, i:number, n:number) => string} [o.text]  caption HTML (default: data-caption · i / n)
 *  @param {'slide'|'fade'|false} [o.transition='slide']  from one photo to the next: 'slide' = the
 *                                       photos slide by, the old one out and the next one in, side by
 *                                       side, in the direction of travel (a film strip: --qry-speed-slow,
 *                                       --qry-ease-move). On a touch screen the photo FOLLOWS the finger,
 *                                       its neighbour beside it; let go past a fifth of the width (or flick)
 *                                       and it slides on from there, else it settles back;
 *                                       'fade' = a cross-fade; false = none. The old photo stays until the
 *                                       next one is decoded (no half image, no blank), and nothing ever
 *                                       overflows (no scrollbar). Reduced motion → the cross-fade. Needs
 *                                       no CSS. Not false:
 *                                       the viewer also opens (fade, the photo from 97 %) and closes
 *                                       (a quicker fade) — durations from the --qry-speed tokens.
 *  @returns {{ open:(a:Element)=>void, show:(i:number)=>void, next:()=>void, previous:()=>void, close:()=>void, destroy:()=>void }}
 *  @example makeViewer('#viewer', { group: '.gallery' })
 */
export const makeViewer = (dialog, {
    links = 'a.zoom', group = null, img = 'img', caption = 'figcaption',
    prev = '[data-go="-1"]', next = '[data-go="1"]', close = '.close',
    text = (a, i, n) => $.html`${a.dataset.caption && $.html`<span class="qry-viewer-caption">${a.dataset.caption}</span> `}${n > 1 && $.html`<span class="qry-viewer-pos">${i + 1} / ${n}</span>`}`,
    transition = 'slide',
} = {}) => {
    const dlg = $(dialog), big = dlg.querySelector(img), cap = dlg.querySelector(caption);
    const bPrev = dlg.querySelector(prev), bNext = dlg.querySelector(next);
    const { on, off, signal } = _scope();
    const DRAG = { start: 10, commit: 1 / 5, flick: .4 };   // px before a drag is ours · share of the width · px/ms
    let items = [], index = 0, opener = null, turn = 0;   // turn: the latest move — an older one gives way
    let ghost = null, peek = null, peekDir = 0, saved = null, drag = null, closing = null;
    big.draggable = false;                            // a mouse drag never lifts the photo out
    if (getComputedStyle(dlg).touchAction === 'auto') dlg.style.touchAction = 'pan-y pinch-zoom';   // horizontal drags are ours
    const src = (k) => items[(k + items.length) % items.length].getAttribute('href');
    const put = (a, i) => {
        big.src = src(i);
        big.alt = a.querySelector('img')?.alt ?? '';
        cap?.html(text(a, i, items.length));
    };

    // ── photos on the move: a film strip ──
    // while they move nothing overflows (no scrollbar) and they pass UNDER the buttons and the
    // caption (the dialog's own layer); stop() puts the styles back as they were
    const layer = () => {
        if (saved) return;
        saved = [dlg.style.overflow, big.getAttribute('style')];
        dlg.style.overflow = 'hidden'; big.style.position = 'relative'; big.style.zIndex = '-1';
    };
    const stop = () => {                              // whatever moves ends where it was going
        turn++;
        for (const x of big.getAnimations()) x.cancel();
        ghost?.remove(); peek?.remove(); ghost = peek = null; peekDir = 0;
        if (!saved) return;
        dlg.style.overflow = saved[0];
        if (saved[1] === null) big.removeAttribute('style'); else big.setAttribute('style', saved[1]);
        saved = null;
    };
    /** A still copy of the large photo, fixed at `r` (never in the page's overflow), `url` its image. */
    const still = (r, url) => {
        const c = big.cloneNode(false);
        c.removeAttribute('id'); c.alt = ''; c.setAttribute('aria-hidden', 'true');
        if (url) c.src = url;
        Object.assign(c.style, { position: 'fixed', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px', margin: '0',
            maxWidth: 'none', maxHeight: 'none', objectFit: 'contain', pointerEvents: 'none', zIndex: '-1', translate: 'none', opacity: '1', scale: 'none' });
        big.before(c);
        return c;
    };
    // dir: +1 next, -1 previous, 0 a jump (open, show(i)) — no animation then.
    // from: where a finger left the old photo (px) — the slide goes on from there
    const show = (k, dir = 0, from = 0) => {
        if (!items.length) return;                    // nothing opened yet
        index = (k + items.length) % items.length;
        const a = items[index], i = index;
        if (items.length > 1) for (const d of [1, -1]) new Image().src = src(index + d);   // preload the neighbours
        if (!transition || !dir || !dlg.open) { drag = null; stop(); put(a, i); return; }
        if (from) turn++; else stop();                // a drag's photos stay where the finger left them
        const t = turn, incoming = new Image();
        incoming.src = src(i);
        incoming.decode().catch(() => {}).then(async () => {   // the old photo stays until the new one is ready
            if (t !== turn) return;
            const slide = transition === 'slide' && !$.reduced, W = dlg.clientWidth;
            layer();
            ghost = still(big.getBoundingClientRect());       // the old photo, where it is now
            big.style.translate = slide ? `${from + dir * W}px` : '';   // the new one waits where it comes from
            big.style.opacity = slide ? '' : '0';
            put(a, i);
            await big.decode().catch(() => {});
            if (t !== turn) return;
            peek?.remove(); peek = null;              // the photo itself takes the place of the finger's preview
            const o = { duration: slide ? _motion('slow') * Math.max(.3, 1 - Math.abs(from) / W) : _motion(), easing: _ease('move') };
            const moves = slide
                ? [ghost.animate([{ translate: '0px' }, { translate: `${-dir * W - from}px` }], o), big.animate([{ translate: `${from + dir * W}px` }, { translate: '0px' }], o)]
                : [ghost.animate(_FADE_OUT, o), big.animate(_FADE_IN, o)];
            big.style.translate = ''; big.style.opacity = '';
            await _settled(moves);
            if (t === turn) stop();
        });
    };

    // ── a finger (or pen) drags the photo: it follows, the neighbour shows beside it; let go past
    //    a fifth of the width (or with a flick) and the slide goes on, else it settles back ──
    const release = (cancel) => {
        const d = drag; drag = null;
        if (!d?.on) return;
        _swallowClick(dlg, signal);                   // a drag never also "clicks" (closes)
        const W = dlg.clientWidth, dir = d.dx < 0 ? 1 : -1;
        const go = !cancel && (Math.abs(d.dx) > W * DRAG.commit || (Math.abs(d.v) > DRAG.flick && Math.sign(d.v) === Math.sign(d.dx)));
        if (!transition) { if (go) show(index + dir, dir); return; }
        if (go) return show(index + dir, dir, d.dx);
        const t = ++turn, o = { duration: _motion(), easing: _ease() };
        const back = [big.animate([{ translate: `${d.dx}px` }, { translate: '0px' }], o)];
        if (peek) back.push(peek.animate([{ translate: peek.style.translate }, { translate: `${dir * W}px` }], o));
        big.style.translate = '';
        _settled(back).then(() => { if (t === turn) stop(); });
    };
    dlg.on('pointerdown', (e) => {
        if (e.pointerType === 'mouse' || !e.isPrimary || items.length < 2 || e.target.closest('button, a')) return;
        drag = { x0: e.clientX, y0: e.clientY, x: e.clientX, t: e.timeStamp, dx: 0, v: 0, on: false };
    }, on);
    dlg.on('pointermove', (e) => {
        if (!drag || !e.isPrimary) return;
        const dx = e.clientX - drag.x0, dy = e.clientY - drag.y0;
        if (!drag.on) {
            if (Math.abs(dy) > DRAG.start && Math.abs(dy) > Math.abs(dx)) { drag = null; return; }   // a scroll, a pinch: not ours
            if (Math.abs(dx) < DRAG.start) return;
            drag.on = true; stop();
            if (transition) { layer(); drag.r = big.getBoundingClientRect(); }
        }
        drag.v = (e.clientX - drag.x) / Math.max(1, e.timeStamp - drag.t); drag.x = e.clientX; drag.t = e.timeStamp; drag.dx = dx;
        if (!transition) return;
        const dir = dx < 0 ? 1 : -1;
        if (peekDir !== dir) { peek?.remove(); peek = still(drag.r, src(index + dir)); peekDir = dir; }
        big.style.translate = `${dx}px`;
        peek.style.translate = `${dx + dir * dlg.clientWidth}px`;
    }, on);
    dlg.on('pointerup', () => release(false), on);
    dlg.on('pointercancel', () => release(true), on);

    // ── open with a fade (the photo from 97 %), close with a quicker one ──
    const fade = (kf, o) => {                         // the dialog and its ::backdrop (where the engine can animate it)
        const a = [dlg.animate(kf, o)];
        try { a.push(dlg.animate(kf, { ...o, pseudoElement: '::backdrop' })); } catch { /* not animatable here: the dialog alone */ }
        return a;
    };
    const open = (a) => {
        if (closing) { for (const x of closing) x.cancel(); closing = null; }   // reopened while it faded out
        if (typeof group === 'function') items = group(a);
        else {
            const box = group ? a.closest(group) : null;
            items = (box ? box.find(links) : $.all(links)).filter((x) => !group || x.closest(group) === box);
        }
        opener = a;
        for (const b of [bPrev, bNext]) if (b) b.hidden = items.length < 2;
        show(Math.max(0, items.indexOf(a)));
        if (dlg.open) return;
        dlg.showModal();
        if (!transition) return;
        const o = { duration: _motion(), easing: _ease() };
        fade(_FADE_IN, o);
        if (!$.reduced) big.animate([{ scale: .97 }, { scale: 1 }], { ...o, duration: _motion('slow') });
    };
    const fadeOut = () => {                           // Escape too: its cancel waits for the fade
        if (!dlg.open || closing) return;
        if (!transition) return dlg.close();
        stop();
        closing = fade(_FADE_OUT, { duration: _motion('fast'), easing: _ease('move'), fill: 'forwards' });
        _settled(closing).then(() => finishClose());
    };
    const finishClose = () => {                       // the fade is over (or cut short): close for real
        if (!closing) return;
        const fades = closing; closing = null;
        dlg.close(); for (const x of fades) x.cancel();
    };

    document.on('click', (e) => {
        const a = e.target.closest?.(links);
        if (!a || _modified(e)) return;
        e.preventDefault();
        open(a);
    }, on);
    bPrev?.on('click', () => show(index - 1, -1), on);
    bNext?.on('click', () => show(index + 1, 1), on);
    dlg.querySelector(close)?.on('click', fadeOut, on);
    dlg.on('click', (e) => { if (e.target === dlg || e.target.tagName === 'FIGURE') fadeOut(); }, on);
    dlg.on('cancel', (e) => { if (transition && !closing) { e.preventDefault(); fadeOut(); } }, on);
    dlg.on('keydown', (e) => {
        if (e.key === 'ArrowRight') show(index + 1, 1);
        else if (e.key === 'ArrowLeft') show(index - 1, -1);
    }, on);
    dlg.on('close', () => { stop(); big.removeAttribute('src'); opener?.focus(); }, on);
    return { open, show: (i) => show(i), next: () => show(index + 1, 1), previous: () => show(index - 1, -1),
        close: fadeOut, destroy: () => { finishClose(); stop(); off(); } };
};

/* The page helpers below are one-call actions; each takes `signal` (an AbortSignal) to undo
   what it wired — the same teardown as everywhere else. */

/** Elements matching `sel` get `is-in` the first time they are seen (qry-ui.css hides
 *  `[data-reveal]:not(.is-in)` inside `.qry-js`). With reduced motion they are shown at once.
 *  Marks <html class="qry-js"> so the hidden state never applies without JavaScript.
 *  @example reveal()                       // [data-reveal] → .is-in; data-reveal-delay="150" staggers (ms)
 */
export const reveal = (sel = '[data-reveal]', { threshold = 0.15, signal } = {}) => {
    document.documentElement.cls('+qry-js');
    for (const el of $.all(sel)) {
        if (el.dataset.revealDelay) el.css('--qry-delay', el.dataset.revealDelay + 'ms');
        if ($.reduced) el.cls('+is-in');
        else el.onVisible(() => el.cls('+is-in'), { once: true, threshold, signal });
    }
};

/** The link (in `links`) whose #section is being read gets aria-current="true" and the
 *  class `is-current`. The section counts as read when it crosses the top third of the viewport;
 *  above the first section (the hero) or between sections, no link is current.
 *  @example scrollSpy('.menu a[href^="#"]')
 */
export const scrollSpy = (links, { rootMargin = '-30% 0px -65% 0px', signal } = {}) => {
    const map = new Map();
    for (const a of $.all(links)) {
        const id = _decode((a.getAttribute('href') || '').split('#').at(-1) || '');   // the LAST #: a hash route (#/p/#s) names its section after it
        const sec = id && document.getElementById(id);
        if (sec) map.set(sec, a);
    }
    const seen = new Set();                            // the sections in the band; the first one (page order) is current
    const mark = () => {
        const cur = [...map.keys()].find((sec) => seen.has(sec));
        for (const [sec, x] of map) x.cls('is-current', sec === cur).attr('aria-current', sec === cur ? 'true' : null);
    };
    for (const sec of map.keys()) sec.onVisible((v) => { if (v) seen.add(sec); else seen.delete(sec); mark(); }, { rootMargin, signal });
};

/** A « back to top » link: hidden (class `is-hidden`) until the reader has scrolled
 *  `after` px — or, when `after` is an element (or a selector), while that element is on
 *  screen (the footer: « shown once you reach the end »); a click goes up (smoothly unless
 *  reduced motion) and gives the focus to the top of the page, so keyboard users land where they see.
 *  Write the link with `class="… is-hidden"` in the markup: it never flashes before the script runs.
 *  @example toTop('.qry-to-top')
 *  @example toTop('.to-top', { after: 'footer' })
 */
export const toTop = (link, { after = 600, signal } = {}) => {
    const a = _opt(link);
    if (!a) return;
    if (typeof after === 'number') {
        const update = () => a.cls('is-hidden', scrollY <= after);
        window.on('scroll', $.throttle(update, 100), { passive: true, signal });
        update();
    } else {
        a.cls('+is-hidden');
        _opt(after)?.onVisible((v) => a.cls('is-hidden', !v), { signal });
    }
    a.on('click', (e) => {
        e.preventDefault();
        scrollTo({ top: 0, behavior: $.reduced ? 'auto' : 'smooth' });
        const top = document.getElementById('top') || document.body;
        if (!top.hasAttribute('tabindex')) top.attr('tabindex', '-1');
        top.focus({ preventScroll: true });
    }, { signal });
};

/** Click-to-load embeds: a placeholder link `<a class="qry-embed" href="https://…/watch?v=…"
 *  data-embed="https://www.youtube-nocookie.com/embed/…" data-title="…">` becomes an <iframe>
 *  only when clicked — nothing is fetched from the third party before. Without JavaScript it
 *  stays a plain link to the video or the map. Only https: (or this site's own) URLs are
 *  embedded — never javascript: or data:.
 *  @example embeds()
 */
export const embeds = (sel = '[data-embed]', { allow = 'autoplay; fullscreen; picture-in-picture; encrypted-media', signal } = {}) => {
    document.on('click', (e) => {
        const box = e.target.closest?.(sel);
        if (!box || _modified(e)) return;
        let url = null;
        try { url = new URL(box.dataset.embed, location.href); } catch { /* not a URL: the link stays a link */ }
        if (!url || (url.protocol !== 'https:' && url.origin !== location.origin)) return;   // the link stays a link
        e.preventDefault();
        const f = $.create('iframe', { src: url.href, title: box.dataset.title || labels.embed, allow, loading: 'lazy', class: 'qry-embed-frame' });   // fullscreen comes with `allow`
        box.replaceWith(f);
        f.focus();
    }, { signal });
};

/** Self-hosted films with a clean poster: the native controls give way to ONE play button
 *  (`<button class="qry-film-play">`, inserted right after the video — its parent positions it);
 *  a click removes it, brings the controls back and plays. The film's name for the button comes
 *  from `data-film` (else `title`). Without JavaScript the video keeps its own controls.
 *  @example films()        // <div class="screen"><video controls preload="none" poster="p.webp" data-film="Medley" src="m.mp4"></video></div>
 */
export const films = (sel = 'video[data-film]', { signal } = {}) => {
    for (const video of $.all(sel)) {
        const name = video.dataset.film || video.title;
        const btn = $.create('button', { type: 'button', class: 'qry-film-play', 'aria-label': name ? `${labels.play} ${name}` : labels.play },
            $.create('span', { html: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4.5v15l12.5-7.5z"/></svg>' }));
        video.controls = false;
        video.after(btn);
        btn.on('click', () => {
            btn.remove();
            video.controls = true;
            video.play().catch(() => { /* blocked or no source: the controls are there */ });
            video.focus();
        }, { signal });
    }
};

/** Remove the elements whose `data-until="YYYY-MM-DD"` has passed (local time, from that
 *  day's morning): a holiday notice, an event banner, gone without a rebuild.
 *  @example expire()
 */
export const expire = (sel = '[data-until]') => {
    const today = new Date(); today.setHours(0, 0, 0, 0);
    for (const el of $.all(sel)) {
        const [y, m, d] = (el.dataset.until || '').split('-').map(Number);
        if (y && new Date(y, m - 1, d) <= today) el.remove();
    }
};

/** A site's menu on small screens: the button (`aria-controls` names the menu) opens and
 *  closes it — the state is the button's `aria-expanded`, your CSS shows the menu from it
 *  (`.top:has([aria-expanded="true"]) nav { display: block }`). Escape closes it and gives the
 *  focus back to the button; a click outside or on one of its links closes it too. Without
 *  JavaScript, write the menu visible (or as a plain list of links) — the button only adds.
 *  @returns {{ open: () => void, close: () => void, toggle: () => void, destroy: () => void }}
 *  @example makeNav('.nav-toggle')
 */
export const makeNav = (button, { menu } = {}) => {
    const btn = $(button), nav = $(menu ?? `#${btn.getAttribute('aria-controls')}`);
    const { on, off } = _scope();
    const isOpen = () => btn.getAttribute('aria-expanded') === 'true';
    const set = (v) => btn.attr('aria-expanded', v ? 'true' : 'false');
    set(false);
    btn.on('click', () => set(!isOpen()), on);
    document.on('keydown', (e) => { if (e.key === 'Escape' && isOpen()) { set(false); btn.focus(); } }, on);
    document.on('click', (e) => {
        if (!isOpen() || btn.contains(e.target)) return;
        if (!nav.contains(e.target) || e.target.closest('a')) set(false);
    }, on);
    return { open: () => set(true), close: () => set(false), toggle: () => set(!isOpen()), destroy: off };
};

/** Photos (or any slides) that take turns: the children of `box` (or `slides` in it) get the
 *  class `is-current` one after the other, every `interval` ms, cross-fading (`transition:
 *  'fade'`, 2 × --qry-speed-slow; false: your CSS does it). Your CSS keeps the steady state —
 *  the slides stacked, the others at `opacity: 0` (no CSS transition then: it would fight the
 *  fade) — so the first one shows without JavaScript. It waits while the box is off screen or
 *  the tab is hidden; under reduced motion it never moves by itself (go/next/previous still fade).
 *  @returns {{ go: (i: number) => void, next: () => void, previous: () => void, destroy: () => void }}
 *  @example makeSlideshow('[data-slideshow]', { slides: 'img', interval: 6000 })
 */
export const makeSlideshow = (box, { slides = ':scope > *', interval = 6000, transition = 'fade' } = {}) => {
    const el = $(box), items = el.find(slides), { on, off, signal } = _scope();
    let index = Math.max(0, items.findIndex((x) => x.cls('?is-current'))), seen = false;
    const go = (k, first = false) => {
        if (!items.length) return;
        const was = items[index];
        index = (k + items.length) % items.length;
        items.forEach((x, i) => x.cls('is-current', i === index));
        if (first || !transition || was === items[index]) return;
        const o = { duration: 2 * _motion('slow'), easing: _ease('move') };
        for (const x of items) for (const a of x.getAnimations()) a.cancel();
        was.animate(_FADE_OUT, o);                   // the CSS already hides it: the fade covers the change
        items[index].animate(_FADE_IN, o);
    };
    go(index, true);
    if (items.length > 1 && !$.reduced) {
        el.onVisible((v) => { seen = v; }, on);
        const timer = setInterval(() => { if (seen && !document.hidden) go(index + 1); }, interval);
        signal.addEventListener('abort', () => clearInterval(timer));
    }
    return { go: (i) => go(i), next: () => go(index + 1), previous: () => go(index - 1), destroy: off };
};

/** Read or write one parameter of the address (`?topic=press`) — a filter that survives a
 *  reload and a shared link. Writing replaces the history entry (no new Back step); null removes it.
 *  @example urlParam('q')                → 'kinect' | null
 *  @example urlParam('q', 'kinect')      // ?q=kinect      ·  urlParam('q', null) removes it
 */
export const urlParam = (name, ...value) => {
    const url = new URL(location.href);
    if (!value.length) return url.searchParams.get(name);
    if (value[0] == null || value[0] === '') url.searchParams.delete(name);
    else url.searchParams.set(name, String(value[0]));
    history.replaceState(history.state, '', url);
};

/** A hash router from a table: `{ '#/': home, '#/c/:course': ({ course }) => …, '#/l/:c/:l': … }`.
 *  A pattern matches the whole hash, segment by segment; `:name` captures one segment (decoded).
 *  '' and '#' count as '#/'. A handler gets `(params, { signal, hash })`: `signal` aborts as soon
 *  as a NEWER navigation starts — an async screen checks it after each await and gives up, so a
 *  slow load never paints over the screen the user went to next. What a handler throws (or
 *  rejects) goes to `onError(error, hash)` (default: reportError) — unless it was overtaken.
 *  No match → `notFound(hash, { signal })` (default: go('#/')). Back / forward work (hashchange);
 *  the current route runs at once. Focus and scroll are the app's: after a screen is drawn, move
 *  the focus to its heading (tabindex="-1") and scroll to the top — a screen reader then reads the
 *  new screen, and Tab starts from it.
 *  @param {Object<string, Function>} routes
 *  @param {{notFound?: Function, onError?: Function, signal?: AbortSignal}} [opts]
 *  @returns {{ go: (hash: string) => void, readonly current: {hash: string, route: string|null, params: Object}, destroy: () => void }}
 *  @example
 *  const router = makeRouter({
 *      '#/': () => home(),
 *      '#/c/:course': async ({ course }, { signal }) => { const c = await load(course); if (signal.aborted) return; show(c); },
 *  }, { onError: (e) => showError(e.message) });
 */
export const makeRouter = (routes, { notFound, onError, signal } = {}) => {
    const { on, off } = _scope();
    const table = Object.entries(routes).map(([route, fn]) => ({ route, fn, parts: route.replace(/^#/, '').split('/') }));
    let nav = null, current = { hash: '#/', route: null, params: {} };
    const norm = (h) => (!h || h === '#' ? '#/' : h);
    const match = (hash) => {
        const parts = hash.replace(/^#/, '').split('/');
        for (const r of table) {
            if (r.parts.length !== parts.length) continue;
            const params = {};
            if (r.parts.every((p, i) => (p.startsWith(':') ? ((params[p.slice(1)] = _decode(parts[i])), parts[i] !== '') : p === parts[i]))) return { ...r, params };
        }
        return null;
    };
    const run = async () => {
        nav?.abort();
        const mine = nav = new AbortController(), hash = norm(location.hash), hit = match(hash);
        current = { hash, route: hit?.route ?? null, params: hit?.params ?? {} };
        try {
            if (hit) await hit.fn(hit.params, { signal: mine.signal, hash });
            else if (notFound) await notFound(hash, { signal: mine.signal });
            else if (hash !== '#/') go('#/');
        } catch (err) {
            if (!mine.signal.aborted) (onError ?? reportError)(err, hash);
        }
    };
    const go = (hash) => { if (norm(location.hash) === norm(hash)) run(); else location.hash = hash; };   // the same hash: run it again
    const destroy = () => { off(); nav?.abort(); };
    if (signal?.aborted) return { go, get current() { return current; }, destroy };
    signal?.addEventListener('abort', destroy, { once: true });
    window.on('hashchange', run, on);
    run();
    return { go, get current() { return current; }, destroy };
};

// ════════════════════════════════════════════════════════════════════════════
// FORMS
// ════════════════════════════════════════════════════════════════════════════

let _errors = 0;
/** The browser's own validation (required, type, pattern, min/max…), shown properly:
 *  the message under the field (.qry-field-error, linked by aria-describedby — one per radio
 *  group, in its fieldset), the field marked aria-invalid, the first error focused, re-checked
 *  as the user types. A field's `data-error` replaces the browser's (localised) message. On a
 *  valid submit, onSubmit gets the values; the form is busy (aria-busy; the submit buttons that
 *  were enabled are disabled) until it resolves; what it throws is shown under the form
 *  (.qry-form-error). destroy() removes everything makeForm added.
 *  @returns {{ data:()=>Object, validate:()=>boolean, reset:()=>void, destroy:()=>void }}
 *  @example makeForm('#join', { onSubmit: async (data) => { await $.load.json('/join', { method: 'POST', body: data }); toast('Welcome!', 'ok'); } })
 */
export const makeForm = (form, { onSubmit } = {}) => {
    const f = $(form);
    const { on, off } = _scope();
    const made = [], novalidate = f.hasAttribute('novalidate');
    f.attr('novalidate', true);
    let tried = false;
    const group = (el) => (el.type === 'radio' && el.name ? f.find(`input[type="radio"][name="${CSS.escape(el.name)}"]`) : [el]);
    const errorOf = (el) => {
        const lead = group(el)[0];
        const id = lead.getAttribute('aria-describedby')?.split(' ').find((x) => x.startsWith('qry-err-'));
        if (id) return document.getElementById(id);
        const e = $.create('span', { class: 'qry-field-error', id: `qry-err-${_errors += 1}`, 'aria-live': 'polite' });
        const host = lead.type === 'radio' ? lead.closest('fieldset, [role="radiogroup"], .qry-field') ?? lead.parentElement : lead.closest('.qry-field') ?? lead.parentElement;
        made.push(e.mount(host));
        for (const x of group(el)) x.attr('aria-describedby', [x.getAttribute('aria-describedby'), e.id].filter(Boolean).join(' '));
        return e;
    };
    const check = (el) => {
        const ok = el.checkValidity();
        for (const x of group(el)) x.attr('aria-invalid', ok ? null : 'true');
        errorOf(el).text(ok ? '' : el.dataset.error || el.validationMessage);
        return ok;
    };
    const fields = () => {                                   // one entry per radio group
        const seen = new Set();
        return [...f.elements].filter((el) => {
            if (!el.name || !el.willValidate) return false;
            if (el.type !== 'radio') return true;
            if (seen.has(el.name)) return false;
            seen.add(el.name);
            return true;
        });
    };
    let formError = f.querySelector('.qry-form-error');
    if (!formError) made.push(formError = $.create('p', { class: 'qry-form-error', role: 'alert', hidden: true }).mount(f));
    const onInput = (e) => { if (tried && e.target.name && e.target.willValidate) check(e.target); };
    f.on('input change', onInput, on);
    f.on('submit', async (e) => {
        e.preventDefault();
        tried = true;
        formError.hidden = true;
        const bad = fields().filter((el) => !check(el));
        if (bad.length) { bad[0].focus(); return; }
        const buttons = f.find('button:not([type="button"]), [type="submit"]').filter((b) => !b.disabled);   // the app's own disabled stay so
        const busy = (b) => { f.attr('aria-busy', b && 'true'); for (const x of buttons) x.enable(!b).attr('aria-busy', b && 'true'); };
        busy(true);
        try { await onSubmit?.(f.serialize(), f); }
        catch (err) { formError.text(err?.message || String(err)).hidden = false; }
        finally { busy(false); }
    }, on);
    const clear = () => [...f.elements].forEach((el) => el.attr('aria-invalid', null));
    return {
        data: () => f.serialize(),
        validate: () => fields().map(check).every(Boolean),
        reset() { f.reset(); tried = false; clear(); fields().forEach((el) => errorOf(el).text('')); formError.hidden = true; },
        destroy() {
            off();
            clear();
            for (const el of f.elements) {
                const ids = (el.getAttribute('aria-describedby') ?? '').split(' ').filter((x) => x && !x.startsWith('qry-err-'));
                el.attr('aria-describedby', ids.length ? ids.join(' ') : null);
            }
            made.forEach((x) => x.remove());
            f.attr('novalidate', novalidate);
        },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// DIALOGS — native <dialog> and a toast stack, styled by qry-ui.css
// ════════════════════════════════════════════════════════════════════════════

let _dialogs = 0;
/** @private — a click on a modal dialog's backdrop: the target is the dialog itself AND the
 *  point is outside its box (its own padding is the dialog too). */
const _backdrop = (dlg, e) => {
    const r = dlg.getBoundingClientRect();
    return e.target === dlg && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom);
};

/** Any modal question → a Promise of the answer. A <dialog class="qry-dialog"> is built,
 *  shown, and removed once closed; the promise settles exactly once: with the clicked button's
 *  `value`, or with `dismiss` on Escape or a click outside the box.
 *  - `body`: a Node (fields, a radio group…), a $.html result, or text (escaped, in a <p>).
 *  - a button: `{ label, value, tone, focus }` — `tone` is a class ('primary', 'danger', any
 *    tone; none = a plain button); a `value` that is a FUNCTION is called on click and its
 *    result is the answer — read the fields of the body there.
 *  - focus: a body element with `autofocus`, else the button marked `focus`, else the last one;
 *    it goes back where it was once closed.
 *  - named by its title (else its body), described by its body; `alert: true` makes it an
 *    alertdialog (a message that needs an answer); `class` adds classes to the <dialog> (e.g.
 *    'center': centred, equal-width actions).
 *  alert, confirm and prompt are this, pre-set (and take `class` too).
 *  @param {{title?: string, body?: Node|string|Object, buttons: Array<{label: string, value?: any, tone?: string, focus?: boolean}>, dismiss?: any, alert?: boolean, class?: string}} o
 *  @returns {Promise<any>}
 *  @example
 *  const body = $.create('div', { html: $.html`<label><input type="radio" name="why" value="typo" checked> A typo</label>
 *      <label><input type="radio" name="why" value="wrong"> A wrong answer</label><textarea class="qry-input" name="note"></textarea>` });
 *  const report = await ask({ title: 'Report this question', body, dismiss: null, buttons: [
 *      { label: labels.cancel, value: null },
 *      { label: 'Send', tone: 'primary', value: () => ({ why: body.querySelector(':checked').value, note: body.querySelector('textarea').value }) }] });
 */
export const ask = ({ title, body, buttons = [], dismiss, alert = false, class: cls = '' } = {}) => new Promise((resolve) => {
    const id = `qry-dlg-${_dialogs += 1}`, back = document.activeElement;
    const dlg = $.create('dialog', { class: `qry-dialog ${cls}`.trim(), role: alert ? 'alertdialog' : null, 'aria-describedby': `${id}-body`,
        'aria-labelledby': title ? `${id}-title` : `${id}-body` });
    dlg.html($.html`${title && $.html`<h2 class="qry-dialog-title" id="${id}-title">${title}</h2>`}<div class="qry-dialog-body" id="${id}-body"></div>
        <div class="qry-dialog-actions">${buttons.map((b, i) => $.html`<button type="button" class="qry-btn ${b.tone}" data-act="${i}">${b.label}</button>`)}</div>`);
    const box = dlg.querySelector('.qry-dialog-body');
    if (body instanceof Node) box.add(body);
    else if (typeof body === 'string' || typeof body === 'number') box.add($.create('p', { text: body }));
    else if (body != null) box.html(body);                              // a $.html result
    let value = dismiss;
    const done = (v) => { value = v; dlg.close(); };
    dlg.on('click', (e) => {
        if (_backdrop(dlg, e)) return done(dismiss);
        const act = e.target.closest('[data-act]')?.dataset.act;
        if (act == null) return;
        const b = buttons[+act];
        done(typeof b.value === 'function' ? b.value() : b.value);
    });
    dlg.on('cancel', (e) => { e.preventDefault(); done(dismiss); });     // Escape
    dlg.on('close', () => { dlg.remove(); if (back?.isConnected) back.focus(); resolve(value); });
    dlg.mount(document.body).showModal();
    const acts = dlg.find('[data-act]');
    (box.querySelector('[autofocus]') ?? acts[buttons.findIndex((b) => b.focus)] ?? acts.at(-1))?.focus();
});

/** A page's own modal <dialog> (rules, settings, a form): `open()` shows it, and it closes on
 *  Escape (native), on a click on its backdrop, and on a click on a `close` element inside it;
 *  the focus goes back where it was. `onClose(returnValue)` follows every close.
 *  @param {string|HTMLDialogElement} dialog
 *  @param {{close?: string, onClose?: (value: string) => void}} [opts]  close: a selector (default '[data-close]')
 *  @returns {{ open: () => void, close: (value?: string) => void, readonly isOpen: boolean, destroy: () => void }}
 *  @example const rules = makeDialog('#rules'); $('#help').on('click', rules.open)
 */
export const makeDialog = (dialog, { close = '[data-close]', onClose } = {}) => {
    const dlg = $(dialog), { on, off } = _scope();
    let back = null;
    dlg.on('click', (e) => { if (_backdrop(dlg, e) || e.target.closest(close)) dlg.close(); }, on);
    dlg.on('close', () => { back?.focus?.(); back = null; onClose?.(dlg.returnValue); }, on);
    return {
        open() { if (dlg.open) return; back = document.activeElement; dlg.showModal(); },
        close: (value) => dlg.close(value),
        get isOpen() { return dlg.open; },
        destroy: off,
    };
};

/** A message that goes away by itself (or by its × button), stacked bottom-right. The stack
 *  is a polite live region; a 'danger' toast is an alert. The type is a tone ('info' = the
 *  accent, 'ok', 'warn', 'danger', 'purple').
 *  @param {string} msg  plain text
 *  @param {'info'|'ok'|'warn'|'danger'|'purple'} [tone='info']
 *  @param {{duration?: number}} [opts]  duration in ms (default 3000); 0 = until dismissed
 *  @returns {Element} the toast
 *  @example toast('Saved', 'ok')   toast('Upload failed', 'danger', { duration: 0 })
 */
export const toast = (msg, tone = 'info', { duration = 3000 } = {}) => {
    const stack = $.opt('.qry-toasts') ?? $.create('div', { class: 'qry-toasts', role: 'status', 'aria-live': 'polite' }).mount(document.body);
    const text = $.create('span', { class: 'qry-toast-text' });
    const t = $.create('div', { class: `qry-toast ${tone}`, role: tone === 'danger' ? 'alert' : null }, text,
        $.create('button', { type: 'button', class: 'qry-toast-close', 'aria-label': labels.dismiss, text: '×' }));
    const remove = () => { t.cls('+is-leaving'); setTimeout(() => t.remove(), $.reduced ? 0 : 200); };
    t.on('click', remove);
    stack.add(t);
    requestAnimationFrame(() => text.text(msg));                         // filled once in the live region: announced
    if (duration) setTimeout(remove, duration);
    return t;
};

/** A message to acknowledge (an alertdialog).  @example await alert('Export finished.') */
export const alert = (message, { title = '', ok = labels.ok, class: cls } = {}) =>
    ask({ title, body: message, alert: true, class: cls, buttons: [{ label: ok, tone: 'primary' }] });

/** Yes or no → true / false (Escape and the backdrop say no). `danger` paints OK red and
 *  puts the focus on Cancel, so a stray Enter never deletes.
 *  @example if (await confirm('Delete this take?', { ok: 'Delete', danger: true })) remove()
 */
export const confirm = (message, { title = '', ok = labels.ok, cancel = labels.cancel, danger = false, class: cls } = {}) =>
    ask({ title, body: message, dismiss: false, alert: true, class: cls,
        buttons: [{ label: cancel, value: false, focus: danger }, { label: ok, tone: danger ? 'danger' : 'primary', value: true }] });

/** A line of text → the string, or null when cancelled. Enter validates. The answer is trimmed
 *  (a stray space is never part of a name or an answer); `trim: false` keeps it exactly as typed.
 *  @example const name = await prompt('Session name?', { value: 'take-1' })
 */
export const prompt = (message, { title = '', value = '', placeholder = '', ok = labels.ok, cancel = labels.cancel, trim = true, class: cls } = {}) => {
    const input = $.create('input', { class: 'qry-input', type: 'text', autocomplete: 'off', autofocus: true, value, placeholder });
    const body = $.create('label', { class: 'qry-field' }, $.create('span', { text: message }), input);
    const p = ask({ title, body, dismiss: null, class: cls, buttons: [{ label: cancel, value: null }, { label: ok, tone: 'primary', value: () => (trim ? input.value.trim() : input.value) }] });
    input.select();
    input.on('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); input.closest('dialog').querySelector('.qry-dialog-actions > :last-child').click(); } });
    return p;
};

// ════════════════════════════════════════════════════════════════════════════
// THEME — light / dark / system, boot
// ════════════════════════════════════════════════════════════════════════════

/** @private — the OS colour scheme, asked lazily (importing the module touches nothing). */
let _osQuery = null;
const _os = () => (_osQuery ??= matchMedia('(prefers-color-scheme: dark)'));
let _themeKey = 'qry:theme', _themeGround = '--qry-bg', _themeWatch = null;
/** @private — every <meta name="theme-color"> (media-scoped ones too: the page's ground is the
 *  same whatever the OS says) takes the page ground. Never throws: a DOM without a canvas or
 *  computed styles (a test sandbox) just keeps its metas. */
const _bar = () => {
    try {
        const metas = document.querySelectorAll('meta[name="theme-color"]');
        if (!metas.length) return;
        const c = $.color(_themeGround, document.body ?? document.documentElement);
        if (c) for (const m of metas) m.content = c;
    } catch { /* no colour to read here: the metas stay as they are */ }
};

/** Light / dark / system, through ONE hook: `data-theme` on <html> (qry-ui.css turns it
 *  into the native color-scheme; without it, the OS decides). The choice is persisted (as JSON,
 *  under 'qry:theme' or the `key` given to init); 'system' removes the attribute and follows the
 *  OS live. Every change — set(), or the OS while on 'system' — puts EVERY
 *  `<meta name="theme-color">` (media-scoped ones included) on the page ground (`ground`, default
 *  --qry-bg) — never throwing — and fires `qry:theme` on <html> with { mode, dark }.
 *  No flash before the module runs: in <head>, after the stylesheet and the theme-color meta
 *  (same key, same ground token) — the README has it.
 *  @example theme.init(); theme.toggle(); theme.set('system'); theme.isDark()
 *  @example theme.init({ key: 'floop:theme', ground: '--floop-paper' })   // the app's own key and ground
 */
export const theme = {
    /** @param {'light'|'dark'|'system'} mode */
    set(mode) {
        const html = document.documentElement;
        html.attr('data-theme', mode === 'system' ? null : mode);
        $.store().set(_themeKey, mode);
        _bar();
        html.trigger('qry:theme', { mode, dark: this.isDark() });
        return mode;
    },
    get() { return $.store().get(_themeKey, 'system'); },
    isDark() {
        const t = document.documentElement.dataset.theme;
        return t ? t === 'dark' : _os().matches;
    },
    toggle() { return this.set(this.isDark() ? 'light' : 'dark'); },
    /** Apply the saved choice (default: follow the OS), and follow the OS while on 'system'.
     *  @param {{key?: string, ground?: string}} [opts]  key: the localStorage key of the choice
     *  (default 'qry:theme'); ground: the token (or CSS colour) of the page ground the browser bar
     *  takes (default '--qry-bg' — an app not on qry-ui.css names its own) */
    init({ key = 'qry:theme', ground = '--qry-bg' } = {}) {
        _themeKey = key;
        _themeGround = ground;
        if (!_themeWatch) {
            _themeWatch = () => { if (this.get() === 'system') this.set('system'); };
            _os().addEventListener('change', _themeWatch);
        }
        return this.set(this.get());
    },
};

/** One-call convenience for the common opening sequence:
 *  set the title → apply the saved theme → run `ready` on DOM-ready.
 *  Every step is also available standalone — use this only if it saves typing.
 *  @param {Object}   [opts]
 *  @param {Function} [opts.ready]        Run once the DOM is ready
 *  @param {boolean|{key?: string}} [opts.theme=true]   Apply persisted/system theme (an object: theme.init's options)
 *  @param {string}   [opts.title]        Sets document.title if given
 *  @example boot({ title: 'Gallery', ready: () => wire() })
 */
export const boot = ({ ready, theme: useTheme = true, title } = {}) => {
    if (title) document.title = title;
    if (useTheme) theme.init(useTheme === true ? {} : useTheme);
    if (ready) $.ready(ready);
};

// ════════════════════════════════════════════════════════════════════════════
// DATA — formats, clipboard, files
// ════════════════════════════════════════════════════════════════════════════

/** @private — zero-pad a number to `w` digits. */
const _pad = (n, w = 2) => String(n).padStart(w, '0');

/** Display formatters: value → string, and local calendar days. Pure (plural reads <html lang>).
 *  @example format.duration(90000) // → '1m 30s'
 *  @example format.bytes(1536)     // → '1.5 kB' ('1,5 ko' on a page in French)
 */
export const format = {
    /** ms → `1h 02m`, `5m 03s`, `820ms`. @param {number} ms */
    duration(ms) {
        ms = Math.round(ms);
        if (ms < 1000) return `${ms}ms`;
        const s = Math.floor(ms / 1000) % 60, m = Math.floor(ms / 60000) % 60, h = Math.floor(ms / 3600000);
        return h ? `${h}h ${_pad(m)}m` : m ? `${m}m ${_pad(s)}s` : `${s}s`;
    },
    /** bytes → `1.5 kB`, `3.2 MB` — in the page's language (<html lang>): `1,5 ko` in French,
     *  `1,5 kB` in German (Intl units, binary steps of 1024).
     *  @param {number} n  @param {{locale?: string}} [opts] */
    bytes(n, { locale = document.documentElement.lang || undefined } = {}) {
        const units = ['byte', 'kilobyte', 'megabyte', 'gigabyte', 'terabyte'];
        const i = n > 0 ? $.clamp(Math.floor(Math.log(n) / Math.log(1024)), 0, units.length - 1) : 0;
        return new Intl.NumberFormat(locale, { style: 'unit', unit: units[i], unitDisplay: 'short', maximumFractionDigits: i ? 1 : 0 })
            .format(n > 0 ? n / 1024 ** i : 0);
    },
    /** Date|number|string → `YYYY-MM-DD HH:MM` (local time). */
    date(d) {
        const x = new Date(d);
        return `${x.getFullYear()}-${_pad(x.getMonth() + 1)}-${_pad(x.getDate())} ${_pad(x.getHours())}:${_pad(x.getMinutes())}`;
    },
    /** A compact local `YYYYMMDDhhmmss`, for file names (default: now).
     *  @example download(rows, `export_${format.stamp()}.json`) */
    stamp(d = new Date()) {
        const x = new Date(d);
        return `${x.getFullYear()}${_pad(x.getMonth() + 1)}${_pad(x.getDate())}${_pad(x.getHours())}${_pad(x.getMinutes())}${_pad(x.getSeconds())}`;
    },
    /** seconds → `mm:ss.mmm` media-clock timecode. @example format.clock(90.5) → '01:30.500' */
    clock(s) {
        if (s == null || isNaN(s)) return '00:00.000';
        return `${_pad(Math.floor(s / 60))}:${_pad(Math.floor(s % 60))}.${_pad(Math.floor((s % 1) * 1000), 3)}`;
    },
    /** number → fixed-precision string (`''` for null / NaN). @param {number} v @param {number} [p=2] */
    number(v, p = 2) { return v == null || isNaN(v) ? '' : Number(v).toFixed(p); },
    /** Truncate with a trailing ellipsis if longer than `max`. */
    truncate(s, max) { return !s || s.length <= max ? s : s.slice(0, Math.max(0, max - 1)) + '…'; },
    /** A LOCAL calendar day `YYYY-MM-DD`, `offset` days from `from` (default today). Calendar
     *  arithmetic, never ±24 h: around a change of clock (a 23 h or 25 h day) "yesterday" stays
     *  yesterday. (toISOString() would give the UTC day.)
     *  @example format.day() → '2026-10-03'   format.day(-1) → '2026-10-02' */
    day(offset = 0, from = new Date()) {
        const x = new Date(from);
        const d = new Date(x.getFullYear(), x.getMonth(), x.getDate() + offset);
        return `${d.getFullYear()}-${_pad(d.getMonth() + 1)}-${_pad(d.getDate())}`;
    },
    /** Whole calendar days from `a` to `b` (default today): a `YYYY-MM-DD` (read as a LOCAL day) or
     *  a Date. Exact across clock changes.  @example format.daysBetween(lastSeen) → 3 */
    daysBetween(a, b = new Date()) {
        const utc = (v) => {
            if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v)) { const [y, m, d] = v.split('-').map(Number); return Date.UTC(y, m - 1, d); }
            const x = new Date(v); return Date.UTC(x.getFullYear(), x.getMonth(), x.getDate());
        };
        return Math.round((utc(b) - utc(a)) / 864e5);
    },
    /** A count and its word, by the language's plural rules (Intl.PluralRules): `forms` is
     *  `[one, other]` or `{ zero?, one, two?, few?, many?, other }`. The locale defaults to the
     *  page's <html lang>. French counts 0 and 1 as one: '0 jour', '1 jour', '2 jours'.
     *  @example format.plural(3, ['jour', 'jours']) → '3 jours'
     *  @example format.plural(2, { one: 'plik', few: 'pliki', many: 'plików', other: 'pliku' }, { locale: 'pl' }) → '2 pliki' */
    plural(n, forms, { locale = document.documentElement.lang || undefined } = {}) {
        const cat = new Intl.PluralRules(locale).select(n);
        const word = Array.isArray(forms) ? forms[cat === 'one' ? 0 : forms.length - 1] : forms[cat] ?? forms.other;
        return `${n} ${word}`;
    },
};

/** Copy text to the clipboard. Shows a toast unless `notify` is false — `message` (default
 *  labels.copied) says what was copied.
 *  @returns {Promise<boolean>} whether the copy succeeded
 *  @example await copy(report.url, { message: 'Link copied' })
 */
export const copy = async (text, { notify = true, message } = {}) => {
    try {
        await navigator.clipboard.writeText(text);
        if (notify) toast(message ?? labels.copied, 'ok', { duration: 1500 });
        return true;
    } catch {
        if (notify) toast(labels.copyFailed, 'danger');
        return false;
    }
};

/** Save data as a file. A Blob goes as is; a string or bytes get `type`; anything else (an
 *  object, an array) is saved as pretty JSON.
 *  @param {Blob|string|BufferSource|Object} data
 *  @param {string} filename
 *  @param {{type?: string}} [opts]  MIME type for a string or bytes (default text/plain)
 *  @example download(rows, 'data.json')
 *  @example download(pdfBytes, 'letter.pdf', { type: 'application/pdf' })
 */
export const download = (data, filename, { type = 'text/plain' } = {}) => {
    const raw = data instanceof Blob || typeof data === 'string' || ArrayBuffer.isView(data) || data instanceof ArrayBuffer;
    const blob = data instanceof Blob ? data
        : raw ? new Blob([data], { type })
        : new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = $.create('a', { href: url, download: filename }).mount(document.body);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);   // an immediate revoke can abort the download
};

/** @private — does a file pass an `accept` list, as in <input accept>: '.json,.zip',
 *  'image/*', 'text/csv'… ('' or '*' takes everything). */
const _accepts = (file, accept) => !accept || accept === '*' || accept.split(',').some((a) => {
    const t = a.trim().toLowerCase(), name = file.name.toLowerCase(), mime = (file.type || '').toLowerCase();
    return t.startsWith('.') ? name.endsWith(t) : t.endsWith('/*') ? mime.startsWith(t.slice(0, -1)) : mime === t;
});

/** @private — text → data by extension: JSON parsed, CSV / TSV through $.parseCSV. */
const _parsers = {
    json: (t) => JSON.parse(t),
    csv: (t) => $.parseCSV(t),
    tsv: (t) => $.parseCSV(t, { sep: '\t' }),
};

/** @private — a zip archive (bytes) → read(name) → its bytes, or null. The central directory +
 *  the browser's own inflate (DecompressionStream 'deflate-raw'): stored and deflated entries,
 *  no zip64, no encryption — what Office files and ordinary archives are. */
const _unzip = (u8) => {
    const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength), utf8 = new TextDecoder();
    let end = -1;
    for (let i = u8.length - 22; i >= Math.max(0, u8.length - 65557); i--) if (dv.getUint32(i, true) === 0x06054b50) { end = i; break; }
    if (end < 0) throw new Error('not a zip archive');
    const entries = new Map();
    for (let k = 0, p = dv.getUint32(end + 16, true), n = dv.getUint16(end + 10, true); k < n; k++) {
        if (dv.getUint32(p, true) !== 0x02014b50) throw new Error('damaged zip directory');
        const len = dv.getUint16(p + 28, true);
        entries.set(utf8.decode(u8.subarray(p + 46, p + 46 + len)),
            { method: dv.getUint16(p + 10, true), size: dv.getUint32(p + 20, true), offset: dv.getUint32(p + 42, true) });
        p += 46 + len + dv.getUint16(p + 30, true) + dv.getUint16(p + 32, true);
    }
    const read = async (name) => {
        const e = entries.get(name);
        if (!e) return null;
        if (e.size === 0xFFFFFFFF) throw new Error('zip64 archives are not supported');
        const start = e.offset + 30 + dv.getUint16(e.offset + 26, true) + dv.getUint16(e.offset + 28, true);
        const raw = u8.subarray(start, start + e.size);
        if (e.method === 0) return raw;
        if (e.method !== 8) throw new Error(`unsupported zip compression (${e.method})`);
        return new Uint8Array(await new Response(new Blob([raw]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).arrayBuffer());
    };
    return { names: [...entries.keys()], read };
};

/** @private — an .xlsx (bytes) → its FIRST worksheet as TSV text, one line per sheet row (empty
 *  rows kept, so line n is row n), cells quoted when they hold a tab, a newline or a quote.
 *  Values as Excel stores them: numbers and dates as numbers (a date is a serial day). */
const _xlsx = async (u8) => {
    const { read } = _unzip(u8), utf8 = new TextDecoder();
    const doc = async (name) => { const b = await read(name); return b && new DOMParser().parseFromString(utf8.decode(b), 'application/xml'); };
    const tags = (node, name) => [...node.getElementsByTagNameNS('*', name)];
    const book = await doc('xl/workbook.xml');
    const first = book && tags(book, 'sheet')[0];
    if (!first) throw new Error('not an Excel workbook');
    const rid = first.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships', 'id');
    const rels = await doc('xl/_rels/workbook.xml.rels');
    const target = rels && tags(rels, 'Relationship').find((r) => r.getAttribute('Id') === rid)?.getAttribute('Target');
    const sheet = await doc(!target ? 'xl/worksheets/sheet1.xml' : target.startsWith('/') ? target.slice(1) : `xl/${target}`);
    if (!sheet) throw new Error('not an Excel workbook');
    const sst = await doc('xl/sharedStrings.xml');
    const shared = sst ? tags(sst, 'si').map((si) => tags(si, 't').filter((t) => !t.closest('rPh')).map((t) => t.textContent).join('')) : [];
    const col = (ref) => [...ref.replace(/\d+/g, '')].reduce((n, ch) => n * 26 + ch.charCodeAt(0) - 64, 0) - 1;
    const cell = (t) => (/[\t\n\r"]/.test(t) ? `"${t.replace(/"/g, '""')}"` : t);
    const lines = [];
    for (const row of tags(sheet, 'row')) {
        const cells = [];
        for (const c of tags(row, 'c')) {
            const type = c.getAttribute('t'), v = tags(c, 'v')[0]?.textContent ?? '';
            cells[col(c.getAttribute('r') || '')] = type === 's' ? shared[+v] ?? ''
                : type === 'inlineStr' ? tags(c, 't').map((t) => t.textContent).join('')
                : type === 'b' ? (v === '1' ? 'TRUE' : 'FALSE') : v;
        }
        lines[(+row.getAttribute('r') || lines.length + 1) - 1] = Array.from(cells, (t) => cell(t ?? '')).join('\t');
    }
    return Array.from(lines, (l) => l ?? '').join('\n');
};

/** Read dropped / picked files: `.json`, `.csv` and `.tsv` are parsed, other text kept as
 *  text. An `.xlsx` is read natively (no library): its first sheet becomes TSV `text`, one line
 *  per row, and `data` as for a CSV (objects keyed by the first row). A `.zip` is opened natively
 *  too, and its entries read through `accept`. A file outside `accept` (labels.refused) and a file
 *  that fails are reported through `onError` — the batch goes on. (Inside a zip, entries outside
 *  `accept` are skipped silently: an archive carries what it carries.) Nothing is ever fetched.
 *  @param {FileList|File[]} files
 *  @param {Object}   [opts]
 *  @param {string}   [opts.accept='.json,.csv,.tsv,.txt,.xlsx']  which files (and zip entries) to read
 *  @param {boolean}  [opts.parse=true]   false → `{ name, text }` only
 *  @param {Function} [opts.onError]      (name, error) → void; default console.warn
 *  @returns {Promise<Array<{name: string, text: string, data?: any}>>}
 *  @example makeDropZone(document.body, { onFiles: async (f) => (await readFiles(f)).forEach(({ name, data }) => load(name, data)) })
 *  @example readFiles(files, { accept: '.xlsx,.csv', onError: (name, e) => toast(`${name}: ${e.message}`, 'danger') })
 */
export const readFiles = async (files, { accept = '.json,.csv,.tsv,.txt,.xlsx', parse = true, onError } = {}) => {
    const warn = onError || ((name, err) => console.warn(`readFiles: ${name} — ${err.message}`));
    const utf8 = new TextDecoder(), out = [];
    const ext = (name) => name.split('.').pop().toLowerCase();
    const take = async (name, get) => {
        try {
            const bytes = await get(), xlsx = ext(name) === 'xlsx';
            const raw = xlsx ? await _xlsx(bytes) : utf8.decode(bytes);
            const text = raw.charCodeAt(0) === 0xFEFF ? raw.slice(1) : raw;   // no BOM
            const p = parse && _parsers[xlsx ? 'tsv' : ext(name)];
            out.push(p ? { name, text, data: p(text) } : { name, text });
        } catch (err) { warn(name, err); }
    };
    for (const file of files) {
        try {
            const bytes = () => file.arrayBuffer().then((b) => new Uint8Array(b));
            if (ext(file.name) === 'zip') {
                const zip = _unzip(await bytes());
                for (const name of zip.names) if (!name.endsWith('/') && _accepts({ name }, accept)) await take(name, () => zip.read(name));
            } else if (_accepts(file, accept)) {
                await take(file.name, bytes);
            } else {
                warn(file.name, new Error(labels.refused));
            }
        } catch (err) {
            warn(file.name, err);
        }
    }
    return out;
};

// ════════════════════════════════════════════════════════════════════════════
// LAYOUT — tabs, menus, splitter, sidebar, header, zoom-pan
// ════════════════════════════════════════════════════════════════════════════

/** Wire a tab bar: clicking a tab activates it and reveals the matching panel(s) — the
 *  ARIA tablist pattern (roles, aria-selected, roving tabindex, ←/→/Home/End). The state IS
 *  the ARIA: a tab is selected by aria-selected="true", a panel is shown by not being
 *  `hidden`. A panel may serve several tabs: list their names space-separated in its
 *  attribute. The tab marked aria-selected="true" in the markup is the start (else the first);
 *  give the other panels `hidden` in the markup too, so nothing flashes before the script.
 *  Re-selecting the current tab is a no-op (no `onChange`) unless `select(name, true)`.
 *  @param {Object} [opts]
 *  @param {string|Element[]} [opts.tabs='.qry-tab']
 *  @param {string|Element[]} [opts.panels='.qry-panel']
 *  @param {string} [opts.attr='data-tab']  value links a tab to its panel(s)
 *  @param {Function} [opts.onChange]  (name:string) → void  (only on an actual change)
 *  @returns {{ select:(name:string, force?:boolean)=>void, readonly current:string, tabs:Element[], destroy:Function }}
 *  @example const tabs = makeTabs({ onChange: n => { if (n === 'page') render(); } })
 */
export const makeTabs = ({ tabs: tabsOpt = '.qry-tab', panels: panelsOpt = '.qry-panel', attr = 'data-tab', onChange } = {}) => {
    const tabs = _list(tabsOpt), panels = _list(panelsOpt);
    const { on, off } = _scope();
    let cur = null;
    const select = (name, force = false) => {
        if (name == null || (name === cur && !force)) return;
        cur = name;
        for (const t of tabs) {
            const sel = t.attr(attr) === name;
            t.attr({ 'aria-selected': String(sel), tabindex: sel ? '0' : '-1' });
        }
        for (const p of panels) p.hidden = !(p.attr(attr) || '').split(' ').includes(name);
        onChange?.(name);
    };
    tabs[0]?.parentElement.attr('role', 'tablist');
    panels.forEach((p) => p.attr('role', 'tabpanel'));
    for (const t of tabs) {
        t.attr('role', 'tab').on('click', () => select(t.attr(attr)), on);
        t.on('keydown', (e) => {
            const i = tabs.indexOf(t), n = tabs.length;
            const j = { ArrowRight: i + 1, ArrowDown: i + 1, ArrowLeft: i - 1 + n, ArrowUp: i - 1 + n, Home: 0, End: n - 1 }[e.key];
            if (j === undefined) return;
            e.preventDefault();
            tabs[j % n].focus();
            select(tabs[j % n].attr(attr));
        }, on);
    }
    const start = tabs.find((t) => t.attr('aria-selected') === 'true') || tabs[0];
    if (start) select(start.attr(attr), true);
    return { select, get current() { return cur; }, tabs, destroy: off };
};

/** Make a drag handle resize a pane by writing a px length into a CSS variable on its
 *  container. Pairs with the qry-ui.css `.qry-split` handle and the `.qry-workspace` shell
 *  (its start aside reads `--qry-aside-w`, its end aside `--qry-aside-end-w`), but works on any
 *  container / variable. `from: 'end'` sizes a pane measured from the container's right (or
 *  bottom) edge — the end aside, an inspector — and turns the keys round to match.
 *  Live updates flow through `onResize` (every move); commit-once work belongs in `onEnd`.
 *  The handle is a focusable separator: ←/→ (↑/↓ on the y axis) move it by 16 px.
 *  Double-click resets to `initial` (or clears the variable).
 *  @param {string|Element} handle  the splitter element
 *  @param {Object}  [opts]
 *  @param {string|Element} [opts.container]  where the var is written (default: nearest `.qry-workspace`, else the handle's parent)
 *  @param {string}  [opts.prop]  CSS custom property to drive (default `--qry-aside-w`, or `--qry-aside-end-w` from the end)
 *  @param {'x'|'y'} [opts.axis='x']  x → a width, y → a height
 *  @param {'start'|'end'} [opts.from='start']  measured from the container's left / top, or from its right / bottom
 *  @param {number}  [opts.min=150]
 *  @param {number|((boxSize:number)=>number)} [opts.max]  default 60% of the container
 *  @param {number}  [opts.initial]  size (px) to apply now, and to reset to
 *  @param {Function}[opts.onStart] @param {Function}[opts.onResize] @param {Function}[opts.onEnd]  (px:number) → void
 *  @returns {{ set:(px:number)=>number, current:()=>number|null, reset:()=>void, destroy:Function }}
 *  @example makeSplitter('#split', { min: 150, max: 560, onEnd: () => scheduleRender() })
 *  @example makeSplitter('#split-end', { from: 'end', min: 200 })   // the end aside (an inspector)
 */
export const makeSplitter = (handle, {
    container, from = 'start', prop = from === 'end' ? '--qry-aside-end-w' : '--qry-aside-w', axis = 'x', min = 150, max, initial,
    onStart, onResize, onEnd,
} = {}) => {
    const el = $(handle), y = axis === 'y', sign = from === 'end' ? -1 : 1;
    const box = container ? $(container) : (el.closest('.qry-workspace') || el.parentElement);
    const { on, off } = _scope();
    const span = () => box.getBoundingClientRect()[y ? 'height' : 'width'];
    const hi = () => (max == null ? span() * 0.6 : typeof max === 'function' ? max(span()) : max);
    const current = () => { const v = parseFloat(box.css(prop)); return Number.isFinite(v) ? v : null; };
    const aria = (v) => el.attr({ 'aria-valuenow': v == null ? null : Math.round(v), 'aria-valuemax': Math.round(hi()) });
    const write = (px) => {
        const v = $.clamp(px, min, hi());
        box.css(prop, v + 'px');
        aria(v);
        onResize?.(v);
        return v;
    };
    const at = (e) => {                                  // the pointer's distance from the measured edge
        const r = box.getBoundingClientRect(), p = y ? e.clientY : e.clientX;
        return sign > 0 ? p - r[y ? 'top' : 'left'] : r[y ? 'bottom' : 'right'] - p;
    };
    el.attr({ role: 'separator', tabindex: '0', 'aria-orientation': y ? 'horizontal' : 'vertical', 'aria-valuemin': min });
    el.onDrag(({ phase, event }) => {
        if (phase === 'start') { el.cls('+is-dragging'); onStart?.(current() ?? at(event)); }
        else if (phase === 'move') write(at(event));
        else { el.cls('-is-dragging'); onEnd?.(current()); }
    }, on);
    el.on('keydown', (e) => {
        const d = { ArrowLeft: -16, ArrowRight: 16, ArrowUp: -16, ArrowDown: 16 }[e.key];
        if (!d || (y !== (e.key === 'ArrowUp' || e.key === 'ArrowDown'))) return;
        e.preventDefault();
        const v = write((current() ?? span() / 2) + d * sign);   // write first: onEnd?.(write(…)) skips the write without onEnd
        onEnd?.(v);
    }, on);
    const reset = () => {
        if (initial != null) write(initial); else { box.css(prop, null); aria(current()); onResize?.(current()); }
        onEnd?.(current());
    };
    el.on('dblclick', reset, on);
    if (initial != null) write(initial); else aria(current());
    return { set: (px) => { const v = write(px); onEnd?.(v); return v; }, current, reset, destroy: off };
};

/** Wire the qry-ui.css sidebar: slide-in on small screens (≤ 768 px, as in qry-ui.css),
 *  collapse to icons on large ones. Markup: `<aside class="qry-sidebar mobile">` (the
 *  `mobile` class enables the slide-in), a backdrop `<div id="qry-overlay" class="qry-overlay">`,
 *  and a toggle button. The button's aria-expanded follows (also across the breakpoint);
 *  Escape and the overlay close the mobile menu.
 *  @param {string|Element} sidebar
 *  @param {Object} [opts] @param {string} [opts.button='#qry-collapse'] @param {string} [opts.overlay='#qry-overlay']
 *  @returns {{ open, close, toggle, destroy }}
 *  @example const nav = makeSidebar('.qry-sidebar'); nav.toggle()
 */
export const makeSidebar = (sidebar, { button = '#qry-collapse', overlay = '#qry-overlay' } = {}) => {
    const el = $(sidebar), ov = _opt(overlay), btn = _opt(button), small = matchMedia('(max-width: 768px)');
    const { on, off } = _scope();
    const expanded = () => btn?.attr('aria-expanded', String(small.matches ? el.cls('?is-open') : !el.cls('?is-collapsed')));
    const open = () => { el.cls('+is-open'); ov?.cls('+is-visible'); expanded(); };
    const close = () => { el.cls('-is-open'); ov?.cls('-is-visible'); expanded(); };
    const toggle = () => { if (!small.matches) { el.cls('~is-collapsed'); expanded(); } else if (el.cls('?is-open')) close(); else open(); };
    ov?.on('click', close, on);
    btn?.on('click', toggle, on);
    document.on('keydown', (e) => { if (e.key === 'Escape' && el.cls('?is-open')) close(); }, on);
    small.addEventListener('change', close, on);                      // crossing the breakpoint: a clean state
    expanded();
    return { open, close, toggle, destroy: off };
};

/** Hide a sticky `.qry-header` on scroll-down, reveal on scroll-up (class `is-hidden`) — more room
 *  to read on a phone. Never hidden near the top, nor while the focus is inside it (keyboard).
 *  Follows `.qry-content` when its CSS makes it a scroller (overflow auto / scroll), else the
 *  window — decided by the CSS, not by how much content there is yet.
 *  @example makeAutoHideHeader({ header: '#top' })
 *  @param {Object} [opts] @param {string} [opts.header='.qry-header'] @param {string} [opts.scroll='.qry-content'] @param {number} [opts.topGuard=8] @param {number} [opts.hideDelta=12] @param {number} [opts.showDelta=6]
 *  @returns {{ destroy: Function }}
 */
export const makeAutoHideHeader = ({ header = '.qry-header', scroll = '.qry-content', topGuard = 8, hideDelta = 12, showDelta = 6 } = {}) => {
    const h = _opt(header);
    const { on, off } = _scope();
    if (!h) return { destroy: off };
    const s = _opt(scroll);
    const own = s && /(auto|scroll|overlay)/.test(s.css('overflowY'));
    const getY = own ? () => s.scrollTop : () => scrollY;
    let lastY = getY(), raf = 0;
    const update = () => {
        raf = 0;
        const y = getY(), dy = y - lastY, hidden = h.cls('?is-hidden');
        if (y <= topGuard || h.matches(':focus-within')) h.cls('-is-hidden');
        else if (!hidden && dy > hideDelta) h.cls('+is-hidden');
        else if (hidden && dy < -showDelta) h.cls('-is-hidden');
        lastY = y;
    };
    (own ? s : window).on('scroll', () => { raf ||= requestAnimationFrame(update); }, { ...on, passive: true });
    h.on('focusin', () => h.cls('-is-hidden'), on);
    return { destroy() { off(); cancelAnimationFrame(raf); h.cls('-is-hidden'); } };
};

/** Pan + zoom a large content element inside a scrollable stage (`.qry-stage`).
 *  Zoom sets the content's CSS width in % (100% = fit at zoom 1), so the stage's own
 *  scrollbars track the overflow; the content can be a div, an <img> or a <canvas>.
 *  Ctrl/⌘ + wheel zooms toward the cursor (plain wheel with `wheelModifier: false`), a drag
 *  pans, two fingers pinch, a double-click toggles zoom; drags starting on an interactive
 *  child (`noPan`) are left alone. The content is resolved live (it may be swapped).
 *  @param {string|Element} stage  the scroll container
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.content]  zoom target (default: the stage's first element child)
 *  @param {number} [opts.min=0.25] @param {number} [opts.max=8]
 *  @param {number} [opts.step=1.25]  button / dblclick factor
 *  @param {number} [opts.wheelStep=1.1]  wheel factor per notch
 *  @param {boolean}[opts.wheelModifier=true]  require Ctrl/⌘ for wheel-zoom
 *  @param {boolean}[opts.dblClick=true]
 *  @param {string} [opts.noPan]  selector of children that do NOT start a pan
 *  @param {Function} [opts.onZoom]  (zoom:number) → void
 *  @param {Function} [opts.onPan]   (scrollLeft:number, scrollTop:number) → void
 *  @returns {{ zoom:(z:number)=>void, zoomIn, zoomOut, zoomAt:(factor, clientX?, clientY?)=>void, fit, readonly current:number, destroy }}
 *  (A pinch that ends with one finger still down continues as a pan from there.)
 *  @example const view = makeZoomPan('#stage', { content: '#canvas', onZoom: () => scheduleRender() })
 */
export const makeZoomPan = (stage, {
    content, min = 0.25, max = 8, step = 1.25, wheelStep = 1.1, wheelModifier = true, dblClick = true,
    noPan = 'a,button,input,select,textarea,label,[contenteditable],[data-no-pan]',
    onZoom, onPan,
} = {}) => {
    const s = $(stage);
    const { on, off } = _scope();
    const target = () => (content ? $(content) : s.firstElementChild);
    let z = 1;
    const apply = () => target()?.css('width', z * 100 + '%');
    const set = (nz) => { const prev = z; z = $.clamp(nz, min, max); apply(); onZoom?.(z); return z / prev; };
    const zoomAt = (factor, cx, cy) => {               // keep the viewport point (cx, cy) fixed
        const r = s.getBoundingClientRect();
        if (cx == null) { cx = r.left + s.clientWidth / 2; cy = r.top + s.clientHeight / 2; }
        const x = s.scrollLeft + (cx - r.left), y = s.scrollTop + (cy - r.top);
        const ratio = set(z * factor);
        s.scrollLeft = x * ratio - (cx - r.left);
        s.scrollTop = y * ratio - (cy - r.top);
    };
    s.on('wheel', (e) => {
        if (wheelModifier && !(e.ctrlKey || e.metaKey)) return;
        e.preventDefault();
        zoomAt(e.deltaY < 0 ? wheelStep : 1 / wheelStep, e.clientX, e.clientY);
    }, { ...on, passive: false });
    // one pointer registry drives both the one-pointer pan and the two-pointer pinch
    const pts = new Map();
    let sx = 0, sy = 0, sl = 0, st = 0, pinch = 0;
    const panFrom = (e) => { sx = e.clientX; sy = e.clientY; sl = s.scrollLeft; st = s.scrollTop; };
    const pair = () => [...pts.values()];
    const dist = () => { const [a, b] = pair(); return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY); };
    s.on('pointerdown', (e) => {
        if ((e.pointerType === 'mouse' && e.button !== 0) || e.target.closest?.(noPan)) return;
        pts.set(e.pointerId, e);
        s.setPointerCapture(e.pointerId);
        if (pts.size === 2) { pinch = dist(); s.cls('-is-grabbing'); }
        else { panFrom(e); s.cls('+is-grabbing'); }
    }, on);
    s.on('pointermove', (e) => {
        if (!pts.has(e.pointerId)) return;
        pts.set(e.pointerId, e);
        if (pts.size >= 2) {
            const d = dist(), [a, b] = pair();
            if (pinch) zoomAt(d / pinch, (a.clientX + b.clientX) / 2, (a.clientY + b.clientY) / 2);
            pinch = d;
        } else {
            s.scrollLeft = sl - (e.clientX - sx); s.scrollTop = st - (e.clientY - sy);
            onPan?.(s.scrollLeft, s.scrollTop);
        }
    }, on);
    s.on('pointerup pointercancel', (e) => {
        pts.delete(e.pointerId);
        if (pts.size < 2) pinch = 0;
        if (pts.size === 1) panFrom(pair()[0]);                    // a pinch ending in one finger: pan from here
        if (!pts.size) s.cls('-is-grabbing');
    }, on);
    s.on('dblclick', (e) => { if (dblClick && !e.target.closest?.(noPan)) zoomAt(z > 1 ? 1 / z : step * step, e.clientX, e.clientY); }, on);
    apply();
    return {
        zoom: (v) => { set(v); },
        zoomIn: () => zoomAt(step),
        zoomOut: () => zoomAt(1 / step),
        zoomAt,
        fit: () => { set(1); s.scrollLeft = s.scrollTop = 0; },
        get current() { return z; },
        destroy: off,
    };
};

let _anchors = 0;
/** A menu on the native popover: `<button popovertarget="m">⋯</button>` +
 *  `<div id="m" popover class="qry-menu"><button data-value="copy">Copy</button><hr>…</div>`.
 *  The browser already opens, closes, light-dismisses and handles Escape; qry-ui.css places it
 *  under its button with CSS anchor positioning (flipping at the viewport edges). makeMenu adds
 *  what a menu needs on top: an explicit anchor, the ARIA menu pattern (roles, aria-haspopup,
 *  aria-expanded), focus on the first item, ↑/↓/Home/End between items, Tab closes, a click
 *  on an item calls onSelect and closes. Items can be added later: they are read on open.
 *  Without makeMenu, `.qry-menu` / `.qry-popover` still open and anchor (implicit anchor).
 *  @param {string|Element} menu  the popover element (it needs an id)
 *  @param {Object} [opts]
 *  @param {string|Element} [opts.button]  its trigger (default: the `[popovertarget=<id>]` button)
 *  @param {(value:string, item:Element)=>void} [opts.onSelect]  value = data-value, else the text
 *  @returns {{ open:()=>void, close:()=>void, toggle:()=>void, readonly isOpen:boolean, destroy:()=>void }}
 *  @example makeMenu('#more', { onSelect: (v) => v === 'export' && exportCsv() })
 */
export const makeMenu = (menu, { button, onSelect } = {}) => {
    const m = $(menu);
    const b = _opt(button ?? `[popovertarget="${CSS.escape(m.id)}"]`);
    const { on, off } = _scope();
    const anchor = `--qry-anchor-${_anchors += 1}`;
    if (!m.hasAttribute('popover')) m.attr('popover', '');
    m.attr('role', 'menu').css('position-anchor', anchor);
    b?.attr({ popovertarget: m.id, 'aria-haspopup': 'menu', 'aria-expanded': 'false' }).css('anchor-name', anchor);
    const items = () => m.find(':scope > :is(button, a, [role="menuitem"])').filter((i) => !i.disabled && !i.hidden);
    const isOpen = () => m.matches(':popover-open');
    m.on('beforetoggle', (e) => {                         // synchronous: ARIA is right before anyone looks
        const open = e.newState === 'open';
        b?.attr('aria-expanded', String(open));
        if (open) items().forEach((i) => i.attr({ role: 'menuitem', tabindex: '-1' }));
        else if (m.contains(document.activeElement)) b?.focus();
    }, on);
    m.on('toggle', (e) => { if (e.newState === 'open') items()[0]?.focus(); }, on);   // focusable once shown
    m.on('keydown', (e) => {
        if (e.key === 'Tab') { m.hidePopover(); return; }
        const list = items(), i = list.indexOf(document.activeElement), n = list.length;
        const j = { ArrowDown: i + 1, ArrowUp: i - 1 + n, Home: 0, End: n - 1 }[e.key];
        if (j === undefined || !n) return;
        e.preventDefault();
        list[j % n].focus();
    }, on);
    m.on('click', (e) => {
        const item = e.target.closest?.('button, a, [role="menuitem"]');
        if (!item || !m.contains(item) || item.disabled) return;
        onSelect?.(item.dataset.value ?? item.textContent.trim(), item);
        m.hidePopover();
    }, on);
    return {
        open: () => { if (!isOpen()) m.showPopover(); },
        close: () => { if (isOpen()) m.hidePopover(); },
        toggle: () => m.togglePopover(),
        get isOpen() { return isOpen(); },
        destroy: off,
    };
};

// ════════════════════════════════════════════════════════════════════════════
// CANVAS — sharp at any pixel density, in the theme's colours, redrawn when needed
// ════════════════════════════════════════════════════════════════════════════

/** A canvas that stays sharp and in the theme: its bitmap follows its CSS box ×
 *  devicePixelRatio, and `draw(ctx, { width, height, dpr, color })` runs again on resize, on a
 *  theme change (qry:theme, the OS) and on redraw() — once per frame at most. The context is
 *  scaled: draw in CSS px. `color(token)` resolves a --qry-* token where the canvas is (a canvas
 *  cannot read CSS variables) — cached until the theme changes, so a 60 fps loop may call it freely. Size the canvas with CSS (e.g. width: 100%; height: 240px, or
 *  width: 100% alone to keep its aspect).
 *  @param {string|HTMLCanvasElement} canvas
 *  @param {{draw?: Function}} [opts]
 *  @returns {{ canvas: HTMLCanvasElement, ctx: CanvasRenderingContext2D, redraw: () => void, destroy: () => void }}
 *  @example makeCanvas('#chart', { draw(ctx, { width, height, color }) { ctx.fillStyle = color('--qry-accent'); ctx.fillRect(0, 0, width / 2, height); } })
 */
export const makeCanvas = (canvas, { draw } = {}) => {
    const c = $(canvas), ctx = c.getContext('2d');
    const { on, off } = _scope();
    let size = { width: 0, height: 0 }, raf = 0;
    const colors = new Map();
    const color = (token) => colors.get(token) ?? colors.set(token, $.color(token, c)).get(token);
    const paint = () => {
        raf = 0;
        if (!size.width || !size.height) return;
        const dpr = devicePixelRatio || 1, w = Math.round(size.width * dpr), h = Math.round(size.height * dpr);
        if (c.width !== w || c.height !== h) {
            const box = c.getBoundingClientRect();
            c.width = w; c.height = h;
            const r = c.getBoundingClientRect();                       // its box moved: sized by its attributes, not by CSS — pin it
            if (Math.abs(r.width - box.width) > 0.5 || Math.abs(r.height - box.height) > 0.5) c.css({ width: box.width + 'px', height: box.height + 'px' });
        }
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.clearRect(0, 0, size.width, size.height);
        draw?.(ctx, { ...size, dpr, color });
    };
    const redraw = () => { raf ||= requestAnimationFrame(paint); };
    const retheme = () => { colors.clear(); redraw(); };
    c.onResize((s) => { size = s; redraw(); }, on);
    document.documentElement.on('qry:theme', retheme, on);
    _os().addEventListener('change', retheme, on);
    return { canvas: c, ctx, redraw, destroy() { off(); cancelAnimationFrame(raf); } };
};

/** Freehand ink on a canvas — a signature, a note on a page. Strokes are points in CSS px,
 *  captured from mouse, finger or pen (coalesced events: smooth lines), drawn sharp and in the
 *  theme (makeCanvas). The strokes FOLLOW the canvas: when its box is resized (a window, a phone
 *  turned) every point scales with the width, so a signature keeps its place and shape (a hidden,
 *  0 px canvas keeps its last size). `width()` is the box width the strokes are expressed in;
 *  `toPath(scale)` gives an SVG path ("M x y L …" per stroke) — e.g. for pdf-lib's drawSvgPath
 *  with scale = boxInPoints / width(); `strokes` is the raw data.
 *  @param {string|HTMLCanvasElement} canvas
 *  @param {{width?: number, color?: string, onChange?: (strokes: number[][][]) => void}} [opts]  color: a --qry-* token or a CSS colour (default --qry-text)
 *  @returns {{ strokes: number[][][], undo: () => void, clear: () => void, isEmpty: () => boolean, width: () => number, toPath: (scale?: number) => string, destroy: () => void }}
 *  @example const sign = makeInk('#signature'); … if (sign.isEmpty()) toast('Please sign', 'warn')
 */
export const makeInk = (canvas, { width = 2.2, color = '--qry-text', onChange } = {}) => {
    const strokes = [];
    const view = makeCanvas(canvas, { draw(ctx, { color: resolve }) {
        Object.assign(ctx, { lineWidth: width, lineCap: 'round', lineJoin: 'round', strokeStyle: resolve(color) });
        for (const s of strokes) {
            ctx.beginPath();
            s.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
            if (s.length === 1) ctx.lineTo(s[0][0] + 0.01, s[0][1]);   // a tap is a dot
            ctx.stroke();
        }
    } });
    const { on, off } = _scope();
    const changed = () => { view.redraw(); onChange?.(strokes); };
    let boxW = 0;
    view.canvas.onResize(({ width: w }) => {
        if (!w) return;                                                 // hidden: keep the last size
        if (boxW && Math.abs(w - boxW) > 0.5) { const k = w / boxW; for (const s of strokes) for (const p of s) { p[0] *= k; p[1] *= k; } }
        boxW = w;
    }, on);
    view.canvas.onDrag(({ x, y, phase, event }) => {
        const c = view.canvas, bx = c.clientLeft, by = c.clientTop;      // the bitmap starts inside the border
        if (phase === 'start') strokes.push([[x - bx, y - by]]);
        else if (phase === 'move') {
            const r = c.getBoundingClientRect();
            for (const p of event.getCoalescedEvents?.() ?? [event]) strokes.at(-1).push([p.clientX - r.left - bx, p.clientY - r.top - by]);
            view.redraw();
        } else changed();
    }, on);
    const pt = ([x, y], k) => `${+(x * k).toFixed(2)} ${+(y * k).toFixed(2)}`;
    return {
        strokes,
        undo() { strokes.pop(); changed(); },
        clear() { strokes.length = 0; changed(); },
        isEmpty: () => !strokes.length,
        width: () => boxW,
        toPath: (scale = 1) => strokes.map((s) => `M${pt(s[0], scale)} L${(s.length > 1 ? s.slice(1) : [[s[0][0] + 0.01, s[0][1]]]).map((p) => pt(p, scale)).join(' L')}`).join(' '),
        destroy() { off(); view.destroy(); },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// CHARTS — plain SVG, drawn for its box, in the theme's tokens
// ════════════════════════════════════════════════════════════════════════════

/** @private — a value accessor: a function (d, i) → v, or a property name. */
const _acc = (f) => (typeof f === 'function' ? f : (d) => d?.[f]);

/** @private — nice ticks (1, 2, 5 × 10ⁿ) covering [lo, hi], about `n` of them (whole numbers only
 *  with `integer`: counts, trial numbers). */
const _ticks = (lo, hi, n = 5, integer = false) => {
    if (!(hi > lo)) { const w = Math.abs(lo) || 1; lo -= w / 2; hi += w / 2; }
    const raw = (hi - lo) / Math.max(1, n), mag = 10 ** Math.floor(Math.log10(raw)), f = raw / mag;
    let step = (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * mag;
    if (integer) step = Math.max(1, Math.round(step));
    const a = Math.floor(lo / step + 1e-9) * step, b = Math.ceil(hi / step - 1e-9) * step;
    const out = [];
    for (let v = a; v <= b + step / 2; v += step) out.push(+v.toFixed(10));
    return { lo: a, hi: b, ticks: out, step };
};

/** A chart in plain SVG — dots, lines, bars and reference rules on shared axes — drawn for its box
 *  (size the element with CSS: a height) and in the theme: marks are painted with the tokens
 *  (`--qry-accent`, or a tone), so light / dark and a scoped `.qry-theme` follow with no redraw.
 *  `marks` is an array, or a FUNCTION returning one: it runs in an effect — the chart redraws when
 *  a signal it reads changes. It also redraws when its box is resized.
 *  A mark: `{ type: 'dot' | 'line' | 'bar' | 'rule', data, x, y, tone, tip }` —
 *    x / y: an accessor `(d, i) => value` or a property name; tone: a tone class (`ok` `warn` `danger`
 *    `purple` `accent` `highlight`, or `muted` for background data) or `(d) => tone`; tip: `(d) =>
 *    [title, content]` — $.tip on hover. dot: `r` (px, default 3). line: points in data order (a
 *    null y breaks it). bar: from 0 to y — on a BAND axis when its x values are not numbers (one
 *    slot per category), else from x to `x2` (a histogram's bins). rule: a line across at `y` (or
 *    down at `x`), with an optional `label`.
 *  Axes: `x` / `y` = { label, min, max, ticks (about how many, default 5), format: (v) => text, zero,
 *  integer (whole-number ticks only: counts, trial numbers) } — a domain from the data unless given,
 *  extended to nice ticks; bars always include 0. A band's bars are at most 72 px wide.
 *  No data: the chart says `labels.empty`. The SVG is role="img", named by `label`.
 *  @param {string|Element} el  the chart's box
 *  @param {{marks: Array|Function, x?: Object, y?: Object, label?: string}} opts
 *  @returns {{ readonly svg: SVGSVGElement|null, redraw: () => void, destroy: () => void }}
 *  @example makeChart('#rt', { label: 'RT per trial', y: { label: 'ms', zero: true },
 *    marks: () => [{ type: 'dot', data: trials.value, x: 'n', y: 'rt', tone: (t) => (t.ok ? 'accent' : 'danger') }] })
 */
export const makeChart = (el, { marks = [], x: xo = {}, y: yo = {}, label = '' } = {}) => {
    const box = $(el), { on, off } = _scope();
    let size = { width: 0, height: 0 }, svg = null, list = [];
    const draw = () => {
        const { width: W, height: H } = size;
        if (!W || !H) return;
        const all = list.filter(Boolean).map((m) => ({ ...m, xs: _acc(m.x), ys: _acc(m.y), data: m.data ?? [] }));
        const bars = all.filter((m) => m.type === 'bar');
        const band = bars.some((m) => !m.x2 && m.data.some((d, i) => typeof m.xs(d, i) !== 'number'));
        const cats = band ? [...new Set(bars.flatMap((m) => m.data.map((d, i) => String(m.xs(d, i)))))] : [];
        const xv = [], yv = [];
        for (const m of all) {
            if (m.type === 'rule') { if (m.y != null) yv.push(m.y); if (m.x != null && !band) xv.push(m.x); continue; }
            m.data.forEach((d, i) => {
                const yy = m.ys(d, i), xx = m.xs(d, i);
                if (Number.isFinite(yy)) yv.push(yy);
                if (!band && Number.isFinite(xx)) xv.push(xx);
                if (m.x2 && Number.isFinite(_acc(m.x2)(d, i))) xv.push(_acc(m.x2)(d, i));
            });
            if (m.type === 'bar') yv.push(0);
        }
        if (yo.zero) yv.push(0);
        const svgEl = $.svg('svg', { class: 'qry-chart', width: W, height: H, viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': label || null });
        if (!yv.length || (!band && !xv.length)) {
            svgEl.add($.svg('text', { class: 'qry-chart-empty', x: W / 2, y: H / 2, 'text-anchor': 'middle', 'dominant-baseline': 'middle', text: labels.empty }));
            return svgEl;
        }
        const fx = xo.format ?? ((v) => String(v)), fy = yo.format ?? ((v) => String(v));
        const ty = _ticks(yo.min ?? Math.min(...yv), yo.max ?? Math.max(...yv), yo.ticks ?? 5, yo.integer);
        const ylo = yo.min ?? ty.lo, yhi = yo.max ?? ty.hi, yt = ty.ticks.filter((v) => v >= ylo - 1e-9 && v <= yhi + 1e-9);
        const tx = band ? null : _ticks(xo.min ?? Math.min(...xv), xo.max ?? Math.max(...xv), xo.ticks ?? 5, xo.integer);
        const xlo = band ? 0 : xo.min ?? tx.lo, xhi = band ? 0 : xo.max ?? tx.hi;
        const chars = Math.max(...yt.map((v) => fy(v).length));
        const L = 10 + chars * 6.6 + (yo.label ? 16 : 0), R = 10, T = 10, B = 22 + (xo.label ? 16 : 0);
        const sy = (v) => $.scale(v, ylo, yhi, H - B, T);
        const slot = band ? (W - L - R) / Math.max(1, cats.length) : 0;
        const sx = band ? (c) => L + (cats.indexOf(String(c)) + 0.5) * slot : (v) => $.scale(v, xlo, xhi, L, W - R);
        const g = (cls) => $.svg('g', { class: cls }).mount(svgEl);
        const grid = g('qry-chart-grid'), axes = g('qry-chart-axes');
        for (const v of yt) {
            grid.add($.svg('line', { x1: L, x2: W - R, y1: sy(v), y2: sy(v) }));
            axes.add($.svg('text', { class: 'qry-chart-tick', x: L - 6, y: sy(v), 'text-anchor': 'end', 'dominant-baseline': 'middle', text: fy(v) }));
        }
        const xt = band ? cats : tx.ticks.filter((v) => v >= xlo - 1e-9 && v <= xhi + 1e-9);
        const every = Math.max(1, Math.ceil(xt.length / Math.max(1, (W - L - R) / Math.max(36, 7 * Math.max(...xt.map((v) => fx(v).length)) + 10))));
        xt.forEach((v, i) => { if (i % every === 0) axes.add($.svg('text', { class: 'qry-chart-tick', x: sx(v), y: H - B + 14, 'text-anchor': 'middle', text: fx(v) })); });
        axes.add($.svg('line', { class: 'qry-chart-axis', x1: L, x2: W - R, y1: H - B, y2: H - B }));
        if (yo.label) axes.add($.svg('text', { class: 'qry-chart-label', transform: `translate(12 ${(T + H - B) / 2}) rotate(-90)`, 'text-anchor': 'middle', text: yo.label }));
        if (xo.label) axes.add($.svg('text', { class: 'qry-chart-label', x: (L + W - R) / 2, y: H - 4, 'text-anchor': 'middle', text: xo.label }));
        const layer = g('qry-chart-marks');
        const tone = (m, d) => (typeof m.tone === 'function' ? m.tone(d) : m.tone) || '';
        const tip = (node, m, d) => { if (m.tip) { const [a, b] = [].concat(m.tip(d)); $.tip(node, a, b); } return node; };
        for (const m of all) {
            const cls = (d) => `qry-chart-${m.type} ${tone(m, d)}`.trim();
            if (m.type === 'rule') {
                const r = m.y != null ? { x1: L, x2: W - R, y1: sy(m.y), y2: sy(m.y) } : { x1: sx(m.x), x2: sx(m.x), y1: T, y2: H - B };
                layer.add($.svg('line', { class: cls(), ...r }));
                if (m.label) layer.add($.svg('text', { class: `qry-chart-rule-label ${tone(m)}`.trim(), x: m.y != null ? W - R - 4 : r.x1 + 4, y: m.y != null ? r.y1 - 4 : T + 10, 'text-anchor': m.y != null ? 'end' : 'start', text: m.label }));
            } else if (m.type === 'line') {
                let d = '', pen = false;
                m.data.forEach((p, i) => {
                    const yy = m.ys(p, i), xx = m.xs(p, i);
                    if (!Number.isFinite(yy)) { pen = false; return; }
                    d += `${pen ? 'L' : 'M'}${sx(xx).toFixed(1)} ${sy(yy).toFixed(1)}`; pen = true;
                });
                layer.add($.svg('path', { class: cls(m.data[0]), d }));
            } else {
                m.data.forEach((p, i) => {
                    const yy = m.ys(p, i), xx = m.xs(p, i);
                    if (!Number.isFinite(yy)) return;
                    if (m.type === 'dot') layer.add(tip($.svg('circle', { class: cls(p), cx: sx(xx), cy: sy(yy), r: m.r ?? 3 }), m, p));
                    else {
                        const y0 = sy(0), y1 = sy(yy);
                        const [x0, w] = band ? [sx(xx) - Math.min(slot * 0.4, 36), Math.min(slot * 0.8, 72)] : [sx(xx), Math.max(1, sx(_acc(m.x2)(p, i)) - sx(xx) - 1)];
                        layer.add(tip($.svg('rect', { class: cls(p), x: x0, y: Math.min(y0, y1), width: Math.max(1, w), height: Math.abs(y0 - y1) }), m, p));
                    }
                });
            }
        }
        return svgEl;
    };
    const paint = () => { const next = draw(); if (next) { if (svg?.isConnected) svg.replaceWith(next); else box.add(next); svg = next; } };
    box.onResize((s) => { size = s; paint(); }, on);
    const stop = typeof marks === 'function' ? $.effect(() => { list = marks() ?? []; paint(); }) : ((list = marks), null);
    return { get svg() { return svg; }, redraw: paint, destroy() { off(); stop?.(); svg?.remove(); svg = null; } };
};

// ════════════════════════════════════════════════════════════════════════════
// INPUT — keyboard shortcuts, drop zone
// ════════════════════════════════════════════════════════════════════════════

/** Global keyboard shortcuts. `add(key, fn, opts)` registers and returns a remover;
 *  `destroy()` removes everything. Matching is case-insensitive; a `ctrl` binding fires only
 *  with Ctrl/⌘ held, a plain one only without. A shortcut never steals a key from something
 *  that owns it: skipped when the event was already handled (defaultPrevented), while typing
 *  in a field (a text-like INPUT, TEXTAREA, SELECT, [contenteditable] — also inside a web
 *  component), while a modal dialog is open, and — for a plain key — when the focused control
 *  uses that very key (a button or link: Space / Enter; a checkbox or radio: Space and the
 *  arrows; a range, tab, menu item, option, slider, separator: the arrows, Home / End, Page Up /
 *  Down — and Space / Enter for the last four). `shift: true` asks for Shift, `shift: false` forbids it; a
 *  `ctrl` binding without `shift` fires only without Shift (Ctrl+Shift+Z is not Ctrl+Z).
 *
 *  `declarative: true` also honours the markup: when no registered shortcut took the key, the
 *  first visible, enabled element whose `aria-keyshortcuts` lists it is clicked — the attribute
 *  that tells assistive technology about the shortcut is the one that wires it. Syntax (ARIA):
 *  space-separated shortcuts, each `Modifier+Key` — "1", "Enter", "Escape", "Space", "Shift+N",
 *  "Control+S" (Control = Ctrl or ⌘). A letter compares case-insensitively and Shift must match;
 *  a digit or symbol ("1", "?") ignores Shift (layouts differ: digits need Shift in French).
 *  The same guards apply (fields, keys the focused control owns, handled events); while a modal
 *  dialog is open, only elements INSIDE it answer — nothing behind it. Opt-in, because a page
 *  may already handle the keys it documents with aria-keyshortcuts (then both would act); one
 *  declarative keyboard per page is enough.
 *  @param {{declarative?: boolean}} [opts]
 *  @returns {{ add:(key:string, fn:Function, opts?:{ctrl?:boolean,shift?:boolean,prevent?:boolean})=>(()=>void), destroy:()=>void }}
 *  @example
 *  const keys = makeKeyboard({ declarative: true });   // + <button aria-keyshortcuts="1">…</button>
 *  keys.add(' ', () => player.toggle());
 *  keys.add('e', () => exportCsv(), { ctrl: true });
 *  keys.add('z', redo, { ctrl: true, shift: true });
 */
const _OWNS = [
    ['[role="tab"], [role="menuitem"], [role="option"], [role="slider"], [role="separator"]', /^( |Enter|Arrow\w+|Home|End|PageUp|PageDown)$/],
    ['input[type="range"]', /^(Arrow\w+|Home|End|PageUp|PageDown)$/],
    ['input:is([type="checkbox"], [type="radio"])', /^( |Arrow\w+)$/],
    ['button, a[href], summary, [role="button"], input:is([type="button"], [type="submit"], [type="reset"], [type="file"], [type="color"], [type="image"])', /^( |Enter)$/],
];
const _owned = (t, key) => _OWNS.some(([sel, keys]) => keys.test(key) && t.closest(sel));
/** @private — a control the user types into (a checkbox, a radio, a range or a button input is not). */
const _typing = (t) => t.isContentEditable || /^(TEXTAREA|SELECT)$/.test(t.tagName)
    || (t.tagName === 'INPUT' && !/^(checkbox|radio|range|color|button|submit|reset|file|image)$/.test(t.type));
/** @private — does an aria-keyshortcuts value list this key event? */
const _lists = (value, e) => value.split(/\s+/).some((s) => {
    const plus = s === '+' || s.endsWith('++');                       // the "+" key itself
    const parts = (plus ? s.slice(0, -1) : s).split('+').filter(Boolean);
    const key = plus ? '+' : parts.pop();
    if (!key) return false;
    const mods = new Set(parts.map((m) => m.toLowerCase()));
    if (mods.has('alt') !== e.altKey || (mods.has('control') || mods.has('meta')) !== (e.ctrlKey || e.metaKey)) return false;
    const symbol = key.length === 1 && key.toLowerCase() === key.toUpperCase();   // '1', '?': Shift may be how the layout types it
    if (!symbol && mods.has('shift') !== e.shiftKey) return false;
    return (e.key === ' ' ? 'space' : e.key.toLowerCase()) === key.toLowerCase();
});
export const makeKeyboard = ({ declarative = false } = {}) => {
    const handlers = new Set();
    const { on, off } = _scope();
    document.on('keydown', (e) => {
        if (e.defaultPrevented) return;
        const t = e.composedPath()[0];                 // the real field, even in a shadow root
        const ctrl = e.ctrlKey || e.metaKey;
        if (t instanceof Element && (_typing(t) || (!ctrl && _owned(t, e.key)))) return;
        const modal = document.querySelector('dialog:modal');
        if (!modal) for (const h of handlers) {                        // nothing registered fires behind an open modal
            if (e.key.toLowerCase() !== h.key.toLowerCase() || !!h.ctrl !== ctrl) continue;
            if (h.shift !== undefined ? h.shift !== e.shiftKey : ctrl && e.shiftKey) continue;
            if (h.prevent !== false) e.preventDefault();
            h.fn(e);
            return;
        }
        if (!declarative) return;
        const hit = [...(modal ?? document).querySelectorAll('[aria-keyshortcuts]')].find((x) => _lists(x.getAttribute('aria-keyshortcuts'), e) && !x.disabled && x.getAttribute('aria-disabled') !== 'true'
            && !x.closest('[inert]') && x.checkVisibility({ visibilityProperty: true }));
        if (!hit) return;
        e.preventDefault();                            // handled: another keyboard on the page skips it
        hit.click();
    }, on);
    return {
        add: (key, fn, opts = {}) => { const h = { key, fn, ...opts }; handlers.add(h); return () => handlers.delete(h); },
        destroy() { off(); handlers.clear(); },
    };
};

/** The keyboard and ARIA of a custom slider — a circle, a spectrum, a knob drawn in SVG or on
 *  a canvas (for a plain range, use <input type="range">). The element gets role="slider", a tab
 *  stop, aria-valuemin / max and a live aria-valuenow (and aria-valuetext with `text`); the keys
 *  move the signal: arrows ± step, Shift+arrows and Page Up / Down ± page, Home / End. `wrap`
 *  makes it circular (an angle: 359 + 1 → 0). Pointer input stays yours: write the signal.
 *  `text` runs in an effect: it follows the value AND any signal it reads (a curve's data).
 *  @param {string|Element} el
 *  @param {{value: {value: number}, min?: number, max?: number, step?: number, page?: number, wrap?: boolean, text?: (v: number) => string}} opts
 *  @returns {{ destroy: () => void }}
 *  @example makeSlider('#dial', { value: angle, max: 359, page: 15, wrap: true, text: (v) => `${v} degrees` })
 */
export const makeSlider = (el, { value, min = 0, max = 100, step = 1, page = step * 10, wrap = false, text } = {}) => {
    const s = $(el), { on, off } = _scope();
    s.attr({ role: 'slider', tabindex: s.getAttribute('tabindex') ?? '0', 'aria-valuemin': min, 'aria-valuemax': max });
    const stop = $.effect(() => { s.attr('aria-valuenow', value.value); if (text) s.attr('aria-valuetext', text(value.value)); });
    s.on('keydown', (e) => {
        const big = e.shiftKey ? page : step, v = value.peek();
        const next = { ArrowRight: v + big, ArrowUp: v + big, ArrowLeft: v - big, ArrowDown: v - big,
            PageUp: v + page, PageDown: v - page, Home: min, End: max }[e.key];
        if (next === undefined || e.ctrlKey || e.metaKey || e.altKey) return;
        e.preventDefault();
        const span = max - min + step;                                  // wrap: max + step is min again
        value.value = wrap ? min + ((((next - min) % span) + span) % span) : $.clamp(next, min, max);
    }, on);
    return { destroy() { off(); stop(); } };
};

/** Files by drag and drop — and by paste (a screenshot, a copied file) with `paste: true`.
 *  Shows `.qry-drop-overlay` (created if absent) while files are dragged over the target;
 *  `onFiles` gets the File[] that pass `accept` (the <input accept> syntax).
 *  Pair with `readFiles` to parse them.
 *  `pick` names an element that ALSO opens the file chooser — a click, Enter or Space (it becomes a
 *  focusable role=button if it is not a button already) — so one call gives a page its whole
 *  upload: drop, paste and choose. `multiple: false` keeps one file (the first) from any of the three.
 *  `enable(false)` refuses new files (while one is processed): the pick element is aria-disabled
 *  and a drop shows no overlay. `onRefused` hears about files `accept` turned away (say why —
 *  a silent drop reads as a broken page); a disabled zone refuses nothing, it just does not listen.
 *  @param {string|Element} [target=document.body]
 *  @param {Object}   opts
 *  @param {Function} opts.onFiles                 (File[], event) → void  (required)
 *  @param {string}   [opts.accept='']             '.json,.zip', 'image/*'… ('' = any)
 *  @param {boolean}  [opts.paste=false]           also take files pasted anywhere on the page
 *  @param {string}   [opts.label=labels.drop]
 *  @param {string}   [opts.overlay='#qry-drop']   overlay element (auto-created)
 *  @param {string|Element} [opts.pick]          an element that opens the file chooser too
 *  @param {boolean}  [opts.multiple=true]         false: one file only (the first)
 *  @param {Function} [opts.onRefused]             (File[], event) → void: the files `accept` refused
 *  @returns {{ enable: (on: boolean) => void, readonly enabled: boolean, destroy: Function }}
 *  @example makeDropZone(document.body, { accept: '.json,.zip', onFiles: async (f) => show(await readFiles(f)) })
 *  @example makeDropZone('#avatar', { accept: 'image/*', paste: true, onFiles: ([img]) => upload(img) })
 *  @example const zone = makeDropZone(document.body, { pick: '#upload', accept: '.pdf', multiple: false, paste: true,
 *               onFiles: async ([pdf]) => { zone.enable(false); await send(pdf); zone.enable(true); },
 *               onRefused: () => toast(labels.refused, 'warn') })
 */
export const makeDropZone = (target = document.body, { onFiles, accept = '', paste = false, label = labels.drop, overlay = '#qry-drop', pick, multiple = true, onRefused } = {}) => {
    if (typeof onFiles !== 'function') throw new Error('makeDropZone: onFiles is required');
    const el = $(target);
    const { on, off } = _scope();
    const found = _opt(overlay);
    const ov = found ?? $.create('div', { id: overlay.replace(/^#/, ''), class: 'qry-drop-overlay',
        html: $.html`<div class="qry-drop-overlay-inner"><svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 16V4M7 9l5-5 5 5M4 16v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3"/></svg><span>${label}</span></div>` }).mount(document.body);
    let enabled = true;
    const hand = (list, e) => {
        if (!enabled) return 0;
        const all = [...(list || [])];
        let files = all.filter((f) => _accepts(f, accept));
        if (files.length < all.length) onRefused?.(all.filter((f) => !files.includes(f)), e);
        if (!multiple) files = files.slice(0, 1);
        if (files.length) onFiles(files, e);
        return files.length;
    };
    let depth = 0;                                     // dragenter / dragleave fire for every child
    const show = (v) => { if (!v) depth = 0; ov.cls('is-visible', v); };
    const files = (e) => [...(e.dataTransfer?.types || [])].includes('Files');
    el.on('dragenter', (e) => { if (!files(e)) return; e.preventDefault(); if (enabled && ++depth === 1) show(true); }, on);
    el.on('dragover', (e) => { if (files(e)) e.preventDefault(); }, on);   // required to allow a drop
    el.on('dragleave', () => { if (depth && --depth === 0) show(false); }, on);
    el.on('drop', (e) => { if (!files(e)) return; e.preventDefault(); show(false); hand(e.dataTransfer.files, e); }, on);
    if (paste) document.on('paste', (e) => { if (hand(e.clipboardData?.files, e) || e.clipboardData?.files?.length) e.preventDefault(); }, on);
    let picker = null, input = null;
    if (pick) {
        picker = $(pick);
        const made = !picker.matches('button, [role="button"]');
        if (made) picker.attr({ role: 'button', tabindex: picker.getAttribute('tabindex') ?? 0 });
        input = $.create('input', { type: 'file', accept: accept || null, multiple, hidden: true, class: 'qry-pick' }).mount(document.body);
        const choose = (e) => { if (enabled && e.target !== input) input.click(); };
        picker.on('click', choose, on);
        picker.on('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); choose(e); } }, on);
        input.on('change', (e) => { hand(input.files, e); input.value = ''; }, on);   // the same file again is a change next time
        on.signal.addEventListener('abort', () => { input.remove(); if (made) picker.attr({ role: null, tabindex: null }); picker.attr('aria-disabled', null); });
    }
    return {
        enable(v) { enabled = !!v; if (!enabled) show(false); picker?.attr('aria-disabled', enabled ? null : 'true'); },
        get enabled() { return enabled; },
        destroy() { off(); show(false); if (!found) ov.remove(); },
    };
};

// ════════════════════════════════════════════════════════════════════════════
// EMBEDDING — scripts on demand, iframes that size themselves
// ════════════════════════════════════════════════════════════════════════════

const _scripts = new Map();
/** Load an external script once, on demand. Concurrent calls for the same URL share one
 *  <script> (and one Promise); a failed load is forgotten, so a later call can retry.
 *  @returns {Promise<void>} resolves when the script has run
 *  @example await loadScript('../vendor/jszip.min.js')
 */
export const loadScript = (src) => {
    if (!_scripts.has(src)) _scripts.set(src, new Promise((resolve, reject) => {
        $.create('script', { src, onload: () => resolve(),
            onerror: () => { _scripts.delete(src); reject(new Error(`loadScript: failed to load ${src}`)); } }).mount(document.head);
    }));
    return _scripts.get(src);
};

const _IFRAME_HEIGHT = 'qry:iframe-height';

/** CHILD side: post this document's content height to the parent, so the embedding page
 *  can size the iframe (pairs with `makeIframeEmbed`). Measured with a zero-size sentinel
 *  kept at the end of <body>; debounced on resize and DOM changes; `min` avoids resize
 *  feedback loops. Posts `{ type: 'qry:iframe-height', height }` — to `origin` only (required,
 *  as in qry-bridge: no '*').
 *  @param {Object} opts
 *  @param {string} opts.origin            the parent's origin (required)
 *  @param {number} [opts.min=20]          min px change before re-posting
 *  @param {number} [opts.debounce=250]    ms
 *  @returns {{ send: () => void, post: (type: string, data?: Object) => void, destroy: () => void }}
 *  @example makeIframeAutoHeight({ origin: 'https://host.example' })
 */
export const makeIframeAutoHeight = ({ origin, min = 20, debounce = 250 } = {}) => {
    if (!origin || origin === '*') throw new Error('makeIframeAutoHeight: `origin` (the parent page origin) is required');
    const { on, off, signal } = _scope();
    const mark = $.opt('#qry-iframe-sentinel') ?? $.create('div', { id: 'qry-iframe-sentinel', style: { width: 0, height: 0 } });
    let lastH = 0;
    const send = () => {
        if (signal.aborted) return;
        if (document.body.lastElementChild !== mark) document.body.append(mark);   // content added since: stay last
        const h = Math.ceil(mark.getBoundingClientRect().bottom + scrollY);
        if (lastH && Math.abs(h - lastH) < min) return;
        lastH = h;
        parent.postMessage({ type: _IFRAME_HEIGHT, height: h }, origin);
    };
    const later = $.debounce(send, debounce);
    window.on('resize', later, { ...on, passive: true });
    const mo = new MutationObserver(later);
    mo.observe(document.body, { childList: true, subtree: true, attributes: true });
    if (document.readyState === 'complete') send(); else window.on('load', send, { ...on, once: true });
    return {
        send,
        /** Post an app message to the parent; the type is prefixed `qry:` so makeIframeEmbed's
         *  onMessage receives it. @example post('show-modal', { id: 42 }) */
        post: (type, data = {}) => parent.postMessage({ ...data, type: type.startsWith('qry:') ? type : 'qry:' + type }, origin),
        destroy() { off(); later.cancel(); mo.disconnect(); },
    };
};

/** PARENT side: size an iframe to the height its page reports (makeIframeAutoHeight inside).
 *  Only messages from THAT iframe's window AND from `origin` (required) are honoured; a
 *  height must be a finite number, kept within [min, max].
 *  @param {string|HTMLIFrameElement} target
 *  @param {Object}   opts
 *  @param {string}   opts.origin          the iframe's origin (required)
 *  @param {number}   [opts.min=0] @param {number} [opts.max=20000]   height bounds (px)
 *  @param {number}   [opts.buffer=0]      extra px added to the reported height
 *  @param {number}   [opts.fallback]      height to apply if nothing is reported…
 *  @param {number}   [opts.fallbackAfter=3000]  …within this many ms
 *  @param {Function} [opts.onMessage]     (data, event) → void, for the other qry:* messages
 *  @returns {{ destroy: () => void }}
 *  @example makeIframeEmbed('#widget', { origin: 'https://host.example', min: 800, buffer: 24 })
 */
export const makeIframeEmbed = (target, { origin, min = 0, max = 20000, buffer = 0, fallback, fallbackAfter = 3000, onMessage } = {}) => {
    if (!origin || origin === '*') throw new Error('makeIframeEmbed: `origin` (the iframe origin to trust) is required');
    const frame = $(target);
    const { on, off } = _scope();
    const apply = (px) => frame.css('height', $.clamp(px + buffer, min, max) + 'px');
    let got = false;
    window.on('message', (e) => {
        const d = e.data;
        if (e.source !== frame.contentWindow || e.origin !== origin) return;
        if (typeof d?.type !== 'string' || !d.type.startsWith('qry:')) return;
        if (d.type === _IFRAME_HEIGHT) { if (Number.isFinite(d.height) && d.height >= 0) { got = true; apply(d.height); } }
        else onMessage?.(d, e);
    }, on);
    const timer = fallback != null ? setTimeout(() => { if (!got) apply(fallback); }, fallbackAfter) : 0;
    return { destroy() { off(); clearTimeout(timer); } };
};

// ════════════════════════════════════════════════════════════════════════════
// PWA — an app that installs, works offline and updates itself cleanly
// ════════════════════════════════════════════════════════════════════════════

/** Make a page an installable app: register its service worker, offer each NEW version once
 *  it is ready (never a half-updated page), and give an install button what it needs.
 *  The worker is yours (it must live next to the page — a CDN cannot serve it); it only has to
 *  answer `{ type: 'SKIP_WAITING' }` with `self.skipWaiting()` — README « PWA » has a complete one.
 *  An update is offered only when a worker already controls the page (not on the first visit);
 *  accepting it activates the waiting worker and reloads once it has taken over.
 *  @param {Object} [opts]
 *  @param {string} [opts.sw='sw.js']   the worker's URL (relative to the page)
 *  @param {string} [opts.scope]        its scope (default: the worker's folder)
 *  @param {(apply: () => void) => void} [opts.onUpdate]  a new version waits: call `apply()` to
 *                                      switch (default: confirm(labels.update) → apply)
 *  @returns {{ installable: Signal<boolean>, standalone: boolean, install: () => Promise<boolean>,
 *              update: () => Promise<void>, ready: Promise<ServiceWorkerRegistration|null>, destroy: () => void }}
 *  `installable` turns true when the browser offers installation (Chromium's beforeinstallprompt;
 *  Safari installs from its Share menu, so it stays false there) — bind a button to it.
 *  @example const pwa = makePwa(); $('#install').show(pwa.installable).on('click', () => pwa.install());
 */
export const makePwa = ({ sw = 'sw.js', scope, onUpdate } = {}) => {
    const { on, off, signal } = _scope();
    const installable = $.signal(false);
    let offer = null, applying = false;
    window.on('beforeinstallprompt', (e) => { e.preventDefault(); offer = e; installable.value = true; }, on);
    window.on('appinstalled', () => { offer = null; installable.value = false; }, on);
    const sws = navigator.serviceWorker;
    const propose = onUpdate ?? ((apply) => confirm(labels.update, { ok: labels.reload }).then((yes) => yes && apply()));
    const offerUpdate = (reg) => {
        if (signal.aborted || !reg.waiting || !sws.controller) return;
        propose(() => { applying = true; reg.waiting?.postMessage({ type: 'SKIP_WAITING' }); });
    };
    sws?.addEventListener('controllerchange', () => { if (applying) location.reload(); }, on);
    const ready = !sws ? Promise.resolve(null) : sws.register(sw, scope ? { scope } : undefined).then((reg) => {
        offerUpdate(reg);                                             // a worker already waiting (an earlier visit)
        reg.addEventListener('updatefound', () => {
            const next = reg.installing;
            next?.addEventListener('statechange', () => { if (next.state === 'installed') offerUpdate(reg); }, on);
        }, on);
        return reg;
    }, (e) => { console.warn('makePwa: the service worker did not register', e); return null; });
    return {
        installable,
        standalone: matchMedia('(display-mode: standalone)').matches || navigator.standalone === true,
        async install() {
            if (!offer) return false;
            const o = offer; offer = null; installable.value = false;
            await o.prompt();
            return (await o.userChoice).outcome === 'accepted';
        },
        update: async () => { await (await ready)?.update(); },
        ready,
        destroy: off,
    };
};

// ════════════════════════════════════════════════════════════════════════════
// BUILDERS — ready elements over qry-ui.css (no controller: build, then append)
// ════════════════════════════════════════════════════════════════════════════

/** A label / value line (`.qry-info-row`). Each may be text or a Node (a chip, a link…).
 *  @param {string|Node} label @param {string|number|Node} value
 *  @param {{tone?: 'ok'|'warn'|'danger'|'purple'|'accent', class?: string}} [opts]  a tone colours the value; class goes on the row
 *  @returns {Element}
 *  @example meta.add(infoRow('Pages', doc.pages.length))   meta.add(infoRow('Treadmill', 'ok', { tone: 'ok' }))
 */
export const infoRow = (label, value, { tone = '', class: cls = '' } = {}) => $.create('div', { class: `qry-info-row ${cls}`.trim() },
    $.create('span', { class: 'qry-info-label' }, label ?? ''),
    $.create('span', { class: `qry-info-value ${tone}`.trim() }, value ?? ''));

/** A wrap of toggle buttons (`.qry-chip`, aria-pressed). Items are strings or objects: the
 *  key is `key|name|label|item`, the label `label|name|item` (or your accessors — a label may be
 *  a Node: a colour dot and a name, an icon and a word; text stays text). `selected`
 *  is a key or an array of keys; `onClick(item, key)` fires per chip. The wrap carries
 *  `.select(keys)` to move the highlight without rebuilding.
 *  @param {Array} items
 *  @param {{selected?: any, onClick?: Function, key?: Function, label?: Function, class?: string}} [opts]
 *  @returns {Element & { select:(sel:*|Array)=>Element }}
 *  @example const c = chips(formats, { selected: current, onClick: (f) => pick(f.key) }); c.select(next)
 */
export const chips = (items, { selected, onClick, key = (i) => i?.key ?? i?.name ?? i?.label ?? i, label = (i) => i?.label ?? i?.name ?? i, class: cls = '' } = {}) => {
    const wrap = $.create('div', { class: `qry-chips ${cls}`.trim(), role: 'group' });
    const pills = items.map((item) => {
        const k = key(item);
        const chip = $.create('button', { type: 'button', class: 'qry-chip', 'data-key': k }, label(item)).mount(wrap);
        if (onClick) chip.on('click', () => onClick(item, k));
        return { k, chip };
    });
    wrap.select = (sel) => {
        const keys = [].concat(sel);
        for (const { k, chip } of pills) chip.attr('aria-pressed', String(keys.includes(k)));
        return wrap;
    };
    return wrap.select(selected);
};

/** A sortable table (`.qry-table`). Each header is a button (click or keyboard) that sorts
 *  by its column; a `num` column sorts numerically and aligns right. Values are escaped; a
 *  column's `fmt` returns text (escaped too) or $.html`…`. The table carries `.update(rows)` and
 *  `.sortBy(key, dir?)` to refresh in place.
 *  @param {Array<{key:string, label:string, num?:boolean, fmt?:(v:*, row:object)=>string}>} cols
 *  @param {Array<object>} rows
 *  @param {Object} [opts]
 *  @param {boolean} [opts.index]  a 1-based `#` column first
 *  @param {{key:string, dir:'asc'|'desc'}} [opts.sort]  default: the first numeric column desc, else the first column asc
 *  @param {(row:object, tr:Element)=>void} [opts.onRow]  body-row activation (click, or Enter on the focused row)
 *  @param {string} [opts.empty=labels.empty]
 *  @param {string} [opts.class]  extra class on the table
 *  @returns {Element & { update:(rows:object[])=>Element, sortBy:(key:string, dir?:string)=>Element }}
 *  @example const t = sortableTable(cols, fonts, { index: true, onRow: (f) => pick(f) }); t.update(next)
 */
export const sortableTable = (cols, rows, { index = false, sort, onRow, empty = labels.empty, class: cls = '' } = {}) => {
    const col = (k) => cols.find((c) => c.key === k);
    const first = cols.find((c) => c.num) ?? cols[0];
    const state = sort ? { ...sort } : { key: first.key, dir: first.num ? 'desc' : 'asc' };
    let data = [...(rows ?? [])], view = [];           // view: the sorted rows, index-aligned to the body <tr>s
    const table = $.create('table', { class: `qry-table ${cls}`.trim() });
    const cmp = (a, b) => (col(state.key)?.num
        ? (+a[state.key] || 0) - (+b[state.key] || 0)
        : String(a[state.key] ?? '').localeCompare(String(b[state.key] ?? '')));
    const sorted = (c) => (c.key !== state.key ? 'none' : state.dir === 'asc' ? 'ascending' : 'descending');
    const arrow = (c) => c.key === state.key && $.html` <span aria-hidden="true">${state.dir === 'asc' ? '▲' : '▼'}</span>`;   // aria-sort says it
    const cell = (c, r) => (c.fmt ? c.fmt(r[c.key], r) : r[c.key] ?? '–');
    const render = () => {
        view = data.toSorted((a, b) => (state.dir === 'asc' ? cmp(a, b) : cmp(b, a)));
        const num = (c) => (c.num ? 'qry-num' : '');
        table.html($.html`<thead><tr>${index && $.html`<th scope="col" class="qry-num qry-idx">#</th>`}${cols.map((c) =>
            $.html`<th scope="col" class="${num(c)}" aria-sort="${sorted(c)}"><button type="button" data-key="${c.key}">${c.label}${arrow(c)}</button></th>`)}</tr></thead>
            <tbody>${view.length ? view.map((r, i) => $.html`<tr data-i="${i}"${onRow && $.raw(' tabindex="0"')}>${index && $.html`<td class="qry-num qry-idx">${i + 1}</td>`}${cols.map((c) =>
                $.html`<td class="${num(c)}">${cell(c, r)}</td>`)}</tr>`)
            : $.html`<tr class="qry-table-empty"><td colspan="${cols.length + (index ? 1 : 0)}">${empty}</td></tr>`}</tbody>`);
    };
    table.delegate('th button[data-key]', 'click', function () {
        const k = this.dataset.key;
        if (state.key === k) state.dir = state.dir === 'asc' ? 'desc' : 'asc';
        else Object.assign(state, { key: k, dir: col(k).num ? 'desc' : 'asc' });
        render();
        table.querySelector(`th button[data-key="${CSS.escape(k)}"]`).focus();
    });
    if (onRow) {
        table.delegate('tbody tr[data-i]', 'click', function () { onRow(view[+this.dataset.i], this); });
        table.delegate('tbody tr[data-i]', 'keydown', function (e) { if (e.key === 'Enter') onRow(view[+this.dataset.i], this); });
    }
    table.update = (next) => { data = [...(next ?? [])]; render(); return table; };
    table.sortBy = (key, dir) => { state.key = key; if (dir) state.dir = dir; render(); return table; };
    render();
    return table;
};
