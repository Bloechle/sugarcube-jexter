/**
 * qry.js — Lightweight DOM library
 *
 * Prototype-based: $() returns native elements — transparent, near-zero clashes, fully chainable.
 * Utilities live under $.* — one namespace, no global pollution.
 *
 * KISS · DRY · Zero dependencies · No ES module — the one classic script of the stack: it
 * defines the global `$`. Every layer (qry-web, qry-devtools, qry-bridge, qry-awesome) is an
 * ES module that uses it.
 *
 * Every listener-based helper (on, delegate, onVisible, onResize, onSwipe, onDrag, $.tip) takes a
 * `signal` option: abort its AbortController and everything it wired goes away — ONE teardown
 * model. Setters given a signal or a function follow it (see SETTERS).
 *
 * @version 2.0.0
 * @author Jean-Luc Bloechle with Claude.ai
 * @license MIT
 */

// ═══════════════════════════════════════════════════════════════════════════
// 1. NULL-SAFE MISS
// ═══════════════════════════════════════════════════════════════════════════

/** Fresh detached element returned when a selector matches nothing — prevents
 *  crashes. A factory, not a singleton: each failed lookup gets its own node, so
 *  two misses never share state (e.g. one `.add()` can't leak into another). */
const _nil = () => document.createElement('qry-nil');

// ═══════════════════════════════════════════════════════════════════════════
// 2. SELECTORS
// ═══════════════════════════════════════════════════════════════════════════

/** Select one element (null-safe — returns _nil if not found, or if the selector is invalid).
 *  Accepts a CSS selector string or an existing Element (pass-through).
 *  Uses getElementById for bare #id selectors (faster).
 *  @param {string|Element} s  CSS selector or Element
 *  @returns {Element}
 *  @example const el = $('#chart')
 *  @example const el = $('main .title')
 */
const $ = (s) => {
    if (s instanceof Element) return s;
    let el = null;
    try {
        el = typeof s === 'string' && s[0] === '#' && /^#[\w-]+$/.test(s) ? document.getElementById(s.slice(1)) : document.querySelector(s);
    } catch { console.warn(`$(${JSON.stringify(s)}) — invalid selector`); return _nil(); }
    if (!el) console.warn(`$(${JSON.stringify(s)}) — not found`);
    return el ?? _nil();
};

/** Select all matching elements as a real Array.
 *  @param {string} s  CSS selector
 *  @returns {Element[]}
 *  @example $.all('.btn').forEach(btn => btn.cls('active'))
 */
$.all = (s) => [...document.querySelectorAll(s)];

/** Select an OPTIONAL element: the element, or undefined — no warning, no nil.
 *  For things that may legitimately be absent; pairs with optional chaining.
 *  @param {string|Element} s  CSS selector or Element (pass-through)
 *  @returns {Element|undefined}
 *  @example $.opt('#save')?.show()
 */
$.opt = (s) => s instanceof Element ? s
    : (typeof s === 'string' && s ? document.querySelector(s) ?? undefined : undefined);

/** @private — a child worth inserting: null / undefined / false are nothing (as in $.html). */
const _some = (c) => c != null && c !== false;

/** @private — props shared by $.create and $.svg: `on…` + a function → a listener; `text`,
 *  `html` → content; `value` / `checked` → the live form state (val() / the property);
 *  `style` (an object) → css(); everything else → attr() — so `true` / `false` / `null` and
 *  live values (a signal, a function) behave exactly as in the setters. */
const _build = (el, props, children) => {
    for (const [k, v] of Object.entries(props ?? {})) {
        if (k.startsWith('on') && typeof v === 'function') el.on(k.slice(2).toLowerCase(), v);
        else if (k === 'text' || k === 'html') el[k](v);
        else if (k === 'value') el.val(v);
        else if (k === 'checked') _bind(el, 'checked', v, function (x) { this.checked = !!x; });
        else if (k === 'style' && v && typeof v === 'object') el.css(v);
        else el.attr(k, v);
    }
    for (const c of children.flat(Infinity)) if (_some(c)) el.add(c instanceof Node ? c : document.createTextNode(String(c)));
    return el;
};

/** Create an element: props (attributes, `on…` handlers, `class`, `text`, `html`, `value`,
 *  `checked`, a `style` object) then children (elements or text — never parsed as HTML;
 *  null / undefined / false are skipped, so `cond && el` works).
 *  @param {string} tag
 *  @param {Object} [props]
 *  @param {...(Node|string)} children
 *  @returns {HTMLElement}
 *  @example $.create('div', { class: 'card', text: 'Hi' })
 *  @example $.create('button', { type: 'button', disabled: () => busy.value, onclick: save }, 'Save')
 *  @example $.create('ul', null, items.map(i => $.create('li', { text: i })))
 */
$.create = (tag, props, ...children) => _build(document.createElement(tag), props, children);

/** Create an SVG element (the SVG namespace) — same props and children as $.create.
 *  (On SVG, width / height are native animated lengths: set them with attr(), not width().)
 *  @example $.svg('circle', { cx: 10, cy: 10, r: 4, class: 'dot' }).mount(chart)
 */
$.svg = (tag, props, ...children) => _build(document.createElementNS(_SVG, tag), props, children);
const _SVG = 'http://www.w3.org/2000/svg';

/** Run fn when DOM is ready (or immediately if already loaded).
 *  @param {Function} fn
 *  @example $.ready(() => console.log('DOM ready'))
 */
$.ready = (fn) => (document.readyState === 'loading'
    ? document.on('DOMContentLoaded', fn, { once: true })
    : fn());

// ═══════════════════════════════════════════════════════════════════════════
// 3. EVENTS — on EventTarget (window, document, all elements)
// ═══════════════════════════════════════════════════════════════════════════

/** Add an event listener (chainable). Several events: space-separated.
 *  @param {string}   evt    Event name(s)
 *  @param {Function} fn     Handler
 *  @param {Object}   [opts] addEventListener options — `signal` removes it on abort
 *  @example el.on('click', e => console.log(e))
 *  @example el.on('pointerup pointercancel', end, { signal })
 */
EventTarget.prototype.on = function (evt, fn, opts) {
    for (const e of evt.split(' ')) if (e) this.addEventListener(e, fn, opts);
    return this;
};

/** Remove an event listener (chainable) — same events and options as on().
 *  @example el.off('click', fn)
 */
EventTarget.prototype.off = function (evt, fn, opts) {
    for (const e of evt.split(' ')) if (e) this.removeEventListener(e, fn, opts);
    return this;
};

/** Delegated event — fires only when event target matches sel.
 *  @param {string}   sel    CSS selector for the target descendant
 *  @param {string}   evt    Event name(s)
 *  @param {Function} fn     Handler — called with matched element as context
 *  @param {Object}   [opts] addEventListener options (`signal` removes it)
 *  @example list.delegate('li', 'click', function(e) { this.cls('~active') })
 */
Element.prototype.delegate = function (sel, evt, fn, opts) {
    return this.on(evt, (e) => {
        const t = e.target.closest?.(sel);
        if (t && this.contains(t)) fn.call(t, e);
    }, opts);
};

/** Dispatch a bubbling, cancelable CustomEvent with an optional detail payload.
 *  For native actions, call the native method directly — it's already shorter:
 *  btn.click(), input.focus().
 *  @param {string} evt      Event name
 *  @param {any}    [detail] Payload (e.detail on the listener side)
 *  @example el.trigger('update', { value: 42 })
 */
EventTarget.prototype.trigger = function (evt, detail) {
    this.dispatchEvent(new CustomEvent(evt, { bubbles: true, cancelable: true, detail }));
    return this;
};

