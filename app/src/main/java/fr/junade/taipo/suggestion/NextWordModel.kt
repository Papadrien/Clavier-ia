package fr.junade.taipo.suggestion

import java.util.Locale

/**
 * Prédiction du mot (ou de l'emoji) suivant d'après les habitudes d'écriture de l'utilisateur.
 *
 * Le modèle ne contient rien au départ : il apprend, sur l'appareil, quels mots (ou emoji) suivent
 * quels autres dans ce que l'utilisateur tape. Il retient pour cela des comptes de paires
 * « mot → mot suivant » (bigrammes) et de triplets « deux mots → mot suivant » (trigrammes) :
 * après « je suis », puis « arrivé », il propose ce qui a le plus souvent suivi « suis arrivé », puis
 * ce qui a suivi « arrivé » tout court. Le début d'une phrase compte comme un contexte (« ^ »),
 * ce qui permet de proposer des débuts de phrase habituels.
 *
 * Rien n'est mémorisé qui ressemble à un nombre (numéros, codes) : un chiffre coupe le contexte.
 * Les textes eux-mêmes ne sont jamais conservés, seulement des comptes de paires de mots.
 *
 * Logique pure (aucune dépendance Android), testée en JVM. Les méthodes publiques sont
 * synchronisées : le modèle est lu et écrit depuis le thread principal, et sauvegardé ou fusionné
 * depuis un autre.
 */
