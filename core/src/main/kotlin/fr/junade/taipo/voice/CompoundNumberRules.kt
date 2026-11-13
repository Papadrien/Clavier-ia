package fr.junade.taipo.voice

/**
 * Règles des nombres dits en lettres pour les langues autres que le français et l'anglais, à partir d'un lexique
 * ([NumberLexicon], dans [NumberLexicons]).
 *
 * Un mot est découpé en morceaux du lexique, ce qui couvre les langues qui soudent leurs nombres (« dreiundzwanzig »,
 * « tjugotre », « kaksikymmentäkolme », « huszonhárom », « ventitré ») comme celles qui les écrivent en plusieurs mots
 * (« dwadzieścia trzy », « douăzeci și trei », « treinta y tres »). La suite des morceaux est ensuite lue comme un nombre :
 * centaine, dizaine et unité au plus une fois chacune par tranche, échelles (mille, million, milliard) décroissantes,
 * liaisons (« und », « og », « y », « și ») seulement entre deux morceaux. Une suite qui ne respecte pas ces règles n'est pas
 * un nombre (« dos y tres » reste deux nombres, « 2 y 3 »).
 */
internal class CompoundNumberRules(private val lexicon: NumberLexicon) : SpokenNumbers.Rules() {

    override val decimalWords get() = lexicon.decimalWords
    override val decimalSeparator get() = lexicon.decimalSeparator
    override val groupSeparator get() = lexicon.groupSeparator
    override val percentWords get() = lexicon.percentWords
    override val percentSign get() = lexicon.percentSign

    override fun accepts(part: String): Boolean = segment(part) != null

    override fun convertsAlone(word: String, next: String?): Boolean = word !in lexicon.ambiguous

    override fun parse(tokens: List<String>): Long? {
        val morphemes = tokens.flatMap { segment(it) ?: return null }
        return Reading(morphemes).value()
    }

    /** [word] découpé en morceaux du lexique (les plus longs d'abord), ou null s'il n'est pas fait que de morceaux. */
    private fun segment(word: String): List<Morpheme>? {
        if (word.isEmpty() || word.length > MAX_WORD_LENGTH) return null
        val dead = BooleanArray(word.length)
        val found = ArrayList<Morpheme>()

        fun from(start: Int): Boolean {
            if (start == word.length) return true
            if (dead[start]) return false
            for (end in minOf(word.length, start + lexicon.longest) downTo start + 1) {
                val morpheme = pieceAt(word, start, end, found.lastOrNull()) ?: continue
                found.add(morpheme)
                if (from(end)) return true
                found.removeAt(found.lastIndex)
            }
            dead[start] = true
            return false
        }
        return if (from(0)) found else null
    }

    /** Le morceau [start]..[end] de [word] s'il est au lexique et peut suivre [before]. */
    private fun pieceAt(word: String, start: Int, end: Int, before: Morpheme?): Morpheme? {
        val piece = word.substring(start, end)
        // Article soudé après une échelle : terminaison, pas unité (suédois « miljon-en », « le million »).
        if (piece in lexicon.ambiguous && before is Morpheme.Scale) return null
        return lexicon.morphemes[piece]
    }

    /** Lecture d'une suite de morceaux : la valeur du nombre, ou null si elle n'en forme pas un. */
    private class Reading(private val morphemes: List<Morpheme>) {
        private var total = 0L
        private var lastScale = Long.MAX_VALUE
        private var hundreds = 0L
        private var tens = 0L
        private var unit = 0L
        private var teen = 0L

        /** Une dizaine soudée attend son unité (« tizen » dans « tizenhárom »). */
        private var waitingUnit = false
        private var previous: Morpheme? = null

        fun value(): Long? {
            if (morphemes.isEmpty()) return null
            if (morphemes.size == 1 && morphemes[0] == Morpheme.Zero) return 0L
            for ((index, morpheme) in morphemes.withIndex()) {
                val ok = when (morpheme) {
                    is Morpheme.Connector -> connector(index, morpheme)
                    is Morpheme.Number -> number(morpheme)
                    is Morpheme.Hundred -> hundred(morpheme)
                    is Morpheme.Scale -> scale(morpheme)
                    Morpheme.TenTimes -> tenTimes()
                    Morpheme.TeenSuffix -> teenSuffix()
                    Morpheme.Zero -> false
                }
                if (!ok) return null
                previous = morpheme
            }
            if (waitingUnit || morphemes.none { it !is Morpheme.Connector }) return null
            return total + group()
        }

        private fun group() = hundreds + tens + teen + unit

        private fun smallEmpty() = tens == 0L && teen == 0L && unit == 0L && !waitingUnit

