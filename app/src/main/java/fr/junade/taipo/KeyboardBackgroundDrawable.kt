package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Fond unique de tout le clavier : barre du haut, touches et panneaux (emoji, Smart Clipboard) sont
 * transparents et laissent voir ce fond, posé sur la vue racine de l'IME. Les futures animations
 * d'arrière-plan se dessinent ici, une seule fois, et passent donc derrière la barre et le clavier
 * sans raccord visible (appeler [invalidateSelf] à chaque image de l'animation).
 *
 * Clavier flottant : [cornerRadius] arrondit les coins de la carte, et le contour ([getOutline]) donne son ombre.
 */
class KeyboardBackgroundDrawable(backgroundColor: Int) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
    private val rect = RectF()

    /** Rayon des coins (px) : 0 pour le clavier ancré en bas, arrondi pour le clavier flottant. */
    var cornerRadius = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (cornerRadius <= 0f) {
            canvas.drawRect(bounds, paint)
            return
        }
        rect.set(bounds)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = if (cornerRadius > 0f) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE
}
