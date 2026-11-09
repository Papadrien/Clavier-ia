package fr.junade.taipo

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Empile le clavier (premier enfant) et les panneaux qui le remplacent (emoji, Smart Clipboard) au
 * même endroit. La hauteur de l'ensemble est celle du clavier, et celle-là seulement : un
 * `FrameLayout` ordinaire en `wrap_content` prend la hauteur du plus grand de ses enfants, donc un
 * panneau au contenu plus haut que le clavier (grille d'emoji, liste de cartes) agrandirait le
 * clavier au moment de basculer. Ici les panneaux sont mesurés après coup, à la hauteur exacte du
 * clavier, et leur contenu défile à l'intérieur.
 */
class KeyboardStackLayout(context: Context) : FrameLayout(context) {

    /**
     * Mode une main : les panneaux (emoji, Smart Clipboard) prennent la largeur réduite des touches et se calent contre le même
     * bord. Le clavier garde toute la largeur (il dessine lui-même sa zone) ; la barre du haut n'est pas concernée.
     */
    var oneHandedMode: OneHandedMode = OneHandedMode.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var oneHandedWidthPercent: Int = OneHandedWidth.DEFAULT_PERCENT
        set(value) {
            val clamped = OneHandedWidth.clamp(value)
            if (field == clamped) return
            field = clamped
            requestLayout()
        }

    private fun panelZone(width: Int): KeyboardWidth =
        KeyboardWidth.forAvailableWidth(width.toFloat(), resources.displayMetrics.density, oneHandedMode, oneHandedWidthPercent)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = getChildAt(0)
        if (keyboard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        // Le clavier reste mesuré même masqué (INVISIBLE) : c'est lui qui donne la hauteur.
        keyboard.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val height = keyboard.measuredHeight
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        val zone = panelZone(width)
        val panelWidthSpec = if (oneHandedMode.isActive && zone.widthPx > 0f) {
            MeasureSpec.makeMeasureSpec(zone.widthPx.roundToInt(), MeasureSpec.EXACTLY)
        } else {
            widthSpec
        }
        for (i in 1 until childCount) {
            val panel = getChildAt(i)
            if (panel.visibility != View.GONE) panel.measure(panelWidthSpec, heightSpec)
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!oneHandedMode.isActive) return
        val panelLeft = panelZone(right - left).leftPx.roundToInt()
        for (i in 1 until childCount) {
            val panel = getChildAt(i)
            if (panel.visibility == View.GONE) continue
            // Position absolue (et non un décalage) : indépendante du sens d'écriture de la mise en page.
            panel.layout(panelLeft, panel.top, panelLeft + panel.measuredWidth, panel.bottom)
        }
    }
}
