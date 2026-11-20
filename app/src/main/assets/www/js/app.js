// Zerkalo · 鏡 — la app
import * as db from './db.js';
import * as N from './native.js';
import { ic, enso } from './icons.js';
import { coverHTML, itemCoverHTML, bindingHTML, defaultBinding, WASHI, THREAD, PATTERNS, LABELS, esc } from './covers.js';
import { gutenberg, wikisource, oapen, standard, opds, LANGS, TOPICS, LIBRARIES } from './catalogs.js';
import { addFromCatalog, addImported, removeBook, bookDays, findByKey } from './library.js';
import { openReader, exportText } from './reader.js';

const app = document.getElementById('app');
const tabbar = document.getElementById('tabbar');
const stack = [];
let currentTab = 'home';
let openSheet = null;
const PBLUR = '<i></i><i></i><i></i><i></i><i></i><i></i><i></i><b></b>';

// ---------------------------------------------------------------- tema
let themePref = 'system';
try { themePref = localStorage.getItem('zk-theme') || 'system'; } catch (e) {}
function isDark() { return themePref === 'dark' || (themePref === 'system' && N.systemDark()); }
function applyTheme() {
  const d = isDark();
  document.documentElement.classList.toggle('dark', d);
  document.querySelector('meta[name=theme-color]').content = d ? '#2E231D' : '#F7F2E8';
  N.setDarkBars(d);
}
function setTheme(p) { themePref = p; try { localStorage.setItem('zk-theme', p); } catch (e) {} applyTheme(); }
window.__onSystemTheme = () => applyTheme();
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', applyTheme);
applyTheme();

// ---------------------------------------------------------------- vistas, hojas y avisos
const ui = {
  isDark, setTheme, themePref: () => themePref,
  toggleTheme() { setTheme(isDark() ? 'light' : 'dark'); },
  onPause: null, onResume: null,
  view(html, { tabbar: showTab = false, cls = '', root = false } = {}) {
    const el = document.createElement('div');
    el.className = 'view ' + cls + (root ? ' fade-enter' : ' enter');
    el.innerHTML = html;
    if (root) { stack.splice(0).forEach(v => { v.cleanup && v.cleanup(); v.el.remove(); }); }
    else if (stack.length) stack[stack.length - 1].el.style.visibility = 'hidden';
    app.appendChild(el);
    stack.push({ el, cleanup: null, refresh: null, tab: showTab });
    tabbar.classList.toggle('away', !showTab);
    return el;
  },
  setCleanup(fn, el) { const e = el ? stack.find(s => s.el === el) : stack[stack.length - 1]; if (e) e.cleanup = fn; },
  setRefresh(fn) { stack[stack.length - 1].refresh = fn; },
  back() {
    if (openSheet) { openSheet.close(); return true; }
    if (stack.length > 1) {
      const top = stack.pop();
      top.cleanup && top.cleanup();
      top.el.classList.remove('enter'); top.el.classList.add('leave');
      setTimeout(() => top.el.remove(), 320);
      const prev = stack[stack.length - 1];
      prev.el.style.visibility = '';
      tabbar.classList.toggle('away', !prev.tab);
      prev.refresh && prev.refresh();
      return true;
    }
    if (currentTab !== 'home') { go('home'); return true; }
    return false;
  },
  sheet(html) {
    if (openSheet) openSheet.close(true);
    const back = document.createElement('div'); back.className = 'sheet-back';
    const el = document.createElement('div'); el.className = 'sheet';
    el.innerHTML = `<div class="grabber"></div>` + html;
    document.body.append(back, el);
    requestAnimationFrame(() => requestAnimationFrame(() => { back.classList.add('show'); el.classList.add('show'); }));
    const handlers = {};
    el.addEventListener('click', e => { const x = e.target.closest('[data-x]'); if (x && handlers[x.dataset.x]) handlers[x.dataset.x](x, e); });
    const s = {
      el,
      on(name, fn) { handlers[name] = fn; return s; },
      close(instant) {
        if (openSheet === s) openSheet = null;
        back.classList.remove('show'); el.classList.remove('show');
        setTimeout(() => { back.remove(); el.remove(); }, instant ? 0 : 450);
        s.onClose && s.onClose();
      },
    };
    back.addEventListener('click', () => s.close());
    // deslizar hacia abajo para cerrar
    let sy = null;
    el.addEventListener('touchstart', e => { if (el.scrollTop <= 0) sy = e.touches[0].clientY; }, { passive: true });
    el.addEventListener('touchmove', e => { if (sy == null) return; const dy = e.touches[0].clientY - sy; if (dy > 0) el.style.transform = `translateY(${dy}px)`; }, { passive: true });
    el.addEventListener('touchend', e => { if (sy == null) return; const dy = e.changedTouches[0].clientY - sy; sy = null; el.style.transform = ''; if (dy > 110) s.close(); });
    openSheet = s;
    return s;
  },
  toast(msg) {
    const t = document.getElementById('toast');
    t.textContent = msg; t.classList.add('show');
    clearTimeout(ui._tt); ui._tt = setTimeout(() => t.classList.remove('show'), 2400);
  },
};
window.__back = () => ui.back();
window.__onPause = () => ui.onPause && ui.onPause();
window.__onResume = () => { ui.onResume && ui.onResume(); };
window.__importedFromIntent = async payload => {
  const b = await addImported(payload);
  ui.toast(`«${b.title}» llegó a tu biblioteca`);
  openBook(b);
};

// ---------------------------------------------------------------- barra de pestañas
const TABS = [['home', 'Biblioteca'], ['tansu', 'Tansu'], ['taller', 'Taller'], ['cuaderno', 'Cuaderno']];
tabbar.innerHTML = TABS.map(([k, n]) => `<button class="tab" data-tab="${k}">${ic(k, 23, 1.6)}<span>${n}</span></button>`).join('');
tabbar.addEventListener('click', e => { const t = e.target.closest('[data-tab]'); if (t) go(t.dataset.tab); });
function markTab(k) { tabbar.querySelectorAll('.tab').forEach(t => t.classList.toggle('on', t.dataset.tab === k)); }

