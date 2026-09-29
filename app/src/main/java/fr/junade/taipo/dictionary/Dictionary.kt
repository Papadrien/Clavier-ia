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
class Dictionary private constructor(frequencies: Map<String, Long>) {

    /**
     * Mots seuls (sans fréquence connue) : tous ont la même fréquence, donc aucun n'est
     * préféré à un autre — comportement historique (égalité = ambiguïté).
     */
    constructor(words: Collection<String>, extraWords: Collection<String> = emptySet()) :
        this(HashMap<String, Long>(words.size + extraWords.size).apply {
            words.forEach { put(it.lowercase(), 1L) }
            extraWords.forEach { put(it.lowercase(), 1L) }
        })

    // minuscule -> fréquence (nombre d'occurrences dans le corpus source).
    private val frequencies: Map<String, Long> = frequencies

    // Copies en tableaux parallèles : parcours des candidats plus rapide que sur la Map (50 000 mots).
    private val candidateWords: Array<String> = frequencies.keys.toTypedArray()
    private val candidateFrequencies: LongArray = LongArray(candidateWords.size) { frequencies.getValue(candidateWords[it]) }

    // Contractions indexées sans leur apostrophe ("jai" -> "j'ai", "dont" -> "don't") : oublier
    // l'apostrophe est l'erreur la plus courante au clavier ; en cas de collision, la plus fréquente gagne.
    private val contractionsWithoutApostrophe: Map<String, String> = HashMap<String, String>().apply {
        for (i in candidateWords.indices) {
            val word = candidateWords[i]
            if ('\'' !in word) continue
            val key = word.replace("'", "")
            val existing = get(key)
            if (existing == null || candidateFrequencies[i] > frequencies.getValue(existing)) put(key, word)
        }
    }

    // Fréquence attribuée aux mots personnels : strictement supérieure à toutes celles du dictionnaire
    // (l'utilisateur les a ajoutés volontairement, ils priment sur un mot du dictionnaire à distance égale).
    private val personalFrequency: Long = (candidateFrequencies.maxOrNull() ?: 0L) + 1L

    /** Vrai si [word] figure dans le dictionnaire (comparaison insensible à la casse). */
    fun contains(word: String): Boolean = word.lowercase() in frequencies

