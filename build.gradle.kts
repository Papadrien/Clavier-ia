plugins {
    id("com.android.application") version "9.4.0" apply false

    // Room (dictionnaire personnel chiffré, story 1.4) : KSP pour le
    // compilateur Room (kapt est incompatible avec le Kotlin intégré d'AGP 9)
    // et plugin Gradle Room pour l'export des schémas. La version de Room est
    // unique : gradle/libs.versions.toml (plugin et artefacts). KSP 2.3.x est indépendant de la
    // version de Kotlin ; 2.3.10+ corrige la compatibilité avec Kotlin 2.4.0
    // et avec le Kotlin intégré d'AGP 9.
    id("com.google.devtools.ksp") version "2.3.11" apply false
    alias(libs.plugins.room) apply false
}

// Depuis AGP 9.0, le Kotlin est intégré (android.builtInKotlin=true par
// défaut) et applique le plugin org.jetbrains.kotlin.android séparément est
// désormais une erreur bloquante ("no longer required... / Failed to apply
// plugin"), et non plus un simple avertissement comme avant AGP 9.
// Cf. https://kotl.in/gradle/agp-built-in-kotlin
//
// Le Kotlin intégré à AGP 9.4.0 est la 2.2.10, qui ne sait pas lire les
// classes .kotlin_module de litertlm-android 0.17.1 (compilées avec Kotlin
// 2.4.0). La doc Android documente ce cas précis : surclasser la version du
// Kotlin Gradle Plugin (KGP) utilisée par le Kotlin intégré via un
// classpath buildscript, sans réappliquer le plugin kotlin.android.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}