async function go(tab) {
  if (openSheet) openSheet.close(true);
  if (tab === 'taller') {
    const bs = await db.books.all();
    const b = bs.sort((a, b) => (b.lastOpened || b.addedAt) - (a.lastOpened || a.addedAt))[0];
    if (!b) { ui.toast('Primero agrega un libro para encuadernarlo'); return; }
    return openTaller(b);
  }
  currentTab = tab; markTab(tab);
  if (tab === 'home') return renderHome();
  if (tab === 'tansu') return renderTansu();
  if (tab === 'cuaderno') return renderCuaderno();
}

// ---------------------------------------------------------------- Biblioteca
const fmtDate = () => new Date().toLocaleDateString('es', { weekday: 'long', day: 'numeric', month: 'long' });
function shelfHTML(title, count, books, { add = false } = {}) {
  return `
    <div class="section-title"><h2>${esc(title)}</h2><span class="more">${count}</span></div>
    <div class="shelf">
      <div class="books">${books.map(b => `<button data-book="${b.id}" aria-label="${esc(b.title)}">${coverHTML(b, 104, 150)}</button>`).join('')}
        ${add ? `<button class="add-slot" data-act="import">${ic('plus', 22)}<span>Importar EPUB</span></button>` : ''}<div style="width:8px;flex:none"></div></div>
      <div class="plank"><div class="top"></div><div class="front"></div></div>
    </div>`;
}
async function renderHome() {
  const v = ui.view(`<div class="scroll" id="sc"></div><div class="pblur top soft" style="height:calc(var(--sat) + 30px)">${PBLUR}</div><div class="pblur bottom" style="height:calc(var(--sab) + 130px)">${PBLUR}</div>`, { tabbar: true, root: true });
  const sc = v.querySelector('#sc');
  let filter = '';
  async function draw() {
    const all = (await db.books.all()).sort((a, b) => (b.lastOpened || b.addedAt) - (a.lastOpened || a.addedAt));
    const q = filter.toLowerCase();
    const list = q ? all.filter(b => (b.title + ' ' + b.author).toLowerCase().includes(q)) : all;
    const reading = list.filter(b => b.progress > 0 && !b.finishedAt);
    const unread = list.filter(b => !(b.progress > 0) && !b.finishedAt);
    const done = list.filter(b => b.finishedAt);
    const cur = reading[0];
    let html = `
      <div class="head"><div><div class="cap">${fmtDate()}</div><h1 class="big">Biblioteca</h1></div>
        <div class="row"><button class="circle" data-act="search" aria-label="Buscar en mi biblioteca">${ic('search', 20)}</button>
        <button class="circle" data-act="settings" aria-label="Ajustes">${ic('gear', 20)}</button></div></div>
      <div class="search ${filter ? '' : 'hidden'}" id="fs">${ic('search', 18)}<input id="fq" placeholder="Título o autor" value="${esc(filter)}"></div>`;
    if (!all.length) {
      html += `<div class="card" style="margin:24px 16px 0"><div class="empty"><img class="enso-img logo-light" src="img/enso-ink.png" alt=""><img class="enso-img logo-dark" src="img/enso-cream.png" alt="">
        <h3>Tu estante está esperando</h3><p>En el Tansu están las bibliotecas libres del mundo. También puedes traer tus propios EPUB.</p>
        <div class="row" style="justify-content:center;flex-wrap:wrap"><button class="btn" data-act="tansu">Abrir el Tansu</button><button class="btn ghost" data-act="import">Importar EPUB</button></div></div></div>`;
    } else {
      if (cur && !q) {
        const pct = Math.round((cur.progress || 0) * 100);
        html += `<div class="card continue">
          <div class="top"><button data-book="${cur.id}" aria-label="${esc(cur.title)}">${coverHTML(cur, 92, 132, { ribbon: true })}</button>
            <div class="info"><div class="cap accent">Leyendo</div><div class="t">${esc(cur.title)}</div>
              <div class="g muted" style="font-size:13px">${esc(cur.author)}</div>
              <div class="row" style="margin-top:auto"><div class="progress"><i style="width:${pct}%"></i></div><span class="g muted" style="font-size:12px">${pct} %</span></div></div></div>
          <button class="btn block" data-open="${cur.id}" style="justify-content:space-between">Seguir leyendo <span style="font-size:18px">›</span></button></div>`;
      }
      if (reading.length > (cur && !q ? 1 : 0)) html += shelfHTML('Leyendo', reading.length + ' libros', q ? reading : reading.slice(1));
      if (unread.length) html += shelfHTML('Por leer', unread.length + (unread.length === 1 ? ' libro' : ' libros'), unread, { add: !q });
      else if (!q) html += shelfHTML('Por leer', '', [], { add: true });
      if (done.length) html += shelfHTML('Terminados', done.length + (done.length === 1 ? ' libro' : ' libros'), done);
      if (q && !list.length) html += `<div class="empty"><p>Nada con «${esc(filter)}» en tu estante.</p></div>`;
    }
    sc.innerHTML = html;
    const fq = sc.querySelector('#fq');
    fq && fq.addEventListener('input', () => { filter = fq.value; clearTimeout(draw._t); draw._t = setTimeout(async () => { await draw(); const f = sc.querySelector('#fq'); f.focus(); f.setSelectionRange(f.value.length, f.value.length); }, 250); });
  }
  sc.addEventListener('click', async e => {
    const b = e.target.closest('[data-book]'); if (b) return openBook(await db.books.get(b.dataset.book));
    const o = e.target.closest('[data-open]'); if (o) return read(await db.books.get(o.dataset.open));
    const a = e.target.closest('[data-act]'); if (!a) return;
    if (a.dataset.act === 'search') { const fs = sc.querySelector('#fs'); fs.classList.toggle('hidden'); if (!fs.classList.contains('hidden')) fs.querySelector('input').focus(); else { filter = ''; draw(); } }
    if (a.dataset.act === 'settings') renderSettings();
    if (a.dataset.act === 'tansu') go('tansu');
    if (a.dataset.act === 'import') importEpub();
  });
  ui.setRefresh(draw);
  await draw();
}

