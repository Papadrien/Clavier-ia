package fr.junade.taipo.voice

import fr.junade.taipo.KeyboardLanguage

/**
 * Dictée : les nombres dits en toutes lettres par le modèle vocal sont écrits en chiffres (« vingt-trois » -> « 23 »,
 * « deux mille vingt-six » -> « 2026 », « trois virgule cinq » -> « 3,5 », « vingt pour cent » -> « 20 % »).
 *
 * Français et anglais ici, avec leurs règles propres ; les treize autres langues du clavier passent par [CompoundNumberRules]
 * (lexiques dans [NumberLexicons]). En français, les nombres s'écrivent avec ou sans traits d'union (« vingt et un »,
 * « vingt-et-un », « quatre-vingt-dix-sept », « soixante et onze »), y compris en belge et en suisse (« septante »,
 * « huitante », « nonante »). Un nombre s'arrête à toute ponctuation.
 *
 * Mots ambigus, laissés en lettres quand ils sont seuls : « un », « une » et « one » (articles : « un chat », « one of
 * them ») ; « neuf » (adjectif : « un vélo neuf ») sauf suivi d'un mot qui n'est pas une liaison (« neuf ans »). Les
 * ordinaux (« premier », « deuxième ») restent en lettres.
 *
 * Mise en forme : à partir de 10 000, les milliers sont séparés par une espace fine insécable en français (« 50 000 ») et une
 * virgule en anglais (« 50,000 ») ; en dessous, aucun séparateur (« 2026 »). Logique pure, testée en JVM (`SpokenNumbersTest`).
 */
object SpokenNumbers {

    /** [text] avec ses nombres en chiffres, pour la dictée en [language]. */
    fun toDigits(text: String, language: KeyboardLanguage): String {
        val rules = when (language) {
            KeyboardLanguage.FR -> French
            KeyboardLanguage.EN -> English
            else -> NumberLexicons.rules(language)
        }
        return Converter(text, rules).convert()
    }

    /** Une langue : son vocabulaire et sa grammaire des nombres. */
    internal abstract class Rules {
        /** [part] (un mot ou une partie de mot à trait d'union, en minuscules) peut appartenir à un nombre. */
        abstract fun accepts(part: String): Boolean
        abstract val decimalWords: Set<String>
        abstract val decimalSeparator: String
        abstract val groupSeparator: String

        /** Mots qui suivent un nombre pour former un pourcentage (« pour cent », « percent »). */
        abstract val percentWords: List<List<String>>
        abstract val percentSign: String

        /** Valeur des mots [tokens] s'ils forment exactement un nombre, sinon null. */
        abstract fun parse(tokens: List<String>): Long?

        /** Faux si [word], seul, doit rester en lettres ([next] : le mot suivant après une seule espace, ou null). */
        abstract fun convertsAlone(word: String, next: String?): Boolean
    }

    private class Word(val start: Int, val end: Int, val lower: String, val parts: List<String>?)

    private class Converter(private val text: String, private val rules: Rules) {

        private val words: List<Word> = WORD.findAll(text).map { match ->
            val lower = match.value.lowercase()
            val parts = lower.split('-')
            Word(match.range.first, match.range.last + 1, lower, parts.takeIf { list -> list.all(rules::accepts) })
        }.toList()

        private val out = StringBuilder(text.length)
        private var copied = 0

        fun convert(): String {
            var i = 0
            while (i < words.size) {
                i = convertAt(i) ?: (i + 1)
            }
            out.append(text, copied, text.length)
            return out.toString()
        }

