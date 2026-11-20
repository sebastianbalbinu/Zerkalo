// Operaciones de la biblioteca: agregar desde catálogos, importar, borrar, metadatos.
import * as db from './db.js';
import { download, fileUrl, deleteFile } from './native.js';
import { defaultBinding } from './covers.js';

function slug(s) {
  let h = 5381; for (const c of s) h = (h * 33) ^ c.charCodeAt(0);
  return 'b' + (h >>> 0).toString(36) + Date.now().toString(36).slice(-4);
}

/** Lee título, autor, idioma y portada del propio EPUB. */
export async function readMeta(file) {
  const book = window.ePub(fileUrl(file), { openAs: 'epub' });
  try {
    const md = await Promise.race([book.loaded.metadata, new Promise((_, r) => setTimeout(() => r(new Error('tiempo')), 20000))]);
    let coverData = '';
    try {
      const url = await book.coverUrl();
      if (url) coverData = await toDataUrl(url, 420);
    } catch (e) {}
    return { title: md.title || '', author: md.creator || '', lang: (md.language || '').slice(0, 2), description: md.description || '', coverData };
  } catch (e) {
    return {};
  } finally {
    try { book.destroy(); } catch (e) {}
  }
}

function toDataUrl(url, maxW) {
  return new Promise(res => {
    const img = new Image();
    img.onload = () => {
      const s = Math.min(1, maxW / img.naturalWidth);
      const c = document.createElement('canvas');
      c.width = Math.round(img.naturalWidth * s); c.height = Math.round(img.naturalHeight * s);
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      try { res(c.toDataURL('image/jpeg', 0.86)); } catch (e) { res(''); }
    };
    img.onerror = () => res('');
    img.src = url;
  });
}

export async function findByKey(key) {
  return (await db.books.all()).find(b => b.key === key);
}

/** Descarga un libro de un catálogo y lo agrega al estante. */
export async function addFromCatalog(item, onProgress) {
  const existing = await findByKey(item.key);
  if (existing) return existing;
  const id = slug(item.key);
  const res = await download(item.epub, id + '.epub', p => onProgress && onProgress(p == null ? null : p * 0.9));
  let coverFile = '';
  if (item.cover) {
    try { coverFile = (await download(item.cover, id + '.jpg')).file; } catch (e) {}
  }
  const meta = await readMeta(res.file);
  onProgress && onProgress(1);
  const b = {
    id, key: item.key, source: item.source, title: item.title || meta.title || 'Sin título',
    author: item.author || meta.author || '', lang: item.lang || meta.lang || '',
    summary: item.summary || meta.description?.replace(/<[^>]+>/g, '') || '',
    file: res.file, coverFile, coverData: coverFile ? '' : (meta.coverData || ''),
    addedAt: Date.now(), lastOpened: 0, progress: 0, cfi: '', finishedAt: 0,
    binding: defaultBinding(item.key), useBinding: false,
  };
  await db.books.put(b);
  return b;
}

/** Agrega un EPUB propio (elegido o compartido desde otra app). */
export async function addImported({ file, name }) {
  const meta = await readMeta(file);
  const id = slug(file);
  const b = {
    id, key: 'local:' + file, source: 'Importado', title: meta.title || (name || '').replace(/\.epub$/i, '') || 'Sin título',
    author: meta.author || '', lang: meta.lang || '', summary: (meta.description || '').replace(/<[^>]+>/g, ''),
    file, coverFile: '', coverData: meta.coverData || '', addedAt: Date.now(), lastOpened: 0, progress: 0, cfi: '', finishedAt: 0,
    binding: defaultBinding(file), useBinding: false,
  };
  await db.books.put(b);
  return b;
}

export async function removeBook(b) {
  deleteFile(b.file); if (b.coverFile) deleteFile(b.coverFile);
  await db.books.del(b.id);
  await db.notes.delBook(b.id);
  await db.sessions.delBook(b.id);
  await db.kv.set('loc:' + b.id, null);
}

/** Minutos leídos por día de un libro: [{date, min}] */
export async function bookDays(bookId) {
  const xs = await db.sessions.byBook(bookId);
  const m = new Map();
  xs.forEach(s => m.set(s.date, (m.get(s.date) || 0) + s.ms));
  return [...m.entries()].sort((a, b) => a[0] < b[0] ? -1 : 1).map(([date, ms]) => ({ date, min: Math.max(1, Math.round(ms / 60000)) }));
}

export async function minutesOn(date) {
  const xs = await db.sessions.all();
  return Math.round(xs.filter(s => s.date === date).reduce((a, s) => a + s.ms, 0) / 60000);
}
