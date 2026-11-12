package fr.junade.taipo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
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
 * [KeyboardTheme.colorIn], pas par les ressources de l'écran. La couleur d'accent choisie ([accent]) est appliquée au dessin, sauf
 * pour « Couleur dynamique », dont l'accent vient du fond d'écran. Aucune allocation au dessin.
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

    /** Couleur d'accent montrée (section « Couleur » de la page « Thème »). Changer la valeur redessine l'aperçu. */
    var accent: AccentPalette = AccentPalette.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    /** Forme des touches montrée (section « Forme des touches » de la page « Apparence »). Changer la valeur redessine l'aperçu. */
    var shape: KeyShape = KeyShape.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    /** Couleurs d'une palette, lues une fois à la création de la vue. */
    private class Palette(private val context: Context, private val mode: KeyboardTheme.Mode, val dynamic: Boolean = false) {
        private fun color(@ColorRes id: Int) = KeyboardTheme.colorIn(context, mode, id, dynamic)

        val background = color(R.color.keyboard_background)
        val modifier = color(R.color.key_normal)
        val modifierShadow = color(R.color.key_normal_shadow)
        val letter = color(R.color.key_functional)
        val letterShadow = color(R.color.key_functional_shadow)
        val enter = color(R.color.key_enter)
        val enterShadow = color(R.color.key_enter_shadow)
        val spaceBar = color(R.color.key_space_bar)
        val outline = color(R.color.key_outline)
        val plateau = color(R.color.key_plateau)
        val label = color(R.color.key_label)
    }

    private val light = Palette(context, KeyboardTheme.Mode.LIGHT)
    private val dark = Palette(context, KeyboardTheme.Mode.DARK)

    // Couleur dynamique (Android 12+) : moitié claire et moitié sombre, tirées du fond d'écran de l'appareil.
    private val dynamicLight = Palette(context, KeyboardTheme.Mode.LIGHT, dynamic = true)
    private val dynamicDark = Palette(context, KeyboardTheme.Mode.DARK, dynamic = true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val path = Path()
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
        var face: Int
        var shadow: Int
        when (key.kind) {
            PreviewKeyKind.LETTER, PreviewKeyKind.SPACE -> {
                face = palette.letter
                shadow = palette.letterShadow
            }
            PreviewKeyKind.MODIFIER, PreviewKeyKind.BAR_BUTTON -> {
                face = palette.modifier
                shadow = palette.modifierShadow
            }
            PreviewKeyKind.ENTER -> {
                face = palette.enter
                shadow = palette.enterShadow
            }
        }
        val tint = if (palette.dynamic) AccentPalette.DEFAULT else accent
        face = tint.recolor(face)
        shadow = tint.recolor(shadow)
        val accent = key.kind == PreviewKeyKind.ENTER
        // Boutons de la barre : toujours ronds, comme sur le vrai clavier ; les touches suivent la forme choisie (voir KeyShape).
        val keyShape = if (key.kind == PreviewKeyKind.BAR_BUTTON) KeyShape.PILL else shape
        val shadowHeight = key.height * ThemePreviewGeometry.SHADOW_RATIO * minOf(keyShape.shadowFactor, MAX_SHADOW_FACTOR)
        val faceHeight = key.height - shadowHeight
        val drawnWidth = keyShape.faceWidth(accent, key.width, faceHeight)
        val drawnHeight = keyShape.faceHeight(accent, key.width, faceHeight)
        val left = key.left + (key.width - drawnWidth) / 2f
        val top = key.top + (faceHeight - drawnHeight) / 2f
        val base = faceHeight * ThemePreviewGeometry.CORNER_RATIO
        val corner = keyShape.cornerSize(accent, drawnWidth, drawnHeight, base)
        when (keyShape.body(accent)) {
            KeyShape.FaceBody.FILLED -> {
                if (shadowHeight > 0f) {
                    paint.color = shadow
                    rect.set(left, top + shadowHeight, left + drawnWidth, top + drawnHeight + shadowHeight)
                    drawShape(canvas, keyShape, corner, paint)
                }
                paint.color = face
                rect.set(left, top, left + drawnWidth, top + drawnHeight)
                drawShape(canvas, keyShape, corner, paint)
                if (keyShape.hasPlateau) {
                    val inset = minOf(drawnWidth, drawnHeight) * PLATEAU_RATIO
                    paint.color = palette.plateau
                    rect.set(left + inset, top + inset * 0.6f, left + drawnWidth - inset, top + drawnHeight - 2f * inset)
                    canvas.drawRoundRect(rect, corner / 2f, corner / 2f, paint)
                }
            }
            KeyShape.FaceBody.OUTLINED -> {
                strokePaint.color = palette.outline
                strokePaint.strokeWidth = maxOf(1f, faceHeight * OUTLINE_RATIO)
                val half = strokePaint.strokeWidth / 2f
                rect.set(left + half, top + half, left + drawnWidth - half, top + drawnHeight - half)
                drawShape(canvas, keyShape, maxOf(corner - half, 0f), strokePaint)
            }
            // Sans fond : un point à la place de la lettre, pour que l'aperçu montre encore la grille.
            KeyShape.FaceBody.BARE -> if (key.kind != PreviewKeyKind.SPACE) {
                paint.color = palette.label
                canvas.drawCircle(left + drawnWidth / 2f, top + drawnHeight / 2f, faceHeight * DOT_RATIO, paint)
            }
        }
        if (key.kind == PreviewKeyKind.SPACE) drawSpaceBar(canvas, palette, key, faceHeight)
    }

    /** Silhouette de [keyShape] dans [rect] : rectangle arrondi, ou coins coupés (Biseau). */
    private fun drawShape(canvas: Canvas, keyShape: KeyShape, corner: Float, paint: Paint) {
        if (keyShape.outline() == KeyShape.Outline.ROUNDED_RECT) {
            canvas.drawRoundRect(rect, corner, corner, paint)
            return
        }
        path.rewind()
        path.moveTo(rect.left + corner, rect.top)
        path.lineTo(rect.right - corner, rect.top)
        path.lineTo(rect.right, rect.top + corner)
        path.lineTo(rect.right, rect.bottom - corner)
        path.lineTo(rect.right - corner, rect.bottom)
        path.lineTo(rect.left + corner, rect.bottom)
        path.lineTo(rect.left, rect.bottom - corner)
        path.lineTo(rect.left, rect.top + corner)
        path.close()
        canvas.drawPath(path, paint)
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

        /** Mécanique : flanc de l'aperçu plafonné (l'aperçu est petit, un flanc de 2,5 fois l'ombre mangerait la face). */
        const val MAX_SHADOW_FACTOR = 1.8f
        const val PLATEAU_RATIO = 0.14f
        const val OUTLINE_RATIO = 0.08f
        const val DOT_RATIO = 0.12f
    }
}
