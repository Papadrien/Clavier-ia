package fr.junade.taipo.ai

/**
 * Mots du dictionnaire personnel (story 1.4) à signaler au modèle de correction IA : noms propres,
 * jargon, pseudos que l'utilisateur a ajoutés et qui ne sont pas des fautes. Sans cette consigne le
 * modèle les « corrige » (il ne les connaît pas). Fonction pure, testable en JVM.
 */
object ProtectedWords {

    /** Plafond pour garder le prompt court : seuls les mots présents dans le texte sont envoyés. */
    const val MAX_WORDS = 50

    /**
     * Mots de [personalWords] qui apparaissent dans [text] comme mots entiers (sans tenir compte de
     * la casse ni de l'apostrophe typographique ; « l'Adrien » contient « Adrien », pas « Adri »).
     * Ordre de [personalWords], sans doublon, au plus [MAX_WORDS].
     */
    fun inText(text: String, personalWords: Collection<String>): List<String> {
        if (text.isEmpty() || personalWords.isEmpty()) return emptyList()
        val haystack = text.replace('\u2019', '\'')
        val found = LinkedHashMap<String, String>()
        for (word in personalWords) {
            if (found.size >= MAX_WORDS) break
            val key = word.lowercase()
            if (key in found || word.isEmpty()) continue
            if (containsWholeWord(haystack, word)) found[key] = word
        }
        return found.values.toList()
    }

    private fun containsWholeWord(haystack: String, word: String): Boolean {
        var from = 0
        while (true) {
            val index = haystack.indexOf(word, from, ignoreCase = true)
            if (index < 0) return false
            val before = haystack.getOrNull(index - 1)
            val after = haystack.getOrNull(index + word.length)
            if ((before == null || !isWordChar(before)) && (after == null || !isWordChar(after))) return true
            from = index + 1
        }
    }

    /** Lettre, chiffre ou trait d'union : l'apostrophe sépare (élision « l'Adrien »). */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-'
}
