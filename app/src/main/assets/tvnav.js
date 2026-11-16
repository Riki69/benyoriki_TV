/* Navigasi remote TV (D-pad) untuk benyoriki.com di dalam WebView. Aman dipanggil berulang. */
(function () {
  'use strict';
  if (window.__tvNav) return;
  window.__tvNav = true;

  var st = document.createElement('style');
  st.textContent =
    ':focus-visible{outline:3px solid #C9A84C !important;outline-offset:3px !important;border-radius:6px;}' +
    'html{scroll-behavior:smooth;}';
  (document.head || document.documentElement).appendChild(st);

  var SEL = 'a[href],button:not([disabled]),input:not([disabled]):not([type=hidden]),select:not([disabled]),' +
            'textarea:not([disabled]),summary,[role=button],[onclick],[tabindex]:not([tabindex="-1"])';

  function shown(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && cs.pointerEvents !== 'none';
  }

  /* Bila ada modal terbuka, fokus dibatasi di dalamnya. */
  function scope() {
    var pm = document.getElementById('pm');
    if (pm && !pm.hidden) return pm;
    var im = document.getElementById('imgModal');
    if (im && getComputedStyle(im).display !== 'none') return im;
    return document;
  }

  function list() {
    return Array.prototype.slice.call(scope().querySelectorAll(SEL)).filter(shown);
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function pick(from, dir) {
    var fr = from.getBoundingClientRect(), fc = center(fr), best = null, bs = 1e12;
    list().forEach(function (el) {
      if (el === from || from.contains(el) || el.contains(from)) return;
      var r = el.getBoundingClientRect(), c = center(r), dx = c.x - fc.x, dy = c.y - fc.y, p, s;
      if (dir === 'right') { p = r.left - fr.right > -8 ? dx : -1; s = Math.abs(dy); }
      else if (dir === 'left') { p = fr.left - r.right > -8 ? -dx : -1; s = Math.abs(dy); }
      else if (dir === 'down') { p = r.top - fr.bottom > -8 ? dy : -1; s = Math.abs(dx); }
      else { p = fr.top - r.bottom > -8 ? -dy : -1; s = Math.abs(dx); }
      if (p <= 0) return;
      var score = p + s * 2.5;
      if (score < bs) { bs = score; best = el; }
    });
    return best;
  }

  function focusEl(el) {
    el.focus({ preventScroll: true });
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'smooth' }); } catch (e) { el.scrollIntoView(); }
  }

  function isTyping(el) {
    return el && (el.tagName === 'TEXTAREA' || (el.tagName === 'INPUT' && /^(text|search|email|tel|url|password|number)$/i.test(el.type || 'text')));
  }

  var DIR = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

  document.addEventListener('keydown', function (e) {
    var d = DIR[e.key];
    var a = document.activeElement;
    if (e.key === 'Enter' && a && a !== document.body && !/^(A|BUTTON|INPUT|SELECT|TEXTAREA|SUMMARY)$/.test(a.tagName)) {
      a.click(); e.preventDefault(); return;      /* tombol OK pada elemen non-standar */
    }
    if (!d || e.defaultPrevented) return;
    if (a && (a.tagName === 'SELECT' || (a.tagName === 'INPUT' && a.type === 'range'))) return;
    if (isTyping(a) && (d === 'left' || d === 'right')) return;   /* geser kursor teks */

    if (!a || a === document.body || a === document.documentElement || !shown(a)) {
      var vis = list().filter(function (el) { var r = el.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight; });
      if (vis.length) { focusEl(vis[0]); e.preventDefault(); return; }
    } else {
      var n = pick(a, d);
      if (n) { focusEl(n); e.preventDefault(); return; }
    }
    /* tidak ada tujuan fokus: gulir halaman */
    if (d === 'down') { scrollBy({ top: innerHeight * 0.6, behavior: 'smooth' }); e.preventDefault(); }
    else if (d === 'up') { scrollBy({ top: -innerHeight * 0.6, behavior: 'smooth' }); e.preventDefault(); }
  }, true);

  /* Dipanggil oleh tombol Kembali (Android). true = sudah ditangani di halaman. */
  window.__tvBack = function () {
    var im = document.getElementById('imgModal');
    if (im && getComputedStyle(im).display !== 'none') { im.style.display = 'none'; return true; }
    var t = document.activeElement;
    if (t && isTyping(t)) { t.blur(); return true; }
    return false;
  };
})();
