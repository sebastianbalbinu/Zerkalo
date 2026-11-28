package com.sebas.zerkalo.reader

/**
 * Leer en voz alta con la voz del teléfono: el libro se parte en frases dentro de la página,
 * se resalta la frase que suena y la página pasa sola cuando la frase sigue en la siguiente.
 */
object ReadAloud {
    /** Prepara las frases del capítulo que se ve. Devuelve {start, s:[...]} (start = primera frase visible). */
    const val INIT = """
(function(){
  try {
  var W = window.innerWidth, H = window.innerHeight;
  var st = document.getElementById('zk-ra-css');
  if (!st) { st = document.createElement('style'); st.id = 'zk-ra-css';
    st.textContent = '::highlight(zkra){background-color:__HL__;}'; document.head.appendChild(st); }
  var blocks = [];
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
  var n, cur = null, curEl = null;
  function blockOf(node){ var e = node.parentElement;
    while (e && e !== document.body) { var d = getComputedStyle(e).display;
      if (d !== 'inline' && d !== 'inline-block' && d !== 'contents') return e; e = e.parentElement; }
    return document.body; }
  while ((n = walker.nextNode())) {
    var p = n.parentElement; if (!p) continue;
    var tag = p.tagName; if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'RT' || tag === 'RP') continue;
    if (p.closest && p.closest('sup a, a[epub\\:type="noteref"], .zk-ph')) continue;
    var b = blockOf(n);
    if (b !== curEl) { cur = {nodes: [], text: ''}; blocks.push(cur); curEl = b; }
    cur.nodes.push({n: n, at: cur.text.length}); cur.text += n.nodeValue;
  }
  var re = /[^.!?…。！？]+(?:[.!?…。！？]+["'»”’)\]]*)?\s*/g;
  var out = [], ranges = [];
  function pos(bl, off){ for (var i = bl.nodes.length - 1; i >= 0; i--) if (bl.nodes[i].at <= off)
      return [bl.nodes[i].n, Math.min(off - bl.nodes[i].at, bl.nodes[i].n.nodeValue.length)];
    return [bl.nodes[0].n, 0]; }
  blocks.forEach(function(bl){
    var m; re.lastIndex = 0;
    while ((m = re.exec(bl.text)) !== null) {
      if (m[0].length === 0) { re.lastIndex++; continue; }
      var t = m[0].replace(/\s+/g, ' ').trim();
      if (t.length < 2 || !/[\p{L}\p{N}]/u.test(t)) continue;
      var a = pos(bl, m.index), z = pos(bl, m.index + m[0].replace(/\s+$/, '').length);
      var r = document.createRange(); r.setStart(a[0], a[1]); r.setEnd(z[0], z[1]);
      out.push(t.length > 3800 ? t.slice(0, 3800) : t); ranges.push(r);
    }
  });
  window.__zkra = ranges;
  var start = 0;
  for (var i = 0; i < ranges.length; i++) { var rc = ranges[i].getClientRects()[0];
    if (rc && rc.right > 0 && rc.left < W && rc.bottom > 0 && rc.top < H) { start = i; break; }
    if (rc && (__PAGED__ ? rc.left >= W : rc.top >= H)) { start = i; break; } }
  return JSON.stringify({start: start, s: out});
  } catch (e) { return JSON.stringify({start: 0, s: [], e: String(e)}); }
})()
"""

    /** Resalta la frase i. Devuelve "next"/"prev" si está en otra página (modo páginas). */
    const val MARK = """
(function(i){
  var rs = window.__zkra; if (!rs || !rs[i]) return 'none';
  try { if (window.CSS && CSS.highlights) CSS.highlights.set('zkra', new Highlight(rs[i])); } catch (e) {}
  var rc = rs[i].getClientRects()[0]; if (!rc) return 'ok';
  var W = window.innerWidth, H = window.innerHeight;
  if (__PAGED__) { if (rc.left >= W - 2) return 'next'; if (rc.right <= 2) return 'prev'; return 'ok'; }
  if (rc.top < H * 0.12 || rc.bottom > H * 0.82) {
    var se = document.scrollingElement || document.documentElement;
    se.scrollBy({top: rc.top - H * 0.32, behavior: 'smooth'});
  }
  return 'ok';
})(__I__)
"""

    const val CLEAR = "(function(){ try { CSS.highlights.delete('zkra'); } catch (e) {} window.__zkra = null; return 'ok'; })()"

    val RATES = listOf(0.8f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    val SLEEP = listOf(0, 15, 30, 45, 60)
}
