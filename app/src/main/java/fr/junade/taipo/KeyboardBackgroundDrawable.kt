package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Fond unique de tout le clavier : barre du haut, touches et panneaux (emoji, Smart Clipboard) sont
 * transparents et laissent voir ce fond, posé sur la vue racine de l'IME. Les futures animations
 * d'arrière-plan se dessinent ici, une seule fois, et passent donc derrière la barre et le clavier
 * sans raccord visible (appeler [invalidateSelf] à chaque image de l'animation).
 */
class KeyboardBackgroundDrawable(backgroundColor: Int) : Drawable() {

    private val paint = Paint().apply { color = backgroundColor }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
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
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
