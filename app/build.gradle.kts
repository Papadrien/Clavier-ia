plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    alias(libs.plugins.room)
}

android {
    namespace = "fr.junade.taipo"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "fr.junade.taipo"
        minSdk = 26
        targetSdk = 37
        // Lot 2.1 : versionCode croissant en CI (VERSION_CODE, sinon le numéro d'exécution GitHub
        // Actions) ; 1 en local. Play refuse un versionCode déjà publié.
        versionCode = (System.getenv("VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    // Signature release : lue dans des variables d'environnement (secrets CI), jamais dans le dépôt.
    // Sans elles (build local), l'APK release n'est simplement pas signé. Voir docs/release.md.
    val keystorePath = System.getenv("TAIPO_KEYSTORE_PATH")
    if (keystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TAIPO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TAIPO_KEY_ALIAS")
                keyPassword = System.getenv("TAIPO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 : réduction du code et des ressources. Les règles keep (JNI sherpa-onnx, LiteRT-LM,
            // SQLCipher) sont dans proguard-rules.pro : à valider sur un APK release installé.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // BuildConfig.DEBUG pilote AppLog : journaux actifs uniquement en debug.
    buildFeatures {
        buildConfig = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Export des schémas Room (JSON versionné dans le dépôt) : indispensable pour
// écrire et tester les futures migrations. Le fichier <version>.json est
// généré au premier build et doit être commité.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")

    // Suggestions d'auto-remplissage « en ligne » (gestionnaire de mots de passe) affichées dans la
    // barre du clavier : la bibliothèque fournit le style que les services d'auto-remplissage exigent.
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Runtime d'inférence locale (voir décision ai-keyboard.md du 23/09/2026).
    // Version figée à 0.17.1 dans gradle/libs.versions.toml (lot 1.2 de la revue) : plus de
    // "latest.release", pour des builds CI reproductibles. Pour monter de version, modifier
    // uniquement cette ligne du catalogue, puis rejouer correction, prompt et dictée sur appareil.
    implementation(libs.litertlm.android)

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

    // Dictionnaire personnel (story 1.4) : Room chiffré par SQLCipher.
    // La clé de chiffrement est générée aléatoirement et protégée par
    // l'Android Keystore (voir DatabasePassphraseProvider). Room reste à la
    // même version que le plugin androidx.room (gradle/libs.versions.toml).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Fournit SupportOpenHelperFactory (passerelle Room <-> SQLCipher).
    // androidx.sqlite arrive déjà via room-runtime.
    implementation("net.zetetic:sqlcipher-android:4.18.0@aar")

    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}