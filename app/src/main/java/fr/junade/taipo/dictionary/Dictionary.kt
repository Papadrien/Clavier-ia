package fr.junade.taipo.dictionary

/**
 * Dictionnaire local pour l'autocorrection/les suggestions (story 1.3).
 *
 * Décision produit : pas d'IA pour cette fonction (jugée plus rapide et plus
 * fiable qu'une correction IA pour cet usage), et pas d'apprentissage
 * automatique — l'ensemble de mots est fixe une fois chargé. Le dictionnaire
 * personnel (ajout manuel par l'utilisateur, story 1.4) est une
 * responsabilité séparée (voir [PersonalDictionary]) : ses mots sont
 * simplement transmis à [correctionFor] à chaque appel, sans jamais être
 * mélangés à l'ensemble fixe chargé ici.
 *
 * Classe pure (aucune dépendance Android) : le chargement depuis les assets
 * de l'app se fait ailleurs (voir DictionaryLoader), pour rester testable en
 * JVM simple.
 */
class Dictionary(words: Collection<String>, extraWords: Collection<String> = emptySet()) {

    private val words: Set<String> = HashSet<String>(words.size + extraWords.size).apply {
        words.forEach { add(it.lowercase()) }
        extraWords.forEach { add(it.lowercase()) }
    }

    /** Vrai si [word] figure dans le dictionnaire (comparaison insensible à la casse). */
    fun contains(word: String): Boolean = word.lowercase() in words

    /**
     * Correction suggérée pour [word] si celui-ci ne figure pas dans le
     * dictionnaire, en cherchant le mot le plus proche à une distance
     * d'édition (Levenshtein) d'au plus [maxDistance]. La casse de la
     * première lettre du mot d'origine est réappliquée à la correction.
     *
     * [personalWords] (story 1.4) : mots ajoutés par l'utilisateur. Ils sont
     * considérés comme corrects (jamais corrigés) et servent aussi de
     * candidats de correction ; un mot personnel garde la casse enregistrée
     * par l'utilisateur (ex. "iPhone"), sauf s'il a été saisi en minuscules.
     *
     * Retourne null si :
     * - le mot est déjà dans le dictionnaire, ou dans [personalWords] (rien à corriger) ;
     * - le mot est trop court pour qu'une correction soit fiable ;
     * - le mot contient un chiffre (probablement pas un mot du langage
     *   courant : numéro, code...) ;
     * - le mot est entièrement en majuscules (probablement un acronyme) ;
     * - aucun candidat n'est assez proche, ou plusieurs candidats sont à
     *   égalité (ambiguïté : mieux vaut ne rien changer qu'un mauvais choix).
     */
    fun correctionFor(
        word: String,
        maxDistance: Int = 2,
        personalWords: Collection<String> = emptyList(),
    ): String? {
        if (word.length < MIN_WORD_LENGTH_FOR_CORRECTION) return null
        if (word.any { it.isDigit() }) return null
        if (word.length > 1 && word.all { it.isUpperCase() }) return null

        val lower = word.lowercase()
        if (lower in words) return null

        // minuscule -> forme enregistrée par l'utilisateur
        val personal = HashMap<String, String>(personalWords.size)
        personalWords.forEach { personal[it.lowercase()] = it }
        if (lower in personal) return null

        var best: String? = null
        var bestDistance = maxDistance + 1
        var ambiguous = false
        val candidates = words.asSequence() + personal.keys.asSequence().filter { it !in words }
        for (candidate in candidates) {
            if (Math.abs(candidate.length - lower.length) > maxDistance) continue
            val distance = levenshtein(lower, candidate)
            when {
                distance < bestDistance -> {
                    bestDistance = distance
                    best = candidate
                    ambiguous = false
                }
                distance == bestDistance && candidate != best -> ambiguous = true
            }
        }

        val correction = best ?: return null
        if (bestDistance > maxDistance || ambiguous) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    companion object {
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 2

        /** Distance d'édition de Levenshtein classique (insertion/suppression/substitution, coût 1 chacune). */
        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            val previousRow = IntArray(b.length + 1) { it }
            val currentRow = IntArray(b.length + 1)

            for (i in 1..a.length) {
                currentRow[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    currentRow[j] = minOf(
                        currentRow[j - 1] + 1,
                        previousRow[j] + 1,
                        previousRow[j - 1] + cost,
                    )
                }
                System.arraycopy(currentRow, 0, previousRow, 0, currentRow.size)
            }
            return previousRow[b.length]
        }

        /** Réapplique une majuscule initiale si [original] en avait une (ex. "Bnojour" -> "Bonjour"). */
        fun applyOriginalCasing(original: String, correction: String): String {
            if (original.isNotEmpty() && original[0].isUpperCase()) {
                return correction.replaceFirstChar { it.uppercaseChar() }
            }
            return correction
        }
    }
}