async function importEpub() {
  const f = await N.pickEpub(); if (!f) return;
  ui.toast('Leyendo el libro…');
  const b = await addImported(f);
  ui.toast(`«${b.title}» llegó a tu biblioteca`);
  if (currentTab === 'home' && stack.length === 1) stack[0].refresh && stack[0].refresh();
}

// ---------------------------------------------------------------- Libro
async function openBook(book) {
  if (!book) return;
  const washi = (WASHI.find(w => w.id === (book.binding || {}).washi) || WASHI[0]).hex;
  const v = ui.view(`
    <div class="book-hero" id="hero" style="background:linear-gradient(180deg, var(--tarjeta), color-mix(in srgb, var(--tarjeta) 35%, transparent) 70%, transparent)"></div>
    <div class="scroll" id="sc" style="padding-bottom:calc(var(--sab) + 110px)"></div>
    <div class="pblur bottom" style="height:calc(var(--sab) + 120px)">${PBLUR}</div>
    <div class="floating-actions"><button class="btn" data-act="read" id="rb">Empezar a leer</button>
      <button class="circle glassy" data-act="taller" aria-label="Encuadernar">${ic('taller', 22)}</button></div>`, { tabbar: false });
  const sc = v.querySelector('#sc');
  async function draw() {
    book = await db.books.get(book.id) || book;
    const days = await bookDays(book.id);
    const today = db.today();
    const pct = Math.round((book.progress || 0) * 100);
    v.querySelector('#rb').textContent = book.finishedAt ? 'Leer de nuevo' : book.progress > 0 ? 'Seguir leyendo' : 'Empezar a leer';
    const seals = days.slice(-12).map((d, i) => `<div class="seal-wrap"><div class="seal" style="transform:rotate(${[-6, 4, -2, 5, -4, 2][i % 6]}deg)">${d.min}′</div><span>${new Date(d.date + 'T12:00').toLocaleDateString('es', { day: 'numeric', month: 'short' })}</span></div>`).join('');
    const hasToday = days.some(d => d.date === today);
    sc.innerHTML = `
      <div style="display:flex;justify-content:space-between;padding:4px 16px 0">
        <button class="circle glassy" data-act="back" aria-label="Volver">${ic('back', 20)}</button>
        <button class="circle glassy" data-act="more" aria-label="Opciones">${ic('more', 20)}</button></div>
      <div style="display:flex;flex-direction:column;align-items:center;margin-top:8px;padding:0 24px;text-align:center">
        ${coverHTML(book, 150, 218, { ribbon: book.progress > 0 && !book.finishedAt })}
        <div style="font-size:25px;font-weight:600;margin-top:22px;line-height:1.2">${esc(book.title)}</div>
        <div class="g muted" style="font-size:14px;margin-top:6px">${esc(book.author || 'Autor desconocido')}</div>
        <div class="chips" style="margin-top:14px">${book.lang ? `<span class="chip">${esc((LANGS.find(l => l.id === book.lang) || {}).name || book.lang)}</span>` : ''}<span class="chip">${esc(book.source || '')}</span>${pct ? `<span class="chip">${pct} %</span>` : ''}</div>
      </div>
      ${book.finishedAt ? `<div class="card" style="margin:20px 16px 0;padding:16px 18px;display:flex;align-items:center;gap:16px">
        <div class="big-seal" style="width:64px;height:64px;font-size:13px">読了</div><div><div style="font-size:17px;font-weight:600">Leído</div>
        <div class="g muted" style="font-size:13px">${new Date(book.finishedAt).toLocaleDateString('es', { day: 'numeric', month: 'long', year: 'numeric' })}</div></div></div>` : ''}
      <div class="card" style="margin:${book.finishedAt ? 12 : 20}px 16px 0;padding:16px 18px">
        <div style="display:flex;justify-content:space-between;align-items:baseline"><span style="font-size:17px;font-weight:600">Sellos de lectura</span>
          <span class="g muted" style="font-size:12px">${days.length ? days.length + (days.length === 1 ? ' día' : ' días') : 'aún sin sellos'}</span></div>
        <div class="seals" style="margin-top:14px">${seals}${hasToday ? '' : `<div class="seal-wrap"><div class="seal empty"></div><span>hoy</span></div>`}</div>
      </div>
      ${book.summary ? `<div class="card" style="margin:12px 16px 0;padding:16px 18px"><div style="font-size:17px;font-weight:600">Sobre el libro</div>
        <div style="font-size:14.5px;line-height:1.65;margin-top:6px;opacity:.88">${esc(book.summary.slice(0, 1200))}</div></div>` : ''}`;
  }
  v.addEventListener('click', async e => {
    const a = e.target.closest('[data-act]'); if (!a) return;
    if (a.dataset.act === 'back') ui.back();
    if (a.dataset.act === 'read') read(book);
    if (a.dataset.act === 'taller') openTaller(book);
    if (a.dataset.act === 'more') bookMenu(book, draw);
  });
  ui.setRefresh(draw);
  await draw();
}

