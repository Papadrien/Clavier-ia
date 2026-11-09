package fr.junade.taipo.dictionary

/**
 * Mots valides trop rares pour être proposés (fréquence sous le seuil de proposition du [Dictionary]) : « sédentaire » n'est vu
 * que 50 fois dans les sous-titres d'où vient la liste française, sous le seuil de 100. Ils ne sont pas candidats de la recherche
 * par distance d'édition ; [accentRestoration] les retrouve quand le mot tapé n'en diffère que par ses accents (« sedentaire » ->
 * « sédentaire »). Sans cela, la correction allait chercher un mot courant à deux fautes (« dentaire »).
 *
 * Écartées, car trop souvent des fautes d'accent du corpus : les formes vues moins de [ACCENT_MIN_FREQUENCY] fois (« proíbido ») et
 * celles qui contiennent une lettre accentuée hors de [accentLetters] (« quíndí » en italien).
 *
 * Essayé puis écarté (banc d'autocorrection, 09/11/2026) : corriger vers un mot rare à une seule faute (« sédentaore » ->
 * « sédentaire »), ou du moins ne pas corriger vers un mot courant plus éloigné. Dans toutes les langues, un mot courant tapé avec
 * deux fautes tombe souvent à une faute d'un mot rare : 1 à 3 points de perdus sur les mots longs à deux fautes.
 *
 * Mémoire : 8 octets par mot rare accentué (empreinte de sa forme sans accent et rang dans [table]), sans aucune chaîne.
 */
internal class RareWords(
    private val table: CompactWordTable,
    private val candidateMinFrequency: Long,
    /** Lettres hors ASCII admises dans une forme de [accentedForms] : les accents courants de la langue. */
    private val accentLetters: Set<Char>,
) {

    private val foldedLetters = HashMap<Char, String>()

    /** (empreinte de la forme sans accent) shl 32 or rang, trié : les formes d'une même empreinte sont voisines. */
    private val accentIndex: LongArray = buildAccentIndex()

    /**
     * Le mot rare dont [lower] ne diffère que par les accents (« sedentaire » -> « sédentaire »), le plus fréquent ; null pour un
     * mot court ou en cas d'égalité. [keepsTypedAccents] : la forme garde les accents déjà tapés (« reflète » ne devient pas
     * « reflété ») ; sans repli, contrairement aux mots courants.
     */
    fun accentRestoration(lower: String, keepsTypedAccents: (String, String) -> Boolean): String? {
        if (lower.length < MIN_LENGTH) return null
        val forms = accentedForms(WordForms.foldAccents(lower)).filter { it.first != lower && keepsTypedAccents(lower, it.first) }
        val first = forms.firstOrNull() ?: return null
        return first.first.takeUnless { forms.size > 1 && forms[1].second == first.second }
    }

    /** Mots rares dont la forme sans accent est [folded], avec leur fréquence, du plus fréquent au moins fréquent. */
    private fun accentedForms(folded: String): List<Pair<String, Long>> {
        val hash = folded.hashCode()
        var position = lowerBound(hash.toLong() shl 32)
        val forms = ArrayList<Pair<String, Long>>(2)
        while (position < accentIndex.size && (accentIndex[position] ushr 32).toInt() == hash) {
            val index = (accentIndex[position] and INDEX_MASK).toInt()
            val word = table.wordAt(index)
            if (WordForms.foldAccents(word) == folded) forms += word to table.frequencyAt(index)
            position++
        }
        return forms.sortedByDescending { it.second }
    }

    private fun buildAccentIndex(): LongArray {
        val entries = ArrayList<Long>()
        for (index in 0 until table.size) accentKeyOf(index)?.let { entries += it }
        return entries.toLongArray().also { it.sort() }
    }

    /**
     * Entrée de [accentIndex] du mot de rang [index], ou null s'il n'y entre pas (proposable, trop rare, sans accent admis).
     * L'empreinte de sa forme sans accent (même valeur que `foldAccents(mot).hashCode()`) est calculée lettre par lettre depuis
     * [table], sans créer de chaîne : `Normalizer` sur chacun des quelque 40 000 mots rares accentués coûtait plus de 100 ms.
     */
    private fun accentKeyOf(index: Int): Long? {
        val frequency = table.frequencyAt(index)
        if (frequency >= candidateMinFrequency || frequency < ACCENT_MIN_FREQUENCY || !table.hasNonAsciiAt(index)) return null
        var hash = 0
        for (position in 0 until table.lengthAt(index)) {
            val c = table.charAt(index, position)
            if (c.code < ASCII_LIMIT) {
                hash = 31 * hash + c.code
                continue
            }
            if (c !in accentLetters) return null
            for (f in foldedLetter(c)) hash = 31 * hash + f.code
        }
        return (hash.toLong() shl 32) or index.toLong()
    }

    /** Forme sans accent d'une lettre (« é » -> « e », « œ » -> « oe »), gardée une fois calculée. */
    private fun foldedLetter(c: Char): String = foldedLetters.getOrPut(c) { WordForms.foldAccents(c.toString()) }

    /** Première position de [accentIndex] dont la valeur est au moins [key]. */
    private fun lowerBound(key: Long): Int {
        var low = 0
        var high = accentIndex.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (accentIndex[middle] < key) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        /**
         * Mots rares de [table] (fréquence sous [candidateMinFrequency]) ; null si tous les mots sont proposables. Les lettres
         * accentuées admises sont celles d'au moins [ACCENT_LETTER_MIN_WORDS] mots proposables ([candidateWords]).
         */
        fun forCandidates(table: CompactWordTable, candidateMinFrequency: Long, candidateWords: Array<String>): RareWords? {
            if (candidateMinFrequency <= 0L || candidateWords.size >= table.size) return null
            val wordsWithLetter = HashMap<Char, Int>()
            for (word in candidateWords) {
                for (c in word.toSet()) if (c.code >= ASCII_LIMIT && c.isLetter()) wordsWithLetter[c] = (wordsWithLetter[c] ?: 0) + 1
            }
            val accentLetters = wordsWithLetter.filterValues { it >= ACCENT_LETTER_MIN_WORDS }.keys
            return RareWords(table, candidateMinFrequency, accentLetters)
        }

        private const val INDEX_MASK = 0xffffffffL

        /** Un mot plus court n'est jamais remplacé par un mot rare (« insu » n'est pas « insù »). */
        private const val MIN_LENGTH = 5

        /** Une lettre accentuée est courante dans la langue si au moins autant de mots proposables la contiennent. */
        private const val ACCENT_LETTER_MIN_WORDS = 25
        private const val ASCII_LIMIT = 0x80

        /** Formes accentuées vues moins souvent : trop de fautes d'accent du corpus (« proíbido »). */
        private const val ACCENT_MIN_FREQUENCY = 20L
    }
}
