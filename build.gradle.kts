plugins {
    id("com.android.application") version "9.4.0" apply false
    // Déclaré explicitement (voir app/build.gradle.kts) : sans ça, le build
    // se rabat sur le compilateur Kotlin embarqué dans AGP 9.4.0 (2.2.0), qui
    // ne sait pas lire les classes .kotlin_module de litertlm-android
    // 0.17.1 (compilées avec Kotlin 2.4.0) -> "Incompatible classes were
    // found in dependencies" + toute la stdlib (listOf, apply, let...)
    // signalée comme "Unresolved reference". Version alignée sur celle
    // qu'exige litertlm-android d'après le log de build du 25/09.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}