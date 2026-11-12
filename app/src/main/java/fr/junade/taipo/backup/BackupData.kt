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
    val numberRowPasswordOnly: Boolean? = null,
    val showEmojiKey: Boolean? = null,
    val showCommaKey: Boolean? = null,
    val showPeriodKey: Boolean? = null,
    val hapticIntensity: String? = null,
    val keyboardHeight: String? = null,
    val recentEmojis: List<String>? = null,
    val habitLearning: Boolean? = null,
    /** Thème du clavier : clé de stockage de [fr.junade.taipo.ThemeChoice] (`system`, `light`, `dark`, `dynamic`). */
    val themeChoice: String? = null,
    /** Couleur d'accent du clavier : clé de stockage de [fr.junade.taipo.AccentPalette] (`violet`, `indigo`…). */
    val accentPalette: String? = null,
    /** Forme des touches : clé de stockage de [fr.junade.taipo.KeyShape] (`rounded`, `pill`…). */
    val keyShape: String? = null,
    /** Style des animations : clé de stockage de [fr.junade.taipo.MotionStyle] (`spring`, `snappy`, `jelly`). Facultatif. */
    val motionStyle: String? = null,
    // Audit UX-06 : réglages ajoutés (facultatifs : un fichier plus ancien ne les a pas, ils restent alors inchangés).
    val keyboardFont: String? = null,
    val backdrop: String? = null,
    val backdropAnimated: Boolean? = null,
    val keySound: Boolean? = null,
    val barSound: Boolean? = null,
    val keySoundVolume: Int? = null,
    val bottomMarginDp: Int? = null,
    val adaptiveTouch: Boolean? = null,
    val clipboardHistory: Boolean? = null,
    val oneHandedMode: String? = null,
    val oneHandedWidth: Int? = null,
    val floating: Boolean? = null,
    val floatingWidth: Int? = null,
    /** Code de la langue choisie ; chaîne vide = automatique (langue du système). */
    val keyboardLanguage: String? = null,
    val multilingual: Boolean? = null,
    val multilingualLanguages: List<String>? = null,
    /** Dispositions de lettres choisies (code de langue → disposition), seulement celles qui diffèrent de l'origine. */
    val letterArrangements: Map<String, String>? = null,
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
