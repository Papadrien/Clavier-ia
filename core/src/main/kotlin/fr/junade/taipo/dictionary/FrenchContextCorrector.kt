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
 *   Aussi : après être (« je suis a la mairie », « t'es pas a la maison »), dans les locutions « a côté », « a cause »,
 *   « a partir », « a quelle heure », « a peu près », « vis a vis » (voir [ALWAYS_AFTER_A], [A_PHRASES]).
 * - **ou / où** : « ou » devient « où » devant un sujet qui ne peut pas prolonger une alternative (« le jour ou il est
 *   parti », « la ville ou j'habite »), et en début de phrase devant un verbe de question (« ou est-il », « ou sont »).
 *   Ce sont des corrections « à rebours » : elles attendent le mot suivant.
 * - **la / là** : « la » devient « là » après être, venir ou rester quand le mot suivant ne peut pas suivre un article
 *   (« je suis la pour toi », « il est la depuis hier »).
 * - **participe passé / infinitif** : un infinitif juste après un auxiliaire devient un participe (« il a manger »
 *   -> « il a mangé ») ; un participe en -é juste après un verbe ou une préposition qui appelle l'infinitif
 *   devient un infinitif (« il faut mangé » -> « il faut manger »).
 * - **accent oublié d'un participe** : après un auxiliaire, un mot en -e dont la forme en -é est un participe courant
 *   reçoit son accent (« il a parle » -> « il a parlé »). Le dictionnaire seul ne peut pas trancher : « parle » et
 *   « parlé » sont deux mots corrects. Après une forme de avoir (« j'ai », « il a », « nous avons »), un mot nu en -e
 *   ne peut quasiment être qu'un participe : le rapport de fréquences exigé est large ([AVOIR_SHARE_DIVISOR]), sauf pour
 *   les noms des locutions (« j'ai envie », « j'ai hâte », voir [NOUNS_AFTER_AVOIR]). Après être, où l'adjectif est
 *   fréquent (« il est calme »), le rapport reste strict ([ACCENTED_SHARE_DIVISOR]). La bande de suggestions
 *   utilise la même analyse ([participleFor]) : quand la correction n'est pas assez sûre pour être appliquée seule,
 *   le participe est tout de même proposé au centre de la bande.
 *
 * - **traits d'union** : « est ce que », « va t il », « dis moi », « au dessus »... (voir [FrenchHyphenation]).
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
        current = fixAccentOnOuAndLa(current, textMayBeTruncated, allowRetro)
        current = fixParticiple(current, frequencyOf)
        // Traits d'union oubliés (« est ce que », « dis moi », « rendez vous ») : en dernier, après les accents de a / à.
        current = FrenchHyphenation.fix(current, textMayBeTruncated) ?: current
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
        for (offset in intArrayOf(2, 1, 0)) {
            if (offset > 0 && !allowRetro) continue
            val tokens = tokenize(current)
            val index = tokens.lastIndex - offset
            if (index < 0) continue
            val token = tokens[index]
            if (!token.isPlain) continue
            val replacement: String? = if (offset == 2) {
                // Locution de trois mots (« a quelle heure », « a peu près ») : revue quand son dernier mot est tapé.
                graveBeforePhrase(token, tokens[index + 1], tokens[index + 2])
            } else {
                graveReplacement(tokens, index, if (offset == 1) tokens[index + 1] else null, textMayBeTruncated, frequencyOf)
            }
            if (replacement == null) continue
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

        // Locutions figées : « il a côté » ou « elle a cause » n'existent pas, « a côté » est donc toujours « à côté ».
        if (next != null && next.isPlain && next.core in ALWAYS_AFTER_A) return grave
        if (previous == null) return null
        // « vis a vis » : « vis-à-vis ».
        if (next != null && previous.core == "vis" && next.core == "vis") return grave
        // Après être (éventuellement suivi d'un adverbe) : « je suis a la mairie », « c'est a toi », « t'es pas a la
        // maison ». Deux verbes conjugués ne se suivent pas : ce « a » ne peut pas être le verbe avoir.
        if (isAfterBe(tokens, index)) return grave
        if (next != null) {
            // Devant un infinitif, après un mot qui exclut le verbe avoir : « quelque chose a manger », « j'ai a faire ».
            val infinitiveLike = next.isPlain && !next.hasElision && participleOf(next.core, frequencyOf) != null
            // Un verbe qui appelle « à » devant un infinitif : « réussir a finir », « il continue a manger ».
            val excludesVerb = previous.head in BEFORE_A_INFINITIVE || previous.core in WORDS_BEFORE_A ||
                previous.head in VERBS_BEFORE_A || previous.head in VERBS_BEFORE_A_INFINITIVE
            return if (infinitiveLike && excludesVerb) grave else null
        }
        if (isSubjectLike(previous)) return null
        // Après un verbe qui appelle « à » : « je vais a Paris », « il pense a toi », « pour réussir a » (un infinitif ne
        // peut pas être le sujet de « a » dans une phrase courante, alors que « aide » ou « arrive » peuvent être des noms).
        return if (previous.head in VERBS_BEFORE_A || previous.core in WORDS_BEFORE_A ||
            previous.head in INFINITIVES_BEFORE_A
        ) {
            grave
        } else {
            null
        }
    }

    /** « a quelle heure », « a peu près » : [token] est « a » et [second], [third] complètent la locution. */
    private fun graveBeforePhrase(token: Token, second: Token, third: Token): String? {
        if (token.raw != "a" && token.raw != "A") return null
        if (!second.isPlain || !third.isPlain) return null
        if ((second.core to third.core) !in A_PHRASES) return null
        return if (token.raw == "A") "À" else "à"
    }

    /** Le mot tokens[index] suit-il une forme de être, avec au plus un adverbe entre les deux (« je ne suis pas a ») ? */
    private fun isAfterBe(tokens: List<Token>, index: Int): Boolean {
        var k = index - 1
        if (k >= 0 && tokens[k].isPlain && tokens[k].core in ADVERBS_BETWEEN) k--
        if (k < 0) return false
        for (j in k until index) if (tokens[j].endsSentence) return false
        val verb = tokens[k]
        return verb.isPlain && verb.head in BE_FORMS
    }

    // ------------------------------------------------------------------
    // ou / où, la / là
    // ------------------------------------------------------------------

    /** Revoit le mot précédent (« ou », « la ») à la lumière du mot qui vient d'être tapé ; sans effet sans correction à rebours. */
    private fun fixAccentOnOuAndLa(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean): String {
        if (!allowRetro) return text
        val tokens = tokenize(text)
        val index = tokens.lastIndex - 1
        if (index < 0) return text
        val token = tokens[index]
        val next = tokens[index + 1]
        if (!token.isPlain || !next.isPlain || next.core.isEmpty()) return text
        val replacement = when (token.raw) {
            "ou", "Ou" -> ouReplacement(tokens, index, next, textMayBeTruncated)
            "la" -> laReplacement(tokens, index, next)
            else -> null
        } ?: return text
        return replaceToken(text, token, replacement)
    }

    private fun ouReplacement(tokens: List<Token>, index: Int, next: Token, textMayBeTruncated: Boolean): String? {
        val token = tokens[index]
        val grave = if (token.raw == "Ou") "Où" else "où"
        val previous = previousInSentence(tokens, index)
        if (previous == null) {
            // Début de phrase : « Ou est-il ? », « Ou sont-ils ? ». Un « ou » au tout début d'une fenêtre coupée n'est pas sûr.
            val sentenceStart = if (index == 0) !textMayBeTruncated else tokens[index - 1].endsSentence
            return if (sentenceStart && next.core.substringBefore('-') in OU_QUESTION_VERBS) grave else null
        }
        // « toi ou il » : une alternative entre deux pronoms ou deux réponses reste un « ou ».
        if (previous.core in BEFORE_OR_ALTERNATIVE) return null
        val subject = next.core in OU_SUBJECTS ||
            (next.hasElision && next.core.substringBefore('\'') in OU_ELIDED_SUBJECTS)
        return if (subject) grave else null
    }

    private fun laReplacement(tokens: List<Token>, index: Int, next: Token): String? {
        val previous = previousInSentence(tokens, index) ?: return null
        if (previous.head !in BEFORE_ADVERB_LA) return null
        return if (next.core in AFTER_ADVERB_LA) "là" else null
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

        // Infinitif ou accent oublié juste après un auxiliaire : « il a manger » -> « il a mangé », « il a parle » -> « il a parlé ».
        val fix = participleAt(tokens, last, frequencyOf)
        if (fix != null && fix.automatic) return replaceToken(text, token, withOriginalCase(token.raw, fix.form))

        // Participe en -é après un verbe qui appelle l'infinitif : « il faut mangé » -> « il faut manger ».
        if (word.endsWith("é") && word.length >= 4 && word !in NOUN_LIKE_PARTICIPLES && followsInfinitiveTrigger(tokens, last)) {
            val infinitive = word.dropLast(1) + "er"
            if (frequencyOf(infinitive) >= MIN_FREQUENCY) return replaceToken(text, token, withOriginalCase(token.raw, infinitive))
        }
        return text
    }

    /**
     * Le participe passé que le dernier mot de [text] (le texte avant le curseur, sans espace finale) devrait être,
     * d'après l'auxiliaire qui le précède, ou null : voir [ParticipleFix]. C'est l'analyse de l'autocorrection,
     * exposée pour que la bande de suggestions montre la même chose (et propose le participe quand il n'est pas
     * assez sûr pour être appliqué seul). La casse du mot tapé n'est pas reprise.
     */
    fun participleFor(text: String, frequencyOf: (String) -> Long): ParticipleFix? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return null
        return participleAt(tokens, tokens.lastIndex, frequencyOf)
    }

    private fun participleAt(tokens: List<Token>, index: Int, frequencyOf: (String) -> Long): ParticipleFix? {
        val token = tokens[index]
        if (!token.isPlain || token.hasElision) return null
        val word = token.core
        val auxiliary = auxiliaryAgreement(tokens, index) ?: return null

        // Infinitif juste après un auxiliaire : « il a manger » -> « il a mangé ».
        participleOf(word, frequencyOf)?.let { return ParticipleFix(agreed(it, auxiliary, frequencyOf), automatic = true) }

        // Accent oublié : « il a parle » -> « il a parlé ». Garde-fous : la forme en -é doit être un vrai mot (fréquence
        // minimale) et, pour être appliquée seule, ne pas être très rare face à la forme en -e (« calme », « comptes »
        // sont des noms ou des adjectifs, pas des participes sans accent). Après avoir, le rapport exigé est bien plus
        // large : un mot nu en -e juste après « j'ai » est presque toujours un participe.
        if (word.length < 4 || !word.endsWith("e") || word in NOUNS_AFTER_AVOIR) return null
        val accented = word.dropLast(1) + "é"
        val accentedFrequency = frequencyOf(accented)
        if (accentedFrequency < MIN_FREQUENCY) return null
        val wordFrequency = frequencyOf(word)
        val form = agreed(accented, auxiliary, frequencyOf)
        val automatic = accented !in NOUN_LIKE_PARTICIPLES &&
            accentedFrequency * (if (auxiliary.isAvoir) AVOIR_SHARE_DIVISOR else ACCENTED_SHARE_DIVISOR) >= wordFrequency
        // Proposé sans être appliqué : après avoir toujours ; après être seulement si la forme en -é n'est pas négligeable.
        val suggestible = automatic || auxiliary.isAvoir || accentedFrequency * SUGGESTED_SHARE_DIVISOR >= wordFrequency
        return if (suggestible) ParticipleFix(form, automatic) else null
    }

    /** [participle] accordé : féminin (« arrivée ») après « elle est », si cette forme est un vrai mot. */
    private fun agreed(participle: String, auxiliary: Auxiliary, frequencyOf: (String) -> Long): String {
        val feminine = participle + "e"
        return if (auxiliary.agreement == FEMININE && frequencyOf(feminine) >= MIN_FREQUENCY) feminine else participle
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
     * sinon l'[Auxiliary] : son accord (MASCULINE, ou FEMININE pour le sujet « elle » avec être) et s'il s'agit du verbe avoir.
     */
    private fun auxiliaryAgreement(tokens: List<Token>, index: Int): Auxiliary? {
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
                if (clear) Auxiliary(MASCULINE, isAvoir = true) else null
            }
            head in AVOIR_FORMS -> Auxiliary(MASCULINE, isAvoir = true)
            head in ETRE_FORMS && !auxiliary.hasElision && auxiliary.isPlain && before != null -> when (before.core) {
                "je", "tu", "il", "on" -> Auxiliary(MASCULINE, isAvoir = false)
                "elle" -> Auxiliary(FEMININE, isAvoir = false)
                else -> null
            }
            else -> null
        }
    }

    private class Auxiliary(val agreement: Int, val isAvoir: Boolean)

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

    /**
     * Une forme en -é n'est un participe sans accent probable que si elle représente au moins 1/11 des occurrences de la
     * forme en -e : « pensé » (24 %), « aimé » (11 %) oui ; « calmé » (2 %), « comptes » (4 %), « placé » (6,5 %) non.
     */
    private const val ACCENTED_SHARE_DIVISOR = 11L

    /**
     * Après une forme de avoir, le rapport exigé est de 1/50 : « regardé » (12 %), « resté » (8 %), « porté » ou
     * « tombé » passent, alors que presque aucun nom en -e ne suit « j'ai » sans article (voir [NOUNS_AFTER_AVOIR]).
     */
    private const val AVOIR_SHARE_DIVISOR = 50L

    /** Seuil (1/30) pour proposer, sans l'appliquer, le participe d'un mot en -e après être : « resté » (8 %) oui, « calmé » (2 %) non. */
    private const val SUGGESTED_SHARE_DIVISOR = 30L

    /** Noms en -e qui suivent « avoir » sans article (« j'ai envie », « j'ai hâte ») : jamais pris pour un participe sans accent. */
    private val NOUNS_AFTER_AVOIR = setOf("envie", "honte", "hâte", "peine", "classe")

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

    /**
     * Infinitifs de verbes qui appellent « à » + infinitif : « réussir a finir », « arriver a faire ». Seuls les
     * infinitifs servent sans mot suivant : « aide » ou « arrive » peuvent être des noms (« l'aide a été »).
     */
    private val INFINITIVES_BEFORE_A = setOf(
        "réussir", "arriver", "continuer", "hésiter", "aider", "chercher", "parvenir", "essayer", "tarder",
        "s'habituer", "habituer", "penser", "servir", "passer",
    )

    /** Formes conjuguées de ces verbes, utiles seulement quand « a » est suivi d'un infinitif : « il réussit a finir ». */
    private val VERBS_BEFORE_A_INFINITIVE = setOf(
        "réussis", "réussit", "réussissons", "réussissez", "réussissent", "réussi", "réussira", "réussirai", "réussissait",
        "arrive", "arrives", "arrivons", "arrivez", "arrivent", "arrivé", "arrivait", "arriverai", "arrivera",
        "continue", "continues", "continuons", "continuez", "continuent", "continué", "continuait", "continuera",
        "hésite", "hésites", "hésitons", "hésitez", "hésitent", "hésité", "hésitait",
        "aide", "aides", "aidons", "aidez", "aident", "aidé", "aidait",
        "cherche", "cherches", "cherchons", "cherchez", "cherchent", "cherché", "cherchait",
        "parviens", "parvient", "parvenons", "parvenez", "parviennent", "parvenu", "parvenait",
        "essaie", "essaies", "essayons", "essayez", "essaient", "essayé", "essayait",
        "tarde", "tardes", "tardons", "tardez", "tardent", "tardé", "tardait",
        "habitue", "habitues", "habituons", "habituez", "habituent", "habitué", "habituait",
    ) + INFINITIVES_BEFORE_A

    /** Début de phrase : verbes de question qui suivent « où » (« où est-il », « où sont les clés », « où vas-tu »). */
    private val OU_QUESTION_VERBS = setOf(
        "est", "sont", "es", "était", "étais", "êtes", "sommes", "suis", "vas", "va", "allez", "vais", "vont", "allons",
        "habites", "habitez", "habitent", "se", "peut", "puis", "pouvons", "peuvent",
    )

    /** Sujets qui, après « ou » au milieu d'une phrase, annoncent une proposition (« la ville ou il habite »). */
    private val OU_SUBJECTS = setOf("je", "tu", "il", "ils", "on")

    /** Élisions de sujet qui font la même chose : « ou j'habite », « ou t'es ». */
    private val OU_ELIDED_SUBJECTS = setOf("j", "t")

    /** Mots avant lesquels « ou » est plutôt une alternative qu'un « où » (« moi ou il », « oui ou non »). */
    private val BEFORE_OR_ALTERNATIVE = setOf(
        "moi", "toi", "lui", "elle", "eux", "elles", "nous", "vous", "oui", "non", "ça", "ceci", "cela",
    )

    /** Verbes (formes courantes) après lesquels « la » seul peut être « là » : « je suis là », « il reste là ». */
    private val BEFORE_ADVERB_LA = setOf(
        "suis", "es", "est", "sommes", "êtes", "sont", "étais", "était", "étions", "étaient", "serai", "sera", "serons",
        "reste", "restes", "restons", "restez", "restent", "reviens", "revient", "viens", "vient", "venez", "attends",
        "attend", "attendez", "attendons",
    )

    /** Mots qui ne peuvent pas suivre l'article « la » : « je suis la pour toi » est « là », « je suis la pluie » n'existe pas. */
    private val AFTER_ADVERB_LA = setOf(
        "pour", "depuis", "avec", "et", "mais", "car", "donc", "quand", "parce", "puisque", "si", "je", "tu", "il", "ils",
        "elle", "elles", "on", "nous", "vous", "ça", "ce", "sans", "après", "ensuite", "aussi", "encore", "toujours",
        "déjà", "maintenant", "demain", "c'est", "qu'on",
    )

    /** Mots et locutions (mot entier, élision comprise) après lesquels « a » est « à » : « grâce à », « quant à »... */
    private val WORDS_BEFORE_A = setOf("grâce", "quant", "face", "rendez-vous")

    /** Mots qui ne peuvent pas suivre le verbe avoir : « a côté », « a cause de », « a partir de », « a gauche »... */
    private val ALWAYS_AFTER_A = setOf("côté", "cause", "propos", "partir", "travers", "présent", "gauche", "droite")

    /** Locutions de trois mots où « a » est toujours « à » : « à quelle heure », « à quel moment », « à peu près ». */
    private val A_PHRASES = setOf(
        "quelle" to "heure", "quelle" to "date", "quel" to "moment", "quel" to "endroit", "peu" to "près",
    )

    /** Formes de être (sans « été », qui est aussi un nom : « l'été a commencé » ; ni « soit » : « soit a, soit b »). */
    private val BE_FORMS = setOf(
        "suis", "es", "est", "sommes", "êtes", "sont", "étais", "était", "étions", "étiez", "étaient",
        "serai", "seras", "sera", "serons", "serez", "seront", "serais", "serait", "serions", "seriez", "seraient",
        "sois", "soyons", "soyez", "être",
    )

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

/**
 * Participe passé attendu à la place d'un mot, d'après l'auxiliaire qui le précède (voir
 * [FrenchContextCorrector.participleFor]). [form] : le participe, accordé si besoin (« arrivée » après « elle est »),
 * en minuscules. [automatic] : assez sûr pour remplacer le mot à l'espace ; sinon il est seulement proposé dans la bande.
 */
class ParticipleFix(val form: String, val automatic: Boolean)
