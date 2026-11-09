package fr.junade.taipo.dictionary

import kotlin.math.pow

/**
 * Modèle de la lettre suivante : d'après le début du mot en cours de frappe, la probabilité de chaque lettre (et de la
 * fin du mot) qui va être tapée ensuite. Étape 1 des zones de frappe adaptatives : le modèle ne connaît ni les touches
 * ni l'écran, la géométrie des zones est un autre lot.
 *
 * Estimation : parmi les mots du dictionnaire qui commencent par le préfixe, la part de la fréquence de ceux qui
 * continuent par une lettre donnée. Les mots sont repliés (minuscules, sans accent : « é » compte pour la touche « e »)
 * et triés une fois pour toutes. Les mots qui ont le préfixe forment alors une plage contiguë, et ceux qui continuent par
 * la même lettre une sous-plage : une requête coûte quelques recherches dichotomiques et des sommes cumulées, sans
 * parcourir la liste (de l'ordre de la microseconde pour 50 000 mots) ni garder de cache.
 *
 * Immuable : [probabilitiesAfter] peut être appelé depuis n'importe quel thread. La construction trie la liste (quelques
 * dizaines de millisecondes pour 50 000 mots) : à faire en arrière-plan, avec le préchargement du dictionnaire.
 *
 * Classe pure (aucune dépendance Android), testée en JVM.
 */
class NextLetterModel private constructor(
    // Mots repliés, triés par ordre lexicographique (doublons possibles : « été » et « ete » se replient sur « ete »).
    private val words: Array<String>,
    // cumulative[i] = somme des poids des mots [0, i) : le poids d'une plage est une simple différence.
    private val cumulative: DoubleArray,
) {

    /**
     * Répartition de ce qui suit un préfixe. [next] : chaque caractère qui peut suivre (en minuscule sans accent : la touche
     * d'une lettre accentuée, mais aussi l'apostrophe de « qu'il » ou le trait d'union de « peut-être »), avec sa
     * probabilité ; [endOfWord] : le préfixe est lui-même un mot (espace ou ponctuation à suivre). Les deux totalisent 1.
     * [wordCount] : nombre de mots du dictionnaire qui commencent par le préfixe, pour juger de la fiabilité de l'estimation
     * (peu de mots = estimation fragile).
     */
    class Probabilities(
        val next: Map<Char, Double>,
        val endOfWord: Double,
        val wordCount: Int,
    ) {
        /** Probabilité de [char] (0 s'il ne peut pas suivre ce préfixe). */
        fun of(char: Char): Double = next[char] ?: 0.0
    }

    /**
     * Ce qui peut suivre [prefix] (le mot en cours de frappe, avec ou sans majuscule, accents ou apostrophe typographique),
     * ou null si aucun mot du dictionnaire ne commence par ce préfixe (mot rare, nom propre, faute...) : pas de prédiction.
     * Un préfixe vide donne la répartition des premières lettres.
     */
    fun probabilitiesAfter(prefix: String): Probabilities? {
        val p = normalize(prefix)
        val start = firstIndexNotBefore(p)
        val end = endOfPrefixRange(p, start)
        if (start == end) return null

        val total = weight(start, end)
        var cursor = start
        // Le mot égal au préfixe se trie avant tous les mots plus longs : ce sont les premiers de la plage.
        var endMass = 0.0
        while (cursor < end && words[cursor].length == p.length) {
            endMass += weight(cursor, cursor + 1)
            cursor++
        }
        val next = HashMap<Char, Double>()
        while (cursor < end) {
            val char = words[cursor][p.length]
            val runEnd = endOfRun(cursor, end, p.length, char)
            next[char] = weight(cursor, runEnd) / total
            cursor = runEnd
        }
        return Probabilities(next, endMass / total, end - start)
    }

    private fun weight(from: Int, to: Int): Double = cumulative[to] - cumulative[from]

    // Premier mot qui n'est pas inférieur à [p] (le premier de ceux qui commencent par [p], s'il y en a).
    private fun firstIndexNotBefore(p: String): Int {
        var low = 0
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid] < p) low = mid + 1 else high = mid
        }
        return low
    }

    // À partir de [start], les mots qui commencent par [p] viennent d'abord : premier indice qui n'en est plus un.
    private fun endOfPrefixRange(p: String, start: Int): Int {
        var low = start
        var high = words.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid].startsWith(p)) low = mid + 1 else high = mid
        }
        return low
    }

    // Dans [from, end), les mots sont triés sur leur caractère à [position] : premier indice dont le caractère diffère de [char].
    private fun endOfRun(from: Int, end: Int, position: Int, char: Char): Int {
        var low = from + 1
        var high = end
        while (low < high) {
            val mid = (low + high) ushr 1
            if (words[mid][position] == char) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /**
         * Exposant appliqué à la fréquence des mots : 1 = fréquence brute ; une valeur plus petite (0,5 : racine carrée)
         * atténue la domination d'un mot très courant sur les mots rares qui partagent son début. Réglage prévu pour la
         * mesure par simulation (étape 3).
         */
        const val DEFAULT_FREQUENCY_EXPONENT = 1.0

        /** Construit le modèle des candidats d'un dictionnaire : [words] et [frequencies] sont deux tableaux parallèles. */
        fun fromCandidates(
            words: Array<String>,
            frequencies: LongArray,
            frequencyExponent: Double = DEFAULT_FREQUENCY_EXPONENT,
        ): NextLetterModel {
            require(words.size == frequencies.size) { "Un mot par fréquence : ${words.size} mots, ${frequencies.size} fréquences" }
            require(frequencyExponent > 0.0) { "L'exposant de fréquence doit être positif : $frequencyExponent" }
            val folded = Array(words.size) { normalize(words[it]) }
            val order = folded.indices.sortedBy { folded[it] }
            val sorted = Array(words.size) { folded[order[it]] }
            val cumulative = DoubleArray(words.size + 1)
            for (i in sorted.indices) {
                // Fréquence d'au moins 1 : un mot présent dans la liste compte toujours (et le total n'est jamais nul).
                val frequency = frequencies[order[i]].coerceAtLeast(1L).toDouble()
                val weight = if (frequencyExponent == 1.0) frequency else frequency.pow(frequencyExponent)
                cumulative[i + 1] = cumulative[i] + weight
            }
            return NextLetterModel(sorted, cumulative)
        }

        /** Construit le modèle d'une liste « mot -> fréquence » (tests, outils de mesure). */
        fun fromFrequencies(entries: Map<String, Long>, frequencyExponent: Double = DEFAULT_FREQUENCY_EXPONENT): NextLetterModel =
            fromCandidates(entries.keys.toTypedArray(), entries.values.toLongArray(), frequencyExponent)

        // Minuscules, apostrophe droite, sans accent : la forme sous laquelle les mots et les préfixes se comparent.
        internal fun normalize(text: String): String =
            WordForms.foldAccents(text.lowercase().replace('\u2019', '\''))
    }
}
