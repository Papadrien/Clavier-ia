package fr.junade.taipo.ai

/** Portion [start, endExclusive) du texte corrigé qui diffère du texte d'origine. */
data class ChangedRange(val start: Int, val endExclusive: Int)

/**
 * Compare le texte d'origine et le texte corrigé par l'IA, mot par mot, pour ne surligner que
 * ce qui a réellement été modifié. Fonction pure, testable indépendamment de l'IME.
 */
object CorrectionDiff {

    /** Au-delà, on ne calcule plus la plus longue sous-séquence commune (mémoire) : toute la zone divergente est marquée. */
    private const val MAX_LCS_CELLS = 2_000_000L

    // Un mot (avec apostrophes/traits d'union internes), une suite d'espaces, ou un caractère isolé.
    private val tokenRegex = Regex("""[\p{L}\p{M}\p{N}]+(?:['’\-][\p{L}\p{M}\p{N}]+)*|\s+|[^\s\p{L}\p{M}\p{N}]""")

    private data class Token(val text: String, val start: Int, val end: Int)

    private fun tokenize(text: String): List<Token> =
        tokenRegex.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()

    fun changedRanges(original: String, corrected: String): List<ChangedRange> {
        if (original == corrected) return emptyList()
        val a = tokenize(original)
        val b = tokenize(corrected)

        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix].text == b[prefix].text) prefix++
        var suffix = 0
        while (
            suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix].text == b[b.size - 1 - suffix].text
        ) suffix++

        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        val matched = matchedIndexesInB(aMid, bMid)

        val changed = BooleanArray(b.size)
        for (j in bMid.indices) changed[prefix + j] = j !in matched

        val ranges = mutableListOf<ChangedRange>()
        var i = 0
        while (i < b.size) {
            if (!changed[i]) {
                i++
                continue
            }
            var j = i
            while (j < b.size && changed[j]) j++
            // Retire les espaces en bordure de zone : on ne surligne que du texte visible.
            var from = i
            var to = j - 1
            while (from <= to && b[from].text.isBlank()) from++
            while (to >= from && b[to].text.isBlank()) to--
            if (from <= to) ranges += ChangedRange(b[from].start, b[to].end)
            i = j
        }
        return ranges
    }

    /** Indices (dans [b]) des tokens appartenant à une plus longue sous-séquence commune avec [a]. */
    private fun matchedIndexesInB(a: List<Token>, b: List<Token>): Set<Int> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0 || n.toLong() * m > MAX_LCS_CELLS) return emptySet()

        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[i].text == b[j].text) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }
        val matched = HashSet<Int>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].text == b[j].text -> {
                    matched += j
                    i++
                    j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> i++
                else -> j++
            }
        }
        return matched
    }
}
