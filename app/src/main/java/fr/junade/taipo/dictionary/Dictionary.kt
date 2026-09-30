package fr.junade.taipo.dictionary

import java.text.Normalizer

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
class Dictionary private constructor(
    frequencies: Map<String, Long>,
    private val inflections: InflectionRules,
) {

    /**
     * Mots seuls (sans fréquence connue) : tous ont la même fréquence, donc aucun n'est
     * préféré à un autre — comportement historique (égalité = ambiguïté).
     */
    constructor(
        words: Collection<String>,
        extraWords: Collection<String> = emptySet(),
        inflections: InflectionRules = InflectionRules.NONE,
    ) : this(
        HashMap<String, Long>(words.size + extraWords.size).apply {
            words.forEach { put(it.lowercase(), 1L) }
            extraWords.forEach { put(it.lowercase(), 1L) }
        },
        inflections,
    )

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

    // Mots accentués indexés par leur forme sans accent (« annee » -> [« année »]) : oublier un
    // accent est la faute la plus courante en français. Seuls les mots qui contiennent un accent
    // sont indexés (~12 000 sur 50 000).
    private val accentedByFolded: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        for (word in candidateWords) {
            val folded = foldAccents(word)
            if (folded != word) getOrPut(folded) { ArrayList(2) }.add(word)
        }
    }

    // Formes sans accent présentes dans les listes mais nettement moins fréquentes que leur forme
    // accentuée : ce sont des fautes du corpus (« ca », « etait », « tres », « duree »), pas des mots.
    // Valeur = forme accentuée à proposer. Assez strict pour laisser « a »/« à », « ou »/« où »,
    // « la »/« là » intacts (les deux formes existent et sont fréquentes).
    private val dominatedByAccented: Map<String, String> = HashMap<String, String>().apply {
        for ((folded, accented) in accentedByFolded) {
            val plainFrequency = frequencies[folded] ?: continue
            val top = accented.maxOf { frequencies.getValue(it) }
            if (top < plainFrequency * ACCENT_DOMINANCE) continue
            val leaders = accented.filter { frequencies.getValue(it) == top }
            if (leaders.size == 1) put(folded, leaders[0])
        }
    }

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
     * Accents (story 1.14 bis) : un mot dont la seule différence avec un mot connu est l'absence
     * d'accent est d'abord rétabli (« durees » -> « durées », « etre » -> « être », « ca » -> « ça »)
     * avant toute recherche par distance d'édition. Une forme sans accent présente dans les listes
     * (faute fréquente du corpus : « ca », « etait », « tres ») est aussi corrigée quand la forme
     * accentuée est au moins 10 fois plus fréquente.
     *
     * Formes régulières : un pluriel/féminin/conjugaison régulier d'un mot connu (voir
     * [InflectionRules]) est considéré comme correct et n'est pas modifié (« durées »).
     *
     * Longueur : les mots de 4 lettres ou moins ne sont corrigés qu'à distance 1 (à distance 2, la
     * moitié du mot serait changée, ex. « teh » -> « t'en »). À distance égale, une inversion de
     * deux lettres voisines (« teh » -> « the ») l'emporte sur les autres candidats.
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

        // minuscule -> forme enregistrée par l'utilisateur
        val personal = HashMap<String, String>(personalWords.size)
        personalWords.forEach { personal[it.lowercase()] = it }
        if (lower in personal) return null

        if (lower in frequencies) {
            // Mot présent dans les listes : corrigé seulement si c'est une faute du corpus dont la
            // version accentuée domine nettement (« ca » -> « ça »).
            val accented = dominatedByAccented[lower] ?: return null
            return applyOriginalCasing(word, accented)
        }

        // Pluriel, féminin ou conjugaison régulière d'un mot connu : correct, absent des listes.
        if (isInflectedForm(lower)) return null

        // Apostrophe oubliée : correction directe, sans passer par la distance d'édition (où un mot
        // voisin plus fréquent, comme "est" pour "cest", l'emporterait à tort sur "c'est").
        contractionsWithoutApostrophe[lower]?.let { return applyOriginalCasing(word, it) }

        // Accent oublié : la forme accentuée (éventuellement fléchie) est la correction évidente.
        accentRestoration(lower)?.let { return applyOriginalCasing(word, it.word) }

        val allowedDistance = minOf(maxDistance, maxDistanceForLength(lower.length))

        var best: String? = null
        var bestDistance = allowedDistance + 1
        var bestFrequency = -1L
        var bestIsSwap = false
        var ambiguous = false

        fun consider(candidate: String, frequency: Long) {
            if (Math.abs(candidate.length - lower.length) > allowedDistance) return
            val distance = damerauLevenshtein(lower, candidate)
            if (distance > allowedDistance) return
            val isSwap = distance == 1 && isAdjacentSwap(lower, candidate)
            val better = distance < bestDistance || (
                distance == bestDistance && (
                    (isSwap && !bestIsSwap) || (isSwap == bestIsSwap && frequency > bestFrequency)
                    )
                )
            when {
                better -> {
                    bestDistance = distance
                    bestFrequency = frequency
                    bestIsSwap = isSwap
                    best = candidate
                    ambiguous = false
                }
                distance == bestDistance && isSwap == bestIsSwap && frequency == bestFrequency && candidate != best ->
                    ambiguous = true
            }
        }

        for (i in candidateWords.indices) consider(candidateWords[i], candidateFrequencies[i])
        for (candidate in personal.keys) if (candidate !in frequencies) consider(candidate, personalFrequency)

        val correction = best ?: return null
        if (bestDistance > allowedDistance || ambiguous) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    /**
     * Story 1.17 : mots proposés dans la bande de suggestions pour le mot en cours de frappe
     * [typed] (au plus [limit]). Toujours local : pas d'IA, pas d'apprentissage (décision de la
     * story 1.3).
     *
     * Ordre : d'abord la correction de [correctionFor] si le mot tapé est une faute, puis les
     * mots qui commencent par [typed] (complétions), ceux du dictionnaire personnel avant les
     * autres, puis par fréquence décroissante (à égalité : le plus court, puis l'ordre
     * alphabétique, pour un résultat stable). Le mot tapé lui-même n'est jamais proposé.
     *
     * La casse de la première lettre tapée est reprise ; un mot personnel garde sa casse
     * enregistrée (« iPhone ») sauf s'il est tapé en minuscules. Rien n'est proposé pour un mot
     * sans lettre, contenant un chiffre, ou entièrement en majuscules (acronyme).
     */
    fun suggestionsFor(
        typed: String,
        limit: Int = SUGGESTION_LIMIT,
        personalWords: Collection<String> = emptyList(),
    ): List<String> {
        if (limit <= 0 || !isSuggestible(typed)) return emptyList()
        val correction = correctionFor(typed, personalWords = personalWords)
        return (listOfNotNull(correction) + completionsFor(typed, limit, personalWords, correction)).take(limit)
    }

    /**
     * Story 1.17 : contenu des [SUGGESTION_LIMIT] emplacements de mots de la bande (null = vide).
     *
     * - Si le mot tapé va être corrigé à l'espace (même [correctionFor] que l'autocorrection) :
     *   [mot tapé, correction, 1re complétion], la correction au centre (gras, elle va remplacer le
     *   mot tapé) et le mot tapé à gauche pour pouvoir la refuser.
     * - Sinon : les complétions, de gauche à droite, en simples suggestions (poids normal).
     */
    fun suggestionSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
    ): List<WordSuggestion?> {
        val empty = List<WordSuggestion?>(SUGGESTION_LIMIT) { null }
        if (!isSuggestible(typed)) return empty
        val correction = correctionFor(typed, personalWords = personalWords)
        val completions = completionsFor(typed, SUGGESTION_LIMIT, personalWords, correction)
        fun completion(index: Int) = completions.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        if (correction != null) {
            return listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(correction, WordSuggestion.Kind.AUTOCORRECTION),
                completion(0),
            )
        }
        return List(SUGGESTION_LIMIT) { completion(it) }
    }

    private fun isSuggestible(typed: String): Boolean = when {
        typed.none { it.isLetter() } -> false
        typed.any { it.isDigit() } -> false
        typed.length > 1 && typed.all { it.isUpperCase() } -> false
        else -> true
    }

    /**
     * Au plus [limit] complétions de [typed] : mots personnels d'abord, puis mots du dictionnaire.
     * Ni le mot tapé ni [exclude] (la correction, déjà proposée à part) n'y figurent.
     */
    private fun completionsFor(
        typed: String,
        limit: Int,
        personalWords: Collection<String>,
        exclude: String?,
    ): List<String> {
        val lower = typed.lowercase()
        val result = ArrayList<String>(limit)
        // Clés minuscules déjà proposées (« Chat » et « chat » ne comptent qu'une fois).
        val seen = HashSet<String>()
        seen += lower
        exclude?.let { seen += it.lowercase() }

        fun offer(display: String, key: String) {
            if (result.size < limit && seen.add(key)) result += display
        }

        // Mots personnels : alphabétique (l'ordre de la liste), avant les mots du dictionnaire.
        for (personal in personalWords) {
            val key = personal.lowercase()
            if (key.startsWith(lower)) offer(personalDisplay(typed, personal), key)
        }
        if (result.size < limit) {
            for (word in topCompletions(lower, limit + 1)) offer(applyOriginalCasing(typed, word), word)
        }
        return result
    }

    /** Mot personnel tel qu'affiché : casse enregistrée si elle est particulière, sinon casse tapée. */
    private fun personalDisplay(typed: String, stored: String): String =
        if (stored != stored.lowercase()) stored else applyOriginalCasing(typed, stored)

    /**
     * Les [count] mots du dictionnaire qui commencent par [prefix] (minuscules) et sont plus longs
     * que lui : fréquence décroissante, puis longueur croissante, puis ordre alphabétique.
     * Insertion bornée : pas de tri des quelques milliers de mots qui commencent par une lettre.
     */
    private fun topCompletions(prefix: String, count: Int): List<String> {
        // Marge de 2 pour absorber les doublons avec les mots personnels déjà proposés.
        val capacity = count + 2
        val bestWords = arrayOfNulls<String>(capacity)
        val bestFrequencies = LongArray(capacity)
        var size = 0

        fun ranksBefore(word: String, frequency: Long, otherWord: String, otherFrequency: Long): Boolean = when {
            frequency != otherFrequency -> frequency > otherFrequency
            word.length != otherWord.length -> word.length < otherWord.length
            else -> word < otherWord
        }

        for (i in candidateWords.indices) {
            val word = candidateWords[i]
            if (word.length <= prefix.length || !word.startsWith(prefix)) continue
            val frequency = candidateFrequencies[i]
            if (size == capacity && !ranksBefore(word, frequency, bestWords[size - 1]!!, bestFrequencies[size - 1])) continue

            var position = if (size < capacity) size else size - 1
            while (position > 0 && ranksBefore(word, frequency, bestWords[position - 1]!!, bestFrequencies[position - 1])) {
                bestWords[position] = bestWords[position - 1]
                bestFrequencies[position] = bestFrequencies[position - 1]
                position--
            }
            bestWords[position] = word
            bestFrequencies[position] = frequency
            if (size < capacity) size++
        }
        return List(size) { bestWords[it]!! }
    }

    /** Forme accentuée trouvée pour une forme tapée. */
    private class Restoration(val word: String)

    /**
     * Forme accentuée de [lower] : un mot connu qui n'en diffère que par les accents, ou une forme
     * régulière d'un tel mot (« durees » -> « durées »). Null s'il n'y en a pas, ou si plusieurs
     * formes sont à égalité de fréquence (ambiguïté). Une forme fléchie hérite d'une fréquence
     * réduite : à égalité de lettres, le mot du dictionnaire prime.
     */
    private fun accentRestoration(lower: String): Restoration? {
        val folded = foldAccents(lower)
        var best: String? = null
        var bestFrequency = -1L
        var tie = false

        fun offer(candidate: String, frequency: Long) {
            if (candidate == lower) return
            when {
                frequency > bestFrequency -> {
                    best = candidate
                    bestFrequency = frequency
                    tie = false
                }
                frequency == bestFrequency && candidate != best -> tie = true
            }
        }

        accentedByFolded[folded]?.forEach { offer(it, frequencies.getValue(it)) }
        for (rule in inflections.rules) {
            val base = rule.baseOf(folded) ?: continue
            accentedByFolded[base]?.forEach { accentedBase ->
                val form = rule.formOf(accentedBase) ?: return@forEach
                offer(form, frequencies.getValue(accentedBase) / INFLECTED_FREQUENCY_DIVISOR)
            }
        }
        val result = best ?: return null
        return if (tie) null else Restoration(result)
    }

    /** Vrai si [lower] est une forme régulière (voir [InflectionRules]) d'un mot du dictionnaire. */
    private fun isInflectedForm(lower: String): Boolean = inflections.rules.any { rule ->
        // Une base qui est une faute du corpus (« duree ») ne valide pas sa forme fléchie (« durees »).
        rule.baseOf(lower)?.let { it in frequencies && it !in dominatedByAccented } == true
    }

    companion object {
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 2

        /** Story 1.17 : nombre d'emplacements de mots de la bande de suggestions. */
        const val SUGGESTION_LIMIT = 3

        /**
         * Une forme sans accent déjà présente dans les listes est corrigée si sa version accentuée
         * est au moins 10 fois plus fréquente (« ca » 190 000 contre « ça » 2 700 000).
         */
        private const val ACCENT_DOMINANCE = 10L

        /** Une forme fléchie retrouvée par les règles compte pour 1/4 de la fréquence de son mot de base. */
        private const val INFLECTED_FREQUENCY_DIVISOR = 4L

        /** Distance d'édition maximale selon la longueur du mot tapé : 1 pour 4 lettres ou moins. */
        private fun maxDistanceForLength(length: Int): Int = if (length <= 4) 1 else Int.MAX_VALUE

        /** Vrai si [b] s'obtient en inversant deux lettres voisines de [a] (« teh » / « the »). */
        internal fun isAdjacentSwap(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var i = 0
            while (i < a.length && a[i] == b[i]) i++
            if (i + 1 >= a.length) return false
            if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
            return a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
        }

        /**
         * Mot sans accents ni cédille (« durée » -> « duree », « ça » -> « ca »), œ et æ développés.
         * Attend une chaîne en minuscules ; renvoie la même instance si elle est déjà en ASCII.
         */
        fun foldAccents(word: String): String {
            if (word.all { it.code < 128 }) return word
            val decomposed = Normalizer.normalize(word.replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD)
            val builder = StringBuilder(decomposed.length)
            for (c in decomposed) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) builder.append(c)
            }
            return builder.toString()
        }

        /**
         * Dictionnaire avec fréquences (mot -> nombre d'occurrences). Les mots sont mis en
         * minuscules ; si deux formes ne diffèrent que par la casse, la plus fréquente est gardée.
         */
        fun withFrequencies(
            entries: Map<String, Long>,
            inflections: InflectionRules = InflectionRules.NONE,
        ): Dictionary {
            val merged = HashMap<String, Long>(entries.size)
            entries.forEach { (word, count) ->
                val key = word.lowercase()
                val previous = merged[key]
                if (previous == null || count > previous) merged[key] = count
            }
            return Dictionary(merged, inflections)
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
