package fr.junade.taipo

sealed interface KeyAction {
    data class TypeChar(val char: Char) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object ToggleLayout : KeyAction

    /** Story 1.15 : ouvre le panneau emoji (aucun texte saisi par la touche elle-même). */
    data object Emoji : KeyAction
}