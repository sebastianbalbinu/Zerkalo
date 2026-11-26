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
 * - En todos los modos: imágenes y tablas nunca más anchas que la pantalla, versalitas respetadas y
 *   ninguna palabra más grande que el texto que la rodea.
 * - Original y Zerkalo: sin justificado en líneas angostas (menos de 42 letras), que dejan huecos grandes.
 * - Ninguna (modo 3): el libro tal cual; se deshace todo lo de Zerkalo.
 *
 * Todo se hace con estilos en línea marcados con data-zk-*, así se puede deshacer y rehacer cuando
 * cambian los ajustes, sin tocar el texto (los subrayados guardados siguen en su sitio).
 */
/** Desplazamiento: espacio dentro de la página arriba y abajo (px de CSS) y el color del papel para el desenfoque. */
data class PagePad(val top: Float = 0f, val bottom: Float = 0f, val paper: Int = 0, val left: Float = 0f, val right: Float = 0f)

object Typeset {
    fun script(r: ReaderSettings, lang: String, pad: PagePad = PagePad(), nonce: Long = 0): String {
        val align = Fit.align(r)
        val justify = align != 0
        val c = pad.paper
        val rgb = "${(c shr 16) and 255},${(c shr 8) and 255},${c and 255}"
        val opts = """{"mode":${r.layout},"justify":$justify,"align":$align,"minJ":${Fit.AUTO_JUSTIFY},"lang":"${lang.replace("\"", "")}","scroll":${r.scroll},""" +
            """"size":${r.size},"lite":${com.sebas.zerkalo.data.Prefs.blurLite()},"padTop":${pad.top},"padBottom":${pad.bottom},"padLeft":${pad.left},"padRight":${pad.right},"paper":"$rgb","n":$nonce}"""
        return JS.replace("__OPTS__", opts)
    }