function bookMenu(book, redraw) {
  const s = ui.sheet(`
    <h3>${esc(book.title)}</h3>
    <div class="list-item" data-x="cover"><div class="grow"><div class="t">${book.useBinding ? 'Mostrar la portada original' : 'Mostrar mi encuadernación'}</div></div></div>
    <div class="list-item" data-x="done"><div class="grow"><div class="t">${book.finishedAt ? 'Marcar como no terminado' : 'Marcar como terminado'}</div></div></div>
    <div class="list-item" data-x="notes"><div class="grow"><div class="t">Exportar subrayados</div><div class="s">Con cita lista para tus trabajos</div></div></div>
    <div class="list-item" data-x="del"><div class="grow"><div class="t" style="color:var(--shu)">Quitar de mi biblioteca</div><div class="s">Se borran el archivo, sus sellos y sus notas</div></div></div>`);
  s.on('cover', async () => { book.useBinding = !book.useBinding; await db.books.put(book); s.close(); redraw(); });
  s.on('done', async () => { book.finishedAt = book.finishedAt ? 0 : Date.now(); if (book.finishedAt) book.progress = 1; await db.books.put(book); s.close(); redraw(); });
  s.on('notes', async () => { const hls = (await db.notes.byBook(book.id)).filter(n => n.type === 'hl'); if (!hls.length) return ui.toast('Este libro aún no tiene subrayados'); N.share(exportText(book, hls)); });
  s.on('del', () => {
    s.el.innerHTML = `<div class="grabber"></div><h3>¿Quitar «${esc(book.title)}»?</h3><p class="g muted" style="font-size:14px;line-height:1.6">Se borra del teléfono junto con sus sellos y notas. Esto no se puede deshacer.</p>
      <div class="row" style="margin-top:16px"><button class="btn ghost" data-x="no">Cancelar</button><button class="btn" data-x="yes" style="background:var(--shu);color:#fff">Quitar</button></div>`;
    s.on('no', () => s.close());
    s.on('yes', async () => { await removeBook(book); s.close(); ui.toast('Libro quitado'); ui.back(); });
  });
}

async function read(book) {
  await openReader(book, ui);
}

// ---------------------------------------------------------------- Taller
function openTaller(book) {
  const b = Object.assign(defaultBinding(book.id), book.binding || {});
  const v = ui.view(`
    <div id="tint" style="position:absolute;inset:0;transition:background .5s"></div>
    <div style="position:relative;padding:calc(var(--sat) + 14px) 16px 0;display:flex;justify-content:space-between;align-items:center">
      <button class="circle glassy" data-act="back" aria-label="Volver">${ic('back', 20)}</button>
      <span style="font-size:17px;font-weight:600">Taller</span>
      <button class="circle glassy" data-act="share" aria-label="Compartir shiori">${ic('share', 19)}</button></div>
    <div style="position:relative;display:flex;justify-content:center;margin-top:16px" id="pv"></div>
    <div class="sheet show" style="position:absolute;transform:none;max-height:none;bottom:0;top:auto;z-index:5" id="opts"></div>`, { tabbar: false });
  const pv = v.querySelector('#pv'), opts = v.querySelector('#opts'), tint = v.querySelector('#tint');
  function draw() {
    const w = WASHI.find(x => x.id === b.washi) || WASHI[0];
    tint.style.background = `linear-gradient(180deg, color-mix(in srgb, ${w.hex} 32%, transparent), color-mix(in srgb, ${w.hex} 6%, transparent) 55%, transparent)`;
    pv.innerHTML = `<div class="cover" style="width:160px;height:232px">${bindingHTML(book.title, b, 160, 232)}</div>`;
    const t = THREAD.find(x => x.id === b.thread) || THREAD[0];
    opts.innerHTML = `<div class="grabber"></div>
      <div class="field-label" style="margin-top:4px">Washi <small>${w.name}</small></div>
      <div class="swatches">${WASHI.map(x => `<button class="swatch ${x.id === b.washi ? 'on' : ''}" data-w="${x.id}" style="background:${x.hex}" aria-label="${x.name}"></button>`).join('')}</div>
      <div class="field-label">Patrón</div>
      <div class="seg">${PATTERNS.map(x => `<button data-p="${x.id}" class="${x.id === b.pattern ? 'on' : ''}">${x.name}</button>`).join('')}</div>
      <div class="field-label">Hilo <small>${t.name}</small></div>
      <div class="swatches">${THREAD.map(x => `<button class="swatch ${x.id === b.thread ? 'on' : ''}" data-t="${x.id}" style="background:${x.hex}" aria-label="${x.name}"></button>`).join('')}</div>
      <div class="field-label">Etiqueta</div>
      <div class="seg">${LABELS.map(x => `<button data-l="${x.id}" class="${x.id === b.label ? 'on' : ''}">${x.name}</button>`).join('')}</div>
      <div class="list-item" style="margin-top:10px"><div class="grow"><div class="t">Usar en mi estante</div><div class="s">En lugar de la portada original</div></div>
        <button class="switch ${book.useBinding ? 'on' : ''}" data-act="use" aria-label="Usar encuadernación"></button></div>`;
  }
  async function save() { book.binding = { ...b }; await db.books.put(book); }
  v.addEventListener('click', async e => {
    const x = e.target.closest('[data-w],[data-p],[data-t],[data-l],[data-act]'); if (!x) return;
    if (x.dataset.w) b.washi = x.dataset.w;
    if (x.dataset.p) b.pattern = x.dataset.p;
    if (x.dataset.t) b.thread = x.dataset.t;
    if (x.dataset.l) b.label = x.dataset.l;
    if (x.dataset.act === 'back') return ui.back();
    if (x.dataset.act === 'use') book.useBinding = !book.useBinding;
    if (x.dataset.act === 'share') {
      const n = id => (WASHI.find(y => y.id === id) || THREAD.find(y => y.id === id) || {}).name || id;
      return N.share(`Mi shiori de «${book.title}» en Zerkalo 鏡\nWashi ${n(b.washi)} · ${(PATTERNS.find(p => p.id === b.pattern) || {}).name} · hilo ${n(b.thread).split(' · ')[0]}\n\nzerkalo://shiori/${b.washi}-${b.pattern}-${b.thread}-${b.label}`);
    }
    await save(); draw();
  });
  draw();
}

