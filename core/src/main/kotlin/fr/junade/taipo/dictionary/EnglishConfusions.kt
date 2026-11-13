package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.noneOf
import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes de l'anglais, par table déclarative : même moteur que [FrenchConfusions] ([FrenchConfusions.fixWith]),
 * autres règles. Le dictionnaire ne voit qu'un mot à la fois : « their », « there » et « they're » sont tous trois justes.
 * Seul le voisin peut trancher, et seulement quand il rend l'autre lecture impossible.
 *
 * Familles couvertes : its / it's, your / you're, their / there / they're, to / too, then / than, lose / loose, whose / who's,
 * « should of » -> « should have », « a » -> « an » devant une voyelle, « i » -> « I », et quelques mots soudés par erreur
 * (« alot », « atleast », « eachother »).
 *
 * Principe (comme en français) : sans contexte sûr, on ne corrige pas. Une règle dont le contexte reste possible dans une phrase
 * juste est retirée (« its all », « their going away party », « to long »...). Testé contre les phrases justes du banc
 * (`tools/autocorrect-bench/en/sentences-ok.txt`) et `EnglishConfusionsTest`. Les règles à mot suivant attendent ce mot (correction
 * « à rebours », comme a / à en français) et ne s'appliquent pas juste après une annulation.
 *
 * Volontairement non couvert : affect / effect, passed / past, principal / principle, an -> a (« an hour », « an MBA » sont justes),
 * « loose » adjectif contre « lose » verbe sans verbe auxiliaire devant.
 */
