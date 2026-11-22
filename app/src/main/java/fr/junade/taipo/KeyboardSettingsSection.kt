package fr.junade.taipo

import androidx.annotation.StringRes

/**
 * Pages des paramètres du clavier (22/11/2026, maquette A « sommaire ») : l'accueil montre une ligne par thème à la place de
 * l'ancienne ligne « Paramètres du clavier », et chaque ligne ouvre [KeyboardSettingsActivity] sur ce seul thème, sous son titre.
 * Langues garde sa propre ligne et son écran ([LanguagesActivity]).
 */
enum class KeyboardSettingsSection(val storageKey: String, @StringRes val titleRes: Int) {
    /** Hauteur, marge du bas, mode (normal, une main, flottant). */
    SIZE("size", R.string.keyboard_settings_size_title),

    /** Rangée de chiffres, touches du bas, bulle des touches. */
    LAYOUT("layout", R.string.keyboard_settings_layout_title),

    /** Vibrations, sons, volume. */
    SOUNDS("sounds", R.string.keyboard_settings_sounds_title),

    /** Saisie par glissement, habitudes, historique du presse-papiers, lien vers vos données (et réglages debug). */
    SMART("smart", R.string.keyboard_settings_smart_title),
    ;

    companion object {
        /** Section demandée par l'accueil, ou null (clé absente ou inconnue : toutes les sections, comme avant). Logique pure. */
        fun fromKey(key: String?): KeyboardSettingsSection? = entries.firstOrNull { it.storageKey == key }
    }
}
