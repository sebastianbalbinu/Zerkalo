const P = {
  home: '<path d="M4 4h3.2v16H4zM9.4 6h3.2v14H9.4zM15 5.6l3-.8 3.4 14.4-3 .8z"/>',
  tansu: '<rect x="3.5" y="4" width="17" height="16" rx="2"/><path d="M3.5 12h17M10 8h4M10 16h4"/>',
  taller: '<path d="M7 3.5h10v17l-5-3.8-5 3.8z"/>',
  cuaderno: '<rect x="4.5" y="3.5" width="15" height="17" rx="2"/><circle cx="12" cy="11" r="3.2"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  close: '<path d="M6 6l12 12M18 6L6 18"/>',
  more: '<circle cx="6" cy="12" r="1.1"/><circle cx="12" cy="12" r="1.1"/><circle cx="18" cy="12" r="1.1"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 7.5V12l3 2"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"/>',
  moon: '<path d="M19.5 14.5A8 8 0 019.5 4.5a8 8 0 1010 10z"/>',
  aa: '<path d="M3 18l4.5-12L12 18M4.6 14h5.8M14 18l3-8 3 8M15 15.5h4"/>',
  shiori: '<path d="M8 3.5h8v17l-4-3.2-4 3.2z"/>',
  toc: '<path d="M8 6h12M8 12h12M8 18h12M4 6h.01M4 12h.01M4 18h.01"/>',
  share: '<path d="M4.5 13v6a1.5 1.5 0 001.5 1.5h12a1.5 1.5 0 001.5-1.5v-6M12 3.5v12M7.5 8L12 3.5 16.5 8"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.6 1.6 0 00.3 1.8l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.6 1.6 0 00-1.8-.3 1.6 1.6 0 00-1 1.5V21a2 2 0 11-4 0v-.1a1.6 1.6 0 00-1-1.5 1.6 1.6 0 00-1.8.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.6 1.6 0 00.3-1.8 1.6 1.6 0 00-1.5-1H3a2 2 0 110-4h.1a1.6 1.6 0 001.5-1 1.6 1.6 0 00-.3-1.8l-.1-.1a2 2 0 112.8-2.8l.1.1a1.6 1.6 0 001.8.3H9a1.6 1.6 0 001-1.5V3a2 2 0 114 0v.1a1.6 1.6 0 001 1.5 1.6 1.6 0 001.8-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.6 1.6 0 00-.3 1.8V9a1.6 1.6 0 001.5 1H21a2 2 0 110 4h-.1a1.6 1.6 0 00-1.5 1z"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  download: '<path d="M12 4v11M7.5 10.5L12 15l4.5-4.5M5 19.5h14"/>',
  trash: '<path d="M4.5 7h15M10 11v6M14 11v6M6 7l1 12.5a1.5 1.5 0 001.5 1.4h7a1.5 1.5 0 001.5-1.4L18 7M9 7V4.5h6V7"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  note: '<path d="M4 20l4-1 11-11-3-3L5 16z"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5.5A1.5 1.5 0 0014.5 4h-9A1.5 1.5 0 004 5.5v9A1.5 1.5 0 005.5 16H8"/>',
  minus: '<path d="M5 12h14"/>',
  folder: '<path d="M3.5 7.5a2 2 0 012-2h4l2 2.5h7a2 2 0 012 2v7.5a2 2 0 01-2 2h-13a2 2 0 01-2-2z"/>',
};
export function ic(name, size = 22, sw = 1.6) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${sw}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[name] || ''}</svg>`;
}
export const enso = (size = 54) => `<svg class="enso-spin" width="${size}" height="${size}" viewBox="0 0 150 150" aria-hidden="true"><path d="M112 30 C 86 12, 46 16, 28 44 C 10 72, 22 112, 54 126 C 86 140, 124 122, 132 88 C 136 70, 132 56, 124 46" fill="none" stroke="currentColor" stroke-width="10" stroke-linecap="round"/></svg>`;
