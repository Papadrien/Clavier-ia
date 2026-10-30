package fr.junade.taipo.dictionary

/**
 * Suggestions de mots de la bande du clavier (story 1.17) : mots proposés pendant la frappe, emplacements de la bande,
 * alternatives pour un mot déjà écrit. Extrait de [Dictionary] (audit 2.1, étape 10) ; le code est celui d'avant
 * l'extraction. S'appuie sur les listes et l'état de [Dictionary] (fréquences, candidats, corrections), que celui-ci
 * expose au module ; [Dictionary] garde ses méthodes publiques, qui renvoient ici.
 */
internal class DictionarySuggestions(private val d: Dictionary) {

    /**
     * Story 1.17 : mots proposés dans la bande de suggestions pour le mot en cours de frappe
     * [typed] (au plus [limit]). Toujours local : pas d'IA, pas d'apprentissage (décision de la
     * story 1.3).
     *
     * Ordre : d'abord la correction de [Dictionary.correctionFor] si le mot tapé est une faute, puis les
     * mots qui commencent par [typed] (complétions), ceux du dictionnaire personnel avant les
     * autres, puis par fréquence décroissante (à égalité : le plus court, puis l'ordre
     * alphabétique, pour un résultat stable). Le mot tapé lui-même n'est jamais proposé.
     *
     * La casse de la première lettre tapée est reprise ; un mot personnel garde sa casse
     * enregistrée (« iPhone ») sauf s'il est tapé en minuscules. Rien n'est proposé pour un mot
     * sans lettre, contenant un chiffre, ou entièrement en majuscules (acronyme).
     */
    fun suggestionsFor(
        typed: String,
        limit: Int = Dictionary.SUGGESTION_LIMIT,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): List<String> {
        if (limit <= 0 || !isSuggestible(typed)) return emptyList()
        // Tournure habituelle : à défaut de faute, un mot valide que les habitudes remplacent clairement (voir PhraseHabits).
        val correction = d.correctionFor(typed, personalWords = personalWords, habits = habits)
            ?: d.habitPhraseCorrectionFor(typed, personalWords, habits)
        return (listOfNotNull(correction) + completionsFor(typed, limit, personalWords, listOfNotNull(correction))).take(limit)
    }

