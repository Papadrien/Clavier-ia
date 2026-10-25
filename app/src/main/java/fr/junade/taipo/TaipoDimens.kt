package fr.junade.taipo

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.annotation.DimenRes

/**
 * Accès aux dimensions de la charte Taipo (`res/values/dimens.xml`, lot 03 de la refonte graphique).
 *
 * [dimen] renvoie des pixels (valeur en dp multipliée par la densité, ou valeur en sp selon la taille de
 * police) sous forme de flottant : les appelants gardent leurs `.toInt()` d'origine, ce qui conserve le
 * rendu au pixel près.
 */
internal fun Context.dimen(@DimenRes id: Int): Float = resources.getDimension(id)

internal fun View.dimen(@DimenRes id: Int): Float = context.dimen(id)

/** Taille de texte tirée d'une dimension en sp de la charte (déjà convertie en pixels par [dimen]). */
internal fun TextView.setTextSizeRes(@DimenRes id: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(id))
}

/**
 * Lot 20 (accessibilité) : taille de texte d'une dimension en sp de la charte, pour les textes de **hauteur fixe**
 * (barres du haut, suggestions, panneau emoji) : l'échelle de police du système y est plafonnée à
 * `taipo_max_font_scale` (130 %). Au-delà, le texte ne tiendrait plus dans les 36 dp de ses boutons.
 * Les écrans de réglages, les cartes du presse-papiers et le chat défilent : ils suivent la police du système
 * ([setTextSizeRes]). Le calcul est approché (Android 14 met les grandes polices à l'échelle de façon non linéaire) ;
 * il ne réduit jamais le texte en dessous de sa taille de base.
 */
internal fun Context.fixedHeightTextDimen(@DimenRes id: Int): Float {
    val size = resources.getDimension(id)
    val fontScale = resources.configuration.fontScale
    val max = maxFontScale(resources)
    if (fontScale <= max) return size
    val base = size / fontScale
    return (size * max / fontScale).coerceAtLeast(base)
}

// Lot 21 : plafond de l'échelle de police, constant (aucune variante de ressource) : lu une fois, sans TypedValue à chaque appel.
private var cachedMaxFontScale = 0f

private fun maxFontScale(resources: Resources): Float {
    if (cachedMaxFontScale == 0f) {
        cachedMaxFontScale = TypedValue().also { resources.getValue(R.dimen.taipo_max_font_scale, it, true) }.float
    }
    return cachedMaxFontScale
}

internal fun View.fixedHeightTextDimen(@DimenRes id: Int): Float = context.fixedHeightTextDimen(id)

/** Comme [setTextSizeRes], avec l'échelle de police plafonnée (voir [fixedHeightTextDimen]) : textes de hauteur fixe. */
internal fun TextView.setFixedTextSizeRes(@DimenRes id: Int) {
    setTextSize(TypedValue.COMPLEX_UNIT_PX, fixedHeightTextDimen(id))
}
