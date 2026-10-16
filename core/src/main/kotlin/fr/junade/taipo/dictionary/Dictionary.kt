package fr.junade.taipo.dictionary

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
    internal val proximity: KeyProximity = KeyProximity.NONE,
    private val letterPrefilter: Boolean = true,
    properNames: Collection<String> = emptyList(),
) {

    // Prénoms et noms propres courants (forme avec majuscule, « Adrien », « Éric ») : les listes de fréquence sont
    // toutes en minuscules, elles ne savent donc pas qu'un mot prend une majuscule. Clé = minuscules.
    private val exactNames: Map<String, String> = properNames.associateBy { it.lowercase() }

    // Clé sans accent -> forme accentuée (« eric » -> « Éric »), pour un mot tapé en minuscules sans ses accents.
    private val foldedNames: Map<String, String> = HashMap<String, String>().apply {
        for ((key, display) in exactNames) {
            val folded = WordForms.foldAccents(key)
            if (folded != key && folded !in exactNames) putIfAbsent(folded, display)
        }
    }

    // Fréquence donnée aux prénoms quand ils sont proposés en correction (celle du seuil : jamais devant un vrai mot).
    private val nameFrequency: Long = maxOf(1L, candidateMinFrequency)

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
    internal val frequencies: Map<String, Long> = frequencies

    // Candidats de correction et de complétion : les mots de fréquence >= candidateMinFrequency. Avec une liste
    // complète (~250 000 mots et plus), les mots rares valident un mot tapé (ils ne sont pas « corrigés ») mais ne
    // sont jamais proposés : la recherche par distance d'édition reste aussi rapide qu'avec 50 000 mots, et les
    // suggestions ne se remplissent pas de fautes du corpus. 0 = tous les mots sont candidats.
    // Copies en tableaux parallèles : parcours des candidats plus rapide que sur la Map.
    internal val candidateWords: Array<String> = if (candidateMinFrequency <= 0L) {
        frequencies.keys.toTypedArray()
    } else {
        frequencies.entries.asSequence().filter { it.value >= candidateMinFrequency }.map { it.key }.toList().toTypedArray()
    }
    internal val candidateFrequencies: LongArray = LongArray(candidateWords.size) { frequencies.getValue(candidateWords[it]) }

    // Masque des lettres de chaque candidat (voir [letterMask]) : pré-filtre bon marché de la recherche par distance
    // d'édition. 8 octets par candidat (~400 Ko pour 50 000 mots).
    internal val candidateMasks: LongArray = LongArray(candidateWords.size) { letterMask(candidateWords[it]) }

    // Indices des candidats regroupés par longueur (ordre d'origine conservé dans chaque groupe) : la
    // recherche par distance d'édition ne parcourt que les longueurs à ±distance du mot tapé, au lieu
    // des ~50 000 mots (lot 1.4 de la revue de code). Le résultat est inchangé : un candidat dont la
    // longueur s'écarte de plus que la distance autorisée était déjà écarté.
    internal val indicesByLength: Array<IntArray> = run {
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
    internal val personalFrequency: Long = (candidateFrequencies.maxOrNull() ?: 0L) + 1L

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
    internal val dominatedByAccented: Map<String, String> = HashMap<String, String>().apply {
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
     * - le mot contient un chiffre (probablement pas un mot du langage courant : numéro, code...), sauf un
     *   chiffre unique tapé à la place d'une lettre voisine (voir [digitSlipCorrection]) ;
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
        val correction = correctionWithoutNames(word, maxDistance, personalWords, habits)
        return properNameForm(word, correction, personalWords) ?: correction
    }

    /**
     * Forme d'un prénom ou nom propre connu : « adrien » -> « Adrien », « adrein » -> « Adrien » (faute corrigée puis
     * mise en majuscule), « eric » -> « Éric ». Null si le mot n'est pas un nom connu ou s'il est déjà bien écrit.
     * Un mot du dictionnaire personnel garde toujours la forme choisie par l'utilisateur, et un mot tapé avec une
     * majuscule garde ses accents (« Eric » reste « Eric »). Les noms qui sont aussi des mots courants (« rose »,
     * « pierre », « will ») ne figurent pas dans les listes de noms.
     */
    private fun properNameForm(word: String, correction: String?, personalWords: Collection<String>): String? {
        if (exactNames.isEmpty() || word.length < MIN_WORD_LENGTH_FOR_CORRECTION || word.any { it.isDigit() }) return null
        if (word.all { it.isUpperCase() }) return null // acronyme ou nom écrit en capitales
        if (personalWords.any { it.equals(word, ignoreCase = true) || (correction != null && it.equals(correction, ignoreCase = true)) }) return null
        val target = correction ?: word
        exactNames[target.lowercase()]?.let { return it.takeIf { display -> display != word } }
        if (correction == null && word == word.lowercase()) foldedNames[word]?.let { return it }
        return null
    }

    private fun correctionWithoutNames(
        word: String,
        maxDistance: Int,
        personalWords: Collection<String>,
        habits: HabitContext?,
    ): String? {
        if (word.length < MIN_WORD_LENGTH_FOR_CORRECTION) return null
        if (word.any { it.isDigit() }) return digitSlipCorrection(word)
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
        // Prénoms courants : proposés en correction même s'ils sont absents des listes ou trop rares pour y être candidats.
        for (name in exactNames.keys) if ((frequencies[name] ?: 0L) < nameFrequency) consider(name, nameFrequency)

        val correction = best ?: return null
        if (bestDistance > allowedDistance || ambiguous) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    // Suggestions de la bande : voir DictionarySuggestions (audit 2.1, étape 10). Sans état : une instance paresseuse suffit.
    private val suggestions by lazy(LazyThreadSafetyMode.NONE) { DictionarySuggestions(this) }

    /** Story 1.17 : mots proposés pour le mot en cours de frappe [typed] (au plus [limit]) ; voir [DictionarySuggestions]. */
    fun suggestionsFor(
        typed: String,
        limit: Int = SUGGESTION_LIMIT,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): List<String> = suggestions.suggestionsFor(typed, limit, personalWords, habits)

    /** Story 1.17 : contenu des [SUGGESTION_LIMIT] emplacements de mots de la bande (null = vide). */
    fun suggestionSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): List<WordSuggestion?> = suggestions.suggestionSlotsFor(typed, personalWords, habits)

    /** Emplacements quand le curseur est posé sur un mot déjà écrit ([typed]) ; voir [DictionarySuggestions]. */
    fun placementSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        previousForm: String? = null,
        habits: HabitContext? = null,
    ): List<WordSuggestion?> = suggestions.placementSlotsFor(typed, personalWords, previousForm, habits)

    /** Au plus [limit] alternatives pour le mot entier [typed] ; voir [DictionarySuggestions]. */
    fun alternativesFor(
        typed: String,
        limit: Int,
        personalWords: Collection<String> = emptyList(),
        exclude: Set<String> = emptySet(),
    ): List<String> = suggestions.alternativesFor(typed, limit, personalWords, exclude)

    /** Vrai si [stem] commence par une voyelle (accentuée ou non) ou un h : seuls ces mots s'élident. */
    internal fun canFollowElision(stem: String): Boolean = stem.firstOrNull()?.lowercaseChar()?.let {
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
        if (head in APOSTROPHE_ELISIONS) {
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
    internal fun accentVariants(lower: String): List<String> {
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
    internal fun personalDisplay(typed: String, stored: String): String =
        if (stored != stored.lowercase()) stored else applyOriginalCasing(typed, stored)

    /**
     * Les [count] mots du dictionnaire qui commencent par [prefix] (minuscules) et sont plus longs
     * que lui : fréquence décroissante, puis longueur croissante, puis ordre alphabétique.
     * Insertion bornée : pas de tri des quelques milliers de mots qui commencent par une lettre.
     */
    internal fun topCompletions(prefix: String, count: Int): List<String> {
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

    /**
     * Chiffre tapé à la place d'une lettre (« 3crire » pour « écrire » : le doigt a visé la rangée du dessus). Cas
     * volontairement étroit, car un mot avec chiffre est en général un code ou un numéro : un seul chiffre, au moins
     * 4 caractères, le reste en lettres, et le chiffre remplacé par une lettre dont la touche est voisine. Corrigé
     * seulement si un seul mot du dictionnaire sort de ce remplacement (ou si l'un est nettement plus fréquent).
     */
    private fun digitSlipCorrection(word: String): String? {
        if (!proximity.isEnabled || word.length < DIGIT_SLIP_MIN_LENGTH) return null
        if (word.count { it.isDigit() } != 1 || !word.all { it.isLetterOrDigit() }) return null
        val digitIndex = word.indexOfFirst { it.isDigit() }
        val letters = word.filterIndexed { i, _ -> i != digitIndex }
        if (letters.length > 1 && letters.all { it.isUpperCase() }) return null
        val lower = word.lowercase()
        val matches = proximity.neighborsOf(word[digitIndex]).filter { it.isLetter() }
            .map { lower.replaceRange(digitIndex, digitIndex + 1, it.toString()) }
            .filter { it in frequencies }
            .sortedByDescending { frequencies.getValue(it) }
        val best = matches.firstOrNull() ?: return null
        if (matches.size > 1 && frequencies.getValue(best) == frequencies.getValue(matches[1])) return null
        return applyOriginalCasing(word, best)
    }

    companion object {
        private const val MIN_WORD_LENGTH_FOR_CORRECTION = 2
        private const val DIGIT_SLIP_MIN_LENGTH = 4

        /** Élisions françaises (partie avant l'apostrophe, minuscules) : « l'ami », « qu'il », « jusqu'à ». */
        private val ELISIONS = setOf(
            "l", "d", "j", "t", "m", "s", "n", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "quelqu", "presqu",
        )

        /**
         * Élisions italiennes (« un'altra », « dell'acqua », « all'inizio », « quest'anno ») : le corpus italien les
         * sépare (« dell' » + « acqua »), donc le mot collé n'est jamais dans la liste ; comme en français, seul le
         * mot qui suit l'apostrophe est examiné. Non utilisées pour restituer une apostrophe oubliée
         * ([ELISION_PREFIXES]) : « un » ou « all » en tête de mot y feraient trop de faux positifs en français.
         */
        private val ITALIAN_ELISIONS = setOf(
            "un", "dell", "all", "nell", "dall", "sull", "quest", "quell", "com", "anch", "sant", "bell",
        )

        /** Élisions dont le mot collé à l'apostrophe est examiné seul : françaises et italiennes. */
        internal val APOSTROPHE_ELISIONS = ELISIONS + ITALIAN_ELISIONS

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
        internal const val MIN_WORD_LENGTH_FOR_NEIGHBORS = 3

        /** Un voisin proposé doit valoir au moins 1/20 de la fréquence du mot tapé (il ne doit pas être bien plus rare). */
        internal const val NEIGHBOR_MIN_SHARE_DIVISOR = 20L

        /** Coût maximal de [KeyProximity.editCost] d'une faute « plausible » (touche voisine, lettre oubliée ou effleurée, inversion). */
        internal const val PLAUSIBLE_SLIP_COST = 2

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

        /**
         * Dictionnaire avec fréquences (mot -> nombre d'occurrences). Les mots sont mis en
         * minuscules ; si deux formes ne diffèrent que par la casse, la plus fréquente est gardée.
         * [candidateMinFrequency] : seuls les mots au moins aussi fréquents peuvent être proposés comme
         * correction ou complétion ; tous les mots de [entries] restent valides (jamais corrigés).
         * [proximity] : proximité des touches du clavier, pour départager les candidats à distance égale.
         * [letterPrefilter] : pré-filtre par les lettres de la recherche par distance d'édition (résultat identique,
         * beaucoup plus rapide) ; désactivable uniquement pour mesurer ou comparer.
         * [properNames] : prénoms et noms propres courants avec leur majuscule (voir [properNameForm]).
         */
        fun withFrequencies(
            entries: Map<String, Long>,
            inflections: InflectionRules = InflectionRules.NONE,
            candidateMinFrequency: Long = 0L,
            proximity: KeyProximity = KeyProximity.NONE,
            letterPrefilter: Boolean = true,
            properNames: Collection<String> = emptyList(),
        ): Dictionary {
            // Liste déjà en minuscules (cas des listes de l'application) : pas de copie, ce qui évite de
            // doubler la mémoire avec une liste complète de plusieurs centaines de milliers de mots.
            if (entries.keys.all { it == it.lowercase() }) return Dictionary(entries, inflections, candidateMinFrequency, proximity, letterPrefilter, properNames)
            val merged = HashMap<String, Long>(entries.size)
            entries.forEach { (word, count) ->
                val key = word.lowercase()
                val previous = merged[key]
                if (previous == null || count > previous) merged[key] = count
            }
            return Dictionary(merged, inflections, candidateMinFrequency, proximity, letterPrefilter, properNames)
        }

        // Relais vers EditDistance et WordForms (audit 2.1, étape 6) : l'API de Dictionary reste celle d'avant.
        fun levenshtein(a: String, b: String): Int = EditDistance.levenshtein(a, b)

        fun damerauLevenshtein(a: String, b: String): Int = EditDistance.damerauLevenshtein(a, b)

        internal fun damerauLevenshteinBounded(a: String, b: String, maxDistance: Int): Int =
            EditDistance.damerauLevenshteinBounded(a, b, maxDistance)

        internal fun isAdjacentSwap(a: String, b: String): Boolean = EditDistance.isAdjacentSwap(a, b)

        internal fun letterMask(word: String): Long = EditDistance.letterMask(word)

        fun foldAccents(word: String): String = WordForms.foldAccents(word)

        fun parseFrequencyLines(lines: Sequence<String>, minFrequency: Long = 0L): Map<String, Long> =
            WordForms.parseFrequencyLines(lines, minFrequency)

        fun applyOriginalCasing(original: String, correction: String): String =
            WordForms.applyOriginalCasing(original, correction)
    }
}
