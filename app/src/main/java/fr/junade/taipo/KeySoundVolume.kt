package fr.junade.taipo

/**
 * Volume des sons du clavier : un pourcentage réglé par curseur dans les paramètres (0 à 100 %), converti en gain de
 * lecture `SoundPool`. Logique pure, testée sur JVM.
 *
 * Les échantillons sont plafonnés à 0,7 : à [MAX_GAIN] (curseur à 100 %), le pic atteint 25 % de la pleine échelle (le
 * maximum a été divisé par deux : l'ancien 50 % est devenu 100 %). Le réglage par défaut ([DEFAULT_PERCENT]) redonne
 * exactement le volume d'avant le curseur (gain 0,2857, pic à 20 %).
 */
object KeySoundVolume {
    const val MIN_PERCENT = 0
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = 80

    /** Gain de lecture à 100 %. */
    const val MAX_GAIN = 0.3571f

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Gain `SoundPool` (0 à [MAX_GAIN]) pour [percent] ; une valeur hors bornes est ramenée dans [0, 100]. */
    fun gain(percent: Int): Float = clamp(percent) / MAX_PERCENT.toFloat() * MAX_GAIN
}
