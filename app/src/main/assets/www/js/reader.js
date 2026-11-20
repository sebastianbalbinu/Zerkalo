// El lector: epub.js con la tipografía, el papel y los controles flotantes de Zerkalo.
import * as db from './db.js';
import { fileUrl, keepAwake, share, setReminderBook } from './native.js';
import { ic, enso } from './icons.js';
import { esc } from './covers.js';
import { minutesOn } from './library.js';

const FONTS = [
  { id: 'Shippori Mincho', name: 'Mincho' }, { id: 'Literata', name: 'Literata' },
  { id: 'EB Garamond', name: 'Garamond' }, { id: 'Zen Kaku Gothic New', name: 'Gótica' },
];
const MARGINS = { s: 34, m: 52, l: 76 };
const HL = [
  { id: 'kin', fill: 'rgba(226, 190, 82, .45)', dark: 'rgba(226, 190, 82, .30)' },
  { id: 'koke', fill: 'rgba(120, 140, 80, .38)', dark: 'rgba(160, 182, 112, .30)' },
  { id: 'shu', fill: 'rgba(196, 67, 42, .26)', dark: 'rgba(220, 96, 70, .30)' },
  { id: 'ai', fill: 'rgba(70, 95, 140, .30)', dark: 'rgba(120, 150, 200, .30)' },
];
const DEFAULTS = { font: 'Shippori Mincho', size: 18, lh: 1.6, margin: 'm', justify: true };
const css = v => getComputedStyle(document.documentElement).getPropertyValue(v).trim();

