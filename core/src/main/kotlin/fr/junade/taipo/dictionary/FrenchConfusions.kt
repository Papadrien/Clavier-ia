package fr.junade.taipo.dictionary

/** Test sur un mot du texte ; le second paramètre donne la fréquence d'un mot dans les listes (0 si absent). */
internal typealias WordTest = (Word, (String) -> Long) -> Boolean

/** Un mot du texte tapé : position, forme brute et forme normalisée. */
internal class Word(val start: Int, val end: Int, val raw: String) {

    /**
     * Signes d'ouverture « ¿ » et « ¡ » collés au début du mot (espagnol : « ¿como »). Ils ne font pas partie du mot : [core]
     * est « como » ; la règle qui remplace le mot les remet devant. Vide dans les autres langues.
     */
    val leading: String = raw.takeWhile { it == '¿' || it == '¡' }

    /** Ponctuation collée à la fin du mot (« la, »), ou null. */
    val trailing: Char? = raw.lastOrNull()?.takeIf { it in TRAILING }

    private val bare: String = (if (trailing != null) raw.dropLast(1) else raw).drop(leading.length)

    /** Le mot ne contient que des lettres, des apostrophes et des traits d'union (pas de chiffre, de guillemet...). */
    val isClean: Boolean = bare.isNotEmpty() && bare.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }

    /** Le mot en minuscules, sans ponctuation de bord, avec une apostrophe droite (« qu'ils »). */
    val core: String = bare.lowercase().replace('’', '\'')

    /** Le mot après l'élision (« qu'ils » -> « ils »). */
    val head: String = core.substringAfterLast('\'')

    val endsSentence: Boolean = trailing != null && trailing in SENTENCE_END

    val isCapitalized: Boolean = bare.firstOrNull()?.isUpperCase() == true

    /** « ON », « SONT » : un mot en capitales n'est jamais touché. */
    val isAllCaps: Boolean = bare.length > 1 && bare.any { it.isLetter() } && bare.filter { it.isLetter() }.all { it.isUpperCase() }

    private companion object {
        const val TRAILING = ",;:.!?…"
        const val SENTENCE_END = ".!?…"
    }
}

/**
 * Une confusion de mots courante (phase 4) : [typed] doit devenir [replacement] **si et seulement si** le contexte le
 * confirme. Une règle sans contexte est refusée (sauf [always], pour une forme qui n'existe pas, comme « d'ou »).
 *
 * Conditions (toutes celles qui sont données doivent être vraies) :
 * - [previous] : le mot qui précède, dans la même phrase et sans ponctuation entre les deux (on peut sauter les mots de
 *   [skip], « ne » par exemple : « ils ne son pas ») ;
 * - [previous2] : le mot avant celui-là ;
 * - [next] : le mot qui suit. La règle attend alors que ce mot soit tapé (correction « à rebours », comme a / à et ou / où) ;
 * - [sentenceStart] : le mot commence une phrase ;
 * - [self] : condition sur le mot tapé lui-même (par exemple « il suit un ¿ ») ;
 * - [punctAfter] : le mot est directement suivi d'une ponctuation (« reste la, je reviens »). Même attente que [next].
 *
 * Voir [FrenchConfusions] pour la table, les garde-fous et la liste de ce qui n'est volontairement pas couvert.
 */
@Suppress("LongParameterList")
internal class ConfusionRule(
    val typed: String,
    val replacement: String,
    val name: String,
    val previous: WordTest? = null,
    val previous2: WordTest? = null,
    val next: WordTest? = null,
    val sentenceStart: Boolean = false,
    val skip: Set<String> = emptySet(),
    val punctAfter: Boolean = false,
    val always: Boolean = false,
    /** Condition sur le mot tapé lui-même (espagnol : il ouvre une question, « ¿como »). */
    val self: WordTest? = null,
    /** Condition sur le deuxième mot qui suit (allemand : « wurde gern mitkommen »). Demande [next] ; la règle attend ce deuxième mot. */
    val next2: WordTest? = null,
    /** Le mot qui précède est directement suivi d'une virgule (allemand : « Ich weiß, das du... »). Demande [previous]. */
    val afterComma: Boolean = false,
) {
    init {
        require(always || previous != null || previous2 != null || next != null || sentenceStart || punctAfter || self != null) {
            "La règle « $name » n'a aucun contexte"
        }
    }

    /** Vrai si la règle doit voir le mot suivant : elle s'applique au mot précédent, dès que le mot suivant est tapé. */
    val needsNextWord: Boolean = next != null || punctAfter

    /** Nombre de mots que la règle attend après le mot qu'elle remplace : 0 (le dernier mot), 1 ([next], [punctAfter]) ou 2 ([next2]). */
    val lookahead: Int = if (next2 != null) 2 else if (needsNextWord) 1 else 0
}