class NextWordModel(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /** Mots (au plus [maxWords]) et emoji proposés à la suite du texte. */
    data class Prediction(val words: List<String>, val emoji: String?) {
        val isEmpty: Boolean get() = words.isEmpty() && emoji == null

        companion object {
            val NONE = Prediction(emptyList(), null)
        }
    }

    // contexte (1 mot) -> suivant -> nombre d'occurrences ; idem pour le contexte de 2 mots (« a b »).
    private val bigrams = HashMap<String, HashMap<String, Int>>()
    private val trigrams = HashMap<String, HashMap<String, Int>>()
    private var entryCount = 0

    /** Vrai si rien n'a encore été appris. */
    @Synchronized
    fun isEmpty(): Boolean = entryCount == 0

    @Synchronized
    fun size(): Int = entryCount

    @Synchronized
    fun clear() {
        bigrams.clear()
        trigrams.clear()
        entryCount = 0
    }

    /**
     * Apprend la dernière transition de [textBeforeCursor] : le dernier mot (ou emoji) terminé, en
     * fonction des un ou deux éléments qui le précèdent. [truncated] indique que le texte est une
     * fenêtre coupée au début de ce que l'utilisateur a écrit (le premier mot peut être incomplet).
     * Retourne vrai si quelque chose a été appris.
     */
    @Synchronized
    fun learn(textBeforeCursor: String, truncated: Boolean): Boolean {
        val tokens = tokenize(textBeforeCursor).toMutableList()
        if (truncated && tokens.isNotEmpty()) tokens.removeAt(0)
        // Les ponctuations de fin de phrase qui terminent le texte ne sont pas un mot à apprendre.
        while (tokens.isNotEmpty() && tokens.last() == SENTENCE_START) tokens.removeAt(tokens.size - 1)
        if (tokens.isEmpty()) return false
        val last = tokens.last()
        if (last == BARRIER) return false

        val previous = contextBefore(tokens, tokens.size - 1, truncated) ?: return false
        increment(bigrams, previous.first, last)
        previous.second?.let { increment(trigrams, "$it ${previous.first}", last) }
        if (entryCount > maxEntries) prune()
        return true
    }

    /**
     * Mots et emoji les plus probables après [textBeforeCursor], qui doit se terminer par une espace
     * (le mot suivant, pas la complétion du mot en cours). Les mots sont classés d'après les
     * triplets, plus précis, puis les paires ; l'emoji doit avoir déjà suivi ce contexte au moins
     * [MIN_EMOJI_COUNT] fois pour être proposé.
     */
    @Synchronized
    fun predict(textBeforeCursor: String, truncated: Boolean, maxWords: Int = 3): Prediction {
        if (entryCount == 0 || !textBeforeCursor.endsWith(' ')) return Prediction.NONE
        val tokens = tokenize(textBeforeCursor).toMutableList()
        if (truncated && tokens.isNotEmpty()) tokens.removeAt(0)
        if (tokens.isEmpty()) return Prediction.NONE
        val last = tokens.last()
        if (last == BARRIER) return Prediction.NONE

        // Contexte : le dernier élément, et celui d'avant s'il y en a un.
        val sentenceStart = last == SENTENCE_START
        val context1: String
        val context2: String?
        if (sentenceStart) {
            context1 = SENTENCE_START
            context2 = null
        } else {
            context1 = last
            context2 = contextBefore(tokens, tokens.size - 1, truncated)?.first
        }

        val scores = HashMap<String, Int>()
        bigrams[context1]?.forEach { (next, count) -> scores.merge(next, count, Int::plus) }
        if (context2 != null) {
            trigrams["$context2 $context1"]?.forEach { (next, count) ->
                scores.merge(next, count * TRIGRAM_WEIGHT, Int::plus)
            }
        }
        if (scores.isEmpty()) return Prediction.NONE

        val ranked = scores.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val words = ranked.asSequence()
            .map { it.key }
            .filter { !isEmoji(it) }
            .take(maxWords)
            .map { if (sentenceStart) capitalizeFirst(it) else it }
            .toList()
        val emoji = ranked.firstOrNull { isEmoji(it.key) && (bigrams[context1]?.get(it.key) ?: 0) >= MIN_EMOJI_COUNT }?.key
        return Prediction(words, emoji)
    }

    /** Ajoute les comptes de [other] à ceux de ce modèle (chargement du disque après quelques saisies). */
    fun mergeFrom(other: NextWordModel) {
        if (other === this) return
        val bigramSnapshot: List<Triple<String, String, Int>>
        val trigramSnapshot: List<Triple<String, String, Int>>
        synchronized(other) {
            bigramSnapshot = other.entries(other.bigrams)
            trigramSnapshot = other.entries(other.trigrams)
        }
        synchronized(this) {
            bigramSnapshot.forEach { (key, next, count) -> add(bigrams, key, next, count) }
            trigramSnapshot.forEach { (key, next, count) -> add(trigrams, key, next, count) }
            if (entryCount > maxEntries) prune()
        }
    }

    /** Texte de sauvegarde : une ligne `B|T <tab> contexte <tab> suivant <tab> compte` par entrée. */
    @Synchronized
    fun serialize(): String = buildString {
        for ((key, next, count) in entries(bigrams)) append("B\t").append(key).append('\t').append(next).append('\t').append(count).append('\n')
        for ((key, next, count) in entries(trigrams)) append("T\t").append(key).append('\t').append(next).append('\t').append(count).append('\n')
    }

    // ------------------------------------------------------------------

    private fun entries(map: HashMap<String, HashMap<String, Int>>): List<Triple<String, String, Int>> {
        val result = ArrayList<Triple<String, String, Int>>(entryCount)
        for ((key, nexts) in map) for ((next, count) in nexts) result.add(Triple(key, next, count))
        return result
    }

    private fun increment(map: HashMap<String, HashMap<String, Int>>, key: String, next: String) = add(map, key, next, 1)

    private fun add(map: HashMap<String, HashMap<String, Int>>, key: String, next: String, count: Int) {
        val nexts = map.getOrPut(key) { HashMap() }
        val existing = nexts[next]
        if (existing == null) entryCount++
        nexts[next] = (existing ?: 0) + count
    }

    /**
     * Contexte qui précède le jeton d'indice [index] : (élément précédent, élément encore avant ou
     * null). Au début d'un texte complet (non tronqué), l'élément précédent est le début de phrase.
     * Null si le contexte est coupé par un chiffre ou inexistant.
     */
    private fun contextBefore(tokens: List<String>, index: Int, truncated: Boolean): Pair<String, String?>? {
        val first: String = when {
            index >= 1 -> tokens[index - 1]
            !truncated -> SENTENCE_START
            else -> return null
        }
        if (first == BARRIER) return null
        val second: String? = if (first == SENTENCE_START) {
            null
        } else when {
            index >= 2 -> tokens[index - 2].takeIf { it != BARRIER }
            index == 1 && !truncated -> SENTENCE_START
            else -> null
        }
        return first to second
    }

    /** Supprime les entrées vues une seule fois, puis, si ce n'est pas assez, divise tous les comptes par deux. */
    private fun prune() {
        dropBelow(2)
        if (entryCount > maxEntries) {
            for (map in listOf(bigrams, trigrams)) for (nexts in map.values) nexts.replaceAll { _, count -> count / 2 }
            dropBelow(1)
        }
    }

    private fun dropBelow(minCount: Int) {
        for (map in listOf(bigrams, trigrams)) {
            val iterator = map.entries.iterator()
            while (iterator.hasNext()) {
                val nexts = iterator.next().value
                val inner = nexts.entries.iterator()
                while (inner.hasNext()) {
                    if (inner.next().value < minCount) {
                        inner.remove()
                        entryCount--
                    }
                }
                if (nexts.isEmpty()) iterator.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 40_000
        const val MIN_EMOJI_COUNT = 2
        private const val TRIGRAM_WEIGHT = 4
        private const val MAX_WORD_LENGTH = 30

        /** Jeton « début de phrase » (point, point d'exclamation ou d'interrogation, retour à la ligne). */
        const val SENTENCE_START = "^"

        /** Jeton qui coupe le contexte (un nombre : numéro, code...), jamais appris ni proposé. */
        const val BARRIER = "#"

        private const val ZWJ = 0x200D
        private const val VS15 = 0xFE0E
        private const val VS16 = 0xFE0F
        private const val KEYCAP = 0x20E3

        /** Relit un texte produit par [serialize] ; les lignes illisibles sont ignorées. */
        fun parse(text: String, maxEntries: Int = DEFAULT_MAX_ENTRIES): NextWordModel {
            val model = NextWordModel(maxEntries)
            for (line in text.lineSequence()) {
                val parts = line.split('\t')
                if (parts.size != 4) continue
                val count = parts[3].toIntOrNull()?.takeIf { it > 0 } ?: continue
                when (parts[0]) {
                    "B" -> model.add(model.bigrams, parts[1], parts[2], count)
                    "T" -> model.add(model.trigrams, parts[1], parts[2], count)
                }
            }
            return model
        }

        /** Vrai pour un jeton emoji (la première lettre d'un mot n'est jamais un emoji). */
        fun isEmoji(token: String): Boolean = token.isNotEmpty() && isEmojiBase(token.codePointAt(0))

        private fun isEmojiBase(cp: Int): Boolean =
            cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp == 0xA9 || cp == 0xAE || cp == 0x203C || cp == 0x2049 ||
                cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

        private fun isEmojiSuffix(cp: Int): Boolean =
            cp == VS15 || cp == VS16 || cp == KEYCAP || cp in 0x1F3FB..0x1F3FF

        private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

        private fun isWordPart(cp: Int): Boolean =
            Character.isLetterOrDigit(cp) || cp == '\''.code || cp == 0x2019 || cp == '-'.code

        private fun capitalizeFirst(word: String): String =
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

        /**
         * Découpe un texte en jetons : mots en minuscules (apostrophes et traits d'union gardés à
         * l'intérieur), emoji entiers, [SENTENCE_START] pour une fin de phrase ou un retour à la
         * ligne, [BARRIER] pour un mot qui contient un chiffre ou qui est trop long. Espaces et
         * autres ponctuations (virgule, parenthèses, guillemets...) sont ignorés.
         */
        fun tokenize(text: String): List<String> {
            val tokens = ArrayList<String>()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val width = Character.charCount(cp)
                when {
                    // Un mot commence par une lettre ou un chiffre (une apostrophe ou un trait d'union isolés sont ignorés).
                    Character.isLetterOrDigit(cp) -> {
                        val start = i
                        var end = i
                        var hasDigit = false
                        while (end < text.length) {
                            val c = text.codePointAt(end)
                            if (!isWordPart(c)) break
                            if (Character.isDigit(c)) hasDigit = true
                            end += Character.charCount(c)
                        }
                        val raw = text.substring(start, end).replace('\u2019', '\'').trim('\'', '-')
                        i = end
                        if (raw.isEmpty()) continue
                        if (hasDigit || raw.length > MAX_WORD_LENGTH) {
                            if (tokens.lastOrNull() != BARRIER) tokens.add(BARRIER)
                        } else {
                            tokens.add(raw.lowercase(Locale.ROOT))
                        }
                    }

                    cp == '.'.code || cp == '!'.code || cp == '?'.code || cp == 0x2026 || cp == '\n'.code -> {
                        if (tokens.lastOrNull() != SENTENCE_START) tokens.add(SENTENCE_START)
                        i += width
                    }

                    isEmojiBase(cp) -> {
                        var end = i + width
                        var regionalCount = if (isRegionalIndicator(cp)) 1 else 0
                        while (end < text.length) {
                            val next = text.codePointAt(end)
                            val nextWidth = Character.charCount(next)
                            when {
                                isEmojiSuffix(next) -> end += nextWidth
                                next == ZWJ && end + nextWidth < text.length &&
                                    isEmojiBase(text.codePointAt(end + nextWidth)) -> {
                                    end += nextWidth + Character.charCount(text.codePointAt(end + nextWidth))
                                }
                                // Drapeau : deux indicateurs régionaux ; un troisième commence un autre emoji.
                                regionalCount == 1 && isRegionalIndicator(next) -> {
                                    end += nextWidth
                                    regionalCount = 2
                                }
                                else -> break
                            }
                        }
                        tokens.add(text.substring(i, end))
                        i = end
                    }

                    else -> i += width
                }
            }
            return tokens
        }
    }
}