        /** Remplace le nombre qui commence au mot [i] ; renvoie l'indice du mot qui le suit, ou null s'il n'y en a pas. */
        private fun convertAt(i: Int): Int? {
            val number = longestNumber(i) ?: return null
            var end = number.second
            var written = format(number.first)
            // Partie décimale : « trois virgule cinq », « trois virgule zéro cinq » (chiffres dits un par un).
            if (end < words.size && words[end].lower in rules.decimalWords && joined(end - 1, end)) {
                val decimals = StringBuilder()
                var j = end + 1
                while (j < words.size && joined(j - 1, j)) {
                    val part = longestNumber(j, alone = true) ?: break
                    decimals.append(part.first)
                    j = part.second
                }
                if (decimals.isNotEmpty()) {
                    written += rules.decimalSeparator + decimals
                    end = j
                }
            }
            rules.percentWords.firstOrNull { follows(end, it) }?.let { percent ->
                written += rules.percentSign
                end += percent.size
            }
            out.append(text, copied, words[i].start).append(written)
            copied = words[end - 1].end
            return end
        }

        /** Les mots [expected] suivent, chacun après une seule suite d'espaces, à partir du mot [at]. */
        private fun follows(at: Int, expected: List<String>): Boolean =
            at + expected.size <= words.size &&
                expected.indices.all { k -> words[at + k].lower == expected[k] && joined(at + k - 1, at + k) }

        /** Le mot [next] prolonge le nombre qui commence au mot [first] : un mot de nombre, collé par des espaces. */
        private fun extends(first: Int, next: Int): Boolean =
            next < words.size && next - first < MAX_WORDS && words[next].parts != null && joined(next - 1, next)

        /**
         * Le plus long nombre qui commence au mot [i] : sa valeur et l'indice du mot qui le suit. Un mot seul ambigu (« un »)
         * n'est pas un nombre, sauf [alone] (partie décimale, où « un » est bien un chiffre).
         */
        private fun longestNumber(i: Int, alone: Boolean = false): Pair<Long, Int>? {
            if (words[i].parts == null) return null
            var last = i
            while (extends(i, last + 1)) last++
            for (end in last + 1 downTo i + 1) {
                val tokens = (i until end).flatMap { words[it].parts!! }
                val value = rules.parse(tokens) ?: continue
                if (end == i + 1 && !alone && tokens.size == 1) {
                    val next = words.getOrNull(end)?.takeIf { joined(end - 1, end) }?.lower
                    if (!rules.convertsAlone(tokens[0], next)) return null
                }
                return value to end
            }
            return null
        }

        /** Les mots [a] et [b] (consécutifs) ne sont séparés que par des espaces : ils peuvent appartenir au même nombre. */
        private fun joined(a: Int, b: Int): Boolean {
            if (a < 0 || b >= words.size) return false
            val from = words[a].end
            val to = words[b].start
            if (to <= from) return false
            for (k in from until to) if (text[k] != ' ') return false
            return true
        }

        private fun format(value: Long): String {
            val digits = value.toString()
            if (value < GROUPING_FROM) return digits
            val grouped = StringBuilder()
            for ((index, c) in digits.withIndex()) {
                if (index > 0 && (digits.length - index) % 3 == 0) grouped.append(rules.groupSeparator)
                grouped.append(c)
            }
            return grouped.toString()
        }
    }