/**
 * Homophones et confusions courantes du français, par table déclarative (phase 4) : ce / se, on / ont, son / sont,
 * ces / c'est / s'est, leur / leurs, ça / sa, et / est, peu / peut, la / là, a / à, d'où. Chaque ligne de [RULES] dit
 * **quel mot, remplacé par quoi, dans quel contexte** ; le moteur ([fix]) ne connaît aucune règle en particulier.
 *
 * Principe : sans contexte sûr, on ne corrige pas. Une règle s'appuie sur un mot qui rend l'autre lecture impossible
 * (« ils on » : aucun « on » ne suit « ils » ; « il ce lève » : « ce » ne suit pas un pronom sujet). Les règles testées
 * contre les phrases justes du banc (tools/autocorrect-bench/sentences-ok.txt) et contre un lot de phrases difficiles
 * (`FrenchConfusionsTest`).
 *
 * Volontairement non couvert : ces / ses (le sens seul décide), « Marie est Paul » (il faudrait reconnaître deux prénoms),
 * « par la » en fin de texte (« par la fenêtre » est juste, la suite n'est pas encore connue), le participe après « de »
 * (« fini de travaillé »). Les règles a / à, ou / où, la / là et participes de [FrenchContextCorrector] restent telles
 * quelles : seuls les cas qu'elles manquaient sont ajoutés ici.
 *
 * Les règles à mot suivant ne s'appliquent que si la correction à rebours est permise (pas juste après une annulation).
 */
internal object FrenchConfusions {

    /**
     * Applique les règles à la fin de [text] (le texte avant le curseur, sans la touche qui termine le mot) : d'abord au mot
     * précédent, revu avec le mot qui vient d'être tapé ([allowRetro]), puis au mot courant. Renvoie le texte, corrigé ou non.
     */
    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Le moteur de [fix] avec une autre table de règles (celle de [EnglishConfusions]) : il ne connaît aucune règle en particulier. */
    internal fun fixWith(
        rules: List<ConfusionRule>,
        text: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        frequencyOf: (String) -> Long,
    ): String {
        var current = text
        for (offset in intArrayOf(2, 1, 0)) {
            if (offset >= 1 && !allowRetro) continue
            val words = tokenize(current)
            val index = words.lastIndex - offset
            if (index < 0) continue
            val word = words[index]
            val rule = rules.firstOrNull { matches(it, words, index, textMayBeTruncated, frequencyOf) } ?: continue
            val replacement = if (word.isCapitalized) rule.replacement.replaceFirstChar { it.uppercaseChar() } else rule.replacement
            current = current.substring(0, word.start) + word.leading + replacement + (word.trailing?.toString() ?: "") + current.substring(word.end)
        }
        return current
    }

    /** Vrai si [rule] s'applique au mot words[index]. */
    internal fun matches(
        rule: ConfusionRule,
        words: List<Word>,
        index: Int,
        textMayBeTruncated: Boolean,
        frequencyOf: (String) -> Long,
    ): Boolean {
        val word = words[index]
        if (!word.isClean || word.isAllCaps || word.core != rule.typed) return false
        // Une règle à mot suivant ne regarde que l'avant-dernier mot, une règle à deux mots suivants l'antépénultième ; les autres, que le dernier.
        if (index != words.lastIndex - rule.lookahead) return false
        if (rule.punctAfter) {
            if (word.trailing == null) return false
        } else if (word.trailing != null) {
            return false
        }
        if (rule.always) return true
        if (rule.sentenceStart) {
            val start = if (index == 0) !textMayBeTruncated else words[index - 1].endsSentence
            if (!start) return false
        }
        if (rule.self != null && !rule.self.invoke(word, frequencyOf)) return false
        return previousMatches(rule, words, index, frequencyOf) && nextMatches(rule, words, index, frequencyOf)
    }

