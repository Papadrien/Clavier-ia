package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes de l'italien, par table déclarative : même moteur que [FrenchConfusions] ([FrenchConfusions.fixWith]), autres
 * règles. Le dictionnaire ne voit qu'un mot à la fois : « e » et « è », « a » et « ha », « o » et « ho » sont tous justes. Seul le voisin
 * tranche, et seulement quand il rend l'autre lecture impossible (« non e » : la conjonction ne suit pas « non » ; « io o fatto » : « o »
 * ne précède pas un participe).
 *
 * Familles couvertes :
 * - e / è : après « non », ou devant un participe d'« essere » ou un adjectif de jugement (« e stato », « e vero », « e meglio ») ;
 * - c'è : « c'e » et « cè » (formes qui n'existent pas), « ce » devant « un », « qualcosa », « nessuno »... ;
 * - po' : « un po » ;
 * - ho / ha / hanno : « o », « a », « anno » entre un sujet, une négation ou un pronom complément et un participe (« io o fatto »,
 *   « chi a mangiato », « non anno capito ») ; « io o fame » ;
 * - dà : « mi da fastidio » ; sì : « Si, certo » en début de phrase ; qual è (« qual'è » ne prend pas d'apostrophe).
 *
 * Principe (comme pour les autres langues) : sans contexte sûr, on ne corrige pas. Testé contre les phrases justes du banc
 * (`tools/autocorrect-bench/it/sentences-ok.txt`, dont « lui e lei », « io e te », « ce l'ho fatta ») et `ItalianConfusionsTest`.
 *
 * Volontairement non couvert : « e » + adjectif après un nom (« la città e bella » : « rosso e bello » est juste), « ne » / « né »
 * (« né io né lui » demande les deux), « se » / « sé », « la » / « là », « li » / « lì » (le sens seul décide).
 */
internal object ItalianConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    private val IRREGULAR_PARTICIPLES = setOf(
        "fatto", "detto", "visto", "preso", "messo", "scritto", "letto", "chiesto", "risposto", "rimasto", "perso", "deciso", "aperto",
        "chiuso", "vinto", "corso", "scelto", "spento", "acceso", "offerto", "sofferto", "morto", "nato", "rotto", "speso", "venduto",
        "successo", "promesso", "permesso", "dato", "stato",
    )

    /** Participe passé probable : en -ato / -uto / -ito (5 lettres ou plus) ou participe irrégulier courant. */
    private val PARTICIPLE_LIKE: WordTest = { w, _ ->
        val c = w.core
        c in IRREGULAR_PARTICIPLES || (c.length >= 5 && '\'' !in c && (c.endsWith("ato") || c.endsWith("uto") || c.endsWith("ito")))
    }

    /** Ce qui suit « è » et jamais la conjonction « e » en tête de groupe : participes d'« essere », adjectifs de jugement. */
    private val AFTER_IS = arrayOf(
        "stato", "stata", "stati", "state", "vero", "possibile", "impossibile", "meglio", "peggio", "necessario", "importante", "giusto",
        "sbagliato", "inutile", "difficile", "facile", "arrivato", "arrivata", "successo", "andato", "andata", "venuto", "venuta",
        "tornato", "tornata", "partito", "partita", "uscito", "uscita",
    )

    /** Ce qui suit « c'è » et jamais « ce » (« ce l'ho », « ce ne sono » restent justes). */
    private val AFTER_THERE_IS = arrayOf(
        "un", "una", "uno", "qualcuno", "qualcosa", "nessuno", "niente", "nulla", "molto", "molta", "tanto", "tanta", "troppo", "troppa",
        "poco", "poca", "sempre",
    )

    /** Ce qui suit « ho » dans « ho fame », « ho sonno »... (« o » ne les précède pas après « io » ou « non »). */
    private val HAVE_NOUNS = arrayOf(
        "fame", "sete", "sonno", "paura", "freddo", "caldo", "bisogno", "voglia", "ragione", "torto", "fretta", "tempo", "capito",
    )

    /** Sujets, négation et pronoms complément qui précèdent « ha » (3e personne). */
    private val BEFORE_HAS = arrayOf("lui", "lei", "chi", "non", "mi", "ti", "ci", "vi", "gli", "le", "lo", "la", "ne", "che", "cosa")

    /** Sujets, négation et pronoms complément qui précèdent « hanno ». */
    private val BEFORE_HAVE_PLURAL = arrayOf("loro", "non", "ci", "gli", "mi", "ti", "vi", "li", "le", "ne", "che", "lo", "la")

    internal val RULES: List<ConfusionRule> = buildList {
        // --- e / è ---
        add(ConfusionRule("e", "è", "e > è après « non »", previous = oneOf("non")))
        add(ConfusionRule("e", "è", "e > è devant un participe d'essere ou un adjectif de jugement", next = oneOf(*AFTER_IS)))

        // --- c'è ---
        add(ConfusionRule("c'e", "c'è", "c'e > c'è", always = true))
        add(ConfusionRule("cè", "c'è", "cè > c'è", always = true))
        add(ConfusionRule("ce", "c'è", "ce > c'è devant un, qualcosa, nessuno...", next = oneOf(*AFTER_THERE_IS)))

        // --- po' ---
        add(ConfusionRule("po", "po'", "po > po' après « un »", previous = oneOf("un")))
        add(ConfusionRule("pò", "po'", "pò > po'", always = true))

        // --- ho / ha / hanno ---
        add(
            ConfusionRule(
                "o",
                "ho",
                "o > ho entre io, non ou un pronom et un participe",
                previous = oneOf("io", "non", "ne", "ce", "lo", "la", "li", "le"),
                next = PARTICIPLE_LIKE,
            ),
        )
        add(ConfusionRule("o", "ho", "o > ho dans « io o fame »", previous = oneOf("io", "non"), next = oneOf(*HAVE_NOUNS)))
        add(ConfusionRule("a", "ha", "a > ha entre un sujet ou un pronom et un participe", previous = oneOf(*BEFORE_HAS), next = PARTICIPLE_LIKE))
        add(
            ConfusionRule(
                "anno",
                "hanno",
                "anno > hanno entre un sujet ou un pronom et un participe",
                previous = oneOf(*BEFORE_HAVE_PLURAL),
                next = PARTICIPLE_LIKE,
            ),
        )

        // --- dà, sì, qual è ---
        add(
            ConfusionRule(
                "da",
                "dà",
                "da > dà dans « mi da fastidio »",
                previous = oneOf("mi", "ti", "ci", "gli", "vi"),
                next = oneOf("fastidio", "retta", "ragione", "torto", "noia"),
            ),
        )
        add(ConfusionRule("si", "sì", "si > sì en début de phrase, suivi d'une ponctuation", sentenceStart = true, punctAfter = true))
        add(ConfusionRule("qual'è", "qual è", "qual'è > qual è", always = true))
        add(ConfusionRule("qual'era", "qual era", "qual'era > qual era", always = true))
    }
}

/** Correction de contexte de l'italien ([ItalianConfusions]). */
object ItalianContextCorrector {

    /** Corrige la fin de [text] ; null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct]. */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = ItalianConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