    /**
     * Story 1.17 : contenu des [SUGGESTION_LIMIT] emplacements de mots de la bande (null = vide).
     *
     * [habits] : les mêmes habitudes que celles de l'autocorrection, pour que la correction affichée soit celle qui sera appliquée.
     *
     * - Si le mot tapé va être corrigé à l'espace (même [Dictionary.correctionFor] que l'autocorrection) :
     *   [mot tapé, correction, 1re complétion], la correction au centre (gras, elle va remplacer le
     *   mot tapé) et le mot tapé à gauche pour pouvoir la refuser.
     * - Sinon : les complétions, de gauche à droite, en simples suggestions (poids normal).
     *
     * [participle] : participe passé attendu à la place du mot tapé d'après l'auxiliaire qui le précède (voir
     * [FrenchContextCorrector.participleFor]) ; il n'est pris en compte que si le mot tapé n'est pas une faute.
     * Voir [participleSlots].
     */
    fun suggestionSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
        participle: ParticipleFix? = null,
    ): List<WordSuggestion?> {
        val empty = List<WordSuggestion?>(Dictionary.SUGGESTION_LIMIT) { null }
        if (!isSuggestible(typed)) return empty
        // Même règle que l'autocorrection : la tournure habituelle affichée en gras est celle qui sera appliquée.
        val correction = d.correctionFor(typed, personalWords = personalWords, habits = habits)
            ?: d.habitPhraseCorrectionFor(typed, personalWords, habits)
        // Mot valide qui suit un auxiliaire (« il a observe ») : le participe est proposé au centre.
        if (correction == null && participle != null) return participleSlots(typed, participle, personalWords)
        val completions = completionsFor(typed, Dictionary.SUGGESTION_LIMIT, personalWords, listOfNotNull(correction))
        fun completion(index: Int) = completions.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        if (correction != null) {
            return listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(correction, WordSuggestion.Kind.AUTOCORRECTION),
                completion(0),
            )
        }
        return List(Dictionary.SUGGESTION_LIMIT) { completion(it) }
    }

    /**
     * Contenu des [SUGGESTION_LIMIT] emplacements quand le curseur est posé sur un mot déjà écrit ([typed], le mot
     * entier, partie après le curseur comprise), au lieu d'être en train d'en taper un :
     *
     * - le mot va être corrigé (même [Dictionary.correctionFor] que l'autocorrection) : [mot tapé, correction, meilleure
     *   alternative] ;
     * - le mot a déjà été corrigé ([previousForm], sa forme avant la correction) : [forme d'origine, 1re et 2e
     *   alternatives], pour pouvoir revenir en arrière ;
     * - sinon : les 3 meilleures alternatives (voir [alternativesFor]).
     */
    fun placementSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        previousForm: String? = null,
        habits: HabitContext? = null,
        participle: ParticipleFix? = null,
    ): List<WordSuggestion?> {
        val empty = List<WordSuggestion?>(Dictionary.SUGGESTION_LIMIT) { null }
        if (!isSuggestible(typed)) return empty
        val correction = d.correctionFor(typed, personalWords = personalWords, habits = habits)
        val exclude = HashSet<String>()
        exclude += typed.lowercase()
        correction?.let { exclude += it.lowercase() }
        val participleForm = participle?.takeIf { correction == null }
        participleForm?.let { exclude += participleKeys(it) }
        val original = previousForm?.takeIf { correction == null && it.isNotEmpty() && it.lowercase() != typed.lowercase() }
        original?.let { exclude += it.lowercase() }
        val alternatives = alternativesFor(typed, Dictionary.SUGGESTION_LIMIT, personalWords, exclude)
        fun alternative(index: Int) = alternatives.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        return when {
            correction != null -> listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(correction, WordSuggestion.Kind.AUTOCORRECTION),
                alternative(0),
            )
            // Mot déjà écrit après un auxiliaire (« il a observe ») : le participe au centre, comme à la frappe.
            participleForm != null -> {
                val form = WordForms.applyOriginalCasing(typed, participleForm.form)
                if (participleForm.automatic) {
                    listOf(
                        WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                        WordSuggestion(form, WordSuggestion.Kind.AUTOCORRECTION),
                        alternative(0),
                    )
                } else {
                    listOf(alternative(0), WordSuggestion(form, WordSuggestion.Kind.COMPLETION), alternative(1))
                }
            }
            original != null -> listOf(WordSuggestion(original, WordSuggestion.Kind.TYPED), alternative(0), alternative(1))
            else -> List(Dictionary.SUGGESTION_LIMIT) { alternative(it) }
        }
    }

    /**
     * Emplacements d'un mot tapé qui est valide mais que l'auxiliaire qui le précède fait attendre au participe passé
     * (« il a observe » -> « observé ») :
     *
     * - [ParticipleFix.automatic] (l'autocorrection l'appliquera à l'espace) : [mot tapé, participe, 1re complétion],
     *   le participe au centre et en gras, le mot tapé à gauche pour le refuser, comme pour une faute du dictionnaire ;
     * - sinon (pas assez sûr pour être appliqué seul) : [1re complétion, participe, 2e complétion], le participe au
     *   centre en simple suggestion (poids normal), à toucher pour l'adopter.
     *
     * Le participe (et sa forme masculine, s'il est accordé) n'est pas répété dans les complétions.
     */
    private fun participleSlots(
        typed: String,
        participle: ParticipleFix,
        personalWords: Collection<String>,
    ): List<WordSuggestion?> {
        val form = WordForms.applyOriginalCasing(typed, participle.form)
        val completions = completionsFor(typed, Dictionary.SUGGESTION_LIMIT, personalWords, participleKeys(participle).toList())
        fun completion(index: Int) = completions.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        return if (participle.automatic) {
            listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(form, WordSuggestion.Kind.AUTOCORRECTION),
                completion(0),
            )
        } else {
            listOf(completion(0), WordSuggestion(form, WordSuggestion.Kind.COMPLETION), completion(1))
        }
    }

    /** Clés minuscules à ne pas reproposer à côté du participe : lui-même et, s'il est accordé (« arrivée »), sa forme masculine. */
    private fun participleKeys(participle: ParticipleFix): Set<String> {
        val key = participle.form.lowercase()
        return setOf(key, key.removeSuffix("e"))
    }

    /**
     * Au plus [limit] mots pertinents pour le mot entier [typed] : formes accentuées, mots personnels, mots à une
     * faute plausible au clavier (« peu » -> « peut », « peux ») et complétions (« bon » -> « bonne »), classés par
     * fréquence dans le corpus, comme le fait l'autocorrection à distance égale. Un mot à une faute de distance n'est
     * retenu que s'il n'est pas bien plus rare que le mot tapé (au moins 1/[NEIGHBOR_MIN_SHARE_DIVISOR] de sa
     * fréquence) et si l'erreur est plausible au clavier (voir [KeyProximity.editCost]) : sans cette condition, les
     * mots courts proposeraient n'importe quel mot voisin (« bon » -> « son », « ton »).
     * Ni [typed] ni [exclude] (clés en minuscules) n'y figurent.
     */
    fun alternativesFor(
        typed: String,
        limit: Int,
        personalWords: Collection<String> = emptyList(),
        exclude: Set<String> = emptySet(),
    ): List<String> {
        if (limit <= 0 || !isSuggestible(typed)) return emptyList()
        val lower = typed.lowercase()
        val typedFrequency = d.frequencies[lower] ?: 0L
        val seen = HashSet<String>(exclude)
        seen += lower
        val scored = ArrayList<Pair<String, Long>>()

        fun add(display: String, key: String, score: Long) {
            if (seen.add(key)) scored += display to score
        }

        d.accentVariants(lower).forEachIndexed { index, variant ->
            add(WordForms.applyOriginalCasing(typed, variant), variant, Long.MAX_VALUE - index)
        }
        for (personal in personalWords) {
            val key = personal.lowercase()
            if (key == lower) continue
            if (key.startsWith(lower) || (lower.length >= 3 && EditDistance.damerauLevenshteinBounded(lower, key, 1) <= 1)) {
                add(d.personalDisplay(typed, personal), key, d.personalFrequency)
            }
        }
        if (lower.length >= Dictionary.MIN_WORD_LENGTH_FOR_NEIGHBORS) {
            val minFrequency = maxOf(1L, typedFrequency / Dictionary.NEIGHBOR_MIN_SHARE_DIVISOR)
            val typedMask = EditDistance.letterMask(lower)
            // Boucle chaude : les tableaux du dictionnaire sont lus une fois, hors de la boucle.
            val indicesByLength = d.indicesByLength
            val candidateFrequencies = d.candidateFrequencies
            val candidateWords = d.candidateWords
            val candidateMasks = d.candidateMasks
            val dominatedByAccented = d.dominatedByAccented
            val proximity = d.proximity
            for (length in maxOf(0, lower.length - 1)..minOf(indicesByLength.size - 1, lower.length + 1)) {
                for (i in indicesByLength[length]) {
                    val frequency = candidateFrequencies[i]
                    if (frequency < minFrequency) continue
                    val candidate = candidateWords[i]
                    if (candidate == lower || candidate in dominatedByAccented) continue
                    val mask = candidateMasks[i]
                    if ((typedMask and mask.inv()).countOneBits() > 1 || (mask and typedMask.inv()).countOneBits() > 1) continue
                    if (EditDistance.damerauLevenshteinBounded(lower, candidate, 1) > 1) continue
                    // Faute plausible au clavier seulement ; sans proximité (tests, anglais sans disposition), tout compte.
                    if (proximity.isEnabled && proximity.editCost(lower, candidate) > Dictionary.PLAUSIBLE_SLIP_COST) continue
                    add(WordForms.applyOriginalCasing(typed, candidate), candidate, frequency)
                }
            }
        }
        for (word in d.topCompletions(lower, limit + 3)) {
            add(WordForms.applyOriginalCasing(typed, word), word, d.frequencies.getValue(word))
        }
        return scored.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun isSuggestible(typed: String): Boolean = when {
        typed.none { it.isLetter() } -> false
        typed.any { it.isDigit() } -> false
        typed.length > 1 && typed.all { it.isUpperCase() } -> false
        else -> true
    }

    /**
     * Au plus [limit] complétions de [typed] : mots personnels d'abord, puis mots du dictionnaire.
     * Ni le mot tapé ni [exclude] (la correction ou le participe, déjà proposés à part) n'y figurent.
     */
    private fun completionsFor(
        typed: String,
        limit: Int,
        personalWords: Collection<String>,
        exclude: Collection<String>,
    ): List<String> {
        val lower = typed.lowercase()
        val result = ArrayList<String>(limit)
        // Clés minuscules déjà proposées (« Chat » et « chat » ne comptent qu'une fois).
        val seen = HashSet<String>()
        seen += lower
        for (word in exclude) seen += word.lowercase()

        fun offer(display: String, key: String) {
            if (result.size < limit && seen.add(key)) result += display
        }

        // Mots personnels : alphabétique (l'ordre de la liste), avant les mots du dictionnaire.
        for (personal in personalWords) {
            val key = personal.lowercase()
            if (key.startsWith(lower)) offer(d.personalDisplay(typed, personal), key)
        }
        // Après une élision (« l'Ta »), les mots personnels sont proposés avec leur élision (« l'Taipo »).
        val apostrophe = typed.lastIndexOf('\'')
        val hasElision = apostrophe > 0 && apostrophe < typed.length - 1
        if (hasElision) {
            val elision = typed.substring(0, apostrophe + 1)
            val stem = typed.substring(apostrophe + 1)
            val stemLower = stem.lowercase()
            for (personal in personalWords) {
                val key = personal.lowercase()
                if (key.length > stemLower.length && key.startsWith(stemLower)) {
                    offer(elision + d.personalDisplay(stem, personal), elision.lowercase() + key)
                }
            }
        }
        // Formes accentuées du mot tapé : avant les complétions (« corrige » -> « corrigé » avant « corriger »).
        if (result.size < limit && !hasElision) {
            for (variant in d.accentVariants(lower)) offer(WordForms.applyOriginalCasing(typed, variant), variant)
        }
        if (result.size < limit) {
            for (word in d.topCompletions(lower, limit + 1)) offer(WordForms.applyOriginalCasing(typed, word), word)
        }
        // Après une élision française (« t'insc »), mots du dictionnaire qui complètent le mot collé à
        // l'apostrophe (« t'inscrire »), à condition qu'il commence par une voyelle ou un h (« l'ordi »
        // oui, « l'cheval » non). Les contractions du dictionnaire (« j'ai ») restent proposées avant.
        if (hasElision && result.size < limit) {
            val elision = typed.substring(0, apostrophe + 1)
            val stem = typed.substring(apostrophe + 1)
            if (elision.dropLast(1).lowercase() in Dictionary.APOSTROPHE_ELISIONS && d.canFollowElision(stem)) {
                for (word in d.topCompletions(stem.lowercase(), limit + 1)) {
                    offer(elision + WordForms.applyOriginalCasing(stem, word), elision.lowercase() + word)
                }
            }
        }
        return result
    }
}
