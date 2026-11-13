package fr.junade.taipo

import android.app.Activity
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.activity.ComponentActivity

/**
 * Flèche retour en haut à gauche d'une page de l'application (13/11/2026 : toutes les pages sauf l'accueil). Elle revient à la
 * page précédente, comme le geste ou le bouton Retour d'Android (les pages qui interceptent le retour le font aussi pour elle).
 *
 * Posée au-dessus du contenu de la page, qui est déplacé dans une colonne : elle reste visible quand la page défile. Alignée sur
 * le bord du texte de la page (la zone tactile de 48 dp déborde dans la marge).
 */
fun Activity.addBackArrow() {
    val content = findViewById<FrameLayout>(android.R.id.content)
    if (content.findViewById<ImageButton>(R.id.back_arrow) != null || content.childCount == 0) return
    val page = content.getChildAt(0)
    content.removeView(page)
    val touch = resources.getDimensionPixelSize(R.dimen.taipo_touch_target_min)
    val inset = resources.getDimensionPixelSize(R.dimen.taipo_settings_screen_padding) - touch / 4
    val arrow = ImageButton(this).apply {
        id = R.id.back_arrow
        setImageResource(R.drawable.ic_arrow_back)
        imageTintList = ColorStateList.valueOf(themeColor(R.color.text_primary))
        contentDescription = getString(R.string.back_arrow_description)
        val ripple = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setOnClickListener { goBack() }
    }
    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    column.addView(arrow, LinearLayout.LayoutParams(touch, touch).apply { marginStart = inset.coerceAtLeast(0) })
    column.addView(page, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    content.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
}

/** Même chose que le bouton Retour d'Android : les rappels de retour de la page (modifications non enregistrées…) s'appliquent. */
private fun Activity.goBack() {
    if (this is ComponentActivity) {
        onBackPressedDispatcher.onBackPressed()
    } else {
        @Suppress("DEPRECATION")
        onBackPressed()
    }
}