// ---------------------------------------------------------------- Tansu
let tansuLang = null;
async function renderTansu() {
  if (!tansuLang) tansuLang = await db.kv.get('lang', (navigator.language || 'es').slice(0, 2));
  if (!LANGS.find(l => l.id === tansuLang)) tansuLang = 'es';
  const v = ui.view(`<div class="scroll" id="sc"></div><div class="pblur top soft" style="height:calc(var(--sat) + 30px)">${PBLUR}</div><div class="pblur bottom" style="height:calc(var(--sab) + 130px)">${PBLUR}</div>`, { tabbar: true, root: true });
  const sc = v.querySelector('#sc');
  let query = '';
  sc.innerHTML = `
    <div class="head"><div><div class="cap">Libros libres del mundo</div><h1 class="big">Tansu</h1></div>
      <button class="circle" data-act="opds" aria-label="Mis catálogos">${ic('folder', 20)}</button></div>
    <form class="search" id="sf">${ic('search', 18)}<input id="q" type="search" enterkeyhint="search" placeholder="Título, autor o tema" autocomplete="off"></form>
    <div class="pills" style="margin-top:14px" id="lp">${LANGS.map(l => `<button class="pill ${l.id === tansuLang ? 'on' : ''}" data-lang="${l.id}">${l.name}</button>`).join('')}</div>
    <div id="body"></div>
    <div class="source-note">Zerkalo no guarda libros: los trae directo de Project Gutenberg, Wikisource, OAPEN, Standard Ebooks y los catálogos que agregues. Todo es de dominio público o de acceso abierto. Gracias a quienes los digitalizaron.</div>`;
  const body = sc.querySelector('#body');
  const row = (id, title, kicker = '') => `<div class="section-title" style="margin-top:26px"><div>${kicker ? `<div class="cap accent">${kicker}</div>` : ''}<h2 style="margin-top:2px">${title}</h2></div><span class="more" data-more="${id}"></span></div>
    <div class="hscroll" id="r-${id}"><div class="loading" style="padding:0">${'<div class="skel"></div>'.repeat(4)}</div></div>`;
  const cache = new Map();
  const itemHTML = it => `<button class="item" data-key="${esc(it.key)}">${itemCoverHTML(it)}<div class="t">${esc(it.title)}</div><div class="a">${esc(it.author || it.source)}</div></button>`;
  function fill(id, items, emptyMsg = 'Nada por aquí todavía.') {
    const el = sc.querySelector('#r-' + id); if (!el) return;
    items.forEach(i => cache.set(i.key, i));
    el.innerHTML = items.length ? items.map(itemHTML).join('') + '<div style="width:6px;flex:none"></div>' : `<p class="g muted" style="font-size:13px;margin:0">${emptyMsg}</p>`;
  }
  const fail = (id, e) => { if (e) console.warn('catálogo', id, e && e.message); const el = sc.querySelector('#r-' + id); if (el) el.innerHTML = `<p class="g muted" style="font-size:13px;margin:0">No se pudo conectar. Revisa tu internet.</p>`; };

  async function home() {
    const topics = TOPICS.slice(0, 5);
    let html = row('pop', 'Los más leídos', 'Project Gutenberg');
    topics.forEach(t => html += row('t-' + t.id.replace(/\s/g, '_'), t.name));
    if (tansuLang === 'en') html += row('se', 'Recién editados', 'Standard Ebooks');
    html += row('oa', tansuLang === 'es' ? 'Ensayo y ciencia abiertos' : 'Open access', 'OAPEN');
    const cats = await db.kv.get('opds', []);
    if (cats.length) html += `<div class="section-title" style="margin-top:26px"><h2>Mis catálogos</h2></div><div class="pills" style="margin-top:12px">${cats.map((c, i) => `<button class="pill" data-cat="${i}">${esc(c.name)}</button>`).join('')}</div>`;
    body.innerHTML = html;
    gutenberg.list({ lang: tansuLang }).then(r => fill('pop', r.items)).catch(e => fail('pop', e));
    topics.forEach(t => gutenberg.list({ lang: tansuLang, topic: t.id }).then(r => fill('t-' + t.id.replace(/\s/g, '_'), r.items)).catch(e => fail('t-' + t.id.replace(/\s/g, '_', e))));
    if (tansuLang === 'en') standard.news().then(r => fill('se', r.items.slice(0, 20))).catch(e => fail('se', e));
    oapen.search(tansuLang === 'es' ? 'historia' : tansuLang === 'fr' ? 'histoire' : tansuLang === 'pt' ? 'história' : tansuLang === 'de' ? 'Geschichte' : 'history')
      .then(r => fill('oa', r.items)).catch(e => fail('oa', e));
  }
  async function search(q) {
    body.innerHTML = row('sg', 'En Project Gutenberg') + row('sw', 'En Wikisource') + row('so', 'Libros académicos', 'OAPEN');
    gutenberg.list({ search: q, lang: tansuLang }).then(r => fill('sg', r.items, 'Sin resultados en este idioma.')).catch(e => fail('sg', e));
    wikisource.search(q, tansuLang).then(r => fill('sw', r.items, 'Sin resultados.')).catch(e => fail('sw', e));
    oapen.search(q).then(r => fill('so', r.items, 'Sin resultados.')).catch(e => fail('so', e));
  }
  sc.querySelector('#sf').addEventListener('submit', e => { e.preventDefault(); query = sc.querySelector('#q').value.trim(); sc.querySelector('#q').blur(); query ? search(query) : home(); });
  sc.querySelector('#q').addEventListener('input', e => { if (!e.target.value.trim() && query) { query = ''; home(); } });
  sc.addEventListener('click', async e => {
    const l = e.target.closest('[data-lang]');
    if (l) { tansuLang = l.dataset.lang; db.kv.set('lang', tansuLang); sc.querySelectorAll('[data-lang]').forEach(x => x.classList.toggle('on', x === l)); return query ? search(query) : home(); }
    const it = e.target.closest('[data-key]'); if (it) return preview(cache.get(it.dataset.key));
    const c = e.target.closest('[data-cat]'); if (c) { const cats = await db.kv.get('opds', []); return browseCatalog(cats[+c.dataset.cat].url, cats[+c.dataset.cat].name); }
    const a = e.target.closest('[data-act]'); if (a && a.dataset.act === 'opds') catalogsSheet(home);
  });
  await home();
}

