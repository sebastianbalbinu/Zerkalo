#!/usr/bin/env bash
# Prueba real en un emulador Android: instala, abre la biblioteca, el Tansu y el lector, y toma fotos.
set -x
T="timeout 90"
$T adb shell getprop ro.build.version.release
$T adb shell dumpsys package com.google.android.webview | grep versionName | head -2
$T adb install -r Zerkalo.apk
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
# Abrir el libro de bienvenida en el lector
$T adb shell am start -n com.sebas.zerkalo/.MainActivity --es zerkalo.abrir bienvenida
sleep 15
$T adb exec-out screencap -p > shot3.png
# Pasar de página tocando el borde derecho y mostrar los controles
[ -n "$W" ] && $T adb shell input tap $((W*92/100)) $((H/2))
sleep 3
[ -n "$W" ] && $T adb shell input tap $((W/2)) $((H/2))
sleep 3
$T adb exec-out screencap -p > shot4.png
$T adb logcat -d > logcat-full.txt
{ $T adb shell getprop ro.build.version.release; grep -E "ZK_|FATAL|AndroidRuntime|Zerkalo" logcat-full.txt | tail -300; } > logcat.txt
grep -q "ZK_READY" logcat-full.txt && grep -q "ZK_READER_OK" logcat-full.txt && ! grep -q "FATAL EXCEPTION" logcat-full.txt
