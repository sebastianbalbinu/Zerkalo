package com.sebas.zerkalo.reader

import com.sebas.zerkalo.data.ReaderSettings

/**
 * Diagramación de Zerkalo: se aplica a cada capítulo después de que Readium lo muestra.
 *
 * - Justificado apagado = apagado en TODO el libro (también donde la editorial lo forzaba).
 * - Poemas: se reconocen y van siempre verso a verso, alineados a la izquierda y sin guiones.
 *   Si un verso no cabe, el poema baja un poco de tamaño (hasta 12 %) antes de partir la línea.
 * - Modo Zerkalo: justificado solo con líneas de 40 letras o más, títulos proporcionados,
 *   sin títulos solos al final de la página, epígrafes con ancho cómodo, líneas sin palabras sueltas.
 * - En todos los modos: imágenes y tablas nunca más anchas que la pantalla.
 *
 * Todo se hace con estilos en línea marcados con data-zk-*, así se puede deshacer y rehacer cuando
 * cambian los ajustes, sin tocar el texto (los subrayados guardados siguen en su sitio).
 */
object Typeset {
    fun script(r: ReaderSettings, lang: String): String {
        val justify = r.justify && !r.dyslexia
        val opts = """{"mode":${r.layout},"justify":$justify,"lang":"${lang.replace("\"", "")}"}"""
        return JS.replace("__OPTS__", opts)
    }

