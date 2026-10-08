package fr.junade.taipo.dictionary

/**
 * Proximité des touches du clavier pour l'autocorrection.
 *
 * Décision produit : l'autocorrection reste 100 % locale et instantanée, sans modèle IA (le chargement
 * d'un modèle peut prendre plusieurs secondes). La proximité des touches est donc calculée à partir de la
 * géométrie réelle des rangées du clavier : deux lettres sont voisines si leurs touches se touchent ou se
 * recouvrent horizontalement sur la même rangée ou sur deux rangées contiguës.
 *
 * Elle ne change jamais l'ensemble des candidats (la distance d'édition de [Dictionary] reste le filtre) :
 * elle sert à départager les candidats situés à la même distance d'édition. Une faute d'un doigt qui glisse
 * sur la touche d'à côté (« cgat » -> « chat ») est plus probable qu'une lettre sans rapport avec la touche
 * visée.
 *
 * Classe pure (aucune dépendance Android) : le module de l'application construit les rangées à partir des
 * dispositions réelles (voir KeyProximityFactory).
 */
class KeyProximity private constructor(
    private val neighbors: Map<Char, Set<Char>>,
    private val missingLetterCost: Int,
) {

    /** Une touche d'une rangée : [char] est null pour une touche sans caractère (Maj, Effacer...). */
    class KeyBox(val char: Char?, val weight: Float)

    /** Faux pour [NONE] : aucune information de proximité, le coût pondéré n'est alors pas utilisé. */
    val isEnabled: Boolean get() = neighbors.isNotEmpty()

    /** Vrai si [a] et [b] sont deux touches voisines (insensible à la casse et aux accents). */
    fun areNeighbors(a: Char, b: Char): Boolean {
        val x = fold(a)
        val y = fold(b)
        if (x == y) return false
        return neighbors[x]?.contains(y) == true
    }

    /**
     * Coût d'édition pondéré pour passer de [typed] (minuscules) à [candidate] (minuscules), variante
     * « optimal string alignment » comme [Dictionary.damerauLevenshtein] mais avec des coûts qui
     * tiennent compte de la proximité des touches (échelle entière : 2 = « probable », 4 = « normal ») :
     *
     * - substitution par une touche voisine (ou même lettre sans l'accent) : 2 ; autre substitution : 4 ;
     * - lettre en trop dans le mot tapé, doublée ou voisine d'une lettre adjacente (doigt qui effleure deux
     *   touches) : 2 ; autre lettre en trop : 4 ;
     * - lettre manquante dans le mot tapé : [DEFAULT_MISSING_LETTER_COST] par défaut (réglable, voir [fromRows]) :
     *   2 par défaut, comme une touche voisine : oublier une lettre est aussi plausible que glisser sur la touche
     *   d'à côté, et à coût égal la fréquence départage (valeur retenue après mesure : 2 corrige mieux que 3 ou 4) ;
     * - inversion de deux lettres voisines : 2.
     *
     * Sert uniquement à départager des candidats déjà à la même distance d'édition ; renvoie 0 si la
     * proximité est désactivée.
     */
    fun editCost(typed: String, candidate: String): Int {
        if (!isEnabled || typed == candidate) return 0
        if (typed.isEmpty()) return candidate.length * missingLetterCost
        if (candidate.isEmpty()) return typed.length * COST_NORMAL

        var twoRowsAgo = IntArray(candidate.length + 1)
        var previousRow = IntArray(candidate.length + 1) { it * missingLetterCost }
        var currentRow = IntArray(candidate.length + 1)

        for (i in 1..typed.length) {
            val typedChar = typed[i - 1]
            currentRow[0] = previousRow[0] + extraCost(typed, i - 1)
            for (j in 1..candidate.length) {
                val candidateChar = candidate[j - 1]
                val substitution = when {
                    typedChar == candidateChar -> 0
                    sameKeyOrNeighbor(typedChar, candidateChar) -> COST_PROBABLE
                    else -> COST_NORMAL
                }
                var value = minOf(
                    currentRow[j - 1] + missingLetterCost, // lettre manquante dans le mot tapé
                    previousRow[j] + extraCost(typed, i - 1), // lettre en trop dans le mot tapé
                    previousRow[j - 1] + substitution,
                )
                if (i > 1 && j > 1 && typedChar == candidate[j - 2] && typed[i - 2] == candidateChar) {
                    value = minOf(value, twoRowsAgo[j - 2] + COST_PROBABLE)
                }
                currentRow[j] = value
            }
            val recycled = twoRowsAgo
            twoRowsAgo = previousRow
            previousRow = currentRow
            currentRow = recycled
        }
        return previousRow[candidate.length]
    }

    /** Coût de la lettre [index] de [typed] si elle est en trop : probable si elle double ou touche une lettre adjacente. */
    private fun extraCost(typed: String, index: Int): Int {
        val c = typed[index]
        val before = typed.getOrNull(index - 1)
        val after = typed.getOrNull(index + 1)
        val touchesBefore = before != null && (fold(before) == fold(c) || areNeighbors(before, c))
        val touchesAfter = after != null && (fold(after) == fold(c) || areNeighbors(after, c))
        return if (touchesBefore || touchesAfter) COST_PROBABLE else COST_NORMAL
    }

    private fun sameKeyOrNeighbor(a: Char, b: Char): Boolean = fold(a) == fold(b) || areNeighbors(a, b)

    companion object {
        private const val COST_PROBABLE = 2
        private const val COST_NORMAL = 4

        /** Aucune proximité connue : [Dictionary] se comporte exactement comme avant (départage par fréquence). */
        val NONE = KeyProximity(emptyMap(), COST_NORMAL)

        /** Coût d'une lettre manquante (échelle de [editCost]) : 2 = aussi probable qu'une touche voisine, 4 = erreur quelconque. */
        const val DEFAULT_MISSING_LETTER_COST = 2

        /**
         * Construit la proximité à partir de [rows] (de haut en bas, chaque rangée de gauche à droite). La
         * largeur d'une touche est proportionnelle à son poids, comme à l'écran : chaque rangée occupe toute la
         * largeur du clavier. Deux touches de caractère sont voisines si elles sont sur la même rangée ou sur
         * deux rangées contiguës et se touchent ou se recouvrent horizontalement.
         */
        fun fromRows(
            rows: List<List<KeyBox>>,
            missingLetterCost: Int = DEFAULT_MISSING_LETTER_COST,
        ): KeyProximity {
            class Placed(val char: Char, val row: Int, val start: Float, val end: Float)

            val placed = ArrayList<Placed>()
            rows.forEachIndexed { rowIndex, row ->
                val total = row.fold(0f) { acc, key -> acc + key.weight }
                if (total <= 0f) return@forEachIndexed
                var x = 0f
                for (key in row) {
                    val width = key.weight / total
                    key.char?.let { placed += Placed(fold(it), rowIndex, x, x + width) }
                    x += width
                }
            }

            val result = HashMap<Char, MutableSet<Char>>()
            for (a in placed) {
                for (b in placed) {
                    if (a === b || a.char == b.char) continue
                    if (kotlin.math.abs(a.row - b.row) > 1) continue
                    // Marge de 1 % de la largeur du clavier : deux touches côte à côte se touchent exactement.
                    val overlap = minOf(a.end, b.end) - maxOf(a.start, b.start)
                    if (overlap >= -TOUCH_TOLERANCE) result.getOrPut(a.char) { HashSet() }.add(b.char)
                }
            }
            return KeyProximity(result, missingLetterCost)
        }

        private const val TOUCH_TOLERANCE = 0.01f

        /** Lettre de base en minuscule (« É » -> « e »). */
        private fun fold(c: Char): Char {
            val lower = c.lowercaseChar()
            if (lower.code < 128) return lower
            return Dictionary.foldAccents(lower.toString()).firstOrNull() ?: lower
        }
    }
}
