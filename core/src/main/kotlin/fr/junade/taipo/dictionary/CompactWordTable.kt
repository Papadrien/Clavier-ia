package fr.junade.taipo.dictionary

import java.util.AbstractMap.SimpleImmutableEntry

/**
 * Table « mot -> fréquence » compacte du [Dictionary] (revue de code, mémoire), en lecture seule.
 *
 * Pas d'objet par mot : tous les mots sont mis bout à bout dans un seul tableau de caractères ([chars], avec leurs débuts dans
 * [offsets]), les fréquences dans un tableau d'`Int` (de `Long` seulement si l'une d'elles dépasse un `Int`), et une table de
 * hachage à adressage ouvert ([slots]) donne l'indice d'un mot. La `HashMap<String, Long>` coûtait, par mot, une `String`
 * (en-tête et tableau), un nœud et un `Long` en boîte ; ici, une vingtaine d'octets.
 *
 * Mesure sur un Pixel 9 (diagnostic mémoire, liste française de 251 704 mots) : 8,8 Mo au lieu de 15,4 Mo avec des fréquences
 * en `Long` (environ 1 Mo de moins en `Int`), construite en 130 ms.
 *
 * C'est une [Map] : [get], [containsKey] et [frequencyOf] n'allouent rien d'autre que la boîte du résultat de [get] ;
 * parcourir [entries] (ou [keys]) recrée une chaîne par mot, à réserver à la construction du dictionnaire et aux outils.
 */
class CompactWordTable private constructor(
    private val chars: CharArray,
    private val offsets: IntArray,
    /** Fréquences en `Int` (cas des listes de l'application), ou null si l'une dépasse : elles sont alors dans [longFrequencies]. */
    private val intFrequencies: IntArray?,
    private val longFrequencies: LongArray?,
    private val slots: IntArray,
) : AbstractMap<String, Long>() {

    override val size: Int get() = offsets.size - 1

    override fun isEmpty(): Boolean = size == 0

    override fun get(key: String): Long? = frequencyOf(key).takeIf { it != ABSENT }

    override fun containsKey(key: String): Boolean = indexOf(key) >= 0

    /** Fréquence de [word], ou [ABSENT]. */
    fun frequencyOf(word: CharSequence): Long {
        val index = indexOf(word)
        return if (index < 0) ABSENT else frequencyAt(index)
    }

    /** Octets occupés par les tableaux (en-têtes d'objets non comptés) : pour comparer avec la mesure réelle. */
    val approximateBytes: Long
        get() = chars.size * 2L + offsets.size * 4L + (intFrequencies?.size ?: 0) * 4L + (longFrequencies?.size ?: 0) * 8L +
            slots.size * 4L

    override val entries: Set<Map.Entry<String, Long>> = object : AbstractSet<Map.Entry<String, Long>>() {
        override val size: Int get() = this@CompactWordTable.size

        override fun iterator(): Iterator<Map.Entry<String, Long>> = object : Iterator<Map.Entry<String, Long>> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): Map.Entry<String, Long> {
                if (index >= size) throw NoSuchElementException()
                val start = offsets[index]
                val word = String(chars, start, offsets[index + 1] - start)
                return SimpleImmutableEntry(word, frequencyAt(index++))
            }
        }
    }

    private fun frequencyAt(index: Int): Long {
        val ints = intFrequencies
        return if (ints != null) ints[index].toLong() else longFrequencies!![index]
    }

    private fun indexOf(word: CharSequence): Int {
        val mask = slots.size - 1
        // Même empreinte que [hash] ; une String garde la sienne en cache (calculée une seule fois par chaîne).
        val h = if (word is String) word.hashCode() else stringHash(word)
        var slot = (h xor (h ushr 16)) and mask
        while (true) {
            val entry = slots[slot]
            if (entry == 0) return -1
            val index = entry - 1
            if (matches(index, word)) return index
            slot = (slot + 1) and mask
        }
    }

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
            if (entries is CompactWordTable) return entries
            val count = entries.size
            var totalChars = 0
            var fitsInt = true
            for ((word, frequency) in entries) {
                totalChars += word.length
                if (frequency !in 0L..Int.MAX_VALUE.toLong()) fitsInt = false
            }
            val chars = CharArray(totalChars)
            val offsets = IntArray(count + 1)
            val intFrequencies = if (fitsInt) IntArray(count) else null
            val longFrequencies = if (fitsInt) null else LongArray(count)
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
                if (intFrequencies != null) intFrequencies[index] = frequency.toInt() else longFrequencies!![index] = frequency
                var slot = hash(word) and mask
                while (slots[slot] != 0) slot = (slot + 1) and mask
                slots[slot] = index + 1
                index++
            }
            offsets[count] = position
            return CompactWordTable(chars, offsets, intFrequencies, longFrequencies, slots)
        }

        /** `String.hashCode` brassé pour l'adressage ouvert. */
        private fun hash(word: String): Int {
            val h = word.hashCode()
            return h xor (h ushr 16)
        }

        /** Même calcul que `String.hashCode`, sur n'importe quelle suite de caractères. */
        private fun stringHash(word: CharSequence): Int {
            var h = 0
            for (i in 0 until word.length) h = 31 * h + word[i].code
            return h
        }
    }
}