export async function openReader(book, ui) {
  let settings = Object.assign({}, DEFAULTS, await db.kv.get('reader', {}));
  const v = ui.view(`
    <div class="reader" id="rd">
      <div class="pages" id="pages"></div>
      <div class="loader" id="ld">${enso()}<div class="cap">Abriendo el libro</div></div>
      <div class="ribbon-mark" id="rib"></div>
      <div class="pblur top ctrl"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><b></b></div>
      <div class="top-bar ctrl">
        <button class="circle glassy" data-act="close" aria-label="Volver">${ic('back', 20)}</button>
        <div class="title" id="chap">${esc(book.title)}</div>
      </div>
      <div class="pblur bottom ctrl"><i></i><i></i><i></i><i></i><i></i><i></i><i></i><b></b></div>
      <div class="bottom-bar ctrl">
        <div class="pills-row">
          <button class="rpill" data-act="clock" aria-label="Tiempo leído hoy">${ic('clock', 19, 1.7)}<span id="mins">0 min</span></button>
          <div style="flex:1"></div>
          <button class="rpill icon" data-act="toc" aria-label="Índice y marcas">${ic('toc', 20)}</button>
          <button class="rpill icon" data-act="theme" aria-label="Tema" id="thm">${ic(ui.isDark() ? 'sun' : 'moon', 21)}</button>
          <button class="rpill icon" data-act="type" aria-label="Tipografía">${ic('aa', 21)}</button>
          <button class="rpill icon shiori" data-act="shiori" aria-label="Shiori" id="shb">${ic('shiori', 20)}</button>
        </div>
        <div class="meta"><div class="progress"><i id="bar" style="width:${Math.round((book.progress || 0) * 100)}%"></i></div><span id="left"></span></div>
      </div>
      <div class="mini-meta" id="mini"></div>
      <div class="hl-bar" id="hlb">
        ${HL.map(h => `<button class="dot" data-hl="${h.id}" style="background:${h.fill}" aria-label="Subrayar"></button>`).join('')}
        <button class="act" data-act="hl-note">${ic('note', 16)}</button>
        <button class="act" data-act="hl-copy">${ic('copy', 16)}</button>
      </div>
    </div>`, { tabbar: false, cls: 'reader-view' });

  const $ = s => v.querySelector(s);
  const rd = $('#rd'), pagesEl = $('#pages');
  let cleanupFn = null;
  ui.setCleanup(() => cleanupFn && cleanupFn(), v);
  keepAwake(true);
  setReminderBook(book.title);

  book.lastOpened = Date.now();
  await db.books.put(book);

  const epub = window.ePub(fileUrl(book.file), { openAs: 'epub' });
  let rendition, cfi = book.cfi || '', lastLoc = null, toc = [], destroyed = false;
  let marks = (await db.notes.byBook(book.id));
  let pendingSel = null;

  // ---------- tiempo de lectura ----------
  const baseToday = await minutesOn(db.today());
  let activeMs = 0, lastTick = Date.now(), lastAct = Date.now(), paused = false;
  let secPerPage = (await db.kv.get('spp', 55)), lastTurn = Date.now();
  const tick = () => {
    const now = Date.now();
    if (!paused && now - lastAct < 5 * 60000) activeMs += now - lastTick;
    lastTick = now;
    const m = baseToday + Math.round(activeMs / 60000);
    $('#mins').textContent = m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60}`;
  };
  const timer = setInterval(tick, 15000);
  tick();
  async function saveSession() {
    tick();
    if (activeMs >= 30000) await db.sessions.add({ bookId: book.id, date: db.today(), ms: activeMs, at: Date.now() });
    activeMs = 0;
  }
  ui.onPause = () => { paused = true; saveSession(); saveProgress(); };
  ui.onResume = () => { paused = false; lastTick = Date.now(); lastAct = Date.now(); };

  // ---------- estilo dentro del libro ----------
  function bookCSS() {
    const dark = ui.isDark();
    return `
      html { -webkit-text-size-adjust: 100%; color-scheme: ${dark ? 'dark' : 'light'}; }
      html, body { background: transparent !important; color: ${css('--text')} !important; }
      body { font-family: '${settings.font}', 'Shippori Mincho', Georgia, serif !important; font-size: ${settings.size}px !important;
        line-height: ${settings.lh} !important; text-align: ${settings.justify ? 'justify' : 'left'} !important;
        hyphens: auto !important; -webkit-hyphens: auto !important; text-rendering: optimizeLegibility; font-kerning: normal;
        -webkit-font-smoothing: antialiased; widows: 2; orphans: 2; }
      body * { font-family: inherit !important; color: inherit !important; background-color: transparent !important; line-height: inherit !important; }
      p, div, li, blockquote { font-size: 1em !important; }
      h1, h2, h3, h4 { line-height: 1.25 !important; text-align: center !important; hyphens: manual !important; font-weight: 600 !important; }
      a { color: ${css('--accent')} !important; text-decoration: none !important; }
      img, svg, image { max-width: 100% !important; height: auto; ${dark ? 'filter: brightness(.88);' : ''} }
      ::selection { background: ${dark ? 'rgba(196,67,42,.35)' : 'rgba(196,67,42,.22)'}; }
      hr { border: 0; height: 1px; background: ${css('--line')}; margin: 1.4em 25%; }
      pre, code { white-space: pre-wrap !important; }`;
  }
  function styleContents(c) {
    const d = c.document;
    d.documentElement.lang = d.documentElement.lang || book.lang || 'es';
    let st = d.getElementById('zk-style');
    if (!st) {
      const link = d.createElement('link'); link.rel = 'stylesheet'; link.href = new URL('fonts/fonts.css', location.href).href;
      d.head.appendChild(link);
      st = d.createElement('style'); st.id = 'zk-style'; d.head.appendChild(st);
    }
    st.textContent = bookCSS();
  }

  // ---------- gestos dentro del libro ----------
  function attachTouch(c) {
    const d = c.document; let sx = 0, sy = 0, st = 0;
    d.addEventListener('touchstart', e => { const t = e.changedTouches[0]; sx = t.screenX; sy = t.screenY; st = Date.now(); lastAct = Date.now(); }, { passive: true });
    d.addEventListener('touchend', e => {
      const sel = d.getSelection && d.getSelection();
      if (sel && !sel.isCollapsed) return;
      const t = e.changedTouches[0]; const dx = t.screenX - sx, dy = t.screenY - sy, dt = Date.now() - st;
      if (Math.abs(dx) > 45 && Math.abs(dx) > Math.abs(dy) * 1.3 && dt < 800) { dx < 0 ? next() : prev(); return; }
      if (Math.abs(dx) < 12 && Math.abs(dy) < 12 && dt < 400) {
        if (e.target.closest && e.target.closest('a')) return;
        const x = t.screenX / (screen.width || window.innerWidth);
        if (x < 0.26) prev(); else if (x > 0.74) next(); else toggleCalm();
      }
    }, { passive: true });
    d.addEventListener('keydown', onKey);
  }

  function toggleCalm(force) {
    const calm = force ?? !rd.classList.contains('calm');
    rd.classList.toggle('calm', calm);
    hideHl();
  }
  function next() { lastAct = Date.now(); hideHl(); rendition && rendition.next(); }
  function prev() { lastAct = Date.now(); hideHl(); rendition && rendition.prev(); }
  function onKey(e) {
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') next();
    if (e.key === 'ArrowLeft' || e.key === 'PageUp') prev();
  }
  document.addEventListener('keydown', onKey);

  // ---------- abrir ----------
  async function render(at) {
    if (rendition) { try { rendition.destroy(); } catch (e) {} }
    pagesEl.innerHTML = '';
    rendition = epub.renderTo(pagesEl, {
      width: '100%', height: '100%', flow: 'paginated', spread: 'none', minSpreadWidth: 99999,
      gap: MARGINS[settings.margin] || 52, allowScriptedContent: false,
    });
    rendition.hooks.content.register(c => { styleContents(c); attachTouch(c); });
    rendition.on('relocated', onRelocated);
    rendition.on('selected', (range, c) => onSelected(range, c));
    rendition.on('markClicked', (r, data) => onMarkClicked(r, data));
    await rendition.display(at || undefined);
    marks.filter(m => m.type === 'hl').forEach(addHl);
  }

  try {
    await epub.ready;
    const nav = await epub.loaded.navigation;
    toc = flatToc(nav.toc || []);
    const saved = await db.kv.get('loc:' + book.id, null);
    if (saved) { try { epub.locations.load(saved); } catch (e) {} }
    await render(cfi);
    $('#ld').style.opacity = 0; setTimeout(() => $('#ld')?.remove(), 500);
    setTimeout(() => toggleCalm(true), 1600);
    if (!epub.locations.length()) {
      epub.locations.generate(1400).then(() => {
        if (destroyed) return;
        db.kv.set('loc:' + book.id, epub.locations.save());
        if (lastLoc) onRelocated(lastLoc);
      }).catch(() => {});
    }
  } catch (e) {
    $('#ld').innerHTML = `<div class="empty"><h3>No se pudo abrir</h3><p>El archivo parece dañado o incompleto. Puedes borrarlo y descargarlo de nuevo.</p></div>`;
  }

  function flatToc(items, depth = 0, out = []) {
    items.forEach(it => { out.push({ label: (it.label || '').trim(), href: it.href, depth }); if (it.subitems) flatToc(it.subitems, depth + 1, out); });
    return out;
  }
  function chapterOf(href) {
    const base = (href || '').split('#')[0];
    let best = null;
    toc.forEach(t => { if (t.href && t.href.split('#')[0].endsWith(base.split('/').pop())) best = best || t; });
    return best;
  }

  let saveT;
  function saveProgress() {
    if (!lastLoc) return;
    book.cfi = cfi; book.lastOpened = Date.now();
    db.books.put(book);
  }
  function onRelocated(loc) {
    if (destroyed || !v.isConnected) return;
    lastLoc = loc; cfi = loc.start.cfi;
    const now = Date.now(), dt = (now - lastTurn) / 1000; lastTurn = now;
    if (dt > 6 && dt < 240) { secPerPage = secPerPage * 0.85 + dt * 0.15; db.kv.set('spp', secPerPage); }
    let pct = loc.start.percentage;
    if (epub.locations.length()) pct = epub.locations.percentageFromCfi(cfi);
    if (loc.atEnd) book.progress = 1; else if (typeof pct === 'number' && !isNaN(pct)) book.progress = pct;
    $('#bar').style.width = Math.round((book.progress || 0) * 100) + '%';
    const disp = loc.start.displayed || {};
    const left = Math.max(0, (disp.total || 1) - (disp.page || 1));
    const mins = Math.ceil(left * secPerPage / 60);
    $('#left').textContent = left === 0 ? 'Última página del capítulo' : mins <= 1 ? 'Menos de 1 min para cerrar el capítulo' : `${mins} min para cerrar el capítulo`;
    $('#mini').textContent = `${Math.round((book.progress || 0) * 100)} %`;
    const ch = chapterOf(loc.start.href);
    $('#chap').textContent = ch ? ch.label : book.title;
    $('#rib').classList.toggle('on', !!currentShiori());
    $('#shb').classList.toggle('on', !!currentShiori());
    clearTimeout(saveT); saveT = setTimeout(saveProgress, 800);
    if (loc.atEnd && !book.finishedAt) finished();
  }

  // ---------- shiori (marcapáginas) ----------
  function currentShiori() {
    if (!lastLoc) return null;
    const s = lastLoc.start.cfi, e = lastLoc.end.cfi;
    return marks.find(m => m.type === 'shiori' && epub.locations && cmp(m.cfi, s) >= 0 && cmp(m.cfi, e) <= 0);
  }
  function cmp(a, b) { try { return new window.ePub.CFI().compare(a, b); } catch (e) { return a === b ? 0 : -1; } }
  async function toggleShiori() {
    const cur = currentShiori();
    if (cur) { await db.notes.del(cur.id); marks = marks.filter(m => m !== cur); ui.toast('Shiori retirado'); }
    else {
      const ch = chapterOf(lastLoc?.start.href);
      const m = { id: 'n' + Date.now(), bookId: book.id, type: 'shiori', cfi, label: ch ? ch.label : '', pct: book.progress || 0, at: Date.now() };
      await db.notes.put(m); marks.push(m); ui.toast('Shiori puesto en esta página');
    }
    onRelocated(lastLoc);
  }

  // ---------- subrayados ----------
  function hlFill(id) { const h = HL.find(x => x.id === id) || HL[0]; return ui.isDark() ? h.dark : h.fill; }
  function addHl(m) {
    try {
      rendition.annotations.add('highlight', m.cfi, { id: m.id }, null, 'zk-hl',
        { fill: hlFill(m.color), 'fill-opacity': '1', 'mix-blend-mode': ui.isDark() ? 'normal' : 'multiply' });
    } catch (e) {}
  }
  function onSelected(range, c) {
    pendingSel = { range, c };
    $('#hlb').classList.add('show');
  }
  function hideHl() { $('#hlb')?.classList.remove('show'); pendingSel = null; }
  async function saveHl(color, note = '') {
    if (!pendingSel) return;
    const { range, c } = pendingSel;
    let text = '';
    try { text = (await epub.getRange(range)).toString(); } catch (e) {}
    const ch = chapterOf(lastLoc?.start.href);
    const m = { id: 'n' + Date.now(), bookId: book.id, type: 'hl', cfi: range, text, color, note, chapter: ch ? ch.label : '', at: Date.now() };
    await db.notes.put(m); marks.push(m); addHl(m);
    try { c.window.getSelection().removeAllRanges(); } catch (e) {}
    hideHl();
    return m;
  }
  function onMarkClicked(range) {
    const m = marks.find(x => x.type === 'hl' && x.cfi === range); if (!m) return;
    const s = ui.sheet(`
      <h3>Subrayado</h3>
      <div style="font-size:16px;line-height:1.6;border-left:3px solid ${hlFill(m.color)};padding-left:12px">${esc(m.text)}</div>
      ${m.note ? `<div class="g muted" style="margin-top:12px;font-size:14px">${esc(m.note)}</div>` : ''}
      <div class="row" style="margin-top:20px"><button class="btn ghost small" data-x="share">${ic('share', 16)} Compartir</button>
      <button class="btn ghost small" data-x="del">${ic('trash', 16)} Quitar</button></div>`);
    s.on('share', () => share(quote(m)));
    s.on('del', async () => { await db.notes.del(m.id); marks = marks.filter(x => x !== m); rendition.annotations.remove(m.cfi, 'highlight'); s.close(); });
  }
  function quote(m) { return `«${m.text.trim()}»\n— ${book.author ? book.author + ', ' : ''}${book.title}${m.chapter ? ' (' + m.chapter + ')' : ''}\n\nLeído en Zerkalo`; }

  v.querySelectorAll('[data-hl]').forEach(b => b.addEventListener('click', () => saveHl(b.dataset.hl)));
  v.addEventListener('click', async e => {
    const a = e.target.closest('[data-act]'); if (!a) return;
    lastAct = Date.now();
    switch (a.dataset.act) {
      case 'close': ui.back(); break;
      case 'shiori': toggleShiori(); break;
      case 'theme': ui.toggleTheme(); $('#thm').innerHTML = ic(ui.isDark() ? 'sun' : 'moon', 21); restyle(); break;
      case 'type': typeSheet(); break;
      case 'toc': tocSheet(); break;
      case 'clock': ui.toast(`Hoy llevas ${$('#mins').textContent} de lectura`); break;
      case 'hl-copy': {
        if (!pendingSel) break;
        let t = ''; try { t = (await epub.getRange(pendingSel.range)).toString(); } catch (_) {}
        navigator.clipboard?.writeText(t).catch(() => {}); ui.toast('Copiado'); hideHl(); break;
      }
      case 'hl-note': {
        if (!pendingSel) break;
        const keep = pendingSel;
        const s = ui.sheet(`<h3>Nota</h3><textarea id="nt" rows="4" style="width:100%;border:0;border-radius:14px;background:var(--chip);padding:12px;font:16px var(--serif);color:var(--text);outline:none;resize:none" placeholder="Lo que te hizo pensar…"></textarea>
          <button class="btn block" style="margin-top:14px" data-x="ok">Guardar nota</button>`);
        setTimeout(() => s.el.querySelector('#nt').focus(), 300);
        s.on('ok', async () => { pendingSel = keep; await saveHl('koke', s.el.querySelector('#nt').value.trim()); s.close(); ui.toast('Nota guardada'); });
        break;
      }
    }
  });

  function restyle() {
    try { rendition.getContents().forEach(styleContents); } catch (e) {}
    try { marks.filter(m => m.type === 'hl').forEach(m => { rendition.annotations.remove(m.cfi, 'highlight'); addHl(m); }); } catch (e) {}
  }

  async function saveSettings(reflow) {
    await db.kv.set('reader', settings);
    if (reflow) { const at = cfi; await render(at); } else restyle();
  }

  function typeSheet() {
    const s = ui.sheet(`
      <h3>Lectura</h3>
      <div class="field-label">Letra</div>
      <div class="seg" id="ff">${FONTS.map(f => `<button data-f="${f.id}" class="${settings.font === f.id ? 'on' : ''}" style="font-family:'${f.id}'">${f.name}</button>`).join('')}</div>
      <div class="field-label">Tamaño <small id="szv">${settings.size} px</small></div>
      <div class="row"><button class="circle" data-x="smaller" aria-label="Más pequeña">${ic('minus', 18)}</button>
        <input class="range" type="range" min="14" max="28" step="1" value="${settings.size}" id="sz" aria-label="Tamaño de letra">
        <button class="circle" data-x="bigger" aria-label="Más grande">${ic('plus', 18)}</button></div>
      <div class="field-label">Interlineado</div>
      <div class="seg" id="lh">${[[1.45, 'Compacto'], [1.6, 'Medio'], [1.8, 'Amplio']].map(([x, n]) => `<button data-v="${x}" class="${settings.lh === x ? 'on' : ''}">${n}</button>`).join('')}</div>
      <div class="field-label">Márgenes</div>
      <div class="seg" id="mg">${[['s', 'Estrechos'], ['m', 'Medios'], ['l', 'Amplios']].map(([x, n]) => `<button data-v="${x}" class="${settings.margin === x ? 'on' : ''}">${n}</button>`).join('')}</div>
      <div class="list-item" style="margin-top:12px"><div class="grow"><div class="t">Justificar el texto</div><div class="s">Con guiones, como un libro impreso</div></div>
        <button class="switch ${settings.justify ? 'on' : ''}" id="jt" aria-label="Justificar"></button></div>
      <div class="field-label">Papel</div>
      <div class="seg" id="tm">${[['light', 'Crema'], ['dark', 'Nogal'], ['oled', 'Sumi'], ['system', 'Sistema']].map(([x, n]) => `<button data-v="${x}" class="${ui.themePref() === x ? 'on' : ''}">${n}</button>`).join('')}</div>`);
    const segs = (sel, fn) => s.el.querySelectorAll(sel + ' button').forEach(b => b.addEventListener('click', () => {
      s.el.querySelectorAll(sel + ' button').forEach(x => x.classList.toggle('on', x === b)); fn(b);
    }));
    segs('#ff', b => { settings.font = b.dataset.f; saveSettings(true); });
    segs('#lh', b => { settings.lh = parseFloat(b.dataset.v); saveSettings(true); });
    segs('#mg', b => { settings.margin = b.dataset.v; saveSettings(true); });
    segs('#tm', b => { ui.setTheme(b.dataset.v); $('#thm').innerHTML = ic(ui.isDark() ? 'sun' : 'moon', 21); restyle(); });
    const sz = s.el.querySelector('#sz');
    let szT;
    const setSize = n => { settings.size = Math.max(14, Math.min(28, n)); sz.value = settings.size; s.el.querySelector('#szv').textContent = settings.size + ' px'; clearTimeout(szT); szT = setTimeout(() => saveSettings(true), 350); };
    sz.addEventListener('input', () => setSize(parseInt(sz.value, 10)));
    s.on('smaller', () => setSize(settings.size - 1));
    s.on('bigger', () => setSize(settings.size + 1));
    s.el.querySelector('#jt').addEventListener('click', e => { settings.justify = !settings.justify; e.currentTarget.classList.toggle('on', settings.justify); saveSettings(true); });
  }

  function tocSheet() {
    const cur = lastLoc ? chapterOf(lastLoc.start.href) : null;
    const shioris = marks.filter(m => m.type === 'shiori').sort((a, b) => a.pct - b.pct);
    const hls = marks.filter(m => m.type === 'hl').sort((a, b) => cmp(a.cfi, b.cfi));
    const s = ui.sheet(`
      <div class="seg" id="tt"><button class="on" data-v="toc">Índice</button><button data-v="marks">Marcas · ${shioris.length + hls.length}</button></div>
      <div class="toc" id="tocl" style="margin-top:8px">${toc.map((t, i) => `<button class="ti ${t.depth ? 'sub' : ''} ${cur && cur.href === t.href ? 'cur' : ''}" data-i="${i}">${esc(t.label)}</button>`).join('') || '<p class="g muted">Este libro no trae índice.</p>'}</div>
      <div class="toc hidden" id="mkl" style="margin-top:8px">
        ${shioris.map(m => `<button class="ti" data-cfi="${esc(m.cfi)}"><span style="color:var(--accent)">${ic('shiori', 15)}</span> ${esc(m.label || 'Shiori')} <span class="g muted" style="font-size:12px">· ${Math.round(m.pct * 100)} %</span></button>`).join('')}
        ${hls.map(m => `<button class="ti" data-cfi="${esc(m.cfi)}"><span style="border-left:3px solid ${hlFill(m.color)};padding-left:10px;display:block">${esc(m.text.slice(0, 180))}${m.note ? `<span class="g muted" style="display:block;font-size:12.5px;margin-top:4px">${esc(m.note)}</span>` : ''}</span></button>`).join('')}
        ${shioris.length + hls.length === 0 ? '<p class="g muted" style="line-height:1.6">Aún no hay marcas. Pon un shiori con la cinta o mantén presionado el texto para subrayar.</p>' : ''}
        ${hls.length ? `<button class="btn ghost small" style="margin-top:14px" data-x="export">${ic('share', 16)} Exportar subrayados</button>` : ''}
      </div>`);
    s.el.querySelectorAll('#tt button').forEach(b => b.addEventListener('click', () => {
      s.el.querySelectorAll('#tt button').forEach(x => x.classList.toggle('on', x === b));
      s.el.querySelector('#tocl').classList.toggle('hidden', b.dataset.v !== 'toc');
      s.el.querySelector('#mkl').classList.toggle('hidden', b.dataset.v === 'toc');
    }));
    s.el.querySelectorAll('[data-i]').forEach(b => b.addEventListener('click', () => { rendition.display(toc[+b.dataset.i].href); s.close(); toggleCalm(true); }));
    s.el.querySelectorAll('[data-cfi]').forEach(b => b.addEventListener('click', () => { rendition.display(b.dataset.cfi); s.close(); toggleCalm(true); }));
    s.on('export', () => share(exportText(book, hls)));
  }

  async function finished() {
    book.finishedAt = Date.now(); book.progress = 1;
    await db.books.put(book);
    const s = ui.sheet(`
      <div style="display:flex;flex-direction:column;align-items:center;text-align:center;padding:10px 0 4px">
        <div class="big-seal" style="width:110px;height:110px;font-size:17px">読了</div>
        <h3 style="margin-top:18px">Terminaste ${esc(book.title)}</h3>
        <p class="g muted" style="font-size:14px;line-height:1.6;margin:0 0 18px">Su sello quedó en tu cuaderno.</p>
        <button class="btn block" data-x="ok">Gracias</button></div>`);
    s.on('ok', () => s.close());
  }

  cleanupFn = () => {
    destroyed = true;
    clearInterval(timer);
    document.removeEventListener('keydown', onKey);
    saveSession(); saveProgress();
    keepAwake(false);
    ui.onPause = ui.onResume = null;
    try { rendition && rendition.destroy(); } catch (e) {}
    try { epub.destroy(); } catch (e) {}
  };
  if (destroyed) cleanupFn();
  return cleanupFn;
}

export function exportText(book, hls) {
  const year = new Date().getFullYear();
  const head = `${book.author || 'Autor desconocido'}. ${book.title}. ${book.source || ''} (consultado en ${year}).`;
  return `${book.title}\n${book.author || ''}\n\n` + hls.map(m => `«${m.text.trim()}»${m.chapter ? `\n(${m.chapter})` : ''}${m.note ? `\nNota: ${m.note}` : ''}`).join('\n\n') +
    `\n\nCita: ${head}\n\nSubrayados exportados desde Zerkalo`;
}
