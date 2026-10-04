package fr.junade.taipo

import android.content.Context
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