internal object EnglishConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Mots qui ouvrent une proposition : « if their going », « and there not » ne peuvent venir d'un possessif. */
    private val CLAUSE_OPENERS = arrayOf(
        "if", "but", "and", "because", "when", "that", "so", "since", "while", "what", "how", "where", "why", "think", "thought",
        "know", "knew", "said", "say", "hope", "guess", "sure", "or",
    )

    /** Mots courants qui commencent par une voyelle écrite mais que « a » précède (son initial consonne). */
    private val A_BEFORE_VOWEL_LETTER = arrayOf(
        "one", "once", "eulogy", "euro", "euros", "european", "europe", "ewe", "eunuch", "eucalyptus", "euphemism", "euphoria",
    )

    /** Noms courants qui suivent « their » et jamais « there » : « there own », « there friends ». */
    private val THEIR_NOUNS = arrayOf(
        "own", "friends", "family", "parents", "kids", "children", "team", "company", "phone", "car", "house", "names", "name",
        "money", "mom", "dad", "mother", "father", "results", "cars", "dog", "dogs", "cat", "cats", "teacher", "teachers",
    )

    /** Verbes qui suivent « there » (« there is ») et jamais un possessif (« their is » n'existe pas). */
    private val THERE_VERBS = arrayOf(
        "is", "are", "was", "were", "isn't", "aren't", "wasn't", "weren't", "has", "would", "should", "could",
    )

    /** « u » prononcé « you » : « a university », « a user », « a unit » (et non « an »). */
    private val U_AS_YOU_PREFIXES = arrayOf("uni", "use", "usu", "uti", "ura", "uri", "ure", "uro", "uku", "ukr", "ubi", "uta", "uvu")

    /** « h » muet : « an hour », « an honest man », « an heir ». */
    private val SILENT_H = arrayOf("hour", "hours", "hourly", "honest", "honestly", "honor", "honour", "honors", "honours", "heir", "heirs", "honorable")

    private val STARTS_WITH_VOWEL_SOUND: WordTest = { w, _ ->
        val c = w.core
        if (c in SILENT_H) {
            true
        } else {
            c.length >= 3 &&
                '\'' !in c &&
                '-' !in c &&
                c[0] in "aeiou" &&
                c !in A_BEFORE_VOWEL_LETTER &&
                !c.startsWith("eu") &&
                !(c[0] == 'u' && U_AS_YOU_PREFIXES.any { c.startsWith(it) }) &&
                c !in setOf("and", "are", "all", "any", "also", "ill", "off", "old", "one", "our", "out", "own", "are", "ago", "act")
        }
    }

    private val COMPARATIVES = arrayOf(
        "more", "less", "fewer", "rather", "other", "better", "greater", "bigger", "smaller", "larger", "higher", "lower", "faster", "slower",
        "older", "younger", "cheaper", "easier", "harder", "stronger", "taller", "worse", "longer", "shorter",
    )

    private val SUBJECT_PRONOUNS_AND_DETERMINERS = arrayOf(
        "i", "you", "he", "she", "we", "they", "it", "me", "him", "her", "us", "them", "the", "a", "an", "my", "his", "their", "our",
        "your", "ever", "expected", "thought", "usual", "before", "anyone", "anything", "last", "any", "this", "that",
    )

    internal val RULES: List<ConfusionRule> = listOf(
        // --- « i » : le pronom est toujours en capitale ---
        ConfusionRule("i", "I", "i > I", always = true),
        ConfusionRule("i'm", "I'm", "i'm > I'm", always = true),
        ConfusionRule("i'll", "I'll", "i'll > I'll", always = true),
        ConfusionRule("i've", "I've", "i've > I've", always = true),
        ConfusionRule("i'd", "I'd", "i'd > I'd", always = true),

        // --- its / it's ---
        ConfusionRule(
            "its",
            "it's",
            "its > it's devant un mot qui ne suit pas un possessif",
            next = oneOf("a", "an", "the", "been", "not", "going", "just", "very", "so", "too", "really", "still", "because", "what", "ok", "okay", "getting", "gonna"),
        ),
        ConfusionRule("it's", "its", "it's > its devant « own »", next = oneOf("own")),
        ConfusionRule(
            "it's",
            "its",
            "it's > its après une préposition",
            previous = oneOf("of", "in", "on", "at", "by", "from", "with", "into", "despite", "during"),
            next = noneOf("a", "an", "the", "not", "been", "going", "just", "very", "so", "too", "really", "still", "because", "what", "ok", "okay", "getting", "gonna", "all", "time", "like", "my", "your", "our", "his", "her", "their", "i", "you", "he", "she", "we", "they", "it", "that", "this", "me", "us"),
        ),

        // --- your / you're ---
        ConfusionRule(
            "your",
            "you're",
            "your > you're devant un mot qui n'est pas un nom",
            next = oneOf("welcome", "going", "gonna", "not", "being", "doing", "getting", "so", "very", "too", "really", "always", "never", "just", "still", "a", "an", "the", "such", "quite", "coming"),
        ),
        ConfusionRule(
            "you're",
            "your",
            "you're > your devant un nom de possession",
            next = oneOf("own", "mom", "dad", "mother", "father", "family", "phone", "car", "name", "email", "account", "mind", "life", "job"),
        ),

        // --- their / there / they're ---
        ConfusionRule("there", "their", "there > their devant un nom possédé", next = oneOf(*THEIR_NOUNS)),
        ConfusionRule(
            "they're",
            "their",
            "they're > their devant un nom possédé",
            next = oneOf("own", "mom", "dad", "mother", "father", "car", "house", "phone"),
        ),
        ConfusionRule("their", "there", "their > there devant un verbe d'existence", next = oneOf(*THERE_VERBS)),
        ConfusionRule("they're", "there", "they're > there devant un verbe d'existence", next = oneOf("is", "are", "was", "were", "isn't", "aren't")),
        ConfusionRule(
            "there",
            "they're",
            "there > they're en début de proposition devant un gérondif",
            sentenceStart = true,
            next = oneOf("going", "gonna", "doing", "getting", "coming"),
        ),
        ConfusionRule(
            "there",
            "they're",
            "there > they're après une conjonction devant un gérondif",
            previous = oneOf(*CLAUSE_OPENERS),
            next = oneOf("going", "gonna", "not", "doing", "getting", "coming"),
        ),
        ConfusionRule(
            "their",
            "they're",
            "their > they're après une conjonction devant un gérondif",
            previous = oneOf(*CLAUSE_OPENERS),
            next = oneOf("going", "gonna", "not", "doing", "getting", "coming"),
        ),
        ConfusionRule("their", "they're", "their > they're devant « gonna »", next = oneOf("gonna")),
        ConfusionRule(
            "their",
            "there",
            "their > there devant un mot de temps (« over their yesterday »)",
            next = oneOf("yesterday", "today", "tonight", "tomorrow", "later"),
        ),
        ConfusionRule(
            "their",
            "there",
            "their > there en fin de groupe après un verbe de lieu",
            previous = oneOf("over", "out", "back", "from", "go", "went", "been", "going", "get", "got", "stay", "stayed", "live", "lived"),
            punctAfter = true,
        ),

        // --- grate / great ---
        ConfusionRule(
            "grate",
            "great",
            "grate > great devant un nom d'éloge, après « a », « was » ou « so »",
            previous = oneOf("a", "was", "is", "so", "very", "really", "such", "what", "have", "had", "been"),
            next = oneOf("movie", "day", "job", "idea", "time", "thing", "work", "game", "news", "guy", "one", "film", "show", "place", "friend", "person", "man", "woman", "team", "song", "book", "party", "weekend", "night", "morning", "year", "life", "story", "answer", "question", "view", "deal", "help", "thanks"),
        ),

        // --- to / too ---
        ConfusionRule(
            "to",
            "too",
            "to > too devant un adjectif ou adverbe de degré",
            next = oneOf("much", "many", "late", "early", "soon", "tired", "busy", "expensive"),
        ),
        ConfusionRule(
            "too",
            "to",
            "too > to devant un verbe à l'infinitif",
            previous = oneOf("want", "wants", "wanted", "going", "need", "needs", "needed", "have", "has", "had", "how", "way", "able", "trying", "try", "tried", "used", "supposed", "ready", "time", "like", "love", "hope", "plan", "decided", "forget", "forgot", "remember", "wish"),
            next = oneOf("be", "do", "go", "have", "make", "get", "see", "say", "take", "find", "help", "give", "use", "know", "try", "keep", "let", "come", "buy", "work", "play", "eat", "send", "call", "tell", "start", "stop", "leave", "stay", "read", "write", "learn", "ask", "talk", "watch", "change", "move", "check", "meet"),
        ),

        // --- then / than ---
        ConfusionRule(
            "then",
            "than",
            "then > than après un comparatif",
            previous = oneOf(*COMPARATIVES),
            next = oneOf(*SUBJECT_PRONOUNS_AND_DETERMINERS),
        ),
        ConfusionRule("than", "then", "than > then après « and » ou « but »", previous = oneOf("and", "but")),
        ConfusionRule("than", "then", "than > then en début de phrase", sentenceStart = true),

        // --- lose / loose ---
        ConfusionRule(
            "loose",
            "lose",
            "loose > lose après un auxiliaire",
            previous = oneOf("to", "will", "would", "can", "could", "might", "may", "don't", "didn't", "won't", "wouldn't", "can't", "couldn't", "shouldn't", "gonna", "should", "never"),
            next = noneOf("fitting", "clothes", "clothing", "ends", "end", "fit", "jeans", "pants", "shirt", "change", "cannon", "tea", "leaf", "lips"),
        ),
        ConfusionRule("lose", "loose", "lose > loose après « too », « very » ou « break »", previous = oneOf("too", "very", "let", "break", "broke")),

        // --- whose / who's ---
        ConfusionRule(
            "whose",
            "who's",
            "whose > who's devant un verbe ou un article",
            next = oneOf("going", "gonna", "coming", "been", "not", "the", "a", "an", "there", "ready", "here", "next", "first"),
        ),
        ConfusionRule(
            "who's",
            "whose",
            "who's > whose devant un nom possédé",
            next = oneOf("own", "idea", "turn", "fault", "car", "phone", "book", "name", "house", "mom", "dad", "side", "responsibility", "job", "bag", "coat", "keys", "shoes", "dog", "cat", "kid", "kids", "child", "parents", "birthday"),
        ),

        // --- should of ---
        ConfusionRule(
            "of",
            "have",
            "of > have après un modal",
            previous = oneOf("should", "could", "would", "must", "might", "shouldn't", "couldn't", "wouldn't", "mustn't"),
        ),

        // --- a / an ---
        ConfusionRule("a", "an", "a > an devant une voyelle", next = STARTS_WITH_VOWEL_SOUND),

        // --- mots soudés ---
        ConfusionRule("alot", "a lot", "alot > a lot", always = true),
        ConfusionRule("atleast", "at least", "atleast > at least", always = true),
        ConfusionRule("eachother", "each other", "eachother > each other", always = true),
        ConfusionRule("aswell", "as well", "aswell > as well", always = true),
        ConfusionRule("infact", "in fact", "infact > in fact", always = true),
        ConfusionRule("alittle", "a little", "alittle > a little", always = true),
        ConfusionRule("inspite", "in spite", "inspite > in spite", always = true),
        ConfusionRule("thankyou", "thank you", "thankyou > thank you", always = true),
    )
}

/** Correction de contexte de l'anglais ([EnglishConfusions]) : le pendant de [FrenchContextCorrector.correct] pour l'anglais. */
object EnglishContextCorrector {

    /**
     * Corrige la fin de [text] (le texte avant le curseur, sans la touche qui termine le mot). Renvoie le texte corrigé, ou
     * null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct].
     */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = EnglishConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
