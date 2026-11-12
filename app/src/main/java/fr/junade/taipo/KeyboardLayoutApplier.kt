package fr.junade.taipo

/**
 * Disposition affichée (sortie de [TaipoIme], revue de code) : rangée de chiffres et touches du bas selon le champ, puis
 * disposition, Maj et nom de la langue posés sur la vue à chaque [apply] (à chaque touche et chaque déplacement du curseur).
 * Les réglages de disposition ne sont relus qu'au changement de langue ou à l'ouverture d'un champ ([invalidateSettings]).
 */
internal class KeyboardLayoutApplier(
    private val ime: ImeHostContext,
    private val controller: KeyboardController,
    private val fieldType: () -> FieldType,
) {

    private val preferences: KeyboardPreferences get() = ime.keyboardPreferences

    /** Rangée de chiffres affichée pour le champ courant. */
    var numberRowEnabled = false
        private set

    private var bottomRowKeys = BottomRowKeys.ALL

    private class Settings(val language: KeyboardLanguage, val arrangement: LetterArrangement, val compactEnter: Boolean)

    private var settings: Settings? = null

    /**
     * Rangée de chiffres affichée pour le champ courant : toujours, ou seulement dans un champ mot de passe (voir [NumberRowPolicy]).
     * Relit aussi les touches du bas à afficher (emoji, virgule, point).
     */
    fun refreshNumberRow() {
        numberRowEnabled = NumberRowPolicy.shown(
            always = preferences.isNumberRowEnabled,
            passwordFieldsOnly = preferences.isNumberRowInPasswordFieldsOnly,
            fieldType = fieldType(),
        )
        bottomRowKeys = preferences.bottomRowKeys
    }

    /** Les réglages ont pu changer (ouverture d'un champ) : relus au prochain [apply]. */
    fun invalidateSettings() {
        settings = null
    }

    private fun settingsFor(language: KeyboardLanguage): Settings =
        settings?.takeIf { it.language == language } ?: Settings(
            language,
            // Disposition des lettres choisie pour la langue active (écran « Langues » des paramètres).
            preferences.letterArrangementOf(language),
            // Multilingue : le globe est en exposant sur Entrée, qui se rétrécit au profit de l'espace.
            MultilingualChoice.isActive(preferences.multilingualLanguages),
        ).also { settings = it }

    private fun layoutOf(id: LayoutId, language: KeyboardLanguage, field: FieldType, current: Settings): KeyboardLayout =
        Keyboards.layoutOf(id, language, numberRowEnabled, field, bottomRowKeys, current.arrangement, current.compactEnter)

    fun apply() {
        val keyboardView = ime.keyboardView
        val state = controller.state
        val current = settingsFor(state.language)
        val field = fieldType()
        // Hauteur constante : les pages de symboles (4 rangées) prennent la hauteur du clavier de lettres affiché,
        // qui compte une rangée de plus avec la rangée de chiffres. Évite de faire défiler l'application dessous.
        keyboardView.minRowCount = layoutOf(LayoutId.LETTERS, state.language, field, current).rows.size
        keyboardView.layout = layoutOf(state.activeLayout, state.language, field, current)
        keyboardView.isShifted = state.isShifted
        // Multilingue : nom de la langue active sur l'espace, à la place du trait ; une seule langue : le trait, comme avant.
        keyboardView.spaceLabel = if (current.compactEnter) state.language.displayName else null
        keyboardView.isCapsLock = state.isCapsLock
        keyboardView.showsGlobeOnEnter = current.compactEnter
        // Audit UX-10 : la touche Entrée montre l'action du champ ; en mode prompt, Entrée garde son rôle (retour à la ligne).
        keyboardView.enterActionIcon = if (ime.prompt.active) EnterAction.Icon.ENTER else EnterAction.iconFor(EnterAction.of(ime.editorInfo()))
    }
}