    /** Les conditions [ConfusionRule.previous] et [ConfusionRule.previous2] : mots collés à leur voisin, sans ponctuation entre eux. */
    private fun previousMatches(rule: ConfusionRule, words: List<Word>, index: Int, frequencyOf: (String) -> Long): Boolean {
        if (rule.previous == null && rule.previous2 == null) return true
        val j = previousIndex(words, index, rule.skip)
        if (j < 0) return false
        val previous = words[j]
        if (!previous.isClean) return false
        if (previous.trailing != null && !(rule.afterComma && previous.trailing == ',')) return false
        if (rule.previous != null && !rule.previous.invoke(previous, frequencyOf)) return false
        if (rule.previous2 == null) return true
        val k = previousIndex(words, j, emptySet())
        if (k < 0) return false
        val before = words[k]
        return before.isClean && before.trailing == null && rule.previous2.invoke(before, frequencyOf)
    }

    /** La condition [ConfusionRule.next] : le mot suivant existe, ne contient que des lettres, et passe le test. */
    private fun nextMatches(rule: ConfusionRule, words: List<Word>, index: Int, frequencyOf: (String) -> Long): Boolean {
        if (rule.next == null) return true
        val following = words[index + 1]
        if (!following.isClean || !rule.next.invoke(following, frequencyOf)) return false
        if (rule.next2 == null) return true
        // Deuxième mot suivant : pas de ponctuation entre les deux mots qui suivent.
        val second = words.getOrNull(index + 2) ?: return false
        return following.trailing == null && second.isClean && rule.next2.invoke(second, frequencyOf)
    }

    /** L'indice du mot qui précède words[index], en sautant les mots de [skip] ; -1 s'il n'y en a pas. */
    private fun previousIndex(words: List<Word>, index: Int, skip: Set<String>): Int {
        var j = index - 1
        while (j >= 0 && words[j].isClean && words[j].trailing == null && words[j].core in skip) j--
        return j
    }

