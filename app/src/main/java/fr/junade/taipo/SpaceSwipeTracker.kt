package fr.junade.taipo

import kotlin.math.abs

/**
 * Story 1.7 : convertit le glissement horizontal d'un doigt posé sur la barre espace en un nombre
 * de caractères dont déplacer le curseur. Logique pure (pas d'Android), testable à part.
 *
 * Le geste ne démarre qu'une fois le doigt éloigné de [activationPx] du point d'appui (sinon un
 * simple appui légèrement tremblé serait pris pour un glissement) ; ensuite chaque [stepPx]
 * parcouru déplace le curseur d'un caractère. La sensibilité (le pas) sera réglable en V2.
 */
class SpaceSwipeTracker(
    private val activationPx: Float,
    private val stepPx: Float,
) {
    private var downX = 0f
    private var anchorX = 0f

    /** Vrai dès que le geste est devenu un glissement : la barre espace ne doit alors plus saisir d'espace. */
    var isActive: Boolean = false
        private set

    fun onDown(x: Float) {
        downX = x
        anchorX = x
        isActive = false
    }

    /**
     * Nombre de caractères dont déplacer le curseur pour ce mouvement : négatif vers la gauche,
     * positif vers la droite, 0 si le doigt n'a pas assez bougé.
     */
    fun onMove(x: Float): Int {
        if (!isActive) {
            if (abs(x - downX) < activationPx) return 0
            isActive = true
            anchorX = x
            return 0
        }
        val steps = ((x - anchorX) / stepPx).toInt()
        if (steps != 0) anchorX += steps * stepPx
        return steps
    }

    fun reset() {
        isActive = false
    }
}
