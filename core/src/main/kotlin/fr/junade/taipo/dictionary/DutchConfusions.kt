package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes du néerlandais, par table déclarative : même moteur que [FrenchConfusions]. La faute la plus répandue est le
 * « d / dt » (« hij word », « ik wordt ») : les deux formes sont des mots justes, seul le sujet tranche.
 *
 * Familles couvertes :
 * - d / dt de « worden », « vinden », « houden », « antwoorden » : « t » après « hij », « zij », « ze », « men », « u », « jij »
 *   (« hij word » -> « wordt ») ; pas de « t » après « ik » ni devant « jij » inversé (« ik wordt » -> « word », « wordt jij » -> « word jij ») ;
 * - jou / jouw : « voor jouw. » (en fin de groupe après une préposition, c'est le pronom « jou ») ;
 * - hun / zij : « Hun hebben » en début de phrase, devant un verbe ;
 * - apostrophe : « s'avonds » -> « 's avonds » (et « s'morgens », « s'middags », « s'nachts », « s'ochtends ») ; « zon mooie » -> « zo'n mooie ».
 *
 * Testé contre les phrases justes du banc (`tools/autocorrect-bench/nl/sentences-ok.txt`, dont « word jij », « wordt het », « dat woord »,
 * « wat is er gebeurd ») et `DutchConfusionsTest`.
 *
 * Volontairement non couvert : « gebeurd » / « gebeurt » (« het is gebeurd » et « het gebeurt » se ressemblent trop sans analyse),
 * « wordt je » (« wordt je auto gerepareerd » est juste), « groter als » / « groter dan » (usage), « jouw » devant un nom.
 */
internal object DutchConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Sujets après lesquels le verbe prend un « t » (3e personne, « u », « jij » non inversé). */
    private val T_SUBJECTS = arrayOf("hij", "zij", "ze", "men", "u", "jij")

    /** Radicaux en « d » des verbes courants : « word » / « wordt »... */
    private val D_STEMS = arrayOf("word", "vind", "houd", "antwoord")

    private val PREPOSITIONS = arrayOf("voor", "aan", "met", "van", "bij", "naar", "op", "om", "over", "zonder", "tegen", "achter", "door", "dan")

    private val PLURAL_VERBS = arrayOf(
        "hebben", "zijn", "willen", "kunnen", "gaan", "komen", "doen", "moeten", "zeggen", "weten", "waren", "hadden", "gingen", "kwamen",
        "wilden", "konden", "mogen", "zullen", "zouden",
    )

    /** Adjectifs qui suivent « zo'n » (« zo'n mooie dag ») et jamais le nom « zon » (le soleil). */
    private val AFTER_SUCH_A = arrayOf(
        "mooie", "leuke", "grote", "kleine", "rare", "goede", "lange", "korte", "oude", "nieuwe", "gekke", "enorme", "vreselijke", "heerlijke",
        "prachtige", "lekkere", "slechte", "zware", "lieve", "domme", "slimme",
    )

    internal val RULES: List<ConfusionRule> = buildList {
        // --- d / dt ---
        // « het / er / dit / dat / die word » : sujets de « worden » seulement (« wat vind jij » reste « vind »).
        add(ConfusionRule("word", "wordt", "word > wordt après het, er, dit, dat, die", previous = oneOf("het", "er", "dit", "dat", "die")))
        for (stem in D_STEMS) {
            add(ConfusionRule(stem, "${stem}t", "$stem > ${stem}t après hij, zij, u, jij...", previous = oneOf(*T_SUBJECTS)))
            add(ConfusionRule("${stem}t", stem, "${stem}t > $stem après « ik »", previous = oneOf("ik")))
            add(ConfusionRule("${stem}t", stem, "${stem}t > $stem devant « jij » (inversion)", next = oneOf("jij")))
        }

        // --- jou / jouw ---
        add(ConfusionRule("jouw", "jou", "jouw > jou en fin de groupe après une préposition", previous = oneOf(*PREPOSITIONS), punctAfter = true))

        // --- hun / zij ---
        add(ConfusionRule("hun", "zij", "hun > zij en début de phrase devant un verbe", sentenceStart = true, next = oneOf(*PLURAL_VERBS)))

        // --- apostrophe ---
        for (time in arrayOf("avonds", "morgens", "middags", "nachts", "ochtends")) {
            add(ConfusionRule("s'$time", "'s $time", "s'$time > 's $time", always = true))
        }
        add(ConfusionRule("zon", "zo'n", "zon > zo'n devant un adjectif", next = oneOf(*AFTER_SUCH_A)))
    }
}

/** Correction de contexte du néerlandais ([DutchConfusions]). */
object DutchContextCorrector {

    /** Corrige la fin de [text] ; null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct]. */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = DutchConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