    private object French : Rules() {
        private val units = mapOf(
            "un" to 1,
            "une" to 1,
            "deux" to 2,
            "trois" to 3,
            "quatre" to 4,
            "cinq" to 5,
            "six" to 6,
            "sept" to 7,
            "huit" to 8,
            "neuf" to 9,
        )
        private val teens = mapOf("dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16)
        private val tens = mapOf(
            "vingt" to 20,
            "trente" to 30,
            "quarante" to 40,
            "cinquante" to 50,
            "soixante" to 60,
            "septante" to 70,
            "octante" to 80,
            "huitante" to 80,
            "nonante" to 90,
        )
        private val scales = listOf(setOf("milliard", "milliards") to 1_000_000_000L, setOf("million", "millions") to 1_000_000L)
        private val hundred = setOf("cent", "cents")
        private const val THOUSAND = "mille"
        private val zero = setOf("zéro", "zero")

        /** Après « neuf » seul, ces mots en font l'adjectif (« un vélo neuf et rapide ») plutôt que le nombre. */
        private val linkWords = setOf(
            "et",
            "ou",
            "mais",
            "comme",
            "pour",
            "avec",
            "de",
            "du",
            "des",
            "à",
            "au",
            "aux",
            "en",
            "que",
            "qui",
            "dans",
            "sur",
            "par",
            "sans",
            "chez",
            "donc",
            "car",
        )

        private val vocabulary: Set<String> =
            units.keys + teens.keys + tens.keys + scales.flatMap { it.first } + hundred + zero + setOf(THOUSAND, "vingts", "et")

        override fun accepts(part: String) = part in vocabulary
        override val decimalWords = setOf("virgule")
        override val decimalSeparator = ","
        override val groupSeparator = NARROW_NO_BREAK_SPACE
        override val percentWords = listOf(listOf("pour", "cent"))
        override val percentSign = "$NARROW_NO_BREAK_SPACE%"

        override fun convertsAlone(word: String, next: String?): Boolean = when (word) {
            "un", "une" -> false
            "neuf" -> next != null && next !in linkWords
            else -> true
        }

        override fun parse(tokens: List<String>): Long? {
            if (tokens.size == 1 && tokens[0] in zero) return 0L
            val reader = Reader(tokens)
            var total = 0L
            var scaleIndex = 0
            var group = below1000(reader)
            while (true) {
                val token = reader.peek()
                val scale = scales.withIndex().firstOrNull { (index, entry) -> index >= scaleIndex && token in entry.first }
                when {
                    scale != null && group != null -> {
                        total += group * scale.value.second
                        scaleIndex = scale.index + 1
                        reader.next()
                    }
                    token == THOUSAND && scaleIndex <= scales.size -> {
                        total += (group ?: 1L) * 1_000L
                        scaleIndex = scales.size + 1
                        reader.next()
                    }
                    else -> {
                        if (group == null && total == 0L) return null
                        total += group ?: 0L
                        return if (reader.done()) total else null
                    }
                }
                group = below1000(reader)
            }
        }

        private fun below1000(reader: Reader): Long? {
            val start = reader.position
            var hundreds = 0L
            val first = reader.peek()
            if (units[first]?.let { it >= 2 } == true && reader.peek(1) in hundred) {
                hundreds = units.getValue(first) * 100L
                reader.skip(2)
            } else if (first in hundred) {
                hundreds = 100L
                reader.skip(1)
            }
            val rest = below100(reader)
            if (hundreds == 0L && rest == null) {
                reader.position = start
                return null
            }
            return hundreds + (rest ?: 0L)
        }

        private fun below100(reader: Reader): Long? {
            val first = reader.peek().ifEmpty { return null }
            val ten = tens[first]
            if (ten != null) {
                reader.skip(1)
                return ten + afterTens(reader, ten)
            }
            if (first == "quatre" && reader.peek(1).let { it == "vingt" || it == "vingts" }) {
                reader.skip(2)
                return 80L + (teensOrUnits(reader) ?: 0L)
            }
            return teensOrUnits(reader)
        }

        /** Ce qui suit une dizaine : « et un », « et onze » (après soixante), dix à dix-neuf (après soixante), une unité. */
        private fun afterTens(reader: Reader, ten: Int): Long {
            val next = reader.peek()
            val after = reader.peek(1)
            if (next == "et" && (after == "un" || after == "une")) {
                reader.skip(2)
                return 1L
            }
            if (next == "et" && after == "onze" && ten == 60) {
                reader.skip(2)
                return 11L
            }
            if (ten == 60) teensOrUnits(reader)?.let { return it }
            val unit = units[next] ?: return 0L
            reader.skip(1)
            return unit.toLong()
        }

        /** Une unité, dix à seize, ou dix-sept à dix-neuf. */
        private fun teensOrUnits(reader: Reader): Long? {
            val first = reader.peek().ifEmpty { return null }
            if (first == "dix") {
                val unit = units[reader.peek(1)]
                if (unit != null && unit >= 7) {
                    reader.skip(2)
                    return 10L + unit
                }
            }
            teens[first]?.let { value ->
                reader.skip(1)
                return value.toLong()
            }
            val unit = units[first] ?: return null
            reader.skip(1)
            return unit.toLong()
        }
    }

