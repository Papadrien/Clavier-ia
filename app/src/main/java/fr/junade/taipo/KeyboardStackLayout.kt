package fr.junade.taipo

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Empile le clavier (premier enfant) et les panneaux qui le remplacent (emoji, Smart Clipboard) au
 * même endroit. La hauteur de l'ensemble est celle du clavier, et celle-là seulement : un
 * `FrameLayout` ordinaire en `wrap_content` prend la hauteur du plus grand de ses enfants, donc un
 * panneau au contenu plus haut que le clavier (grille d'emoji, liste de cartes) agrandirait le
 * clavier au moment de basculer. Ici les panneaux sont mesurés après coup, à la hauteur exacte du
 * clavier, et leur contenu défile à l'intérieur.
 */
class KeyboardStackLayout(context: Context) : FrameLayout(context) {

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
        for (i in 1 until childCount) {
            val panel = getChildAt(i)
            if (panel.visibility != View.GONE) panel.measure(widthSpec, heightSpec)
        }
        setMeasuredDimension(width, height)
    }
}
