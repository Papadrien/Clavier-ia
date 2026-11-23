package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
 * La copie récente proposée au collage vient aussi dans la bande, tant qu'elle est proposée : la pilule a le même poids qu'un
 * emplacement de mot et se place après les mots (avant l'emoji). Avec trois mots, elle prend la place du 3e ; sans aucun mot,
 * elle occupe toute la largeur disponible.
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

    /** Appui long sur un mot (23/11/2026, [SuggestionWordDrag]) : relâché ailleurs, ajout ; déposé sur la poubelle, retrait. */
    interface OnWordEditListener {
        fun onWordAdd(suggestion: WordSuggestion)
        fun onWordRemove(suggestion: WordSuggestion)
    }

    private val style = BarStyle(context)
    private val wordSlots = List(WORD_SLOT_COUNT) { TextView(context) }
    private val emojiSlot = TextView(context)

    /** Copie récente à coller (après les mots, avant l'emoji) : aperçu du texte copié, sans icône. */
    private val pasteSlot = TextView(context)
    private val slotGap = dimen(R.dimen.taipo_suggestion_slot_gap).toInt()
    private var emojiListener: OnEmojiClickListener? = null
    private var wordListener: OnWordClickListener? = null
    private var pasteListener: (() -> Unit)? = null
    private var editListener: OnWordEditListener? = null

    // Appui long et glisser-déposer d'un mot (voir SuggestionWordDrag).
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pressedIndex = -1
    private var pressDownX = 0f
    private var pressDownY = 0f
    private var pressMoved = false
    private var dragged: WordSuggestion? = null
    private var dragX = 0f
    private var dragY = 0f
    private var overTrash = false
    private val longPressRunnable = Runnable { startDrag() }
    private val trashIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_trash)?.mutate()
    private val trashPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.accent) }
    private val dragTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_on_accent)
        textSize = dimen(R.dimen.taipo_suggestion_text_size)
        textAlign = Paint.Align.CENTER
        typeface = TaipoType.typeface(context, TaipoType.Weight.BOLD)
    }
    private val dragRect = RectF()
    private val a11yAddIds = IntArray(WORD_SLOT_COUNT) { View.NO_ID }
    private val a11yRemoveIds = IntArray(WORD_SLOT_COUNT) { View.NO_ID }

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
            slot.setOnTouchListener { view, event -> onSlotTouch(index, view, event) }
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

        val chipPadding = dimen(R.dimen.taipo_suggestion_chip_padding).toInt()
        pasteSlot.setTextColor(context.themeColor(R.color.text_primary))
        pasteSlot.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        pasteSlot.useTaipoFont()
        pasteSlot.gravity = Gravity.CENTER
        pasteSlot.maxLines = 1
        pasteSlot.ellipsize = TextUtils.TruncateAt.END
        pasteSlot.background = style.pillBackground(R.color.surface_button)
        pasteSlot.setPadding(chipPadding, 0, chipPadding, shadow)
        pasteSlot.visibility = View.GONE
        pasteSlot.setOnClickListener { pasteListener?.invoke() }
        pasteSlot.announceAsButton() // lot 20
        // Même poids qu'un emplacement de mot : la pilule prend la place du 3e mot, ou toute la bande s'il n'y a aucun mot.
        addView(pasteSlot, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        // L'emoji reste tout à droite, après la pilule.
        val emojiWidth = dimen(R.dimen.taipo_suggestion_emoji_slot_width).toInt()
        addView(emojiSlot, LayoutParams(emojiWidth, LayoutParams.MATCH_PARENT))
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
            refreshWordSlotsVisibility()
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
        refreshWordSlotsVisibility()
        updateGaps()
    }

    /** Le dernier emplacement de mot cède sa place à la pilule de collage tant qu'elle est affichée. */
    private fun isWordSlotShown(index: Int, suggestion: WordSuggestion?): Boolean =
        suggestion != null && !(index == WORD_SLOT_COUNT - 1 && pasteSlot.visibility == View.VISIBLE)

    private fun refreshWordSlotsVisibility() {
        wordSlots.forEachIndexed { index, slot ->
            slot.visibility = if (isWordSlotShown(index, words.getOrNull(index))) View.VISIBLE else View.GONE
        }
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener) {
        emojiListener = listener
    }

    fun setOnWordClickListener(listener: OnWordClickListener) {
        wordListener = listener
    }

    fun setOnWordEditListener(listener: OnWordEditListener?) {
        editListener = listener
    }

    /**
     * Toucher d'un emplacement de mot : un toucher court reste un clic ; tenu sans bouger ([longPressTimeout]), le mot se détache
     * ([startDrag]) et suit le doigt jusqu'au relâchement ([endDrag]).
     */
    @SuppressLint("ClickableViewAccessibility") // le clic court passe par performClick ; actions TalkBack dans setWords
    private fun onSlotTouch(index: Int, view: View, event: MotionEvent): Boolean {
        val stripX = event.x + view.left
        val stripY = event.y + view.top
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = index
                pressDownX = stripX
                pressDownY = stripY
                pressMoved = false
                view.isPressed = true
                val suggestion = words.getOrNull(index)
                if (editListener != null && suggestion != null && SuggestionWordDrag.canDrag(suggestion)) {
                    postDelayed(longPressRunnable, longPressTimeout)
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragged != null) {
                dragX = stripX
                dragY = stripY
                overTrash = SuggestionWordDrag.isOverTrash(dragX, dragY, width / 2f, trashRadius(), height.toFloat())
                invalidate()
            } else if (!pressMoved && (kotlin.math.abs(stripX - pressDownX) > touchSlop || kotlin.math.abs(stripY - pressDownY) > touchSlop)) {
                pressMoved = true
                removeCallbacks(longPressRunnable)
                view.isPressed = false
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                view.isPressed = false
                if (dragged != null) {
                    endDrag(drop = true)
                } else if (!pressMoved) {
                    view.performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                view.isPressed = false
                endDrag(drop = false)
            }
        }
        return true
    }

    private fun startDrag() {
        val suggestion = words.getOrNull(pressedIndex) ?: return
        dragged = suggestion
        dragX = pressDownX
        dragY = pressDownY
        overTrash = false
        wordSlots.getOrNull(pressedIndex)?.isPressed = false
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        for (child in wordSlots + pasteSlot + emojiSlot) child.alpha = DIMMED_ALPHA
        invalidate()
    }

    /** Fin du glisser : [drop] faux (geste annulé) ne fait rien ; sinon retrait sur la poubelle, ajout ailleurs. */
    private fun endDrag(drop: Boolean) {
        val suggestion = dragged ?: return
        val removing = overTrash
        dragged = null
        overTrash = false
        for (child in wordSlots + pasteSlot + emojiSlot) child.alpha = 1f
        invalidate()
        if (!drop) return
        if (removing) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            editListener?.onWordRemove(suggestion)
        } else {
            editListener?.onWordAdd(suggestion)
        }
    }

    private fun trashRadius(): Float = minOf(height * TRASH_HEIGHT_RATIO, dimen(R.dimen.taipo_icon_size))

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val suggestion = dragged ?: return
        // Poubelle au milieu de la bande : rouge quand le mot est dessus.
        val cx = width / 2f
        val cy = height / 2f
        val radius = trashRadius()
        trashPaint.color = if (overTrash) TRASH_ACTIVE else context.themeColor(R.color.surface_button)
        canvas.drawCircle(cx, cy, radius, trashPaint)
        trashIcon?.let { icon ->
            icon.setTint(if (overTrash) TRASH_ICON_ACTIVE else context.themeColor(R.color.text_primary))
            val half = (radius * TRASH_ICON_RATIO).toInt()
            icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            icon.draw(canvas)
        }
        // Le mot détaché suit le doigt, dans une pastille de la couleur d'accent.
        val text = suggestion.text
        val textWidth = dragTextPaint.measureText(text)
        val pad = dimen(R.dimen.taipo_suggestion_slot_padding)
        val pillHalfHeight = dragTextPaint.textSize * PILL_HEIGHT_RATIO
        val y = dragY.coerceIn(pillHalfHeight, height - pillHalfHeight)
        dragRect.set(dragX - textWidth / 2f - pad, y - pillHalfHeight, dragX + textWidth / 2f + pad, y + pillHalfHeight)
        canvas.drawRoundRect(dragRect, pillHalfHeight, pillHalfHeight, dragPaint)
        canvas.drawText(text, dragX, y - (dragTextPaint.descent() + dragTextPaint.ascent()) / 2f, dragTextPaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        endDrag(drop = false)
        super.onDetachedFromWindow()
    }

    /** Lot 20 : TalkBack propose « Ajouter au dictionnaire » et « Retirer du dictionnaire » sur chaque mot (pas d'appui long). */
    private fun updateEditActions(index: Int, slot: TextView, suggestion: WordSuggestion?) {
        if (a11yAddIds[index] != View.NO_ID) ViewCompat.removeAccessibilityAction(slot, a11yAddIds[index])
        if (a11yRemoveIds[index] != View.NO_ID) ViewCompat.removeAccessibilityAction(slot, a11yRemoveIds[index])
        a11yAddIds[index] = View.NO_ID
        a11yRemoveIds[index] = View.NO_ID
        val listener = editListener ?: return
        if (suggestion == null || !SuggestionWordDrag.canDrag(suggestion)) return
        a11yAddIds[index] = ViewCompat.addAccessibilityAction(slot, context.getString(R.string.suggestion_word_add_action)) { _, _ ->
            listener.onWordAdd(suggestion)
            true
        }
        a11yRemoveIds[index] = ViewCompat.addAccessibilityAction(slot, context.getString(R.string.suggestion_word_remove_action)) { _, _ ->
            listener.onWordRemove(suggestion)
            true
        }
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
            slot.visibility = if (isWordSlotShown(index, suggestion)) View.VISIBLE else View.GONE
            // Lot 20 : TalkBack lit le mot sans les guillemets du mot tapé, et dit lequel remplacera le mot à l'espace.
            slot.contentDescription = suggestion?.text
            ViewCompat.setStateDescription(
                slot,
                if (suggestion?.replacesOnSpace == true) context.getString(R.string.a11y_suggestion_replaces_on_space) else null,
            )
            updateEditActions(index, slot, suggestion)
        }
        updateGaps()
    }

    /**
     * Espace de [slotGap] entre deux éléments visibles seulement : les emplacements retirés ne laissent ni
     * marge ni trou, et le premier élément visible est collé au bord gauche de la bande.
     */
    private fun updateGaps() {
        var first = true
        val visible = wordSlots + pasteSlot + emojiSlot
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

        /** Opacité des emplacements pendant qu'un mot est glissé. */
        private const val DIMMED_ALPHA = 0.25f

        /** Rayon de la poubelle, en part de la hauteur de la bande (plafonné à la taille d'une icône). */
        private const val TRASH_HEIGHT_RATIO = 0.42f
        private const val TRASH_ICON_RATIO = 0.55f
        private const val PILL_HEIGHT_RATIO = 0.95f

        /** Rouge de la poubelle survolée : lisible en thème clair comme sombre. */
        private const val TRASH_ACTIVE = 0xFFE5484D.toInt()
        private const val TRASH_ICON_ACTIVE = 0xFFFFFFFF.toInt()
    }
}
