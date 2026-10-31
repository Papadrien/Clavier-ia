package fr.junade.taipo.dictionary

/**
 * Score unique d'un candidat de correction (phase 2 de la refonte de l'autocorrecteur). Il remplace la cascade de
 * comparaisons (« mot personnel, puis habitudes, puis proximité des touches, puis inversion, puis fréquence ») par une
 * somme pondérée : plus le score est haut, meilleur est le candidat. Il ne départage que des candidats à la **même
 * distance d'édition** : la distance reste le critère dominant, comme avant.
 *
 * ```
 * score = FREQUENCY x ln(1 + fréquence)          ce que l'on tape le plus
 *       - COST x coût de saisie                   plausibilité de la faute (touche voisine, lettre oubliée...)
 *       + SWAP si inversion de deux lettres
 *       + habitudes (paire acceptée > contexte > usage)
 *       + PERSONAL si mot du dictionnaire personnel
 * ```
 *
 * Les poids sont étagés pour que l'ordre des priorités soit celui de l'ancienne cascade : un terme ne peut être renversé
 * par les termes plus bas qu'à l'intérieur de leur plage réaliste (coût <= 60, fréquence < 10^13, compteurs <= 10^6).
 * Une seule différence voulue : l'inversion n'est plus un critère absolu mais un bonus de [SWAP] (e^4, soit environ
 * 55 fois), de sorte qu'un mot beaucoup plus fréquent peut passer devant une inversion à coût de saisie égal. Réglé sur le
 * banc d'autocorrection (docs/autocorrection-bench.md) : fautes courantes + 0,83 point, fautes variées - 0,07, aucune fausse
 * correction de plus. Avec les touches du clavier, la plupart des cas sont déjà tranchés par le coût de saisie (une
 * inversion coûte 2, une lettre en trop 4 : « teh » reste « the » même devant « te »).
 *
 * Deux scores identiques (tous les termes égaux) signalent une ambiguïté : `Dictionary` ne corrige alors pas.
 * Logique pure, testée en JVM par `CandidateScoreTest`.
 */
internal object CandidateScore {

    /** Par unité de ln(1 + fréquence) : l'échelle de référence, les autres poids sont exprimés par rapport à elle. */
    const val FREQUENCY = 1.0

    /** Par unité de coût de saisie (voir [KeyProximity.editCost] : 2 = touche voisine, 4 = erreur quelconque). */
    const val COST = 10.0

    /** Bonus d'une inversion de deux lettres voisines (« teh » -> « the »). */
    const val SWAP = 4.0

    /** Une paire « tapé -> candidat » acceptée au moins deux fois passe devant le contexte, qui passe devant l'usage. */
    const val ACCEPTED = 1e7
    const val CONTEXT = 1e5
    const val USAGE = 1e3

    /** Un mot que l'utilisateur a ajouté volontairement passe devant tout le reste (habitudes comprises). */
    const val PERSONAL = 1e9

    fun of(frequency: Long, cost: Int, isSwap: Boolean, isPersonal: Boolean, habit: HabitScore): Double {
        var score = FREQUENCY * Math.log(1.0 + frequency) - COST * cost
        if (isSwap) score += SWAP
        if (habit !== HabitScore.NONE) {
            score += ACCEPTED * Math.log(1.0 + habit.accepted) +
                CONTEXT * Math.log(1.0 + habit.context) +
                USAGE * Math.log(1.0 + habit.usage)
        }
        if (isPersonal) score += PERSONAL
        return score
    }
}
