package com.sebas.zerkalo.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import java.io.ByteArrayInputStream

/**
 * Modo continuo: todo el libro en una sola página larga, como el desplazamiento de Apple Books.
 *
 * Readium muestra los libros capítulo por capítulo, así que este modo tiene su propio motor: una página
 * "cascarón" a la que se le van pegando los capítulos (hacia abajo y hacia arriba) a medida que la persona
 * avanza. Los archivos del libro se sirven desde la publicación abierta, sin copiarlos a ningún lado.
 * La posición se informa como capítulo + progresión, igual que en el modo páginas, así se puede cambiar
 * de modo sin perder el lugar.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class ContinuousView(ctx: Context, private val pub: Publication, private val cb: Callbacks) : WebView(ctx) {

    interface Callbacks {
        /** El capítulo [index] está a la altura de la lectura, a [progression] (0..1) de su largo. */
        fun onPosition(index: Int, progression: Double)
        /**
         * Toque fuera de un enlace: [fx] y [fy] van de 0 a 1 (izquierda→derecha, arriba→abajo);
         * [x] e [y] son el punto en píxeles de la pantalla (para traducir la palabra tocada).
         */
        fun onTap(fx: Float, fy: Float, x: Float, y: Float)
        /** Se tocó un subrayado o una nota adhesiva. */
        fun onMark(id: String) {}
        /** Se eligió una opción del menú de texto seleccionado; [json] describe la selección (o null si no había). */
        fun onSelection(id: Int, json: String?) {}
        /** Se agregó un capítulo a la página (hay que volver a diagramar). */
        fun onLoaded(index: Int)
        /** Ya se ve el punto de partida. */
        fun onReady()
    }

    private val main = Handler(Looper.getMainLooper())
    private val links: List<Link> = pub.readingOrder
    private val byPath: Map<String, Link> = (pub.readingOrder + pub.resources).associateBy { it.url().toString().substringBefore('#') }

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.textZoom = 100
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(0)
        addJavascriptInterface(Bridge(), "ZKA")
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w("Zerkalo", "ZK_CONT_JS ${m.message()} (${m.lineNumber()})")
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url
                if (u.host != HOST) return null
                val path = (u.encodedPath ?: "/").removePrefix("/")
                return when {
                    path == "__shell.html" -> html(SHELL.toByteArray())
                    path.startsWith("__gfont/") -> GoogleFonts.webFile(context, path)?.let { f ->
                        WebResourceResponse(if (f.extension == "woff2") "font/woff2" else "font/ttf", null, f.inputStream())
                    }
                    path.startsWith("__font/") -> runCatching {
                        val name = path.removePrefix("__font/").substringBefore('?')
                        WebResourceResponse("font/ttf", null, context.assets.open("fonts/$name"))
                    }.getOrNull()
                    else -> book(path)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
    }

    private fun html(bytes: ByteArray) = WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))

    /** Un archivo del libro (capítulo, imagen, hoja de estilos, letra), leído de la publicación. */
    private fun book(path: String): WebResourceResponse? {
        val link = byPath[path] ?: byPath[android.net.Uri.decode(path)] ?: byPath.entries.firstOrNull { android.net.Uri.decode(it.key) == android.net.Uri.decode(path) }?.value
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val bytes = runCatching { runBlocking { pub.get(link.url())?.read()?.getOrNull() } }.getOrNull()
            ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val type = link.mediaType?.toString()?.substringBefore(';') ?: guess(path)
        val charset = if (type.startsWith("text/") || type.contains("xml") || type.contains("html")) "utf-8" else null
        return WebResourceResponse(type, charset, ByteArrayInputStream(bytes))
    }

    private fun guess(p: String): String = when (p.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    fun urlOf(index: Int): String = BASE + links[index].url().toString().substringBefore('#')

    private var pending: String? = null
    private var shellReady = false

    /** Abre el libro en el capítulo [index], a [progression] de su largo. */
    fun start(index: Int, progression: Double, css: String, marks: JSONArray) {
        val cfg = JSONObject()
            .put("ch", JSONArray(links.indices.map { urlOf(it) }))
            .put("i", index.coerceIn(0, (links.size - 1).coerceAtLeast(0)))
            .put("p", progression.coerceIn(0.0, 1.0))
            .put("css", css)
            .put("marks", marks)
        pending = "window.zk && zk.start(${cfg});"
        loadUrl(BASE + "__shell.html")
    }

    private inner class Bridge {
        @JavascriptInterface fun shell() = main.post {
            shellReady = true
            pending?.let { evaluateJavascript(it, null) }
            pending = null
        }
        @JavascriptInterface fun pos(i: Int, p: Double) = main.post { cb.onPosition(i, p) }
        @JavascriptInterface fun tap(fx: Float, fy: Float, x: Float, y: Float) = main.post {
            val dp = resources.displayMetrics.density
            cb.onTap(fx, fy, x * dp, y * dp)
        }
        @JavascriptInterface fun mark(id: String) = main.post { cb.onMark(id) }
        @JavascriptInterface fun loaded(i: Int) = main.post { cb.onLoaded(i) }
        @JavascriptInterface fun ready() = main.post { cb.onReady() }
    }

    /** Todo el libro va hacia abajo: nunca se deja correr hacia los lados (cortaría el texto). */
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        if (l != 0) { scrollTo(0, t); return }
        super.onScrollChanged(l, t, oldl, oldt)
    }

    fun js(code: String) { if (shellReady) evaluateJavascript(code, null) }

    /** El menú del texto seleccionado: el mismo que en el modo Páginas (lo más usado primero). */
    var menu: List<Pair<Int, String>> = emptyList()

    override fun startActionMode(callback: android.view.ActionMode.Callback, type: Int): android.view.ActionMode? {
        if (menu.isEmpty()) return super.startActionMode(callback, type)
        val wrap = object : android.view.ActionMode.Callback2() {
            override fun onCreateActionMode(mode: android.view.ActionMode, m: android.view.Menu): Boolean {
                callback.onCreateActionMode(mode, m)
                m.clear()
                menu.forEachIndexed { i, (id, title) -> m.add(android.view.Menu.NONE, id, i, title) }
                return true
            }
            override fun onPrepareActionMode(mode: android.view.ActionMode, m: android.view.Menu): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                val id = item.itemId
                // Primero se lee la selección (al cerrar el menú se borra) y después se cierra
                evaluateJavascript("window.zk ? zk.sel() : ''") { raw ->
                    val s = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() }
                    mode.finish()
                    cb.onSelection(id, s)
                }
                return true
            }
            override fun onDestroyActionMode(mode: android.view.ActionMode) { callback.onDestroyActionMode(mode) }
            override fun onGetContentRect(mode: android.view.ActionMode, view: android.view.View, outRect: android.graphics.Rect) {
                if (callback is android.view.ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
            }
        }
        return super.startActionMode(wrap, type)
    }

    fun clearSelection() = js("window.getSelection && window.getSelection().removeAllRanges();")

    fun applyCss(css: String) = js("zk.css(${JSONObject.quote(css)});")
    fun go(index: Int, progression: Double) = js("zk.go($index, $progression);")
    fun goHref(url: String) = js("zk.goHref(${JSONObject.quote(url)});")
    fun page(dir: Int) = js("zk.page($dir);")
    fun setMarks(marks: JSONArray) = js("zk.marks($marks);")

    companion object {
        const val HOST = "zerkalo.libro"
        const val BASE = "https://$HOST/"

        /** La página cascarón con el motor (en JavaScript) que pega los capítulos. */
        private const val SHELL = """<!DOCTYPE html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<style id="zk-base"></style>
</head><body>
<script>
(function(){
var Z = { ch: [], marks: [], busy: false, started: false, lastPos: 0 };
var d = document;
function secs() { return [].slice.call(d.querySelectorAll('section.zk-ch')); }
function sec(i) { return d.querySelector('section.zk-ch[data-i="' + i + '"]'); }
function pad() { var v = parseFloat(getComputedStyle(d.documentElement).getPropertyValue('--zk-pt')); return isNaN(v) ? 0 : v; }

function abs(root, base) {
  var els = root.querySelectorAll('[src],[href],[poster]');
  for (var k = 0; k < els.length; k++) {
    var el = els[k];
    ['src', 'href', 'poster'].forEach(function(a){
      var v = el.getAttribute(a);
      if (!v) return;
      if (a === 'href' && v.charAt(0) === '#') { el.setAttribute(a, base.split('#')[0] + v); return; }
      if (/^(data:|blob:|[a-z][a-z0-9+.-]*:)/i.test(v)) return;
      try { el.setAttribute(a, new URL(v, base).href); } catch (e) {}
    });
  }
  var imgs = root.getElementsByTagName('image');
  for (var j = 0; j < imgs.length; j++) {
    var im = imgs[j], x = im.getAttribute('xlink:href') || im.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || im.getAttribute('href');
    if (x && !/^(data:|[a-z][a-z0-9+.-]*:)/i.test(x)) { try { im.setAttributeNS('http://www.w3.org/1999/xlink', 'href', new URL(x, base).href); } catch (e) {} }
  }
}

function styles(doc, url) {
  var links = doc.querySelectorAll('link[rel~="stylesheet"],link[rel~="Stylesheet"]');
  for (var k = 0; k < links.length; k++) {
    var h; try { h = new URL(links[k].getAttribute('href'), url).href; } catch (e) { continue; }
    if (d.querySelector('link[data-src="' + h + '"]')) continue;
    var n = d.createElement('link'); n.rel = 'stylesheet'; n.href = h; n.setAttribute('data-src', h);
    d.head.insertBefore(n, d.getElementById('zk-base'));
  }
  var sts = doc.querySelectorAll('style');
  for (var s = 0; s < sts.length; s++) {
    var t = sts[s].textContent || '';
    if (!t.trim()) continue;
    var key = t.length + ':' + t.slice(0, 80);
    if (d.querySelector('style[data-k="' + key.replace(/["\\]/g, '') + '"]')) continue;
    var st = d.createElement('style'); st.textContent = t; st.setAttribute('data-k', key.replace(/["\\]/g, ''));
    d.head.insertBefore(st, d.getElementById('zk-base'));
  }
}

// Busca un texto subrayado dentro de un capítulo y lo marca (con su color)
function markIn(root, m) {
  var text = (m.t || '').replace(/\s+/g, ' ').trim();
  if (text.length < 2) return;
  var walker = d.createTreeWalker(root, NodeFilter.SHOW_TEXT, null), nodes = [], all = '';
  while (walker.nextNode()) { var nd = walker.currentNode; nodes.push({ n: nd, s: all.length }); all += nd.nodeValue; }
  var flat = all.replace(/\s/g, ' ');
  var before = (m.b || '').replace(/\s+/g, ' ').slice(-30);
  var start = -1, from = 0;
  while (true) {
    var at = flat.indexOf(text.split(' ')[0], from);
    if (at < 0) break;
    var chunk = flat.slice(at, at + text.length * 2).replace(/ +/g, ' ');
    if (chunk.indexOf(text) === 0) {
      var pre = flat.slice(Math.max(0, at - 60), at).replace(/ +/g, ' ');
      if (!before || pre.slice(-before.length) === before || start < 0) { start = at; if (!before || pre.slice(-before.length) === before) break; }
    }
    from = at + 1;
  }
  if (start < 0) return;
  var end = start, need = text.length, seen = 0;
  while (end < flat.length && seen < need) { if (!(flat.charAt(end) === ' ' && flat.charAt(end - 1) === ' ')) seen++; end++; }
  var lastMk = null;
  for (var q = nodes.length - 1; q >= 0; q--) {
    var a = nodes[q], len = a.n.nodeValue.length, s0 = Math.max(start, a.s), e0 = Math.min(end, a.s + len);
    if (s0 >= e0) continue;
    try {
      var r = d.createRange(); r.setStart(a.n, s0 - a.s); r.setEnd(a.n, e0 - a.s);
      var mk = d.createElement('mark'); mk.className = 'zk-hl'; mk.style.cssText = 'background:' + m.c + ' !important;color:inherit !important;border-radius:2px';
      if (m.id) mk.setAttribute('data-id', m.id);
      r.surroundContents(mk);
      if (!lastMk) lastMk = mk;
    } catch (e) {}
  }
  // Nota adhesiva: un papelito al final de la frase
  if (m.k === 's' && lastMk && lastMk.parentNode) {
    var ic = d.createElement('span'); ic.className = 'zk-stk'; ic.setAttribute('data-id', m.id);
    lastMk.parentNode.insertBefore(ic, lastMk.nextSibling);
  }
}

function load(i, where) {
  if (i < 0 || i >= Z.ch.length || sec(i)) return Promise.resolve(false);
  var url = Z.ch[i];
  return fetch(url).then(function(r){ return r.text(); }).then(function(t){
    var xml = /\.x?html?$/i.test(url) && t.indexOf('<?xml') >= 0;
    var doc = new DOMParser().parseFromString(t, xml ? 'application/xhtml+xml' : 'text/html');
    if (doc.getElementsByTagName('parsererror').length) doc = new DOMParser().parseFromString(t, 'text/html');
    styles(doc, url);
    var body = doc.body || doc.documentElement;
    var s = d.createElement('section'); s.className = 'zk-ch'; s.setAttribute('data-i', i); s.setAttribute('data-href', url);
    var inner = d.createElement('div'); inner.className = 'zk-body ' + (body.getAttribute('class') || '');
    var kids = [].slice.call(body.childNodes);
    for (var k = 0; k < kids.length; k++) inner.appendChild(d.importNode(kids[k], true));
    abs(inner, url);
    s.appendChild(inner);
    var list = secs();
    if (where < 0 && list.length) {
      // Se pega arriba: se mantiene a la vista lo que se estaba leyendo
      var anchor = atLine(), before = anchor ? anchor.getBoundingClientRect().top : 0;
      d.body.insertBefore(s, list[0]);
      if (anchor) { var after = anchor.getBoundingClientRect().top; if (Math.abs(after - before) > 1) window.scrollBy(0, after - before); }
    } else {
      var next = list.filter(function(x){ return +x.getAttribute('data-i') > i; })[0];
      if (next) d.body.insertBefore(s, next); else d.body.appendChild(s);
    }
    Z.marks.filter(function(m){ return m.h === url; }).forEach(function(m){ markIn(inner, m); });
    ZKA.loaded(i);
    return true;
  }).catch(function(e){ console.error('capitulo ' + i + ': ' + e); return false; });
}

// El elemento que está a la altura de la lectura (justo debajo del margen de arriba)
function atLine() {
  var y = pad() + 6, w = window.innerWidth;
  var e = d.elementFromPoint(w / 2, y) || d.elementFromPoint(w * 0.3, y);
  return e && e.closest('section.zk-ch') ? e : null;
}

function report() {
  var y = pad() + 4, list = secs();
  for (var k = 0; k < list.length; k++) {
    var r = list[k].getBoundingClientRect();
    if (r.top <= y && r.bottom > y) {
      var p = (y - r.top) / Math.max(1, r.height);
      ZKA.pos(+list[k].getAttribute('data-i'), Math.max(0, Math.min(1, p)));
      return;
    }
  }
}

// Mantiene pegados los capítulos de arriba y de abajo, y suelta los que quedaron lejos
function keep() {
  if (Z.busy || !Z.started) return;
  var list = secs(); if (!list.length) return;
  var h = window.innerHeight, first = list[0], last = list[list.length - 1];
  var job = null;
  if (last.getBoundingClientRect().bottom < h * 3) job = load(+last.getAttribute('data-i') + 1, 1);
  else if (first.getBoundingClientRect().top > -h * 1.5) job = load(+first.getAttribute('data-i') - 1, -1);
  else if (list.length > 6) {
    // Demasiados capítulos pegados: se quita el más lejano (si es de arriba, sin mover lo que se lee)
    var a = list[0].getBoundingClientRect(), b = list[list.length - 1].getBoundingClientRect();
    if (-a.bottom > b.top - h) { var anchor = atLine(), t0 = anchor ? anchor.getBoundingClientRect().top : 0; list[0].remove(); if (anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - t0); }
    else list[list.length - 1].remove();
  }
  if (job) { Z.busy = true; job.then(function(){ Z.busy = false; setTimeout(keep, 30); }); }
}

var tick = 0;
window.addEventListener('scroll', function(){
  if (tick) return;
  tick = setTimeout(function(){ tick = 0; report(); keep(); }, 120);
}, { passive: true });

d.addEventListener('click', function(ev){
  var a = ev.target.closest && ev.target.closest('a[href]');
  if (a) {
    ev.preventDefault();
    var h = a.getAttribute('href') || '';
    if (h.indexOf('https://zerkalo.libro/') === 0) zk.goHref(h);
    return;
  }
  var sel = window.getSelection && window.getSelection();
  if (sel && !sel.isCollapsed) return;
  // Tocar un subrayado o una nota adhesiva la abre
  var mkd = ev.target.closest && ev.target.closest('.zk-stk,mark.zk-hl[data-id]');
  if (mkd) { ev.preventDefault(); ZKA.mark(mkd.getAttribute('data-id')); return; }
  ZKA.tap(ev.clientX / Math.max(1, window.innerWidth), ev.clientY / Math.max(1, window.innerHeight), ev.clientX, ev.clientY);
}, true);

function scrollToIn(s, p) {
  var r = s.getBoundingClientRect();
  window.scrollTo(0, window.scrollY + r.top + p * r.height - pad());
}

window.zk = {
  start: function(cfg) {
    Z.ch = cfg.ch; Z.marks = cfg.marks || [];
    zk.css(cfg.css);
    Z.busy = true;
    load(cfg.i, 1).then(function(){
      var s = sec(cfg.i);
      // Se espera a que carguen letras e imágenes del capítulo antes de ubicar el punto
      var go = function(){ if (s) scrollToIn(s, cfg.p); };
      go();
      (d.fonts ? d.fonts.ready : Promise.resolve()).then(function(){
        setTimeout(function(){ go(); Z.busy = false; Z.started = true; ZKA.ready(); keep(); report(); }, 120);
      });
    });
  },
  css: function(css) { d.getElementById('zk-base').textContent = css; },
  go: function(i, p) {
    var s = sec(i);
    if (s) { scrollToIn(s, p); report(); return; }
    // Capítulo lejano: se arma de nuevo desde ahí
    Z.started = false; Z.busy = true;
    secs().forEach(function(x){ x.remove(); });
    load(i, 1).then(function(){ var t = sec(i); if (t) scrollToIn(t, p); setTimeout(function(){ if (t) scrollToIn(t, p); Z.busy = false; Z.started = true; keep(); report(); }, 150); });
  },
  goHref: function(url) {
    var base = url.split('#')[0], frag = url.split('#')[1], i = Z.ch.indexOf(base);
    if (i < 0) return;
    var then = function(){
      var s = sec(i); if (!s) return;
      var t = frag ? s.querySelector('[id="' + frag.replace(/"/g, '') + '"],[name="' + frag.replace(/"/g, '') + '"]') : null;
      if (t) window.scrollTo(0, window.scrollY + t.getBoundingClientRect().top - pad() - 4); else scrollToIn(s, 0);
      report();
    };
    if (sec(i)) then(); else { zk.go(i, 0); setTimeout(then, 400); }
  },
  page: function(dir) {
    var lh = parseFloat(getComputedStyle(d.body).lineHeight) || 28;
    var visible = window.innerHeight - pad() - (parseFloat(getComputedStyle(d.documentElement).getPropertyValue('--zk-pb')) || 0);
    window.scrollBy({ top: dir * Math.max(lh * 3, visible - lh * 1.5), behavior: 'smooth' });
  },
  // El texto seleccionado: capítulo, punto del capítulo y las palabras de antes y después (para ubicarlo siempre)
  sel: function() {
    var s = window.getSelection && window.getSelection();
    if (!s || s.isCollapsed || !s.rangeCount) return '';
    var r = s.getRangeAt(0), el = r.startContainer.nodeType === 1 ? r.startContainer : r.startContainer.parentElement;
    var sc = el && el.closest('section.zk-ch'); if (!sc) return '';
    var pre = d.createRange(); pre.setStart(sc, 0); pre.setEnd(r.startContainer, r.startOffset);
    var post = d.createRange(); post.setStart(r.endContainer, r.endOffset); post.setEnd(sc, sc.childNodes.length);
    var sr = sc.getBoundingClientRect(), rr = r.getBoundingClientRect();
    return JSON.stringify({ i: +sc.getAttribute('data-i'), t: s.toString(), b: pre.toString().slice(-200), a: post.toString().slice(0, 200),
      p: Math.max(0, Math.min(1, (rr.top - sr.top) / Math.max(1, sr.height))) });
  },
  marks: function(list) {
    Z.marks = list || [];
    [].slice.call(d.querySelectorAll('.zk-stk')).forEach(function(x){ x.remove(); });
    [].slice.call(d.querySelectorAll('mark.zk-hl')).forEach(function(m){ var p = m.parentNode; while (m.firstChild) p.insertBefore(m.firstChild, m); p.removeChild(m); p.normalize(); });
    secs().forEach(function(s){ var url = s.getAttribute('data-href'); Z.marks.filter(function(m){ return m.h === url; }).forEach(function(m){ markIn(s, m); }); });
  }
};
ZKA.shell();
})();
</script>
</body></html>"""
    }
}