// ═══════════════════════════════════════════════════════════════════════════
// 4. SETTERS — one binding model for every setter
// ═══════════════════════════════════════════════════════════════════════════
// Every setter (text, html, attr, css, cls(name, on), show, enable, val, data) goes through
// _bind: a plain value is applied; a signal or a function is FOLLOWED — re-applied whenever
// what it reads changes. The LAST write to a slot wins: el.text(a) then el.text(b) (or
// el.text('x')) stops following a. A binding keeps following while its element is detached
// (put it back and it is up to date) and goes away with the element (it holds it weakly).
// A getter is a call WITHOUT the argument: el.text(undefined) clears, it does not read.

const _slots = new WeakMap();                       // element → Map(slot → stop)
const _bind = (el, slot, v, apply, wire) => {
    let map = _slots.get(el);
    map?.get(slot)?.();
    map?.delete(slot);
    if (!_isLive(v)) { apply.call(el, v); return el; }
    if (!map) _slots.set(el, map = new Map());
    const read = typeof v === 'function' ? v : () => v.value;
    const ref = new WeakRef(el);
    const stopEffect = $.effect(() => {
        const target = ref.deref();
        if (target) apply.call(target, read());       // gone (collected) → reads nothing → never runs again
    });
    const unwire = wire?.(el);
    map.set(slot, () => { stopEffect(); unwire?.(); });
    return el;
};

// ═══════════════════════════════════════════════════════════════════════════
// 5. STYLES — on Element (HTML + SVG)
// ═══════════════════════════════════════════════════════════════════════════

/** @private — camelCase goes through style[prop]; kebab-case and custom properties (`--x`)
 *  through setProperty. null / undefined / '' removes the property. */
const _kebab = (p) => p.includes('-') ? p : p.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
const _setStyle = (el, prop, v) => {
    if (v == null || v === '') el.style.removeProperty(_kebab(prop));
    else if (prop.includes('-')) el.style.setProperty(prop, String(v));
    else el.style[prop] = v;
};

/** Get computed / set one / set multiple CSS properties — custom properties included.
 *  @example el.css('color')                           → 'rgb(0,0,0)'
 *  @example el.css('--qry-aside-w')                   → '240px'
 *  @example el.css('color', 'red')                    → this   (null removes it)
 *  @example el.css({ color: 'red', '--size': '2em' }) → this
 *  @example el.css('opacity', () => busy.value ? .5 : 1)   (live)
 */
Element.prototype.css = function (prop, val) {
    if (prop && typeof prop === 'object') { for (const [k, v] of Object.entries(prop)) this.css(k, v); return this; }
    if (arguments.length < 2) {
        const cs = getComputedStyle(this);
        return prop.includes('-') ? cs.getPropertyValue(prop).trim() : cs[prop];
    }
    return _bind(this, 'css:' + prop, val, function (v) { _setStyle(this, prop, v); });
};

/** @private — width() / height(): measure (border-box px) with no arg, else set (a number is px). */
const _size = (dim) => function (v) {
    if (!arguments.length) return this.getBoundingClientRect()[dim];
    return this.css(dim, typeof v === 'number' ? v + 'px' : v);
};

/** @example el.width() → 320   @example el.width(320) → this   @example el.width('50%') */
Element.prototype.width = _size('width');
/** @example el.height() → 200  @example el.height(200) → this */
Element.prototype.height = _size('height');

// ═══════════════════════════════════════════════════════════════════════════
// 6. CLASSES — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** Manipulate CSS classes with prefix operators.
 *  +name  add      (default if no prefix)
 *  -name  remove
 *  ~name  toggle
 *  ?name  check    → returns boolean
 *  Multiple classes can be space-separated in a single string.
 *  @example el.cls('active')        → this   (add)
 *  @example el.cls('-active')       → this   (remove)
 *  @example el.cls('~open')         → this   (toggle)
 *  @example el.cls('?visible')      → true/false
 *  @example el.cls('+a -b ~c')      → this   (multiple)
 *  @example el.cls('done', item.done)        → this   (on/off by a condition)
 *  @example el.cls('active', () => tab.value === 'a')   (live: follows the signal)
 */
Element.prototype.cls = function (s, on) {
    if (arguments.length > 1) {
        return _bind(this, 'cls:' + s, on, function (v) { for (const c of s.split(' ')) if (c) this.classList.toggle(c, !!v); });
    }
    const ops = { '+': 'add', '-': 'remove', '~': 'toggle' };
    let query;
    for (const t of s.split(' ')) {
        if (!t) continue;
        const op = ops[t[0]];
        if (op) this.classList[op](t.slice(1));
        else if (t[0] === '?') query = this.classList.contains(t.slice(1));
        else this.classList.add(t);
    }
    return query !== undefined ? query : this;
};

// ═══════════════════════════════════════════════════════════════════════════
// 7. VISIBILITY — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** Show the element (default display) — or, given a condition, show it only when it holds
 *  (an explicit undefined / null / false hides). Showing also clears a `hidden` attribute: markup
 *  that starts hidden (no flash before the script) is shown by the same call.
 *  (<dialog> keeps its native show(): use showModal() / close() there.)
 *  @example el.show()   @example el.show(list.length > 0)   @example el.show(() => busy.value)
 */
Element.prototype.show = function (on) {
    return _bind(this, 'show', arguments.length ? on : true, function (v) {
        this.style.display = v ? '' : 'none';
        if (v) this.removeAttribute('hidden');
    });
};

/** Hide element (display: none).             @example el.hide() */
Element.prototype.hide = function () { return Element.prototype.show.call(this, false); };

// ═══════════════════════════════════════════════════════════════════════════
// 8. ATTRIBUTES — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** Get / set / remove (null, undefined, false) / set multiple (object) attributes.
 *  @example el.attr('href')              → '/page'
 *  @example el.attr('href', '/new')      → this
 *  @example el.attr('title', null)       → this  (removes)
 *  @example el.attr({ x: 10, y: 20 })   → this
 *  @example btn.attr('disabled', true)  → this  (true → present, false/null → removed)
 *  @example btn.attr('disabled', () => busy.value)   (live)
 */
Element.prototype.attr = function (name, val) {
    if (name && typeof name === 'object') {
        for (const [k, v] of Object.entries(name)) this.attr(k, v);
        return this;
    }
    if (arguments.length < 2) return this.getAttribute(name);
    return _bind(this, 'attr:' + name, val, function (v) {
        if (v == null || v === false) this.removeAttribute(name); else this.setAttribute(name, v === true ? '' : v);
    });
};

/** Get / set / remove (null, undefined) a data-* attribute — the key in camelCase, as in dataset.
 *  @example el.data('userId')         → '42'   (data-user-id; '' when absent)
 *  @example el.data('userId', 42)     → this
 *  @example el.data('userId', null)   → this   (removed)
 */
Element.prototype.data = function (key, val) {
    if (arguments.length < 2) return this.dataset[key] ?? '';
    return _bind(this, 'data:' + key, val, function (v) { if (v == null) delete this.dataset[key]; else this.dataset[key] = v; });
};

// ═══════════════════════════════════════════════════════════════════════════
// 9. CONTENT — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** Get / set text content (safe — no HTML parsing; null / undefined clear it). The DOM gets a
 *  string, whatever the value (a number, a $.html result) and whatever the DOM implementation.
 *  @example el.text()         → 'Hello'
 *  @example el.text('Hello')  → this
 *  @example el.text(count)    → this   (a signal, or a function: stays up to date)
 */
Element.prototype.text = function (v) {
    if (!arguments.length) return this.textContent;
    return _bind(this, 'content', v, function (x) { this.textContent = String(x ?? ''); });
};

