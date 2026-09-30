package fr.junade.taipo.ai

/** Portion [start, endExclusive) d'un texte. */
data class TextBlock(val start: Int, val endExclusive: Int)

/** Découpe un texte en phrases. Fonction pure, testable indépendamment de l'IME. */
object SentenceSplitter {

    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'»”’)]"

    /**
     * Phrases du texte, sans les espaces en bordure. Une phrase se termine par . ! ? … (suivi
     * d'un espace ou de la fin du texte, pour ne pas couper « 3.14 » ni « site.fr »), ou par
     * un retour à la ligne.
     */
    fun split(text: String): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        var start = -1

        fun close(endExclusive: Int) {
            if (start >= 0) {
                var end = endExclusive
                while (end > start && text[end - 1].isWhitespace()) end--
                if (end > start) result += TextBlock(start, end)
            }
            start = -1
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                close(i)
                i++
            } else if (c.isWhitespace()) {
                i++
            } else {
                if (start < 0) start = i
                var next = i + 1
                if (c in TERMINATORS) {
                    var j = i + 1
                    while (j < text.length && (text[j] in TERMINATORS || text[j] in CLOSERS)) j++
                    if (j >= text.length || text[j].isWhitespace()) {
                        close(j)
                        next = j
                    }
                }
                i = next
            }
        }
        close(text.length)
        return result
    }
}

/**
 * Mémoire (en RAM uniquement, jamais sur disque) des phrases déjà corrigées : une phrase
 * identique à une phrase déjà corrigée n'est pas renvoyée à l'IA.
 */
class CorrectedSentenceMemory(private val capacity: Int = 1000) {

    private val sentences = LinkedHashSet<String>()

    private fun normalize(sentence: String): String = sentence.trim().replace(Regex("\\s+"), " ")

    @Synchronized
    fun contains(sentence: String): Boolean = normalize(sentence) in sentences

    /** Retient chaque phrase de [text] comme déjà corrigée. */
    @Synchronized
    fun remember(text: String) {
        SentenceSplitter.split(text).forEach { block ->
            val key = normalize(text.substring(block.start, block.endExclusive))
            sentences.remove(key)
            sentences.add(key)
        }
        while (sentences.size > capacity) {
            val oldest = sentences.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() = sentences.clear()
}

/** Décide quelles parties d'un texte doivent être envoyées à l'IA. */
object CorrectionPlanner {

    /**
     * Zones à corriger : les phrases pas encore corrigées (nouvelles, ou dont un mot a été
     * modifié ou ajouté). Les phrases consécutives d'un même paragraphe sont regroupées en
     * une seule zone, pour un seul appel au modèle (une zone ne franchit jamais un retour à
     * la ligne) ; les phrases déjà corrigées ne sont pas envoyées.
     */
    fun blocksToCorrect(text: String, isAlreadyCorrected: (String) -> Boolean): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var blockStart = -1
        var blockEnd = -1
        for (sentence in SentenceSplitter.split(text)) {
            if (isAlreadyCorrected(text.substring(sentence.start, sentence.endExclusive))) {
                if (blockStart >= 0) {
                    blocks += TextBlock(blockStart, blockEnd)
                    blockStart = -1
                }
            } else {
                // Un retour à la ligne (nouveau paragraphe) ferme la zone en cours : chaque
                // paragraphe fait l'objet d'un appel distinct au modèle. Envoyer plusieurs
                // paragraphes d'un coup faisait parfois répondre le modèle avec le premier
                // seulement, et les suivants étaient alors effacés du champ.
                if (blockStart >= 0 && text.substring(blockEnd, sentence.start).contains('\n')) {
                    blocks += TextBlock(blockStart, blockEnd)
                    blockStart = -1
                }
                if (blockStart < 0) blockStart = sentence.start
                blockEnd = sentence.endExclusive
            }
        }
        if (blockStart >= 0) blocks += TextBlock(blockStart, blockEnd)
        return blocks
    }
}
