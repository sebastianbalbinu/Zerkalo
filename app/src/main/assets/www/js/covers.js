// Portadas: la original si existe; si no, o si la persona lo elige, la encuadernación japonesa.
import { fileUrl } from './native.js';

export const WASHI = [
  { id: 'koke', name: 'Koke · musgo', hex: '#5E6B3F' },
  { id: 'ai', name: 'Ai · índigo', hex: '#3D4F66' },
  { id: 'kuri', name: 'Kuri · castaño', hex: '#6B4A3A' },
  { id: 'enji', name: 'Enji · granate', hex: '#7A3B2E' },
  { id: 'nezumi', name: 'Nezumi · gris', hex: '#9C8F86' },
  { id: 'matsuba', name: 'Matsuba · pino', hex: '#2F3D2A' },
  { id: 'sumi', name: 'Sumi · tinta', hex: '#1F1D1B' },
  { id: 'kinari', name: 'Kinari · crudo', hex: '#EFE8DA' },
];
export const THREAD = [
  { id: 'shu', name: 'Shu · bermellón', hex: '#C4432A' },
  { id: 'kinari', name: 'Kinari · crudo', hex: '#F4EFE4' },
  { id: 'sumi', name: 'Sumi · tinta', hex: '#1F1D1B' },
  { id: 'kin', name: 'Kin · oro viejo', hex: '#B08D4C' },
];
export const PATTERNS = [{ id: 'liso', name: 'Liso' }, { id: 'seigaiha', name: 'Seigaiha' }, { id: 'asanoha', name: 'Asanoha' }];
export const LABELS = [{ id: 'washi', name: 'Washi', hex: '#FBF8F2' }, { id: 'kinari', name: 'Kinari', hex: '#EADFC8' }, { id: 'none', name: 'Sin etiqueta' }];

function hash(s) { let h = 2166136261; for (const c of s || '') { h ^= c.charCodeAt(0); h = Math.imul(h, 16777619); } return h >>> 0; }
export function defaultBinding(seed) {
  const h = hash(seed);
  return { washi: WASHI[h % 7].id, pattern: PATTERNS[(h >>> 3) % 3].id, thread: THREAD[(h >>> 5) % 4].id, label: 'washi' };
}
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

export function bindingHTML(title, b, w, h) {
  const washi = WASHI.find(x => x.id === b.washi) || WASHI[0];
  const thread = THREAD.find(x => x.id === b.thread) || THREAD[1];
  const label = LABELS.find(x => x.id === b.label) || LABELS[0];
  const fs = Math.max(8, Math.round(w * 0.105));
  const lh = Math.round(h * 0.68);
  const cols = (title || '').length * fs * 0.78 <= lh - 14 ? 1 : 2;
  const lw = Math.max(Math.round(w * 0.2), Math.round(fs * 1.25 * cols + 8));
  const sw = Math.round(w * 0.12);
  const holes = [0.12, 0.37, 0.63, 0.88].map(f =>
    `<i style="top:${Math.round(h * f)}px;left:${Math.round(sw * .35)}px;width:${Math.round(sw * .55)}px;background:${thread.hex}"></i><b style="top:${Math.round(h * f)}px;width:${Math.round(sw * .35)}px;background:${thread.hex}"></b>`).join('');
  const lab = label.id === 'none' ? '' :
    `<div class="daisen" style="left:${sw + Math.round(w * .1)}px;top:${Math.round(h * .07)}px;width:${lw}px;height:${lh}px;font-size:${fs}px;background:${label.hex}">${esc(title)}</div>`;
  const pat = b.pattern === 'liso' ? '' : 'p-' + b.pattern;
  return `<div class="binding ${pat}" style="background-color:${washi.hex}"><div class="stitch" style="width:${sw}px">${holes}</div>${lab}</div>`;
}

/** Portada de un libro de la biblioteca. */
export function coverHTML(book, w = 104, h = 150, { ribbon = false } = {}) {
  const useBinding = book.useBinding || !(book.coverFile || book.coverData || book.coverUrl);
  let inner;
  if (useBinding) inner = bindingHTML(book.title, book.binding || defaultBinding(book.id), w, h);
  else {
    const src = book.coverData || (book.coverFile ? fileUrl(book.coverFile) : book.coverUrl);
    inner = `<img src="${esc(src)}" alt="" loading="lazy" decoding="async" onerror="this.remove()">`;
  }
  return `<div class="cover" style="width:${w}px;height:${h}px">${inner}${ribbon ? '<div class="ribbon"></div>' : ''}</div>`;
}

/** Portada de un resultado del catálogo (aún no descargado). */
export function itemCoverHTML(item, w = 104, h = 150) {
  if (item.cover) return `<div class="cover" style="width:${w}px;height:${h}px">${bindingHTML(item.title, defaultBinding(item.key), w, h)}<img src="${esc(item.cover)}" alt="" loading="lazy" decoding="async" style="position:absolute;inset:0" onerror="this.remove()"></div>`;
  return `<div class="cover" style="width:${w}px;height:${h}px">${bindingHTML(item.title, defaultBinding(item.key), w, h)}</div>`;
}
export { esc };
