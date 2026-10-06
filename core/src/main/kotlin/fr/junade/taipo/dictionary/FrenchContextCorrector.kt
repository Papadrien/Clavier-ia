package fr.junade.taipo.dictionary

/**
 * Autocorrection du français qui tient compte des mots voisins (le dictionnaire, lui, ne voit qu'un mot à la
 * fois : « a » et « à », « manger » et « mangé » sont tous deux des mots corrects).
 *
 * Deux familles de corrections, volontairement prudentes (mieux vaut ne rien changer qu'abîmer un texte juste) :
 * - **a / à** : « a » devient « à » en début de phrase (« a demain »), après un verbe qui appelle « à » (« je vais a
 *   Paris »), après « jusqu' » (« jusqu'a »), et devant un infinitif quand ce qui précède exclut le verbe avoir
 *   (« quelque chose a manger », « j'ai a faire »). Ce dernier cas a besoin du mot suivant : il est donc revu quand
 *   ce mot vient d'être tapé (correction « à rebours » du mot précédent).
 * - **participe passé / infinitif** : un infinitif juste après un auxiliaire devient un participe (« il a manger »
 *   -> « il a mangé ») ; un participe en -é juste après un verbe ou une préposition qui appelle l'infinitif
 *   devient un infinitif (« il faut mangé » -> « il faut manger »).
 *
 * Non couvert : l'accord du participe (« elles sont parti »), les noms sujets (« le chat a manger » reste inchangé,
 * ce peut être « à » comme « a mangé »). Logique pure (sans Android), testée en JVM.
 */
object FrenchContextCorrector {

    /** Fréquence minimale, dans les listes, pour qu'une forme compte comme un vrai mot (les listes sont bruitées). */
    const val MIN_FREQUENCY = 300L