    private const val JS = """(function(){
var O = __OPTS__;
var d = document, b = d.body;
if (!b) return 'sin-cuerpo';
window.__zkOpts = O;
window.__zkEdges = function(top, bot, rgb, lite){
  var d = document, old = d.getElementById('zk-edges');
  if (old) old.remove();
  if (top <= 0 && bot <= 0) return;
  var wrap = d.createElement('div'); wrap.id = 'zk-edges'; wrap.setAttribute('aria-hidden', 'true');
  // Liviano: 2 capas (más fluido en teléfonos modestos); completo: 5 capas, el degradé más suave
  var blurs = lite ? [3, 12] : [1, 2, 4, 8, 16], n = blurs.length;
  function imp(css) { return css.split(';').filter(function(x){ return x.trim(); }).map(function(x){ return x + ' !important'; }).join(';'); }
  function zone(isTop, h) {
    var dir = isTop ? 'to bottom' : 'to top', pos = isTop ? 'top:0' : 'bottom:0';
    for (var j = 0; j < n; j++) {
      // Capa j: más desenfocada cuanto más alta; cubre desde el borde hasta (n-j)/n de la zona
      var mid = Math.round((n - j - 1) / n * 100), end = Math.round((n - j) / n * 100);
      var m = 'linear-gradient(' + dir + ',#000 ' + mid + '%,transparent ' + end + '%)';
      var x = d.createElement('div');
      x.style.cssText = imp('position:fixed;left:0;right:0;' + pos + ';height:' + h + 'px;pointer-events:none;z-index:2147483646;' +
        'background:transparent;-webkit-backdrop-filter:blur(' + blurs[j] + 'px);backdrop-filter:blur(' + blurs[j] + 'px);' +
        '-webkit-mask-image:' + m + ';mask-image:' + m + ';display:block;margin:0;padding:0');
      wrap.appendChild(x);
    }
    // Un tinte suave del papel, como el vidrio del menú
    var t = d.createElement('div');
    t.style.cssText = imp('position:fixed;left:0;right:0;' + pos + ';height:' + h + 'px;pointer-events:none;z-index:2147483647;' +
      'background:linear-gradient(' + dir + ',rgba(' + rgb + ',0.62),rgba(' + rgb + ',0.25) 55%,rgba(' + rgb + ',0));display:block;margin:0;padding:0');
    wrap.appendChild(t);
  }
  if (top > 0) zone(true, top);
  if (bot > 0) zone(false, bot);
  d.documentElement.appendChild(wrap);
};
// Si nada cambió desde la última vez, no se vuelve a acomodar (así el texto no se mueve solo)
if (window.__zkKey === JSON.stringify(O) && window.__zkDone) return 'igual';
window.__zkKey = JSON.stringify(O);
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
  // Letras por línea medidas con la letra real (no un cálculo aproximado)
  var cv = (window.__zkCv = window.__zkCv || d.createElement('canvas').getContext('2d')), cw = {};
  function perLine(e, cs) {
    var f = cs.fontStyle + ' ' + cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
    if (!(f in cw)) { cv.font = f; cw[f] = cv.measureText('de la conquista del infinito y lo desconocido').width / 45; }
    return cw[f] > 0 ? e.clientWidth / cw[f] : 99;
  }

  // 2) Estilos generales
  var st = d.getElementById('zk-type');
  if (!st) { st = d.createElement('style'); st.id = 'zk-type'; (d.head || d.documentElement).appendChild(st); }
  // Desplazamiento: el texto usa toda la pantalla y se desenfoca de forma progresiva arriba y abajo
  // (varias capas, cada una más desenfocada cerca del borde, como el menú de la app). El espacio del
  // inicio y del final va en ::before/::after del cuerpo, que el motor del lector no anula.
  var edge = '';
  if (O.scroll) {
    edge = 'html{overflow-x:hidden !important}' + 'body::before{content:"" !important;display:block !important;height:' + O.padTop + 'px !important}' +
      'body::after{content:"" !important;display:block !important;height:' + (O.padBottom + 28) + 'px !important}';
  }
  // Los márgenes de los lados van dentro de la página: así el desenfoque llega de borde a borde
  if (O.scroll && (O.padLeft > 0 || O.padRight > 0)) {
    set(b, 'padding-left', O.padLeft + 'px'); set(b, 'padding-right', O.padRight + 'px'); set(b, 'box-sizing', 'border-box');
  }
  window.__zkEdges(O.scroll ? O.padTop + 10 : 0, O.scroll ? O.padBottom + 18 : 0, O.paper, !!O.lite);
  if (O.mode === 3) { st.textContent = edge; return 'ninguna'; }
  var css = 'img,svg,video{max-width:100% !important;height:auto !important;object-fit:contain}img{max-height:92vh}' +
    'table{max-width:100% !important}pre{white-space:pre-wrap !important}';
  if (O.mode === 1) css += 'h1,h2,h3,h4,h5,h6{break-after:avoid;break-inside:avoid;text-wrap:balance}' +
    'p{orphans:2;widows:2;text-wrap:pretty}';
  st.textContent = css + edge;

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
    var ta = cs.textAlign;
    // Justificado (siempre): los párrafos de texto se justifican con guiones en cualquier modo;
    // los títulos y lo centrado o a la derecha se respetan.
    if (O.align === 1) {
      var textBlock = /^(P|LI|BLOCKQUOTE|DD)$/.test(e.tagName) || (e.tagName === 'DIV' && !e.querySelector('p,div,li,h1,h2,h3,h4,h5,h6,table'));
      if (textBlock && !e.closest('h1,h2,h3,h4,h5,h6') && (ta === 'justify' || ta === 'start' || ta === 'left')) {
        set(e, 'text-align', 'justify');
        if (O.lang) { set(e, 'hyphens', 'auto'); set(e, '-webkit-hyphens', 'auto'); }
      }
      continue;
    }
    if (ta === 'justify' || cs.textAlignLast === 'justify') {
      // Automático: justifica si caben las letras suficientes; si no, a la izquierda con guiones
      var narrow = O.align === 2 && (O.mode === 0 || O.mode === 1) && e.clientWidth > 0 && perLine(e, cs) < O.minJ;
      if (O.align === 0 || narrow) {
        set(e, 'text-align', 'left'); set(e, 'text-align-last', 'auto');
        // Como antes (y como JW Library): a la izquierda y sin cortar palabras
        set(e, 'hyphens', 'manual'); set(e, '-webkit-hyphens', 'manual');
      }
    }
  }
  // Versalitas: mayúsculas pequeñas como las pensó la editorial; y ninguna palabra más grande que su párrafo
  var SC = /(^|[\s_-])(versalita|versalitas|versal|smallcaps|small-caps|small_caps|smcp|sc|caps|vers)([\s_-]|$)/i;
  var inl = b.querySelectorAll('span,small,abbr,acronym,em,i,b,strong,cite,dfn,var,a,font');
  for (var i = 0; i < inl.length && i < 4000; i++) {
    var e = inl[i], par = e.parentElement;
    if (!par) continue;
    var cs = getComputedStyle(e), pf = parseFloat(getComputedStyle(par).fontSize) || 16, f = parseFloat(cs.fontSize) || pf;
    var t = txt(e), caps = t.length > 1 && t === t.toUpperCase() && /[A-ZÁÉÍÓÚÑÜ]/.test(t);
    var smallcaps = cs.fontVariantCaps === 'small-caps' || cs.fontVariantCaps === 'all-small-caps' || SC.test(e.getAttribute('class') || '') || (e.tagName === 'SMALL' && caps);
    if (smallcaps) set(e, 'font-variant-caps', caps ? 'all-small-caps' : 'small-caps');
    if (!e.closest('h1,h2,h3,h4,h5,h6,[data-zk~=verse],sup,sub') && e.closest('p,li,blockquote') && f > pf * 1.12) set(e, 'font-size', '1em');
  }

  var vl = b.querySelectorAll('[data-zk~=vline]');
  for (var i = 0; i < vl.length; i++) {
    var e = vl[i];
    // Sangría francesa: si un verso se parte, la continuación queda más adentro
    if (parseFloat(getComputedStyle(e).textIndent) >= 0) { set(e, 'padding-left', '1.4em'); set(e, 'text-indent', '-1.4em'); }
  }

  // Letra grande (18 pt o más): los títulos crecen menos que el texto, para no llenar media página
  if (O.size >= 150 && O.mode !== 1) {
    var bodyF = parseFloat(getComputedStyle(b.querySelector('p') || b).fontSize) || 16;
    var hsB = b.querySelectorAll('h1,h2,h3,h4');
    for (var i = 0; i < hsB.length; i++) {
      var h = hsB[i], hf = parseFloat(getComputedStyle(h).fontSize) || bodyF;
      var capB = bodyF * (h.tagName === 'H1' ? 1.4 : h.tagName === 'H2' ? 1.25 : 1.15);
      if (hf > capB) set(h, 'font-size', capB.toFixed(1) + 'px');
    }
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
    var big = O.size >= 150;
    var cap = body * (h.tagName === 'H1' ? (big ? 1.4 : 1.7) : h.tagName === 'H2' ? (big ? 1.25 : 1.5) : (big ? 1.15 : 1.3));
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
window.__zkDone = true;
if (d.fonts && !window.__zkFonts) { window.__zkFonts = 1; d.fonts.ready.then(function(){ window.__zkRun(); }); }
return res;
})()"""
}
