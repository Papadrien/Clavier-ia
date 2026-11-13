package fr.junade.taipo

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Style des animations du clavier (page « Apparence », section « Animations ») : la façon dont les bulles des touches, les boutons
 * de la barre, les barres du mode chat et les icônes animées apparaissent et se posent. Toutes ces animations reposent sur un
 * ressort amorti ([SpringValue], [BarReveal.springProgress]) dont le style donne la pulsation propre ([omega], rad/s) et le
 * ratio d'amortissement ([zeta]) :
 *
 * - [SPRING] (Ressort, le style d'origine) : dépasse sa place d'environ 20 % puis rebondit avant de se poser ;
 * - [SNAPPY] (Vif) : amortissement critique, deux fois plus rapide, aucun dépassement ;
 * - [JELLY] (Gelée, réservé au pack premium) : ressort plus souple, et la vue s'écrase puis s'étire en se posant ([stretchX],
 *   [stretchY]).
 *
 * Les animations qui ont leur propre réglage (icône Maj, balancier d'Effacer, trait de la barre espace, glissement des chiffres,
 * clavier flottant, mode une main, robot, envoi du chat, coche de Corriger) suivent aussi le style depuis le 13/11/2026 : leur
 * réglage est celui du style Ressort, et [tuneStiffness], [tuneDampingRatio], [tuneDamping], [tuneSeconds], [tuneDurationMs] et
 * [tuneOvershootTension] le transposent dans le style choisi (identique en Ressort ; deux fois plus vif et sans rebond en Vif ;
 * plus lent et plus rebondi en Gelée). Les boucles continues (chargement, ondes, orbite) gardent leur rythme.
 * Animations coupées dans les réglages système : rien ne bouge, quel que soit le style. Logique pure, testée par `MotionStyleTest`.
 */
enum class MotionStyle(
    val storageKey: String,
    val requiresPremium: Boolean,
    val omega: Float,
    val zeta: Float,
    /** Amplitude de l'écrasement / étirement (0 : la vue garde ses proportions). */
    val jelly: Float,
    /** Amortissement du ressort de la barre ([BarReveal.springProgress]) : un peu plus fort que [zeta] en Ressort (≈ 16 % de dépassement). */
    val revealZeta: Float,
    /** Durée de l'entrée en ressort des boutons de la barre ([BarReveal.enterSpring]) et de leurs glissements. */
    val revealDurationMs: Long,
    /** Décalage entre deux boutons de la barre qui apparaissent en cascade. */
    val revealStaggerMs: Long,
    /** Multiplicateur des durées du dévoilement en rideau de la barre ([BarReveal.enter]). */
    val curtainScale: Float,
) {
    SPRING("spring", false, omega = 20f, zeta = 0.456f, jelly = 0f, revealZeta = 0.5f, revealDurationMs = 620L, revealStaggerMs = 45L, curtainScale = 1f),
    SNAPPY("snappy", false, omega = 42f, zeta = 1f, jelly = 0f, revealZeta = 1f, revealDurationMs = 220L, revealStaggerMs = 20L, curtainScale = 0.5f),
    JELLY("jelly", true, omega = 17f, zeta = 0.34f, jelly = 0.55f, revealZeta = 0.34f, revealDurationMs = 760L, revealStaggerMs = 50L, curtainScale = 1f),
    ;

    val stiffness: Float get() = omega * omega
    val damping: Float get() = 2f * zeta * omega

    fun isAvailable(premium: Boolean): Boolean = premium || !requiresPremium

    /** Vitesse relative au style Ressort (rapport des pulsations) : 1 en Ressort, 2,1 en Vif, 0,85 en Gelée. */
    val speed: Float get() = omega / SPRING_OMEGA

    /** Raideur d'un ressort réglé pour Ressort, transposée dans ce style (pulsation multipliée par [speed]). */
    fun tuneStiffness(stiffness: Float): Float = if (this == SPRING) stiffness else stiffness * speed * speed

    /**
     * Ratio d'amortissement d'un ressort réglé pour Ressort, transposé dans ce style : au moins 1 (aucun dépassement) en Vif,
     * réduit dans le même rapport que [zeta] en Gelée (plus de rebonds), inchangé en Ressort.
     */
    fun tuneDampingRatio(ratio: Float): Float = when {
        this == SPRING -> ratio
        zeta >= 1f -> max(ratio, 1f)
        else -> ratio * zeta / SPRING_ZETA
    }

    /** Amortissement absolu (masse 1) d'un ressort de raideur [stiffness] réglé pour Ressort, transposé dans ce style. */
    fun tuneDamping(stiffness: Float, damping: Float): Float {
        if (this == SPRING || stiffness <= 0f) return damping
        return 2f * tuneDampingRatio(damping / (2f * sqrt(stiffness))) * sqrt(tuneStiffness(stiffness))
    }

    /** Durée (secondes) d'une animation réglée pour Ressort, dans ce style. */
    fun tuneSeconds(seconds: Float): Float = if (this == SPRING) seconds else seconds / speed

    /** Durée (millisecondes) d'une animation réglée pour Ressort, dans ce style. */
    fun tuneDurationMs(durationMs: Long): Long = if (this == SPRING) durationMs else (durationMs / speed).roundToLong()

    /** Tension d'un `OvershootInterpolator` réglé pour Ressort : aucun dépassement en Vif, un dépassement plus marqué en Gelée. */
    fun tuneOvershootTension(tension: Float): Float = when {
        this == SPRING -> tension
        zeta >= 1f -> 0f
        else -> tension * SPRING_ZETA / zeta
    }

    /**
     * Largeur relative d'une vue dont le ressort vaut [value] (1 = posée) : plus large quand le ressort dépasse sa place, plus
     * étroite avant de l'atteindre ; [strength] (0 à 1) modère l'effet pour les grandes vues (barres entières). Toujours 1 hors
     * [JELLY].
     */
    fun stretchX(value: Float, strength: Float = 1f): Float = (1f + jelly * strength * (value - 1f)).coerceAtLeast(MIN_STRETCH)

    /** Hauteur relative, à l'inverse de [stretchX] : la vue s'aplatit quand elle s'élargit (son volume semble constant). */
    fun stretchY(value: Float, strength: Float = 1f): Float = (1f - jelly * strength * (value - 1f)).coerceAtLeast(MIN_STRETCH)

    /**
     * Position (0 au départ, 1 à l'arrivée) à [seconds] d'un ressort de pulsation [omega] et d'amortissement [ratio] lâché au
     * repos : réponse analytique, sans état. Sous-amorti, elle dépasse 1 puis oscille ; à l'amortissement critique ([SNAPPY]),
     * elle approche 1 sans le dépasser.
     */
    fun response(seconds: Float, ratio: Float = zeta): Float {
        val t = seconds.coerceAtLeast(0f)
        val zeta = ratio
        if (zeta >= 1f) return 1f - exp(-omega * t) * (1f + omega * t)
        val decay = zeta * omega
        val omegaD = omega * sqrt(1f - zeta * zeta)
        return 1f - exp(-decay * t) * (cos(omegaD * t) + decay / omegaD * sin(omegaD * t))
    }

    companion object {
        val DEFAULT = SPRING

        /** Plus petite proportion qu'une vue peut prendre en s'étirant. */
        private const val MIN_STRETCH = 0.2f

        /** Pulsation et amortissement du style Ressort, référence des réglages propres à chaque animation. */
        private const val SPRING_OMEGA = 20f
        private const val SPRING_ZETA = 0.456f

        /**
         * Style appliqué en ce moment, déjà ramené à Ressort s'il est réservé au pack et que celui-ci n'est pas actif
         * ([effective]). Posé au démarrage du processus, à chaque changement du réglage et du pack ([PremiumPass]).
         */
        @Volatile
        var current: MotionStyle = DEFAULT

        fun fromStorageKey(key: String?): MotionStyle = entries.firstOrNull { it.storageKey == key } ?: DEFAULT

        fun effective(stored: MotionStyle, premium: Boolean): MotionStyle = if (stored.isAvailable(premium)) stored else DEFAULT
    }
}
