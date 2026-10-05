package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Story 1.16 : bande de suggestions de la barre du haut, avec 4 emplacements : 3 pour des mots (à
 * gauche) et 1 pour l'emoji suggéré (à droite). Story 1.17 : les emplacements de mots
 * ([setWords]) sont remplis d'après le mot en cours de frappe. Seul le mot qui va remplacer le mot
 * tapé à l'espace (autocorrection) est en gras, au centre ; le mot tapé, proposé à côté pour
 * refuser la correction, est entre guillemets ; les simples suggestions sont en poids normal. Un
 * emplacement sans mot reste réservé (invisible), comme l'emplacement emoji quand aucun emoji
 * n'est proposé, pour que la bande ne change pas de taille.
 *
 * Charte Taipo (lot 11) : texte blanc sur le fond du clavier ; le mot de l'autocorrection (action importante) est en
 * gras sur une pastille violette ; l'emplacement emoji est un bouton secondaire. Les fonds « face + ombre » sont créés
 * une fois ([BarStyle.pillBackground]) et simplement posés ou retirés, sans allocation à chaque frappe.
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

    private val style = BarStyle(context)
    private val wordSlots = List(WORD_SLOT_COUNT) { TextView(context) }
    private val wordHighlights = List(WORD_SLOT_COUNT) { style.pillBackground(R.color.accent) }
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

        val slotPadding = dimen(R.dimen.taipo_suggestion_slot_padding).toInt()
        val slotGap = dimen(R.dimen.taipo_suggestion_slot_gap).toInt()
        // Le texte se centre sur la face des fonds : marge basse = épaisseur d'ombre, aussi sur les emplacements sans fond.
        val shadow = dimen(R.dimen.taipo_key_shadow_height).toInt()
        wordSlots.forEachIndexed { index, slot ->
            slot.setTextColor(context.themeColor(R.color.text_primary))
            slot.setFixedTextSizeRes(R.dimen.taipo_suggestion_text_size)
            slot.useTaipoFont()
            slot.gravity = Gravity.CENTER
            slot.maxLines = 1
            slot.ellipsize = TextUtils.TruncateAt.END
            slot.setPadding(slotPadding, 0, slotPadding, shadow)
            slot.visibility = View.INVISIBLE
            slot.setOnClickListener { words.getOrNull(index)?.let { wordListener?.onWordClick(it) } }
            slot.announceAsButton() // lot 20
            addView(slot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (index > 0) marginStart = slotGap })
        }

        emojiSlot.setFixedTextSizeRes(R.dimen.taipo_suggestion_emoji_text_size)
        emojiSlot.gravity = Gravity.CENTER
        emojiSlot.maxLines = 1
        emojiSlot.background = style.pillBackground(R.color.surface_button)
        emojiSlot.setPadding(0, 0, 0, shadow)
        emojiSlot.visibility = View.INVISIBLE
        emojiSlot.setOnClickListener { emoji?.let { emojiListener?.onEmojiClick(it) } }
        emojiSlot.announceAsButton() // lot 20
        val emojiWidth = dimen(R.dimen.taipo_suggestion_emoji_slot_width).toInt()
        addView(emojiSlot, LayoutParams(emojiWidth, LayoutParams.MATCH_PARENT).apply { marginStart = slotGap })
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
            slot.useTaipoFont(if (suggestion?.replacesOnSpace == true) TaipoType.Weight.BOLD else TaipoType.Weight.REGULAR)
            slot.background = if (suggestion?.replacesOnSpace == true) wordHighlights[index] else null
            slot.visibility = if (suggestion == null) View.INVISIBLE else View.VISIBLE
            // Lot 20 : TalkBack lit le mot sans les guillemets du mot tapé, et dit lequel remplacera le mot à l'espace.
            slot.contentDescription = suggestion?.text
            ViewCompat.setStateDescription(
                slot,
                if (suggestion?.replacesOnSpace == true) context.getString(R.string.a11y_suggestion_replaces_on_space) else null,
            )
        }
    }

    companion object {
        const val WORD_SLOT_COUNT = 3
    }
}
