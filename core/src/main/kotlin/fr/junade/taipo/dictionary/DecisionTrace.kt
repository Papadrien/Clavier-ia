package fr.junade.taipo.dictionary

/**
 * Ce que [Dictionary.correctionFor] a décidé pour un mot (phase 6) : des types et des nombres, **jamais le texte**. C'est le
 * paramètre de sortie facultatif qui permet de compter, en debug, les décisions du moteur pour ajuster les seuils de
 * [CorrectionConfidence] à l'usage. Sans trace (null, le cas normal), le moteur ne fait rien de plus et rend le même résultat.
 *
 * Une trace sert à un seul mot : un nouvel objet à chaque appel. Logique pure, testée en JVM.
 */
class DecisionTrace {

    /** Par où la décision est passée. */
    enum class Path {
        /** Aucune correction n'a été examinée : mot valide, trop court, acronyme, mot personnel, aucun candidat. */
        NONE,

        /** Une règle directe (accent, apostrophe, ligature, élision, nom propre) : pas de concours de candidats. */
        DIRECT,

        /** La recherche par distance d'édition a trouvé un meilleur candidat, soumis au seuil de marge. */
        SEARCH,
    }

    var path: Path = Path.NONE
        internal set

    /** Type de faute du meilleur candidat (seulement pour [Path.SEARCH]). */
    var kind: FaultKind? = null
        internal set

    /** Marge du meilleur candidat sur le suivant, en unités de ln(fréquence) ; l'infini s'il n'a pas de rival. */
    var margin: Double = 0.0
        internal set

    /** Le meilleur candidat a atteint le seuil de son type de faute (seulement pour [Path.SEARCH]). */
    var accepted: Boolean = false
        internal set

    /** La recherche à trois erreurs d'édition (mots longs) a été tentée : c'est le chemin le plus coûteux. */
    var threeEditsSearched: Boolean = false
        internal set

    /** Une correction trouvée a été écartée parce que l'utilisateur l'a annulée deux fois. */
    var refusedByHabits: Boolean = false
        internal set
}
