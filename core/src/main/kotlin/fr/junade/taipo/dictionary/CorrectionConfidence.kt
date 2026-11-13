package fr.junade.taipo.dictionary

/**
 * Type de faute que corrige un candidat (phase 3 de la refonte de l'autocorrecteur). Il décide du seuil de confiance :
 * une inversion de deux lettres est presque toujours voulue, un mot refait à coups de trois erreurs presque jamais.
 */
enum class FaultKind {
    /** Deux lettres voisines inversées (« teh » -> « the »). */
    SWAP,

    /** Une lettre remplacée par une touche voisine (« cgat » -> « chat »). */
    NEIGHBOR,

    /** Une lettre oubliée (« oubier » -> « oublier »). */
    MISSING,

    /** Une lettre en trop (« chatt » -> « chat »). */
    EXTRA,

    /** Une lettre remplacée par une touche éloignée (« maintenent » -> « maintenant »). */
    SUBSTITUTION,

    /** Deux erreurs d'édition. */
    TWO_EDITS,

    /** Trois erreurs d'édition : réservé aux mots longs, voir [CorrectionConfidence.longWordMinLength]. */
    THREE_EDITS,
}

/**
 * Décision de déclenchement (phase 3) : le meilleur candidat est-il assez sûr pour être appliqué ?
 *
 * La phase 2 donne à chaque candidat un score ([CandidateScore]). La **marge** est l'écart entre le score du meilleur
 * candidat et celui du suivant, à la même distance d'édition (en unités de ln(fréquence) : 0,7 = deux fois plus
 * fréquent à coût de saisie égal). Elle mesure la confiance : une marge nulle est un pile ou face, une marge infinie
 * signifie qu'aucun autre candidat ne rivalise (aucun autre à cette distance, ou aucun autre tout court). Les habitudes
 * et le dictionnaire personnel pèsent dans le score avec des poids énormes : un candidat qu'ils soutiennent a une marge
 * colossale et passe toujours.
 *
 * Règles :
 * - une marge nulle ne corrige jamais (deux candidats strictement à égalité : aucune raison de préférer l'un) ;
 * - sinon la marge doit atteindre le seuil du [FaultKind] du candidat ([minMargin]) ;
 * - trois erreurs d'édition ne sont tentées que sur les mots d'au moins [longWordMinLength] lettres, quand rien n'a été
 *   trouvé à distance 2 ou moins, avec une marge d'au moins le seuil de [FaultKind.THREE_EDITS] et un coût de saisie d'au
 *   plus [longWordMaxCost] ;
 * - une élision rétablie sans apostrophe (« tinquiete » -> « t'inquiete ») demande au moins [singleLetterElisionMinRest]
 *   lettres après une lettre élidée (« mour » ne devient plus « m'our ») ; voir [Dictionary].
 *
 * Calibrage (français, clavier AZERTY) : voir docs/autocorrection-bench.md, phase 3. [NONE] reproduit exactement le
 * comportement d'avant la phase 3 ; [STANDARD] est celui du français dans l'application. Logique pure, testée en JVM.
 */
