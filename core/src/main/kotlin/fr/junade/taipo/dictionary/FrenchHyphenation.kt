package fr.junade.taipo.dictionary

/**
 * Traits d'union oubliés en français (phase 1 de la refonte) : « est ce que » -> « est-ce que », « va t il » -> « va-t-il »,
 * « dis moi » -> « dis-moi », « rendez vous » -> « rendez-vous », « aujourd hui » -> « aujourd'hui ».
 *
 * Le dictionnaire ne voit qu'un mot à la fois : chaque morceau (« est », « ce ») est un mot juste. Comme les règles de
 * [FrenchContextCorrector], celles-ci se déclenchent quand le DERNIER mot de l'expression vient d'être tapé et regardent
 * la fin du texte. Volontairement prudentes : une expression n'est corrigée que si elle ne peut pas s'écrire autrement.
 *
 * - **Locutions figées** ([FIXED]) : « au dessus », « celui ci », « grand mère », « week end »...
 * - **Est-ce** : « est ce que / qu'il / qui » au début d'une question (début de phrase, après « qu' », « où », « pourquoi »...),
 *   « n'est ce pas ». Jamais après un sujet (« il est ce que je pensais » reste intact).
 * - **-t- euphonique** : une lettre « t » seule entre un verbe en a / e / c et « il », « elle », « on » (« a t il » -> « a-t-il »).
 * - **Impératif + pronom** : « moi », « toi », « lui » derrière un impératif de la liste [IMPERATIVES], en début de proposition
 *   (« Dis moi », « Dépêche toi »). Pas « le », « la », « nous », « vous » : « Fais le ménage », « Écoutez vous êtes... ».
 *
 * Non couvert (trop ambigu sans le mot suivant) : « dit il » / « répondit elle » (« je lui ai dit il y a longtemps »),
 * inversions sans -t- (« est il », « peut elle »), « peut être » en deux mots (« il peut être grand »).
 * Logique pure (sans Android), testée en JVM.
 */
object FrenchHyphenation {

    private const val BOUNDARY = "(?<![\\p{L}'-])"
    private val IGNORE_CASE = setOf(RegexOption.IGNORE_CASE)

    /**
     * Corrige la fin de [text] (le texte avant le curseur, qui se termine par le mot qui vient d'être tapé). Renvoie le
     * texte entier corrigé, ou null s'il n'y a rien à changer. [textMayBeTruncated] : [text] peut commencer au milieu
     * d'une phrase (le début n'est alors pas un début de proposition).
     */
    fun fix(text: String, textMayBeTruncated: Boolean): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val norm = FrenchCanonicalizer.normalizeApostrophes(text)
        // Un seul morceau de texte change à la fois (le plus à droite qui correspond), comme les autres règles de contexte.
        return fixed(text, norm)
            ?: estCe(text, norm, textMayBeTruncated)
            ?: euphonicT(text, norm)
            ?: imperative(text, norm, textMayBeTruncated)
    }

    // ------------------------------------------------------------------
    // Locutions figées
    // ------------------------------------------------------------------

    private class Fixed(val regex: Regex, val result: String)

    /** Dernier mot (en minuscules) de la locution -> règles qui s'y terminent : on n'évalue que celles-là à chaque mot. */
    private val FIXED_BY_LAST_WORD: Map<String, List<Fixed>> = HashMap<String, MutableList<Fixed>>().also { index ->
        for ((inputs, result) in FIXED_ENTRIES) {
            for (input in inputs) {
                val words = input.split(' ')
                val pattern = BOUNDARY + words.joinToString("[ ]") { Regex.escape(it) } + "\\z"
                index.getOrPut(words.last().lowercase()) { ArrayList() } += Fixed(Regex(pattern, IGNORE_CASE), result)
            }
        }
    }

    private fun fixed(text: String, norm: String): String? {
        val last = norm.substring(norm.lastIndexOfAny(WHITESPACE) + 1).lowercase()
        val rules = FIXED_BY_LAST_WORD[last] ?: return null
        for (rule in rules) {
            val match = rule.regex.find(norm) ?: continue
            val start = match.range.first
            var result = if (text[start].isUpperCase()) rule.result.replaceFirstChar { it.uppercaseChar() } else rule.result
            // L'apostrophe tapée par l'utilisateur est conservée (« c’est a dire » -> « c’est-à-dire »).
            val typed = text.substring(start)
            val apostrophe = typed.firstOrNull { it != '\'' && norm[start + typed.indexOf(it)] == '\'' }
            if (apostrophe != null) result = result.replace('\'', apostrophe)
            return text.substring(0, start) + result
        }
        return null
    }

    // ------------------------------------------------------------------
    // Est-ce que
    // ------------------------------------------------------------------

    private val EST_CE = Regex("$BOUNDARY((?:qu'|n')?)(est) (ce) (que|qu'\\p{L}+|pas|qui)\\z", IGNORE_CASE)

    private fun estCe(text: String, norm: String, truncated: Boolean): String? {
        val match = EST_CE.find(norm) ?: return null
        val pre = match.groupValues[1].lowercase()
        val next = match.groupValues[4].lowercase()
        if (pre == "n'") {
            if (next != "pas") return null
        } else {
            if (next == "pas") return null
            // Sans « qu' » : seulement en début de question (« Est ce que tu viens », « Où est ce que... »), jamais après un sujet.
            if (pre.isEmpty() && !startsQuestion(norm.substring(0, match.range.first), truncated)) return null
        }
        val start = match.groups[2]!!.range.first
        return text.substring(0, start) + text.substring(start).replaceFirst(' ', '-')
    }

    private fun startsQuestion(before: String, truncated: Boolean): Boolean {
        if (startsClause(before, truncated)) return true
        val lastWord = before.trimEnd().substringAfterLast(' ').lowercase()
        return lastWord in QUESTION_WORDS
    }

    // ------------------------------------------------------------------
    // -t- euphonique
    // ------------------------------------------------------------------

    private val EUPHONIC_T = Regex("$BOUNDARY(\\p{L}*[aec]) t (il|elle|on)\\z", IGNORE_CASE)

    private fun euphonicT(text: String, norm: String): String? {
        val match = EUPHONIC_T.find(norm) ?: return null
        val start = match.groups[1]!!.range.first
        return text.substring(0, start) + text.substring(start).replace(' ', '-')
    }

    // ------------------------------------------------------------------
    // Impératif + moi / toi / lui
    // ------------------------------------------------------------------

    private val IMPERATIVE = Regex(
        "$BOUNDARY(${IMPERATIVES.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }}) (moi|toi|lui)\\z",
        IGNORE_CASE,
    )

    private fun imperative(text: String, norm: String, truncated: Boolean): String? {
        val match = IMPERATIVE.find(norm) ?: return null
        val start = match.range.first
        if (!startsClause(norm.substring(0, start), truncated)) return null
        return text.substring(0, start) + text.substring(start).replaceFirst(' ', '-')
    }

    // ------------------------------------------------------------------
    // Début de proposition
    // ------------------------------------------------------------------

    /** Vrai si [before] (le texte qui précède) est vide au début du texte, ou finit une phrase ou une proposition. */
    private fun startsClause(before: String, truncated: Boolean): Boolean {
        val trimmed = before.trimEnd()
        if (trimmed.isEmpty()) return !truncated || before.contains('\n')
        if (before.length > trimmed.length && before.substring(trimmed.length).contains('\n')) return true
        return trimmed.last() in CLAUSE_END
    }

    private val WHITESPACE = charArrayOf(' ', '\n', '\t', ' ', ' ')

    private const val CLAUSE_END = ".!?…,;:«\"(—"

    /**
     * Mots interrogatifs qui ouvrent une question : « où est ce que ». Pas « qui », « que », « et »... : « ce qui est ce qu'on
     * attendait » n'est pas une question.
     */
    private val QUESTION_WORDS = setOf("où", "pourquoi", "comment", "quand", "combien")
}

