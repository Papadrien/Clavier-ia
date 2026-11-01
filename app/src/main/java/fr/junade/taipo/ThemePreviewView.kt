package fr.junade.taipo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.ColorRes

/**
 * Aperçu d'un thème du clavier (page « Thème », lot T5) : un mini clavier dessiné avec les couleurs réelles de la palette
 * claire ou sombre. « Système, automatique » montre les deux moitiés côte à côte (clair à gauche, sombre à droite).
 *
 * Les écrans de l'application restent sombres : les couleurs des deux palettes sont lues par
 * [KeyboardTheme.colorIn], pas par les ressources de l'écran. Aucune allocation au dessin.
 */
class ThemePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Thème montré. Changer la valeur redessine l'aperçu. */
    var choice: ThemeChoice = ThemeChoice.SYSTEM
        set(value) {
            field = value
            invalidate()
        }

    /** Couleurs d'une palette, lues une fois à la création de la vue. */
    private class Palette(private val context: Context, private val mode: KeyboardTheme.Mode, private val dynamic: Boolean = false) {
        private fun color(@ColorRes id: Int) = KeyboardTheme.colorIn(context, mode, id, dynamic)

        val background = color(R.color.keyboard_background)
        val modifier = color(R.color.key_normal)
        val modifierShadow = color(R.color.key_normal_shadow)
        val letter = color(R.color.key_functional)
        val letterShadow = color(R.color.key_functional_shadow)
        val enter = color(R.color.key_enter)
        val enterShadow = color(R.color.key_enter_shadow)
        val spaceBar = color(R.color.key_space_bar)
    }

    private val light = Palette(context, KeyboardTheme.Mode.LIGHT)
    private val dark = Palette(context, KeyboardTheme.Mode.DARK)

    // Couleur dynamique (Android 12+) : moitié claire et moitié sombre, tirées du fond d'écran de l'appareil.
    private val dynamicLight = Palette(context, KeyboardTheme.Mode.LIGHT, dynamic = true)
    private val dynamicDark = Palette(context, KeyboardTheme.Mode.DARK, dynamic = true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val cornerRadius = resources.getDimension(R.dimen.taipo_settings_card_corner_radius)

    private var keys: List<PreviewKey> = emptyList()

    init {
        // Coins arrondis antialiasés : le contour découpe aussi la séparation clair / sombre de « Système ».
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
        // Décoratif : la carte qui le contient porte le libellé lu par TalkBack.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keys = ThemePreviewGeometry.keys(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (choice) {
            ThemeChoice.LIGHT -> drawKeyboard(canvas, light, w, h)
            ThemeChoice.DARK -> drawKeyboard(canvas, dark, w, h)
            ThemeChoice.SYSTEM -> drawSplit(canvas, light, dark, w, h)
            ThemeChoice.DYNAMIC -> drawSplit(canvas, dynamicLight, dynamicDark, w, h)
        }
    }

    /** Deux moitiés côte à côte : [left] à gauche, [right] à droite (Système : clair et sombre ; Couleur dynamique : idem, du fond d'écran). */
    private fun drawSplit(canvas: Canvas, left: Palette, right: Palette, w: Float, h: Float) {
        val save = canvas.save()
        canvas.clipRect(0f, 0f, w / 2f, h)
        drawKeyboard(canvas, left, w, h)
        canvas.restoreToCount(save)
        val saveRight = canvas.save()
        canvas.clipRect(w / 2f, 0f, w, h)
        drawKeyboard(canvas, right, w, h)
        canvas.restoreToCount(saveRight)
    }

    private fun drawKeyboard(canvas: Canvas, palette: Palette, w: Float, h: Float) {
        paint.color = palette.background
        canvas.drawRect(0f, 0f, w, h, paint)
        for (key in keys) drawKey(canvas, palette, key)
    }

    private fun drawKey(canvas: Canvas, palette: Palette, key: PreviewKey) {
        val face: Int
        val shadow: Int
        when (key.kind) {
            PreviewKeyKind.LETTER, PreviewKeyKind.SPACE -> { face = palette.letter; shadow = palette.letterShadow }
            PreviewKeyKind.MODIFIER, PreviewKeyKind.BAR_BUTTON -> { face = palette.modifier; shadow = palette.modifierShadow }
            PreviewKeyKind.ENTER -> { face = palette.enter; shadow = palette.enterShadow }
        }
        val shadowHeight = key.height * ThemePreviewGeometry.SHADOW_RATIO
        val faceHeight = key.height - shadowHeight
        // Entrée et boutons de la barre : arrondis complets, comme sur le vrai clavier ; les autres touches, coins des touches.
        val radius = when (key.kind) {
            PreviewKeyKind.ENTER, PreviewKeyKind.BAR_BUTTON -> faceHeight / 2f
            else -> faceHeight * ThemePreviewGeometry.CORNER_RATIO
        }
        paint.color = shadow
        rect.set(key.left, key.top + shadowHeight, key.right, key.bottom)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.color = face
        rect.set(key.left, key.top, key.right, key.top + faceHeight)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (key.kind == PreviewKeyKind.SPACE) drawSpaceBar(canvas, palette, key, faceHeight)
    }

    /** Trait central de la barre d'espace. */
    private fun drawSpaceBar(canvas: Canvas, palette: Palette, key: PreviewKey, faceHeight: Float) {
        val thickness = faceHeight * SPACE_BAR_THICKNESS_RATIO
        val centerY = key.top + faceHeight / 2f
        val half = key.width * SPACE_BAR_LENGTH_RATIO / 2f
        val centerX = (key.left + key.right) / 2f
        paint.color = palette.spaceBar
        rect.set(centerX - half, centerY - thickness / 2f, centerX + half, centerY + thickness / 2f)
        canvas.drawRoundRect(rect, thickness / 2f, thickness / 2f, paint)
    }

    private companion object {
        const val SPACE_BAR_THICKNESS_RATIO = 0.22f
        const val SPACE_BAR_LENGTH_RATIO = 0.4f
    }
}
