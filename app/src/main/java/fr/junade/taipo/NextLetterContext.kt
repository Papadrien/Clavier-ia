package fr.junade.taipo

import fr.junade.taipo.dictionary.NextLetterModel
import fr.junade.taipo.suggestion.WordText

/**
 * Zones de frappe adaptatives, étape 4 : du texte avant le curseur au mot dont on prédit la lettre suivante.
 * Logique pure (sans vue), testée en JVM.
 */
internal object NextLetterContext {

    // Le mot en cours est collé à l'un de ces caractères : adresse, chemin, identifiant ou nombre, pas du langage courant.
    private val NOT_A_WORD_START = charArrayOf('@', '/', '\\', ':', '_')

    /**
     * Ce qui peut suivre le mot en cours de frappe à la fin de [textBeforeCursor], ou null (pas de prédiction : zones
     * nominales). Après une espace ou une ponctuation, le mot en cours est vide et la prédiction porte sur la première
     * lettre. Un mot que le dictionnaire ne connaît pas (nom propre, faute) n'a pas de prédiction ; une élision (« l' »,
     * « qu' ») ou un trait d'union repart du mot qui suit.
     */
    fun predict(model: NextLetterModel, textBeforeCursor: String): NextLetterModel.Probabilities? {
        val word = WordText.trailingWord(textBeforeCursor)
        val before = textBeforeCursor.getOrNull(textBeforeCursor.length - word.length - 1)
        if (before != null && (before.isDigit() || before in NOT_A_WORD_START)) return null
        model.probabilitiesAfter(word)?.let { return it }
        val cut = word.indexOfLast { it == '\'' || it == '\u2019' || it == '-' }
        return if (cut >= 0) model.probabilitiesAfter(word.substring(cut + 1)) else null
    }
}
