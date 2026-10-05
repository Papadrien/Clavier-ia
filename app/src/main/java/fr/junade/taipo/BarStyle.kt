package fr.junade.taipo

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import androidx.annotation.ColorRes

/**
 * Style commun des boutons de la barre du haut (lot 4.2) : fonds « face + ombre » de la charte Taipo (lots 08, 10 et 11),
 * texte ou icône blanche. Sorti de [CorrectionBarView].
 */
internal class BarStyle(private val context: Context) {

    /**
     * Fond rond de la charte (lot 08) : face [faceRes] sur une ombre de la couleur associée, qui s'enfonce à l'appui.
     * Réservé aux boutons ronds secondaires de la barre (réglages, générer, vocal).
     */
    fun roundBackground(@ColorRes faceRes: Int): RoundKeyDrawable = RoundKeyDrawable(
        faceColor = context.themeColor(faceRes),
        shadowColor = context.themeColor(shadowFor(faceRes)),
        shadowHeight = context.dimen(R.dimen.taipo_key_shadow_height),
        pressedShadowHeight = context.dimen(R.dimen.taipo_key_shadow_pressed_height),
    )

    @ColorRes
    private fun shadowFor(@ColorRes faceRes: Int): Int = when (faceRes) {
        R.color.accent, R.color.action_send -> R.color.taipo_purple_shadow
        R.color.action_danger -> R.color.action_danger_shadow
        else -> R.color.taipo_key_secondary_shadow
    }

    /**
     * Fond rectangulaire arrondi de la charte (lot 10) : face [faceRes] sur une ombre de la couleur associée, qui
     * s'enfonce à l'appui. Rayon : [R.dimen.taipo_button_corner_radius]. Boutons à texte ou à icône de la barre du haut, bande de suggestions.
     */
    fun pillBackground(@ColorRes faceRes: Int): PillKeyDrawable = PillKeyDrawable(
        faceColor = context.themeColor(faceRes),
        shadowColor = context.themeColor(shadowFor(faceRes)),
        cornerRadius = context.dimen(R.dimen.taipo_button_corner_radius),
        shadowHeight = context.dimen(R.dimen.taipo_key_shadow_height),
        pressedShadowHeight = context.dimen(R.dimen.taipo_key_shadow_pressed_height),
    )

    /**
     * Lot 21 : fonds « au repos » / « actif » (violet) d'un bouton à bascule (menu, Smart Clipboard), créés à la demande puis
     * réutilisés : un changement d'état pose un fond existant au lieu d'en allouer un nouveau. Un jeu par bouton (un
     * Drawable n'appartient qu'à une seule vue).
     */
    fun toggleBackgrounds(): ToggleBackgrounds = ToggleBackgrounds { active ->
        pillBackground(if (active) R.color.accent else R.color.surface_button)
    }

    /**
     * Bouton rond à icône blanche (lots 08 et 09) : l'icône garde sa taille ([R.dimen.taipo_icon_size]) et se centre sur
     * la face, au-dessus de l'épaisseur d'ombre (marge basse = ombre au repos).
     */
    fun styleRoundIconButton(button: ImageButton, iconRes: Int, @ColorRes faceRes: Int = R.color.surface_button) {
        applyIcon(button, iconRes)
        button.background = roundBackground(faceRes)
        button.setPadding(0, 0, 0, context.dimen(R.dimen.taipo_key_shadow_height).toInt())
    }

    private fun applyIcon(button: ImageButton, iconRes: Int) {
        button.setImageResource(iconRes)
        button.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
    }

    /**
     * Bouton à icône blanche de la barre du haut (lot 10), sur un fond [pillBackground] : l'icône garde sa taille et se
     * centre sur la face, au-dessus de l'épaisseur d'ombre.
     */
    fun styleBarIconButton(button: ImageButton, iconRes: Int, @ColorRes faceRes: Int = R.color.surface_button) {
        applyIcon(button, iconRes)
        button.background = pillBackground(faceRes)
        button.setPadding(0, 0, 0, context.dimen(R.dimen.taipo_key_shadow_height).toInt())
    }

    /** Bouton à texte de la barre du haut (lot 10) : Open Sans gras, fond [pillBackground] ; le texte se centre sur la face. */
    fun styleButton(button: Button, @ColorRes backgroundColor: Int) {
        button.setTextColor(context.themeColor(R.color.text_primary))
        button.useTaipoFont(TaipoType.Weight.BOLD)
        button.setFixedTextSizeRes(R.dimen.taipo_bar_button_text_size)
        button.isAllCaps = false
        button.maxLines = 1
        button.ellipsize = TextUtils.TruncateAt.END
        button.maxWidth = context.dimen(R.dimen.taipo_bar_text_button_max_width).toInt()
        button.background = pillBackground(backgroundColor)
        val padding = context.dimen(R.dimen.taipo_bar_text_button_padding).toInt()
        button.setPadding(padding, 0, padding, context.dimen(R.dimen.taipo_key_shadow_height).toInt())
    }
}

/** Lot 21 : les deux fonds d'un bouton à bascule, construits au premier besoin par [create] (`true` = actif), puis gardés. */
internal class ToggleBackgrounds(private val create: (active: Boolean) -> Drawable) {
    private var rest: Drawable? = null
    private var active: Drawable? = null

    fun get(isActive: Boolean): Drawable =
        if (isActive) active ?: create(true).also { active = it } else rest ?: create(false).also { rest = it }
}
