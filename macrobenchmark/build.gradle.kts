plugins {
    id("com.android.test")
}

// Lot 3.6 (P5) : macrobenchmarks et génération du profil de démarrage. Module chargé à la demande
// (voir settings.gradle.kts et docs/performance.md). Se lance sur un appareil réel (Pixel 9), jamais
// sur émulateur ni sur secteur faible.
android {
    namespace = "fr.junade.taipo.macrobenchmark"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        // Génération de profil : API 28+ (non rooté : API 33+).
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // Même clé debug fixe que :app (app/debug.keystore) : l'APK de test et l'app ciblée doivent partager la
    // même signature pour que l'instrumentation fonctionne.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Cible la variante « benchmark » de :app (même nom).
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro)
}

// Seule la variante « benchmark » a un sens ici.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}
