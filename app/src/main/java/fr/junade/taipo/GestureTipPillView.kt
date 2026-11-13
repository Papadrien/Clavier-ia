package fr.junade.taipo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Astuce de geste ([GestureTips]) dans la barre du haut : pilule de la hauteur des boutons ronds (36 dp), teintée de l'accent,
 * posée entre le premier bouton (robot ou Paramètres) et Vocal ([CorrectionBarView.showGestureTip]). Ampoule + texte sur une
 * ligne ; un texte trop long pour la pilule défile une fois (marquee), sauf si les animations sont désactivées (il est alors
 * coupé par « … »). Le fond est opaque : la pilule masque la bande de mots ou les boutons qu'elle recouvre.
 */
@SuppressLint("ViewConstructor")
internal class GestureTipPillView(context: Context) : LinearLayout(context) {

    private val icon = ImageView(context)
    private val text = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val height = dimen(R.dimen.taipo_bar_round_button_size)
        setPadding(dimen(R.dimen.taipo_gesture_tip_padding_start).toInt(), 0, dimen(R.dimen.taipo_gesture_tip_padding_end).toInt(), 0)
        background = GradientDrawable().apply {
            cornerRadius = height / 2f
            setColor(
                ColorUtils.blendARGB(
                    context.themeColor(R.color.keyboard_background),
                    context.themeColor(R.color.accent),
                    TINT_RATIO,
                ),
            )
        }
        val ink = context.themeColor(R.color.accent_on_key)
        icon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_tip_bulb))
        icon.imageTintList = ColorStateList.valueOf(ink)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        text.setTextColor(ink)
        text.setFixedTextSizeRes(R.dimen.taipo_bar_button_text_size)
        text.useTaipoFont(TaipoType.Weight.MEDIUM)
        text.setSingleLine(true)
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val iconSize = dimen(R.dimen.taipo_bar_pill_icon_size).toInt()
        addView(icon, LayoutParams(iconSize, iconSize))
        addView(
            text,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dimen(R.dimen.taipo_bar_gap).toInt() },
        )
        isClickable = true
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /**
     * Affiche [message] avec l'icône [iconRes] (ampoule des astuces, étincelles des nouveautés) ; [description] : texte lu par
     * TalkBack (par défaut « Astuce : … »). Le message défile une fois s'il ne tient pas (animations actives), sinon il est coupé.
     */
    fun setMessage(message: String, iconRes: Int = R.drawable.ic_tip_bulb, description: String? = null) {
        text.text = message
        icon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
        contentDescription = description ?: context.getString(R.string.gesture_tip_description, message)
        if (ValueAnimator.areAnimatorsEnabled()) {
            text.ellipsize = TextUtils.TruncateAt.MARQUEE
            text.marqueeRepeatLimit = 1
            text.isSelected = true // le défilement ne démarre que sur une vue sélectionnée
        } else {
            text.ellipsize = TextUtils.TruncateAt.END
            text.isSelected = false
        }
    }

    private companion object {
        /** Part de l'accent dans le fond de la pilule (le reste : fond du clavier). */
        const val TINT_RATIO = 0.16f
    }
}
