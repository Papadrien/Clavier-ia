package fr.junade.taipo

import androidx.annotation.ColorRes

/**
 * Couleur du contenu (texte, icône) posé sur une face colorée du clavier (lot T6).
 *
 * En thème clair, `text_primary` est sombre : sur une face violette ou rouge, le contenu doit être `text_on_accent` (blanc dans les
 * deux palettes). Sur toute autre face (touches et boutons gris, pastilles), `text_primary` suit le thème. Un seul endroit décide,
 * pour que les boutons de la barre, le bouton d'envoi, les bulles du chat et les menus restent d'accord.
 *
 * Logique pure (sans Android), testée en JVM.
 */
internal object FaceContent {

    /** Faces qui ne changent pas de valeur d'un thème à l'autre et portent donc un contenu blanc. */
    @ColorRes
    fun colorOn(@ColorRes face: Int): Int = if (isColored(face)) R.color.text_on_accent else R.color.text_primary

    /** Vrai pour une face violette (accent, envoi, sélection, étiquette) ou rouge (danger). */
    fun isColored(@ColorRes face: Int): Boolean = when (face) {
        R.color.accent,
        R.color.action_send,
        R.color.action_danger,
        R.color.taipo_purple,
        R.color.key_enter,
        R.color.key_popup_selection,
        R.color.clip_pin_badge,
        R.color.clip_action_primary,
        R.color.emoji_tab_selected,
        R.color.button_primary,
        -> true
        else -> false
    }
}
