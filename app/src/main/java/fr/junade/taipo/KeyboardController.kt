package fr.junade.taipo

data class KeyboardState(
    val activeLayout: LayoutId = LayoutId.LETTERS,
    val isShifted: Boolean = false,
    val language: KeyboardLanguage = KeyboardLanguage.FR,
    /**
     * Verrouillage des majuscules (double appui sur Maj) : [isShifted] reste vrai après chaque
     * lettre, jusqu'à un nouvel appui sur Maj.
     */
    val isCapsLock: Boolean = false,
)

data class KeyPressResult(
    val commit: String? = null,
    val deleteBefore: Int = 0,
    val isEnter: Boolean = false,
    val newState: KeyboardState,
)

class KeyboardController(initialState: KeyboardState = KeyboardState()) {

    var state: KeyboardState = initialState
        private set

    fun reset() {
        // Ne réinitialise pas la langue : elle est pilotée par le subtype IME
        // actif (décision 1.1), pas par le cycle de vie du champ de saisie.
        state = state.copy(activeLayout = LayoutId.LETTERS, isShifted = false, isCapsLock = false)
        lastShiftTapAt = null
    }

    /** Instant du dernier appui sur Maj, pour détecter un double appui ; null si aucun appui récent. */
    private var lastShiftTapAt: Long? = null

    /**
     * Story 1.18 : majuscule automatique (story 1.2) active dans le champ courant. Elle est coupée
     * dans les champs e-mail, URL, mot de passe et numériques, où une majuscule de début de champ
     * serait fausse. Le choix manuel de la touche Maj reste possible. Piloté par le type de champ
     * ([FieldType.autoCapitalizes]), pas par un réglage : il survit à [reset].
     */
    var autoCapitalization: Boolean = true
        private set

    fun setAutoCapitalization(enabled: Boolean) {
        if (autoCapitalization == enabled) return
        autoCapitalization = enabled
        if (!enabled && state.isShifted && !state.isCapsLock) state = state.copy(isShifted = false)
    }

    fun setLanguage(language: KeyboardLanguage) {
        if (state.language == language) return
        state = state.copy(language = language, isShifted = false, isCapsLock = false)
    }

    /**
     * Recalcule la majuscule automatique (story 1.2 : majuscule en début de
     * phrase ou après une ponctuation de fin de phrase, non désactivable —
     * aucun paramètre ne permet de couper ce comportement).
     *
     * [textBeforeCursor] est le texte du champ actif juste avant le curseur,
     * fourni par l'IME (InputConnection) à chaque événement pertinent : le
     * contrôleur ne le suit pas lui-même en interne, car le texte peut
     * changer par d'autres biais que les touches de ce clavier (correction
     * IA, saisie vocale, déplacement du curseur par l'utilisateur).
     */
    fun applyTextContext(textBeforeCursor: String) {
        if (state.isCapsLock) return // le verrouillage des majuscules prime sur la majuscule automatique
        val shouldCapitalize = autoCapitalization && shouldAutoCapitalize(textBeforeCursor)
        if (state.isShifted != shouldCapitalize) {
            state = state.copy(isShifted = shouldCapitalize)
        }
    }

    /**
     * [textBeforeCursor] (optionnel) sert au double espace : si l'espace tapé suit
     * un mot déjà suivi d'un seul espace, ce dernier est remplacé par ". ".
     *
     * [uptimeMillis] (optionnel) est l'instant de l'appui : deux appuis sur Maj à moins de
     * [SHIFT_DOUBLE_TAP_MS] verrouillent les majuscules. Sans lui, aucun double appui n'est détecté.
     */
    fun onKey(key: Key, textBeforeCursor: String? = null, uptimeMillis: Long? = null): KeyPressResult {
        // Toute autre touche entre deux appuis sur Maj annule le double appui.
        if (key.action != KeyAction.Shift) lastShiftTapAt = null
        return when (val action = key.action) {
            is KeyAction.TypeChar -> {
                val c = if (state.isShifted && action.char.isLetter()) action.char.uppercaseChar() else action.char
                state = state.copy(isShifted = state.isCapsLock)
                KeyPressResult(commit = c.toString(), newState = state)
            }

            KeyAction.Shift -> {
                val previousTap = lastShiftTapAt
                val isDoubleTap = uptimeMillis != null && previousTap != null &&
                    uptimeMillis - previousTap in 0..SHIFT_DOUBLE_TAP_MS
                state = when {
                    // Verrouillage actif : un appui le désactive.
                    state.isCapsLock -> state.copy(isShifted = false, isCapsLock = false)
                    // Double appui : verrouillage des majuscules.
                    isDoubleTap -> state.copy(isShifted = true, isCapsLock = true)
                    else -> state.copy(isShifted = !state.isShifted)
                }
                lastShiftTapAt = if (isDoubleTap) null else uptimeMillis
                KeyPressResult(newState = state)
            }

            KeyAction.Backspace -> {
                KeyPressResult(deleteBefore = 1, newState = state)
            }

            KeyAction.Enter -> {
                state = state.copy(isShifted = state.isCapsLock)
                KeyPressResult(isEnter = true, newState = state)
            }

            KeyAction.Space -> {
                state = state.copy(isShifted = state.isCapsLock)
                if (textBeforeCursor != null && shouldInsertPeriodOnDoubleSpace(textBeforeCursor)) {
                    KeyPressResult(commit = ". ", deleteBefore = 1, newState = state)
                } else {
                    KeyPressResult(commit = " ", newState = state)
                }
            }

            // Story 1.15 : le panneau emoji est géré par l'IME ; la touche ne change ni texte ni état.
            KeyAction.Emoji -> KeyPressResult(newState = state)

            KeyAction.ToggleLayout -> {
                state = state.copy(
                    activeLayout = if (state.activeLayout == LayoutId.LETTERS) LayoutId.SYMBOLS else LayoutId.LETTERS,
                    isShifted = state.isCapsLock,
                )
                KeyPressResult(newState = state)
            }
        }
    }

    companion object {
        /** Délai maximal entre deux appuis sur Maj pour verrouiller les majuscules. */
        const val SHIFT_DOUBLE_TAP_MS = 350L

        /** Au-delà de cette distance avant le curseur, la réponse ne peut plus changer (espaces mis à part). */
        private const val LOOKBEHIND_LIMIT = 50

        /**
         * Vrai si le texte juste avant le curseur se termine par une
         * ponctuation de fin de phrase (point, point d'exclamation, point
         * d'interrogation), par un retour à la ligne, ou s'il n'y a rien
         * avant le curseur (tout début de champ) — en ignorant les espaces
         * et tabulations de fin. Fonction pure, testable indépendamment de
         * l'IME.
         */
        fun shouldAutoCapitalize(textBeforeCursor: String): Boolean {
            val relevant = textBeforeCursor.takeLast(LOOKBEHIND_LIMIT)
            val trimmed = relevant.trimEnd(' ', '\t')
            if (trimmed.isEmpty()) return true
            return when (trimmed.last()) {
                '.', '!', '?', '\n' -> true
                else -> false
            }
        }

        /**
         * Vrai si le texte se termine par une lettre ou un chiffre suivi d'un
         * seul espace : un nouvel espace doit alors devenir ". " (double espace).
         */
        fun shouldInsertPeriodOnDoubleSpace(textBeforeCursor: String): Boolean {
            val n = textBeforeCursor.length
            if (n < 2 || textBeforeCursor[n - 1] != ' ') return false
            return textBeforeCursor[n - 2].isLetterOrDigit()
        }
    }
}
