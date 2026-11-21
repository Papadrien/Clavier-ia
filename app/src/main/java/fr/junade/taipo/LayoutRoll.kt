package fr.junade.taipo

/**
 * Animation « compteur » au changement de page (21/11/2026) : lettres → chiffres et symboles → seconde page de symboles, et
 * retour. Les touches ne bougent pas ; dans chacune, l'ancien caractère défile hors de la touche et le nouveau arrive à sa place,
 * comme sur un compteur mécanique ou un panneau d'affichage de gare. La vague part de la touche touchée (« ?123 », « ABC »,
 * « =\< ») : une touche démarre d'autant plus tard qu'elle en est loin.
 *
 * Sens : vers les pages suivantes (lettres → chiffres → symboles), les caractères montent ; vers les précédentes, ils descendent.
 * Le ressort et le décalage entre touches suivent le style d'animation ([MotionStyle]).
 *
 * Logique pure (testée en JVM) : [KeyboardView] fournit les positions et dessine (voir [KeyRenderer.roll]).
 */
class LayoutRoll(
    /** Instant de départ (ms, horloge du dessin). */
    private val startMs: Long,
    /** +1 : les caractères montent ; -1 : ils descendent. */
    val direction: Int,
    /** Ancien caractère de chaque nouvelle touche (celle qui occupait sa place), ou null s'il n'y en avait pas. */
    private val outgoing: Map<Key, String>,
    /** Délai de départ de chaque nouvelle touche (ms). */
    private val delays: Map<Key, Long>,
    private val durationMs: Long,
    private val style: MotionStyle,
) {

    /** Caractère qui sort de la touche [key], ou null. */
    fun outgoingLabel(key: Key): String? = outgoing[key]

    /** Avancement de [key] à [nowMs] : 0 au départ, 1 à l'arrivée (le ressort peut dépasser 1 un instant). */
    fun progress(key: Key, nowMs: Long): Float {
        val elapsed = nowMs - startMs - (delays[key] ?: 0L)
        if (elapsed <= 0L) return 0f
        if (elapsed >= durationMs) return 1f
        return BarReveal.springProgress(elapsed.toFloat() / durationMs, style)
    }

    /** L'animation tourne encore à [nowMs]. */
    fun isRunning(nowMs: Long): Boolean = nowMs - startMs < durationMs + (delays.values.maxOrNull() ?: 0L)

    /** Position et libellé d'une touche dans une page, pour la correspondance entre l'ancienne et la nouvelle. */
    data class Slot(val key: Key, val centerX: Float, val centerY: Float, val label: String?)

    companion object {

        /** Ordre des pages : lettres, chiffres et symboles, seconde page de symboles. */
        private fun rank(id: LayoutId): Int? = when (id) {
            LayoutId.LETTERS -> 0
            LayoutId.SYMBOLS_2 -> 1
            LayoutId.SYMBOLS -> 2
            else -> null
        }

        /** Sens de l'animation entre deux pages, ou null : pas d'animation (même page, pavé numérique, autre langue). */
        fun direction(from: LayoutId, to: LayoutId): Int? {
            val a = rank(from) ?: return null
            val b = rank(to) ?: return null
            return when {
                b > a -> 1
                b < a -> -1
                else -> null
            }
        }

        /**
         * Prépare l'animation : chaque nouvelle touche ([newSlots]) reprend le libellé de l'ancienne touche la plus proche
         * ([oldSlots]) s'il diffère du sien ; le délai suit la distance à [originX]/[originY] (centre de la touche touchée),
         * exprimée en largeurs de touche [unitPx].
         */
        @Suppress("LongParameterList")
        fun start(
            nowMs: Long,
            direction: Int,
            oldSlots: List<Slot>,
            newSlots: List<Slot>,
            originX: Float,
            originY: Float,
            unitPx: Float,
            rowHeightPx: Float,
            style: MotionStyle,
        ): LayoutRoll {
            val outgoing = HashMap<Key, String>()
            val delays = HashMap<Key, Long>()
            val stagger = style.revealStaggerMs * STAGGER_RATIO
            for (slot in newSlots) {
                val nearest = oldSlots.minByOrNull { (it.centerX - slot.centerX).let { dx -> dx * dx } + (it.centerY - slot.centerY).let { dy -> dy * dy } }
                val old = nearest?.label
                if (old != null && old != slot.label) outgoing[slot.key] = old
                val columns = kotlin.math.abs(slot.centerX - originX) / unitPx.coerceAtLeast(1f)
                val rows = kotlin.math.abs(slot.centerY - originY) / rowHeightPx.coerceAtLeast(1f)
                delays[slot.key] = ((columns + rows * ROW_WEIGHT) * stagger).toLong()
            }
            val duration = (style.revealDurationMs * DURATION_RATIO).toLong()
            return LayoutRoll(nowMs, direction, outgoing, delays, duration, style)
        }

        /** Décalage entre deux colonnes : la moitié de celui des boutons de la barre (Ressort : ~22 ms). */
        private const val STAGGER_RATIO = 0.5f

        /** Une rangée d'écart compte pour une demi-colonne : la vague s'étale surtout en largeur. */
        private const val ROW_WEIGHT = 0.5f

        /** Durée d'une touche : un peu plus courte que l'entrée des boutons de la barre. */
        private const val DURATION_RATIO = 0.8f
    }
}
