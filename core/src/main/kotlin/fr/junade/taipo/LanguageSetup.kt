package fr.junade.taipo

/**
 * Réglage des langues du clavier tel qu'il est stocké : langue choisie ([chosen], null = automatique), langues cochées
 * ([selection]) et interrupteur du mode multilingue ([multilingualEnabled]). L'écran « Langues » ne montre qu'une liste :
 * [inUse] en tire les langues réellement utilisées, [LanguageList] les ajoute et les retire en gardant ces trois valeurs
 * cohérentes. Logique pure, testée en JVM.
 */
data class LanguageSetup(
    val chosen: KeyboardLanguage?,
    val selection: List<KeyboardLanguage>,
    val multilingualEnabled: Boolean,
) {
    /**
     * Langues utilisées, dans l'ordre de [KeyboardLanguage.entries] : les langues cochées si le mode multilingue est actif
     * (au moins deux), sinon la seule langue choisie ou, en automatique, [automatic] (langue du système, voir
     * [KeyboardLanguageChoice.resolve]).
     */
    fun inUse(automatic: KeyboardLanguage): List<KeyboardLanguage> =
        selection.takeIf { multilingualEnabled && MultilingualChoice.isActive(it) } ?: listOf(chosen ?: automatic)
}

/** Ajout et retrait d'une langue de la liste. Une langue au moins reste toujours utilisée. */
object LanguageList {

    /** Langues qu'on peut encore ajouter, dans l'ordre de [KeyboardLanguage.entries]. */
    fun available(setup: LanguageSetup, automatic: KeyboardLanguage): List<KeyboardLanguage> {
        val used = setup.inUse(automatic)
        return KeyboardLanguage.entries.filter { it !in used }
    }

    /** Ajoute [language] ; avec une deuxième langue, le clavier devient multilingue. Sans effet si elle y est déjà. */
    fun add(setup: LanguageSetup, automatic: KeyboardLanguage, language: KeyboardLanguage): LanguageSetup {
        val used = setup.inUse(automatic)
        if (language in used) return setup
        val selection = KeyboardLanguage.entries.filter { it in used || it == language }
        return setup.copy(selection = selection, multilingualEnabled = true)
    }

    /** Vrai si [language] est utilisée et qu'il en reste une autre : la dernière langue ne se retire pas. */
    fun canRemove(setup: LanguageSetup, automatic: KeyboardLanguage, language: KeyboardLanguage): Boolean {
        val used = setup.inUse(automatic)
        return language in used && used.size > 1
    }

    /**
     * Retire [language]. S'il reste deux langues ou plus, le mode multilingue continue ; s'il n'en reste qu'une, il s'éteint
     * et cette langue devient la langue choisie (null, donc automatique, si c'est la langue du système).
     */
    fun remove(setup: LanguageSetup, automatic: KeyboardLanguage, language: KeyboardLanguage): LanguageSetup {
        if (!canRemove(setup, automatic, language)) return setup
        val remaining = setup.inUse(automatic) - language
        return if (MultilingualChoice.isActive(remaining)) {
            setup.copy(selection = remaining, multilingualEnabled = true)
        } else {
            val only = remaining.single()
            LanguageSetup(chosen = only.takeUnless { it == automatic }, selection = remaining, multilingualEnabled = false)
        }
    }
}