/** Get / set inner HTML (null / undefined clear it). Takes a string or a $.html result — the DOM
 *  always gets a string (a DOM that does not stringify objects, e.g. linkedom, works too).
 *  @example el.html()                       → '<strong>Hi</strong>'
 *  @example el.html('<strong>Hi</strong>')  → this
 *  @example list.html(() => $.html`${todos.value.map(t => $.html`<li>${t.text}</li>`)}`)   (live)
 */
Element.prototype.html = function (v) {
    if (!arguments.length) return this.innerHTML;
    return _bind(this, 'content', v, function (x) { this.innerHTML = String(x ?? ''); });
};

/** Remove all children.  @example el.empty() */
Element.prototype.empty = function () { this.replaceChildren(); return this; };

/** Remove element from the DOM (chainable). Overrides the native
 *  ChildNode.remove() to return this instead of undefined; behaviour is
 *  otherwise identical.  @example el.remove() */
Element.prototype.remove = function () { this.parentNode?.removeChild(this); return this; };

// ═══════════════════════════════════════════════════════════════════════════
// 10. FORM — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** @private — how a control shows a value, and what it reads back. A checkbox is its
 *  `checked`; a radio is checked when its value is the one given; a multiple select is an
 *  array of values; a number / range field reads a number (null while empty or half-typed);
 *  everything else is `value`. A value already shown is not rewritten (the caret stays). */
const _num = (el) => el.type === 'number' || el.type === 'range';
const _put = (el, x) => {
    if (el.type === 'checkbox') el.checked = !!x;
    else if (el.type === 'radio') el.checked = el.value === String(x ?? '');
    else if (el.multiple && el.options) { const set = [].concat(x ?? []).map(String); for (const o of el.options) o.selected = set.includes(o.value); }
    else if (_num(el) && (el.valueAsNumber === x || (x == null && el.value === ''))) return;
    // an <option> without a value attribute READS its text: compare with that and an empty
    // value is never written — `$.create('option', { value: '' }, 'All')` would submit 'All'
    else if (el.tagName === 'OPTION') el.value = x ?? '';
    else if (String(el.value) !== String(x ?? '')) el.value = x ?? '';
};
const _read = (el) => (el.type === 'checkbox' ? el.checked
    : el.multiple && el.options ? [...el.selectedOptions].map((o) => o.value)
    : _num(el) ? (Number.isNaN(el.valueAsNumber) ? null : el.valueAsNumber)
    : el.value ?? '');

/** Get / set the value of a form control.
 *  @example input.val()       → '42'   (a checkbox → checked; a number field → a number or null; select[multiple] → array)
 *  @example input.val('42')   → this
 *  @example input.val(name)   → this   (a SIGNAL: two-way — the field shows it, typing sets it;
 *                                         a later plain input.val('x') writes the signal, the binding stays)
 *  @example $.all('[name="size"]').forEach(r => r.val(size))   (radios: the checked one is the value)
 */
const _twoWay = new WeakMap();                                  // field → the signal it is bound to, both ways
Element.prototype.val = function (v) {
    if (!arguments.length) return _read(this);
    const bound = _twoWay.get(this);
    if (bound && !_isLive(v)) { bound.value = v; return this; }  // a plain write to a bound field goes through its signal
    _twoWay.delete(this);
    if (_isSignal(v)) _twoWay.set(this, v);
    const wire = _isSignal(v) && ((el) => {                     // two-way: the field writes the signal back
        const evt = el.type === 'checkbox' || el.type === 'radio' || el.tagName === 'SELECT' ? 'change' : 'input';
        const back = () => {
            if (el.validity?.badInput) return;                   // '-' or '1e' on the way to a number
            if (el.type !== 'radio') v.value = _read(el); else if (el.checked) v.value = el.value;
        };
        el.on(evt, back);
        return () => el.off(evt, back);
    });
    return _bind(this, 'value', v, function (x) { _put(this, x); }, wire || undefined);
};

/** Enable or disable a form control (a condition, a signal or a function too; an explicit
 *  undefined / null / false disables).
 *  @example input.enable()        → this  (enabled)
 *  @example input.enable(false)   → this  (disabled)
 *  @example save.enable(() => form.valid.value)   (live)
 */
Element.prototype.enable = function (on) {
    return _bind(this, 'enable', arguments.length ? on : true, function (v) { this.disabled = !v; });
};

/** Disable a form control.  @example input.disable() */
Element.prototype.disable = function () { return this.enable(false); };

/** A form's values as a plain object — the shape you send, store or validate. Repeated
 *  names (checkbox groups, multi-selects) become arrays; files stay File objects.
 *  (A group with ONE box ticked gives a string: use [].concat(v) where you expect a list.)
 *  @example form.serialize() → { name: 'Anne', topics: ['news', 'events'] }
 *  @example $.load.json('/join', { method: 'POST', body: form.serialize() })
 */
Element.prototype.serialize = function () {
    const out = {};
    for (const [k, v] of new FormData(this)) out[k] = Object.hasOwn(out, k) ? [].concat(out[k], v) : v;
    return out;
};

// ═══════════════════════════════════════════════════════════════════════════
// 11. CHILDREN — on Element
// ═══════════════════════════════════════════════════════════════════════════

/** @private — shared insertion: a Node (element, text, fragment) goes in as is; anything
 *  else is HTML (a string, or $.html`…`); null / undefined / false are nothing. */
const _node = { end: 'append', start: 'prepend', before: 'before', after: 'after' };
const _html = { end: 'beforeend', start: 'afterbegin', before: 'beforebegin', after: 'afterend' };
const _add = (el, content, where) => {
    if (content instanceof Node) el[_node[where]](content);
    else if (_some(content)) el.insertAdjacentHTML(_html[where], String(content));
    return el;
};

/** @private — HTML → a fragment, parsed for where it goes: a <template> parses anything (even
 *  <tr>); inside SVG, the markup is parsed as SVG. Scripts never run. */
const _frag = (html, context) => {
    const t = document.createElement('template');
    const svg = context?.namespaceURI === _SVG;
    t.innerHTML = svg ? `<svg>${html}</svg>` : String(html ?? '');
    if (!svg) return t.content;
    const f = document.createDocumentFragment();
    f.append(...t.content.firstChild.childNodes);
    return f;
};

/** Append a child (Node or HTML string) at the end.
 *  @example el.add($.create('span', { text: 'Hi' }))
 *  @example el.add('<li>Item</li>')
 */
Element.prototype.add = function (c) { return _add(this, c, 'end'); };

/** Prepend a child at the start.            @example el.addFirst(header) */
Element.prototype.addFirst = function (c) { return _add(this, c, 'start'); };

/** Insert a sibling before this element.    @example el.addBefore(separator) */
Element.prototype.addBefore = function (c) { return _add(this, c, 'before'); };

/** Insert a sibling after this element.     @example el.addAfter(note) */
Element.prototype.addAfter = function (c) { return _add(this, c, 'after'); };

/** Mount this element into a target (append).
 *  @param {string|Element} target  CSS selector or Element
 *  @example card.mount('#container')
 *  @example card.mount(parentEl)
 */
Element.prototype.mount = function (target) { $(target).add(this); return this; };

/** Mount this element into a target (prepend).  @example header.mountFirst('body') */
Element.prototype.mountFirst = function (target) { $(target).addFirst(this); return this; };

// ═══════════════════════════════════════════════════════════════════════════
// 12. TRAVERSAL — on Element
// ═══════════════════════════════════════════════════════════════════════════

/* Traversal answers like the DOM does: an element, or null — pair it with `?.`.
   (Only $() is null-safe: a selector that should match and does not is a bug worth a warning.) */

