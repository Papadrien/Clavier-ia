package fr.junade.taipo.dictionary

/**
 * Frappe [sentence] comme au clavier : à chaque espace ou signe de ponctuation, [correct] (la correction de contexte d'une langue)
 * regarde la fin du texte. Même méthode que `SpanishConfusionsTest`.
 */
internal fun typeWithContext(sentence: String, correct: (String) -> String?): String {
    fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'
    var text = ""
    for (c in sentence) {
        if (!isWordChar(c) && text.isNotEmpty() && !text.last().isWhitespace()) text = correct(text) ?: text
        text += c
    }
    return correct(text) ?: text
}