    private const val JS = """(function(){
var O = __OPTS__;
var d = document, b = d.body;
if (!b) return 'sin-cuerpo';
window.__zkOpts = O;
window.__zkRun = function(){
  var O = window.__zkOpts, d = document, b = d.body;
  if (!d.documentElement.lang && O.lang) d.documentElement.lang = O.lang;
  // 1) Deshacer la pasada anterior
  var old = d.querySelectorAll('[data-zk-s]');
  for (var i = 0; i < old.length; i++) {
    var e = old[i], props = (e.getAttribute('data-zk-s') || '').split(' ');
    for (var k = 0; k < props.length; k++) if (props[k]) e.style.removeProperty(props[k]);
    e.removeAttribute('data-zk-s');
  }
  var mk = d.querySelectorAll('[data-zk]');
  for (var m = 0; m < mk.length; m++) mk[m].removeAttribute('data-zk');
  function set(e, prop, val) {
    e.style.setProperty(prop, val, 'important');
    var a = e.getAttribute('data-zk-s') || '';
    if ((' ' + a + ' ').indexOf(' ' + prop + ' ') < 0) e.setAttribute('data-zk-s', (a + ' ' + prop).trim());
  }
  function txt(e) { return (e.textContent || '').replace(/\s+/g, ' ').trim(); }

  // 2) Estilos generales
  var st = d.getElementById('zk-type');
  if (!st) { st = d.createElement('style'); st.id = 'zk-type'; (d.head || d.documentElement).appendChild(st); }
  var css = 'img,svg,video{max-width:100% !important;height:auto !important;object-fit:contain}img{max-height:92vh}' +
    'table{max-width:100% !important}pre{white-space:pre-wrap !important}';
  if (O.mode === 1) css += 'h1,h2,h3,h4,h5,h6{break-after:avoid;break-inside:avoid;text-wrap:balance}' +
    'p{orphans:2;widows:2;text-wrap:pretty}';
  st.textContent = css;

  // 3) Reconocer poemas
  var VCLS = /(^|[\s_-])(poem|poema|poemas|poesia|poetry|verse|verso|versos|stanza|estrofa|lg|linegroup|line-group|poetic|cancion|song|lyrics|copla|songtext)(\d*)([\s_-]|$)/i;
  var LCLS = /(^|\s)(l|line|linea|v|vl|vers|i\d+|indent\d*)(\s|$)/i;
  var all = b.getElementsByTagName('*');
  function isVerse(e) {
    var c = (e.getAttribute('class') || '') + ' ' + (e.getAttribute('epub:type') || '') + ' ' + (e.getAttribute('role') || '');
    return VCLS.test(c);
  }
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (isVerse(e)) { e.setAttribute('data-zk', 'verse'); continue; }
    var tag = e.tagName;
    if (tag === 'P' || tag === 'DIV' || tag === 'BLOCKQUOTE') {
      // Varias líneas cortas separadas con <br>
      var brs = 0, ch = e.childNodes;
      for (var j = 0; j < ch.length; j++) if (ch[j].nodeName === 'BR') brs++;
      if (brs >= 2) {
        var segs = (e.innerText || '').split('\n').map(function(s){ return s.trim(); }).filter(function(s){ return s; });
        if (segs.length >= 3) {
          var long = 0, sum = 0;
          for (var q = 0; q < segs.length; q++) { sum += segs[q].length; if (segs[q].length > 110) long++; }
          if (!long && sum / segs.length <= 72) e.setAttribute('data-zk', 'verse');
        }
      }
    }
  }
  // Clases de "línea" dentro de un bloque
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (LCLS.test(e.getAttribute('class') || '') && e.parentElement && !e.closest('[data-zk~=verse]')) {
      var t = txt(e);
      if (t.length > 0 && t.length < 90) e.parentElement.setAttribute('data-zk', 'verse');
    }
  }
  // Rachas de párrafos cortos seguidos (poemas escritos como un párrafo por verso)
  var ps = b.querySelectorAll('p');
  var run = [];
  function flush() {
    if (run.length >= 4) {
      var sum = 0; for (var r = 0; r < run.length; r++) sum += run[r].l;
      if (sum / run.length <= 52) for (var r = 0; r < run.length; r++) run[r].e.setAttribute('data-zk', 'verse vline');
    }
    run = [];
  }
  for (var i = 0; i < ps.length; i++) {
    var p = ps[i], t = txt(p);
    var prev = run.length ? run[run.length - 1].e : null;
    var sib = prev && (p.previousElementSibling === prev);
    var ok = t.length > 0 && t.length <= 75 && !/^[—–\-«"“]/.test(t) && !p.closest('[data-zk~=verse]') && !p.querySelector('img');
    if (ok && (sib || !prev)) run.push({ e: p, l: t.length });
    else { flush(); if (ok) run.push({ e: p, l: t.length }); }
  }
  flush();

  // 4) Alineación: poemas a la izquierda; justificado apagado en todo; en modo Zerkalo solo donde caben 40+ letras
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    var cs = getComputedStyle(e);
    if (cs.display === 'inline' || cs.display === 'none') continue;
    var inVerse = !!e.closest('[data-zk~=verse]');
    if (inVerse) {
      if (cs.textAlign === 'justify' || cs.textAlign === 'start' || cs.textAlign === 'left') set(e, 'text-align', 'left');
      set(e, 'text-align-last', 'auto'); set(e, 'hyphens', 'manual'); set(e, '-webkit-hyphens', 'manual');
      continue;
    }
    if (cs.textAlign === 'justify' || cs.textAlignLast === 'justify') {
      var fs = parseFloat(cs.fontSize) || 16;
      var narrow = e.clientWidth > 0 && e.clientWidth / (fs * 0.5) < 40;
      if (!O.justify || (O.mode === 1 && narrow)) {
        set(e, 'text-align', 'left'); set(e, 'text-align-last', 'auto');
        set(e, 'hyphens', 'manual'); set(e, '-webkit-hyphens', 'manual');
      }
    }
  }
  var vl = b.querySelectorAll('[data-zk~=vline]');
  for (var i = 0; i < vl.length; i++) {
    var e = vl[i];
    // Sangría francesa: si un verso se parte, la continuación queda más adentro
    if (parseFloat(getComputedStyle(e).textIndent) >= 0) { set(e, 'padding-left', '1.4em'); set(e, 'text-indent', '-1.4em'); }
  }

  if (O.mode === 2) return 'ok';

  // 5) Que los versos quepan: el poema baja hasta un 12 % antes de partir líneas
  var roots = [];
  var vs = b.querySelectorAll('[data-zk~=verse]');
  for (var i = 0; i < vs.length; i++) {
    var e = vs[i];
    if (e.parentElement && e.parentElement.closest('[data-zk~=verse]')) continue;
    if (e.getAttribute('data-zk').indexOf('vline') >= 0) { roots.push(e); continue; }
    roots.push(e);
  }
  for (var i = 0; i < roots.length; i++) {
    var e = roots[i], w = e.clientWidth;
    if (!w) continue;
    var ws = e.style.whiteSpace;
    e.style.whiteSpace = 'nowrap';
    var need = e.scrollWidth;
    var kids = e.querySelectorAll('*');
    for (var k = 0; k < kids.length && k < 400; k++) if (kids[k].scrollWidth > need) need = kids[k].scrollWidth;
    e.style.whiteSpace = ws;
    if (need > w + 2) {
      var ratio = Math.max(0.88, w / need);
      set(e, 'font-size', (ratio * 100).toFixed(1) + '%');
    }
  }

  if (O.mode !== 1) return 'ok';

  // 6) Títulos proporcionados: nunca más de 1,7 veces la letra del texto
  var body = parseFloat(getComputedStyle(b.querySelector('p') || b).fontSize) || 16;
  var hs = b.querySelectorAll('h1,h2,h3,h4');
  for (var i = 0; i < hs.length; i++) {
    var h = hs[i], hf = parseFloat(getComputedStyle(h).fontSize) || body;
    var cap = body * (h.tagName === 'H1' ? 1.7 : h.tagName === 'H2' ? 1.5 : 1.3);
    if (hf > cap) set(h, 'font-size', cap.toFixed(1) + 'px');
    set(h, 'hyphens', 'manual'); set(h, '-webkit-hyphens', 'manual');
  }
  // 7) Epígrafes y citas a la derecha: ancho cómodo, no una columnita
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (e.tagName !== 'DIV' && e.tagName !== 'BLOCKQUOTE' && e.tagName !== 'P') continue;
    var c = (e.getAttribute('class') || '') + ' ' + (e.getAttribute('epub:type') || '');
    if (/epigra|epígra|motto|dedica/i.test(c)) {
      set(e, 'max-width', 'min(88%, 26em)'); set(e, 'margin-left', 'auto'); set(e, 'margin-right', '0');
    }
  }
  return 'ok';
};
var res = window.__zkRun();
if (d.fonts && !window.__zkFonts) { window.__zkFonts = 1; d.fonts.ready.then(function(){ window.__zkRun(); }); }
return res;
})()"""
}
