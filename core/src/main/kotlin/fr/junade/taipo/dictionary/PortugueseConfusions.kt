package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.noneOf
import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes du portugais (Brésil et Portugal), par table déclarative : même moteur que [FrenchConfusions]. « e » et « é »,
 * « esta » et « está », « a » et « há », « mas » et « mais » sont tous justes : seul le voisin tranche, et seulement quand il rend l'autre
 * lecture impossible.
 *
 * Familles couvertes :
 * - e / é : après « não » ; entre un pronom (« isso », « tudo », « ela »...) et un mot qui suit « é » (« verdade », « possível »...) ;
 * - esta / está : après un pronom sujet (« você esta bem » ; « esta casa » reste « esta ») ; « onde esta » devant un article ; estas /
 *   estás après « tu » ;
 * - há : « a muito tempo », « a pouco tempo » ;
 * - mais : « nunca mas », « cada vez mas », « muito mas do que » ;
 * - a gente : « agente » devant un verbe, hors « o agente », « um agente »... ;
 * - mots soudés (« concerteza », « derrepente », « apartir », « porisso », « denovo »).
 *
 * Testé contre les phrases justes du banc (`tools/autocorrect-bench/pt/sentences-ok.txt`, dont « ele e ela », « isso e aquilo »,
 * « como esta casa é bonita », « o agente da polícia ») et `PortugueseConfusionsTest`.
 *
 * Volontairement non couvert : « e » + « muito » ou « bom » sans pronom avant (« bonito e bom » est juste), « mas » après « bem »
 * (« fala bem mas devagar »), « porque » / « por quê » (la question peut finir loin du mot, et une règle « suivie d'une ponctuation » attend
 * le mot suivant : « Porque? » seul en fin de texte ne serait jamais vu), « há » + durée sans « muito ».
 */
internal object PortugueseConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Ce qui suit « é » et jamais la conjonction « e » après un pronom. */
    private val AFTER_IS = arrayOf(
        "verdade", "possível", "possivel", "impossível", "impossivel", "melhor", "pior", "difícil", "dificil", "fácil", "facil", "importante",
        "necessário", "necessario", "normal", "ótimo", "otimo", "ótima", "otima", "sério", "serio", "mentira", "muito", "tão", "tao",
    )

    private val PRONOUNS = arrayOf("isso", "isto", "aquilo", "tudo", "ele", "ela", "você", "voce", "nada")

    /** Sujets après lesquels « esta » ne peut être que « está » (« esta casa » suit un article ou ouvre la phrase). */
    private val SUBJECTS = arrayOf("você", "voce", "ele", "ela", "tudo", "isso", "isto", "nada", "quem")

    /** Ce qui suit « onde está » et jamais le démonstratif « esta » (qui précède un nom). */
    private val AFTER_WHERE_IS = arrayOf("o", "a", "os", "as", "meu", "minha", "meus", "minhas", "teu", "tua", "seu", "sua", "você", "voce", "ele", "ela", "tudo")

    /** Verbes qui suivent « a gente » (« a gente vai », « a gente não pode »). */
    private val AFTER_WE = arrayOf(
        "vai", "vamos", "pode", "podia", "tem", "tinha", "fica", "ficou", "está", "esta", "estava", "foi", "era", "ia", "se", "não", "nao",
        "quer", "queria", "precisa", "sabe", "vê", "faz", "fez", "deve", "devia", "gosta", "acha", "chega", "volta", "sempre", "nunca",
    )

    /** Mots qui précèdent le nom « agente » (« o agente vai ») : la règle « a gente » ne s'applique pas après eux. */
    private val BEFORE_AGENT_NOUN = arrayOf(
        "o", "um", "do", "no", "ao", "pelo", "este", "esse", "aquele", "seu", "meu", "nosso", "teu", "outro", "cada", "novo", "bom",
        "melhor", "primeiro", "último", "ultimo", "agente", "como",
    )

    internal val RULES: List<ConfusionRule> = buildList {
        // --- e / é ---
        add(ConfusionRule("e", "é", "e > é après « não »", previous = oneOf("não", "nao")))
        add(ConfusionRule("e", "é", "e > é entre un pronom et un mot qui suit « é »", previous = oneOf(*PRONOUNS), next = oneOf(*AFTER_IS)))

        // --- esta / está ---
        add(ConfusionRule("esta", "está", "esta > está après un pronom sujet", previous = oneOf(*SUBJECTS)))
        add(ConfusionRule("esta", "está", "esta > está après « onde » devant un article ou un pronom", previous = oneOf("onde"), next = oneOf(*AFTER_WHERE_IS)))
        add(ConfusionRule("esta", "está", "esta > está après « onde », en fin de question", previous = oneOf("onde"), punctAfter = true))
        add(ConfusionRule("estas", "estás", "estas > estás après « tu »", previous = oneOf("tu")))

        // --- há ---
        add(ConfusionRule("a", "há", "a > há dans « a muito tempo »", next = oneOf("muito", "pouco"), next2 = oneOf("tempo")))

        // --- mais ---
        add(ConfusionRule("mas", "mais", "mas > mais après « nunca »", previous = oneOf("nunca")))
        add(ConfusionRule("mas", "mais", "mas > mais dans « cada vez mas »", previous = oneOf("vez"), previous2 = oneOf("cada")))
        add(ConfusionRule("mas", "mais", "mas > mais dans « muito mas do que »", previous = oneOf("muito", "pouco"), next = oneOf("do", "de", "que")))

        // --- a gente ---
        add(ConfusionRule("agente", "a gente", "agente > a gente en début de phrase devant un verbe", sentenceStart = true, next = oneOf(*AFTER_WE)))
        add(ConfusionRule("agente", "a gente", "agente > a gente devant un verbe, hors « o agente »", previous = noneOf(*BEFORE_AGENT_NOUN), next = oneOf(*AFTER_WE)))

        // --- mots soudés ---
        add(ConfusionRule("concerteza", "com certeza", "concerteza > com certeza", always = true))
        add(ConfusionRule("derrepente", "de repente", "derrepente > de repente", always = true))
        add(ConfusionRule("apartir", "a partir", "apartir > a partir", always = true))
        add(ConfusionRule("porisso", "por isso", "porisso > por isso", always = true))
        add(ConfusionRule("denovo", "de novo", "denovo > de novo", always = true))
    }
}

/** Correction de contexte du portugais ([PortugueseConfusions]). */
object PortugueseContextCorrector {

    /** Corrige la fin de [text] ; null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct]. */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = PortugueseConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
