package fr.junade.taipo.dictionary

import java.text.Normalizer

/**
 * Formes d'un mot (accents, casse) et lecture des listes de fréquences, extraites de [Dictionary] (audit 2.1, étape 6).
 * Fonctions pures, sans état : le code est celui d'avant l'extraction, `Dictionary` les expose toujours par des relais.
 */
object WordForms {

    /**
     * Mot sans accents ni cédille (« durée » -> « duree », « ça » -> « ca »), œ et æ développés.
     * Attend une chaîne en minuscules ; renvoie la même instance si elle est déjà en ASCII.
     */
    fun foldAccents(word: String): String {
        if (word.all { it.code < 128 }) return word
        val decomposed = Normalizer.normalize(word.replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) builder.append(c)
        }
        return builder.toString()
    }

    /**
     * Lit des lignes « mot fréquence » (séparées par un espace). Une ligne sans fréquence
     * valide (mot seul, ou fréquence non numérique) compte pour 1 ; les lignes vides sont ignorées.
     * Les mots de fréquence inférieure à [minFrequency] sont écartés dès la lecture (pas de pic mémoire).
     */
    fun parseFrequencyLines(lines: Sequence<String>, minFrequency: Long = 0L): Map<String, Long> {
        val result = HashMap<String, Long>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val separator = trimmed.lastIndexOf(' ')
            val word: String
            val count: Long
            if (separator > 0) {
                val parsed = trimmed.substring(separator + 1).toLongOrNull()
                if (parsed != null) {
                    word = trimmed.substring(0, separator).trim()
                    count = parsed
                } else {
                    word = trimmed
                    count = 1L
                }
            } else {
                word = trimmed
                count = 1L
            }
            if (word.isNotEmpty() && count >= minFrequency) result[word] = maxOf(result[word] ?: 0L, count)
        }
        return result
    }

    /** Réapplique une majuscule initiale si [original] en avait une (ex. "Bnojour" -> "Bonjour"). */
    fun applyOriginalCasing(original: String, correction: String): String {
        if (original.isNotEmpty() && original[0].isUpperCase()) {
            return correction.replaceFirstChar { it.uppercaseChar() }
        }
        return correction
    }
}
