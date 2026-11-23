package fr.junade.taipo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.ai.TranslationLanguage

/**
 * Bandeau du mode Traduction (maquette du 21/11/2026), juste au-dessus de la zone de saisie, de gauche à droite :
 * « Traduire le texte » (la sélection ou le texte du champ, sur place ; « Traduction… » pendant le traitement), la langue de
 * départ (« Détecter » par défaut), ⇄ et la langue d'arrivée. Toucher une langue ouvre la liste des langues à la place des
 * touches ([TranslationLanguagePanelView]) ; la puce de la langue ouverte est cerclée.
 *
 * Animations : un nom de langue qui change sort vers le haut et revient d'en bas avec un rebond ; ⇄ tourne d'un demi-tour en
 * ressort. Animations Android coupées : changements directs.
 */
@SuppressLint("ViewConstructor")
internal class TranslationBarView(context: Context) : LinearLayout(context) {

    /** Côté de la liste des langues ouverte (ou aucun). */
    enum class Side { NONE, SOURCE, TARGET }

    var onTranslateFieldClicked: (() -> Unit)? = null
    var onSourceClicked: (() -> Unit)? = null
    var onTargetClicked: (() -> Unit)? = null
    var onSwapClicked: (() -> Unit)? = null

    // Déclarées avant les puces : [LanguageChip] dessine son fond (pill) dès sa construction, et un chipHeight encore à 0
    // donnait des coins carrés jusqu'au premier toucher.
    private val chipHeight = dimen(R.dimen.taipo_ai_panel_chip_height).toInt()
    private val gap = dimen(R.dimen.taipo_bar_gap).toInt()
    private val bounce = dimen(R.dimen.taipo_translation_name_bounce)

    private val action = TextView(context)
    private val source = LanguageChip(context)
    private val swap = ImageButton(context)
    private val target = LanguageChip(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val side = dimen(R.dimen.taipo_key_inset).toInt() * 2
        val vertical = dimen(R.dimen.taipo_bar_padding_vertical).toInt()
        setPadding(side, vertical, side, vertical)

        styleText(action)
        action.background = pill(R.color.accent)
        action.setTextColor(context.themeColor(R.color.text_on_accent))
        action.setOnClickListener { onTranslateFieldClicked?.invoke() }
        action.announceAsButton()
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight).apply { marginEnd = gap })

        source.setOnClickListener { onSourceClicked?.invoke() }
        addView(source, LayoutParams(0, chipHeight, 1f))

        swap.setImageResource(R.drawable.ic_swap)
        swap.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        swap.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(context.themeColor(R.color.key_functional))
        }
        swap.contentDescription = context.getString(R.string.translation_swap)
        swap.setOnClickListener {
            rotateSwap()
            onSwapClicked?.invoke()
        }
        addView(swap, LayoutParams(chipHeight, chipHeight).apply { setMargins(gap, 0, gap, 0) })

        target.setOnClickListener { onTargetClicked?.invoke() }
        addView(target, LayoutParams(0, chipHeight, 1f))
    }

    /** [busy] : une traduction tourne ; [open] : côté dont la liste des langues est ouverte. */
    fun render(sourceLanguage: TranslationLanguage?, targetLanguage: TranslationLanguage?, busy: Boolean, open: Side) {
        val sourceName = sourceLanguage?.displayName() ?: context.getString(R.string.translation_detect)
        source.setName(sourceName, animate = isLaidOut)
        source.contentDescription = context.getString(R.string.translation_source_description, sourceName)
        source.setOpen(open == Side.SOURCE)
        val targetName = targetLanguage?.displayName().orEmpty()
        target.setName(targetName, animate = isLaidOut)
        target.contentDescription = context.getString(R.string.translation_target_chip_description, targetName)
        target.setOpen(open == Side.TARGET)
        action.text = context.getString(if (busy) R.string.translation_in_progress else R.string.translation_translate_field)
        action.isEnabled = !busy
        action.alpha = if (busy) RESTRICTED_ALPHA else 1f
    }

    private fun rotateSwap() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        swap.animate().cancel()
        swap.animate()
            .rotation(swap.rotation + HALF_TURN)
            .setDuration(SWAP_MS)
            .setInterpolator(OvershootInterpolator(SWAP_OVERSHOOT))
            .start()
    }

    private fun styleText(view: TextView) {
        view.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        view.useTaipoFont(TaipoType.Weight.MEDIUM)
        view.gravity = Gravity.CENTER
        view.maxLines = 1
        val pad = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        view.setPadding(pad, 0, pad, 0)
    }

    private fun pill(colorRes: Int, strokeRes: Int? = null) = GradientDrawable().apply {
        cornerRadius = chipHeight / 2f
        setColor(context.themeColor(colorRes))
        if (strokeRes != null) setStroke(dimen(R.dimen.taipo_translation_open_stroke).toInt(), context.themeColor(strokeRes))
    }

    /** Puce d'une langue : nom (coupé si trop long) et ▾ ; cerclée pendant que sa liste est ouverte. */
    private inner class LanguageChip(context: Context) : LinearLayout(context) {
        private val name = TextView(context)
        private val caret = TextView(context)
        private var shown: String? = null
        private var open: Boolean? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            styleText(name)
            name.setTextColor(context.themeColor(R.color.text_primary))
            name.ellipsize = TextUtils.TruncateAt.END
            addView(name, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            caret.text = CARET
            caret.setTextColor(context.themeColor(R.color.text_hint))
            caret.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            caret.setPadding(0, 0, dimen(R.dimen.taipo_bar_padding_horizontal).toInt(), 0)
            addView(caret, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            announceAsButton()
            setOpen(false)
        }

        fun setOpen(value: Boolean) {
            if (open == value) return
            open = value
            background = pill(R.color.key_functional, if (value) R.color.accent else null)
            if (ValueAnimator.areAnimatorsEnabled() && isLaidOut) {
                caret.animate().rotation(if (value) HALF_TURN else 0f).setDuration(CARET_MS).start()
            } else {
                caret.rotation = if (value) HALF_TURN else 0f
            }
        }

        /** Nouveau nom : il sort vers le haut, puis revient d'en bas avec un petit rebond. */
        fun setName(text: String, animate: Boolean) {
            if (shown == text) return
            val first = shown == null
            shown = text
            if (first || !animate || !ValueAnimator.areAnimatorsEnabled()) {
                name.text = text
                return
            }
            name.animate().cancel()
            name.animate().translationY(-bounce).alpha(0f).setDuration(NAME_OUT_MS).withEndAction {
                name.text = text
                name.translationY = bounce
                name.animate().translationY(0f).alpha(1f).setDuration(NAME_IN_MS).setInterpolator(OvershootInterpolator(NAME_OVERSHOOT)).start()
            }.start()
        }
    }

    private companion object {
        const val CARET = "▾"
        const val HALF_TURN = 180f
        const val SWAP_MS = 420L
        const val SWAP_OVERSHOOT = 1.6f
        const val CARET_MS = 250L
        const val NAME_OUT_MS = 120L
        const val NAME_IN_MS = 260L
        const val NAME_OVERSHOOT = 2.2f
    }
}
