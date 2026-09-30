package fr.junade.taipo

/**
 * Positionnement horizontal de la bulle d'appui long (story 1.8). Logique pure (sans dépendance
 * Android) pour rester testable en JVM.
 */
object PopupPlacement {

    /**
     * Abscisse gauche de la bulle. Le point d'ancrage de la bulle ([anchorInPopup], mesuré depuis
     * son bord gauche : centre du choix présélectionné, ou centre de la bulle s'il n'y en a pas) est
     * aligné sur le centre de la touche pressée ([keyCenterX]). Si la bulle dépasse de la zone
     * disponible [[minLeft], [maxRight]], elle est décalée pour rester entièrement visible : le
     * choix présélectionné n'est alors plus exactement centré sur la touche.
     */
    fun left(keyCenterX: Float, anchorInPopup: Float, popupWidth: Float, minLeft: Float, maxRight: Float): Float {
        val maxLeft = (maxRight - popupWidth).coerceAtLeast(minLeft)
        return (keyCenterX - anchorInPopup).coerceIn(minLeft, maxLeft)
    }
}
