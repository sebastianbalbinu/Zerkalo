// Puente con Android. Fuera de Android (pruebas en navegador) usa fetch y memoria.
const N = window.ZerkaloNative || null;
export const isNative = !!N;
let seq = 0;
const waits = new Map();
const listeners = new Map();
const devFiles = new Map();

window.__native = {
  resolve(id, ok, payload) {
    const w = waits.get(id); if (!w) return;
    waits.delete(id);
    ok ? w.res(payload) : w.rej(new Error(payload));
  },
  event(name, data) { (listeners.get(name) || []).forEach(f => f(data)); },
};
export function on(name, fn) { if (!listeners.has(name)) listeners.set(name, []); listeners.get(name).push(fn); }

function call(fn, ...args) {
  return new Promise((res, rej) => { const id = 'c' + (++seq); waits.set(id, { res, rej }); N[fn](...args, id); });
}

export async function getText(url) {
  if (N) {
    let raw;
    try { raw = await call('httpGet', url); } catch (e) {
      let msg = 'sin conexión'; try { const j = JSON.parse(e.message); msg = 'HTTP ' + j.status; } catch (_) {}
      throw new Error(msg);
    }
    return JSON.parse(raw).body;
  }
  const r = await fetch(url);
  if (!r.ok) throw new Error('HTTP ' + r.status);
  return r.text();
}
export async function getJSON(url) { return JSON.parse(await getText(url)); }

/** Descarga a la biblioteca interna. onProgress(0..1 | null) */
export async function download(url, name, onProgress) {
  if (N) {
    const id = 'c' + (++seq);
    const handler = d => { if (d.cb === id && onProgress) onProgress(d.total > 0 ? d.loaded / d.total : null); };
    on('progress', handler);
    const p = new Promise((res, rej) => waits.set(id, { res, rej }));
    N.download(url, name, id);
    try { return JSON.parse(await p); } finally { const l = listeners.get('progress'); l.splice(l.indexOf(handler), 1); }
  }
  const r = await fetch(url); if (!r.ok) throw new Error('HTTP ' + r.status);
  const b = await r.blob(); devFiles.set(name, URL.createObjectURL(b));
  onProgress && onProgress(1);
  return { file: name, bytes: b.size };
}

export function fileUrl(name) {
  if (!name) return '';
  if (N) return '/books/' + encodeURIComponent(name);
  return devFiles.get(name) || ('/books/' + name);
}
export function deleteFile(name) { if (N && name) N.deleteFile(name); }
export async function pickEpub() {
  if (N) { try { return JSON.parse(await call('pickEpub')); } catch (e) { return null; } }
  return new Promise(res => {
    const inp = document.createElement('input'); inp.type = 'file'; inp.accept = '.epub,application/epub+zip';
    inp.onchange = () => { const f = inp.files[0]; if (!f) return res(null); const name = 'imp-' + Date.now() + '.epub'; devFiles.set(name, URL.createObjectURL(f)); res({ file: name, name: f.name }); };
    inp.click();
  });
}
export function share(text) { if (N) N.share(text); else if (navigator.share) navigator.share({ text }).catch(() => {}); else navigator.clipboard?.writeText(text); }
export function openUrl(url) { if (N) N.openUrl(url); else window.open(url, '_blank'); }
export function setDarkBars(d) { if (N) N.setDark(d); }
export function keepAwake(on) { if (N) N.keepAwake(on); }
export function systemDark() { return N ? N.systemDark() : matchMedia('(prefers-color-scheme: dark)').matches; }
export function appInfo() { try { return N ? JSON.parse(N.appInfo()) : { versionName: 'web', versionCode: 0 }; } catch (e) { return {}; } }
export function updateState() { try { return N ? JSON.parse(N.updateState()) : { status: 'Solo en Android', available: '', ready: false, auto: true }; } catch (e) { return {}; } }
export function checkUpdate() { if (N) N.checkUpdate(); }
export function installUpdate() { if (N) N.installUpdate(); }
export function setAutoUpdate(on) { if (N) N.setAutoUpdate(on); }
export function setReminder(on, h, m) { if (N) N.setReminder(on, h, m); }
export function setReminderBook(t) { if (N) N.setReminderBook(t || ''); }