    private fun tokenize(text: String): List<Word> {
        val words = ArrayList<Word>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            words.add(Word(start, i, text.substring(start, i)))
        }
        return words
    }

    // ------------------------------------------------------------------
    // Tests de mots
    // ------------------------------------------------------------------

    /** Le mot (ou le mot après son élision : « qu'ils » -> « ils ») est l'un de [words]. */
    internal fun oneOf(vararg words: String): WordTest {
        val set = words.toSet()
        return { w, _ -> w.core in set || ('\'' in w.core && w.head in set) }
    }

    /** Le mot n'est aucun de [words] (même élision comprise). */
    internal fun noneOf(vararg words: String): WordTest {
        val test = oneOf(*words)
        return { w, f -> !test(w, f) }
    }

    private fun coreIn(words: Set<String>): WordTest = { w, _ -> w.core in words }

    internal fun anyOf(vararg tests: WordTest): WordTest = { w, f -> tests.any { it(w, f) } }

    internal fun allOf(vararg tests: WordTest): WordTest = { w, f -> tests.all { it(w, f) } }

    internal fun negate(test: WordTest): WordTest = { w, f -> !test(w, f) }

    /** Participe passé probable : en -é / -ée (4 lettres ou plus) ou participe irrégulier courant. */
    private val participleLike: WordTest = { w, _ ->
        val c = w.core
        c in IRREGULAR_PARTICIPLES || (c.length >= 4 && '\'' !in c && (c.endsWith("é") || c.endsWith("ée")))
    }

    /** Mot en -é / -ée (4 lettres ou plus) : participe ou adjectif (« fatigué », « arrivée »). */
    private val eAcute: WordTest = { w, _ ->
        val c = w.core
        c.length >= 4 && '\'' !in c && (c.endsWith("é") || c.endsWith("ée"))
    }

    /** Infinitif probable : 4 lettres ou plus, en -er, -ir ou -re (sauf « être »). */
    private val infinitiveLike: WordTest = { w, _ ->
        val c = w.core
        c.length >= 4 && '\'' !in c && (c.endsWith("er") || c.endsWith("ir") || c.endsWith("re")) && c != "être"
    }

    /** Mot au pluriel probable, sans regarder les listes : 4 lettres ou plus, en -s ou -x, hors adverbes et pronoms en -s. */
    private val pluralWord: WordTest = { w, _ ->
        val c = w.core
        c.length >= 4 && '\'' !in c && (c.endsWith("s") || c.endsWith("x")) && c !in ADVERBS_IN_S
    }

    /**
     * Nom pluriel probable : son singulier est un vrai mot courant, et pas bien plus rare que lui (« clés » -> « clé »,
     * « maisons » -> « maison » ; « avis » n'est pas le pluriel d'« avi »). Hors formes verbales en -s (« dis », « fais »).
     */
    private val pluralNoun: WordTest = { w, f ->
        val c = w.core
        if (c.length < 4 || '\'' in c || c in VERB_FORMS_IN_S) {
            false
        } else if (c.endsWith("s")) {
            val stem = f(c.dropLast(1))
            stem >= FrenchContextCorrector.MIN_FREQUENCY && stem * 3 >= f(c)
        } else {
            c.endsWith("x") && c.length >= 5 && f(c) >= FrenchContextCorrector.MIN_FREQUENCY
        }
    }

    // ------------------------------------------------------------------
    // Données
    // ------------------------------------------------------------------

    private val SUBJECTS_PLURAL = arrayOf("ils", "elles")
    private val SUBJECTS_SINGULAR = arrayOf("il", "elle", "on")

    /** Sujets, clitiques et négations après lesquels « leur » est un pronom (« je leur dis », « qu'on leur dit »). */
    private val BEFORE_PRONOUN_LEUR = arrayOf(
        "je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles", "ne", "qui", "que", "y", "en", "me", "te", "se",
        "lui", "le", "la", "les", "ce", "ça", "tout", "pas", "plus", "jamais", "rien", "j", "n", "m", "t", "s", "l", "c", "qu",
    )

    private val IRREGULAR_PARTICIPLES = setOf(
        "fini", "eu", "été", "fait", "dit", "pris", "vu", "mis", "su", "pu", "voulu", "dû", "parti", "partis", "venu", "vendu",
        "perdu", "lu", "bu", "reçu", "fatigué",
    )

    private val ADVERBS_IN_S = setOf(
        "nous", "vous", "tous", "plus", "mais", "alors", "donc", "jamais", "toujours", "parfois", "souvent", "ailleurs", "dans",
        "sous", "après", "depuis", "puis", "très", "pas", "aussi", "encore", "hélas", "dès", "vers", "avec", "sans", "chez", "lors",
    )

    private val VERB_FORMS_IN_S = setOf("fais", "dois", "veux", "vois", "prends", "tiens", "viens", "sais", "peux", "mets", "suis", "donnes", "dits", "dites")

    /** Adjectifs qui suivent « est » (« il est fatigué »), jamais une conjonction « et » juste après un sujet. */
    private val ADJECTIVES = setOf(
        "intéressant", "intéressante", "beau", "belle", "bon", "bonne", "grand", "grande", "petit", "petite", "facile", "difficile",
        "simple", "possible", "impossible", "vrai", "vraie", "faux", "normal", "normale", "fini", "finie", "terminé", "terminée",
        "fermé", "fermée", "ouvert", "ouverte", "prêt", "prête", "content", "contente", "fatigué", "fatiguée", "malade", "vide",
        "plein", "pleine", "gentil", "gentille", "drôle", "utile", "inutile", "important", "importante", "cher", "chère", "rapide",
        "lent", "lente", "clair", "claire", "sûr", "sûre", "parti", "partie", "venu", "venue", "arrivé", "arrivée", "mort", "morte",
        "né", "née", "chaud", "chaude", "froid", "froide",
    )

    /** Adjectifs qui précèdent souvent le nom : « le grand et beau château » n'est pas « le grand est beau ». */
    private val PRENOMINAL_ADJECTIVES = setOf(
        "grand", "grande", "petit", "petite", "gros", "grosse", "beau", "belle", "bon", "bonne", "mauvais", "mauvaise", "vieux",
        "vieille", "jeune", "nouveau", "nouvelle", "joli", "jolie", "long", "longue", "haut", "haute", "vrai", "vraie", "faux",
        "autre", "même", "premier", "première", "dernier", "dernière", "seul", "seule", "pauvre", "cher", "chère", "pire",
        "meilleur", "meilleure",
    )

    private val DETERMINERS_SINGULAR = arrayOf("ce", "cet", "cette", "le", "la", "un", "une", "mon", "ma", "ton", "ta", "son", "sa", "notre", "votre")

    /** Noms masculins singuliers qu'un « son » possessif précède (« son portable »). */
    private val OBJECT_NOUNS = arrayOf(
        "portable", "téléphone", "nom", "prénom", "chien", "chat", "sac", "travail", "livre", "ordinateur", "bureau", "jardin",
        "cheval", "vélo", "patron", "avis", "âge",
    )

    /** Noms de parenté : « son père », mais « ce sont père et fils » existe, d'où des conditions plus strictes. */
    private val KIN_NOUNS = arrayOf(
        "père", "mère", "frère", "sœur", "soeur", "fils", "fille", "ami", "amie", "mari", "femme", "oncle", "tante", "cousin", "cousine",
    )

    /** Noms qu'un « ce » démonstratif précède (« ce livre », « ce soir »). « livre » aussi est un verbe : voir la règle après préposition. */
    private val DEMONSTRATIVE_NOUNS = arrayOf(
        "livre", "film", "soir", "matin", "jour", "week-end", "weekend", "monsieur", "garçon", "type", "mec", "texte", "message",
        "projet", "travail", "site", "jeu", "moment", "lieu", "point", "sujet", "genre",
    )

    /** Noms de la famille et de la maison qu'un « sa » possessif précède, jamais un « ça » (« ça mère » n'existe pas). */
    private val SA_NOUNS = arrayOf(
        "mère", "soeur", "sœur", "femme", "fille", "maison", "voiture", "famille", "copine", "amie", "tante", "cousine", "mamie",
        "grand-mère", "belle-mère", "fierté",
    )

    /** Verbes et pronoms qui suivent « ça » et jamais « sa » : « ça va », « ça suffit », « ça me plaît ». */
    private val CA_FOLLOWERS = arrayOf(
        "va", "fait", "suffit", "dépend", "existe", "importe", "semble", "convient", "vient", "peut", "doit", "me", "te", "nous", "vous", "lui", "y",
    )

    private val PRONOUNS_AFTER_VERB = arrayOf("te", "me", "lui", "vous", "nous", "leur")

    private val PREPOSITIONS_BEFORE_SE_NOUN = arrayOf("dans", "pour", "avec", "sur", "par", "sans", "à", "de")

    private val POSSESSIVES_AND_PRONOUNS_AFTER_DIT = arrayOf(
        "son", "sa", "ses", "mon", "ma", "mes", "ton", "ta", "tes", "notre", "nos", "votre", "vos", "leur", "leurs", "lui", "elle",
        "eux", "elles", "moi", "toi", "nous", "vous",
    )

    /** Participes de verbes qui prennent « à » + personne : « il l'a dit à son frère ». */
    private val SPEECH_PARTICIPLES = arrayOf(
        "dit", "donné", "parlé", "écrit", "répondu", "envoyé", "offert", "montré", "prêté", "demandé", "expliqué", "raconté",
        "téléphoné", "souri",
    )

    private val ADJECTIVES_BEFORE_A_INFINITIVE = arrayOf(
        "facile", "difficile", "simple", "impossible", "prêt", "prête", "prêts", "lent", "long", "dur", "bon", "beau",
    )

    /** Jours qu'un participe suivi de « à » annonce (« reporté à demain »). */
    private val DAYS_AFTER_A = arrayOf(
        "demain", "après-demain", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche",
    )

    private val NOUNS_BEFORE_A_INFINITIVE =arrayOf("travail", "temps", "courses", "devoirs", "linge", "vaisselle", "ménage", "boulot", "trucs", "truc")

    private val BE_FORMS_FOR_LA = arrayOf("suis", "es", "est", "sommes", "êtes", "sont", "était", "étais", "sera", "serai")

    private val VERBS_BEFORE_LA_PUNCT = arrayOf(
        "reste", "restes", "restez", "restons", "suis", "es", "est", "sommes", "êtes", "sont", "par", "de", "viens", "vient", "venez",
        "était", "étais", "sera", "serai", "attends", "attend", "attendez",
    )

    // ------------------------------------------------------------------
    // La table
    // ------------------------------------------------------------------

    /** Les règles, dans l'ordre : la première qui correspond s'applique. Une ligne = un mot, son remplaçant, son contexte. */
    internal val RULES: List<ConfusionRule> = listOf(
        // ce / se : « il ce lève » (un pronom sujet n'est jamais suivi de « ce »), « se livre est » (« se » ne commence pas une phrase).
        ConfusionRule(
            "ce", "se", "ce > se après un sujet",
            previous = oneOf(*SUBJECTS_PLURAL, *SUBJECTS_SINGULAR), skip = setOf("ne"),
            next = noneOf(
                "soir", "soir-là", "matin", "matin-là", "jour-là", "midi", "weekend", "week-end", "jour", "mois", "an", "après-midi",
                "que", "qui", "dont",
            ),
        ),
        ConfusionRule("se", "ce", "se > ce en début de phrase", sentenceStart = true, next = oneOf(*DEMONSTRATIVE_NOUNS)),
        ConfusionRule(
            "se", "ce", "se > ce après une préposition",
            previous = oneOf(*PREPOSITIONS_BEFORE_SE_NOUN), next = oneOf(*DEMONSTRATIVE_NOUNS.filter { it != "livre" }.toTypedArray()),
        ),
        // on / ont
        ConfusionRule("on", "ont", "on > ont après ils / elles", previous = oneOf(*SUBJECTS_PLURAL)),
        ConfusionRule(
            "on", "ont", "on > ont après un nom pluriel",
            previous = pluralWord,
            next = anyOf(participleLike, oneOf("faim", "soif", "peur", "froid", "chaud", "mal", "raison", "tort", "besoin", "envie", "honte", "sommeil")),
        ),
        ConfusionRule(
            "ont", "on", "ont > on devant un verbe au singulier",
            next = oneOf("y", "se", "ne", "va", "vient", "peut", "doit", "sait", "voit", "part", "verra", "pourra", "dira", "fera", "ira", "aura", "sera"),
        ),
        // son / sont
        ConfusionRule("son", "sont", "son > sont après ils / elles", previous = oneOf(*SUBJECTS_PLURAL), skip = setOf("ne")),
        ConfusionRule("sont", "son", "sont > son en début de phrase", sentenceStart = true, next = oneOf(*OBJECT_NOUNS, *KIN_NOUNS)),
        ConfusionRule(
            "sont", "son", "sont > son devant un objet",
            previous = allOf(noneOf("ce", "qui", "ils", "elles", "ceux", "celles"), negate(pluralWord)), next = oneOf(*OBJECT_NOUNS),
        ),
        ConfusionRule(
            "sont", "son", "sont > son devant un parent",
            previous = allOf(noneOf("ce", "qui", "ils", "elles", "ceux", "celles"), participleLike), next = oneOf(*KIN_NOUNS),
        ),
        // ces / c'est / s'est
        ConfusionRule("ces", "c'est", "ces > c'est", next = oneOf("un", "une", "mon", "ma", "ton", "ta", "vrai", "faux", "à")),
        ConfusionRule("c'est", "s'est", "c'est > s'est", previous = oneOf(*SUBJECTS_SINGULAR), next = eAcute),
        ConfusionRule("s'est", "c'est", "s'est > c'est", sentenceStart = true, next = oneOf("un", "une", "à", "vrai", "faux", "mon", "ma", "ton", "ta")),
        // leur / leurs
        ConfusionRule("leur", "leurs", "leur > leurs", previous = noneOf(*BEFORE_PRONOUN_LEUR), next = pluralNoun),
        ConfusionRule(
            "leurs", "leur", "leurs > leur après un sujet",
            previous = oneOf("je", "tu", "il", "elle", "on", "nous", "vous", "ils", "elles"),
        ),
        // ça / sa
        ConfusionRule("sa", "ça", "sa > ça", next = oneOf(*CA_FOLLOWERS)),
        ConfusionRule("ça", "sa", "ça > sa", next = oneOf(*SA_NOUNS)),
        // et / est
        ConfusionRule("et", "est", "et > est après un sujet", previous = oneOf(*SUBJECTS_SINGULAR), next = anyOf(coreIn(ADJECTIVES), eAcute)),
        ConfusionRule(
            "et", "est", "et > est après un nom",
            previous = allOf(negate(coreIn(ADJECTIVES)), negate(coreIn(PRENOMINAL_ADJECTIVES))),
            previous2 = oneOf(*DETERMINERS_SINGULAR), next = coreIn(ADJECTIVES),
        ),
        ConfusionRule(
            "est", "et", "est > et entre deux adjectifs",
            previous = coreIn(ADJECTIVES), previous2 = oneOf("fait", "très"), next = coreIn(ADJECTIVES),
        ),
        // peu / peut
        ConfusionRule("peu", "peut", "peu > peut après un sujet", previous = oneOf(*SUBJECTS_SINGULAR, "ça"), skip = setOf("ne")),
        ConfusionRule("peut", "peu", "peut > peu après un adverbe", previous = oneOf("très", "trop", "assez", "si", "un")),
        ConfusionRule("peut", "peu", "peut > peu devant de", previous = oneOf("a"), next = oneOf("de", "d'")),
        // la / là
        ConfusionRule("la", "là", "la > là avant une ponctuation", previous = oneOf(*VERBS_BEFORE_LA_PUNCT), punctAfter = true),
        ConfusionRule(
            "la", "là", "la > là après « n'est pas »",
            previous = oneOf(*BE_FORMS_FOR_LA), skip = setOf("pas", "plus", "jamais"),
            next = oneOf("aujourd'hui", "hier", "demain", "depuis", "pour", "avec", "ce", "ça"),
        ),
        // d'où : « d'ou » n'existe pas.
        ConfusionRule("d'ou", "d'où", "d'ou > d'où", always = true),
        // a / à
        ConfusionRule("a", "à", "a > à devant midi", next = oneOf("midi", "minuit")),
        // « mangé a demain », « reporté a lundi » : après un participe, « a » + jour ne peut pas être le verbe avoir. Pas
        // « bientôt » ni « plus » (« le projet lancé a bientôt dix ans », « le prix fixé a plus que doublé »).
        ConfusionRule("a", "à", "a > à après un participe, devant un jour", previous = participleLike, next = oneOf(*DAYS_AFTER_A)),
        ConfusionRule("a", "à", "a > à après un adjectif", previous = oneOf(*ADJECTIVES_BEFORE_A_INFINITIVE), next = infinitiveLike),
        ConfusionRule("a", "à", "a > à après rien", previous = oneOf("rien"), next = oneOf(*PRONOUNS_AFTER_VERB)),
        ConfusionRule(
            "a", "à", "a > à après quelque chose",
            previous = oneOf("chose"), previous2 = oneOf("quelque", "autre"), next = oneOf(*PRONOUNS_AFTER_VERB),
        ),
        ConfusionRule("a", "à", "a > à après un nom de tâche", previous = oneOf(*NOUNS_BEFORE_A_INFINITIVE), next = infinitiveLike),
        ConfusionRule("a", "à", "a > à après dire, donner...", previous = oneOf(*SPEECH_PARTICIPLES), next = oneOf(*POSSESSIVES_AND_PRONOUNS_AFTER_DIT)),
    )
}
