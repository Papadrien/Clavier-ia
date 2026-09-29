package fr.junade.taipo

/**
 * Story 1.9 : convertit le glissement horizontal d'un doigt posé sur la touche retour arrière en
 * un nombre de mots entiers à supprimer avant le curseur. Seul un glissement vers la gauche compte
 * (vers la droite, rien ne se passe). Logique pure (pas d'Android), testable à part, sur le même
 * principe que [SpaceSwipeTracker] (story 1.7).
 *
 * Le geste ne démarre qu'une fois le doigt éloigné de [activationPx] du point d'appui (sinon un
 * simple appui légèrement tremblé serait pris pour un glissement) ; l'activation supprime aussitôt
 * le premier mot, puis chaque [stepPx] supplémentaire parcouru vers la gauche en supprime un de
 * plus, ce qui permet d'enchaîner sur plusieurs mots sans relâcher le doigt. Les valeurs de seuil
 * sont des estimations à ajuster empiriquement sur le Pixel 9 (décision 11.1), comme le reste de
 * la roadmap.
 */
class BackspaceSwipeTracker(
    private val activationPx: Float,
    private val stepPx: Float,
) {
    private var downX = 0f
    private var anchorX = 0f

    /** Vrai dès que le geste est devenu un glissement : le relâchement ne doit alors plus effacer un caractère. */
    var isActive: Boolean = false
        private set

    fun onDown(x: Float) {
        downX = x
        anchorX = x
        isActive = false
    }

    /**
     * Nombre de mots à supprimer pour ce mouvement (toujours ≥ 0). Un glissement vers la droite,
     * ou un mouvement trop court pour activer le geste, ne supprime rien.
     */
    fun onMove(x: Float): Int {
        if (!isActive) {
            if (downX - x < activationPx) return 0
            isActive = true
            anchorX = x
            return 1 // le premier mot est supprimé dès l'activation du geste
        }
        val leftward = anchorX - x
        if (leftward <= 0f) return 0
        val words = (leftward / stepPx).toInt()
        if (words > 0) anchorX -= words * stepPx
        return words
    }

    fun reset() {
        isActive = false
    }
}