    /**
     * Corrige la fin de [text] (le texte avant le curseur, qui se termine par le mot qui vient d'être tapé, sans la
     * touche qui le termine). Renvoie le texte corrigé en entier, ou null s'il n'y a rien à changer.
     *
     * [textMayBeTruncated] : [text] est une fenêtre qui peut commencer au milieu d'une phrase (le début n'est alors
     * pas un début de phrase). [allowRetro] : autorise la révision du mot précédent à la lumière du mot qui
     * vient d'être tapé (désactivé juste après que l'utilisateur a annulé une correction).
     */
    fun correct(
        text: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        frequencyOf: (String) -> Long,
    ): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        var current = text
        current = fixAccentOnA(current, textMayBeTruncated, allowRetro, frequencyOf)
        current = fixParticiple(current, frequencyOf)
        return if (current == text) null else current
    }

    // ------------------------------------------------------------------
    // Découpage en mots
    // ------------------------------------------------------------------

    private class Token(val start: Int, val end: Int, val raw: String) {
        private val trimmed: String = raw.trim { !it.isLetter() && it != '\'' && it != '’' && it != '-' }

        /** Le mot en minuscules, sans ponctuation de bord, avec une apostrophe droite (« j'ai »). */
        val core: String = trimmed.lowercase().replace('’', '\'')

        /** Le mot après l'élision (« j'ai » -> « ai »). */
        val head: String = core.substringAfterLast('\'')

        val hasElision: Boolean = core.contains('\'')

        /** Aucune ponctuation collée au mot. */
        val isPlain: Boolean = raw == trimmed

        val endsSentence: Boolean = raw.isNotEmpty() && raw.last() in SENTENCE_END

        val isCapitalized: Boolean = raw.firstOrNull()?.isUpperCase() == true
    }

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            tokens.add(Token(start, i, text.substring(start, i)))
        }
        return tokens
    }

    private fun replaceToken(text: String, token: Token, replacement: String): String =
        text.substring(0, token.start) + replacement + text.substring(token.end)

    /** Le mot précédent [index], s'il est dans la même phrase. */
    private fun previousInSentence(tokens: List<Token>, index: Int): Token? =
        if (index > 0 && !tokens[index - 1].endsSentence) tokens[index - 1] else null

    private fun isSubjectLike(token: Token): Boolean = token.head in SUBJECTS || token.core in SUBJECTS

    // ------------------------------------------------------------------
    // a / à
    // ------------------------------------------------------------------

    private fun fixAccentOnA(
        text: String,
        textMayBeTruncated: Boolean,
        allowRetro: Boolean,
        frequencyOf: (String) -> Long,
    ): String {
        var current = text
        // D'abord le mot précédent (revu avec le mot qui vient d'être tapé), puis le mot courant.
        for (offset in intArrayOf(1, 0)) {
            if (offset == 1 && !allowRetro) continue
            val tokens = tokenize(current)
            val index = tokens.lastIndex - offset
            if (index < 0) continue
            val token = tokens[index]
            val next = if (offset == 1) tokens[index + 1] else null
            if (!token.isPlain) continue
            val replacement = graveReplacement(tokens, index, next, textMayBeTruncated, frequencyOf) ?: continue
            current = replaceToken(current, token, replacement)
        }
        return current
    }

    /** Le mot corrigé à mettre à la place de tokens[index], ou null s'il reste tel quel. */
    private fun graveReplacement(
        tokens: List<Token>,
        index: Int,
        next: Token?,
        textMayBeTruncated: Boolean,
        frequencyOf: (String) -> Long,
    ): String? {
        val token = tokens[index]
        if (next == null && token.core == "jusqu'a") return token.raw.dropLast(1) + "à" // « jusqu'à » : jamais « jusqu'a »
        if (token.raw != "a" && token.raw != "A") return null
        val grave = if (token.raw == "A") "À" else "à"
        val previous = previousInSentence(tokens, index)

        // Début de phrase : le verbe avoir a toujours un sujet avant lui. Un « a » seul au tout début du texte n'est
        // corrigé qu'avec le mot suivant (une réponse « A » suivie de Entrée ne doit pas devenir « À »).
        val sentenceStart = if (index == 0) !textMayBeTruncated else tokens[index - 1].endsSentence
        if (previous == null && sentenceStart && (index > 0 || (next != null && next.isPlain && next.head.isNotEmpty()))) {
            return grave
        }

        if (previous == null) return null
        if (next != null) {
            // Devant un infinitif, après un mot qui exclut le verbe avoir : « quelque chose a manger », « j'ai a faire ».
            val infinitiveLike = next.isPlain && !next.hasElision && participleOf(next.core, frequencyOf) != null
            val excludesVerb = previous.head in BEFORE_A_INFINITIVE || previous.core in WORDS_BEFORE_A
            return if (infinitiveLike && excludesVerb) grave else null
        }
        if (isSubjectLike(previous)) return null
        // Après un verbe qui appelle « à » : « je vais a Paris », « il pense a toi ».
        return if (previous.head in VERBS_BEFORE_A || previous.core in WORDS_BEFORE_A) grave else null
    }

    // ------------------------------------------------------------------
    // Participe passé / infinitif
    // ------------------------------------------------------------------

    private fun fixParticiple(text: String, frequencyOf: (String) -> Long): String {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return text
        val last = tokens.lastIndex
        val token = tokens[last]
        if (!token.isPlain || token.hasElision) return text
        val word = token.core

        // Infinitif juste après un auxiliaire : « il a manger » -> « il a mangé ».
        val participle = participleOf(word, frequencyOf)
        if (participle != null) {
            val gender = auxiliaryAgreement(tokens, last)
            if (gender != null) {
                val feminine = participle + "e"
                val form = if (gender == FEMININE && frequencyOf(feminine) >= MIN_FREQUENCY) feminine else participle
                return replaceToken(text, token, withOriginalCase(token.raw, form))
            }
        }

        // Participe en -é après un verbe qui appelle l'infinitif : « il faut mangé » -> « il faut manger ».
        if (word.endsWith("é") && word.length >= 4 && word !in NOUN_LIKE_PARTICIPLES && followsInfinitiveTrigger(tokens, last)) {
            val infinitive = word.dropLast(1) + "er"
            if (frequencyOf(infinitive) >= MIN_FREQUENCY) return replaceToken(text, token, withOriginalCase(token.raw, infinitive))
        }
        return text
    }

    /**
     * Participe passé de [word] s'il s'agit d'un infinitif connu (« manger » -> « mangé », « faire » -> « fait »),
     * sinon null. Les formes doivent figurer dans les listes : « danger » n'est pas un infinitif (« dangé » n'existe pas).
     */
    private fun participleOf(word: String, frequencyOf: (String) -> Long): String? {
        if (word.length < 4 || frequencyOf(word) < MIN_FREQUENCY) return null
        IRREGULAR_PARTICIPLES[word]?.let { return it }
        val participle = when {
            word.endsWith("er") -> word.dropLast(2) + "é"
            word.endsWith("ir") -> word.dropLast(2) + "i"
            else -> return null
        }
        return if (frequencyOf(participle) >= MIN_FREQUENCY) participle else null
    }

    /**
     * Le mot tokens[index] suit-il un auxiliaire (avec au plus deux adverbes entre les deux) ? Renvoie null sinon,
     * MASCULINE ou FEMININE (sujet « elle » avec être) selon l'accord à donner au participe.
     */
    private fun auxiliaryAgreement(tokens: List<Token>, index: Int): Int? {
        var k = index - 1
        var skipped = 0
        while (k >= 0 && skipped < 2 && tokens[k].isPlain && tokens[k].core in ADVERBS_BETWEEN) {
            k--
            skipped++
        }
        if (k < 0) return null
        for (j in k until index) if (tokens[j].endsSentence) return null
        val auxiliary = tokens[k]
        val before = if (k > 0 && !tokens[k - 1].endsSentence) tokens[k - 1] else null
        val head = auxiliary.head
        return when {
            head == "a" -> {
                // « a » seul est ambigu (« à ») : il faut un sujet clair avant (« il a manger »), un nom propre
                // (« Paul a manger »), ou une élision (« l'a manger »).
                val clear = auxiliary.hasElision ||
                    (before != null && ((isSubjectLike(before) && before.core != "y") || before.isCapitalized))
                if (clear) MASCULINE else null
            }
            head in AVOIR_FORMS -> MASCULINE
            head in ETRE_FORMS && !auxiliary.hasElision && auxiliary.isPlain && before != null -> when (before.core) {
                "je", "tu", "il", "on" -> MASCULINE
                "elle" -> FEMININE
                else -> null
            }
            else -> null
        }
    }

    /** Le mot tokens[index] suit-il un verbe ou une préposition qui appelle l'infinitif (au plus un adverbe entre) ? */
    private fun followsInfinitiveTrigger(tokens: List<Token>, index: Int): Boolean {
        var k = index - 1
        if (k >= 0 && tokens[k].isPlain && tokens[k].core in ADVERBS_BETWEEN) k--
        if (k < 0) return false
        for (j in k until index) if (tokens[j].endsSentence) return false
        val trigger = tokens[k]
        return trigger.isPlain && !trigger.hasElision && trigger.core in INFINITIVE_TRIGGERS
    }

    private fun withOriginalCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercaseChar() } else replacement

    // ------------------------------------------------------------------
    // Données
    // ------------------------------------------------------------------

    private const val MASCULINE = 0
    private const val FEMININE = 1

    private val SENTENCE_END = charArrayOf('.', '!', '?', '…')

    /** Mots qui sont (ou annoncent) le sujet d'un « a » verbe : « il a », « qui a », « il y a », « tout a changé ». */
    private val SUBJECTS = setOf(
        "il", "elle", "on", "ce", "ça", "ca", "cela", "ceci", "qui", "y", "tout", "rien", "personne",
        "quelqu'un", "chacun", "celui", "celle", "lequel", "laquelle",
    )

    /** Verbes (formes courantes) après lesquels « a » ne peut être que « à » : aller, venir, penser, parler... */
    private val VERBS_BEFORE_A = setOf(
        "vais", "vas", "va", "allons", "allez", "vont", "allé", "allée", "allés", "allées", "aller",
        "irai", "iras", "ira", "irons", "irez", "iront", "allais", "allait", "allions", "alliez", "allaient",
        "viens", "vient", "venons", "venez", "viennent", "venir", "venu", "venue",
        "habite", "habites", "habitons", "habitez", "habitent", "habiter", "habité",
        "pense", "penses", "pensons", "pensez", "pensent", "penser", "pensé",
        "parle", "parles", "parlons", "parlez", "parlent", "parler", "parlé",
        "commence", "commences", "commençons", "commencez", "commencent", "commencer", "commencé",
        "tiens", "tient", "tenons", "tenez", "tiennent", "tenir", "tenu",
        "apprends", "apprend", "apprenons", "apprenez", "apprennent", "apprendre", "appris",
        "sert", "servent", "servir", "servi",
    )

    /** Mots et locutions (mot entier, élision comprise) après lesquels « a » est « à » : « grâce à », « quant à »... */
    private val WORDS_BEFORE_A = setOf("grâce", "quant", "face", "rendez-vous")

    /** Mots après lesquels « a » devant un infinitif est « à » : « quelque chose à manger », « j'ai à faire ». */
    private val BEFORE_A_INFINITIVE = setOf(
        "chose", "choses", "rien", "tout", "quelque", "quoi", "beaucoup", "assez", "peu", "trop", "et", "ou", "mais", "pas", "plus",
        "ai", "as", "avons", "avez", "ont", "avais", "avait", "avions", "aviez", "avaient",
        "est", "suis", "es", "sommes", "êtes", "sont", "était", "étais",
    )

    /** Formes de avoir (sans « a », traité à part) qui précèdent un participe. */
    private val AVOIR_FORMS = setOf(
        "ai", "as", "avons", "avez", "ont", "avais", "avait", "avions", "aviez", "avaient",
        "aurai", "auras", "aura", "aurons", "aurez", "auront", "aurais", "aurait", "aurions", "auriez", "auraient",
        "avoir",
    )

    /** Formes de être (singulier) qui précèdent un participe (passé composé de « partir », « arriver »...). */
    private val ETRE_FORMS = setOf(
        "suis", "es", "est", "étais", "était", "serai", "seras", "sera", "serais", "serait",
    )

    /** Adverbes qui peuvent se glisser entre l'auxiliaire (ou le modal) et le verbe : « je n'ai pas manger ». */
    private val ADVERBS_BETWEEN = setOf(
        "pas", "plus", "jamais", "rien", "déjà", "bien", "mal", "trop", "toujours", "encore", "vraiment", "enfin", "souvent",
    )

    /** Verbes et prépositions qui appellent l'infinitif : « il faut manger », « je vais manger », « pour manger ». */
    private val INFINITIVE_TRIGGERS = setOf(
        "peux", "peut", "peuvent", "pouvons", "pouvez", "pouvoir",
        "veux", "veut", "veulent", "voulons", "voulez", "vouloir",
        "dois", "doit", "doivent", "devons", "devez", "devoir",
        "faut", "faudra", "faudrait", "pourrait", "pourrais", "pourraient", "voudrais", "voudrait", "voudraient",
        "devrait", "devrais", "devraient",
        "vais", "vas", "va", "vont", "allons", "allez", "aller",
        "pour", "pu", "voulu", "dû", "su",
    )

    /** Mots en -é qui sont aussi des noms ou des adjectifs : jamais changés en infinitif (« à côté », « le passé »). */
    private val NOUN_LIKE_PARTICIPLES = setOf(
        "été", "côté", "passé", "thé", "gré", "degré", "blé", "pré", "clé", "marché", "cité", "comté", "congé", "traité",
        "invité", "employé", "allié", "associé", "député", "marié", "fiancé", "bébé", "café",
    )

    /** Participes passés des infinitifs irréguliers courants (les verbes en -er et en -ir réguliers se déduisent). */
    private val IRREGULAR_PARTICIPLES = mapOf(
        "faire" to "fait", "dire" to "dit", "lire" to "lu", "écrire" to "écrit", "prendre" to "pris",
        "apprendre" to "appris", "comprendre" to "compris", "vendre" to "vendu", "attendre" to "attendu",
        "entendre" to "entendu", "répondre" to "répondu", "mettre" to "mis", "permettre" to "permis",
        "promettre" to "promis", "voir" to "vu", "savoir" to "su", "pouvoir" to "pu", "vouloir" to "voulu",
        "devoir" to "dû", "boire" to "bu", "recevoir" to "reçu", "venir" to "venu", "tenir" to "tenu",
        "devenir" to "devenu", "revenir" to "revenu", "courir" to "couru", "offrir" to "offert", "ouvrir" to "ouvert",
        "découvrir" to "découvert", "souffrir" to "souffert", "connaître" to "connu", "paraître" to "paru",
        "vivre" to "vécu", "suivre" to "suivi", "rire" to "ri", "conduire" to "conduit", "construire" to "construit",
        "produire" to "produit", "traduire" to "traduit",
    )
}
