# Zerkalo · 鏡

Lector EPUB gratuito, sin anuncios y sin servidores propios, con las bibliotecas libres del mundo:
Project Gutenberg, Wikisource, OAPEN, Standard Ebooks y cualquier catálogo OPDS.

- Estética *chashitsu*: crema y keyaki de día, nogal de noche.
- Encuadernación japonesa para cada libro, sellos de lectura, shiori y subrayados.
- Se actualiza sola desde las Releases de este repositorio.

## Instalar

Descarga `Zerkalo.apk` de la [última versión](https://github.com/sebastianbalbinu/Zerkalo/releases/latest).
Desde ahí, la app se actualiza sola.

## Cómo está hecho

- `app/src/main/java` — envoltura Android en Kotlin: WebView, puente nativo (descargas, importar EPUB,
  recordatorio de lectura) y actualizador.
- `app/src/main/assets/www` — la interfaz (HTML, CSS y JavaScript) y el lector (epub.js).
- `.github/workflows/build.yml` — compila y publica la APK firmada en cada cambio.
