package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * Traînée de la saisie par glissement (19/11/2026), comme celle de Gboard : un trait de la couleur d'accent suit le doigt et
 * s'efface derrière lui (les points de plus de [TAIL_MS] disparaissent), plus fin et plus pâle vers sa queue ; au relâchement,
 * elle s'efface entièrement en [FADE_MS]. Animations Android coupées : le trait reste entier tant que le doigt glisse.
 */
internal class GestureTrail(color: Int, private val widthPx: Float) {

    private class Dot(val x: Float, val y: Float, val at: Long)

    private val dots = ArrayList<Dot>()
    private var releasedAt = NOT_RELEASED
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val opaque = paint.alpha

    /** Le doigt passe en ([x], [y]) à l'instant [now] (ms, `SystemClock.uptimeMillis`). */
    fun add(x: Float, y: Float, now: Long) {
        releasedAt = NOT_RELEASED
        dots += Dot(x, y, now)
    }

    /** Le doigt se lève : la traînée s'efface. */
    fun release(now: Long) {
        if (dots.isNotEmpty()) releasedAt = now
    }

    fun clear() {
        dots.clear()
        releasedAt = NOT_RELEASED
    }

    /** Vrai tant que quelque chose reste à dessiner : la vue demande l'image suivante. */
    fun isRunning(now: Long): Boolean = dots.isNotEmpty() && (releasedAt == NOT_RELEASED || now - releasedAt < FADE_MS)

    fun draw(canvas: Canvas, now: Long, animated: Boolean) {
        if (dots.isEmpty()) return
        val fade = if (releasedAt == NOT_RELEASED) 1f else 1f - (now - releasedAt).toFloat() / FADE_MS
        if (fade <= 0f) {
            clear()
            return
        }
        // Seule la queue récente reste (sauf animations coupées) ; les points plus anciens sont oubliés.
        val reference = if (releasedAt == NOT_RELEASED) now else releasedAt
        if (animated) dots.removeAll { reference - it.at > TAIL_MS && dots.size > 2 }
        for (i in 1 until dots.size) {
            val a = dots[i - 1]
            val b = dots[i]
            val freshness = if (animated) (1f - (reference - b.at).toFloat() / TAIL_MS).coerceIn(0f, 1f) else 1f
            paint.strokeWidth = widthPx * (MIN_WIDTH + (1f - MIN_WIDTH) * freshness)
            paint.alpha = (opaque * freshness * fade * MAX_ALPHA).roundToInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
    }

    private companion object {
        const val NOT_RELEASED = Long.MIN_VALUE
        const val TAIL_MS = 380L
        const val FADE_MS = 180L
        const val MIN_WIDTH = 0.35f
        const val MAX_ALPHA = 0.85f
    }
}
