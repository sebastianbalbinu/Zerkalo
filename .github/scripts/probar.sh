#!/usr/bin/env bash
# Prueba real en un emulador Android: instala, abre la biblioteca, el Tansu y el lector, y toma fotos.
set -x
T="timeout 90"
$T adb shell getprop ro.build.version.release
$T adb shell dumpsys package com.google.android.webview | grep versionName | head -2
$T adb install -r $( [ -f Zerkalo-x86_64.apk ] && echo Zerkalo-x86_64.apk || echo Zerkalo.apk )
$T adb logcat -c
$T adb shell am start -W -n com.sebas.zerkalo/.MainActivity
sleep 20
$T adb exec-out screencap -p > shot1.png
SIZE=$($T adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
# Pestaña Tansu (segunda de cuatro en la barra flotante)
[ -n "$W" ] && $T adb shell input tap $((W*375/1000)) $((H - H*70/1000))
sleep 30
$T adb exec-out screencap -p > shot2.png
# Fluidez: deslizar el Tansu y medir los cuadros (gfxinfo)
$T adb shell dumpsys gfxinfo com.sebas.zerkalo reset > /dev/null
if [ -n "$W" ]; then
  for i in 1 2 3 4; do $T adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*30/100)) 250; sleep 1; done
  for i in 1 2; do $T adb shell input swipe $((W*85/100)) $((H*45/100)) $((W*15/100)) $((H*45/100)) 250; sleep 1; done
  for i in 1 2 3 4; do $T adb shell input swipe $((W/2)) $((H*30/100)) $((W/2)) $((H*75/100)) 250; sleep 1; done
fi
$T adb shell dumpsys gfxinfo com.sebas.zerkalo > gfx-tansu.txt
# Abrir el libro de bienvenida en el lector
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida
# Espera a que el libro abra (hasta 60 s: el emulador de Android 16 a veces es lento)
for i in $(seq 1 30); do $T adb logcat -d | grep -q ZK_READER_OK && break; sleep 2; done
sleep 3
$T adb exec-out screencap -p > shot3.png
# Pasar de página tocando el borde derecho y mostrar los controles
[ -n "$W" ] && $T adb shell input tap $((W*92/100)) $((H/2))
sleep 3
[ -n "$W" ] && $T adb shell input tap $((W/2)) $((H/2))
sleep 3
$T adb exec-out screencap -p > shot4.png
# Modo continuo: el mismo libro, todo seguido (sin cambiar los ajustes guardados)
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida --ez zerkalo.continuo true
for i in $(seq 1 30); do $T adb logcat -d | grep -q ZK_CONT_OK && break; sleep 2; done
sleep 3
[ -n "$W" ] && for i in 1 2 3 4 5 6; do $T adb shell input swipe $((W/2)) $((H*75/100)) $((W/2)) $((H*25/100)) 300; sleep 1; done
sleep 3
$T adb exec-out screencap -p > shot5.png
# Lector de PDF: abre un PDF corto, mantiene presionado un texto (menú con "Al apunte") y toma fotos
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir prueba-pdf
for i in $(seq 1 30); do $T adb logcat -d | grep -q ZK_PDF_READY && break; sleep 2; done
sleep 3
$T adb exec-out screencap -p > shot6.png
[ -n "$W" ] && $T adb shell input swipe $((W*30/100)) $((W*14/100 + H*6/100)) $((W*30/100)) $((W*14/100 + H*6/100)) 1200
sleep 3
$T adb exec-out screencap -p > shot7.png
# Letra de Google descargada + letra grande (22 pt) en el lector por páginas
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.gfont Alegreya
for i in $(seq 1 30); do $T adb logcat -d | grep -qE "ZK_GFONT_OK|ZK_GFONT_FAIL" && break; sleep 2; done
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida
sleep 12
$T adb exec-out screencap -p > shot8.png
[ -n "$W" ] && $T adb shell input tap $((W*85/100)) $((H/2))
sleep 4
$T adb exec-out screencap -p > shot9.png
# El menú Aa nuevo (lo básico) abierto sobre el libro
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida --es zerkalo.hoja type
sleep 12
$T adb exec-out screencap -p > shot10.png
# Papel 3D: deslizar lento (foto a mitad del doblez), tocar, y volver atrás
$T adb shell settings put secure immersive_mode_confirmations confirmed
$T adb shell input keyevent KEYCODE_BACK; sleep 1
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida --ez zerkalo.papel true
sleep 12
if [ -n "$W" ]; then
  $T adb shell input tap $((W*85/100)) $((H/2)); sleep 3
  $T adb shell input tap $((W/2)) $((H/2)); sleep 1; $T adb shell input tap $((W/2)) $((H/2)); sleep 2
  # Dedo lento hacia la izquierda: foto a mitad del doblez
  $T adb shell input swipe $((W*85/100)) $((H*85/100)) $((W*25/100)) $((H*72/100)) 1800 &
  sleep 1.0; $T adb exec-out screencap -p > shot11.png; wait; sleep 2
  # Toque a la derecha: foto a mitad de la animación
  $T adb shell input tap $((W*88/100)) $((H/2)) &
  sleep 0.25; $T adb exec-out screencap -p > shot12.png; wait; sleep 2
  # Dedo lento hacia la derecha (volver): foto a mitad
  $T adb shell input swipe $((W*15/100)) $((H*80/100)) $((W*75/100)) $((H*70/100)) 1800 &
  sleep 1.0; $T adb exec-out screencap -p > shot13.png; wait; sleep 2
  # Salir a la pantalla de inicio y volver: la app no puede congelarse
  $T adb shell input keyevent KEYCODE_HOME; sleep 3
  $T adb shell monkey -p com.sebas.zerkalo -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  $T adb shell input tap $((W*88/100)) $((H/2)); sleep 3
  $T adb exec-out screencap -p > shot14.png
  $T adb shell input keyevent KEYCODE_HOME; sleep 2
  $T adb shell monkey -p com.sebas.zerkalo -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
  $T adb shell input tap $((W*88/100)) $((H/2)); sleep 2
fi
$T adb logcat -d > logcat-full.txt
{
  echo "-- Pantalla --"; grep -E "ZK_HZ" logcat-full.txt | sed 's/^.*Zerkalo *: //' | tail -1
  echo "-- Medida de línea --"; grep -E "ZK_FIT" logcat-full.txt | sed 's/^.*Zerkalo *: //' | tail -4
  echo "-- Letras de Google --"; grep -E "ZK_GFONT" logcat-full.txt | sed 's/^.*Zerkalo *: //' | head -10
  echo "== Android $($T adb shell getprop ro.build.version.release) =="
  echo "-- Tansu (fuentes y tiempos) --"; grep -E "ZK_TANSU|ZK_FUENTE|ZK_INDICE" logcat-full.txt | sed 's/^.*Zerkalo *: //' | head -40
  echo "-- Fluidez al deslizar el Tansu --"
  grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|99th percentile|Slow UI thread|Frame deadline missed" gfx-tansu.txt | head -8
} > metricas.txt
cat metricas.txt
{ $T adb shell getprop ro.build.version.release; grep -E "ZK_|FATAL|AndroidRuntime|Zerkalo" logcat-full.txt | tail -300; } > logcat.txt
grep -q "ZK_READY" logcat-full.txt && grep -q "ZK_READER_OK" logcat-full.txt && grep -q "ZK_CONT_OK" logcat-full.txt && grep -q "ZK_PDF_READY" logcat-full.txt && ! grep -q "FATAL EXCEPTION" logcat-full.txt && ! grep -q "ANR in com.sebas.zerkalo" logcat-full.txt
