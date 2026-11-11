package fr.junade.taipo

import kotlin.math.cos
import kotlin.math.exp
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
 * Les gestes qui suivent le doigt (glissement des chiffres, clavier flottant, mode une main) gardent leur propre ressort.
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
