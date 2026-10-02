package fr.junade.taipo

/**
 * Story 1.9 : convertit le glissement horizontal d'un doigt posé sur la touche retour arrière en
 * un nombre de mots entiers à supprimer avant le curseur. Seul un glissement vers la gauche compte
 * (vers la droite de son point d'appui, rien n'est sélectionné). Logique pure (pas d'Android),
 * testable à part, sur le même principe que [SpaceSwipeTracker] (story 1.7).
 *
 * Le geste ne démarre qu'une fois le doigt éloigné de [activationPx] du point d'appui (sinon un
 * simple appui légèrement tremblé serait pris pour un glissement) ; l'activation sélectionne aussitôt
 * le premier mot, puis chaque [stepPx] supplémentaire parcouru vers la gauche en ajoute un.
 *
 * Le résultat est un total *absolu* (et non plus un incrément) : il dépend uniquement de la position
 * courante du doigt, donc il diminue quand le doigt revient vers la droite. Le texte n'est supprimé
 * qu'au relâchement ; d'ici là, le total sert à surligner la zone qui serait supprimée.
 */
class BackspaceSwipeTracker(
    private val activationPx: Float,
    private val stepPx: Float,
) {
    private var downX = 0f

    /** Vrai dès que le geste est devenu un glissement : le relâchement ne doit alors plus effacer un caractère. */
    var isActive: Boolean = false
        private set

    fun onDown(x: Float) {
        downX = x
        isActive = false
    }

    /**
     * Nombre total de mots à supprimer pour la position [x] du doigt (toujours ≥ 0). 0 tant que le
     * geste n'est pas activé, ou si le doigt est revenu en deçà du seuil d'activation (le geste reste
     * alors actif : relâcher à cet endroit annule la suppression sans effacer de caractère).
     */
    fun onMove(x: Float): Int {
        val leftward = downX - x
        if (!isActive) {
            if (leftward < activationPx) return 0
            isActive = true
        }
        if (leftward < activationPx) return 0
        return 1 + ((leftward - activationPx) / stepPx).toInt()
    }

    fun reset() {
        isActive = false
    }
}
