package fr.junade.taipo.dictionary

/**
 * Prototype de table « mot -> fréquence » compacte (revue de code, mémoire), mesurée par l'écran de diagnostic face à la
 * `HashMap<String, Long>` actuelle du [Dictionary]. Elle n'est **pas encore utilisée par le clavier** : on la mesure d'abord
 * sur le téléphone.
 *
 * Pas d'objet par mot : tous les mots sont mis bout à bout dans un seul tableau de caractères ([chars], avec leurs débuts dans
 * [offsets]), les fréquences dans un tableau de `Long`, et une table de hachage à adressage ouvert ([slots]) donne l'indice d'un
 * mot. La `HashMap` coûte, par mot, une `String` (en-tête et tableau), un nœud et un `Long` en boîte ; ici, quelques octets.
 *
 * Lecture seule après construction ; [frequencyOf] n'alloue rien.
 */
class CompactWordTable private constructor(
    private val chars: CharArray,
    private val offsets: IntArray,
    private val frequencies: LongArray,
    private val slots: IntArray,
) {

    val size: Int get() = frequencies.size

    /** Fréquence de [word], ou [ABSENT]. */
    fun frequencyOf(word: CharSequence): Long {
        val mask = slots.size - 1
        var slot = hash(word) and mask
        while (true) {
            val entry = slots[slot]
            if (entry == 0) return ABSENT
            val index = entry - 1
            if (matches(index, word)) return frequencies[index]
            slot = (slot + 1) and mask
        }
    }

    /** Octets occupés par les tableaux (en-têtes d'objets non comptés) : pour comparer avec la mesure réelle. */
    val approximateBytes: Long
        get() = chars.size * 2L + offsets.size * 4L + frequencies.size * 8L + slots.size * 4L

    private fun matches(index: Int, word: CharSequence): Boolean {
        val start = offsets[index]
        val length = offsets[index + 1] - start
        if (length != word.length) return false
        for (i in 0 until length) if (chars[start + i] != word[i]) return false
        return true
    }

    companion object {
        const val ABSENT = -1L

        fun from(entries: Map<String, Long>): CompactWordTable {
            val count = entries.size
            var totalChars = 0
            for (word in entries.keys) totalChars += word.length
            val chars = CharArray(totalChars)
            val offsets = IntArray(count + 1)
            val frequencies = LongArray(count)
            var capacity = 1
            while (capacity < count * 2) capacity = capacity shl 1 // remplissage au plus 50 % : peu de sondages
            val slots = IntArray(maxOf(capacity, 2))
            val mask = slots.size - 1
            var index = 0
            var position = 0
            for ((word, frequency) in entries) {
                offsets[index] = position
                word.toCharArray(chars, position)
                position += word.length
                frequencies[index] = frequency
                var slot = hash(word) and mask
                while (slots[slot] != 0) slot = (slot + 1) and mask
                slots[slot] = index + 1
                index++
            }
            offsets[count] = position
            return CompactWordTable(chars, offsets, frequencies, slots)
        }

        /** Même calcul que `String.hashCode`, sur n'importe quelle suite de caractères, brassé pour l'adressage ouvert. */
        private fun hash(word: CharSequence): Int {
            var h = 0
            for (i in 0 until word.length) h = 31 * h + word[i].code
            return h xor (h ushr 16)
        }
    }
}