// ----------------------------------------------------------------------
// Données
// ----------------------------------------------------------------------

/**
 * Locutions qui s'écrivent toujours avec des traits d'union : formes tapées (avec ou sans accent) -> écriture juste.
 * La casse de la première lettre est reprise.
 */
private val FIXED_ENTRIES: List<Pair<List<String>, String>> = listOf(
    listOf("rendez vous") to "rendez-vous",
    listOf("avant hier") to "avant-hier",
    listOf("après demain", "apres demain") to "après-demain",
    listOf("au dessus") to "au-dessus",
    listOf("au dessous") to "au-dessous",
    listOf("au delà", "au dela") to "au-delà",
    listOf("par dessus") to "par-dessus",
    listOf("par dessous") to "par-dessous",
    listOf("là dessus", "la dessus") to "là-dessus",
    listOf("là dessous", "la dessous") to "là-dessous",
    listOf("ci dessus") to "ci-dessus",
    listOf("ci dessous") to "ci-dessous",
    listOf("celui ci", "celui là", "celui la") to "celui-ci",
    listOf("celle ci") to "celle-ci",
    listOf("ceux ci") to "ceux-ci",
    listOf("celles ci") to "celles-ci",
    listOf("là bas", "la bas") to "là-bas",
    listOf("là haut", "la haut") to "là-haut",
    listOf("grand mère", "grand mere") to "grand-mère",
    listOf("grand mères", "grand meres") to "grand-mères",
    listOf("grand père", "grand pere") to "grand-père",
    listOf("grand pères", "grand peres") to "grand-pères",
    listOf("grands parents") to "grands-parents",
    listOf("quelques uns") to "quelques-uns",
    listOf("quelques unes") to "quelques-unes",
    listOf("week end") to "week-end",
    listOf("sous sol") to "sous-sol",
    listOf("arc en ciel") to "arc-en-ciel",
    listOf("c'est à dire", "c'est a dire") to "c'est-à-dire",
    listOf("vis à vis", "vis a vis") to "vis-à-vis",
    listOf("peutêtre", "peutetre") to "peut-être",
    listOf("aujourd hui") to "aujourd'hui",
)

/** Impératifs (2e personne du singulier et du pluriel) qui prennent « -moi », « -toi », « -lui » : toujours avec trait d'union. */
private val IMPERATIVES: List<String> = listOf(
    "dis", "dites", "donne", "donnez", "rends", "rendez", "prête", "prêtez", "montre", "montrez", "apporte", "apportez",
    "envoie", "envoyez", "explique", "expliquez", "répète", "répétez", "raconte", "racontez", "parle", "parlez",
    "écris", "écrivez", "laisse", "laissez", "aide", "aidez", "appelle", "appelez", "rappelle", "rappelez", "passe", "passez",
    "fais", "faites", "regarde", "regardez", "écoute", "écoutez", "crois", "croyez", "excuse", "excusez", "pardonne",
    "pardonnez", "dépêche", "dépêchez", "calme", "calmez", "lève", "levez", "assieds", "asseyez", "occupe", "occupez",
    "réveille", "réveillez", "souviens", "souvenez", "sers", "servez", "amuse", "amusez", "préviens", "prévenez",
    "tiens", "tenez", "réponds", "répondez", "sauve", "sauvez", "repose", "reposez", "prends", "prenez", "viens", "venez",
    "suis", "suivez", "ramène", "ramenez", "offre", "offrez", "achète", "achetez",
)
