#!/usr/bin/env bash
# Prueba real en un emulador Android: instala, abre, fotografía y revisa que la biblioteca cargue.
set -x
T="timeout 90"
$T adb shell getprop ro.build.version.release
$T adb shell dumpsys package com.google.android.webview | grep versionName | head -2
$T adb install -r Zerkalo.apk
$T adb logcat -c
$T adb shell am start -W -n com.sebas.zerkalo/.MainActivity
sleep 30
$T adb exec-out screencap -p > shot1.png
SIZE=$($T adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
[ -n "$W" ] && $T adb shell input tap $((W*375/1000)) $((H - H*103/1000))
sleep 25
$T adb exec-out screencap -p > shot2.png
$T adb logcat -d > logcat-full.txt
{ $T adb shell getprop ro.build.version.release; $T adb shell dumpsys package com.google.android.webview | grep versionName; grep -iE "zerkalo|chromium|cr_|AndroidRuntime|WebView|FATAL" logcat-full.txt | tail -400; } > logcat.txt
grep -q "ZK_READY" logcat-full.txt
