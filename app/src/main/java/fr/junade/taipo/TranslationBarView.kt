package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.ai.TranslationLanguage

/**
 * Bandeau du mode Traduction (20/11/2026), juste au-dessus de la zone de saisie : « Traduire le texte du champ » (ou l'état
 * « Traduction… »), puis les langues cibles en puces défilantes, la langue choisie en violet. La langue d'origine est détectée
 * par le modèle : pas de choix à faire (Gboard propose « Détecter la langue » par défaut).
 */
@SuppressLint("ViewConstructor")
internal class TranslationBarView(context: Context) : LinearLayout(context) {

    var onLanguageSelected: ((TranslationLanguage) -> Unit)? = null
    var onTranslateFieldClicked: (() -> Unit)? = null

    private val action = TextView(context)
    private val scroll = HorizontalScrollView(context)
    private val chips = LinearLayout(context)
    private val chipHeight = dimen(R.dimen.taipo_ai_panel_chip_height).toInt()
    private val gap = dimen(R.dimen.taipo_bar_gap).toInt()
    private var shownLanguages: List<TranslationLanguage> = emptyList()
    private var shownTarget: TranslationLanguage? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val side = dimen(R.dimen.taipo_key_inset).toInt() * 2
        val vertical = dimen(R.dimen.taipo_bar_padding_vertical).toInt()
        setPadding(side, vertical, side, vertical)
        styleChip(action, selected = false)
        action.setOnClickListener { onTranslateFieldClicked?.invoke() }
        action.announceAsButton()
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight).apply { marginEnd = gap })
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        chips.orientation = HORIZONTAL
        scroll.addView(chips)
        addView(scroll, LayoutParams(0, chipHeight, 1f))
    }

    /**
     * [languages] et [target] : puces des langues. [busy] : une traduction tourne (l'action montre « Traduction… »).
     * [canTranslateField] : rien n'est tapé et le champ a du texte, l'action « Traduire le texte » est proposée.
     */
    fun render(languages: List<TranslationLanguage>, target: TranslationLanguage?, busy: Boolean, canTranslateField: Boolean) {
        if (languages != shownLanguages || target != shownTarget) {
            val firstShow = shownLanguages.isEmpty()
            shownLanguages = languages
            shownTarget = target
            chips.removeAllViews()
            for (language in languages) {
                val chip = TextView(context)
                chip.text = language.displayName()
                styleChip(chip, selected = language == target)
                chip.contentDescription = context.getString(R.string.translation_target_description, language.displayName())
                chip.isSelected = language == target
                chip.setOnClickListener { onLanguageSelected?.invoke(language) }
                chip.announceAsButton()
                chips.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight).apply { marginEnd = gap })
            }
            if (firstShow) scroll.post { scroll.scrollTo(0, 0) }
        }
        when {
            busy -> {
                action.text = context.getString(R.string.translation_in_progress)
                action.isEnabled = false
                action.visibility = View.VISIBLE
            }
            canTranslateField -> {
                action.text = context.getString(R.string.translation_translate_field)
                action.isEnabled = true
                action.visibility = View.VISIBLE
            }
            else -> action.visibility = View.GONE
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.setTextColor(context.themeColor(if (selected) R.color.text_on_accent else R.color.text_primary))
        chip.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        chip.useTaipoFont()
        chip.gravity = Gravity.CENTER
        chip.maxLines = 1
        val pad = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        chip.setPadding(pad, 0, pad, 0)
        chip.background = GradientDrawable().apply {
            cornerRadius = chipHeight / 2f
            setColor(context.themeColor(if (selected) R.color.accent else R.color.key_functional))
        }
    }
}
