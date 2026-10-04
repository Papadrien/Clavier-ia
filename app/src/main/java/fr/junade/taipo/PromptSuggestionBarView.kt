package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Story 5.1 : rangée de suggestions du mode prompt. Pendant le mode, la barre du haut
 * ([CorrectionBarView], qui porte la bande de suggestions habituelle) est remplacée par
 * [PromptBarView] : les suggestions de mots et d'emoji du prompt s'affichent donc ici, entre la
 * pilule de saisie et les touches. La bande est la même ([SuggestionStripView]) : 3 mots et 1 emoji,
 * emplacements réservés même vides pour que la hauteur du clavier ne change pas en tapant.
 *
 * Vue seule : le contenu vient de `TaipoIme.refreshSuggestions`, les appuis lui sont signalés.
 * Même alignement que [PromptBarView] sur la zone des touches en classe de largeur large.
 */
@SuppressLint("ViewConstructor")
class PromptSuggestionBarView(context: Context) : LinearLayout(context) {

    private val strip = SuggestionStripView(context)

    init {
        orientation = HORIZONTAL
        // Fond transparent : la rangée laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        val paddingH = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        setPadding(paddingH, 0, paddingH, dimen(R.dimen.taipo_bar_padding_vertical).toInt())
        addView(strip, LayoutParams(0, dimen(R.dimen.taipo_bar_height).toInt(), 1f))
    }

    fun setWords(words: List<WordSuggestion?>) = strip.setWords(words)

    fun setEmoji(emoji: String?) = strip.setEmoji(emoji)

    fun setOnWordClickListener(listener: SuggestionStripView.OnWordClickListener) =
        strip.setOnWordClickListener(listener)

    fun setOnEmojiClickListener(listener: SuggestionStripView.OnEmojiClickListener) =
        strip.setOnEmojiClickListener(listener)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val content = KeyboardWidth.forAvailableWidth(available, resources.displayMetrics.density)
        val side = dimen(R.dimen.taipo_bar_padding_horizontal).toInt() + content.leftPx.toInt()
        if (paddingLeft != side || paddingRight != side) {
            setPadding(side, paddingTop, side, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