    private object English : Rules() {
        private val units = mapOf(
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
        )
        private val teens = mapOf(
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
        )
        private val tens = mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )
        private val scales = listOf("billion" to 1_000_000_000L, "million" to 1_000_000L, "thousand" to 1_000L)

        private val vocabulary: Set<String> =
            units.keys + teens.keys + tens.keys + scales.map { it.first } + setOf("hundred", "zero", "and", "a")

        override fun accepts(part: String) = part in vocabulary
        override val decimalWords = setOf("point")
        override val decimalSeparator = "."
        override val groupSeparator = ","
        override val percentWords = listOf(listOf("percent"), listOf("per", "cent"))
        override val percentSign = "%"

        override fun convertsAlone(word: String, next: String?): Boolean = word != "one" && word != "a"

        override fun parse(tokens: List<String>): Long? {
            if (tokens == listOf("zero")) return 0L
            val reader = Reader(tokens)
            var total = 0L
            var scaleIndex = 0
            while (true) {
                val group = below1000(reader)
                val token = reader.peek()
                val scale = scales.withIndex().firstOrNull { (index, entry) -> index >= scaleIndex && token == entry.first }
                if (scale != null && group != null) {
                    total += group * scale.value.second
                    scaleIndex = scale.index + 1
                    reader.next()
                    continue
                }
                if (group == null && total == 0L) return null
                // « two thousand and five » : « and » avant le dernier groupe.
                if (group == null && token == "and" && total > 0L) {
                    reader.next()
                    val last = below100(reader) ?: return null
                    total += last
                } else {
                    total += group ?: 0L
                }
                return if (reader.done()) total else null
            }
        }

        private fun below1000(reader: Reader): Long? {
            val start = reader.position
            val first = reader.peek()
            val multiplier = if (first == "a") 1 else units[first]
            if (multiplier != null && reader.peek(1) == "hundred") {
                reader.skip(2)
                if (reader.peek() == "and") {
                    reader.skip(1)
                    val rest = below100(reader)
                    if (rest == null) {
                        reader.position = start
                        return null
                    }
                    return multiplier * 100L + rest
                }
                return multiplier * 100L + (below100(reader) ?: 0L)
            }
            if (first == "a") {
                // « a thousand », « a million » : « a » vaut 1 devant une échelle.
                if (scales.any { it.first == reader.peek(1) }) {
                    reader.skip(1)
                    return 1L
                }
                return null
            }
            return below100(reader)
        }

        private fun below100(reader: Reader): Long? {
            val first = reader.peek().ifEmpty { return null }
            tens[first]?.let { ten ->
                reader.skip(1)
                val unit = units[reader.peek()] ?: return ten.toLong()
                reader.skip(1)
                return (ten + unit).toLong()
            }
            (teens[first] ?: units[first])?.let { value ->
                reader.skip(1)
                return value.toLong()
            }
            return null
        }
    }

    private class Reader(private val tokens: List<String>) {
        var position = 0

        /** Mot à [offset] de la position, ou "" après le dernier. */
        fun peek(offset: Int = 0): String = tokens.getOrNull(position + offset) ?: ""

        fun next() {
            position++
        }

        fun skip(count: Int) {
            position += count
        }

        fun done(): Boolean = position == tokens.size
    }

    /** Lettres (accents compris), avec traits d'union internes : « vingt-trois », « peut-être ». */
    private val WORD = Regex("""\p{L}+(?:-\p{L}+)*""")
    private const val MAX_WORDS = 16
    private const val GROUPING_FROM = 10_000L
    private const val NARROW_NO_BREAK_SPACE = " "
}
