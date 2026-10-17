package fr.junade.taipo

/**
 * Pose de l'icône robot du bouton Action IA pour un avancement [progress] (0 = repos, 1 = actif, fond violet).
 * Calculs purs, sur la grille de 24 de `ic_robot.xml` : seuls l'antenne, les yeux et la bouche bougent.
 * Un avancement légèrement hors de 0..1 (effet ressort) est accepté : l'antenne et les yeux le suivent, la bouche et le
 * plissement des yeux sont bornés.
 */
internal object RobotIconPose {

    /** Extrémité haute de l'antenne : y = 4 au repos, elle descend de [ANTENNA_DROP] (la tige raccourcit). */
    fun antennaTopY(progress: Float): Float = ANTENNA_REST_Y + ANTENNA_DROP * progress

    /** Décalage vertical des yeux (négatif = vers le haut). */
    fun eyeShiftY(progress: Float): Float = 0f - EYE_RISE * progress

    /** Part de la hauteur de l'œil rognée par le bas (0 = œil rond, [EYE_SQUINT_MAX] = plissé). */
    fun eyeSquint(progress: Float): Float = (EYE_SQUINT * progress).coerceIn(0f, EYE_SQUINT_MAX)

    /** Opacité de la bouche, de 0 à 1. */
    fun mouthAlpha(progress: Float): Float = progress.coerceIn(0f, 1f)

    /** Largeur relative de la bouche (elle s'élargit en apparaissant), de [MOUTH_MIN_SCALE] à 1. */
    fun mouthScaleX(progress: Float): Float =
        MOUTH_MIN_SCALE + (1f - MOUTH_MIN_SCALE) * progress.coerceIn(0f, 1f)

    const val ANTENNA_REST_Y = 4f
    const val ANTENNA_BASE_Y = 8f
    const val ANTENNA_DROP = 2.4f
    const val EYE_RISE = 1f
    const val EYE_SQUINT = 0.45f
    const val EYE_SQUINT_MAX = 0.55f
    const val MOUTH_MIN_SCALE = 0.6f
}