/** Parent element, or the nearest ANCESTOR matching a selector (never the element itself).
 *  @example el.parent()         → direct parent element (or null)
 *  @example el.parent('.card')?.cls('+open')
 */
Element.prototype.parent = function (s) {
    return (s ? this.parentElement?.closest(s) : this.parentElement) ?? null;
};

/** Next sibling element.      @example el.next() */
Element.prototype.next = function () { return this.nextElementSibling; };

/** Previous sibling element.  @example el.prev() */
Element.prototype.prev = function () { return this.previousElementSibling; };

/** querySelectorAll shortcut — returns a real Array.  @example el.find('li.active') */
Element.prototype.find = function (s) { return [...this.querySelectorAll(s)]; };

/** Siblings (excluding self), optionally filtered.  @example el.siblings('.is-active')
 *  (Direct children: `el.find(':scope > li')` — the native selector, no extra method.) */
Element.prototype.siblings = function (s) {
    const c = [...(this.parentElement?.children ?? [])].filter((el) => el !== this);
    return s ? c.filter((el) => el.matches(s)) : c;
};

/** Position among its siblings (0-based).  @example el.index()  → 2 */
Element.prototype.index = function () {
    return this.parentElement ? [...this.parentElement.children].indexOf(this) : -1;
};

// ═══════════════════════════════════════════════════════════════════════════
// 13. MANIPULATION — on Element
// ═══════════════════════════════════════════════════════════════════════════

/* (Cloning and matching are native and short: el.cloneNode(true), el.matches('.x').) */

/** Replace this element with content (a Node, an HTML string or $.html — parsed as SVG
 *  inside SVG). Returns the replacement — for HTML, the first inserted element (null if the
 *  HTML contains none).
 *  @example const newEl = el.swap('<div class="new">...</div>')
 */
Element.prototype.swap = function (content) {
    const node = content instanceof Node ? content : _frag(content, this.parentElement);
    const first = node instanceof DocumentFragment ? node.firstElementChild : node;
    this.replaceWith(node);
    return first;
};

/** Wrap this element in a wrapper (an Element, or HTML whose first element is used — parsed
 *  as SVG inside SVG). Markup with no element is a no-op.
 *  @example el.wrap('<div class="wrapper">')
 *  @example el.wrap($.create('figure'))
 */
Element.prototype.wrap = function (wrapper) {
    const w = wrapper instanceof Element ? wrapper : _frag(wrapper, this.parentElement).firstElementChild;
    if (!w) return this;
    this.before(w);
    w.append(this);
    return this;
};

/** Get position relative to the document (top + left).
 *  @example const { top, left } = el.offset()
 */
Element.prototype.offset = function () {
    const r = this.getBoundingClientRect();
    return { top: r.top + scrollY, left: r.left + scrollX };
};

// ═══════════════════════════════════════════════════════════════════════════
// 13b. LEGACY SHADOW FIXES
// ═══════════════════════════════════════════════════════════════════════════

/* A few prototypes carry legacy members that shadow the qry methods defined on
 * Element.prototype (e.g. `$('a').text('Hi')` would throw — HTMLAnchorElement.text is a
 * legacy string accessor). Re-assert the qry method where the native member is a vestigial
 * alias nobody should rely on:
 *   .text  on <a>, <option>, <script>, <title>, <body>   (aliases of textContent / bgcolor-era text colour)
 *   .data  on <object>                                   (reflection of the data attribute)
 *   .wrap  on <textarea>                                 (reflection of the wrap attribute)
 *   .add   on <select>                                   (native add(option, before) — qry add(el|html) covers it)
 * Deliberately NOT touched — the native member wins, use the qry alternative:
 *   width / height on <img> <canvas> <video> <iframe> <embed> <object> <input> <table> <col>
 *     <hr> <pre> <th> <td> and SVG shapes (`canvas.width = …` must keep resizing the bitmap)
 *     → el.css('width', …) / getBoundingClientRect() / attr('width', …)
 *   show() on <dialog> (native: opens it) → showModal() / close();  index on <option>;
 *   remove(i) on <select>;  a <form>'s named controls (form.text is its <input name="text">).
 * defineProperty is required: plain assignment would trip the legacy accessor's setter. */
{
    const P = Element.prototype;
    const fix = (proto, name) =>
        Object.defineProperty(proto, name, { value: P[name], writable: true, configurable: true });
    [HTMLAnchorElement, HTMLOptionElement, HTMLScriptElement, HTMLTitleElement, HTMLBodyElement].forEach((C) => fix(C.prototype, 'text'));
    fix(HTMLObjectElement.prototype, 'data');
    fix(HTMLTextAreaElement.prototype, 'wrap');
    fix(HTMLSelectElement.prototype, 'add');
}

// ═══════════════════════════════════════════════════════════════════════════
// 14. SCALE
// ═══════════════════════════════════════════════════════════════════════════

/** Map a value linearly from one range to another.
 *  @param {number} val    Input value
 *  @param {number} inMin  Input minimum
 *  @param {number} inMax  Input maximum
 *  @param {number} outMin Output minimum
 *  @param {number} outMax Output maximum
 *  @returns {number}
 *  @example $.scale(82, 0, 100, 0, 500)    → 410
 *  @example $.scale(82, 0, 100, 300, 0)    → 54   (inverted for SVG y-axis)
 */
$.scale = (val, inMin, inMax, outMin, outMax) =>
    outMin + ((val - inMin) / (inMax - inMin)) * (outMax - outMin);

// ═══════════════════════════════════════════════════════════════════════════
// 15. SMALL UTILITIES — the ones every page ends up writing
// ═══════════════════════════════════════════════════════════════════════════

/** Escape text for HTML (& < > " ') — for any value going into .html() or a template.
 *  @example el.html(`<b>${$.esc(name)}</b>`)
 */
$.esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/** A colour — a token ('--qry-accent') or any CSS colour — resolved WHERE an element is
 *  (light / dark, a scoped .qry-theme, relative colours) into an `rgb(r g b / a)` string a
 *  canvas understands. A canvas cannot read CSS variables, and qry's tokens are light-dark()
 *  expressions: resolve them with this, again when the theme changes (makeCanvas does).
 *  Where no 2D canvas exists (a DOM without one, e.g. linkedom), it returns the computed colour
 *  as the browser serialises it — it never throws for that.
 *  @example ctx.strokeStyle = $.color('--qry-accent', canvas)
 */
let _paint = null;
$.color = (c, el = document.body) => {
    const host = el instanceof HTMLElement && !(el instanceof HTMLCanvasElement) ? el : el.parentElement ?? document.body;
    const probe = $.create('i', { style: { color: c.startsWith('--') ? `var(${c})` : c, display: 'none' } });
    host.append(probe);
    const v = getComputedStyle(probe).color;
    probe.remove();
    _paint ??= document.createElement('canvas').getContext?.('2d', { willReadFrequently: true }) ?? null;
    if (!_paint) return v;
    _paint.clearRect(0, 0, 1, 1);
    _paint.fillStyle = v;
    _paint.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = _paint.getImageData(0, 0, 1, 1).data;
    return a === 255 ? `rgb(${r} ${g} ${b})` : `rgb(${r} ${g} ${b} / ${(a / 255).toFixed(3)})`;
};

/** Clamp a number into [lo, hi].  @example $.clamp(120, 0, 100) → 100 */
$.clamp = (x, lo, hi) => (x < lo ? lo : x > hi ? hi : x);

