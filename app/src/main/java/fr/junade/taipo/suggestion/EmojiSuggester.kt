package fr.junade.taipo.suggestion

import fr.junade.taipo.KeyboardLanguage
import java.text.Normalizer
import java.util.Locale

/**
 * Story 1.16 : emoji proposé dans le 4e emplacement de la barre de suggestions, d'après le dernier
 * mot saisi (« pizza » → 🍕). Comme le dictionnaire de la story 1.3, c'est une liste fixe locale :
 * pas d'IA, pas d'apprentissage. Logique pure (sans Android), testée en JVM.
 *
 * Les mots sont comparés sans accent ni majuscule ; un pluriel en « s » est reconnu. Un seul emoji
 * est proposé (un seul emplacement) : celui de la première ligne du fichier qui contient le mot.
 */
class EmojiSuggester(
    private val french: Map<String, String>,
    private val english: Map<String, String>,
) {

    /**
     * Emoji à proposer pour le texte situé avant le curseur, ou null. Le dernier mot doit se terminer
     * à la fin du texte ou avant un seul espace : après une ponctuation, un retour à la ligne, un
     * emoji ou deux espaces, plus rien n'est proposé.
     */
    fun suggest(textBeforeCursor: String, language: KeyboardLanguage): String? {
        val word = lastWord(textBeforeCursor) ?: return null
        val keywords = when (language) {
            KeyboardLanguage.FR -> french
            KeyboardLanguage.EN -> english
        }
        val key = normalize(word)
        keywords[key]?.let { return it }
        if (key.length > MIN_PLURAL_LENGTH && key.endsWith('s')) return keywords[key.dropLast(1)]
        return null
    }

    companion object {
        private const val MAX_WORD_LENGTH = 30
        private const val MIN_PLURAL_LENGTH = 3
        private val whitespace = Regex("\\s+")
        private val accents = Regex("\\p{Mn}+")

        /** Dernier mot (lettres et chiffres) qui finit le texte, avec au plus un espace après lui. */
        fun lastWord(text: String): String? {
            var end = text.length
            if (end > 0 && text[end - 1] == ' ') end--
            var start = end
            while (start > 0 && text[start - 1].isLetterOrDigit()) start--
            val length = end - start
            if (length == 0 || length > MAX_WORD_LENGTH) return null
            return text.substring(start, end)
        }

        /** Minuscules sans accents : « Café » et « cafe » sont le même mot. */
        fun normalize(word: String): String =
            Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(accents, "")

        /**
         * Lit un fichier de mots-clés : une ligne = un emoji suivi des mots qui le déclenchent. Les
         * lignes vides et celles qui commencent par « # » (commentaire) sont ignorées. Si un mot
         * figure sur plusieurs lignes, la première l'emporte.
         */
        fun parse(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line == "#" || line.startsWith("# ")) continue
                val tokens = line.split(whitespace)
                val emoji = tokens.first()
                for (word in tokens.drop(1)) result.putIfAbsent(normalize(word), emoji)
            }
            return result
        }
    }
}
