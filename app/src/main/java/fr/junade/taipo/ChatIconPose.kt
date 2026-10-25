package fr.junade.taipo

/**
 * Poses de repos de l'étincelle du bouton afficher/masquer le chat. L'étincelle a quatre branches identiques : un quart de
 * tour (90°) donne le même dessin, ce qui permet de la faire tourner en se fermant sans qu'elle repose de travers.
 */
internal object ChatIconPose {
    /** Taille de l'étincelle au repos : pleine quand le chat est affiché, un peu réduite quand il est masqué. */
    fun restScale(shown: Boolean): Float = if (shown) 1f else HIDDEN_SCALE

    /** Rotation de repos (degrés) : 0 chat affiché, un quart de tour chat masqué (même dessin). */
    fun restRotation(shown: Boolean): Float = if (shown) 0f else QUARTER_TURN

    /** Rotation de départ de l'apparition à l'ouverture : l'étincelle arrive d'un demi-tour en arrière. */
    const val APPEAR_FROM_ROTATION = -180f
    const val HIDDEN_SCALE = 0.7f
    const val QUARTER_TURN = 90f
}
