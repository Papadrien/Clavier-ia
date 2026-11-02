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

    /** Avec le clavier multilingue : globe puis mode une main ; sinon le mode une main seul. */
    fun grid(multilingual: Boolean): List<List<Char>> =
        if (multilingual) listOf(listOf(GLOBE, ONE_HANDED)) else listOf(listOf(ONE_HANDED))
}
