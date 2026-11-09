package fr.junade.taipo

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Animation « Élastique » du glissement vers le bas qui saisit le chiffre d'une touche du haut (rangée de chiffres
 * désactivée). Pendant le glissement, la touche s'étire sous le doigt, la lettre s'enfonce en rétrécissant et le chiffre
 * éclot depuis son petit indice (en haut à droite) jusqu'au centre. À la validation, le chiffre fait un petit « pop ».
 *
 * Au relâchement après une saisie, le chiffre ne revient pas sur ses pas : il part vers le haut de la touche (il s'efface
 * en sortant par le bord supérieur), puis l'indice réapparaît discrètement, tout petit, à sa place habituelle, pendant que
 * la lettre revient en ressort. Un glissement relâché avant le seuil remet simplement tout en place.
 *
 * Même principe que [BackspaceAnimation] et [SpaceBarAnimation] : logique pure (testée en JVM), la vue appelle [onPress],
 * [onDrag], [onCommit], [onRelease] avec les gestes, puis [advance] une fois par image avant de dessiner ; [KeyRenderer]
 * lit [letterProgress], [digitBloom], [digitExit], [digitPop] et [hintScale], et en déduit la forme avec [DigitSwipePose].
 * Animations système coupées ([animationsEnabled] faux) : rien ne s'anime, la saisie du chiffre reste identique.
 */
