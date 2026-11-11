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
    /**
     * Liste lue (mot en minuscules -> fréquence). Seuls [candidateWords] et [candidateFrequencies] la lisent : tout le reste passe
     * par [frequencies], sa copie compacte. Aucune référence n'y est gardée, pour qu'elle soit libérée après la construction.
     */
    source: Map<String, Long>,
    private val inflections: InflectionRules,
    candidateMinFrequency: Long,
    internal val proximity: KeyProximity = KeyProximity.NONE,
    private val letterPrefilter: Boolean = true,
    properNames: Collection<String> = emptyList(),
    internal val confidence: CorrectionConfidence = CorrectionConfidence.NONE,
    private val accentPolicy: AccentPolicy = AccentPolicy.FRENCH,
    /** Bigrammes livrés (point 1 de docs/autocorrection-roadmap.md) ; leur poids est [CorrectionConfidence.contextWeight]. */
    internal val ngrams: NgramModel = NgramModel.EMPTY,
) {

    // minuscule -> fréquence (nombre d'occurrences dans le corpus source), en table compacte ([CompactWordTable]) : environ
    // 40 % de la mémoire d'une HashMap pour une liste complète. Déclarée en premier : les index ci-dessous la lisent.
    private val table: CompactWordTable = CompactWordTable.from(source)
    internal val frequencies: Map<String, Long> = table

    // Prénoms et noms propres courants (forme avec majuscule, « Adrien », « Éric ») : les listes de fréquence sont
    // toutes en minuscules, elles ne savent donc pas qu'un mot prend une majuscule. Clé = minuscules.
    private val exactNames: Map<String, String> = properNames.associateBy { it.lowercase() }

    // Clé sans accent -> forme accentuée (« eric » -> « Éric »), pour un mot tapé en minuscules sans ses accents.
    private val foldedNames: Map<String, String> = HashMap<String, String>().apply {
        for ((key, display) in exactNames) {
            val folded = WordForms.foldAccents(key)
            if (folded != key && folded !in exactNames) putIfAbsent(folded, display)
            // Allemand : « juergen » -> « Jürgen », « uebung » -> « Übung » (transcription ae, oe, ue, ss).
            if (accentPolicy.transliteration) {
                val transliterated = WordForms.transliterateGerman(key)
                if (transliterated != key && transliterated !in exactNames) putIfAbsent(transliterated, display)
            }
        }
    }

    // Fréquence donnée aux prénoms quand ils sont proposés en correction (celle du seuil : jamais devant un vrai mot).
    private val nameFrequency: Long = maxOf(1L, candidateMinFrequency)

    // Noms à proposer en correction faute d'être assez fréquents dans les listes, rangés par longueur (comme les candidats des listes) :
    // avec une liste de noms allemande de plusieurs dizaines de milliers de mots, parcourir tous les noms à chaque correction coûterait trop cher.
    private val rareNamesByLength: Array<List<String>> by lazy(LazyThreadSafetyMode.NONE) {
        val rare = exactNames.keys.filter { (frequencies[it] ?: 0L) < nameFrequency }
        val maxLength = rare.maxOfOrNull { it.length } ?: 0
        Array(maxLength + 1) { length -> rare.filter { it.length == length } }
    }

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

    // Candidats de correction et de complétion : les mots de fréquence >= candidateMinFrequency. Avec une liste
    // complète (~250 000 mots et plus), les mots rares valident un mot tapé (ils ne sont pas « corrigés ») mais ne
    // sont jamais proposés : la recherche par distance d'édition reste aussi rapide qu'avec 50 000 mots, et les
    // suggestions ne se remplissent pas de fautes du corpus. 0 = tous les mots sont candidats.
    // Copies en tableaux parallèles : parcours des candidats plus rapide que sur la Map.
    // Lues dans [source] (et non dans la table compacte) : mêmes chaînes, même ordre qu'avant, sans en recréer.
    internal val candidateWords: Array<String> = if (candidateMinFrequency <= 0L) {
        source.keys.toTypedArray()
    } else {
        source.entries.asSequence().filter { it.value >= candidateMinFrequency }.map { it.key }.toList().toTypedArray()
    }
    internal val candidateFrequencies: LongArray = LongArray(candidateWords.size) { source.getValue(candidateWords[it]) }

    // Mots valides mais trop rares pour être candidats (« sédentaire ») : retrouvés seulement par leurs accents (voir
    // [RareWords]). Null quand tous les mots sont candidats.
    private val rareWords: RareWords? = RareWords.forCandidates(table, candidateMinFrequency, candidateWords)

    /**
     * Probabilité de la lettre suivante d'après le début du mot tapé (zones de frappe adaptatives), sur les candidats.
     * Construit à la première demande (tri de la liste : quelques dizaines de millisecondes) : la première demande se fait
     * en arrière-plan, avec le préchargement du dictionnaire, jamais sur le thread principal.
     */
    val nextLetterModel: NextLetterModel get() = nextLetterLazy.value

    private val nextLetterLazy = lazy(LazyThreadSafetyMode.PUBLICATION) {
        NextLetterModel.fromCandidates(candidateWords, candidateFrequencies)
    }

    /** Le modèle de la lettre suivante s'il est déjà construit, sinon null, sans jamais le construire : pour le thread principal. */
    val nextLetterModelIfBuilt: NextLetterModel? get() = if (nextLetterLazy.isInitialized()) nextLetterLazy.value else null

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

    // Écriture sans ligature -> écriture avec ligature (« coeur » -> « cœur », « oeuvre » -> « œuvre ») : voir
    // [FrenchCanonicalizer.ligatureRestorations]. Vide pour les langues sans « œ » dans leur liste.
    private val ligatureForms: Map<String, String> =
        FrenchCanonicalizer.ligatureRestorations(candidateWords, candidateFrequencies) { frequencies[it] ?: 0L }

    // Fréquence attribuée aux mots personnels : strictement supérieure à toutes celles du dictionnaire
    // (l'utilisateur les a ajoutés volontairement, ils priment sur un mot du dictionnaire à distance égale).
    internal val personalFrequency: Long = (candidateFrequencies.maxOrNull() ?: 0L) + 1L

    // Fréquence au-delà de laquelle un mot de la liste est courant : un mot personnel qui n'en diffère que par ses accents ne le
    // remplace pas (« Côme » ne remplace pas l'anglais « come »). En dessous, la forme sans accent est un mot rare ou un nom écrit
    // sans ses accents (« nae », « chloe ») : le mot personnel s'applique (voir [correctionWithoutNames]).
    private val commonWordFrequency: Long = (personalFrequency - 1L) / PERSONAL_ACCENT_COMMON_SHARE

    // Mots accentués indexés par leur forme sans accent (« annee » -> [« année »]) : oublier un
    // accent est la faute la plus courante en français. Seuls les mots qui contiennent un accent
    // sont indexés (~12 000 sur 50 000).
    private val accentedByFolded: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        for (word in candidateWords) {
            val folded = foldAccents(word)
            if (folded != word) getOrPut(folded) { ArrayList(2) }.add(word)
        }
    }

    // Allemand : mots à umlaut ou ß indexés par leur transcription (« für » -> « fuer », « straße » -> « strasse »), pour un mot tapé
    // avec « ae / oe / ue » au lieu de l'umlaut. Vide pour les autres langues ([AccentPolicy.transliteration]).
    private val accentedByTranslit: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        if (!accentPolicy.transliteration) return@apply
        for (word in candidateWords) {
            val transliterated = WordForms.transliterateGerman(word)
            if (transliterated != word) getOrPut(transliterated) { ArrayList(2) }.add(word)
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
    // alors presque toujours une faute (sauf quelques exceptions, voir [AccentPolicy.keptPlain]).
    internal val dominatedByAccented: Map<String, String> = HashMap<String, String>().apply {
        for ((folded, accented) in accentedByFolded) {
            val plainFrequency = frequencies[folded] ?: continue
            if (folded in accentPolicy.keptPlain) continue
            val top = accented.maxOf { frequencies.getValue(it) }
            val leaders = accented.filter { frequencies.getValue(it) == top }
            if (leaders.size != 1) continue
            val dominance = accentPolicy.dominanceFor(folded, leaders[0])
            if (top >= plainFrequency * dominance) put(folded, leaders[0])
        }
        // Allemand : une transcription présente dans les listes (« ueber », « buero ») mais très rare face au mot à umlaut.
        for ((transliterated, accented) in accentedByTranslit) {
            val plainFrequency = frequencies[transliterated] ?: continue
            if (transliterated in accentPolicy.keptPlain || containsKey(transliterated)) continue
            val top = accented.maxOf { frequencies.getValue(it) }
            val leaders = accented.filter { frequencies.getValue(it) == top }
            if (leaders.size != 1) continue
            if (top >= plainFrequency * accentPolicy.dominanceFor(transliterated, leaders[0])) put(transliterated, leaders[0])
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
     *
     * [trace] (phase 6) : si fourni, reçoit le chemin pris et la marge de la décision (voir [DecisionTrace]). Il ne change
     * jamais le résultat.
     */
    fun correctionFor(
        word: String,
        maxDistance: Int = 2,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
        trace: DecisionTrace? = null,
        previousWord: String? = null,
    ): String? {
        val correction = correctionWithoutNames(word, maxDistance, personalWords, habits, trace, previousWord)
        var result = properNameForm(word, correction, personalWords) ?: correction
        // Point 4 : « … avec Kubernets » : un mot inconnu à majuscule juste après un autre mot est sans doute un nom propre ; il n'est
        // remplacé que par un nom connu (« Adrein » -> « Adrien »), jamais par un mot courant.
        if (result != null &&
            confidence.protectsMidSentenceCapitals &&
            previousWord != null &&
            isCapitalized(word) &&
            word.lowercase() !in frequencies &&
            result.lowercase() !in exactNames
        ) {
            result = null
        }
        // Phase 5 : une correction annulée deux fois (voir [HabitContext.isRefused]) n'est plus proposée nulle part, ni à
        // l'espace ni dans la bande : celle-ci montre toujours ce que l'autocorrection applique.
        val refused = result != null && habits != null && habits.isRefused(word, result)
        // Phase 6 : [trace] (null hors debug) retient seulement le chemin pris et le refus, jamais le texte.
        if (trace != null) {
            if (refused) trace.refusedByHabits = true
            if (result != null && trace.path == DecisionTrace.Path.NONE) trace.path = DecisionTrace.Path.DIRECT
        }
        return if (refused) null else result
    }

    /**
     * Tournure habituelle à mettre à la place de [word], même s'il est valide (voir [PhraseHabits]) : à appeler quand
     * [correctionFor] n'a rien changé. Null sans habitudes, si [word] est un mot personnel (choix volontaire) ou si les
     * habitudes ne disent pas clairement autre chose. Le mot habituel doit être connu (liste ou mot personnel). La casse
     * du mot tapé est reprise.
     */
    fun habitPhraseCorrectionFor(
        word: String,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
    ): String? {
        if (habits == null || habits.isInert() || word.length < PhraseHabits.MIN_WORD_LENGTH) return null
        if (personalWords.any { it.equals(word, ignoreCase = true) }) return null
        val known = { candidate: String -> candidate in frequencies || personalWords.any { it.equals(candidate, ignoreCase = true) } }
        val candidate = habits.phraseCorrectionFor(word, known) ?: return null
        val stored = personalWords.firstOrNull { it.equals(candidate, ignoreCase = true) }
        return stored ?: applyOriginalCasing(word, candidate)
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
        if (correction == null && word == word.lowercase()) {
            val restored = foldedNames[word]
            // Allemand : la liste contient des noms (« Höhe », « Bär »), dont la forme sans umlaut est parfois un autre mot juste
            // (« hohe », « bar ») : un mot de la liste n'est jamais remplacé par un nom pour son umlaut seul.
            if (restored != null && !(accentPolicy.nounsKeepValidWords && word in frequencies)) return restored
        }
        return null
    }

    private fun correctionWithoutNames(
        word: String,
        maxDistance: Int,
        personalWords: Collection<String>,
        habits: HabitContext?,
        trace: DecisionTrace?,
        previousWord: String? = null,
    ): String? {
        if (word.length < MIN_WORD_LENGTH_FOR_CORRECTION) return null
        if (word.any { it.isDigit() }) return digitSlipCorrection(word)
        if (word.length > 1 && word.all { it.isUpperCase() }) return null

        val lower = word.lowercase()

        // minuscule -> forme enregistrée par l'utilisateur
        val personal = HashMap<String, String>(personalWords.size)
        personalWords.forEach { personal[it.lowercase()] = it }
        // Mot personnel tapé en minuscules alors qu'il a été enregistré avec une majuscule (« brice » pour « Brice », « iphone »
        // pour « iPhone ») : la forme enregistrée s'applique, comme pour un prénom de la liste (« adrien » -> « Adrien »).
        personal[lower]?.let { stored -> return stored.takeIf { word == lower && it != lower } }
        // Point 4 : un mot inconnu que l'utilisateur a déjà gardé deux fois est un mot à lui (nom, argot, sigle) : plus de correction.
        if (habits != null && lower !in frequencies && habits.isLearnedWord(lower)) return null
        // « l'Taipo », « d'Adrien », « Taipo's » : le clavier voit un seul mot avec apostrophe.
        if (isPersonalWithApostrophe(lower, personal)) return null

        if (lower in frequencies) {
            // Accent oublié sur un mot personnel dont la forme sans accent est un mot rare de la liste (« nae », mot écossais de la
            // liste anglaise, pour « Naé ») : le mot ajouté volontairement par l'utilisateur l'emporte.
            if (frequencies.getValue(lower) < commonWordFrequency) personalAccentRestoration(word, lower, personal)?.let { return it }
            // « coeur » -> « cœur » : la liste contient les deux écritures, seule celle avec la ligature est juste.
            ligatureForms[lower]?.let { return applyOriginalCasing(word, it) }
            // Mot présent dans les listes : corrigé seulement si c'est une faute du corpus dont la
            // version accentuée domine nettement (« ca » -> « ça »).
            dominatedByAccented[lower]?.let { return applyOriginalCasing(word, it) }
            // ... ou une contraction écrite sans apostrophe, très nettement plus fréquente (« jetais » 293 contre
            // « j'étais » 276 000) : « tes » / « t'es » restent intacts, les deux formes étant courantes.
            val contraction = contractionsFolded[lower] ?: contractionsWithoutApostrophe[lower] ?: return null
            if (frequencies.getValue(contraction) < frequencies.getValue(lower) * CONTRACTION_DOMINANCE) return null
            // Point 1 (bigrammes) : le mot précédent dit lequel des deux est attendu (« la lune » reste « lune », pas « la l'une » ;
            // « fallen ill » reste « ill », pas « I'll »).
            if (contextPrefers(previousWord, lower, contraction)) return null
            return applyOriginalCasing(word, contraction)
        }

        // Contraction écrite sans apostrophe, absente des listes (« thats » -> « that's », « theyre » -> « they're ») : avant les
        // flexions, car « thats » se lirait « that » + « s », un pluriel régulier.
        contractionsWithoutApostrophe[lower]?.let { return applyOriginalCasing(word, it) }

        // Pluriel, féminin ou conjugaison régulière d'un mot connu : correct, absent des listes.
        if (isInflectedForm(lower)) return null

        // Mot composé (allemand) : juste s'il se découpe en parties connues et n'est pas une faute de frappe d'un mot fréquent.
        if (isCompoundForm(lower)) return null

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
        accentRestoration(lower)?.let { return applyOriginalCasing(word, ligatureForms[it.word] ?: it.word) }

        // Apostrophe d'élision oubliée devant un mot connu (« tinquiete » -> « t'inquiète », « lorsquun » ->
        // « lorsqu'un »), avant la recherche par distance d'édition qui, sinon, retirerait la lettre élidée.
        elisionRestoration(lower)?.let { return applyOriginalCasing(word, it) }

        val allowedDistance = minOf(maxDistance, maxDistanceForLength(lower.length))
        // Rien d'appris : aucun départage possible, on ne calcule rien (résultat identique à l'absence d'habitudes).
        val activeHabits = habits?.takeUnless { it.isInert() }

        var contest = searchCandidates(lower, allowedDistance, personal, activeHabits, previousWord)
        // Accent oublié sur un mot rare (« sedentaire » -> « sédentaire ») : seulement si aucun mot proposable n'est à une seule
        // faute ; il passe alors devant un mot proposable plus éloigné (« dentaire », à deux fautes). Avant toute recherche, il
        // prenait la place de fautes de frappe de mots courants (banc d'autocorrection).
        val rare = if (contest.best == null || contest.bestDistance > 1) rareWords?.accentRestoration(lower, ::keepsTypedAccents) else null
        if (rare != null) return applyOriginalCasing(word, rare)
        // Mot long sans aucun candidat à distance 2 ou moins : trois erreurs d'édition sont tentées (phase 3), sous réserve
        // d'une marge forte (voir [CorrectionConfidence]). Pas si l'appelant a volontairement limité la distance.
        if (contest.best == null && maxDistance >= 2 && confidence.allowsThreeEditsFor(lower.length)) {
            trace?.threeEditsSearched = true
            contest = searchCandidates(lower, confidence.longWordDistance, personal, activeHabits, previousWord)
        }

        // Décision de déclenchement (phase 3) : le meilleur candidat ne s'applique que si sa marge sur le suivant atteint
        // le seuil de son type de faute. Une égalité stricte (marge nulle) ne corrige jamais.
        val correction = contest.best ?: return null
        // Point 4 : un mot court à deux erreurs de tout mot connu est souvent un mot juste inconnu (nom, argot) ; on ne le remplace
        // que si le mot précédent soutient la correction.
        if (contest.bestDistance == 2 && lower.length < confidence.twoEditsMinLengthWithoutContext && !contextSupports(previousWord, correction)) return null
        val kind = confidence.kindOf(lower, correction, contest.bestDistance, contest.bestIsSwap, contest.bestCost)
        val margin = contest.margin()
        val accepted = confidence.accepts(kind, margin, contest.bestCost)
        if (trace != null) {
            trace.path = DecisionTrace.Path.SEARCH
            trace.kind = kind
            trace.margin = margin
            trace.accepted = accepted
        }
        if (!accepted) return null
        val stored = personal[correction]
        return if (stored != null && stored != stored.lowercase()) stored else applyOriginalCasing(word, correction)
    }

    /** Majuscule initiale suivie d'au moins une minuscule (« Paris », pas « PARIS » ni « paris »). */
    private fun isCapitalized(word: String): Boolean =
        word.length > 1 && word[0].isUpperCase() && word.drop(1).any { it.isLowerCase() }

    /** Vrai si le modèle de bigrammes attend [candidate] après [previousWord] plus que sans contexte (point 4). */
    private fun contextSupports(previousWord: String?, candidate: String): Boolean =
        previousWord != null && confidence.contextWeight > 0.0 && ngrams.contextCount(previousWord) > 0 && ngrams.bonus(previousWord, candidate) > 0.0

    /** Vrai si, après [previousWord], le modèle de bigrammes attend nettement plus [typed] que [replacement] (point 1). */
    private fun contextPrefers(previousWord: String?, typed: String, replacement: String): Boolean {
        if (previousWord == null || confidence.contextWeight <= 0.0 || ngrams.contextCount(previousWord) == 0) return false
        return ngrams.bonus(previousWord, typed) > ngrams.bonus(previousWord, replacement)
    }

    /**
     * Cherche dans les listes, les mots personnels et les prénoms les candidats à au plus [allowedDistance] erreurs
     * d'édition de [lower] et les range dans un [CandidateContest] (le meilleur, le suivant, la marge). Ne décide rien.
     */
    private fun searchCandidates(
        lower: String,
        allowedDistance: Int,
        personal: Map<String, String>,
        activeHabits: HabitContext?,
        previousWord: String? = null,
    ): CandidateContest {
        // Sans géométrie de clavier, le coût de saisie vaut 0 : une erreur y compte alors comme une erreur quelconque (COST x 4).
        val penalty = confidence.distancePenalty?.let { if (proximity === KeyProximity.NONE) it + NO_PROXIMITY_EDIT_PENALTY else it }
        val contest = CandidateContest(penalty)
        // Point 1 (bigrammes) : le mot précédent départage les candidats, s'il est connu du modèle et que la langue lui donne un poids.
        val context = previousWord?.takeIf { confidence.contextWeight > 0.0 && ngrams.contextCount(it) > 0 }

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
            val habit = if (activeHabits != null && distance <= contest.bestDistance) activeHabits.score(lower, candidate) else HabitScore.NONE
            // Coût pondéré par la proximité des touches (0 si elle est désactivée : sans effet).
            val cost = proximity.editCost(lower, candidate)
            // À distance égale, le meilleur score gagne (voir [CandidateScore] : mot personnel, habitudes, plausibilité de la
            // faute au clavier, inversion, fréquence). L'écart avec le suivant est la marge de confiance.
            var score = CandidateScore.of(frequency, cost, isSwap, isPersonal, habit)
            if (context != null) score += confidence.contextWeight * ngrams.bonus(context, candidate)
            contest.offer(candidate, distance, score, cost, isSwap)
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
        val rareNames = rareNamesByLength
        for (length in minLength..minOf(rareNames.size - 1, lower.length + allowedDistance)) {
            for (name in rareNames[length]) consider(name, nameFrequency)
        }
        return contest
    }

    // Suggestions de la bande : voir DictionarySuggestions (audit 2.1, étape 10). Sans état : une instance paresseuse suffit.
    private val suggestions by lazy(LazyThreadSafetyMode.NONE) { DictionarySuggestions(this) }

    /** Story 1.17 : mots proposés pour le mot en cours de frappe [typed] (au plus [limit]) ; voir [DictionarySuggestions]. */
    fun suggestionsFor(
        typed: String,
        limit: Int = SUGGESTION_LIMIT,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
        previousWord: String? = null,
    ): List<String> = suggestions.suggestionsFor(typed, limit, personalWords, habits, previousWord)

    /** Story 1.17 : contenu des [SUGGESTION_LIMIT] emplacements de mots de la bande (null = vide). */
    fun suggestionSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        habits: HabitContext? = null,
        participle: ParticipleFix? = null,
        previousWord: String? = null,
    ): List<WordSuggestion?> = suggestions.suggestionSlotsFor(typed, personalWords, habits, participle, previousWord)

    /** Emplacements quand le curseur est posé sur un mot déjà écrit ([typed]) ; voir [DictionarySuggestions]. */
    fun placementSlotsFor(
        typed: String,
        personalWords: Collection<String> = emptyList(),
        previousForm: String? = null,
        habits: HabitContext? = null,
        participle: ParticipleFix? = null,
        previousWord: String? = null,
    ): List<WordSuggestion?> = suggestions.placementSlotsFor(typed, personalWords, previousForm, habits, participle, previousWord)

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
        // Apostrophe initiale (néerlandais « 's avonds », « 't », anglais « 'em ») : forme voulue, jamais corrigée.
        if (apostrophe == 0) return ApostropheResult(null)
        // Apostrophe finale après un mot connu : possessif d'un pluriel (« teachers' », « days' ») ou élision finale italienne (« po' »).
        if (apostrophe == lower.length - 1 && apostrophe > 1) {
            val head = lower.substring(0, apostrophe)
            if (head in frequencies || isInflectedForm(head)) return ApostropheResult(null)
        }
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
        if (!confidence.restoresElisions) return null
        for (prefix in ELISION_PREFIXES) {
            if (!lower.startsWith(prefix) || lower.length == prefix.length) continue
            val rest = lower.substring(prefix.length)
            val minRest = if (prefix.length == 1) confidence.singleLetterElisionMinRest else 2
            if (rest.length < minRest || !canFollowElision(rest)) continue
            val word = elidedWord(rest) ?: continue
            if (!canFollowElision(word)) continue
            // « c' » ne s'élide que devant « ce » ou « ça » : « c'est », « c'était », « c'en » (« cait » n'est pas « c'ait »).
            if (confidence.cElisionOnlyBeforeE && prefix == "c" && word.first() !in "eéèêë") continue
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
        // Une forme fléchie rétablie (« olés » pour « olé ») n'a pas de fréquence propre : trop incertaine pour une élision
        // (getValue la faisait planter : trouvé par le banc d'autocorrection, phase 0).
        return if ((frequencies[restored] ?: 0L) >= ELIDED_WORD_MIN_FREQUENCY) restored else null
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
     * Indices des candidats ([candidateWords]) dans l'ordre alphabétique (ordre de [String.compareTo]) : les mots qui commencent
     * par un même début y forment une plage contiguë, trouvée par dichotomie ([firstSortedAtLeast]). Avant cet index,
     * [topCompletions] parcourait tous les candidats à chaque lettre tapée (~0,6 ms sur PC quelle que soit la longueur du début,
     * 16 à 32 ms mesurés sur Pixel 9 en build debug). Construit à la première demande, ou au préchargement ([warmSuggestionIndex]).
     */
    private val sortedCandidates: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        candidateWords.indices.sortedWith { a, b -> candidateWords[a].compareTo(candidateWords[b]) }.toIntArray()
    }

    /** Construit l'index des complétions s'il ne l'est pas encore (préchargement, hors du thread principal). */
    fun warmSuggestionIndex() {
        sortedCandidates
    }

    /** Première position de [sortedCandidates] dont le mot est >= [prefix] (taille de l'index s'il n'y en a pas). */
    private fun firstSortedAtLeast(prefix: String): Int {
        val sorted = sortedCandidates
        var low = 0
        var high = sorted.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (candidateWords[sorted[middle]] < prefix) low = middle + 1 else high = middle
        }
        return low
    }

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

        // Seuls les candidats qui commencent par [prefix] sont parcourus : une plage contiguë de l'index trié (le classement
        // ne dépend pas de l'ordre de parcours : fréquence, longueur puis ordre alphabétique départagent tout).
        val sorted = sortedCandidates
        var at = firstSortedAtLeast(prefix)
        while (at < sorted.size) {
            val i = sorted[at++]
            val word = candidateWords[i]
            if (!word.startsWith(prefix)) break
            if (word.length <= prefix.length) continue
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

    /**
     * Point 5 de docs/autocorrection-roadmap.md : complétions tolérantes à UNE faute dans le début tapé (« aujoudr » -> « aujourd'hui »,
     * « ordona » -> « ordinateur »), les plus fréquentes d'abord, au plus [count]. Seuls les mots dont un début est à exactement une
     * erreur (touche remplacée, oubliée, ajoutée ou inversion) de [prefix] y figurent : ceux qui commencent par [prefix] sont déjà
     * dans [topCompletions]. Rien pour un début de moins de [FUZZY_MIN_PREFIX] lettres (trop de mots à une erreur près).
     */
    internal fun fuzzyCompletions(prefix: String, count: Int): List<String> {
        val n = prefix.length
        if (n < FUZZY_MIN_PREFIX || count <= 0) return emptyList()
        val bestWords = ArrayList<String>(count + 1)
        val bestFrequencies = ArrayList<Long>(count + 1)
        // Lignes de la distance d'édition (avec inversion) entre [prefix] et le début du mot, réutilisées d'un mot à l'autre.
        var before = IntArray(n + 1)
        var previous = IntArray(n + 1)
        var current = IntArray(n + 1)
        // Pré-filtre : à une erreur près, au plus une lettre du début tapé manque au mot (même idée que [searchCandidates]).
        val typedMask = letterMask(prefix)
        for (i in candidateWords.indices) {
            if ((typedMask and candidateMasks[i].inv()).countOneBits() > 1) continue
            val frequency = candidateFrequencies[i]
            if (bestWords.size == count && frequency <= bestFrequencies[count - 1]) continue
            val word = candidateWords[i]
            if (word.length < n || word.startsWith(prefix)) continue
            // Début du mot à une erreur de [prefix] : la ligne j = n - 1, n ou n + 1 lettres du mot doit atteindre 1 en colonne n.
            for (k in 0..n) previous[k] = k
            var found = false
            var j = 1
            val lastRow = minOf(word.length, n + 1)
            while (j <= lastRow) {
                current[0] = j
                var rowMin = j
                val c = word[j - 1]
                for (k in 1..n) {
                    val cost = if (prefix[k - 1] == c) 0 else 1
                    var value = minOf(previous[k] + 1, current[k - 1] + 1, previous[k - 1] + cost)
                    if (j > 1 && k > 1 && prefix[k - 1] == word[j - 2] && prefix[k - 2] == c) value = minOf(value, before[k - 2] + 1)
                    current[k] = value
                    if (value < rowMin) rowMin = value
                }
                if (j >= n - 1 && current[n] <= 1) {
                    found = true
                    break
                }
                if (rowMin > 1) break
                val recycled = before
                before = previous
                previous = current
                current = recycled
                j++
            }
            if (!found || word in dominatedByAccented) continue
            var position = bestWords.size
            while (position > 0 && frequency > bestFrequencies[position - 1]) position--
            bestWords.add(position, word)
            bestFrequencies.add(position, frequency)
            if (bestWords.size > count) {
                bestWords.removeAt(count)
                bestFrequencies.removeAt(count)
            }
        }
        return bestWords
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

        // Les accents déjà tapés sont gardés : « grösser » -> « größer », pas « großer » (plus fréquent, mais sans le « ö » tapé).
        // Si aucune forme ne les contient tous, toutes restent possibles (accent mal placé).
        val direct = (accentedByFolded[folded].orEmpty() + accentedByTranslit[lower].orEmpty())
        val keepingTyped = direct.filter { keepsTypedAccents(lower, it) }
        (keepingTyped.ifEmpty { direct }).forEach { offer(it, frequencies.getValue(it)) }
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

    /** Vrai si [candidate] contient au moins autant de fois chaque lettre accentuée de [typed] (« größer » pour « grösser », pas « großer »). */
    private fun keepsTypedAccents(typed: String, candidate: String): Boolean {
        for (c in typed) {
            if (c in 'a'..'z') continue
            if (candidate.count { it == c } < typed.count { it == c }) return false
        }
        return true
    }

    /** Vrai si [lower] est une forme régulière (voir [InflectionRules]) d'un mot du dictionnaire. */
    private fun isInflectedForm(lower: String): Boolean = inflections.rules.any { rule ->
        // Une base qui est une faute du corpus (« duree ») ne valide pas sa forme fléchie (« durees »).
        rule.baseOf(lower)?.let {
            it in frequencies &&
                it !in dominatedByAccented &&
                frequencies.getValue(it) >= inflections.minBaseFrequency &&
                !isUndoubledConsonant(it, rule)
        } == true
    }

    /**
     * Allemand, proposition seulement (jamais appliquée d'office) : « Haus » + « tür » -> « Haustür ». Vrai composé si les deux mots sont des
     * noms de la liste de noms (voir [properNameForm]) et si leur réunion, avec une liaison de [CompoundRules.links] au besoin
     * (« Arbeit » + « zimmer » -> « Arbeitszimmer »), est un mot des listes ou un composé valide. Parmi plusieurs liaisons valides, la plus
     * fréquente l'emporte. Le composé est un nom : majuscule initiale, le second mot passe en minuscules. Null : rien à proposer.
     *
     * Limite : la liste de noms contient aussi des prénoms, « Herr Thomas » peut donc être proposé en « Herrthomas » si les deux sont connus.
     */
    fun compoundMergeFor(first: String, second: String): String? {
        val rules = inflections.compounds ?: return null
        if (first.length < MIN_MERGE_PART_LENGTH || second.length < MIN_MERGE_PART_LENGTH) return null
        if (!first.all { it.isLetter() } || !second.all { it.isLetter() }) return null
        val firstLower = first.lowercase()
        val secondLower = second.lowercase()
        if (firstLower !in exactNames || secondLower !in exactNames) return null
        var best: String? = null
        var bestFrequency = -1L
        for (link in rules.links) {
            val merged = firstLower + link + secondLower
            val frequency = frequencies[merged] ?: 0L
            val known = frequency >= nameFrequency && merged !in dominatedByAccented
            if (!known && !isCompoundForm(merged)) continue
            if (frequency > bestFrequency) {
                bestFrequency = frequency
                best = first.replaceFirstChar { it.uppercaseChar() } + link + secondLower
            }
        }
        return best
    }

    /**
     * Vrai si [lower] est un composé valide (voir [CompoundRules]) : au moins deux parties connues, et aucun mot fréquent à une faute de
     * frappe (« krankenhauss » se découpe en « krankenhaus » + « s », mais reste une faute de « krankenhaus »). Un mot à chiffre ou apostrophe
     * n'est jamais un composé.
     */
    private fun isCompoundForm(lower: String): Boolean {
        val rules = inflections.compounds ?: return false
        if (lower.length < 2 * rules.minPartLength || !lower.all { it.isLetter() }) return false
        // Un mot des listes qui n'en diffère que par les accents, l'umlaut ou ß l'emporte sur le découpage : « grosszügig » est « großzügig »
        // oublié, pas « gross » + « zügig » ; « gewissermassen » est « gewissermaßen ».
        if (accentedByFolded[foldAccents(lower)]?.any { it != lower } == true || accentedByTranslit.containsKey(lower)) return false
        if (!splitsIntoKnownParts(lower, rules)) return false
        return searchCandidates(lower, 1, emptyMap(), null).best == null
    }

    /** Découpe [lower] en parties connues d'au moins [CompoundRules.minPartLength] lettres, reliées par une liaison de [CompoundRules.links]. */
    private fun splitsIntoKnownParts(lower: String, rules: CompoundRules): Boolean {
        val length = lower.length
        val minPart = rules.minPartLength
        // startsHere[i] : le début de [lower] jusqu'à i est découpé et une partie peut commencer en i.
        val startsHere = BooleanArray(length + 1)
        startsHere[0] = true
        for (start in 0 until length) {
            if (!startsHere[start]) continue
            for (end in start + minPart..length) {
                val part = lower.substring(start, end)
                if ((frequencies[part] ?: 0L) < nameFrequency || part in dominatedByAccented) continue
                if (end == length) {
                    if (start > 0) return true
                    continue
                }
                for (link in rules.links) {
                    val next = end + link.length
                    if (next <= length - minPart && lower.startsWith(link, end)) startsHere[next] = true
                }
            }
        }
        return false
    }

    /** « occur » + « ed » : la forme juste double le r (« occurred », connue) ; « occured » est une faute. Voir [InflectionRules.checksDoubledConsonant]. */
    private fun isUndoubledConsonant(base: String, rule: InflectionRule): Boolean {
        if (!inflections.checksDoubledConsonant || rule.baseEnding.isNotEmpty() || (rule.suffix != "ed" && rule.suffix != "ing")) return false
        if (base.length < 3) return false
        val last = base.last()
        if (last in "aeiouwxy" || base[base.length - 2] !in "aeiou" || base[base.length - 3] in "aeiou") return false
        return (base + last + rule.suffix) in frequencies
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

        /** Part de la fréquence du mot le plus courant au-delà de laquelle un mot est courant (voir [commonWordFrequency]). */
        private const val PERSONAL_ACCENT_COMMON_SHARE = 1_000L

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

        /** Longueur minimale du début tapé pour proposer des complétions malgré une faute ([fuzzyCompletions]). */
        private const val FUZZY_MIN_PREFIX = 4

        /** Pénalité d'une erreur d'édition dans le score unique quand la proximité des touches est inconnue (voir [searchCandidates]). */
        private const val NO_PROXIMITY_EDIT_PENALTY = 40.0

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

        /** Une forme fléchie retrouvée par les règles compte pour 1/4 de la fréquence de son mot de base. */
        private const val INFLECTED_FREQUENCY_DIVISOR = 4L

        /** Distance d'édition maximale selon la longueur du mot tapé : 1 pour 4 lettres ou moins. */
        /** Une partie plus courte qu'un mot (« Bus », « Tür ») reste possible dans une proposition de composé ; moins, non. */
        private const val MIN_MERGE_PART_LENGTH = 3

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
         * [confidence] : décision de déclenchement (phase 3, voir [CorrectionConfidence]) ; par défaut, le comportement
         * d'avant la phase 3 (seule une égalité stricte bloque la correction).
         */
        fun withFrequencies(
            entries: Map<String, Long>,
            inflections: InflectionRules = InflectionRules.NONE,
            candidateMinFrequency: Long = 0L,
            proximity: KeyProximity = KeyProximity.NONE,
            letterPrefilter: Boolean = true,
            properNames: Collection<String> = emptyList(),
            confidence: CorrectionConfidence = CorrectionConfidence.NONE,
            accentPolicy: AccentPolicy = AccentPolicy.FRENCH,
            ngrams: NgramModel = NgramModel.EMPTY,
        ): Dictionary {
            // Liste déjà en minuscules (cas des listes de l'application) : pas de copie, ce qui évite de
            // doubler la mémoire avec une liste complète de plusieurs centaines de milliers de mots.
            if (entries.keys.all {
                    it == it.lowercase()
                }
            ) {
                return Dictionary(entries, inflections, candidateMinFrequency, proximity, letterPrefilter, properNames, confidence, accentPolicy, ngrams)
            }
            val merged = HashMap<String, Long>(entries.size)
            entries.forEach { (word, count) ->
                val key = word.lowercase()
                val previous = merged[key]
                if (previous == null || count > previous) merged[key] = count
            }
            return Dictionary(merged, inflections, candidateMinFrequency, proximity, letterPrefilter, properNames, confidence, accentPolicy, ngrams)
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

        /** Lecture rapide d'un fichier de fréquences entier (voir [WordForms.parseFrequencyBytes]). */
        fun parseFrequencyBytes(bytes: ByteArray, minFrequency: Long = 0L, expectedEntries: Int = 0): Map<String, Long> =
            WordForms.parseFrequencyBytes(bytes, minFrequency, expectedEntries)

        fun applyOriginalCasing(original: String, correction: String): String =
            WordForms.applyOriginalCasing(original, correction)
    }
}
