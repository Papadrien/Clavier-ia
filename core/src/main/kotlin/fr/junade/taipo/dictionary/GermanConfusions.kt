package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.noneOf
import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes de l'allemand, par table déclarative : même moteur que [FrenchConfusions] ([FrenchConfusions.fixWith]), autres règles.
 * Le dictionnaire ne voit qu'un mot à la fois : « seit » et « seid », « wie » et « wir », « wurde » et « würde » sont tous des mots justes. Seul le voisin
 * peut trancher, et seulement quand il rend l'autre lecture impossible. Phase 5 du plan d'autocorrection allemande ; règles prudentes : une règle dont le
 * contexte reste possible dans une phrase juste est retirée.
 *
 * Familles couvertes :
 * - seid / seit : « ihr seit meine Freunde », « wo seit ihr », « seid drei Jahren », « seid wann » ;
 * - wie -> wir : seulement en début de phrase, devant un verbe au pluriel puis un mot qui ne peut suivre qu'un sujet (« Wie sind müde ») ; « Wie sind die Preise ? »,
 *   « Wie haben Sie geschlafen ? » restent intacts ;
 * - wurde -> würde (et wurdest, wurden, wurdet) : devant un infinitif, ou après un adverbe ou un pronom puis un infinitif (« ich wurde gern mitkommen ») ; un participe
 *   (« ich wurde gefragt ») ne change jamais ;
 * - das -> dass : seulement après une virgule qui suit un verbe de pensée ou de parole (ou un adjectif d'appréciation, en minuscules), devant un pronom sujet (« Ich weiß, das du
 *   müde bist ») ; « das Buch, das er liest » et « Ich weiß, das Buch ist gut » restent intacts ;
 * - mal -> Mal après un ordinal ou « jedes » (« zum ersten mal ») ; « Komm mal her » reste intact ;
 * - morgen / abend / nacht / mittag -> majuscule après heute, gestern, am, guten... (« heute abend » -> « heute Abend ») ; « morgen früh » reste intact ;
 * - ihn <-> ihm après un verbe qui ne se construit que d'une façon (« ich helfe ihn » -> « ihm », « ich kenne ihm » -> « ihn »).
 *
 * Volontairement non couvert : « Seit ihr schon fertig ? » (« Seit ihr hier wohnt » est juste), wie / wir après un verbe (« waren wie im Kino » est juste), « ich wurde das nie tun »
 * (« Ihm wurde das nie gesagt » est juste), konnte / könnte et hatte / hätte, ihnen, dass -> das. Les règles à mot suivant attendent ce mot (correction « à rebours »,
 * comme en français) ; celles à deux mots suivants attendent le deuxième. Elles ne s'appliquent pas juste après une annulation.
 *
 * Fichier généré à partir de la table de règles de la phase 5 : modifier une règle, c'est aussi la retester contre `tools/autocorrect-bench/de/sentences-ok.txt` et
 * `GermanConfusionsTest`.
 */
