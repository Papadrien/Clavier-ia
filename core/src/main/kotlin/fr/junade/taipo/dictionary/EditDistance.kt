package fr.junade.taipo.dictionary

/**
 * Distances d'édition et pré-filtre par les lettres, extraits de [Dictionary] (audit 2.1, étape 6). Fonctions pures,
 * sans état : le code est celui d'avant l'extraction, `Dictionary` les expose toujours par des relais.
 */
object EditDistance {

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
     * Masque de 64 bits des lettres de [word] : une lettre = un bit (a-z : 0 à 25, apostrophe : 26, trait d'union :
     * 27, autres caractères, accentués compris : 28 à 63 par repli). Deux caractères qui partagent un bit sont
     * simplement confondus : le masque ne peut que moins distinguer deux mots, jamais plus, donc le pré-filtre
     * reste exact.
     */
    internal fun letterMask(word: String): Long {
        var mask = 0L
        for (c in word) {
            val bit = when {
                c in 'a'..'z' -> c - 'a'
                c == '\'' -> 26
                c == '-' -> 27
                else -> 28 + c.code % 36
            }
            mask = mask or (1L shl bit)
        }
        return mask
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

    /**
     * Même distance que [damerauLevenshtein] lorsqu'elle vaut au plus [maxDistance] ; sinon renvoie
     * `maxDistance + 1` (valeur sentinelle « trop loin »), souvent sans avoir rempli toute la matrice.
     *
     * Arrêt anticipé : une ligne de la matrice ne dépend que des deux lignes précédentes (la
     * troisième sert à l'inversion de lettres). Si le minimum de la ligne courante ET celui de la
     * précédente dépassent [maxDistance], aucune valeur ultérieure ne peut redescendre dessous.
     */
    internal fun damerauLevenshteinBounded(a: String, b: String, maxDistance: Int): Int {
        val tooFar = maxDistance + 1
        if (a == b) return 0
        if (Math.abs(a.length - b.length) > maxDistance) return tooFar
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var twoRowsAgo = IntArray(b.length + 1)
        var previousRow = IntArray(b.length + 1) { it }
        var currentRow = IntArray(b.length + 1)
        var previousRowMin = 0

        for (i in 1..a.length) {
            currentRow[0] = i
            var rowMin = i
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
                if (value < rowMin) rowMin = value
            }
            if (rowMin > maxDistance && previousRowMin > maxDistance) return tooFar
            previousRowMin = rowMin
            val recycled = twoRowsAgo
            twoRowsAgo = previousRow
            previousRow = currentRow
            currentRow = recycled
        }
        val distance = previousRow[b.length]
        return if (distance > maxDistance) tooFar else distance
    }

    /** Vrai si [b] s'obtient en inversant deux lettres voisines de [a] (« teh » / « the »). */
    internal fun isAdjacentSwap(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i + 1 >= a.length) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        return a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
    }
}
