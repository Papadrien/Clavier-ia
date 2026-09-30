package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Story 1.16 : bande de suggestions de la barre du haut, avec 4 emplacements : 3 pour des mots (à
 * gauche) et 1 pour l'emoji suggéré (à droite). Story 1.17 : les emplacements de mots
 * ([setWords]) sont remplis d'après le mot en cours de frappe. Seul le mot qui va remplacer le mot
 * tapé à l'espace (autocorrection) est en gras, au centre ; le mot tapé, proposé à côté pour
 * refuser la correction, est entre guillemets ; les simples suggestions sont en poids normal. Un
 * emplacement sans mot reste réservé (invisible), comme l'emplacement emoji quand aucun emoji
 * n'est proposé, pour que la bande ne change pas de taille.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(context: Context) : LinearLayout(context) {

    fun interface OnEmojiClickListener {
        fun onEmojiClick(emoji: String)
    }

    /** Story 1.17 : touche sur un mot suggéré. */
    fun interface OnWordClickListener {
        fun onWordClick(suggestion: WordSuggestion)
    }

    private val wordSlots = List(WORD_SLOT_COUNT) { TextView(context) }
    private val emojiSlot = TextView(context)
    private var emojiListener: OnEmojiClickListener? = null
    private var wordListener: OnWordClickListener? = null

    /** Contenu des emplacements de mots (null = emplacement vide), au plus [WORD_SLOT_COUNT]. */
    var words: List<WordSuggestion?> = emptyList()
        private set

    /** Emoji actuellement proposé, ou null. */
    var emoji: String? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        wordSlots.forEachIndexed { index, slot ->
            slot.setTextColor(Color.WHITE)
            slot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            slot.gravity = Gravity.CENTER
            slot.maxLines = 1
            slot.ellipsize = TextUtils.TruncateAt.END
            slot.setPadding(dp(4f).toInt(), 0, dp(4f).toInt(), 0)
            slot.visibility = View.INVISIBLE
            slot.setOnClickListener { words.getOrNull(index)?.let { wordListener?.onWordClick(it) } }
            addView(slot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        emojiSlot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        emojiSlot.gravity = Gravity.CENTER
        emojiSlot.maxLines = 1
        emojiSlot.background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(Color.parseColor("#3A3F47"))
        }
        emojiSlot.visibility = View.INVISIBLE
        emojiSlot.setOnClickListener { emoji?.let { emojiListener?.onEmojiClick(it) } }
        addView(emojiSlot, LayoutParams(dp(48f).toInt(), LayoutParams.MATCH_PARENT))
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener) {
        emojiListener = listener
    }

    fun setOnWordClickListener(listener: OnWordClickListener) {
        wordListener = listener
    }

    /** Affiche [newEmoji] dans l'emplacement emoji, ou le vide si null. */
    fun setEmoji(newEmoji: String?) {
        if (emoji == newEmoji) return
        emoji = newEmoji
        emojiSlot.text = newEmoji.orEmpty()
        emojiSlot.visibility = if (newEmoji == null) View.INVISIBLE else View.VISIBLE
    }

    /**
     * Remplit les emplacements de mots (au plus 3, de gauche à droite) ; un emplacement null ou
     * absent reste invisible. Gras uniquement pour le mot qui remplacera le mot tapé à l'espace.
     */
    fun setWords(newWords: List<WordSuggestion?>) {
        val limited = newWords.take(WORD_SLOT_COUNT)
        if (words == limited) return
        words = limited
        wordSlots.forEachIndexed { index, slot ->
            val suggestion = limited.getOrNull(index)
            slot.text = when {
                suggestion == null -> ""
                suggestion.kind == WordSuggestion.Kind.TYPED -> "\u201C${suggestion.text}\u201D"
                else -> suggestion.text
            }
            slot.setTypeface(null, if (suggestion?.replacesOnSpace == true) Typeface.BOLD else Typeface.NORMAL)
            slot.visibility = if (suggestion == null) View.INVISIBLE else View.VISIBLE
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )

    companion object {
        const val WORD_SLOT_COUNT = 3
    }
}
