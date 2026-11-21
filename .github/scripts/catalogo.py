#!/usr/bin/env python3
"""
Arma el catálogo propio de Zerkalo a partir de los datos oficiales de Project Gutenberg.

Gutenberg publica cada semana los datos de todos sus libros (archivos RDF). Este script los
descarga una sola vez (de Gutenberg o de uno de sus espejos oficiales), se queda con los
libros de texto en EPUB y escribe un archivo pequeño por idioma, ordenado por descargas:

    gutenberg-es.json.gz, gutenberg-en.json.gz, ...

Así la app no tiene que consultar a Gutenberg para mostrar el Tansu: solo para bajar el libro.
"""
import gzip
import io
import json
import re
import sys
import tarfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

OUT = Path(sys.argv[1] if len(sys.argv) > 1 else "catalogo")
OUT.mkdir(parents=True, exist_ok=True)

LANGS = ["es", "en", "fr", "pt", "de", "it", "ru", "ja", "la"]
CAP = {"en": 5000}          # inglés tiene decenas de miles: los más leídos bastan
DEFAULT_CAP = 4000
# Mismo orden que Catalogs.TOPICS en la app (cada tema es un bit)
TOPICS = [
    ("fiction", ["fiction", "novel"]),
    ("poetry", ["poetry", "poems", "poesía"]),
    ("short stories", ["short stories", "cuentos"]),
    ("drama", ["drama", "plays", "teatro"]),
    ("philosophy", ["philosophy", "ethics"]),
    ("history", ["history"]),
    ("adventure", ["adventure"]),
    ("science fiction", ["science fiction"]),
    ("children", ["juvenile", "children"]),
]

SOURCES = [
    "https://www.gutenberg.org/cache/epub/feeds/rdf-files.tar.bz2",
    "https://aleph.gutenberg.org/cache/epub/feeds/rdf-files.tar.bz2",
    "https://gutenberg.pglaf.org/cache/epub/feeds/rdf-files.tar.bz2",
    "https://mirror.cs.odu.edu/gutenberg-epub/feeds/rdf-files.tar.bz2",
]
UA = "ZerkaloCatalogo/1.0 (+https://github.com/sebastianbalbinu/Zerkalo; una descarga semanal)"

NS = {
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "dcterms": "http://purl.org/dc/terms/",
    "pgterms": "http://www.gutenberg.org/2009/pgterms/",
}
RDF_ABOUT = "{%s}about" % NS["rdf"]


def download():
    for url in SOURCES:
        for attempt in range(3):
            try:
                print(f"Descargando {url} (intento {attempt + 1})", flush=True)
                req = urllib.request.Request(url, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=120) as r:
                    data = r.read()
                if len(data) > 10_000_000:
                    print(f"  ok: {len(data) / 1e6:.1f} MB", flush=True)
                    return data
                print(f"  muy pequeño ({len(data)} bytes)")
            except Exception as e:
                print(f"  falló: {e}")
                time.sleep(10 * (attempt + 1))
    raise SystemExit("No se pudo descargar el catálogo de Gutenberg de ninguna fuente")


def nice_name(n):
    m = re.match(r"^([^,]+),\s*(.+)$", n)
    s = f"{m.group(2)} {m.group(1)}" if m else n
    return re.sub(r"\s*\(.*?\)\s*", " ", s).strip()


def values(el, path):
    return [v.text.strip() for v in el.findall(path, NS) if v.text and v.text.strip()]


def parse(xml_bytes):
    root = ET.fromstring(xml_bytes)
    book = root.find("pgterms:ebook", NS)
    if book is None:
        return None
    about = book.get(RDF_ABOUT, "")
    m = re.search(r"(\d+)$", about)
    if not m:
        return None
    if "Text" not in values(book, "dcterms:type/rdf:Description/rdf:value"):
        return None
    formats = " ".join(f.get(RDF_ABOUT, "") for f in book.findall("dcterms:hasFormat/pgterms:file", NS))
    if "epub" not in formats:
        return None
    title = (book.findtext("dcterms:title", default="", namespaces=NS) or "").strip()
    title = re.sub(r"\s*[\r\n]+\s*", " — ", title)
    authors = [nice_name(n) for n in values(book, "dcterms:creator/pgterms:agent/pgterms:name")]
    langs = values(book, "dcterms:language/rdf:Description/rdf:value")
    downloads = int(book.findtext("pgterms:downloads", default="0", namespaces=NS) or 0)
    tags = " ".join(values(book, "dcterms:subject/rdf:Description/rdf:value")
                    + values(book, "pgterms:bookshelf/rdf:Description/rdf:value")).lower()
    mask = 0
    for i, (_, words) in enumerate(TOPICS):
        if any(w in tags for w in words):
            mask |= 1 << i
    summary = (book.findtext("pgterms:marc520", default="", namespaces=NS) or "").strip()
    summary = re.sub(r"\s*\(This is an automatically generated summary\.\)\s*", "", summary)
    if len(summary) > 320:
        summary = summary[:317].rsplit(" ", 1)[0] + "…"
    return int(m.group(1)), title, ", ".join(authors[:2]), langs, downloads, mask, summary


def main():
    data = download()
    by_lang = defaultdict(list)
    n = 0
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:bz2") as tar:
        for member in tar:
            if not member.isfile() or not member.name.endswith(".rdf"):
                continue
            f = tar.extractfile(member)
            if f is None:
                continue
            try:
                rec = parse(f.read())
            except ET.ParseError:
                continue
            if rec is None:
                continue
            n += 1
            pid, title, author, langs, downloads, mask, summary = rec
            for lang in langs:
                if lang in LANGS:
                    by_lang[lang].append([pid, title, author, downloads, mask, summary])
    print(f"{n} libros de texto en EPUB", flush=True)
    stamp = time.strftime("%Y-%m-%d")
    index = {"generated": stamp, "topics": [t for t, _ in TOPICS], "langs": {}}
    for lang in LANGS:
        books = sorted(by_lang.get(lang, []), key=lambda b: -b[3])[: CAP.get(lang, DEFAULT_CAP)]
        doc = {"v": 1, "lang": lang, "generated": stamp, "topics": index["topics"], "books": books}
        raw = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        (OUT / f"gutenberg-{lang}.json.gz").write_bytes(gzip.compress(raw, 9))
        index["langs"][lang] = len(books)
        print(f"  {lang}: {len(books)} libros, {len(raw) / 1e3:.0f} KB sin comprimir")
    (OUT / "indice.json").write_text(json.dumps(index, ensure_ascii=False, indent=1))
    if sum(index["langs"].values()) < 1000:
        raise SystemExit("El catálogo salió sospechosamente vacío; no se publica")


if __name__ == "__main__":
    main()
