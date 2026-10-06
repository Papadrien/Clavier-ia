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

        // Lot 4.1 (B7, décision D7) : les bibliothèques natives de la dictée (sherpa-onnx) n'existent que pour
        // arm64-v8a. La release (et la variante benchmark) ne contient donc que cet ABI ; la variante debug
        // ajoute x86_64 pour l'émulateur (voir app/src/debug/jniLibs/README.md : sans les .so sherpa x86_64,
        // tout fonctionne sauf la dictée, qui échoue proprement).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Lot 3.3 : tests instrumentés (Room + SQLCipher + Keystore réels), JUnit 4 côté appareil.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Signature debug FIXE (clé versionnée dans le dépôt : app/debug.keystore, mot de passe public « android »,
    // sans valeur de sécurité). Sans elle, chaque runner CI génère sa propre clé debug : deux APK debug de
    // builds différents ont des signatures différentes et Android refuse la mise à jour par-dessus
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE). Avec cette clé, tous les APK debug (CI et local) se mettent à jour.
    // La variante benchmark réutilise cette même signature (voir plus bas).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }

        release {
            // R8 : réduction du code et des ressources. Les règles keep (JNI sherpa-onnx, LiteRT-LM,
            // SQLCipher) sont dans proguard-rules.pro : à valider sur un APK release installé.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }

        // Lot 3.6 (P5) : variante pour les macrobenchmarks et la génération du profil de démarrage. Même
        // code que la release (R8 compris, c'est ce qui est mesuré), mais signée avec la clé debug, donc
        // installable sans secrets ; n'est jamais publiée. Voir docs/performance.md.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
    // Lot 4.4 (A5) : logique pure JVM (dictionnaire, suggestions, langue du clavier), voir docs/modules.md.
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.18.0")

    // Lot 3.5 (U3) : Activity Result API (ComponentActivity) pour les sélecteurs de fichier des écrans modèle.
    implementation(libs.androidx.activity)

    // Lot 20 (accessibilité) : ExploreByTouchHelper, pour exposer à TalkBack les touches du clavier et la grille d'emojis,
    // dessinées au Canvas (aucune vue enfant). Pas de dépendance transitive garantie : déclarée explicitement.
    implementation(libs.androidx.customview)

    // Lot 3.6 (P5) : installe le profil de démarrage (src/main/baseline-prof.txt, voir docs/performance.md)
    // même hors Play Store. Sans fichier de profil, ne fait rien.
    implementation(libs.androidx.profileinstaller)

    // Suggestions d'auto-remplissage « en ligne » (gestionnaire de mots de passe) affichées dans la
    // barre du clavier : la bibliothèque fournit le style que les services d'auto-remplissage exigent.
    implementation("androidx.autofill:autofill:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Runtime d'inférence locale (décision du 23/09/2026 : docs/decisions-techniques.md).
    // Version figée à 0.17.1 dans gradle/libs.versions.toml (lot 1.2 de la revue) : plus de
    // "latest.release", pour des builds CI reproductibles. Pour monter de version, modifier
    // uniquement cette ligne du catalogue, puis rejouer correction, prompt et dictée sur appareil.
    implementation(libs.litertlm.android)

    // Transcription vocale (sherpa-onnx, décision du 23/09/2026 : docs/decisions-techniques.md).
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

    // junit-jupiter embarque junit-jupiter-params (@ParameterizedTest, lot 3.3).
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")

    // Tests instrumentés (src/androidTest, lot 3.3) : `./gradlew connectedDebugAndroidTest` sur appareil.
    // JUnit 5 n'est pas pris en charge côté appareil : ces tests sont en JUnit 4. Versions à confirmer
    // au premier build (non résolues depuis l'environnement de revue).
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}