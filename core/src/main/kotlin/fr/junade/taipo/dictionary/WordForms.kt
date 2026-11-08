package fr.junade.taipo.dictionary

import java.text.Normalizer

/**
 * Formes d'un mot (accents, casse) et lecture des listes de fréquences, extraites de [Dictionary] (audit 2.1, étape 6).
 * Fonctions pures, sans état : le code est celui d'avant l'extraction, `Dictionary` les expose toujours par des relais.
 */
object WordForms {

    /**
     * Mot sans accents ni cédille (« durée » -> « duree », « ça » -> « ca »), œ et æ développés, ß écrit « ss »
     * (« straße » -> « strasse », « für » -> « fur » : les umlauts perdent leur tréma, comme au clavier sans appui long).
     * Attend une chaîne en minuscules ; renvoie la même instance si elle est déjà en ASCII.
     */
    fun foldAccents(word: String): String {
        if (word.all { it.code < 128 }) return word
        val decomposed = Normalizer.normalize(word.replace("œ", "oe").replace("æ", "ae").replace("ß", "ss"), Normalizer.Form.NFD)
        val builder = StringBuilder(decomposed.length)
        for (c in decomposed) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) builder.append(c)
        }
        return builder.toString()
    }

    /**
     * Transcription allemande d'un mot : ä -> ae, ö -> oe, ü -> ue, ß -> ss (« fuer » pour « für », « strasse » pour « straße »).
     * Attend une chaîne en minuscules ; renvoie la même instance s'il n'y a rien à transcrire.
     */
    fun transliterateGerman(word: String): String {
        if (word.none { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' }) return word
        val builder = StringBuilder(word.length + 2)
        for (c in word) {
            when (c) {
                'ä' -> builder.append("ae")
                'ö' -> builder.append("oe")
                'ü' -> builder.append("ue")
                'ß' -> builder.append("ss")
                else -> builder.append(c)
            }
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

    /**
     * Même lecture que [parseFrequencyLines], directement sur les octets UTF-8 du fichier (revue de code, performance). Le
     * fichier français compte environ 740 000 lignes dont 270 000 sont gardées : la fréquence est lue sur les octets d'abord,
     * et une chaîne n'est créée que pour les mots retenus (au lieu de deux par ligne lue). La table est dimensionnée d'avance.
     *
     * Différence assumée : seuls les espaces ASCII (et tabulations, retours chariot) entourant une ligne sont ignorés avant de
     * chercher la fréquence ; le mot lui-même est ensuite nettoyé comme dans [parseFrequencyLines]. Les listes de l'application
     * sont générées par nos outils : aucune n'a d'espace Unicode en bout de ligne (vérifié par `WordFormsParseTest`).
     */
    fun parseFrequencyBytes(bytes: ByteArray, minFrequency: Long = 0L, expectedEntries: Int = 0): Map<String, Long> {
        val result = HashMap<String, Long>(if (expectedEntries > 0) (expectedEntries / LOAD_FACTOR).toInt() + 1 else DEFAULT_CAPACITY)
        var lineStart = 0
        val size = bytes.size
        while (lineStart < size) {
            var lineEnd = lineStart
            while (lineEnd < size && bytes[lineEnd] != NEWLINE) lineEnd++
            parseFrequencyLine(bytes, lineStart, lineEnd, minFrequency, result)
            lineStart = lineEnd + 1
        }
        return result
    }

    private fun parseFrequencyLine(bytes: ByteArray, from: Int, to: Int, minFrequency: Long, into: HashMap<String, Long>) {
        var start = from
        var end = to
        while (start < end && isAsciiBlank(bytes[start])) start++
        while (end > start && isAsciiBlank(bytes[end - 1])) end--
        if (start == end) return
        var separator = -1
        for (i in end - 1 downTo start + 1) {
            if (bytes[i] == SPACE) {
                separator = i
                break
            }
        }
        val parsed = if (separator > start) parseLong(bytes, separator + 1, end) else NOT_A_NUMBER
        val count: Long
        val wordEnd: Int
        if (parsed != NOT_A_NUMBER) {
            count = parsed
            wordEnd = separator
        } else {
            count = 1L
            wordEnd = end
        }
        if (count < minFrequency) return // écarté sans créer de chaîne
        val word = String(bytes, start, wordEnd - start, Charsets.UTF_8).trim()
        if (word.isEmpty()) return
        into[word] = maxOf(into[word] ?: 0L, count) // même règle que parseFrequencyLines (fréquence négative ramenée à 0)
    }

    /** Entier décimal (signe facultatif) entre [from] et [to], comme `toLongOrNull` ; [NOT_A_NUMBER] sinon. */
    private fun parseLong(bytes: ByteArray, from: Int, to: Int): Long {
        if (from >= to) return NOT_A_NUMBER
        var i = from
        val negative = bytes[i] == MINUS
        if (negative || bytes[i] == PLUS) i++
        if (i >= to) return NOT_A_NUMBER
        var value = 0L
        while (i < to) {
            val digit = bytes[i] - ZERO
            if (digit !in 0..9) return NOT_A_NUMBER
            if (value > (Long.MAX_VALUE - digit) / 10) return NOT_A_NUMBER // dépassement : toLongOrNull renverrait null
            value = value * 10 + digit
            i++
        }
        return if (negative) -value else value
    }

    private fun isAsciiBlank(b: Byte): Boolean = b == SPACE || b == TAB || b == CARRIAGE_RETURN || b == NEWLINE

    private const val NEWLINE: Byte = 0x0A
    private const val CARRIAGE_RETURN: Byte = 0x0D
    private const val TAB: Byte = 0x09
    private const val SPACE: Byte = 0x20
    private const val MINUS: Byte = 0x2D
    private const val PLUS: Byte = 0x2B
    private const val ZERO: Byte = 0x30
    private const val NOT_A_NUMBER = Long.MIN_VALUE
    private const val LOAD_FACTOR = 0.75f
    private const val DEFAULT_CAPACITY = 16

    /** Réapplique une majuscule initiale si [original] en avait une (ex. "Bnojour" -> "Bonjour"). */
    fun applyOriginalCasing(original: String, correction: String): String {
        if (original.isNotEmpty() && original[0].isUpperCase()) {
            return correction.replaceFirstChar { it.uppercaseChar() }
        }
        return correction
    }
}
