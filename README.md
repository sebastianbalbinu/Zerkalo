# Zerkalo 鏡

Lector EPUB gratuito, sin anuncios y sin servidores propios, con alma japonesa.

- **App nativa** en Kotlin con Jetpack Compose: fluida y liviana.
- **Lector**: [Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit) (BSD), paginado, con tipografías Literata, Shippori Mincho, EB Garamond y Zen Kaku Gothic New; papel Crema, Nogal y Sumi (OLED); shiori, subrayados con notas e índice.
- **Desenfoque progresivo** nativo con [Haze](https://github.com/chrisbanes/haze) (Apache 2.0). Portadas con [Coil](https://github.com/coil-kt/coil).
- **Tansu**: Project Gutenberg (Gutendex con respaldo del OPDS oficial), Wikisource, OAPEN, Standard Ebooks y cualquier catálogo OPDS.
- Importa EPUB propios y abre los que llegan por WhatsApp, Drive o Archivos. Detecta y explica los libros con DRM.

Cada push a `main` compila, prueba en emuladores Android 14, 15 y 16 y, si todo pasa, publica la versión en Releases. La app se actualiza sola.
