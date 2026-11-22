package fr.junade.taipo

import androidx.annotation.IdRes
import androidx.annotation.StringRes

/**
 * Pages de l'apparence du clavier (22/11/2026, même principe que [KeyboardSettingsSection]) : l'accueil montre une carte
 * « Apparence » de 4 lignes, et chacune ouvre [ThemeActivity] sur ses seules sections (groupes `theme_group_*` de la page).
 */
enum class AppearanceSection(val storageKey: String, @StringRes val titleRes: Int, @IdRes val groups: List<Int>) {
    /** Thème (système, clair, sombre, dynamique, avec leurs aperçus) et couleur d'accent. */
    THEME("theme", R.string.appearance_section_theme, listOf(R.id.theme_group_keyboard, R.id.theme_group_color)),

    /** Forme des touches et police du clavier. */
    KEYS("keys", R.string.appearance_section_keys, listOf(R.id.theme_group_shape, R.id.theme_group_font)),

    /** Fond du clavier (motifs, image) et son animation. */
    BACKDROP("backdrop", R.string.theme_section_backdrop, listOf(R.id.theme_group_backdrop)),

    /** Style des animations. */
    MOTION("motion", R.string.theme_section_motion, listOf(R.id.theme_group_motion)),
    ;

    companion object {
        /** Groupes de la page, dans l'ordre. */
        val allGroups: List<Int> get() = entries.flatMap { it.groups }

        /** Section demandée par l'accueil, ou null (clé absente ou inconnue : toute la page, comme avant). */
        fun fromKey(key: String?): AppearanceSection? = entries.firstOrNull { it.storageKey == key }
    }
}
