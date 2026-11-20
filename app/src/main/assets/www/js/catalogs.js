// Catálogos legales y gratuitos. Zerkalo no guarda libros: los trae directo de cada biblioteca.
import { getJSON, getText } from './native.js';

export const LANGS = [
  { id: 'es', name: 'Español' }, { id: 'en', name: 'English' }, { id: 'fr', name: 'Français' },
  { id: 'pt', name: 'Português' }, { id: 'de', name: 'Deutsch' }, { id: 'it', name: 'Italiano' },
  { id: 'ru', name: 'Русский' }, { id: 'ja', name: '日本語' }, { id: 'la', name: 'Latina' },
];
export const TOPICS = [
  { id: 'fiction', name: 'Novela' }, { id: 'poetry', name: 'Poesía' }, { id: 'drama', name: 'Teatro' },
  { id: 'short stories', name: 'Cuentos' }, { id: 'philosophy', name: 'Filosofía' }, { id: 'history', name: 'Historia' },
  { id: 'children', name: 'Infantil' }, { id: 'science fiction', name: 'Ciencia ficción' }, { id: 'adventure', name: 'Aventura' },
];

/** "Cervantes Saavedra, Miguel de" → "Miguel de Cervantes Saavedra" */
export function niceName(n) {
  if (!n) return '';
  const m = n.match(/^([^,]+),\s*(.+)$/);
  return (m ? `${m[2]} ${m[1]}` : n).replace(/\s*\(.*?\)\s*/g, ' ').replace(/\s+/g, ' ').trim();
}

// ---------- Project Gutenberg (vía Gutendex) ----------
export const gutenberg = {
  name: 'Project Gutenberg',
  async list({ lang, topic, search, url } = {}) {
    let u = url;
    if (!u) {
      const p = new URLSearchParams();
      if (lang) p.set('languages', lang);
      if (topic) p.set('topic', topic);
      if (search) p.set('search', search);
      p.set('mime_type', 'application/epub');
      u = 'https://gutendex.com/books/?' + p.toString();
    }
    const j = await getJSON(u);
    const items = (j.results || []).map(b => {
      const f = b.formats || {};
      const epub = f['application/epub+zip'] || Object.entries(f).find(([k]) => k.startsWith('application/epub'))?.[1];
      if (!epub) return null;
      const a = (b.authors || [])[0];
      return {
        key: 'pg:' + b.id, source: 'Project Gutenberg', title: (b.title || '').replace(/\s*[\r\n]+\s*/g, ' — '),
        author: niceName(a?.name), year: a?.death_year ? null : null, lang: (b.languages || [])[0] || lang || '',
        cover: f['image/jpeg'] || '', epub, summary: (b.summaries || [])[0] || '',
        subjects: (b.subjects || []).slice(0, 4),
      };
    }).filter(Boolean);
    return { items, next: j.next || null };
  },
};

// ---------- Wikisource (EPUB generado por WS Export) ----------
export const wikisource = {
  name: 'Wikisource',
  async search(q, lang = 'es') {
    const wl = ['es', 'en', 'fr', 'pt', 'de', 'it', 'ru', 'ja', 'la'].includes(lang) ? lang : 'es';
    const u = `https://${wl}.wikisource.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=30&format=json&srsearch=${encodeURIComponent(q)}`;
    const j = await getJSON(u);
    const seen = new Set();
    const items = [];
    for (const r of (j.query?.search || [])) {
      const title = r.title.split('/')[0];
      if (seen.has(title)) continue; seen.add(title);
      items.push({
        key: `ws:${wl}:${title}`, source: 'Wikisource', title, author: '', lang: wl, cover: '',
        epub: `https://ws-export.wmcloud.org/?format=epub&lang=${wl}&page=${encodeURIComponent(title)}`,
        summary: (r.snippet || '').replace(/<[^>]+>/g, '').replace(/&quot;/g, '"').replace(/&amp;/g, '&'),
      });
      if (items.length >= 15) break;
    }
    return { items };
  },
};

