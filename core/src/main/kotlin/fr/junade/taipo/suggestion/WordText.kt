package fr.junade.taipo.suggestion

/**
 * Règles de découpage des mots, partagées par les suggestions, l'autocorrection et le glissement de
 * suppression (extrait de `TaipoIme` au lot 2.3 de la revue, sans changement de comportement).
 */
object WordText {

    /** Lettres, apostrophe et trait d'union : les caractères qui composent un mot. */
    fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '-'

    /** Dernier « mot » avant le curseur : lettres/apostrophes/traits d'union contigus en fin de texte. */
    fun trailingWord(textBeforeCursor: String): String {
        var start = textBeforeCursor.length
        while (start > 0 && isWordChar(textBeforeCursor[start - 1])) start--
        return textBeforeCursor.substring(start)
    }

    /** Début du texte après le curseur qui prolonge le mot du curseur : lettres/apostrophes/traits d'union contigus. */
    fun leadingWord(textAfterCursor: String): String {
        var end = 0
        while (end < textAfterCursor.length && isWordChar(textAfterCursor[end])) end++
        return textAfterCursor.substring(0, end)
    }
}