/** Await a delay.  @example await $.sleep(300) */
$.sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Call fn once things settle: after `ms` without a new call (typing, resizing).
 *  The returned function has `.cancel()` (drop a pending call — on teardown).
 *  @example input.on('input', $.debounce(search, 250))
 */
$.debounce = (fn, ms = 200) => {
    let t;
    const d = function (...a) { clearTimeout(t); t = setTimeout(() => fn.apply(this, a), ms); };
    d.cancel = () => clearTimeout(t);
    return d;
};

/** Call fn at most once every `ms` (scroll, pointer move); the last call is not lost.
 *  @example window.on('scroll', $.throttle(update, 100))
 */
$.throttle = (fn, ms = 100) => {
    let last = 0, t;
    return function (...a) {
        const wait = ms - (Date.now() - last);
        clearTimeout(t);
        if (wait <= 0) { last = Date.now(); fn.apply(this, a); }
        else t = setTimeout(() => { last = Date.now(); fn.apply(this, a); }, wait);
    };
};

/** A prefixed, JSON-safe localStorage that never throws.
 *  get(k, fallback) → the value, or `fallback` when absent, unreadable or unparsable;
 *  set(k, v) → true when written, false when the browser refused (quota full, private mode,
 *  storage blocked) — so an app can say « no room left » instead of losing data silently;
 *  remove(k) → nothing.
 *  @example const store = $.store('floop:'); if (!store.set('draft', text)) toast('No room left', 'danger')
 */
$.store = (prefix = '') => ({
    get(k, fallback = null) { try { const v = localStorage.getItem(prefix + k); return v === null ? fallback : JSON.parse(v); } catch { return fallback; } },
    set(k, v) { try { localStorage.setItem(prefix + k, JSON.stringify(v)); return true; } catch { return false; } },
    remove(k) { try { localStorage.removeItem(prefix + k); } catch { /* storage blocked */ } },
});

/** A string's FOLDED form, to compare what people type: lower case, accents and other marks
 *  dropped (é → e, ü → u, ç → c), compatibility forms unified (ﬁ → fi, ³ → 3), ß → ss, white
 *  space collapsed and trimmed. `{ punctuation: true }` also drops SENTENCE punctuation only —
 *  . , ; : ! ? ¡ ¿ quotes and apostrophes « » " ' ’ … and brackets ( ) [ ] { } — and keeps what
 *  carries meaning in an answer: every symbol and sign (/ % & + − × ÷ = < > - – # @ *) and a . or
 *  , between two digits (3.5, 1,000). So "1/2" ≠ "12", "50%" ≠ "50", "3.5" ≠ "35", but
 *  "Ça va ?" = "ca va". For search and lenient answers; never for display. Pure, no DOM.
 *  @param {*} s
 *  @param {{punctuation?: boolean}} [opts]
 *  @returns {string}
 *  @example $.fold('  Fußball  Über ') → 'fussball uber'
 *  @example $.fold('Qu’est-ce ?', { punctuation: true }) → 'quest-ce'
 */
