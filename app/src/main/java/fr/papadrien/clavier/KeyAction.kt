package fr.papadrien.clavier

sealed interface KeyAction {
    data class TypeChar(val char: Char) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object ToggleLayout : KeyAction
}