internal class DigitSwipeAnimation(
    private val animationsEnabled: () -> Boolean,
) {

    /** Touche suivie (appuyée, glissée ou en train de se remettre en place), ou null. */
    var key: Key? = null
        private set

    private val progress = Spring()
    private val exit = Spring()
    private val hintBack = Spring()
    private val pop = Spring()

    private var committed = false
    private var released = false
    private var bloomHeld = 0f
    private var lastNanos = 0L

    /** Avancement du glissement : 0 au repos, 1 une fois le chiffre saisi ; dépasse un peu (ressort) puis revient à 0 au relâchement. */
    val letterProgress: Float get() = progress.x

    /** Éclosion du chiffre : 0 = petit, à la place de l'indice ; 1 = au centre de la touche, à la taille d'une lettre. Figée au relâchement. */
    val digitBloom: Float get() = if (released && committed) bloomHeld else progress.x

    /** Sortie du chiffre par le haut de la touche après le relâchement : 0 = en place, 1 = sorti. */
    val digitExit: Float get() = if (released && committed) exit.x else 0f

    /** Petit « pop » du chiffre à la validation (0 au repos, positif puis revient à 0). */
    val digitPop: Float get() = pop.x

    /** Échelle de l'indice (le petit chiffre en haut à droite) : 1 au repos, 0 quand le chiffre a éclos, dépasse un peu 1 à son retour. */
    val hintScale: Float
        get() = if (released && committed) hintBack.x.coerceAtLeast(0f) else (1f - HINT_FADE_RATE * progress.x).coerceIn(0f, 1f)

    /** Vrai tant que la vue doit redessiner à chaque image. */
    val isRunning: Boolean
        get() = key != null && (released || committed || progress.target != 0f || !progress.isSettled())

    private val atRest: Boolean
        get() = !committed && abs(progress.x) < REST_PROGRESS

    /** Vrai si [candidate] est la touche suivie et qu'elle n'est pas au repos : [KeyRenderer] la dessine alors avec la pose animée. */
    fun isAnimating(candidate: Key): Boolean = key != null && key == candidate && !atRest

    /** Vrai si [candidate] est la touche glissée, avant la validation du chiffre. */
    fun isTracking(candidate: Key): Boolean = key != null && key == candidate && !committed && !released

    /** Doigt posé sur [pressed] (une touche du haut qui porte un chiffre) : un nouveau geste commence. */
    fun onPress(pressed: Key) {
        cancel()
        if (!animationsEnabled()) return
        key = pressed
        progress.configure(FOLLOW_STIFFNESS, FOLLOW_DAMPING_RATIO)
    }

    /** Le glissement se poursuit : [fraction] de 0 (pas engagé) à 1 (seuil franchi), voir [DigitSwipe.progress]. */
    fun onDrag(fraction: Float) {
        if (key == null || committed || released) return
        progress.target = fraction.coerceIn(0f, 1f)
    }

    /** Le chiffre vient d'être saisi : la touche rebondit et le chiffre fait son « pop ». */
    fun onCommit() {
        if (key == null || committed || released) return
        committed = true
        progress.configure(BOUNCE_STIFFNESS, BOUNCE_DAMPING_RATIO)
        progress.target = 1f
        pop.configure(BOUNCE_STIFFNESS, BOUNCE_DAMPING_RATIO)
        pop.x = 0f
        pop.v = POP_IMPULSE
    }

    /** Doigt levé (ou geste interrompu par le système) : voir la description de la classe pour la suite. */
    fun onRelease() {
        if (key == null || released) return
        released = true
        progress.configure(BOUNCE_STIFFNESS, BOUNCE_DAMPING_RATIO)
        progress.target = 0f
        if (committed) {
            bloomHeld = progress.x
            exit.configure(EXIT_STIFFNESS, EXIT_DAMPING_RATIO)
            exit.x = 0f
            exit.v = 0f
            exit.target = 1f
            hintBack.configure(HINT_STIFFNESS, HINT_DAMPING_RATIO)
            hintBack.x = 0f
            hintBack.v = 0f
            hintBack.target = 0f
        }
    }

    /** Remet tout au repos immédiatement (bulle d'appui long ouverte, changement de touche, vue détachée). */
    fun cancel() {
        key = null
        committed = false
        released = false
        bloomHeld = 0f
        lastNanos = 0L
        progress.reset()
        exit.reset()
        hintBack.reset()
        pop.reset()
    }

    /** Avance l'animation jusqu'à [nowNanos] (une fois par image, avant le dessin). */
    fun advance(nowNanos: Long) {
        val dt = if (lastNanos == 0L) 0f else ((nowNanos - lastNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_FRAME_S)
        lastNanos = nowNanos
        step(dt)
        if (!isRunning) lastNanos = 0L
    }

    /** Avance de [dt] secondes. */
    fun step(dt: Float) {
        if (key == null || dt <= 0f) return
        progress.step(dt)
        pop.step(dt)
        if (!released) return
        if (!committed) {
            if (progress.isSettled()) cancel()
            return
        }
        exit.step(dt)
        if (exit.x >= HINT_RETURN_EXIT) hintBack.target = 1f
        if (hintBack.target > 0f) hintBack.step(dt)
        if (exit.isSettled() && hintBack.target > 0f && hintBack.isSettled() && progress.isSettled() && pop.isSettled()) cancel()
    }

    /** Ressort amorti à masse 1 (raideur et ratio d'amortissement comme un SpringForce Android), intégré en Euler semi-implicite. */
    private class Spring {
        var x = 0f
        var v = 0f
        var target = 0f
        private var stiffness = BOUNCE_STIFFNESS
        private var damping = 2f * BOUNCE_DAMPING_RATIO * sqrt(BOUNCE_STIFFNESS)

        fun configure(stiffness: Float, dampingRatio: Float) {
            this.stiffness = stiffness
            damping = 2f * dampingRatio * sqrt(stiffness)
        }

        fun step(dt: Float) {
            val n = ceil(dt / SUBSTEP_S).toInt().coerceAtLeast(1)
            val h = dt / n
            repeat(n) {
                v += (stiffness * (target - x) - damping * v) * h
                x += v * h
            }
        }

        fun isSettled() = abs(target - x) < SETTLED_DISTANCE && abs(v) < SETTLED_SPEED

        fun reset() {
            x = 0f
            v = 0f
            target = 0f
            configure(BOUNCE_STIFFNESS, BOUNCE_DAMPING_RATIO)
        }
    }

    companion object {
        /** Le doigt est suivi de près : ressort raide, peu de rebond. */
        const val FOLLOW_STIFFNESS = 700f
        const val FOLLOW_DAMPING_RATIO = 0.83f

        /** Validation et retour de la lettre : le ressort des autres animations du clavier (voir [SpringValue]). */
        const val BOUNCE_STIFFNESS = 400f
        const val BOUNCE_DAMPING_RATIO = 0.456f

        /** Sortie du chiffre par le haut : franche, sans rebond visible. */
        const val EXIT_STIFFNESS = 520f
        const val EXIT_DAMPING_RATIO = 0.85f

        /** Retour de l'indice : doux, un très léger dépassement. */
        const val HINT_STIFFNESS = 300f
        const val HINT_DAMPING_RATIO = 0.5f

        /** Vitesse initiale du « pop » du chiffre à la validation. */
        const val POP_IMPULSE = 14f

        /** L'indice commence à revenir quand le chiffre est presque sorti de la touche. */
        const val HINT_RETURN_EXIT = 0.8f

        /** L'indice disparaît dès la moitié du glissement. */
        const val HINT_FADE_RATE = 2f

        /** En dessous de cet avancement, la touche est dessinée normalement (un simple appui n'anime rien). */
        const val REST_PROGRESS = 0.001f

        private const val SUBSTEP_S = 1f / 240f
        private const val MAX_FRAME_S = 0.033f
        private const val NANOS_PER_SECOND = 1_000_000_000f
        private const val SETTLED_DISTANCE = 0.002f
        private const val SETTLED_SPEED = 0.02f
    }
}

/**
 * Forme de la touche et de ses caractères pendant l'animation « Élastique » du chiffre : calculs purs, sans Android.
 * [p] est l'avancement du glissement ([DigitSwipeAnimation.letterProgress]) : négatif pendant le rebond de retour.
 */
internal object DigitSwipePose {

    /** Allongement de la touche vers le bas à avancement 1 (la touche s'étire sous le doigt, sans déborder loin sur la rangée du dessous). */
    const val STRETCH_Y = 0.14f

    /** Amincissement de la touche à avancement 1. */
    const val SQUASH_X = 0.04f

    /** Descente de la lettre à avancement 1, en part de la hauteur de la touche. */
    const val LETTER_SHIFT_RATIO = 0.28f

    /** Hauteur de sortie du chiffre après le relâchement, en part de la hauteur de la touche. */
    const val EXIT_RISE_RATIO = 0.7f

    /** Grossissement du « pop » du chiffre à la validation, par unité de [DigitSwipeAnimation.digitPop]. */
    const val POP_SCALE = 0.35f

    /** Plafonds du rebond : la taille du chiffre et de l'indice ne dérapent jamais. */
    const val MAX_BLOOM = 1.4f
    const val MAX_HINT_SCALE = 1.3f

    private const val MAX_STRETCH_PROGRESS = 1.3f
    private const val MIN_STRETCH_PROGRESS = -0.6f

    fun faceScaleX(p: Float): Float = 1f - SQUASH_X * p.coerceIn(MIN_STRETCH_PROGRESS, MAX_STRETCH_PROGRESS)

    fun faceScaleY(p: Float): Float = 1f + STRETCH_Y * p.coerceIn(MIN_STRETCH_PROGRESS, MAX_STRETCH_PROGRESS)

    /** Échelle de la lettre : rétrécit en s'enfonçant, grossit un peu quand le rebond de retour la remonte. */
    fun letterScale(p: Float): Float = (1f - 0.7f * p).coerceIn(0.05f, 1.5f)

    /** Opacité de la lettre : elle s'efface avant la moitié du glissement. */
    fun letterAlpha(p: Float): Float = (1f - 1.5f * p).coerceIn(0f, 1f)

    /** Opacité du chiffre : il apparaît en éclosant, puis s'efface en sortant par le haut. */
    fun digitAlpha(bloom: Float, exit: Float): Float = (1.8f * bloom).coerceIn(0f, 1f) * (1f - exit).coerceIn(0f, 1f)

    /** Opacité de l'indice pour son échelle [scale] : plein dès la moitié de sa taille. */
    fun hintAlpha(scale: Float): Float = (2f * scale).coerceIn(0f, 1f)
}
