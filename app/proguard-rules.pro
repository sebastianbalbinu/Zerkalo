# Readium usa reflexión en partes del motor EPUB: se conserva completo.
-keep class org.readium.** { *; }
-keep interface org.readium.** { *; }
# Puente JavaScript del navegador de Readium
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# Clases opcionales que Readium y otras librerías mencionan pero no usamos
-dontwarn org.readium.**
-dontwarn org.joda.**
-dontwarn javax.**
-dontwarn com.google.errorprone.**
-dontwarn org.slf4j.**
-ignorewarnings
# Errores legibles en el registro
-keepattributes SourceFile,LineNumberTable

# jsoup (Wikisource) y Media3 (audiolibros)
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**