        private fun connector(index: Int, connector: Morpheme.Connector): Boolean {
            if (index == 0 || index == morphemes.lastIndex || previous is Morpheme.Connector) return false
            return !connector.beforeScale || morphemes[index + 1] is Morpheme.Scale
        }

        private fun number(number: Morpheme.Number): Boolean {
            val value = number.value
            when {
                value < 10 -> {
                    if (unit != 0L || teen != 0L) return false
                    if (waitingUnit) {
                        teen = 10 + value
                        waitingUnit = false
                    } else {
                        unit = value
                    }
                }
                value < 20 -> {
                    if (!smallEmpty()) return false
                    if (number.prefix) waitingUnit = true else teen = value
                }
                value < 100 -> {
                    if (tens != 0L || teen != 0L || waitingUnit) return false
                    // Unité avant la dizaine : seulement après une liaison (« drei-und-zwanzig », « tre-og-tyve »).
                    if (unit != 0L && (previous !is Morpheme.Connector || value % 10 != 0L)) return false
                    // Dizaine et unité d'un seul mot (« veintitrés ») : plus d'unité ensuite.
                    tens = value - value % 10
                    if (value % 10 != 0L) unit = value % 10
                }
                else -> {
                    if (hundreds != 0L || !smallEmpty()) return false
                    hundreds = value
                }
            }
            return true
        }

        /** Multiplie l'unité, ou un nombre de 11 à 19 pour les années (« neunzehnhundert », « negentienhonderd »). */
        private fun hundred(hundred: Morpheme.Hundred): Boolean {
            if (hundreds != 0L || tens != 0L || waitingUnit) return false
            val multiplier = teen + unit
            if (multiplier == 0L && hundred.bound) return false
            hundreds = maxOf(multiplier, 1L) * 100
            unit = 0L
            teen = 0L
            return true
        }

        private fun scale(scale: Morpheme.Scale): Boolean {
            if (scale.value >= lastScale || waitingUnit) return false
            val multiplier = group()
            if (multiplier == 0L && scale.bound) return false
            total += maxOf(multiplier, 1L) * scale.value
            lastScale = scale.value
            hundreds = 0L
            tens = 0L
            teen = 0L
            unit = 0L
            return true
        }

        /** Le morceau précédent est un nombre, et la tranche ne contient encore qu'une unité. */
        private fun afterUnitOnly() = previous is Morpheme.Number && tens == 0L && teen == 0L

        /** « kaksi-kymmentä » : l'unité qui précède devient la dizaine. */
        private fun tenTimes(): Boolean {
            if (unit < 2 || !afterUnitOnly()) return false
            tens = unit * 10
            unit = 0L
            return true
        }

        /** « kaksi-toista » : l'unité qui précède devient un nombre de 11 à 19. */
        private fun teenSuffix(): Boolean {
            if (unit == 0L || !afterUnitOnly()) return false
            teen = 10 + unit
            unit = 0L
            return true
        }
    }

    private companion object {
        const val MAX_WORD_LENGTH = 80
    }
}

/** Un morceau de nombre du lexique d'une langue. */
internal sealed interface Morpheme {
    /** Valeur simple : 1 à 9, 10 à 19, une dizaine ou une centaine entière (« dwieście ») ; [prefix] : dizaine soudée à l'unité qui suit. */
    data class Number(val value: Long, val prefix: Boolean = false) : Morpheme

    /** « hundert », « sto » : multiplie l'unité qui précède par 100 ; [bound] : jamais seul (« sute », « sataa »). */
    data class Hundred(val bound: Boolean) : Morpheme

    /** Mille, million, milliard ; [bound] : forme qui suit toujours un nombre (« tysięcy », « tuhatta », « mii »). */
    data class Scale(val value: Long, val bound: Boolean) : Morpheme

    /** Liaison (« und », « og », « y ») ; [beforeScale] : seulement devant une échelle (roumain « douăzeci de mii »). */
    data class Connector(val beforeScale: Boolean) : Morpheme

    data object Zero : Morpheme

    /** Finnois « kymmentä » : dizaine de l'unité qui précède. */
    data object TenTimes : Morpheme

    /** Finnois « toista » : 10 de plus que l'unité qui précède. */
    data object TeenSuffix : Morpheme
}

/** Le lexique des nombres d'une langue et sa mise en forme. */
internal class NumberLexicon(
    val morphemes: Map<String, Morpheme>,
    /** Mots laissés en lettres quand ils sont seuls (articles, homonymes : « ein », « un », « en », « hét »). */
    val ambiguous: Set<String>,
    val decimalWords: Set<String>,
    val groupSeparator: String,
    val percentWords: List<List<String>>,
    val percentSign: String,
    val decimalSeparator: String = ",",
) {
    val longest: Int = morphemes.keys.maxOf { it.length }
}