async function preview(item) {
  if (!item) return;
  const have = await findByKey(item.key);
  const langName = (LANGS.find(l => l.id === item.lang) || {}).name || item.lang || '';
  const s = ui.sheet(`
    <div style="display:flex;gap:16px">${itemCoverHTML(item, 110, 160)}
      <div style="flex:1;min-width:0"><div class="cap accent">${esc(item.source)}</div>
      <div style="font-size:20px;font-weight:600;line-height:1.2;margin-top:4px">${esc(item.title)}</div>
      <div class="g muted" style="font-size:13.5px;margin-top:6px">${esc(item.author || '')}</div>
      <div class="chips" style="justify-content:flex-start;margin-top:10px">${langName ? `<span class="chip">${esc(langName)}</span>` : ''}<span class="chip">EPUB libre</span></div></div></div>
    ${item.summary ? `<div style="font-size:14.5px;line-height:1.65;margin-top:16px;opacity:.88">${esc(item.summary.slice(0, 900))}</div>` : ''}
    <button class="btn block" style="margin-top:20px" data-x="get" id="get">${have ? 'Abrir en mi biblioteca' : `${ic('download', 18)} Agregar a mi biblioteca`}</button>
    <div class="dl-bar hidden" id="dl"><i></i></div>
    <p class="g muted" style="font-size:11.5px;text-align:center;margin:12px 0 0">${item.source === 'Wikisource' ? 'Wikisource prepara el EPUB al momento: puede tardar un poco.' : 'Se descarga directo de la biblioteca de origen.'}</p>`);
  let busy = false;
  s.on('get', async () => {
    if (have) { s.close(); return openBook(have); }
    if (busy) return; busy = true;
    const btn = s.el.querySelector('#get'), bar = s.el.querySelector('#dl');
    btn.disabled = true; btn.textContent = 'Descargando…'; bar.classList.remove('hidden');
    try {
      const b = await addFromCatalog(item, p => { bar.firstChild.style.width = p == null ? '40%' : Math.round(p * 100) + '%'; });
      btn.disabled = false; btn.innerHTML = 'Leer ahora'; bar.classList.add('hidden');
      ui.toast(`«${b.title}» está en tu estante`);
      s.on('get', () => { s.close(); read(b); });
    } catch (e) {
      busy = false; btn.disabled = false; btn.innerHTML = 'Intentar de nuevo'; bar.classList.add('hidden');
      ui.toast('No se pudo descargar: ' + e.message);
    }
  });
}

