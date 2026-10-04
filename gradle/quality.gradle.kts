import org.gradle.api.attributes.Bundling

// Lot 4.1 (B8) : ktlint (format) et detekt (analyse statique), lancés en ligne de commande par des tâches
// JavaExec plutôt que par leurs plugins Gradle : aucune dépendance au plugin Android / Kotlin du projet,
// rien n'est résolu tant qu'on ne lance pas une de ces tâches, et le build normal n'est donc jamais affecté.
// Voir docs/qualite.md. Versions à confirmer au premier lancement (non résolues depuis l'environnement
// de développement).
val ktlintVersion = "1.5.0"
val detektVersion = "1.23.8"

val ktlint by configurations.creating
val detektCli by configurations.creating

dependencies {
    ktlint("com.pinterest.ktlint:ktlint-cli:$ktlintVersion") {
        attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL)) }
    }
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:$detektVersion")
}

val ktlintBaselineFile = "quality/ktlint-baseline.xml"
val ktlintBaselineOnDisk: java.io.File = layout.projectDirectory.file(ktlintBaselineFile).asFile
val detektBaselineFile = "quality/detekt-baseline.xml"

// Code sherpa-onnx recopié du dépôt officiel : jamais analysé.
val ktlintPatterns = listOf(
    "app/src/**/*.kt",
    "core/src/**/*.kt",
    "macrobenchmark/src/**/*.kt",
    "*.kts",
    "app/*.kts",
    "core/*.kts",
    "macrobenchmark/*.kts",
    "gradle/*.kts",
    "!**/com/k2fsa/**",
    "!**/build/**",
)
val detektInputs = "app/src/main/java,app/src/test/java,app/src/androidTest/java,core/src,macrobenchmark/src"

fun JavaExec.ktlintSetup() {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

// Vérifie le format. Les écarts déjà listés dans quality/ktlint-baseline.xml sont tolérés ; tout nouvel
// écart fait échouer la tâche. Si le fichier de baseline n'existe pas encore, ktlint le crée au premier passage.
tasks.register<JavaExec>("ktlintCheck") {
    group = "verification"
    description = "ktlint : format du code (tolère la baseline, refuse tout nouvel écart)."
    ktlintSetup()
    args(listOf("--relative", "--baseline=$ktlintBaselineFile") + ktlintPatterns)
}

// Corrige automatiquement les écarts de format corrigibles (relire le diff avant de committer).
tasks.register<JavaExec>("ktlintFormat") {
    group = "formatting"
    description = "ktlint : reformate le code (à relire avant de committer)."
    ktlintSetup()
    args(listOf("--relative", "--format") + ktlintPatterns)
}

// Régénère la baseline ktlint : à n'utiliser qu'une fois (état initial) ou après un gros nettoyage.
tasks.register<JavaExec>("ktlintBaseline") {
    group = "verification"
    description = "ktlint : régénère quality/ktlint-baseline.xml (dette acceptée à ce jour)."
    ktlintSetup()
    isIgnoreExitValue = true
    doFirst { ktlintBaselineOnDisk.delete() }
    args(listOf("--relative", "--baseline=$ktlintBaselineFile") + ktlintPatterns)
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "detekt : analyse statique (tolère la baseline, refuse tout nouvel écart)."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    args(
        "--input", detektInputs,
        "--config", "config/detekt/detekt.yml",
        "--build-upon-default-config",
        "--excludes", "**/com/k2fsa/**,**/build/**",
        "--baseline", detektBaselineFile,
    )
}

tasks.register<JavaExec>("detektBaseline") {
    group = "verification"
    description = "detekt : régénère quality/detekt-baseline.xml (dette acceptée à ce jour)."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    isIgnoreExitValue = true
    args(
        "--input", detektInputs,
        "--config", "config/detekt/detekt.yml",
        "--build-upon-default-config",
        "--excludes", "**/com/k2fsa/**,**/build/**",
        "--baseline", detektBaselineFile,
        "--create-baseline",
    )
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "ktlint + detekt."
    dependsOn("ktlintCheck", "detekt")
}