$.fold = (s, { punctuation = false } = {}) => {
    let t = String(s ?? '').normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase().replace(/ß/g, 'ss');
    if (punctuation) t = t.replace(/(?<!\d)[.,]|[.,](?!\d)|[;:!?¡¿«»"'‘’‚‛“”„‟…()[\]{}]/g, '');
    return t.replace(/\s+/g, ' ').trim();
};

/** A random number generator — seeded (a number or a string: the same seed, the same sequence,
 *  on every machine — a build, a test, a shared puzzle) or not (no seed: a fresh one). mulberry32,
 *  a string seed hashed first. One way for randomness: `$.rng()` for a quick shuffle, `$.rng(seed)`
 *  for reproducible runs.
 *  next() → [0, 1) · int(a, b) → an integer in [a, b] · pick(arr) → an element (undefined if
 *  empty) · shuffle(arr) → a shuffled COPY (Fisher–Yates; the array is untouched).
 *  @param {number|string} [seed]
 *  @example const r = $.rng('lesson-12'); r.shuffle(options); r.int(1, 6); r.pick(words)
 *  @example $.rng().shuffle([1, 2, 3])
 */
$.rng = (seed) => {
    let h = 0;
    if (seed === undefined) h = (Math.random() * 2 ** 32) >>> 0;
    else if (typeof seed === 'number') h = seed >>> 0;
    else for (const c of String(seed)) h = Math.imul(h ^ c.codePointAt(0), 0x9e3779b1) ^ (h >>> 16);   // a string → 32 bits
    const next = () => {
        h = (h + 0x6d2b79f5) | 0;
        let t = Math.imul(h ^ (h >>> 15), 1 | h);
        t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
        return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const int = (a, b) => a + Math.floor(next() * (b - a + 1));
    return {
        next, int,
        pick: (arr) => (arr.length ? arr[int(0, arr.length - 1)] : undefined),
        shuffle(arr) {
            const a = [...arr];
            for (let i = a.length - 1; i > 0; i--) { const j = int(0, i); [a[i], a[j]] = [a[j], a[i]]; }
            return a;
        },
    };
};

// ═══════════════════════════════════════════════════════════════════════════
// 16. DATA LOADING
// ═══════════════════════════════════════════════════════════════════════════

/** @private — fetch with what every call needs: an HTTP-status check (a 404/500 throws a
 *  readable error, not a parse failure on the error page — carrying `status`, `detail` (the
 *  server's own words: a JSON body's `error` / `message` — `error` may itself be
 *  `{ code, message }` — else its text) and `code` (the server's machine-readable one, or
 *  null)), a plain object or array
 *  body sent as JSON (strings, FormData, Blobs, buffers, streams go as they are), an optional
 *  timeout that adds to the caller's own `signal`. Any other fetch option passes through. */
const _fetch = async (url, { body, headers, timeout, signal, ...init } = {}) => {
    headers = new Headers(headers);
    if (Array.isArray(body) || body?.constructor === Object) {
        body = JSON.stringify(body);
        if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    }
    if (timeout) signal = signal ? AbortSignal.any([signal, AbortSignal.timeout(timeout)]) : AbortSignal.timeout(timeout);
    const r = await fetch(url, { ...init, body, headers, signal }).catch((e) => {
        throw e.name === 'TimeoutError' ? new Error(`$.load — no answer after ${timeout} ms — ${url}`) : e;
    });
    if (!r.ok) {
        const text = (await r.text().catch(() => '')).trim();
        let detail = text, code = null;
        try {
            const j = JSON.parse(text), e = j?.error;
            detail = typeof j === 'string' ? j : (typeof e === 'object' && e ? e.message : e) ?? j?.message ?? text;
            code = (typeof e === 'object' && e ? e.code : j?.code) ?? null;
        } catch { /* not JSON: the text */ }
        detail = String(detail ?? '').slice(0, 300);
        throw Object.assign(new Error(`$.load — ${r.status} ${r.statusText} — ${url}${detail ? ` — ${detail}` : ''}`), { status: r.status, detail, code });
    }
    return r;
};

/** Parse CSV text. Honors quoted fields, `""` escapes, separators and
 *  newlines inside quotes, a leading BOM, and CRLF/CR line endings. Blank
 *  lines are skipped (a quoted empty value is not blank). Values stay strings —
 *  convert with +v or parseFloat(v).
 *
 *  With `header` (default), the first row names the columns and each data
 *  row becomes an object. With `header: false`, returns string arrays.
 *
 *  @param {string} text  Raw CSV text
 *  @param {Object} [opts]
 *  @param {string}  [opts.sep=',']      Column separator (',' '\t' ';'…)
 *  @param {boolean} [opts.header=true]  First row is a header row
 *  @param {boolean} [opts.trim=true]    Trim each field
 *  @returns {Object[]|string[][]}
 *  @example $.parseCSV('a,b\n1,"x,y"')                   → [{ a:'1', b:'x,y' }]
 *  @example $.parseCSV('1;2\n3;4', { sep:';', header:false }) → [['1','2'],['3','4']]
 */
$.parseCSV = (text, { sep = ',', header = true, trim = true } = {}) => {
    if (!text) return [];
    if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);   // strip BOM
    const rows = [[]], quoted = new Set();                      // rows that had a quote: never blank
    let cur = '', q = false;
    const endField = () => { rows[rows.length - 1].push(trim ? cur.trim() : cur); cur = ''; };
    const endRow   = () => { endField(); rows.push([]); };
    for (let i = 0; i < text.length; i++) {
        const c = text[i];
        if (c === '"') {
            quoted.add(rows.length - 1);
            if (q && text[i + 1] === '"') { cur += '"'; i++; }   // "" escape
            else q = !q;
        }
        else if (!q && c === sep)  endField();
        else if (!q && c === '\n') endRow();
        else if (!q && c === '\r') { endRow(); if (text[i + 1] === '\n') i++; }
        else cur += c;
    }
    endField();
    const out = rows.filter((r, i) => r.length > 1 || r[0] !== '' || quoted.has(i));  // drop blank lines
    if (!out.length || !header) return out;
    const keys = out[0];
    return out.slice(1).map((vals) =>
        Object.fromEntries(keys.map((k, i) => [k, vals[i] ?? ''])));
};

/** Talk to a server or load a file — ONE helper for both. Every loader takes fetch options:
 *  `method`, `body` (a plain object / array is sent as JSON), `headers`, `signal`, `timeout`
 *  (ms), and the rest. A refused request throws an Error with `status` (404, 409…), `detail`
 *  (what the server said: `{ "error": "…" }`, `{ "error": { "code", "message" } }`, `{ "message": "…" }`
 *  or its text) — show `e.detail` — and `code` (the server's own code, e.g. 'rate_limited', or null).
 *  @namespace $.load
 *  @example const data = await $.load.json('./data/countries.json')
 *  @example await $.load.json('/api/notes', { method: 'POST', body: { text: 'hi' }, timeout: 5000 })
 */
$.load = {
    /** Fetch and parse JSON (an empty answer gives null). */
    json: (url, opts) => _fetch(url, opts).then((r) => r.text()).then((t) => (t ? JSON.parse(t) : null)),

    /** Fetch text (HTML fragments, Markdown, SVG…). */
    text: (url, opts) => _fetch(url, opts).then((r) => r.text()),

    /** Fetch a Blob (an image, a zip, a PDF…). */
    blob: (url, opts) => _fetch(url, opts).then((r) => r.blob()),

    /** Load and parse a CSV file into an array of objects — see $.parseCSV
     *  for parsing rules and options.
     *  @param {string} url
     *  @param {Object} [opts]  { sep, header, trim } + any fetch option (headers included)
     *  @returns {Promise<Object[]>}
     *  @example const data = await $.load.csv('./data/pop.csv')
     *  @example const data = await $.load.csv('./data/pop.tsv', { sep: '\t' })
     */
    csv: (url, { sep, header, trim, ...opts } = {}) => _fetch(url, opts).then((r) => r.text()).then((text) => $.parseCSV(text, { sep, header, trim })),
};

// ═══════════════════════════════════════════════════════════════════════════
// 17. TOOLTIP — one per page, created on first use
// ═══════════════════════════════════════════════════════════════════════════

/** A shared tooltip that follows the cursor: one call binds enter / move / leave — and keyboard
 *  focus (a focusable element shows it under itself on :focus-visible, hides it on blur).
 *  `title` and `content` are escaped like $.html values (pass $.html`…` for markup), and may
 *  be functions, read each time the tooltip shows (a chart whose data changes).
 *  The tooltip is fixed to the viewport and flips to stay inside it — even faded out it never
 *  widens the page. A scroll or a resize hides a pointer's tip and moves a focused element's
 *  along with it (focus scrolls into view). qry-ui.css skins it; without it, style
 *  #qry-tooltip, #qry-tooltip-title and #qry-tooltip-content yourself.
 *  @param {Element} el
 *  @param {string|Function} title
 *  @param {string|Function} [content]
 *  @param {{enter?: Function, leave?: Function, signal?: AbortSignal}} [opts]
 *  @example $.tip(circle, 'France', '67.4M inhabitants')
 *  @example $.tip(bar, () => d.name, () => $.html`<b>${d.value}</b> visits`, { enter: () => bar.cls('+hot'), leave: () => bar.cls('-hot') })
 *  Low level (e.g. suppress while dragging): $.tip.show(eventOrElement, title, content) · $.tip.move(e) · $.tip.hide()
 */
$.tip = (el, title, content, { enter, leave, signal } = {}) => el
    .on('mouseenter', (e) => { $.tip.show(e, title, content); enter?.(e); }, { signal })
    .on('mousemove', (e) => $.tip.move(e), { signal })
    .on('mouseleave', (e) => { $.tip.hide(); leave?.(e); }, { signal })
    .on('focus', (e) => { if (el.matches(':focus-visible')) { $.tip.show(el, title, content); enter?.(e); } }, { signal })
    .on('blur', (e) => { $.tip.hide(); leave?.(e); }, { signal });
{
    let box = null, head, body, owner = null, anchored = false;
    /** A stand-in pointer under an element's box (keyboard focus, a tap). */
    const under = (el) => { const r = el.getBoundingClientRect(); return { currentTarget: el, clientX: r.left, clientY: r.bottom + 12 }; };
    const value = (v) => _part(typeof v === 'function' ? v() : v);
    const init = () => {
        if (box) return;
        // mechanics only (position, fade, above everything, never eats the mouse) — the look is CSS's
        // (fixed: an absolute box left where it faded out would widen the page once the window narrows)
        document.head.add($.create('style', { text: '#qry-tooltip{position:fixed;left:0;top:0;pointer-events:none;opacity:0;transition:opacity .15s;z-index:1000}#qry-tooltip>p{margin:0}#qry-tooltip>p:empty{display:none}' }));
        head = $.create('p', { id: 'qry-tooltip-title' });
        body = $.create('p', { id: 'qry-tooltip-content' });
        box = $.create('div', { id: 'qry-tooltip', role: 'tooltip' }, head, body).mount(document.body);
        // the element under the pointer may be removed (a chart redraws): no mouseleave then
        document.on('pointermove', () => { if (owner && !owner.isConnected) $.tip.hide(); }, { passive: true });
        // the content moves under a fixed tip: a scroll (the page or a scrolling panel) or a resize hides a
        // pointer's tip, and moves an anchored one with its element
        const away = () => { if (owner) { if (anchored && owner.isConnected) $.tip.move(under(owner)); else $.tip.hide(); } };
        document.on('scroll', away, { passive: true, capture: true });
        window.on('resize', away, { passive: true });
    };
    /** Show the tooltip at the pointer — or under an element (keyboard focus, a tap). */
    $.tip.show = (e, title, content) => {
        init();
        anchored = e instanceof Element;
        if (anchored) e = under(e);
        owner = e.currentTarget instanceof Element ? e.currentTarget : e.target;
        head.html(value(title));
        body.html(value(content));
        box.style.opacity = '1';
        $.tip.move(e);
    };
    /** Follow the pointer (viewport coordinates); flip left / up rather than overflow the viewport. */
    $.tip.move = (e) => {
        if (!box) return;
        const { width: w, height: h } = box.getBoundingClientRect();
        const x = e.clientX + 16 + w > innerWidth ? e.clientX - 16 - w : e.clientX + 16;
        const y = e.clientY - 8 + h > innerHeight ? e.clientY - 8 - h : e.clientY - 8;
        box.style.left = Math.max(0, x) + 'px';
        box.style.top = Math.max(0, y) + 'px';
    };
    /** Fade the tooltip out. */
    $.tip.hide = () => { owner = null; if (box) box.style.opacity = '0'; };
}

// ═══════════════════════════════════════════════════════════════════════════
// 18. VIEWPORT & GESTURES — what pages re-wrote by hand, each a little differently
// ═══════════════════════════════════════════════════════════════════════════

/** True when the visitor asked for less motion (OS setting) — read live, never cached.
 *  @example if (!$.reduced) el.cls('+animate')
 */
Object.defineProperty($, 'reduced', { get: () => matchMedia('(prefers-reduced-motion: reduce)').matches });

/** @private — an observer that stops when `signal` aborts. */
const _watch = (observer, el, signal) => {
    if (signal?.aborted) return;
    observer.observe(el);
    signal?.addEventListener('abort', () => observer.disconnect(), { once: true });
};

/** Call fn(true) when the element enters the viewport and fn(false) when it leaves —
 *  start an animation only while it is seen, light up the section being read.
 *  `once`: fn(true) the first time only, then stop watching (reveal on scroll). Chainable.
 *  @param {Function} fn  (visible: boolean, entry) => void
 *  @param {{once?: boolean, threshold?: number|number[], rootMargin?: string, signal?: AbortSignal}} [opts]
 *  @example canvas.onVisible(v => v ? start() : stop())
 *  @example section.onVisible(() => section.cls('+in'), { once: true, threshold: 0.2 })
 */
Element.prototype.onVisible = function (fn, { once = false, threshold = 0, rootMargin = '0px', signal } = {}) {
    const io = new IntersectionObserver((entries) => {
        const e = entries.at(-1);                                     // the latest state of a batch
        if (!once) fn(e.isIntersecting, e);
        else if (e.isIntersecting) { io.disconnect(); fn(true, e); }
    }, { threshold, rootMargin });
    _watch(io, this, signal);
    return this;
};

/** Call fn({ width, height }) when the element's size changes (and once at the start) —
 *  redraw a chart for its box, not for the window. Chainable.
 *  @param {Function} fn  ({width, height}, entry) => void   (content-box px)
 *  @param {{signal?: AbortSignal}} [opts]
 *  @example chart.onResize(({ width }) => draw(width))
 */
Element.prototype.onResize = function (fn, { signal } = {}) {
    _watch(new ResizeObserver(([e]) => fn({ width: e.contentRect.width, height: e.contentRect.height }, e)), this, signal);
    return this;
};

/** A horizontal swipe by finger or pen: fn(+1) when it goes left (next), fn(-1) when it
 *  goes right (previous). The mouse is ignored unless `mouse: true`. The click a swipe
 *  ends with is swallowed, so a swipe never also "clicks" what is under it. Chainable.
 *  (qry-web's makeViewer does more: the photo follows the finger.)
 *  @param {Function} fn  (direction: 1|-1, event) => void
 *  @param {{min?: number, mouse?: boolean, signal?: AbortSignal}} [opts]  min: distance in px (default 50)
 *  @example cards.onSwipe(d => go(index + d))
 */
Element.prototype.onSwipe = function (fn, { min = 50, mouse = false, signal } = {}) {
    // a horizontal drag must reach us: let the browser keep vertical scrolling only
    if (getComputedStyle(this).touchAction === 'auto') this.style.touchAction = 'pan-y pinch-zoom';
    let x0 = null, y0 = 0;
    this.on('pointerdown', (e) => { if (mouse || e.pointerType !== 'mouse') { x0 = e.clientX; y0 = e.clientY; } }, { signal });
    this.on('pointercancel', () => { x0 = null; }, { signal });
    this.on('pointerup', (e) => {
        if (x0 === null) return;
        const dx = e.clientX - x0, dy = e.clientY - y0;
        x0 = null;
        if (Math.abs(dx) < min || Math.abs(dx) < Math.abs(dy)) return;    // too short, or a scroll
        const swallow = (c) => { c.stopPropagation(); c.preventDefault(); };
        this.addEventListener('click', swallow, { capture: true, once: true });
        setTimeout(() => this.removeEventListener('click', swallow, { capture: true }), 400);
        fn(dx < 0 ? 1 : -1, e);
    }, { signal });
    return this;
};

/** A drag by mouse, finger or pen: fn({ x, y, dx, dy, phase, event }) with phase 'start',
 *  'move', 'end' ('end' also on cancel). x / y are in the element's box (CSS px), dx / dy since
 *  the start. The pointer is captured, so the drag survives leaving the element. Returning
 *  false from 'start' declines that drag (a press on a child button…). Sets touch-action: none
 *  unless the element has its own. One pointer at a time. Chainable.
 *  @param {Function} fn  ({x, y, dx, dy, phase, event}) => void | false
 *  @param {{button?: number, signal?: AbortSignal}} [opts]  button: the mouse button (default 0, -1 = any)
 *  @example knob.onDrag(({ x, phase }) => { if (phase !== 'end') angle.value = x / knob.width() * 360; })
 */
Element.prototype.onDrag = function (fn, { button = 0, signal } = {}) {
    if (getComputedStyle(this).touchAction === 'auto') this.style.touchAction = 'none';
    let id = null, x0 = 0, y0 = 0;
    const at = (e, phase) => {
        const r = this.getBoundingClientRect();
        return { x: e.clientX - r.left, y: e.clientY - r.top, dx: e.clientX - x0, dy: e.clientY - y0, phase, event: e };
    };
    this.on('pointerdown', (e) => {
        if (id !== null || (e.pointerType === 'mouse' && button >= 0 && e.button !== button)) return;
        x0 = e.clientX; y0 = e.clientY;
        if (fn(at(e, 'start')) === false) return;
        id = e.pointerId;
        try { this.setPointerCapture(id); } catch { /* a synthetic event has no pointer to capture */ }
        e.preventDefault();                                      // no text selection, no native drag
    }, { signal });
    this.on('pointermove', (e) => { if (e.pointerId === id) fn(at(e, 'move')); }, { signal });
    this.on('pointerup pointercancel', (e) => {
        if (e.pointerId !== id) return;
        id = null;
        fn(at(e, 'end'));
    }, { signal });
    return this;
};

// ═══════════════════════════════════════════════════════════════════════════
// 19. SAFE HTML TEMPLATES — every value escaped unless you say otherwise
// ═══════════════════════════════════════════════════════════════════════════

/** Trusted HTML, as returned by $.html and $.raw — inserted as is, never escaped again. */
class _SafeHTML { constructor(s) { this.s = s; } toString() { return this.s; } }
/** @private — a value as markup. In content (and an unquoted, boolean-attribute position)
 *  null / undefined / false are nothing — `${done && 'checked'}` works; inside a QUOTED attribute
 *  value a boolean is its word — aria-pressed="${false}" is "false" — null / undefined stay empty. */
const _part = (v, quoted = false) => (v == null ? ''
    : v === false ? (quoted ? 'false' : '')
    : v instanceof _SafeHTML ? v.s
    : Array.isArray(v) ? v.map((x) => _part(x, quoted)).join('')
    : $.esc(v));
/** @private — for each value of a template, is it inside a quoted attribute value? Read from the
 *  static parts only (values are escaped: they cannot open or close a quote), once per call site
 *  (the strings array of a tagged template is the same object every time). */
const _quoted = new WeakMap();
const _contexts = (strings) => {
    let got = _quoted.get(strings);
    if (got) return got;
    got = [];
    let state = 'text', q = '';
    for (const str of strings) {
        for (let i = 0; i < str.length; i++) {
            const c = str[i];
            if (state === 'text') { if (c === '<' && /[a-zA-Z]/.test(str[i + 1] ?? '')) state = 'tag'; }
            else if (state === 'tag') { if (c === '"' || c === "'") { state = 'quote'; q = c; } else if (c === '>') state = 'text'; }
            else if (c === q) state = 'tag';
        }
        got.push(state === 'quote');
    }
    _quoted.set(strings, got);
    return got;
};

/** A tagged template that ESCAPES every value: user text can never become markup.
 *  Arrays are joined (lists); null / undefined / false render nothing (so `${done && 'checked'}`
 *  adds a boolean attribute or nothing); inside a QUOTED attribute value a boolean renders as its
 *  word (`aria-pressed="${false}"` → "false"), null / undefined still render nothing; numbers
 *  always render (0 is "0"); a nested $.html or $.raw stays HTML. The result goes into .html(), .add() or a template literal.
 *  Escaping protects text and QUOTED attribute values: always quote attributes
 *  (class="${c}", never class=${c}); URLs are not checked — never put user input in
 *  href / src without validating its scheme.
 *  @example list.html($.html`<ul>${items.map(i => $.html`<li class="${i.cls}">${i.name}</li>`)}</ul>`)
 */
$.html = (strings, ...values) => {
    const quoted = _contexts(strings);
    return new _SafeHTML(strings.reduce((out, str, i) => out + str + (i < values.length ? _part(values[i], quoted[i]) : ''), ''));
};

/** Mark a string you trust as HTML (your own markup, never user input).
 *  @example $.html`<p>${$.raw(icons.check)} Saved</p>`
 */
$.raw = (s) => new _SafeHTML(String(s ?? ''));

/** A safe inline mini-markup → trusted HTML: `*bold*` → <strong>, `_italic_` → <em>,
 *  `==marked==` → <mark>, `` `code` `` → <code>. Everything is escaped FIRST, so the text can never
 *  carry markup of its own; code spans are set aside before the others (`i * 2 * 3` stays code).
 *  One line of content (a title, a hint, a step) — no blocks, no links, no nesting rules.
 *  @param {*} text
 *  @returns {Object} SafeHTML (goes into .html(), .add() or a $.html template)
 *  @example $('#hint').html($.md('Press *Enter* — or type `ok`'))
 */
$.md = (text) => $.raw(String(text ?? '').split(/`([^`]+)`/).map((p, i) => (i % 2 ? `<code>${$.esc(p)}</code>` : $.esc(p)
    .replace(/\*([^*]+)\*/g, '<strong>$1</strong>')
    .replace(/_([^_]+)_/g, '<em>$1</em>')
    .replace(/==([^=]+)==/g, '<mark>$1</mark>'))).join(''));

// ═══════════════════════════════════════════════════════════════════════════
// 20. REACTIVITY — signals, effects, and setters that follow them
// ═══════════════════════════════════════════════════════════════════════════
// A signal holds a value; an effect re-runs when a signal it READ changes; a computed is a
// signal derived from others. Effects run synchronously; $.batch groups several writes into
// one run. And the qry setters accept a signal or a function instead of a value — then they
// stay up to date by themselves:   $('#left').text(() => `${left.value} to do`)

let _observer = null, _batching = 0;
const _pending = new Set();

/** @private — run what writes scheduled, until nothing is left. Computeds go first, so an
 *  effect reading a signal AND a computed of it runs once, with both fresh. A failing effect
 *  is reported (reportError) and never stops the others; an effect that keeps re-triggering
 *  itself is stopped after 100 runs in one flush (an effect writing what it reads). */
const _flush = () => {
    const runs = new Map();
    _batching += 1;                                           // writes made by effects join this flush
    try {
        while (_pending.size) {
            let next = null;
            for (const e of _pending) if (e.computed) { next = e; break; }
            next ??= _pending.values().next().value;
            _pending.delete(next);
            const n = (runs.get(next) ?? 0) + 1;
            runs.set(next, n);
            if (n > 100) { reportError(new Error('$.effect — an effect keeps re-triggering itself (it writes a signal it reads)')); continue; }
            try { next.run(); } catch (err) { reportError(err); }
        }
    } finally { _batching -= 1; }
};

/** A reactive value: read `.value` (inside an effect: subscribes), write `.value` (re-runs
 *  them). `persist: 'key'` keeps it in localStorage — restored now, saved on every change.
 *  Replace arrays and objects rather than mutating them: `todos.value = [...todos.value, t]`.
 *  @example const count = $.signal(0); $('#out').text(count); count.value++
 *  @example const todos = $.signal([], { persist: 'todos' })
 */
const _SIGNAL = Symbol('qry.signal');
$.signal = (value, { persist } = {}) => {
    const subs = new Set(), store = persist ? $.store('qry:signal:') : null;
    if (store) value = store.get(persist, value);
    return {
        [_SIGNAL]: true,
        get value() { if (_observer) { subs.add(_observer); _observer.deps.add(subs); } return value; },
        set value(v) {
            if (Object.is(v, value)) return;
            value = v;
            store?.set(persist, v);
            for (const e of subs) if (!e.dead) _pending.add(e);
            if (!_batching) _flush();
        },
        peek: () => value,                                   // read without subscribing
    };
};

/** @private — an effect: runs fn, tracks what it read, re-runs on change. */
const _effect = (fn, computed = false) => {
    const e = {
        deps: new Set(), dead: false, cleanup: null, computed,
        run() {
            if (this.dead) return;
            for (const d of this.deps) d.delete(this);
            this.deps.clear();
            if (typeof this.cleanup === 'function') this.cleanup();
            const prev = _observer;
            _observer = this;
            try { this.cleanup = fn(); } finally { _observer = prev; }
        },
    };
    _batching += 1;                                           // its own writes wait until it has run
    try { e.run(); } finally { if (--_batching === 0) _flush(); }
    return () => {
        e.dead = true;
        _pending.delete(e);
        for (const d of e.deps) d.delete(e);
        if (typeof e.cleanup === 'function') e.cleanup();
    };
};

/** Run fn now, and again whenever a signal it read changes. fn may return a cleanup.
 *  @returns {Function} stop
 *  @example const stop = $.effect(() => { document.title = `${todos.value.length} to do`; })
 */
$.effect = (fn) => _effect(fn);

/** A read-only signal computed from others, kept up to date (before the effects that read it).
 *  @example const left = $.computed(() => todos.value.filter(t => !t.done).length)
 */
$.computed = (fn) => {
    const s = $.signal(undefined);
    _effect(() => { s.value = fn(); }, true);
    return { [_SIGNAL]: true, get value() { return s.value; }, peek: s.peek };
};

/** Several writes, one update: the effects run once, after fn.
 *  @example $.batch(() => { first.value = 'Ada'; last.value = 'Lovelace'; })
 */
$.batch = (fn) => {
    _batching += 1;
    try { return fn(); } finally { if (--_batching === 0) _flush(); }
};

/** True for what $.signal and $.computed return.  @example $.isSignal(count) → true */
$.isSignal = (v) => v?.[_SIGNAL] === true;
const _isSignal = $.isSignal;

/** @private — a signal or a function given to a setter is followed (see _bind). */
const _isLive = (v) => typeof v === 'function' || _isSignal(v);

// ═══════════════════════════════════════════════════════════════════════════
// 21. GLOBAL EXPORT
// ═══════════════════════════════════════════════════════════════════════════

window.$ = $;
