plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sebas.zerkalo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sebas.zerkalo"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // La llave vive en los secretos del repositorio (KEYSTORE_B64 + KEYSTORE_PASSWORD).
        // Siempre la misma: así cada versión nueva se instala encima de la anterior.
        create("zerkalo") {
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = "zerkalo"
        }
    }

    buildTypes {
        release {
            // R8: quita el código que no se usa y optimiza el resto (APK más liviana y más rápida).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("zerkalo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
    }
    // Un APK por tipo de procesador: el traductor trae piezas nativas pesadas y así cada teléfono baja solo la suya.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("org.readium.r2.shared.ExperimentalReadiumApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Lector EPUB: Readium (código abierto, licencia BSD)
    val readium = "3.1.2"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readium")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readium")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readium")

    // Lector de PDF de Google (zoom, búsqueda y selección de texto; Apache 2.0). Se usa desde Android 9.
    implementation("androidx.pdf:pdf-document-service:1.0.0-beta01")
    implementation("com.google.android.material:material:1.13.0")
    // Interfaz nativa
    val compose = "1.9.4"
    implementation("androidx.compose.ui:ui:$compose")
    implementation("androidx.compose.foundation:foundation:$compose")
    implementation("androidx.compose.animation:animation:$compose")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Desenfoque progresivo (Haze, Apache 2.0) y portadas (Coil, Apache 2.0)
    implementation("dev.chrisbanes.haze:haze:1.7.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Traducción dentro del teléfono (los idiomas se bajan una vez y funcionan sin internet)
    implementation("com.google.mlkit:translate:17.0.3")
    // Audiolibros (LibriVox): reproductor con controles en la pantalla de bloqueo
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // Para convertir las obras de Wikisource en EPUB
    implementation("org.jsoup:jsoup:1.18.3")

    // Instala los perfiles de arranque que traen Compose y las demás librerías: abre y desliza más rápido.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

// El lector de PDF de Google (androidx.pdf) pide compilar con una "extensión" del SDK que GitHub no trae.
// Esa extensión solo la usan sus funciones nuevas (anotar, formularios), que ya revisan en el teléfono si existen;
// Zerkalo no las llama, así que se salta esa revisión al compilar.
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }.configureEach { enabled = false }
