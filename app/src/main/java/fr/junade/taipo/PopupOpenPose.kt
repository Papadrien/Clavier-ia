package fr.junade.taipo

/**
 * Ouverture de la bulle de symboles : elle sort de la touche appuyée en grandissant avec un ressort (calculs purs).
 * La bulle part à [START_SCALE] de sa taille, pivote autour du haut de la touche, dépasse un peu sa taille puis se pose
 * ([SpringValue], ~20 % de dépassement du trajet). Le dépassement est plafonné pour que la bulle ne sorte ni de la place
 * disponible au-dessus de la vue ni de la zone des touches.
 */
internal object PopupOpenPose {

    /** Vitesse du ressort d'ouverture : 2 = bulle posée en deux fois moins de temps (même trajet, même dépassement). */
    const val TIME_SCALE = 2f

    /** Taille de la bulle au premier dessin, avant le ressort. */
    const val START_SCALE = 0.25f

    /** Transparence au départ : la bulle est pleinement opaque dès que sa taille atteint [OPAQUE_AT]. */
    const val OPAQUE_AT = 0.6f

    /** Opacité (0 à 1) de la bulle à la taille [scale] : elle apparaît en grandissant. */
    fun alpha(scale: Float): Float = (scale / OPAQUE_AT).coerceIn(0f, 1f)

    /**
     * Plus grande taille que la bulle peut prendre en dépassant son point d'arrivée, autour du pivot ([pivotX], [pivotY]) :
     * ses bords ne dépassent pas [minLeft], [maxRight] et [minTop]. Jamais inférieure à 1.
     */
    fun maxScale(
        pivotX: Float,
        pivotY: Float,
        left: Float,
        right: Float,
        top: Float,
        minLeft: Float,
        maxRight: Float,
        minTop: Float,
    ): Float {
        var max = Float.MAX_VALUE
        if (pivotX > left) max = minOf(max, (pivotX - minLeft) / (pivotX - left))
        if (right > pivotX) max = minOf(max, (maxRight - pivotX) / (right - pivotX))
        if (pivotY > top) max = minOf(max, (pivotY - minTop) / (pivotY - top))
        return max.coerceAtLeast(1f)
    }
}