internal object GermanConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Le mot est l'un de [words] et ne commence pas par une majuscule : « das Schreiben » (nom) n'est pas l'infinitif « schreiben ». */
    private fun lowercaseOneOf(vararg words: String): WordTest {
        val set = words.toSet()
        return { w, _ -> !w.isCapitalized && w.core in set }
    }

    private val SUBJECT_PRONOUNS = arrayOf(
        "ich", "du", "er", "sie", "es", "wir", "ihr", "man",
    )

    private val THINK_WORDS = arrayOf(
        "weiß", "weißt", "weiss", "weisst", "wissen", "wusste", "glaube", "glaubst",
        "glauben", "glaubte", "hoffe", "hoffst", "hoffen", "hoffte", "denke", "denkst",
        "denken", "dachte", "meine", "meinst", "meinen", "finde", "findest", "finden",
        "sage", "sagst", "sagt", "sagen", "sagte", "behauptet", "fürchte", "vermute",
        "vermutet", "wünsche", "schön", "gut", "klar", "schade", "toll", "wichtig",
        "traurig", "bekannt", "wahr", "schlimm", "super", "wunderbar", "logisch", "nett",
        "seltsam", "komisch", "egal",
    )

    private val AFTER_SEID = arrayOf(
        "meine", "meiner", "unsere", "deine", "eure", "die", "alle", "nicht",
        "nie", "immer", "so", "sehr", "ganz", "wirklich", "auch", "schon",
        "doch", "bald", "zu", "ein", "eine", "herzlich", "willkommen", "fertig",
        "müde", "hier", "da", "gut", "richtig", "bereit", "pünktlich", "still",
        "leise", "laut", "spät", "früh", "echt", "toll", "super", "verrückt",
        "nett", "dabei",
    )

    private val QUESTION_WORDS = arrayOf(
        "wo", "wer", "wie", "was", "warum", "wann", "wieso", "weshalb",
    )

    private val TIME_AFTER_SEIT = arrayOf(
        "wann", "langem", "kurzem", "gestern", "heute", "dem", "einem", "einer",
        "einigen", "mehreren", "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag",
        "sonntag", "damals", "jeher", "beginn", "anfang", "letztem", "diesem", "zwei",
        "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
        "zwanzig",
    )

    private val NOT_BEFORE_SEIT = arrayOf(
        "ihr", "alle", "beide", "doch", "ja", "nun", "nur", "auch",
        "also", "eigentlich", "mal", "bitte", "bloß", "bloss", "zwei", "drei",
        "vier", "und",
    )

    private val PLURAL_VERBS = arrayOf(
        "sind", "haben", "müssen", "wollen", "sollen", "dürfen", "können", "werden",
        "waren", "hatten", "gehen", "fahren", "kommen", "bleiben", "machen", "essen",
        "trinken", "schlafen", "brauchen", "mögen", "möchten", "wissen", "sehen", "treffen",
        "freuen", "wären", "hätten", "würden", "könnten", "lieben", "arbeiten", "wohnen",
        "spielen", "lernen", "fliegen",
    )

    private val SAFE_AFTER_WIR = arrayOf(
        "müde", "keine", "kein", "los", "nicht", "zusammen", "hier", "dort",
        "gern", "gerne", "sehr", "wenig", "viel", "zu", "uns",
    )

    private val WURDE_FORMS = arrayOf(
        "wurde", "wurdest", "wurden", "wurdet",
    )

    private val INFINITIVES = arrayOf(
        "kommen", "gehen", "fahren", "schlafen", "essen", "trinken", "bleiben", "helfen",
        "machen", "sagen", "tun", "haben", "sein", "werden", "wissen", "sehen",
        "lesen", "spielen", "arbeiten", "warten", "nehmen", "bringen", "kaufen", "reisen",
        "fliegen", "laufen", "schwimmen", "tanzen", "singen", "lachen", "lernen", "wohnen",
        "leben", "besuchen", "bestellen", "anrufen", "mitkommen", "mitmachen", "mitgehen", "zusagen",
        "absagen", "schreiben", "denken", "fragen", "antworten", "finden", "geben", "zeigen",
        "suchen", "helfen", "holen", "bezahlen", "probieren", "versuchen", "tragen", "anfangen",
        "aufhören", "bauen", "fahren", "lieben", "mögen", "sitzen", "stehen", "liegen",
        "freuen", "treffen", "sprechen", "verlassen", "verstehen", "vergessen",
    )

    private val BEFORE_INFINITIVE = arrayOf(
        "gern", "gerne", "nie", "niemals", "bestimmt", "sicher", "auch", "ja",
        "doch", "ich", "du", "er", "sie", "es", "wir", "ihr",
        "man", "das",
    )

    private val ORDINAL_BEFORE_MAL = arrayOf(
        "erste", "ersten", "erstes", "zweite", "zweiten", "dritte", "dritten", "letzte",
        "letzten", "letztes", "nächste", "nächsten", "nächstes", "vorige", "vorigen", "einzige",
        "einzigen", "andere", "anderen", "jedes", "jedem", "manches", "manchem", "dieses",
        "diesem",
    )

    private val DAY_WORDS = arrayOf(
        "heute", "gestern", "vorgestern",
    )

    private val DAY_WORDS_AHEAD = arrayOf(
        "heute", "gestern", "vorgestern", "morgen", "übermorgen",
    )

    private val PREP_DAY = arrayOf(
        "am", "jeden", "diesen", "guten", "nächsten", "letzten", "schönen", "zu",
    )

    private val DATIVE_VERBS = arrayOf(
        "helfe", "hilfst", "hilft", "helfen", "helft", "half", "halfen", "danke",
        "dankst", "dankt", "danken", "gehört", "gehören", "gefällt", "folge", "folgt",
        "folgst", "antworte", "antwortet",
    )

    private val ACCUSATIVE_VERBS = arrayOf(
        "kenne", "kennst", "kennt", "kennen", "liebe", "liebst", "liebt", "lieben",
        "mag", "magst", "mögen", "besuche", "besuchst", "besucht", "besuchen",
    )

    internal val RULES: List<ConfusionRule> = listOf(
        ConfusionRule("seit", "seid", "seit > seid après « ihr » devant un mot qui ne peut suivre « seit »", previous = oneOf("ihr"), next = oneOf(*AFTER_SEID)),
        ConfusionRule("seit", "seid", "seit > seid : « wo seit ihr »", previous = oneOf(*QUESTION_WORDS), next = oneOf("ihr")),
        ConfusionRule("seid", "seit", "seid > seit devant une durée ou « wann »", previous = noneOf(*NOT_BEFORE_SEIT), next = oneOf(*TIME_AFTER_SEIT)),
        ConfusionRule("seid", "seit", "seid > seit en début de phrase devant « wann »", next = oneOf("wann"), sentenceStart = true),
        ConfusionRule("wie", "wir", "wie > wir en début de phrase devant un verbe au pluriel et un mot qui ne peut suivre qu'un sujet", next = oneOf(*PLURAL_VERBS), next2 = oneOf(*SAFE_AFTER_WIR), sentenceStart = true),
        ConfusionRule("wurde", "würde", "wurde > würde devant un infinitif", next = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurde", "würde", "wurde > würde devant un adverbe ou un pronom puis un infinitif", next = oneOf(*BEFORE_INFINITIVE), next2 = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurdest", "würdest", "wurdest > würdest devant un infinitif", next = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurdest", "würdest", "wurdest > würdest devant un adverbe ou un pronom puis un infinitif", next = oneOf(*BEFORE_INFINITIVE), next2 = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurden", "würden", "wurden > würden devant un infinitif", next = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurden", "würden", "wurden > würden devant un adverbe ou un pronom puis un infinitif", next = oneOf(*BEFORE_INFINITIVE), next2 = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurdet", "würdet", "wurdet > würdet devant un infinitif", next = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("wurdet", "würdet", "wurdet > würdet devant un adverbe ou un pronom puis un infinitif", next = oneOf(*BEFORE_INFINITIVE), next2 = lowercaseOneOf(*INFINITIVES)),
        ConfusionRule("das", "dass", "das > dass après une virgule qui suit un verbe de pensée ou de parole, devant un pronom sujet", previous = lowercaseOneOf(*THINK_WORDS), next = oneOf(*SUBJECT_PRONOUNS), afterComma = true),
        ConfusionRule("mal", "Mal", "mal > Mal après un ordinal ou « jedes »", previous = oneOf(*ORDINAL_BEFORE_MAL)),
        ConfusionRule("morgen", "Morgen", "morgen > Morgen après « heute », « gestern » ou une préposition", previous = oneOf("heute", "gestern", "vorgestern", "am", "jeden", "diesen", "guten", "nächsten")),
        ConfusionRule("abend", "Abend", "abend > Abend après un jour ou une préposition", previous = oneOf(*DAY_WORDS_AHEAD)),
        ConfusionRule("abend", "Abend", "abend > Abend après une préposition", previous = oneOf(*PREP_DAY)),
        ConfusionRule("nacht", "Nacht", "nacht > Nacht après un jour", previous = oneOf(*DAY_WORDS_AHEAD)),
        ConfusionRule("nacht", "Nacht", "nacht > Nacht après gute, letzte, diese ou jede", previous = oneOf("gute", "letzte", "diese", "jede", "nächste", "ganze")),
        ConfusionRule("mittag", "Mittag", "mittag > Mittag après un jour ou une préposition", previous = oneOf(*DAY_WORDS_AHEAD)),
        ConfusionRule("mittag", "Mittag", "mittag > Mittag après une préposition", previous = oneOf(*PREP_DAY)),
        ConfusionRule("ihn", "ihm", "ihn > ihm après un verbe qui veut le datif", previous = oneOf(*DATIVE_VERBS)),
        ConfusionRule("ihm", "ihn", "ihm > ihn après un verbe qui veut l'accusatif", previous = oneOf(*ACCUSATIVE_VERBS)),
    )
}

/** Correction de contexte de l'allemand ([GermanConfusions]) : le pendant de [EnglishContextCorrector] pour l'allemand. */
object GermanContextCorrector {

    /**
     * Corrige la fin de [text] (le texte avant le curseur, sans la touche qui termine le mot). Renvoie le texte corrigé, ou
     * null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct].
     */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = GermanConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
