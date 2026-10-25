// Lot 4.4 (A5) : module JVM pur, sans aucune dépendance Android. Il contient la logique du dictionnaire
// (correction, suggestions, dictionnaire personnel) et des suggestions (mot suivant, emoji), c'est-à-dire
// le code testable sur la JVM seule. Mêmes packages que dans l'ancien module `app` : aucun import à changer.
// Voir docs/modules.md.
plugins {
    // Le plugin Kotlin est déjà sur le classpath du build (buildscript racine, KGP 2.4.0) : pas de version ici.
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Aucune dépendance d'exécution : ni Android, ni coroutines (le dépôt du dictionnaire personnel, qui en a besoin,
// reste dans le module app avec sa base Room).
dependencies {
    // Même JUnit 5 que le module app (junit-jupiter embarque junit-jupiter-params).
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// La CI lance `./gradlew testDebugUnitTest` : cet alias fait que les tests du module core en font partie
// (un module JVM n'a pas de variante « debug », donc pas cette tâche).
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Alias de test : permet à `./gradlew testDebugUnitTest` d'inclure les tests du module core."
    dependsOn("test")
}
