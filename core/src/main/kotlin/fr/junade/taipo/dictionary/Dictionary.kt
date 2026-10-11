package fr.junade.taipo.dictionary

import java.text.Normalizer

/**
 * Dictionnaire local pour l'autocorrection/les suggestions (story 1.3).
 *
 * Décision produit : pas d'IA pour cette fonction (jugée plus rapide et plus
 * fiable qu'une correction IA pour cet usage), et pas d'apprentissage
 * automatique — l'ensemble de mots est fixe une fois chargé. Le dictionnaire
 * personnel (ajout manuel par l'utilisateur, story 1.4) est une
 * responsabilité séparée (voir [PersonalDictionary]) : ses mots sont
 * simplement transmis à [correctionFor] à chaque appel, sans jamais être
 * mélangés à l'ensemble fixe chargé ici.
 *
 * Classe pure (aucune dépendance Android) : le chargement depuis les assets
 * de l'app se fait ailleurs (voir DictionaryLoader), pour rester testable en
 * JVM simple.
 */
class Dictionary private constructor(
    frequencies: Map<String, Long>,
    private val inflections: InflectionRules,
    candidateMinFrequency: Long,
    private val proximity: KeyProximity = KeyProximity.NONE,
    private val letterPrefilter: Boolean = true,
) {

    /**
     * Mots seuls (sans fréquence connue) : tous ont la même fréquence, donc aucun n'est
     * préféré à un autre — comportement historique (égalité = ambiguïté).
     */
    constructor(
        words: Collection<String>,
        extraWords: Collection<String> = emptySet(),
        inflections: InflectionRules = InflectionRules.NONE,
    ) : this(
        HashMap<String, Long>(words.size + extraWords.size).apply {
            words.forEach { put(it.lowercase(), 1L) }
            extraWords.forEach { put(it.lowercase(), 1L) }
        },
        inflections,
        0L,
    )

    // minuscule -> fréquence (nombre d'occurrences dans le corpus source).
    private val frequencies: Map<String, Long> = frequencies

    // Candidats de correction et de complétion : les mots de fréquence >= candidateMinFrequency. Avec une liste
    // complète (~250 000 mots et plus), les mots rares valident un mot tapé (ils ne sont pas « corrigés ») mais ne
    // sont jamais proposés : la recherche par distance d'édition reste aussi rapide qu'avec 50 000 mots, et les
    // suggestions ne se remplissent pas de fautes du corpus. 0 = tous les mots sont candidats.
    // Copies en tableaux parallèles : parcours des candidats plus rapide que sur la Map.
    private val candidateWords: Array<String> = if (candidateMinFrequency <= 0L) {
        frequencies.keys.toTypedArray()
    } else {
        frequencies.entries.asSequence().filter { it.value >= candidateMinFrequency }.map { it.key }.toList().toTypedArray()
    }
    private val candidateFrequencies: LongArray = LongArray(candidateWords.size) { frequencies.getValue(candidateWords[it]) }

    // Masque des lettres de chaque candidat (voir [letterMask]) : pré-filtre bon marché de la recherche par distance
    // d'édition. 8 octets par candidat (~400 Ko pour 50 000 mots).
    private val candidateMasks: LongArray = LongArray(candidateWords.size) { letterMask(candidateWords[it]) }

    // Indices des candidats regroupés par longueur (ordre d'origine conservé dans chaque groupe) : la
    // recherche par distance d'édition ne parcourt que les longueurs à ±distance du mot tapé, au lieu
    // des ~50 000 mots (lot 1.4 de la revue de code). Le résultat est inchangé : un candidat dont la
    // longueur s'écarte de plus que la distance autorisée était déjà écarté.
    private val indicesByLength: Array<IntArray> = run {
        val maxLength = candidateWords.maxOfOrNull { it.length } ?: 0
        val counts = IntArray(maxLength + 1)
        for (word in candidateWords) counts[word.length]++
        val groups = Array(maxLength + 1) { IntArray(counts[it]) }
        val filled = IntArray(maxLength + 1)
        for (i in candidateWords.indices) {
            val length = candidateWords[i].length
            groups[length][filled[length]++] = i
        }
        groups
    }

    // Contractions indexées sans leur apostrophe ("jai" -> "j'ai", "dont" -> "don't") : oublier
    // l'apostrophe est l'erreur la plus courante au clavier ; en cas de collision, la plus fréquente gagne.
    private val contractionsWithoutApostrophe: Map<String, String> = HashMap<String, String>().apply {
        for (i in candidateWords.indices) {
            val word = candidateWords[i]
            if ('\'' !in word) continue
            val key = word.replace("'", "")
            val existing = get(key)
            if (existing == null || candidateFrequencies[i] > frequencies.getValue(existing)) put(key, word)
        }
    }

    // Contractions dont l'écriture sans apostrophe et sans accent diffère de leur forme sans apostrophe seule
    // (« jetais » -> « j'étais »). Même règle de collision : la plus fréquente gagne.
    private val contractionsFolded: Map<String, String> = HashMap<String, String>().apply {
        for (i in candidateWords.indices) {
            val word = candidateWords[i]
            if ('\'' !in word) continue
            val key = foldAccents(word.replace("'", ""))
            if (key == word.replace("'", "")) continue // déjà couvert par contractionsWithoutApostrophe
            val existing = get(key)
            if (existing == null || candidateFrequencies[i] > frequencies.getValue(existing)) put(key, word)
        }
    }

    // Fréquence attribuée aux mots personnels : strictement supérieure à toutes celles du dictionnaire
    // (l'utilisateur les a ajoutés volontairement, ils priment sur un mot du dictionnaire à distance égale).
    private val personalFrequency: Long = (candidateFrequencies.maxOrNull() ?: 0L) + 1L

    // Mots accentués indexés par leur forme sans accent (« annee » -> [« année »]) : oublier un
    // accent est la faute la plus courante en français. Seuls les mots qui contiennent un accent
    // sont indexés (~12 000 sur 50 000).
    private val accentedByFolded: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        for (word in candidateWords) {
            val folded = foldAccents(word)
            if (folded != word) getOrPut(folded) { ArrayList(2) }.add(word)
        }
    }

    // Formes sans accent présentes dans les listes mais nettement moins fréquentes que leur forme
    // accentuée : ce sont des fautes du corpus (« ca », « etait », « tres », « duree »), pas des mots.
    // Valeur = forme accentuée à proposer. Assez strict pour laisser « a »/« à », « ou »/« où »,
    // « la »/« là » intacts (les deux formes existent et sont fréquentes).
    //
    // Deux seuils : une paire « e / é » finale (« tue / tué », « essaye / essayé », « rencontres / rencontrés ») garde
    // le seuil strict, car la forme sans accent est un vrai mot (verbe conjugué, pluriel) ; toute autre paire
    // (« ecoute / écoute », « grace / grâce », « eglise / église ») passe au seuil souple : la forme sans accent n'est
    // alors presque toujours une faute (sauf quelques exceptions, voir [PLAIN_WORDS_KEPT]).
    private val dominatedByAccented: Map<String, String> = HashMap<String, String>().apply {
        for ((folded, accented) in accentedByFolded) {
            val plainFrequency = frequencies[folded] ?: continue
            if (folded in PLAIN_WORDS_KEPT) continue
            val top = accented.maxOf { frequencies.getValue(it) }
            val leaders = accented.filter { frequencies.getValue(it) == top }
            if (leaders.size != 1) continue
            val dominance = if (isParticipleVariant(folded, leaders[0])) ACCENT_DOMINANCE else SOFT_ACCENT_DOMINANCE
            if (top >= plainFrequency * dominance) put(folded, leaders[0])
        }
    }

    /** Vrai si [word] figure dans le dictionnaire (comparaison insensible à la casse). */
    fun contains(word: String): Boolean = word.lowercase() in frequencies

    /** Fréquence de [word] dans les listes (insensible à la casse), 0 s'il n'y figure pas. */
    fun frequencyOf(word: String): Long = frequencies[word.lowercase()] ?: 0L

    /**
     * Correction suggérée pour [word] si celui-ci ne figure pas dans le
     * dictionnaire, en cherchant le mot le plus proche à une distance
     * d'édition (Damerau-Levenshtein : une inversion de deux lettres voisines compte
     * pour 1, pas pour 2) d'au plus [maxDistance]. La casse de la
     * première lettre du mot d'origine est réappliquée à la correction.
     *
     * [personalWords] (story 1.4) : mots ajoutés par l'utilisateur. Ils sont
     * considérés comme corrects (jamais corrigés) et servent aussi de
     * candidats de correction ; un mot personnel garde la casse enregistrée
     * par l'utilisateur (ex. "iPhone"), sauf s'il a été saisi en minuscules.
     *
     * Choix du candidat (story 1.14 — fréquences) : le plus proche d'abord (distance d'édition
     * minimale) ; à distance égale, le mot le plus fréquent dans le corpus l'emporte
     * (ex. "dont" -> "done" plutôt qu'un mot rare). Les mots personnels priment sur ceux du
     * dictionnaire à distance égale. Un dictionnaire sans fréquences (constructeur [Collection]) donne la même
     * fréquence à tous les mots : l'égalité reste alors ambiguë.
     *
     * Accents (story 1.14 bis) : un mot dont la seule différence avec un mot connu est l'absence
     * d'accent est d'abord rétabli (« durees » -> « durées », « etre » -> « être », « ca » -> « ça »)
     * avant toute recherche par distance d'édition. Une forme sans accent présente dans les listes
     * (faute fréquente du corpus : « ca », « etait », « tres ») est aussi corrigée quand la forme
     * accentuée est au moins 10 fois plus fréquente.
     *
     * Formes régulières : un pluriel/féminin/conjugaison régulier d'un mot connu (voir
     * [InflectionRules]) est considéré comme correct et n'est pas modifié (« durées »).
     *
     * Habitudes ([habits], phase 4) : à distance d'édition égale, et après le mot personnel, le candidat que
     * l'utilisateur a déjà laissé en place (paire « tapé -> candidat » acceptée au moins
     * [CorrectionHabits.ACCEPTANCE_THRESHOLD] fois) l'emporte, puis celui qui suit habituellement le mot précédent
     * (bigrammes), puis celui qu'il tape le plus (voir [HabitScore]), avant la proximité des touches. Elles ne
     * réordonnent que des candidats à distance égale, et peuvent lever une égalité qui laissait le mot sans
     * correction. Sans habitudes (null, ou rien d'appris), le résultat est identique. Le mot collé à une élision
     * (« t'inscrir ») est examiné sans elles : seul le reste du mot est cherché.
     *
     * Proximité des touches ([KeyProximity]) : à distance d'édition égale, le candidat dont la différence est la
     * plus plausible au clavier (touche voisine, lettre effleurée en trop) l'emporte, avant l'inversion de lettres
     * et la fréquence (« cgat » -> « chat », g et h étant voisines). Sans proximité, comportement inchangé.
     *
     * Longueur : les mots de 4 lettres ou moins ne sont corrigés qu'à distance 1 (à distance 2, la
     * moitié du mot serait changée, ex. « teh » -> « t'en »). À distance égale, une inversion de
     * deux lettres voisines (« teh » -> « the ») l'emporte sur les autres candidats.
     *
     * Cas particulier : un mot qui n'est qu'une contraction du dictionnaire sans son apostrophe
     * ("jai", "cest", "dont" en anglais) est corrigé directement en "j'ai", "c'est", "don't".
     *
     * Retourne null si :
     * - le mot est déjà dans le dictionnaire, ou dans [personalWords] (rien à corriger) ;
     * - le mot est trop court pour qu'une correction soit fiable ;
     * - le mot contient un chiffre (probablement pas un mot du langage
     *   courant : numéro, code...) ;
     * - le mot est entièrement en majuscules (probablement un acronyme) ;
     * - aucun candidat n'est assez proche, ou plusieurs candidats sont à égalité de
     *   distance ET de fréquence (ambiguïté : mieux vaut ne rien changer qu'un mauvais choix).
     */
    fun correctionFor(
        word: String,
        maxDistance: Int = 2,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): String? {
        if (word.length < MIN_WORD_LENGTH_FOR_CORRECTION) return null
        if (word.any { it.isDigit() }) return null
        if (word.length > 1 && word.all { it.isUpperCase() }) return null

        val lower = word.lowercase()

        // minuscule -> forme enregistrée par l'utilisateur
        val personal = HashMap<String, String>(personalWords.size)
        personalWords.forEach { personal[it.lowercase()] = it }
        if (lower in personal) return null
        // « l'Taipo », « d'Adrien », « Taipo's » : le clavier voit un seul mot avec apostrophe.
        if (isPersonalWithApostrophe(lower, personal)) return null

        if (lower in frequencies) {
            // Mot présent dans les listes : corrigé seulement si c'est une faute du corpus dont la
            // version accentuée domine nettement (« ca » -> « ça »).
            dominatedByAccented[lower]?.let { return applyOriginalCasing(word, it) }
            // ... ou une contraction écrite sans apostrophe, très nettement plus fréquente (« jetais » 293 contre
            // « j'étais » 276 000) : « tes » / « t'es » restent intacts, les deux formes étant courantes.
            val contraction = contractionsFolded[lower] ?: contractionsWithoutApostrophe[lower] ?: return null
            if (frequencies.getValue(contraction) < frequencies.getValue(lower) * CONTRACTION_DOMINANCE) return null
            return applyOriginalCasing(word, contraction)
        }

        // Pluriel, féminin ou conjugaison régulière d'un mot connu : correct, absent des listes.
        if (isInflectedForm(lower)) return null

        // Élision (« t'inscrire », « l'ordinateur ») ou possessif anglais (« today's ») : seul le mot
        // collé à l'apostrophe est examiné, la partie élidée est conservée telle quelle. Sans cela, la
        // recherche par distance d'édition retirait « t' » (« t'inscrire » -> « inscrire »).
        apostropheCorrection(word, lower, maxDistance, personalWords)?.let { return it.value }

        // Accent oublié sur un mot personnel (« Nae » pour « Naé ») : la forme enregistrée est la
        // correction évidente, avant toute recherche par distance d'édition.
        personalAccentRestoration(word, lower, personal)?.let { return it }

        // Apostrophe oubliée : correction directe, sans passer par la distance d'édition (où un mot
        // voisin plus fréquent, comme "est" pour "cest", l'emporterait à tort sur "c'est").
        contractionsWithoutApostrophe[lower]?.let { return applyOriginalCasing(word, it) }
        contractionsFolded[lower]?.let { return applyOriginalCasing(word, it) }

        // Accent oublié : la forme accentuée (éventuellement fléchie) est la correction évidente.
        accentRestoration(lower)?.let { return applyOriginalCasing(word, it.word) }

        // Apostrophe d'élision oubliée devant un mot connu (« tinquiete » -> « t'inquiète », « lorsquun » ->
        // « lorsqu'un »), avant la recherche par distance d'édition qui, sinon, retirerait la lettre élidée.
        elisionRestoration(lower)?.let { return applyOriginalCasing(word, it) }

        val allowedDistance = minOf(maxDistance, maxDistanceForLength(lower.length))

        var best: String? = null
        var bestDistance = allowedDistance + 1
        var bestFrequency = -1L
        var bestIsSwap = false
        var bestIsPersonal = false
        var bestHabit = HabitScore.NONE
        var bestCost = Int.MAX_VALUE
        var ambiguous = false
        // Rien d'appris : aucun départage possible, on ne calcule rien (résultat identique à l'absence d'habitudes).
        val activeHabits = habits?.takeUnless { it.isInert() }

        fun consider(candidate: String, frequency: Long, isPersonal: Boolean = false) {
            // Distance bornée : renvoie allowedDistance + 1 dès que le candidat est trop loin (même
            // résultat que damerauLevenshtein pour tout candidat retenu, mais sans calculer le reste).
            val distance = damerauLevenshteinBounded(lower, candidate, allowedDistance)
            if (distance > allowedDistance) return
            // Un mot sans accent dont la version accentuée domine (« ete » pour « été ») est une faute du corpus : jamais
            // une correction (« eet » -> « est », pas « ete »).
            if (candidate in dominatedByAccented) return
            val isSwap = distance == 1 && isAdjacentSwap(lower, candidate)
            // Habitudes : seulement pour un candidat qui peut encore passer devant ou à égalité (plus loin, il est battu).
            val habit = if (activeHabits != null && distance <= bestDistance) activeHabits.score(lower, candidate) else HabitScore.NONE
            // Coût pondéré par la proximité des touches (0 si elle est désactivée : sans effet).
            val cost = proximity.editCost(lower, candidate)
            // À distance égale : mot personnel (ajouté volontairement) > habitudes (paire acceptée > contexte >
            // usage) > faute plausible au clavier (touche voisine, lettre effleurée, inversion : coût pondéré le
            // plus bas) > inversion de deux lettres > fréquence. Sans la première règle, « nae » donnait « ane »
            // (inversion) et non « naé ».
            val better = distance < bestDistance || (
                distance == bestDistance && (
                    (isPersonal && !bestIsPersonal) || (
                        isPersonal == bestIsPersonal && (
                            habit > bestHabit || (
                                habit == bestHabit && (
                                    cost < bestCost || (
                                        cost == bestCost && (
                                            (isSwap && !bestIsSwap) || (isSwap == bestIsSwap && frequency > bestFrequency)
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            when {
                better -> {
                    bestDistance = distance
                    bestFrequency = frequency
                    bestIsSwap = isSwap
                    bestIsPersonal = isPersonal
                    bestHabit = habit
                    bestCost = cost
                    best = candidate
                    ambiguous = false
                }
                distance == bestDistance && isPersonal == bestIsPersonal && habit == bestHabit && isSwap == bestIsSwap &&
                    cost == bestCost && frequency == bestFrequency && candidate != best ->
                    ambiguous = true
            }
        }

        val minLength = maxOf(0, lower.length - allowedDistance)
        val maxLength = minOf(indicesByLength.size - 1, lower.length + allowedDistance)
        // Pré-filtre : à distance d'édition d au plus, le mot tapé et le candidat ne diffèrent que d'au plus d lettres
        // (chaque insertion, suppression ou substitution change l'ensemble des lettres d'au plus une lettre de
        // chaque côté ; une inversion ne le change pas). Un candidat qui en diffère de plus est trop loin : il était
        // déjà écarté par la distance bornée, le résultat est donc strictement identique, sans calculer la distance.
        val typedMask = letterMask(lower)
        for (length in minLength..maxLength) {
            for (i in indicesByLength[length]) {
                if (letterPrefilter) {
                    val mask = candidateMasks[i]
                    if ((typedMask and mask.inv()).countOneBits() > allowedDistance ||
                        (mask and typedMask.inv()).countOneBits() > allowedDistance
                    ) {
                        continue
                    }
                }
                consider(candidateWords[i], candidateFrequencies[i])
            }
        }
        for (candidate in personal.keys) if (candidate !in frequencies) consider(candidate, personalFrequency, isPersonal = true)

        val correction = best ?: return null
        if (bestDistance > allowedDistance || ambiguous) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    /**
     * Story 1.17 : mots proposés dans la bande de suggestions pour le mot en cours de frappe
     * [typed] (au plus [limit]). Toujours local : pas d'IA, pas d'apprentissage (décision de la
     * story 1.3).
     *
     * Ordre : d'abord la correction de [correctionFor] si le mot tapé est une faute, puis les
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
        limit: Int = SUGGESTION_LIMIT,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): List<String> {
        if (limit <= 0 || !isSuggestible(typed)) return emptyList()
        val correction = correctionFor(typed, personalWords = personalWords, habits = habits)
        return (listOfNotNull(correction) + completionsFor(typed, limit, personalWords, correction)).take(limit)
    }

    /**
     * Story 1.17 : contenu des [SUGGESTION_LIMIT] emplacements de mots de la bande (null = vide).
     *
     * [habits] : les mêmes habitudes que celles de l'autocorrection, pour que la correction affichée soit celle qui sera appliquée.
     *
     * - Si le mot tapé va être corrigé à l'espace (même [correctionFor] que l'autocorrection) :
     *   [mot tapé, correction, 1re complétion], la correction au centre (gras, elle va remplacer le
     *   mot tapé) et le mot tapé à gauche pour pouvoir la refuser.
     * - Sinon : les complétions, de gauche à droite, en simples suggestions (poids normal).
     */
    fun suggestionSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): List<WordSuggestion?> {
        val empty = List<WordSuggestion?>(SUGGESTION_LIMIT) { null }
        if (!isSuggestible(typed)) return empty
        val correction = correctionFor(typed, personalWords = personalWords, habits = habits)
        val completions = completionsFor(typed, SUGGESTION_LIMIT, personalWords, correction)
        fun completion(index: Int) = completions.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        if (correction != null) {
            return listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(correction, WordSuggestion.Kind.AUTOCORRECTION),
                completion(0),
            )
        }
        return List(SUGGESTION_LIMIT) { completion(it) }
    }

    /**
     * Contenu des [SUGGESTION_LIMIT] emplacements quand le curseur est posé sur un mot déjà écrit ([typed], le mot
     * entier, partie après le curseur comprise), au lieu d'être en train d'en taper un :
     *
     * - le mot va être corrigé (même [correctionFor] que l'autocorrection) : [mot tapé, correction, meilleure
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
    ): List<WordSuggestion?> {
        val empty = List<WordSuggestion?>(SUGGESTION_LIMIT) { null }
        if (!isSuggestible(typed)) return empty
        val correction = correctionFor(typed, personalWords = personalWords, habits = habits)
        val exclude = HashSet<String>()
        exclude += typed.lowercase()
        correction?.let { exclude += it.lowercase() }
        val original = previousForm?.takeIf { correction == null && it.isNotEmpty() && it.lowercase() != typed.lowercase() }
        original?.let { exclude += it.lowercase() }
        val alternatives = alternativesFor(typed, SUGGESTION_LIMIT, personalWords, exclude)
        fun alternative(index: Int) = alternatives.getOrNull(index)?.let { WordSuggestion(it, WordSuggestion.Kind.COMPLETION) }
        return when {
            correction != null -> listOf(
                WordSuggestion(typed, WordSuggestion.Kind.TYPED),
                WordSuggestion(correction, WordSuggestion.Kind.AUTOCORRECTION),
                alternative(0),
            )
            original != null -> listOf(WordSuggestion(original, WordSuggestion.Kind.TYPED), alternative(0), alternative(1))
            else -> List(SUGGESTION_LIMIT) { alternative(it) }
        }
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
        val typedFrequency = frequencies[lower] ?: 0L
        val seen = HashSet<String>(exclude)
        seen += lower
        val scored = ArrayList<Pair<String, Long>>()

        fun add(display: String, key: String, score: Long) {
            if (seen.add(key)) scored += display to score
        }

        accentVariants(lower).forEachIndexed { index, variant ->
            add(applyOriginalCasing(typed, variant), variant, Long.MAX_VALUE - index)
        }
        for (personal in personalWords) {
            val key = personal.lowercase()
            if (key == lower) continue
            if (key.startsWith(lower) || (lower.length >= 3 && damerauLevenshteinBounded(lower, key, 1) <= 1)) {
                add(personalDisplay(typed, personal), key, personalFrequency)
            }
        }
        if (lower.length >= MIN_WORD_LENGTH_FOR_NEIGHBORS) {
            val minFrequency = maxOf(1L, typedFrequency / NEIGHBOR_MIN_SHARE_DIVISOR)
            val typedMask = letterMask(lower)
            for (length in maxOf(0, lower.length - 1)..minOf(indicesByLength.size - 1, lower.length + 1)) {
                for (i in indicesByLength[length]) {
                    val frequency = candidateFrequencies[i]
                    if (frequency < minFrequency) continue
                    val candidate = candidateWords[i]
                    if (candidate == lower || candidate in dominatedByAccented) continue
                    val mask = candidateMasks[i]
                    if ((typedMask and mask.inv()).countOneBits() > 1 || (mask and typedMask.inv()).countOneBits() > 1) continue
                    if (damerauLevenshteinBounded(lower, candidate, 1) > 1) continue
                    // Faute plausible au clavier seulement ; sans proximité (tests, anglais sans disposition), tout compte.
                    if (proximity.isEnabled && proximity.editCost(lower, candidate) > PLAUSIBLE_SLIP_COST) continue
                    add(applyOriginalCasing(typed, candidate), candidate, frequency)
                }
            }
        }
        for (word in topCompletions(lower, limit + 3)) {
            add(applyOriginalCasing(typed, word), word, frequencies.getValue(word))
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
     * Ni le mot tapé ni [exclude] (la correction, déjà proposée à part) n'y figurent.
     */
    private fun completionsFor(
        typed: String,
        limit: Int,
        personalWords: Collection<String>,
        exclude: String?,
    ): List<String> {
        val lower = typed.lowercase()
        val result = ArrayList<String>(limit)
        // Clés minuscules déjà proposées (« Chat » et « chat » ne comptent qu'une fois).
        val seen = HashSet<String>()
        seen += lower
        exclude?.let { seen += it.lowercase() }

        fun offer(display: String, key: String) {
            if (result.size < limit && seen.add(key)) result += display
        }

        // Mots personnels : alphabétique (l'ordre de la liste), avant les mots du dictionnaire.
        for (personal in personalWords) {
            val key = personal.lowercase()
            if (key.startsWith(lower)) offer(personalDisplay(typed, personal), key)
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
                    offer(elision + personalDisplay(stem, personal), elision.lowercase() + key)
                }
            }
        }
        // Formes accentuées du mot tapé : avant les complétions (« corrige » -> « corrigé » avant « corriger »).
        if (result.size < limit && !hasElision) {
            for (variant in accentVariants(lower)) offer(applyOriginalCasing(typed, variant), variant)
        }
        if (result.size < limit) {
            for (word in topCompletions(lower, limit + 1)) offer(applyOriginalCasing(typed, word), word)
        }
        // Après une élision française (« t'insc »), mots du dictionnaire qui complètent le mot collé à
        // l'apostrophe (« t'inscrire »), à condition qu'il commence par une voyelle ou un h (« l'ordi »
        // oui, « l'cheval » non). Les contractions du dictionnaire (« j'ai ») restent proposées avant.
        if (hasElision && result.size < limit) {
            val elision = typed.substring(0, apostrophe + 1)
            val stem = typed.substring(apostrophe + 1)
            if (elision.dropLast(1).lowercase() in ELISIONS && canFollowElision(stem)) {
                for (word in topCompletions(stem.lowercase(), limit + 1)) {
                    offer(elision + applyOriginalCasing(stem, word), elision.lowercase() + word)
                }
            }
        }
        return result
    }

    /** Vrai si [stem] commence par une voyelle (accentuée ou non) ou un h : seuls ces mots s'élident. */
    private fun canFollowElision(stem: String): Boolean = stem.firstOrNull()?.lowercaseChar()?.let {
        it in "aeiouyhàâäéèêëîïôöùûüœæ"
    } == true

    /**
     * Vrai si [lower] (minuscules) est un mot personnel collé à une élision (« l'Taipo », « qu'Adrien »,
     * soit le mot personnel après la dernière apostrophe) ou suivi du possessif anglais (« Taipo's »).
     */
    private fun isPersonalWithApostrophe(lower: String, personal: Map<String, String>): Boolean {
        val last = lower.lastIndexOf('\'')
        if (last < 0) return false
        val after = lower.substring(last + 1)
        if (after.length >= MIN_WORD_LENGTH_FOR_CORRECTION && after in personal) return true
        val first = lower.indexOf('\'')
        return lower.substring(first + 1) == "s" && lower.substring(0, first) in personal
    }

    /** Résultat de [apostropheCorrection] : [value] null = mot à laisser tel quel. */
    private class ApostropheResult(val value: String?)

    /**
     * Traitement d'un mot qui contient une apostrophe et n'est pas dans les listes. Renvoie null si le
     * mot n'a pas cette forme (la recherche normale s'applique), sinon un [ApostropheResult] :
     * - élision française (« t'inscrire ») : seul le mot qui suit l'apostrophe est corrigé
     *   (« t'inscrir » -> « t'inscrire ») ; valeur null si ce mot est correct ;
     * - possessif/clitique anglais (« today's ») dont la base est un mot connu : valeur null.
     */
    private fun apostropheCorrection(
        word: String,
        lower: String,
        maxDistance: Int,
        personalWords: Collection<String>,
    ): ApostropheResult? {
        val apostrophe = lower.indexOf('\'')
        if (apostrophe <= 0 || apostrophe >= lower.length - 1) return null
        val head = lower.substring(0, apostrophe)
        val tail = lower.substring(apostrophe + 1)
        if (head in ELISIONS) {
            // Le reste du mot est examiné seul : correct (listes, forme régulière, mot personnel) ou corrigé.
            val stem = word.substring(apostrophe + 1)
            val corrected = correctionFor(stem, maxDistance, personalWords) ?: return ApostropheResult(null)
            return ApostropheResult(word.substring(0, apostrophe + 1) + corrected)
        }
        if (tail in ENGLISH_CLITICS && (head in frequencies || isInflectedForm(head))) return ApostropheResult(null)
        return null
    }

    /**
     * Mot élidé (« t'inquiète », « lorsqu'un ») dont [lower] est l'écriture sans apostrophe (et éventuellement sans
     * accent), ou null. Seul un mot absent des listes arrive ici. Le reste du mot doit commencer par une voyelle ou un
     * h, être un mot connu (ou une forme régulière, ou se retrouver en rétablissant ses accents) et assez courant ;
     * après une seule lettre élidée, il doit compter au moins 3 lettres (« tai », « lon » sont trop ambigus).
     */
    private fun elisionRestoration(lower: String): String? {
        for (prefix in ELISION_PREFIXES) {
            if (!lower.startsWith(prefix) || lower.length == prefix.length) continue
            val rest = lower.substring(prefix.length)
            if (rest.length < (if (prefix.length == 1) 3 else 2) || !canFollowElision(rest)) continue
            val word = elidedWord(rest) ?: continue
            if (!canFollowElision(word)) continue
            return "$prefix'$word"
        }
        return null
    }

    /** Le mot à placer après l'élision pour le reste [rest] tapé (accents rétablis si besoin), ou null s'il n'est pas assez sûr. */
    private fun elidedWord(rest: String): String? {
        if (rest in frequencies) {
            val word = dominatedByAccented[rest] ?: rest
            return if (frequencies.getValue(word) >= ELIDED_WORD_MIN_FREQUENCY) word else null
        }
        if (isInflectedForm(rest)) return rest
        val restored = accentRestoration(rest)?.word ?: return null
        return if (frequencies.getValue(restored) >= ELIDED_WORD_MIN_FREQUENCY) restored else null
    }

    /**
     * Formes accentuées du mot [lower] tel que tapé (« corrige » -> « corrigé », « ou » -> « où », « la » -> « là »),
     * les plus fréquentes d'abord, au plus [MAX_ACCENT_VARIANTS]. Proposées avant les complétions : un mot complet sans
     * accent est souvent un accent oublié.
     */
    private fun accentVariants(lower: String): List<String> {
        val variants = accentedByFolded[foldAccents(lower)] ?: return emptyList()
        // Plus fréquent d'abord ; une forme très rare face à la plus courante est un débris du corpus (« lã », « oû »).
        val sorted = variants.sortedByDescending { frequencies.getValue(it) }
        val top = frequencies.getValue(sorted[0])
        return sorted.asSequence()
            .filter { it != lower && frequencies.getValue(it) * MIN_VARIANT_SHARE_DIVISOR >= top }
            .take(MAX_ACCENT_VARIANTS)
            .toList()
    }

    /** Forme enregistrée d'un mot personnel qui ne diffère de [word] que par les accents, ou null. */
    private fun personalAccentRestoration(word: String, lower: String, personal: Map<String, String>): String? {
        val folded = foldAccents(lower)
        var found: String? = null
        for ((key, stored) in personal) {
            if (key == lower || foldAccents(key) != folded) continue
            if (found != null) return null // plusieurs formes possibles : ambiguïté
            found = stored
        }
        return found?.let { personalDisplay(word, it) }
    }

    /** Mot personnel tel qu'affiché : casse enregistrée si elle est particulière, sinon casse tapée. */
    private fun personalDisplay(typed: String, stored: String): String =
        if (stored != stored.lowercase()) stored else applyOriginalCasing(typed, stored)

    /**
     * Les [count] mots du dictionnaire qui commencent par [prefix] (minuscules) et sont plus longs
     * que lui : fréquence décroissante, puis longueur croissante, puis ordre alphabétique.
     * Insertion bornée : pas de tri des quelques milliers de mots qui commencent par une lettre.
     */
    private fun topCompletions(prefix: String, count: Int): List<String> {
        // Marge de 2 pour absorber les doublons avec les mots personnels déjà proposés.
        val capacity = count + 2
        val bestWords = arrayOfNulls<String>(capacity)
        val bestFrequencies = LongArray(capacity)
        var size = 0

        fun ranksBefore(word: String, frequency: Long, otherWord: String, otherFrequency: Long): Boolean = when {
            frequency != otherFrequency -> frequency > otherFrequency
            word.length != otherWord.length -> word.length < otherWord.length
            else -> word < otherWord
        }

        for (i in candidateWords.indices) {
            val word = candidateWords[i]
            if (word.length <= prefix.length || !word.startsWith(prefix)) continue
            val frequency = candidateFrequencies[i]
            if (size == capacity && !ranksBefore(word, frequency, bestWords[size - 1]!!, bestFrequencies[size - 1])) continue

            var position = if (size < capacity) size else size - 1
            while (position > 0 && ranksBefore(word, frequency, bestWords[position - 1]!!, bestFrequencies[position - 1])) {
                bestWords[position] = bestWords[position - 1]
                bestFrequencies[position] = bestFrequencies[position - 1]
                position--
            }
            bestWords[position] = word
            bestFrequencies[position] = frequency
            if (size < capacity) size++
        }
        return List(size) { bestWords[it]!! }
    }

    /** Forme accentuée trouvée pour une forme tapée. */
    private class Restoration(val word: String)

    /**
     * Forme accentuée de [lower] : un mot connu qui n'en diffère que par les accents, ou une forme
     * régulière d'un tel mot (« durees » -> « durées »). Null s'il n'y en a pas, ou si plusieurs
     * formes sont à égalité de fréquence (ambiguïté). Une forme fléchie hérite d'une fréquence
     * réduite : à égalité de lettres, le mot du dictionnaire prime.
     */
    private fun accentRestoration(lower: String): Restoration? {
        val folded = foldAccents(lower)
        var best: String? = null
        var bestFrequency = -1L
        var tie = false

        fun offer(candidate: String, frequency: Long) {
            if (candidate == lower) return
            when {
                frequency > bestFrequency -> {
                    best = candidate
                    bestFrequency = frequency
                    tie = false
                }
                frequency == bestFrequency && candidate != best -> tie = true
            }
        }

        accentedByFolded[folded]?.forEach { offer(it, frequencies.getValue(it)) }
        for (rule in inflections.rules) {
            val base = rule.baseOf(folded) ?: continue
            accentedByFolded[base]?.forEach { accentedBase ->
                val form = rule.formOf(accentedBase) ?: return@forEach
                offer(form, frequencies.getValue(accentedBase) / INFLECTED_FREQUENCY_DIVISOR)
            }
        }
        val result = best ?: return null
        return if (tie) null else Restoration(result)
    }

    /** Vrai si [lower] est une forme régulière (voir [InflectionRules]) d'un mot du dictionnaire. */
    private fun isInflectedForm(lower: String): Boolean = inflections.rules.any { rule ->
        // Une base qui est une faute du corpus (« duree ») ne valide pas sa forme fléchie (« durees »).
        rule.baseOf(lower)?.let { it in frequencies && it !in dominatedByAccented } == true
    }

    companion object {
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 2

        /** Élisions françaises (partie avant l'apostrophe, minuscules) : « l'ami », « qu'il », « jusqu'à ». */
        private val ELISIONS = setOf(
            "l", "d", "j", "t", "m", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "quelqu", "presqu",
        )

        /** Élisions classées de la plus longue à la plus courte (« lorsqu » avant « qu », « qu » avant « c »). */
        private val ELISION_PREFIXES = ELISIONS.sortedByDescending { it.length }

        /** Terminaisons anglaises après l'apostrophe : « today's », « they're », « we'll ». */
        private val ENGLISH_CLITICS = setOf("s", "t", "re", "ve", "ll", "d", "m")

        /** Story 1.17 : nombre d'emplacements de mots de la bande de suggestions. */
        const val SUGGESTION_LIMIT = 3

        /**
         * Une forme sans accent déjà présente dans les listes est corrigée si sa version accentuée
         * est au moins 10 fois plus fréquente (« ca » 190 000 contre « ça » 2 700 000). Seuil des paires
         * « e / é » finales (« tue / tué »), où la forme sans accent est un vrai mot.
         */
        private const val ACCENT_DOMINANCE = 10L

        /**
         * Seuil des autres paires : la forme accentuée est au moins 2 fois plus fréquente (« ecoute » 38 000 contre
         * « écoute » 82 000, « grace » contre « grâce »). Les formes sans accent y sont des fautes du corpus.
         */
        private const val SOFT_ACCENT_DOMINANCE = 2L

        /** Formes sans accent qui sont de vrais mots (« une tache » n'est pas « une tâche ») : jamais corrigées pour leur accent. */
        private val PLAIN_WORDS_KEPT = setOf("tache", "taches")

        /** Une contraction écrite sans apostrophe est corrigée si la contraction est au moins 100 fois plus fréquente. */
        private const val CONTRACTION_DOMINANCE = 100L

        /** Fréquence minimale du mot placé après une élision rétablie (« t'inquiète » : « inquiète » 44 000). */
        private const val ELIDED_WORD_MIN_FREQUENCY = 500L

        /** Une forme accentuée proposée en tête doit valoir au moins 1/20 de la plus fréquente des formes accentuées. */
        private const val MIN_VARIANT_SHARE_DIVISOR = 20L

        /** Longueur minimale du mot pour lui chercher des voisins (« la », « ou » seraient noyés de mots courants). */
        private const val MIN_WORD_LENGTH_FOR_NEIGHBORS = 3

        /** Un voisin proposé doit valoir au moins 1/20 de la fréquence du mot tapé (il ne doit pas être bien plus rare). */
        private const val NEIGHBOR_MIN_SHARE_DIVISOR = 20L

        /** Coût maximal de [KeyProximity.editCost] d'une faute « plausible » (touche voisine, lettre oubliée ou effleurée, inversion). */
        private const val PLAUSIBLE_SLIP_COST = 2

        /** Nombre maximal de formes accentuées proposées en tête de la bande de suggestions. */
        private const val MAX_ACCENT_VARIANTS = 2

        /** Vrai si [accented] ne diffère de [plain] que par un « é » final (ou avant un « s » final) : « tue » / « tué ». */
        private fun isParticipleVariant(plain: String, accented: String): Boolean {
            if (plain.length != accented.length) return false
            val index = if (plain.endsWith("s")) plain.length - 2 else plain.length - 1
            return index >= 0 && plain[index] == 'e' && accented == plain.substring(0, index) + "é" + plain.substring(index + 1)
        }

        /** Une forme fléchie retrouvée par les règles compte pour 1/4 de la fréquence de son mot de base. */
        private const val INFLECTED_FREQUENCY_DIVISOR = 4L

        /** Distance d'édition maximale selon la longueur du mot tapé : 1 pour 4 lettres ou moins. */
        private fun maxDistanceForLength(length: Int): Int = if (length <= 4) 1 else Int.MAX_VALUE

        /** Vrai si [b] s'obtient en inversant deux lettres voisines de [a] (« teh » / « the »). */
        internal fun isAdjacentSwap(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var i = 0
            while (i < a.length && a[i] == b[i]) i++
            if (i + 1 >= a.length) return false
            if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
            return a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
        }

        /**
         * Mot sans accents ni cédille (« durée » -> « duree », « ça » -> « ca »), œ et æ développés.
         * Attend une chaîne en minuscules ; renvoie la même instance si elle est déjà en ASCII.
         */
        fun foldAccents(word: String): String {
            if (word.all { it.code < 128 }) return word
            val decomposed = Normalizer.normalize(word.replace("œ", "oe").replace("æ", "ae"), Normalizer.Form.NFD)
            val builder = StringBuilder(decomposed.length)
            for (c in decomposed) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) builder.append(c)
            }
            return builder.toString()
        }

        /**
         * Dictionnaire avec fréquences (mot -> nombre d'occurrences). Les mots sont mis en
         * minuscules ; si deux formes ne diffèrent que par la casse, la plus fréquente est gardée.
         * [candidateMinFrequency] : seuls les mots au moins aussi fréquents peuvent être proposés comme
         * correction ou complétion ; tous les mots de [entries] restent valides (jamais corrigés).
         * [proximity] : proximité des touches du clavier, pour départager les candidats à distance égale.
         * [letterPrefilter] : pré-filtre par les lettres de la recherche par distance d'édition (résultat identique,
         * beaucoup plus rapide) ; désactivable uniquement pour mesurer ou comparer.
         */
        fun withFrequencies(
            entries: Map<String, Long>,
            inflections: InflectionRules = InflectionRules.NONE,
            candidateMinFrequency: Long = 0L,
            proximity: KeyProximity = KeyProximity.NONE,
            letterPrefilter: Boolean = true,
        ): Dictionary {
            // Liste déjà en minuscules (cas des listes de l'application) : pas de copie, ce qui évite de
            // doubler la mémoire avec une liste complète de plusieurs centaines de milliers de mots.
            if (entries.keys.all { it == it.lowercase() }) return Dictionary(entries, inflections, candidateMinFrequency, proximity, letterPrefilter)
            val merged = HashMap<String, Long>(entries.size)
            entries.forEach { (word, count) ->
                val key = word.lowercase()
                val previous = merged[key]
                if (previous == null || count > previous) merged[key] = count
            }
            return Dictionary(merged, inflections, candidateMinFrequency, proximity, letterPrefilter)
        }

        /**
         * Lit des lignes « mot fréquence » (séparées par un espace). Une ligne sans fréquence
         * valide (mot seul, ou fréquence non numérique) compte pour 1 ; les lignes vides sont ignorées.
         * Les mots de fréquence inférieure à [minFrequency] sont écartés dès la lecture (pas de pic mémoire).
         */
        fun parseFrequencyLines(lines: Sequence<String>, minFrequency: Long = 0L): Map<String, Long> {
            val result = HashMap<String, Long>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val separator = trimmed.lastIndexOf(' ')
                val word: String
                val count: Long
                if (separator > 0) {
                    val parsed = trimmed.substring(separator + 1).toLongOrNull()
                    if (parsed != null) {
                        word = trimmed.substring(0, separator).trim()
                        count = parsed
                    } else {
                        word = trimmed
                        count = 1L
                    }
                } else {
                    word = trimmed
                    count = 1L
                }
                if (word.isNotEmpty() && count >= minFrequency) result[word] = maxOf(result[word] ?: 0L, count)
            }
            return result
        }

        /** Distance d'édition de Levenshtein classique (insertion/suppression/substitution, coût 1 chacune). */
        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            val previousRow = IntArray(b.length + 1) { it }
            val currentRow = IntArray(b.length + 1)

            for (i in 1..a.length) {
                currentRow[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    currentRow[j] = minOf(
                        currentRow[j - 1] + 1,
                        previousRow[j] + 1,
                        previousRow[j - 1] + cost,
                    )
                }
                System.arraycopy(currentRow, 0, previousRow, 0, currentRow.size)
            }
            return previousRow[b.length]
        }

        /**
         * Masque de 64 bits des lettres de [word] : une lettre = un bit (a-z : 0 à 25, apostrophe : 26, trait d'union :
         * 27, autres caractères, accentués compris : 28 à 63 par repli). Deux caractères qui partagent un bit sont
         * simplement confondus : le masque ne peut que moins distinguer deux mots, jamais plus, donc le pré-filtre
         * reste exact.
         */
        internal fun letterMask(word: String): Long {
            var mask = 0L
            for (c in word) {
                val bit = when {
                    c in 'a'..'z' -> c - 'a'
                    c == '\'' -> 26
                    c == '-' -> 27
                    else -> 28 + c.code % 36
                }
                mask = mask or (1L shl bit)
            }
            return mask
        }

        /**
         * Distance d'édition de Damerau-Levenshtein (variante « optimal string alignment ») :
         * insertion, suppression, substitution et inversion de deux lettres adjacentes, coût 1
         * chacune. Une inversion (« teh » -> « the ») compte donc pour 1 et non pour 2.
         */
        fun damerauLevenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var twoRowsAgo = IntArray(b.length + 1)
            var previousRow = IntArray(b.length + 1) { it }
            var currentRow = IntArray(b.length + 1)

            for (i in 1..a.length) {
                currentRow[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = minOf(
                        currentRow[j - 1] + 1,
                        previousRow[j] + 1,
                        previousRow[j - 1] + cost,
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = minOf(value, twoRowsAgo[j - 2] + 1)
                    }
                    currentRow[j] = value
                }
                val recycled = twoRowsAgo
                twoRowsAgo = previousRow
                previousRow = currentRow
                currentRow = recycled
            }
            return previousRow[b.length]
        }

        /**
         * Même distance que [damerauLevenshtein] lorsqu'elle vaut au plus [maxDistance] ; sinon renvoie
         * `maxDistance + 1` (valeur sentinelle « trop loin »), souvent sans avoir rempli toute la matrice.
         *
         * Arrêt anticipé : une ligne de la matrice ne dépend que des deux lignes précédentes (la
         * troisième sert à l'inversion de lettres). Si le minimum de la ligne courante ET celui de la
         * précédente dépassent [maxDistance], aucune valeur ultérieure ne peut redescendre dessous.
         */
        internal fun damerauLevenshteinBounded(a: String, b: String, maxDistance: Int): Int {
            val tooFar = maxDistance + 1
            if (a == b) return 0
            if (Math.abs(a.length - b.length) > maxDistance) return tooFar
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            var twoRowsAgo = IntArray(b.length + 1)
            var previousRow = IntArray(b.length + 1) { it }
            var currentRow = IntArray(b.length + 1)
            var previousRowMin = 0

            for (i in 1..a.length) {
                currentRow[0] = i
                var rowMin = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var value = minOf(
                        currentRow[j - 1] + 1,
                        previousRow[j] + 1,
                        previousRow[j - 1] + cost,
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        value = minOf(value, twoRowsAgo[j - 2] + 1)
                    }
                    currentRow[j] = value
                    if (value < rowMin) rowMin = value
                }
                if (rowMin > maxDistance && previousRowMin > maxDistance) return tooFar
                previousRowMin = rowMin
                val recycled = twoRowsAgo
                twoRowsAgo = previousRow
                previousRow = currentRow
                currentRow = recycled
            }
            val distance = previousRow[b.length]
            return if (distance > maxDistance) tooFar else distance
        }

        /** Réapplique une majuscule initiale si [original] en avait une (ex. "Bnojour" -> "Bonjour"). */
        fun applyOriginalCasing(original: String, correction: String): String {
            if (original.isNotEmpty() && original[0].isUpperCase()) {
                return correction.replaceFirstChar { it.uppercaseChar() }
            }
            return correction
        }
    }
}