class CorrectionConfidence(
    private val minMargin: Map<FaultKind, Double>,
    /** Longueur minimale du mot tapé pour tenter trois erreurs d'édition ; [Int.MAX_VALUE] = jamais. */
    val longWordMinLength: Int = Int.MAX_VALUE,
    /** Coût de saisie maximal ([KeyProximity.editCost]) d'une correction à trois erreurs. */
    val longWordMaxCost: Int = Int.MAX_VALUE,
    /** Nombre minimal de lettres après une lettre élidée (« t' », « m' », « j' ») pour rétablir l'apostrophe. */
    val singleLetterElisionMinRest: Int = 3,
    /** Si vrai, une élision en « c' » n'est rétablie que devant « e » (« c'est », « c'était », « c'en »). */
    val cElisionOnlyBeforeE: Boolean = false,
    /**
     * Rétablir une apostrophe d'élision oubliée (« tinquiete » -> « t'inquiete »). Propre au français (et à l'italien) :
     * l'anglais n'a pas d'élision, « therr » ne devient pas « t'herr » ; ses contractions (« dont » -> « don't ») passent
     * par un autre chemin, qui reste actif.
     */
    val restoresElisions: Boolean = true,
    /**
     * Poids du mot précédent ([NgramModel.bonus]) dans le score d'un candidat (point 1 de docs/autocorrection-roadmap.md). 0 : le
     * contexte est ignoré (langues sans bigrammes ou non calibrées). 1 : une unité de bonus vaut une unité de ln(fréquence).
     */
    val contextWeight: Double = 0.0,
    /**
     * Point 2 de docs/autocorrection-roadmap.md : pénalité d'une erreur d'édition dans le score unique ([CandidateContest]). Null : la
     * distance d'édition prime sur tout le reste (comportement historique).
     */
    val distancePenalty: Double? = null,
    /**
     * Point 4 de docs/autocorrection-roadmap.md : un mot tapé plus court que cette longueur n'est corrigé à deux erreurs que si le mot
     * précédent soutient la correction ([NgramModel.bonus] positif). 0 : jamais exigé.
     */
    val twoEditsMinLengthWithoutContext: Int = 0,
    /**
     * Point 4 : un mot inconnu à majuscule qui suit directement un autre mot (pas en début de phrase) n'est remplacé que par un nom
     * propre connu. Faux en allemand, où tous les noms prennent une majuscule.
     */
    val protectsMidSentenceCapitals: Boolean = false,
) {

    /** Distance d'édition de la recherche à mots longs. */
    val longWordDistance: Int = 3

    /** Vrai si la recherche à trois erreurs est active pour un mot de [length] lettres. */
    fun allowsThreeEditsFor(length: Int): Boolean = length >= longWordMinLength

    /** Type de faute corrigée par [candidate] à la place de [typed] (minuscules). [cost] : [KeyProximity.editCost]. */
    fun kindOf(typed: String, candidate: String, distance: Int, isSwap: Boolean, cost: Int): FaultKind = when {
        distance >= 3 -> FaultKind.THREE_EDITS
        distance == 2 -> FaultKind.TWO_EDITS
        isSwap -> FaultKind.SWAP
        candidate.length > typed.length -> FaultKind.MISSING
        candidate.length < typed.length -> FaultKind.EXTRA
        cost <= Dictionary.PLAUSIBLE_SLIP_COST -> FaultKind.NEIGHBOR
        else -> FaultKind.SUBSTITUTION
    }

    /** Seuil de marge du type [kind] (0 : toute marge strictement positive suffit). */
    fun thresholdOf(kind: FaultKind): Double = minMargin[kind] ?: 0.0

    /** Vrai si un candidat de type [kind], de coût de saisie [cost] et de marge [margin] doit être appliqué. */
    fun accepts(kind: FaultKind, margin: Double, cost: Int): Boolean {
        if (!(margin > 0.0)) return false
        if (kind == FaultKind.THREE_EDITS && cost > longWordMaxCost) return false
        return margin >= thresholdOf(kind)
    }

    companion object {

        /** Comportement d'avant la phase 3 : seule l'égalité stricte bloque, pas de recherche à trois erreurs. */
        val NONE = CorrectionConfidence(emptyMap())

        /**
         * Allemand : les seuils de [NONE] (pas encore calibrés), sans élision. L'allemand n'élide pas : « danne » ne devient pas
         * « d'anne », « sins » pas « s'ins » (banc `--lang=de`, 07/11/2026).
         */
        val GERMAN = CorrectionConfidence(emptyMap(), restoresElisions = false)

        /**
         * Seuils du français, calibrés sur le banc (docs/autocorrection-bench.md, phase 3). Ils sont volontairement bas :
         * ils ne retirent que les quasi-égalités, où le choix est un pile ou face. Les relever corrige moins et se trompe
         * moins ; chaque point se mesure avec le banc avant d'être changé.
         */
        val STANDARD = CorrectionConfidence(
            minMargin = mapOf(
                FaultKind.SWAP to 0.75,
                FaultKind.NEIGHBOR to 0.25,
                FaultKind.MISSING to 0.25,
                FaultKind.EXTRA to 0.5,
                FaultKind.SUBSTITUTION to 0.5,
                FaultKind.TWO_EDITS to 0.5,
                FaultKind.THREE_EDITS to 6.0,
            ),
            longWordMinLength = 8,
            longWordMaxCost = 12,
            singleLetterElisionMinRest = 4,
            cElisionOnlyBeforeE = true,
            // Point 1 (bigrammes) : réglé sur le banc des phrases mises de côté (0,5 / 1 / 1,5 / 2 : 1 est le meilleur compromis).
            contextWeight = 1.0,
            // Point 2 : un seul score (0 : le coût de saisie, déjà 20 à 40 par erreur, suffit à pénaliser la distance ; banc 0 / 10 / 20 / 30).
            distancePenalty = 0.0,
            // Point 4 : banc 0 / 6 / 8 ; 6 retire des fausses corrections sans perdre de bonne correction (8 coûte 7 points aux mots longs).
            twoEditsMinLengthWithoutContext = 6,
            protectsMidSentenceCapitals = true,
        )

        /**
         * Seuils de l'anglais (clavier QWERTY), calibrés avec `AutocorrectBench --lang=en` (docs/autocorrection-bench.md).
         * Les valeurs de départ sont celles du français ; chaque changement se mesure.
         */
        val ENGLISH = CorrectionConfidence(
            minMargin = mapOf(
                FaultKind.SWAP to 0.75,
                FaultKind.NEIGHBOR to 0.5,
                FaultKind.MISSING to 0.5,
                FaultKind.EXTRA to 0.75,
                FaultKind.SUBSTITUTION to 0.75,
                FaultKind.TWO_EDITS to 1.0,
                FaultKind.THREE_EDITS to 6.0,
            ),
            longWordMinLength = 8,
            longWordMaxCost = 12,
            singleLetterElisionMinRest = 4,
            cElisionOnlyBeforeE = true,
            restoresElisions = false,
            contextWeight = 1.0,
            // Point 2 : un seul score (0 : le coût de saisie, déjà 20 à 40 par erreur, suffit à pénaliser la distance ; banc 0 / 10 / 20 / 30).
            distancePenalty = 0.0,
            // Point 4 : banc 0 / 6 / 8 ; 6 retire des fausses corrections sans perdre de bonne correction (8 coûte 7 points aux mots longs).
            twoEditsMinLengthWithoutContext = 6,
            protectsMidSentenceCapitals = true,
        )

        /**
         * Italien, portugais, néerlandais (07/11/2026, docs/autocorrection-roadmap.md, section « Autres langues ») : les seuils de l'anglais
         * (même clavier QWERTY), le mot précédent (bigrammes Tatoeba), le score unique et la protection des mots inconnus. Seul l'italien
         * élide (« l'ho », « c'è », « dell'acqua ») : les deux autres ne rétablissent pas d'apostrophe d'élision. Calibrés avec le banc
         * (`--lang=it`, `--lang=pt`, `--lang=nl`).
         */
        private fun latinQwerty(restoresElisions: Boolean) = CorrectionConfidence(
            minMargin = mapOf(
                FaultKind.SWAP to 0.75,
                FaultKind.NEIGHBOR to 0.5,
                FaultKind.MISSING to 0.5,
                FaultKind.EXTRA to 0.75,
                FaultKind.SUBSTITUTION to 0.75,
                FaultKind.TWO_EDITS to 1.0,
                FaultKind.THREE_EDITS to 6.0,
            ),
            longWordMinLength = 8,
            longWordMaxCost = 12,
            singleLetterElisionMinRest = 4,
            cElisionOnlyBeforeE = true,
            restoresElisions = restoresElisions,
            contextWeight = 1.0,
            distancePenalty = 0.0,
            twoEditsMinLengthWithoutContext = 6,
            protectsMidSentenceCapitals = true,
        )

        val ITALIAN = latinQwerty(restoresElisions = true)
        val PORTUGUESE = latinQwerty(restoresElisions = false)
        val DUTCH = latinQwerty(restoresElisions = false)

        /**
         * Langues ajoutées le 11/11/2026 (polonais, roumain, tchèque, hongrois, suédois, danois, norvégien, finnois) : les seuils du
         * portugais et du néerlandais, sans élision. Valeurs de DÉPART, pas encore mesurées sur un banc propre à chaque langue.
         */
        val LATIN = latinQwerty(restoresElisions = false)

        /**
         * Seuils de l'espagnol (clavier QWERTY avec Ñ). Valeurs de DÉPART, celles de l'anglais : elles n'ont pas été mesurées (le banc
         * `--lang=es` existe mais n'a pas pu être lancé là où ce code a été écrit : docs/autocorrection-bench.md, section Espagnol).
         * L'espagnol conjugue beaucoup (hablo / hable / habla, une lettre d'écart) : les quasi-égalités entre formes d'un même verbe
         * y sont plus fréquentes qu'en français, d'où des marges au moins aussi prudentes que celles de l'anglais. Pas d'élision.
         */
        val SPANISH = CorrectionConfidence(
            minMargin = mapOf(
                FaultKind.SWAP to 0.75,
                FaultKind.NEIGHBOR to 0.5,
                FaultKind.MISSING to 0.5,
                FaultKind.EXTRA to 0.75,
                FaultKind.SUBSTITUTION to 0.75,
                FaultKind.TWO_EDITS to 1.0,
                FaultKind.THREE_EDITS to 6.0,
            ),
            longWordMinLength = 8,
            longWordMaxCost = 12,
            singleLetterElisionMinRest = 4,
            cElisionOnlyBeforeE = true,
            restoresElisions = false,
        )
    }
}
