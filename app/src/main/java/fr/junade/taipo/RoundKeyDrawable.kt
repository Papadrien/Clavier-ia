package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt

/**
 * Fond rond « face + ombre » des boutons ronds de la charte Taipo (lot 08), dessiné au Canvas : un cercle d'ombre
 * décalé vers le bas sous un cercle de face. Même géométrie que les touches du clavier (lots 04 et 05) : au repos,
 * l'ombre visible fait [shadowHeight] ; pressé (état `pressed` de la vue qui porte ce fond), l'ombre visible tombe à
 * [pressedShadowHeight] et le bouton descend de la différence. Le cercle est centré dans les limites du drawable, de
 * diamètre la plus petite de sa largeur et de la hauteur de la face.
 */
internal class RoundKeyDrawable(
    @ColorInt faceColor: Int,
    @ColorInt shadowColor: Int,
    private val shadowHeight: Float,
    private val pressedShadowHeight: Float,
) : Drawable() {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faceColor }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shadowColor }
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
        val faceHeight = area.bottom - top - shown
        val radius = minOf(area.width().toFloat(), faceHeight) / 2f
        val cx = area.exactCenterX()
        val faceCy = top + faceHeight / 2f
        canvas.drawCircle(cx, faceCy + shown, radius, shadowPaint)
        canvas.drawCircle(cx, faceCy, radius, facePaint)
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
