package fr.junade.taipo.dictionary

/**
 * Concours des candidats d'une correction (phase 3) : retient le meilleur et le suivant, dans l'ordre de l'autocorrecteur
 * (distance d'édition la plus petite d'abord, puis meilleur [CandidateScore]), pour que [CorrectionConfidence] mesure la
 * marge entre les deux. Remplace l'ancien drapeau « ambigu », qui ne voyait que l'égalité stricte.
 * Logique pure, testée en JVM par `CandidateContestTest`.
 */
internal class CandidateContest(
    /**
     * Point 2 de docs/autocorrection-roadmap.md : null = la distance d'édition prime (ordre historique) ; sinon, un seul score
     * départage tous les candidats, `score - distancePenalty x distance`, et une faute à deux touches voisines peut battre une faute
     * à une touche éloignée si le mot est bien plus probable. La marge est alors l'écart entre ces scores, quelle que soit la distance.
     */
    private val distancePenalty: Double? = null,
) {

    var best: String? = null
        private set
    var bestDistance: Int = Int.MAX_VALUE
        private set
    var bestCost: Int = 0
        private set
    var bestIsSwap: Boolean = false
        private set
    private var bestScore: Double = Double.NEGATIVE_INFINITY

    private var second: String? = null
    private var secondDistance: Int = Int.MAX_VALUE
    private var secondScore: Double = Double.NEGATIVE_INFINITY

    /** Propose [candidate] à la distance [distance] avec le score [score] (voir [CandidateScore.of]). */
    fun offer(candidate: String, distance: Int, score: Double, cost: Int, isSwap: Boolean) {
        if (candidate == best) {
            // Même mot vu deux fois (mot personnel et prénom) : on garde le meilleur score.
            if (score > bestScore) bestScore = score
            return
        }
        if (candidate == second) return
        if (beats(distance, score, bestDistance, bestScore)) {
            second = best
            secondDistance = bestDistance
            secondScore = bestScore
            best = candidate
            bestDistance = distance
            bestScore = score
            bestCost = cost
            bestIsSwap = isSwap
        } else if (beats(distance, score, secondDistance, secondScore)) {
            second = candidate
            secondDistance = distance
            secondScore = score
        }
    }

    /**
     * Écart de score entre le meilleur candidat et le suivant, s'ils sont à la même distance d'édition (0 = égalité
     * stricte). [Double.POSITIVE_INFINITY] si le suivant est plus loin ou s'il n'y en a pas : aucun rival.
     */
    fun margin(): Double = when {
        best == null -> 0.0
        second == null -> Double.POSITIVE_INFINITY
        distancePenalty != null -> adjusted(bestScore, bestDistance) - adjusted(secondScore, secondDistance)
        secondDistance > bestDistance -> Double.POSITIVE_INFINITY
        else -> bestScore - secondScore
    }

    private fun adjusted(score: Double, distance: Int): Double = score - (distancePenalty ?: 0.0) * distance

    private fun beats(distance: Int, score: Double, otherDistance: Int, otherScore: Double): Boolean = when {
        otherDistance == Int.MAX_VALUE -> true
        distancePenalty != null -> adjusted(score, distance) > adjusted(otherScore, otherDistance)
        else -> distance < otherDistance || (distance == otherDistance && score > otherScore)
    }
}
