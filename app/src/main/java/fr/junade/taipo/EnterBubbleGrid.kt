package fr.junade.taipo

/**
 * Bulle d'appui long sur Entrée : une rangée de cellules, sélectionnées par glissement du doigt. Logique pure (testable en JVM).
 * Les symboles sont des caractères fictifs (zone d'usage privé) : ils représentent une icône et ne sont jamais saisis.
 */
object EnterBubbleGrid {
    /** Touche globe (clavier multilingue) : changement de langue. */
    const val GLOBE = '\uE000'

    /** Touche mode une main : active ou quitte le mode une main. */
    const val ONE_HANDED = '\uE001'

    /** Touche clavier flottant : détache le clavier ou le recolle en bas. */
    const val FLOATING = '\uE002'

    /** Symboles dessinés comme une icône (jamais saisis). */
    val ICONS: Set<Char> = setOf(GLOBE, ONE_HANDED, FLOATING)

    /** Avec le clavier multilingue : globe, mode une main, flottant ; sinon mode une main et flottant. */
    fun grid(multilingual: Boolean): List<List<Char>> =
        if (multilingual) listOf(listOf(GLOBE, ONE_HANDED, FLOATING)) else listOf(listOf(ONE_HANDED, FLOATING))
}
