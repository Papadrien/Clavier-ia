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
 * emplacement sans mot, comme l'emplacement emoji quand aucun emoji n'est proposé, est retiré de la
 * bande (GONE) : les mots proposés se partagent toute la largeur disponible de la barre. La hauteur,
 * elle, ne change pas (MATCH_PARENT).
 *
 * Charte Taipo (lot 11) : texte blanc sur le fond du clavier ; le mot de l'autocorrection est en gras, sans
 * pastille ni fond ; l'emplacement emoji est un bouton secondaire, dont le fond « face + ombre » est créé une fois
 * ([BarStyle.pillBackground]), sans allocation à chaque frappe.
 *
 * La copie récente proposée au collage (pilule à droite, après l'emoji) vient aussi dans la bande, tant qu'elle est proposée.
 *
 * Un mot trop long pour son emplacement est tronqué au début (« …ement ») : le début est déjà tapé, la fin est ce qui
 * distingue les suggestions.
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
    private val emojiSlot = TextView(context)

    /** Copie récente à coller (à droite de la bande, après l'emoji) : aperçu du texte copié, sans icône. */
    private val pasteSlot = TextView(context)
    private val slotGap = dimen(R.dimen.taipo_suggestion_slot_gap).toInt()
    private var emojiListener: OnEmojiClickListener? = null
    private var wordListener: OnWordClickListener? = null
    private var pasteListener: (() -> Unit)? = null

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
        // Le texte se centre sur la face des fonds : marge basse = épaisseur d'ombre, aussi sur les emplacements sans fond.
        val shadow = dimen(R.dimen.taipo_key_shadow_height).toInt()
        wordSlots.forEachIndexed { index, slot ->
            slot.setTextColor(context.themeColor(R.color.text_primary))
            slot.setFixedTextSizeRes(R.dimen.taipo_suggestion_text_size)
            slot.useTaipoFont()
            slot.gravity = Gravity.CENTER
            slot.maxLines = 1
            // Mot trop long pour l'emplacement : « …» au début, pas à la fin (le début du mot est déjà tapé, la fin est ce qui compte).
            slot.ellipsize = TextUtils.TruncateAt.START
            slot.setPadding(slotPadding, 0, slotPadding, shadow)
            slot.visibility = View.GONE
            slot.setOnClickListener { words.getOrNull(index)?.let { wordListener?.onWordClick(it) } }
            slot.announceAsButton() // lot 20
            addView(slot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        emojiSlot.setFixedTextSizeRes(R.dimen.taipo_suggestion_emoji_text_size)
        emojiSlot.gravity = Gravity.CENTER
        emojiSlot.maxLines = 1
        emojiSlot.background = style.pillBackground(R.color.surface_button)
        emojiSlot.setPadding(0, 0, 0, shadow)
        emojiSlot.visibility = View.GONE
        emojiSlot.setOnClickListener { emoji?.let { emojiListener?.onEmojiClick(it) } }
        emojiSlot.announceAsButton() // lot 20
        val emojiWidth = dimen(R.dimen.taipo_suggestion_emoji_slot_width).toInt()
        addView(emojiSlot, LayoutParams(emojiWidth, LayoutParams.MATCH_PARENT))

        val chipPadding = dimen(R.dimen.taipo_suggestion_chip_padding).toInt()
        pasteSlot.setTextColor(context.themeColor(R.color.text_primary))
        pasteSlot.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        pasteSlot.useTaipoFont()
        pasteSlot.gravity = Gravity.CENTER
        pasteSlot.maxLines = 1
        pasteSlot.ellipsize = TextUtils.TruncateAt.END
        pasteSlot.maxWidth = dimen(R.dimen.taipo_suggestion_paste_slot_max_width).toInt()
        pasteSlot.background = style.pillBackground(R.color.surface_button)
        pasteSlot.setPadding(chipPadding, 0, chipPadding, shadow)
        pasteSlot.visibility = View.GONE
        pasteSlot.setOnClickListener { pasteListener?.invoke() }
        pasteSlot.announceAsButton() // lot 20
        addView(pasteSlot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun setOnPasteClickListener(listener: () -> Unit) {
        pasteListener = listener
    }

    /**
     * Propose le collage de la copie récente avec l'aperçu [preview] (déjà mis en forme, éventuellement masqué), ou retire
     * la pilule si [preview] est null. [sensitive] : l'aperçu est masqué, la description d'accessibilité ne révèle donc pas le texte.
     */
    fun setPaste(preview: String?, sensitive: Boolean) {
        if (preview == null) {
            if (pasteSlot.visibility == View.GONE) return
            pasteSlot.text = ""
            pasteSlot.visibility = View.GONE
            updateGaps()
            return
        }
        pasteSlot.text = preview
        pasteSlot.contentDescription = if (sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else {
            context.getString(R.string.clipboard_paste_description, preview)
        }
        pasteSlot.visibility = View.VISIBLE
        updateGaps()
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
        emojiSlot.visibility = if (newEmoji == null) View.GONE else View.VISIBLE
        updateGaps()
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
            // Adresse e-mail trop longue : « … » à la fin (c'est son début, déjà tapé, qui identifie l'adresse, mais la fin se lit aussi).
            slot.ellipsize = if (suggestion?.kind == WordSuggestion.Kind.EMAIL) TextUtils.TruncateAt.END else TextUtils.TruncateAt.START
            slot.useTaipoFont(if (suggestion?.replacesOnSpace == true) TaipoType.Weight.BOLD else TaipoType.Weight.REGULAR)
            slot.visibility = if (suggestion == null) View.GONE else View.VISIBLE
            // Lot 20 : TalkBack lit le mot sans les guillemets du mot tapé, et dit lequel remplacera le mot à l'espace.
            slot.contentDescription = suggestion?.text
            ViewCompat.setStateDescription(
                slot,
                if (suggestion?.replacesOnSpace == true) context.getString(R.string.a11y_suggestion_replaces_on_space) else null,
            )
        }
        updateGaps()
    }

    /**
     * Espace de [slotGap] entre deux éléments visibles seulement : les emplacements retirés ne laissent ni
     * marge ni trou, et le premier élément visible est collé au bord gauche de la bande.
     */
    private fun updateGaps() {
        var first = true
        val visible = wordSlots + emojiSlot + pasteSlot
        for (view in visible) {
            if (view.visibility != View.VISIBLE) continue
            val params = view.layoutParams as LayoutParams
            val margin = if (first) 0 else slotGap
            if (params.marginStart != margin) {
                params.marginStart = margin
                view.layoutParams = params
            }
            first = false
        }
    }

    companion object {
        const val WORD_SLOT_COUNT = 3
    }
}
