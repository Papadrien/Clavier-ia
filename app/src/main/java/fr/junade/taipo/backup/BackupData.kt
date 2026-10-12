package fr.junade.taipo.backup

/**
 * Contenu d'un fichier de sauvegarde (story 10.3) : les données personnelles et les réglages de l'application, pour une
 * restauration sur un autre appareil. Ne contient ni les modèles IA, ni l'historique du presse-papiers, ni les données
 * apprises (mot suivant, habitudes de correction), ni les clés de chiffrement, ni l'acceptation des licences.
 *
 * Tout champ de réglage à `null` est « absent du fichier » : la restauration n'y touche pas.
 */
data class BackupData(
    val exportedAtMillis: Long,
    val dictionary: List<String>,
    val pins: List<BackupPin>,
    val keyboard: BackupKeyboardSettings,
    val ai: BackupAiSettings,
)

/** Élément épinglé du Smart Clipboard, avec son étiquette éventuelle. */
data class BackupPin(val text: String, val label: String?, val pinnedAtMillis: Long)

data class BackupKeyboardSettings(
    val numberRow: Boolean? = null,
    val hapticIntensity: String? = null,
    val keyboardHeight: String? = null,
    val recentEmojis: List<String>? = null,
    val habitLearning: Boolean? = null,
)

/**
 * Réglages IA : le modèle choisi (identifiant seulement) et les prompts système personnalisés, par usage
 * (`correction`, `generation`, `transcription`). Un prompt par défaut n'est pas exporté.
 */
data class BackupAiSettings(
    val activeModelId: String? = null,
    val prompts: Map<String, String> = emptyMap(),
) {
    companion object {
        const val PROMPT_CORRECTION = "correction"
        const val PROMPT_GENERATION = "generation"
        const val PROMPT_TRANSCRIPTION = "transcription"
    }
}
