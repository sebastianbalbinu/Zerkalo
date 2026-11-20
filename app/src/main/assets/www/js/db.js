// Biblioteca local en IndexedDB: libros, sesiones de lectura, notas y ajustes.
let dbp;
function open() {
  if (dbp) return dbp;
  dbp = new Promise((res, rej) => {
    const r = indexedDB.open('zerkalo', 1);
    r.onupgradeneeded = () => {
      const d = r.result;
      d.createObjectStore('books', { keyPath: 'id' });
      const s = d.createObjectStore('sessions', { keyPath: 'id', autoIncrement: true });
      s.createIndex('bookId', 'bookId');
      const n = d.createObjectStore('notes', { keyPath: 'id' });
      n.createIndex('bookId', 'bookId');
      d.createObjectStore('kv', { keyPath: 'k' });
    };
    r.onsuccess = () => { window.__step && window.__step('base de datos abierta'); res(r.result); };
    r.onblocked = () => window.__step && window.__step('base de datos bloqueada');
    r.onerror = () => { window.__step && window.__step('base de datos error: ' + (r.error && r.error.message)); rej(r.error); };
  });
  return dbp;
}
async function tx(store, mode, fn) {
  const d = await open();
  return new Promise((res, rej) => {
    const t = d.transaction(store, mode); const st = t.objectStore(store);
    const out = fn(st);
    t.oncomplete = () => res(out && 'result' in out ? out.result : out);
    t.onerror = () => rej(t.error);
  });
}
export const books = {
  all: () => tx('books', 'readonly', s => s.getAll()),
  get: id => tx('books', 'readonly', s => s.get(id)),
  put: b => tx('books', 'readwrite', s => s.put(b)),
  del: id => tx('books', 'readwrite', s => s.delete(id)),
};
export const sessions = {
  all: () => tx('sessions', 'readonly', s => s.getAll()),
  byBook: id => tx('sessions', 'readonly', s => s.index('bookId').getAll(id)),
  add: x => tx('sessions', 'readwrite', s => s.add(x)),
  delBook: async id => { const xs = await sessions.byBook(id); return tx('sessions', 'readwrite', s => xs.forEach(x => s.delete(x.id))); },
};
export const notes = {
  all: () => tx('notes', 'readonly', s => s.getAll()),
  byBook: id => tx('notes', 'readonly', s => s.index('bookId').getAll(id)),
  put: n => tx('notes', 'readwrite', s => s.put(n)),
  del: id => tx('notes', 'readwrite', s => s.delete(id)),
  delBook: async id => { const xs = await notes.byBook(id); return tx('notes', 'readwrite', s => xs.forEach(x => s.delete(x.id))); },
};
export const kv = {
  get: async (k, def) => { const r = await tx('kv', 'readonly', s => s.get(k)); return r ? r.v : def; },
  set: (k, v) => tx('kv', 'readwrite', s => s.put({ k, v })),
};
export function today(d = new Date()) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
