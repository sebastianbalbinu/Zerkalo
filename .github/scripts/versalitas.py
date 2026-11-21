"""Anota qué letras del lector traen versalitas propias (rasgo OpenType smcp o c2sc).

La app lo muestra en el selector de letras: las que no las traen las fabrica el teléfono y se nota.
Uso: python3 .github/scripts/versalitas.py app/src/main/assets/fonts
"""
import os
import sys

from fontTools.ttLib import TTFont

out = sys.argv[1]
con = []
for f in sorted(os.listdir(out)):
    if not f.endswith(".ttf"):
        continue
    try:
        t = TTFont(os.path.join(out, f), lazy=True)
        g = t["GSUB"].table if "GSUB" in t else None
        tags = {r.FeatureTag for r in g.FeatureList.FeatureRecord} if g and g.FeatureList else set()
        if tags & {"smcp", "c2sc"}:
            con.append(f)
    except Exception as e:  # una letra rota no debe frenar la compilación
        print(f"::warning::{f}: {e}")
with open(os.path.join(out, "versalitas.txt"), "w") as fh:
    fh.write("\n".join(con))
print("Letras con versalitas:", ", ".join(con) or "ninguna")
