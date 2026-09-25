plugins {
    id("com.android.application")
}

android {
    namespace = "fr.papadrien.clavier"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "fr.papadrien.clavier"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Runtime d'inférence locale (voir décision ai-keyboard.md du 23/09/2026).
    // "latest.release" est la syntaxe officielle documentée par Google
    // (https://ai.google.dev/edge/litert-lm/android) ; à remplacer par un
    // numéro de version figé dès le premier build réussi, pour la
    // reproductibilité du build CI.
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

    // Transcription vocale (sherpa-onnx, décision ai-keyboard.md du 23/09/2026).
    // Contrairement à LiteRT-LM, il n'existe pas de coordonnée Maven officielle
    // simple pour un projet Android natif (hors Flutter/React Native/Dart), et
    // le tar.bz2 des releases GitHub (sherpa-onnx-vX.Y.Z-android.tar.bz2)
    // contient les bibliothèques natives (.so) mais pas de .aar précompilé.
    // Intégration en deux parties, sans dépendance Gradle supplémentaire :
    //   1. Les .so vont dans app/src/main/jniLibs/<abi>/ (auto-détecté par
    //      Gradle, voir le README dans ce dossier) ;
    //   2. Les sources Kotlin du binding JNI (com.k2fsa.sherpa.onnx.*) vont
    //      dans app/src/main/java/com/k2fsa/sherpa/onnx/ (voir le README dans
    //      ce dossier), copiées depuis sherpa-onnx/kotlin-api du dépôt
    //      officiel. Pas de ligne implementation(...) à ajouter ici pour ça.
    // Non fait ici (pas d'accès réseau dans cet environnement) : à faire par
    // Adrien avant que ce module ne compile.

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}