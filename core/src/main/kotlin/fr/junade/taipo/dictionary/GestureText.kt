package fr.junade.taipo.dictionary

/**
 * Règles de texte de la saisie par glissement (19/11/2026), comme Gboard et SwiftKey : le mot glissé est écrit sans espace
 * après lui ; une espace est ajoutée AVANT lui si le texte précédent se termine par un mot ou une ponctuation, et avant une
 * lettre tapée juste après lui. Logique pure, testée en JVM (`GestureTextTest`).
 */
object GestureText {

    /** Caractères après lesquels un mot s'accole sans espace : apostrophe (l'avion), trait d'union, ouvrants, @, #, /. */
    private const val JOINERS = "'’-‐‑([{«\"“‘/@#_"

    /** Vrai si un mot glissé après [textBeforeCursor] doit être précédé d'une espace. */
    fun needsSpaceBefore(textBeforeCursor: String): Boolean {
        val last = textBeforeCursor.lastOrNull() ?: return false
        if (last.isWhitespace()) return false
        return last !in JOINERS
    }

    /** Vrai si [char], tapé juste après un mot glissé, commence un nouveau mot (espace à insérer avant lui). */
    fun startsNewWord(char: Char): Boolean = char.isLetterOrDigit()

    /**
     * Casse du mot glissé : tout en majuscules si les majuscules sont verrouillées ([capsLock]), première lettre en majuscule si
     * Maj est actif ([shifted], appui sur Maj ou début de phrase), sinon tel que dans le dictionnaire (« Paris » garde sa majuscule).
     */
    fun applyCase(word: String, shifted: Boolean, capsLock: Boolean): String = when {
        capsLock -> word.uppercase()
        shifted -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }
}