    /**
     * Correction suggérée pour [word] si celui-ci ne figure pas dans le
     * dictionnaire, en cherchant le mot le plus proche à une distance
     * d'édition (Damerau-Levenshtein : une inversion de deux lettres voisines compte
     * pour 1, pas pour 2) d'au plus [maxDistance]. La casse de la
     * première lettre du mot d'origine est réappliquée à la correction.
     *
     * [personalWords] (story 1.4) : mots ajoutés par l'utilisateur. Ils sont
     * considérés comme corrects (jamais corrigés) et servent aussi de
     * candidats de correction ; un mot personnel garde la casse enregistrée
     * par l'utilisateur (ex. "iPhone"), sauf s'il a été saisi en minuscules.
     *
     * Choix du candidat (story 1.14 — fréquences) : le plus proche d'abord (distance d'édition
     * minimale) ; à distance égale, le mot le plus fréquent dans le corpus l'emporte
     * (ex. "dont" -> "done" plutôt qu'un mot rare). Les mots personnels priment sur ceux du
     * dictionnaire à distance égale. Un dictionnaire sans fréquences (constructeur [Collection]) donne la même
     * fréquence à tous les mots : l'égalité reste alors ambiguë.
     *
     * Cas particulier : un mot qui n'est qu'une contraction du dictionnaire sans son apostrophe
     * ("jai", "cest", "dont" en anglais) est corrigé directement en "j'ai", "c'est", "don't".
     *
     * Retourne null si :
     * - le mot est déjà dans le dictionnaire, ou dans [personalWords] (rien à corriger) ;
     * - le mot est trop court pour qu'une correction soit fiable ;
     * - le mot contient un chiffre (probablement pas un mot du langage
     *   courant : numéro, code...) ;
     * - le mot est entièrement en majuscules (probablement un acronyme) ;
     * - aucun candidat n'est assez proche, ou plusieurs candidats sont à égalité de
     *   distance ET de fréquence (ambiguïté : mieux vaut ne rien changer qu'un mauvais choix).
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
        if (lower in frequencies) return null

        // minuscule -> forme enregistrée par l'utilisateur
        val personal = HashMap<String, String>(personalWords.size)
        personalWords.forEach { personal[it.lowercase()] = it }
        if (lower in personal) return null

        // Apostrophe oubliée : correction directe, sans passer par la distance d'édition (où un mot
        // voisin plus fréquent, comme "est" pour "cest", l'emporterait à tort sur "c'est").
        contractionsWithoutApostrophe[lower]?.let { return applyOriginalCasing(word, it) }

        var best: String? = null
        var bestDistance = maxDistance + 1
        var bestFrequency = -1L
        var ambiguous = false

        fun consider(candidate: String, frequency: Long) {
            if (Math.abs(candidate.length - lower.length) > maxDistance) return
            val distance = damerauLevenshtein(lower, candidate)
            if (distance > maxDistance) return
            when {
                distance < bestDistance || (distance == bestDistance && frequency > bestFrequency) -> {
                    bestDistance = distance
                    bestFrequency = frequency
                    best = candidate
                    ambiguous = false
                }
                distance == bestDistance && frequency == bestFrequency && candidate != best -> ambiguous = true
            }
        }

        for (i in candidateWords.indices) consider(candidateWords[i], candidateFrequencies[i])
        for (candidate in personal.keys) if (candidate !in frequencies) consider(candidate, personalFrequency)

        val correction = best ?: return null
        if (bestDistance > maxDistance || ambiguous) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    companion object {
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 2

        /**
         * Dictionnaire avec fréquences (mot -> nombre d'occurrences). Les mots sont mis en
         * minuscules ; si deux formes ne diffèrent que par la casse, la plus fréquente est gardée.
         */
        fun withFrequencies(entries: Map<String, Long>): Dictionary {
            val merged = HashMap<String, Long>(entries.size)
            entries.forEach { (word, count) ->
                val key = word.lowercase()
                val previous = merged[key]
                if (previous == null || count > previous) merged[key] = count
            }
            return Dictionary(merged)
        }

        /**
         * Lit des lignes « mot fréquence » (séparées par un espace). Une ligne sans fréquence
         * valide (mot seul, ou fréquence non numérique) compte pour 1 ; les lignes vides sont ignorées.
         */
        fun parseFrequencyLines(lines: Sequence<String>): Map<String, Long> {
            val result = HashMap<String, Long>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val separator = trimmed.lastIndexOf(' ')
                val word: String
                val count: Long
                if (separator > 0) {
                    val parsed = trimmed.substring(separator + 1).toLongOrNull()
                    if (parsed != null) {
                        word = trimmed.substring(0, separator).trim()
                        count = parsed
                    } else {
                        word = trimmed
                        count = 1L
                    }
                } else {
                    word = trimmed
                    count = 1L
                }
                if (word.isNotEmpty()) result[word] = maxOf(result[word] ?: 0L, count)
            }
            return result
        }

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

        /**
         * Distance d'édition de Damerau-Levenshtein (variante « optimal string alignment ») :
         * insertion, suppression, substitution et inversion de deux lettres adjacentes, coût 1
         * chacune. Une inversion (« teh » -> « the ») compte donc pour 1 et non pour 2.
         */
        fun damerauLevenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var twoRowsAgo = IntArray(b.length + 1)
            var previousRow = IntArray(b.length + 1) { it }
            var currentRow = IntArray(b.length + 1)

            for (i in 1..a.length) {
                currentRow[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = minOf(
                        currentRow[j - 1] + 1,
                        previousRow[j] + 1,
                        previousRow[j - 1] + cost,
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = minOf(value, twoRowsAgo[j - 2] + 1)
                    }
                    currentRow[j] = value
                }
                val recycled = twoRowsAgo
                twoRowsAgo = previousRow
                previousRow = currentRow
                currentRow = recycled
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
