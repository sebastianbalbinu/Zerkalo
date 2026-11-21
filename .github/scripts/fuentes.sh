#!/usr/bin/env bash
# Descarga fuentes libres (OFL) de Google Fonts y las recorta a latín, griego y cirílico
# para que la app pese poco. Se ejecuta en cada compilación; nada de esto vive en el repositorio.
set -uo pipefail
OUT="app/src/main/assets/fonts"
mkdir -p "$OUT" /tmp/fuentes
python3 -m pip install --quiet --break-system-packages fonttools brotli >/dev/null 2>&1 || python3 -m pip install --quiet fonttools brotli >/dev/null 2>&1 || true
BASE="https://raw.githubusercontent.com/google/fonts/main/ofl"
RANGES="U+0000-024F,U+0259,U+02B0-036F,U+0370-03FF,U+0400-04FF,U+1E00-1EFF,U+2000-206F,U+20A0-20CF,U+2100-214F,U+2190-21FF,U+2200-22FF,U+FB00-FB06"

# nombre_en_la_app  carpeta  archivo_en_google_fonts
LIST="
atkinson     atkinsonhyperlegiblenext AtkinsonHyperlegibleNext[wght].ttf
atkinson_i   atkinsonhyperlegiblenext AtkinsonHyperlegibleNext-Italic[wght].ttf
lexend       lexend                   Lexend[wght].ttf
inter        inter                    Inter[opsz,wght].ttf
inter_i      inter                    Inter-Italic[opsz,wght].ttf
sourcesans   sourcesans3              SourceSans3[wght].ttf
sourcesans_i sourcesans3              SourceSans3-Italic[wght].ttf
notosans     notosans                 NotoSans[wdth,wght].ttf
notosans_i   notosans                 NotoSans-Italic[wdth,wght].ttf
andika       andika                   Andika-Regular.ttf
andika_i     andika                   Andika-Italic.ttf
andika_b     andika                   Andika-Bold.ttf
plexsans     ibmplexsans              IBMPlexSans[wdth,wght].ttf
plexsans_i   ibmplexsans              IBMPlexSans-Italic[wdth,wght].ttf
figtree      figtree                  Figtree[wght].ttf
figtree_i    figtree                  Figtree-Italic[wght].ttf
nunito       nunito                   Nunito[wght].ttf
nunito_i     nunito                   Nunito-Italic[wght].ttf
sourceserif  sourceserif4             SourceSerif4[opsz,wght].ttf
sourceserif_i sourceserif4            SourceSerif4-Italic[opsz,wght].ttf
charis       charissil                CharisSIL-Regular.ttf
charis_i     charissil                CharisSIL-Italic.ttf
charis_b     charissil                CharisSIL-Bold.ttf
merriweather merriweather             Merriweather[opsz,wdth,wght].ttf
merriweather_i merriweather           Merriweather-Italic[opsz,wdth,wght].ttf
lora         lora                     Lora[wght].ttf
lora_i       lora                     Lora-Italic[wght].ttf
crimson      crimsonpro               CrimsonPro[wght].ttf
crimson_i    crimsonpro               CrimsonPro-Italic[wght].ttf
newsreader   newsreader               Newsreader[opsz,wght].ttf
newsreader_i newsreader               Newsreader-Italic[opsz,wght].ttf
bitter       bitter                   Bitter[wght].ttf
bitter_i     bitter                   Bitter-Italic[wght].ttf
"
ok=0; fail=0
while read -r name dir file; do
  [ -z "$name" ] && continue
  enc=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" "$file")
  raw="/tmp/fuentes/$name.ttf"
  if ! curl -fsSL --retry 3 --max-time 60 "$BASE/$dir/$enc" -o "$raw"; then
    echo "::warning::No se pudo bajar $file"; fail=$((fail+1)); continue
  fi
  src="$raw"
  # El eje de ancho no se usa: se fija en 100 para ahorrar espacio.
  if [[ "$file" == *wdth* ]]; then
    python3 -m fontTools.varLib.instancer "$raw" wdth=100 -q -o "/tmp/fuentes/$name.w.ttf" && src="/tmp/fuentes/$name.w.ttf"
  fi
  if python3 -m fontTools.subset "$src" --unicodes="$RANGES" --layout-features='*' --output-file="$OUT/$name.ttf" 2>/dev/null; then
    ok=$((ok+1))
  else
    cp "$src" "$OUT/$name.ttf"; ok=$((ok+1))
  fi
done <<< "$LIST"
echo "Fuentes listas: $ok, fallidas: $fail"
du -ch "$OUT"/*.ttf | tail -1
