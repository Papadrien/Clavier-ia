package fr.junade.taipo.ai

/**
 * Transcription IA des modèles sans entrée audio (Gemma 3, décision du 13/11/2026) : l'enregistrement passe par la dictée
 * (sherpa-onnx, modèle vocal), puis le texte brut est reformulé par le modèle de texte, comme le ferait Gemma 4 avec l'audio.
 * Logique pure, testée en JVM : découpage du texte dicté et garde-fou sur la réponse du modèle.
 *
 * Les petits modèles (Gemma 3 270M surtout) peuvent répondre à ce qui est dicté au lieu de le réécrire, inventer, ou couper.
 * [accept] écarte ces réponses : le texte brut de la dictée est alors inséré tel quel, l'utilisateur obtient toujours son texte.
 */
object DictationRewrite {

    /** Nombre de mots au plus par morceau envoyé au modèle : les petits modèles décrochent sur un long texte. */
    const val MAX_WORDS_PER_CHUNK = 120

    /** En dessous, on ne juge pas les longueurs : une phrase très courte peut beaucoup changer. */
    private const val MIN_LENGTH_TO_CHECK = 30

    /** Une reformulation enlève des hésitations et des répétitions, mais ne réduit pas le texte de moitié. */
    private const val MIN_LENGTH_RATIO = 0.5

    /** Ni ne le double (le modèle a ajouté une réponse, une explication ou une suite). */
    private const val MAX_LENGTH_RATIO = 2.0

    /** Part minimale des mots porteurs de sens du texte dicté qu'on retrouve dans la réponse. */
    private const val MIN_SHARED_WORDS = 0.4

    /** Longueur minimale d'un mot porteur de sens (les mots courts sont trop souvent des mots outils). */
    private const val MEANINGFUL_WORD_LENGTH = 4

    private val SENTENCE_END = Regex("""(?<=[.!?…])\s+""")
    private val WHITESPACE = Regex("""\s+""")
    private val WORD = Regex("""[\p{L}\p{N}]+""")

    /**
     * Découpe [text] en morceaux d'au plus [maxWords] mots, aux fins de phrase quand il y en a ; une phrase plus longue
     * (dictée sans ponctuation) est coupée entre deux mots.
     */
    fun chunks(text: String, maxWords: Int = MAX_WORDS_PER_CHUNK): List<String> {
        val result = mutableListOf<String>()
        val current = mutableListOf<String>()
        fun flush() {
            if (current.isNotEmpty()) result += current.joinToString(" ")
            current.clear()
        }
        for (sentence in text.trim().split(SENTENCE_END)) {
            val words = sentence.split(WHITESPACE).filter { it.isNotEmpty() }
            if (current.size + words.size > maxWords) flush()
            words.chunked(maxWords).forEach { part ->
                if (current.size + part.size > maxWords) flush()
                current += part
            }
        }
        flush()
        return result
    }

    /**
     * Renvoie [rewritten] (sans espaces en bordure) s'il est plausible comme reformulation de [raw], sinon null : le texte brut
     * doit alors être gardé. Refusé : réponse vide, beaucoup plus courte ou plus longue, ou qui ne reprend presque aucun mot
     * porteur de sens du texte dicté (le modèle a répondu à la question dictée au lieu de la réécrire).
     */
    fun accept(raw: String, rewritten: String): String? {
        val candidate = rewritten.trim()
        if (candidate.isEmpty()) return null
        val original = raw.trim()
        if (original.length < MIN_LENGTH_TO_CHECK) return candidate
        if (candidate.length < original.length * MIN_LENGTH_RATIO) return null
        if (candidate.length > original.length * MAX_LENGTH_RATIO) return null
        val rawWords = meaningfulWords(original)
        if (rawWords.isEmpty()) return candidate
        val kept = meaningfulWords(candidate)
        val shared = rawWords.count { it in kept }.toDouble() / rawWords.size
        return if (shared >= MIN_SHARED_WORDS) candidate else null
    }

    private fun meaningfulWords(text: String): Set<String> =
        WORD.findAll(text.lowercase()).map { it.value }.filter { it.length >= MEANINGFUL_WORD_LENGTH }.toSet()
}