// ---------- OAPEN (libros académicos de acceso abierto) ----------
export const oapen = {
  name: 'OAPEN',
  async search(q) {
    const u = `https://library.oapen.org/rest/search?query=${encodeURIComponent(q)}&expand=metadata,bitstreams&limit=40`;
    const arr = await getJSON(u);
    const items = [];
    for (const it of (Array.isArray(arr) ? arr : [])) {
      const md = it.metadata || [];
      const get = k => md.filter(m => m.key === k).map(m => m.value);
      const bs = it.bitstreams || [];
      const ep = bs.find(b => (b.mimeType || '').includes('epub') || /\.epub$/i.test(b.name || ''));
      if (!ep) continue;
      const th = bs.find(b => (b.bundleName || '') === 'THUMBNAIL') || bs.find(b => /image\//.test(b.mimeType || ''));
      const abs = l => l ? (l.startsWith('http') ? l : 'https://library.oapen.org' + (l.startsWith('/') ? '' : '/') + l) : '';
      items.push({
        key: 'oapen:' + (it.handle || it.uuid || it.id), source: 'OAPEN', title: get('dc.title')[0] || it.name || 'Sin título',
        author: get('dc.contributor.author').map(niceName).slice(0, 2).join(', ') || get('dc.contributor.editor').map(niceName)[0] || '',
        lang: (get('dc.language')[0] || '').slice(0, 2).toLowerCase(), cover: abs(th?.retrieveLink),
        epub: abs(ep.retrieveLink), summary: (get('dc.description.abstract')[0] || '').slice(0, 900),
      });
      if (items.length >= 15) break;
    }
    return { items };
  },
};

// ---------- OPDS (Standard Ebooks y cualquier catálogo que agregue la persona) ----------
function parseOPDS(xml, base) {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const abs = h => { try { return new URL(h, base).href; } catch (e) { return h; } };
  const entries = [...doc.getElementsByTagName('entry')];
  const items = [];
  for (const e of entries) {
    const txt = tag => e.getElementsByTagName(tag)[0]?.textContent?.trim() || '';
    const links = [...e.getElementsByTagName('link')].map(l => ({ rel: l.getAttribute('rel') || '', type: l.getAttribute('type') || '', href: abs(l.getAttribute('href') || '') }));
    const epub = links.find(l => l.type.includes('epub') && (l.rel.includes('acquisition') || l.rel === 'enclosure' || !l.rel));
    const img = links.find(l => /opds-spec\.org\/image(?!\/thumbnail)/.test(l.rel)) || links.find(l => l.rel.includes('image') || l.type.startsWith('image/'));
    const author = [...e.getElementsByTagName('author')].map(a => a.getElementsByTagName('name')[0]?.textContent?.trim()).filter(Boolean).join(', ');
    const title = txt('title');
    if (epub) {
      items.push({ key: 'opds:' + (txt('id') || epub.href), source: '', title, author, lang: txt('dc:language') || txt('language'),
        cover: img?.href || '', epub: epub.href, summary: (txt('summary') || txt('content')).replace(/<[^>]+>/g, '').slice(0, 900) });
    } else {
      const nav = links.find(l => l.type.includes('opds-catalog') || l.type.includes('atom+xml'));
      if (nav) items.push({ nav: true, key: 'nav:' + nav.href, title, url: nav.href, summary: txt('content') || txt('summary') });
    }
  }
  const next = [...doc.querySelectorAll('feed > link')].find(l => l.getAttribute('rel') === 'next');
  const feedTitle = doc.querySelector('feed > title')?.textContent?.trim() || '';
  return { items, next: next ? abs(next.getAttribute('href')) : null, title: feedTitle };
}
export const opds = {
  async browse(url, sourceName = '') {
    const xml = await getText(url);
    const r = parseOPDS(xml, url);
    r.items.forEach(i => { if (!i.nav) i.source = sourceName || r.title || 'Catálogo OPDS'; });
    return r;
  },
};
export const standard = {
  name: 'Standard Ebooks',
  async news() { return opds.browse('https://standardebooks.org/feeds/atom/new-releases', 'Standard Ebooks'); },
};

export const LIBRARIES = [
  { name: 'Project Gutenberg', what: 'Más de 75.000 libros de dominio público', url: 'https://www.gutenberg.org', donate: 'https://www.gutenberg.org/donate/' },
  { name: 'Wikisource', what: 'Textos libres en decenas de idiomas, hechos por voluntarios', url: 'https://wikisource.org', donate: 'https://donate.wikimedia.org' },
  { name: 'OAPEN', what: 'Libros académicos de acceso abierto', url: 'https://library.oapen.org', donate: 'https://www.oapen.org' },
  { name: 'Standard Ebooks', what: 'Clásicos en inglés con ediciones cuidadas', url: 'https://standardebooks.org', donate: 'https://standardebooks.org/donate' },
];