function catalogsSheet(after) {
  const draw = async s => {
    const cats = await db.kv.get('opds', []);
    s.el.innerHTML = `<div class="grabber"></div><h3>Mis catálogos OPDS</h3>
      <p class="g muted" style="font-size:13.5px;line-height:1.6;margin-top:-4px">Muchas bibliotecas libres publican un catálogo OPDS. Pega su dirección y aparecerá en el Tansu.</p>
      ${cats.map((c, i) => `<div class="list-item"><div class="grow"><div class="t">${esc(c.name)}</div><div class="s">${esc(c.url)}</div></div><button class="circle" data-x="del" data-i="${i}" aria-label="Quitar">${ic('trash', 18)}</button></div>`).join('')}
      <div class="search" style="margin:14px 0 0">${ic('plus', 18)}<input id="ou" placeholder="https://… catálogo OPDS" inputmode="url"></div>
      <button class="btn block" style="margin-top:12px" data-x="add">Agregar catálogo</button>`;
  };
  const s = ui.sheet('');
  draw(s);
  s.on('del', async x => { const cats = await db.kv.get('opds', []); cats.splice(+x.dataset.i, 1); await db.kv.set('opds', cats); draw(s); after && after(); });
  s.on('add', async () => {
    const url = s.el.querySelector('#ou').value.trim(); if (!/^https?:\/\//.test(url)) return ui.toast('Escribe una dirección completa (https://…)');
    ui.toast('Revisando el catálogo…');
    try {
      const r = await opds.browse(url);
      const cats = await db.kv.get('opds', []);
      cats.push({ name: r.title || new URL(url).host, url }); await db.kv.set('opds', cats);
      draw(s); after && after(); ui.toast('Catálogo agregado');
    } catch (e) { ui.toast('No parece un catálogo OPDS válido'); }
  });
}

async function browseCatalog(url, name) {
  const v = ui.view(`<div class="scroll" id="sc"></div><div class="pblur bottom" style="height:calc(var(--sab) + 100px)">${PBLUR}</div>`, { tabbar: false });
  const sc = v.querySelector('#sc');
  sc.innerHTML = `<div style="padding:4px 16px 0"><button class="circle" data-act="back" aria-label="Volver">${ic('back', 20)}</button></div>
    <div class="head" style="padding-top:8px"><h1 class="big" style="font-size:28px">${esc(name)}</h1></div><div id="gl" class="loading">${'<div class="skel"></div>'.repeat(3)}</div>`;
  const cache = new Map();
  sc.addEventListener('click', e => {
    const a = e.target.closest('[data-act=back]'); if (a) return ui.back();
    const n = e.target.closest('[data-nav]'); if (n) return browseCatalog(n.dataset.nav, n.dataset.name);
    const it = e.target.closest('[data-key]'); if (it) preview(cache.get(it.dataset.key));
  });
  try {
    const r = await opds.browse(url, name);
    const navs = r.items.filter(i => i.nav), books = r.items.filter(i => !i.nav);
    books.forEach(b => cache.set(b.key, b));
    sc.querySelector('#gl').outerHTML = `
      ${navs.length ? `<div class="card" style="margin:18px 16px 0;padding:4px 18px">${navs.map(n => `<div class="list-item" data-nav="${esc(n.url)}" data-name="${esc(n.title)}"><div class="grow"><div class="t">${esc(n.title)}</div></div><span class="muted">›</span></div>`).join('')}</div>` : ''}
      ${books.length ? `<div class="grid">${books.map(it => `<button class="item" data-key="${esc(it.key)}">${itemCoverHTML(it)}<div class="t">${esc(it.title)}</div><div class="a">${esc(it.author)}</div></button>`).join('')}</div>` : ''}
      ${r.next ? `<div style="padding:20px;text-align:center"><button class="btn ghost small" data-nav="${esc(r.next)}" data-name="${esc(name)}">Siguiente página</button></div>` : ''}`;
  } catch (e) { sc.querySelector('#gl').outerHTML = `<div class="empty"><p>No se pudo abrir este catálogo.</p></div>`; }
}

// ---------------------------------------------------------------- Cuaderno
async function renderCuaderno() {
  const v = ui.view(`<div class="scroll" id="sc"></div><div class="pblur top soft" style="height:calc(var(--sat) + 30px)">${PBLUR}</div><div class="pblur bottom" style="height:calc(var(--sab) + 130px)">${PBLUR}</div>`, { tabbar: true, root: true });
  const sc = v.querySelector('#sc');
  async function draw() {
    const [bs, ss, ns] = await Promise.all([db.books.all(), db.sessions.all(), db.notes.all()]);
    const done = bs.filter(b => b.finishedAt).sort((a, b) => b.finishedAt - a.finishedAt);
    const now = new Date(); const weekAgo = Date.now() - 7 * 864e5;
    const sum = xs => Math.round(xs.reduce((a, s) => a + s.ms, 0) / 60000);
    const week = sum(ss.filter(s => s.at > weekAgo));
    const month = sum(ss.filter(s => { const d = new Date(s.at); return d.getMonth() === now.getMonth() && d.getFullYear() === now.getFullYear(); }));
    const daysRead = new Set(ss.map(s => s.date)).size;
    const fmt = m => m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
    const hls = ns.filter(n => n.type === 'hl').sort((a, b) => b.at - a.at);
    const byId = Object.fromEntries(bs.map(b => [b.id, b]));
    sc.innerHTML = `
      <div class="head"><div><div class="cap">Tu historia como lector</div><h1 class="big">Cuaderno</h1></div>
        <button class="circle" data-act="settings" aria-label="Ajustes">${ic('gear', 20)}</button></div>
      <div style="display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;padding:20px 16px 0">
        <div class="card" style="padding:14px 16px"><div class="cap">Esta semana</div><div style="font-size:24px;font-weight:600;margin-top:4px">${fmt(week)}</div></div>
        <div class="card" style="padding:14px 16px"><div class="cap">Este mes</div><div style="font-size:24px;font-weight:600;margin-top:4px">${fmt(month)}</div></div>
        <div class="card" style="padding:14px 16px"><div class="cap">Terminados</div><div style="font-size:24px;font-weight:600;margin-top:4px">${done.length}</div></div>
        <div class="card" style="padding:14px 16px"><div class="cap">Días leyendo</div><div style="font-size:24px;font-weight:600;margin-top:4px">${daysRead}</div></div>
      </div>
      <div class="section-title"><h2>Sellos</h2><span class="more">${done.length ? 'un sello por libro leído' : ''}</span></div>
      ${done.length ? `<div style="display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:18px 10px;padding:16px 20px 0">${done.map((b, i) => `<button data-book="${b.id}" style="display:flex;flex-direction:column;align-items:center;gap:8px">
          <div class="big-seal h" style="transform:rotate(${[-4, 3, -2, 4, -3][i % 5]}deg);font-size:${b.title.length > 22 ? 10.5 : b.title.length > 12 ? 11.5 : 13.5}px">${esc(b.title.length > 40 ? b.title.slice(0, 38) + '…' : b.title)}</div>
          <span class="g muted" style="font-size:11px">${new Date(b.finishedAt).toLocaleDateString('es', { month: 'short', year: 'numeric' })}</span></button>`).join('')}</div>`
        : `<div class="card" style="margin:14px 16px 0"><div class="empty" style="padding:22px"><p style="margin:0">Cuando termines un libro, su sello bermellón quedará aquí.</p></div></div>`}
      <div class="section-title"><h2>Subrayados</h2><span class="more">${hls.length || ''}</span></div>
      ${hls.length ? `<div class="card" style="margin:14px 16px 0;padding:4px 18px">${hls.slice(0, 40).map(h => `<div class="list-item" data-hlbook="${h.bookId}"><div class="grow">
          <div style="font-size:15px;line-height:1.55">«${esc(h.text.slice(0, 220))}»</div>${h.note ? `<div class="g muted" style="font-size:12.5px;margin-top:4px">${esc(h.note)}</div>` : ''}
          <div class="s" style="margin-top:6px">${esc(byId[h.bookId]?.title || '')}</div></div></div>`).join('')}</div>`
        : `<div class="card" style="margin:14px 16px 0"><div class="empty" style="padding:22px"><p style="margin:0">Mantén presionado el texto mientras lees para subrayar o dejar una nota.</p></div></div>`}`;
  }
  sc.addEventListener('click', async e => {
    const a = e.target.closest('[data-act=settings]'); if (a) return renderSettings();
    const b = e.target.closest('[data-book],[data-hlbook]'); if (b) return openBook(await db.books.get(b.dataset.book || b.dataset.hlbook));
  });
  ui.setRefresh(draw);
  await draw();
}

// ---------------------------------------------------------------- Ajustes
async function renderSettings() {
  const v = ui.view(`<div class="scroll" id="sc"></div>`, { tabbar: false });
  const sc = v.querySelector('#sc');
  let poll;
  async function draw() {
    const info = N.appInfo(), up = N.updateState();
    const rem = await db.kv.get('reminder', { on: false, h: 21, m: 30 });
    const p = n => String(n).padStart(2, '0');
    sc.innerHTML = `
      <div style="padding:4px 16px 0"><button class="circle" data-act="back" aria-label="Volver">${ic('back', 20)}</button></div>
      <div class="head" style="padding-top:8px"><h1 class="big">Ajustes</h1></div>
      <div class="card" style="margin:20px 16px 0;padding:16px 18px">
        <div style="font-size:17px;font-weight:600">Papel</div>
        <div class="seg" style="margin-top:12px" id="tm">${[['system', 'Sistema'], ['light', 'Crema'], ['dark', 'Nogal']].map(([x, n]) => `<button data-v="${x}" class="${themePref === x ? 'on' : ''}">${n}</button>`).join('')}</div>
      </div>
      <div class="card" style="margin:12px 16px 0;padding:6px 18px">
        <div class="list-item"><div class="grow"><div class="t">Hora de lectura</div><div class="s">Un aviso amable cada día, nada más</div></div>
          <button class="switch ${rem.on ? 'on' : ''}" data-act="rem" aria-label="Recordatorio"></button></div>
        <div class="list-item ${rem.on ? '' : 'hidden'}"><div class="grow"><div class="t">A las</div></div>
          <input type="time" id="rt" value="${p(rem.h)}:${p(rem.m)}" style="border:0;background:var(--chip);border-radius:10px;padding:8px 10px;font-family:var(--sans);font-size:15px"></div>
      </div>
      <div class="card" style="margin:12px 16px 0;padding:6px 18px">
        <div class="list-item"><div class="grow"><div class="t">Zerkalo ${esc(info.versionName || '')}</div><div class="s" id="ust">${esc(up.status || 'Se actualiza sola desde GitHub')}</div></div>
          <button class="btn small ${up.available ? '' : 'ghost'}" data-act="${up.available ? 'install' : 'check'}">${up.available ? 'Actualizar' : 'Buscar'}</button></div>
        <div class="list-item"><div class="grow"><div class="t">Actualizar sola</div><div class="s">Descarga la versión nueva y la instala cuando no estás leyendo</div></div>
          <button class="switch ${up.auto !== false ? 'on' : ''}" data-act="auto" aria-label="Actualizar sola"></button></div>
      </div>
      <div class="card" style="margin:12px 16px 0;padding:6px 18px">
        <div class="list-item" data-act="import"><div class="grow"><div class="t">Importar un EPUB</div><div class="s">Desde tu teléfono, Drive o descargas</div></div><span class="muted">›</span></div>
        <div class="list-item" data-act="opds"><div class="grow"><div class="t">Catálogos OPDS</div><div class="s">Agrega otras bibliotecas libres</div></div><span class="muted">›</span></div>
      </div>
      <div class="section-title"><h2>Las bibliotecas</h2></div>
      <div class="card" style="margin:12px 16px 0;padding:6px 18px">
        ${LIBRARIES.map(l => `<div class="list-item"><div class="grow"><div class="t">${l.name}</div><div class="s">${l.what}</div></div>
          <button class="btn small ghost" data-url="${l.donate}">Apoyar</button></div>`).join('')}
      </div>
      <div style="padding:34px 0 0;display:flex;justify-content:center"><img class="logo-light" src="img/logo-light.png" alt="Zerkalo" style="width:190px"><img class="logo-dark" src="img/logo-dark.png" alt="Zerkalo" style="width:190px"></div>
      <p class="g muted" style="font-size:12px;line-height:1.7;text-align:center;padding:14px 30px 0">Gratis, sin anuncios y sin servidores propios.<br>Tus libros, sellos y notas viven en tu teléfono.</p>`;
  }
  sc.addEventListener('click', async e => {
    const t = e.target.closest('#tm button'); if (t) { setTheme(t.dataset.v); return draw(); }
    const u = e.target.closest('[data-url]'); if (u) return N.openUrl(u.dataset.url);
    const a = e.target.closest('[data-act]'); if (!a) return;
    const act = a.dataset.act;
    if (act === 'back') return ui.back();
    if (act === 'rem') { const r = await db.kv.get('reminder', { on: false, h: 21, m: 30 }); r.on = !r.on; await db.kv.set('reminder', r); N.setReminder(r.on, r.h, r.m); if (r.on) ui.toast(`Te avisaré a las ${String(r.h).padStart(2, '0')}:${String(r.m).padStart(2, '0')}`); return draw(); }
    if (act === 'check') { N.checkUpdate(); ui.toast('Buscando versión nueva…'); }
    if (act === 'install') { N.installUpdate(); ui.toast('Preparando la actualización…'); }
    if (act === 'auto') { const st = N.updateState(); N.setAutoUpdate(!(st.auto !== false)); return draw(); }
    if (act === 'import') return importEpub();
    if (act === 'opds') return catalogsSheet();
  });
  sc.addEventListener('change', async e => {
    if (e.target.id === 'rt') { const [h, m] = e.target.value.split(':').map(Number); const r = await db.kv.get('reminder', {}); Object.assign(r, { on: true, h, m }); await db.kv.set('reminder', r); N.setReminder(true, h, m); ui.toast(`Te avisaré a las ${e.target.value}`); }
  });
  poll = setInterval(() => { const el = sc.querySelector('#ust'); if (el) el.textContent = N.updateState().status || el.textContent; }, 1500);
  ui.setCleanup(() => clearInterval(poll));
  ui.setRefresh(draw);
  await draw();
}

// ---------------------------------------------------------------- inicio
markTab('home');
go('home').finally(() => setTimeout(() => {
  const s = document.getElementById('splash'); if (!s) return;
  s.classList.add('out'); setTimeout(() => s.remove(), 800);
}, 900));
