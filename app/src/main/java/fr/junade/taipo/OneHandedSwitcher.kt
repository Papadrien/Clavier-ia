package fr.junade.taipo

/**
 * Mode une main (sortie de [TaipoIme], revue de code) : application du réglage, bascule et changement de côté. Gère aussi la
 * bascule du clavier flottant, qui exclut le mode une main (un seul « mode du clavier » à la fois : normal, une main, flottant).
 */
internal class OneHandedSwitcher(private val ime: ImeHostContext) {

    private val preferences: KeyboardPreferences get() = ime.keyboardPreferences

    /**
     * La zone des touches et celle des panneaux suivent le réglage ; la barre du haut garde toute la largeur. Clavier flottant
     * actif : pas de mode une main (les deux s'excluent), le réglage est gardé pour le retour au clavier ancré.
     */
    fun apply() {
        val mode = if (preferences.isFloatingKeyboard) OneHandedMode.OFF else preferences.oneHandedMode
        val percent = preferences.oneHandedWidthPercent
        ime.keyboardView.oneHandedWidthPercent = percent
        ime.keyboardView.oneHandedMode = mode
        ime.viewComposer.keyboardStack.oneHandedWidthPercent = percent
        ime.viewComposer.keyboardStack.oneHandedMode = mode
    }

    /** Touche mode une main de la bulle d'appui long sur Entrée : quitte le mode s'il est actif, sinon l'active du dernier côté choisi. */
    fun toggle() {
        if (preferences.isFloatingKeyboard) {
            // Passer en mode une main quitte le clavier flottant.
            preferences.isFloatingKeyboard = false
            preferences.oneHandedMode = preferences.lastOneHandedSide
            ime.haptic()
            ime.applyFloatingKeyboard(VIA_ONE_HANDED)
            apply()
            return
        }
        preferences.oneHandedMode = if (preferences.oneHandedMode.isActive) OneHandedMode.OFF else preferences.lastOneHandedSide
        ime.haptic()
        apply()
    }

    /**
     * Touche clavier flottant de la bulle d'Entrée, ou poignée glissée tout en bas : détache le clavier (le mode une main est
     * alors quitté) ou le recolle en bas.
     */
    fun toggleFloating(via: String) {
        val floating = !preferences.isFloatingKeyboard
        preferences.isFloatingKeyboard = floating
        if (floating) preferences.oneHandedMode = OneHandedMode.OFF
        ime.haptic()
        ime.applyFloatingKeyboard(via)
        apply()
    }

    /** Bouton « changer de côté » de la zone libre : le mode une main passe de l'autre côté (sans effet s'il n'est pas actif). */
    fun switchSide() {
        val other = when (preferences.oneHandedMode) {
            OneHandedMode.LEFT -> OneHandedMode.RIGHT
            OneHandedMode.RIGHT -> OneHandedMode.LEFT
            OneHandedMode.OFF -> return
        }
        preferences.oneHandedMode = other
        ime.haptic()
        apply()
    }

    companion object {
        /** Origine d'un changement de mode, pour la mesure d'usage (`keyboard_mode`). */
        const val VIA_BUBBLE = "bubble"
        const val VIA_HANDLE = "handle"
        const val VIA_ONE_HANDED = "one_handed"
    }
}
