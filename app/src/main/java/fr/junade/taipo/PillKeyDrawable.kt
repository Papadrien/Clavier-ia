package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Fond « face + ombre » des boutons rectangulaires arrondis de la charte Taipo (lot 10), dessiné au Canvas : un
 * rectangle d'ombre décalé vers le bas sous un rectangle de face. Même géométrie et même comportement que
 * [RoundKeyDrawable] et que les touches du clavier : au repos, l'ombre visible fait [shadowHeight] ; pressé (état
 * `pressed` de la vue qui porte ce fond), elle tombe à [pressedShadowHeight] et la face descend de la différence.
 * Le rayon est plafonné à la moitié de la plus petite dimension de la face (un rayon trop grand donne une pilule).
 * Le rectangle est tracé dans un [RectF] réutilisé : aucune allocation au dessin.
 */
internal class PillKeyDrawable(
    @ColorInt faceColor: Int,
    @ColorInt shadowColor: Int,
    private val cornerRadius: Float,
    private val shadowHeight: Float,
    private val pressedShadowHeight: Float,
) : Drawable() {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faceColor }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadowColor }
    private val rect = RectF()
    private var pressed = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowPressed = state.contains(android.R.attr.state_pressed)
        if (nowPressed == pressed) return false
        pressed = nowPressed
        return true
    }

    override fun draw(canvas: Canvas) {
        val area = bounds
        val rest = minOf(shadowHeight, area.height() * MAX_SHADOW_RATIO)
        val shown = if (pressed) minOf(pressedShadowHeight, rest) else rest
        val top = area.top + (rest - shown)
        val faceBottom = area.bottom - shown
        val radius = minOf(cornerRadius, area.width() / 2f, (faceBottom - top) / 2f)
        rect.set(area.left.toFloat(), top + shown, area.right.toFloat(), faceBottom + shown)
        canvas.drawRoundRect(rect, radius, radius, shadowPaint)
        rect.set(area.left.toFloat(), top, area.right.toFloat(), faceBottom)
        canvas.drawRoundRect(rect, radius, radius, facePaint)
    }

    override fun setAlpha(alpha: Int) {
        facePaint.alpha = alpha
        shadowPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        facePaint.colorFilter = colorFilter
        shadowPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Même garde-fou que les touches du clavier : l'ombre reste une fine épaisseur. */
        const val MAX_SHADOW_RATIO = 0.15f
    }
}
