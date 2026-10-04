package fr.junade.taipo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.ColorRes

/**
 * Style commun des boutons de la barre du haut (lot 4.2) : fond arrondi, texte ou icône claire, boutons
 * compacts. Sorti de [CorrectionBarView] sans changement de rendu.
 */
internal class BarStyle(private val context: Context) {

    fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics,
    )

    fun roundedBackground(@ColorRes colorRes: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(context.themeColor(colorRes))
    }

    /** Boutons compacts : sans la largeur et la hauteur minimales par défaut des boutons Material. */
    fun compact(button: Button, paddingDp: Float) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(dp(paddingDp).toInt(), 0, dp(paddingDp).toInt(), 0)
    }

    /** Bouton à icône blanche centrée, sur le même fond arrondi que les autres boutons de la barre. */
    fun styleIconButton(button: ImageButton, iconRes: Int) {
        button.setImageResource(iconRes)
        button.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        button.background = roundedBackground(R.color.surface_button)
        val padding = dp(8f).toInt()
        button.setPadding(padding, padding, padding, padding)
    }

    fun styleButton(button: Button, @ColorRes backgroundColor: Int) {
        button.setTextColor(context.themeColor(R.color.text_primary))
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.isAllCaps = false
        button.background = roundedBackground(backgroundColor)
        button.setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
    }
}
