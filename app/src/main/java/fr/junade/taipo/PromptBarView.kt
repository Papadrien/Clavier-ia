package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * Story 5.1, phase 5.1-4 : la barre du haut en mode prompt (décision 8), qui prend la place de
 * [CorrectionBarView] tant que le mode est actif. De gauche à droite :
 *
 * - le bouton afficher/masquer la zone de chat, visible une fois le premier prompt envoyé (décision 5) ;
 * - la pilule de saisie, avec la croix « annuler » à gauche, dedans, qui quitte le mode prompt ;
 * - le bouton rond d'envoi, qui devient un bouton stop pendant la génération (décision 10).
 *
 * Vue seule : elle affiche ce qu'on lui donne et signale les appuis. Le texte du prompt vient de
 * `PromptInputBuffer` (phase 5.1-5), l'envoi (5.1-6) et le stop (5.1-7) sont branchés par `TaipoIme`. Pas de
 * bouton Vocal : la dictée dans le prompt est hors périmètre de la 5.1 (décision 13).
 *
 * Charte Taipo (lot 12) : envoi en violet (face `#8C00FF`, ombre `#6800AB`), stop et bouton de chat en secondaire
 * (`#2B2B2B`, ombre `#191919`, violet quand le chat est affiché), pilule de saisie `#2B2B2B` (à plat, sans ombre : ce
 * n'est pas un bouton), icônes VectorDrawable, Open Sans. Les fonds « face + ombre » sont créés une fois et posés
 * seulement quand l'état change (le rendu du bouton d'envoi est rappelé à chaque frappe).
 *
 * Même hauteur que [CorrectionBarView] (36 dp de contenu, 6 dp de marge verticale) pour que la
 * barre ne saute pas à la bascule, et même alignement sur la zone des touches en classe large.
 */
@SuppressLint("ViewConstructor")
class PromptBarView(context: Context) : LinearLayout(context) {

    private val chatToggleButton = ImageButton(context)
    private val pill = LinearLayout(context)
    private val cancelButton = ImageView(context)
    private val inputView = TextView(context)
    private val sendButton = ImageButton(context)
    private val sendSpinner = ProgressBar(context)

    private val style = BarStyle(context)
    private val sendBackground = style.roundBackground(SEND_COLOR)
    private val stopBackground = style.roundBackground(STOP_COLOR)
    private val chatShownBackground = style.pillBackground(ACCENT_COLOR)
    private val chatHiddenBackground = style.pillBackground(BUTTON_COLOR)

    private var cancelListener: (() -> Unit)? = null
    private var sendListener: (() -> Unit)? = null
    private var stopListener: (() -> Unit)? = null
    private var chatToggleListener: (() -> Unit)? = null

    private var inputBlank = true
    private var inputText = ""
    private var inputCursor = 0
    private var renderedInputWidth = -1
    private var chatShown: Boolean? = null
    private var caretVisible = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            caretVisible = !caretVisible
            renderInput()
            postDelayed(this, CARET_BLINK_MS)
        }
    }

    /** Vrai pendant la génération : le bouton rond est alors un bouton stop. */
    var generating: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderSendButton()
        }

    /**
     * Décision 8.7 : le modèle est en cours de chargement (préchargement à l'entrée du mode, décision 18).
     * L'invite de la pilule le dit tant que la saisie est vide, et le bouton d'envoi porte une roue de
     * chargement. L'envoi reste possible : il attend la fin du chargement.
     */
    var modelLoading: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderInput()
            renderSendButton()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Fond transparent : la barre laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        val barHeight = dimen(R.dimen.taipo_bar_height).toInt()
        val gap = dimen(R.dimen.taipo_bar_gap).toInt()
        val paddingH = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        val paddingV = dimen(R.dimen.taipo_bar_padding_vertical).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        // Bouton afficher/masquer le chat : caché tant qu'aucun prompt n'a été envoyé.
        style.styleBarIconButton(chatToggleButton, R.drawable.ic_chat)
        chatToggleButton.contentDescription = context.getString(R.string.prompt_chat_toggle_description)
        chatToggleButton.setOnClickListener { chatToggleListener?.invoke() }
        chatToggleButton.visibility = View.GONE
        addView(
            chatToggleButton,
            LayoutParams(dimen(R.dimen.taipo_bar_icon_button_width).toInt(), barHeight).apply { marginEnd = gap },
        )

        // Pilule de saisie : croix « annuler » à gauche, texte du prompt (ou invite) au centre.
        pill.orientation = HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = GradientDrawable().apply {
            cornerRadius = dimen(R.dimen.taipo_button_corner_radius)
            setColor(context.themeColor(PILL_COLOR))
        }

        cancelButton.setImageResource(R.drawable.ic_close)
        cancelButton.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        cancelButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        cancelButton.contentDescription = context.getString(R.string.prompt_cancel_description)
        cancelButton.setOnClickListener { cancelListener?.invoke() }
        cancelButton.announceAsButton()
        val cancelWidth = dimen(R.dimen.taipo_bar_icon_button_width).toInt()
        pill.addView(cancelButton, LayoutParams(cancelWidth, LayoutParams.MATCH_PARENT))

        inputView.setTextColor(context.themeColor(R.color.text_primary))
        inputView.setFixedTextSizeRes(R.dimen.taipo_prompt_text_size)
        inputView.useTaipoFont()
        inputView.gravity = Gravity.CENTER_VERTICAL
        inputView.isSingleLine = true
        // Le texte est découpé à la main autour du curseur (voir renderInput) : la fin seule est tronquée.
        inputView.ellipsize = TextUtils.TruncateAt.END
        inputView.setPadding(0, 0, dimen(R.dimen.taipo_prompt_input_padding_end).toInt(), 0)
        pill.addView(inputView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        // La largeur n'est connue qu'après la mise en page : on redessine alors le texte autour du curseur.
        inputView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width != renderedInputWidth) renderInput()
        }

        addView(pill, LayoutParams(0, barHeight, 1f).apply { marginEnd = gap })
        // Lot 20 : zone tactile des boutons étendue à 48 dp (36 dp dessinés), sans changer le rendu.
        expandChildTouchTargets()

        // Bouton rond : envoyer au repos, stop pendant la génération.
        sendButton.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        sendButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        // L'icône se centre sur la face du bouton rond, au-dessus de l'épaisseur d'ombre.
        val shadow = dimen(R.dimen.taipo_key_shadow_height).toInt()
        sendButton.setPadding(0, 0, 0, shadow)
        sendButton.setOnClickListener {
            if (generating) stopListener?.invoke() else sendListener?.invoke()
        }
        val roundSize = dimen(R.dimen.taipo_bar_round_button_size).toInt()
        val sendHolder = FrameLayout(context)
        sendHolder.addView(sendButton, FrameLayout.LayoutParams(roundSize, roundSize))
        val spinnerSize = dimen(R.dimen.taipo_bar_spinner_size).toInt()
        sendSpinner.isIndeterminate = true
        sendSpinner.indeterminateTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        sendSpinner.visibility = View.GONE
        // Lot 20 : la roue est décorative ; le chargement est annoncé par l'état du bouton d'envoi (voir renderSendButton).
        sendSpinner.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        sendHolder.addView(
            sendSpinner,
            FrameLayout.LayoutParams(spinnerSize, spinnerSize, Gravity.CENTER).apply { bottomMargin = shadow },
        )
        addView(sendHolder, LayoutParams(roundSize, roundSize))

        setChatShown(false)
        renderSendButton()
    }

    /** Touche sur la croix « annuler » : quitter le mode prompt (la conversation est conservée, décision 14). */
    fun setOnCancelClickListener(listener: () -> Unit) {
        cancelListener = listener
    }

    /** Touche sur le bouton d'envoi (hors génération). */
    fun setOnSendClickListener(listener: () -> Unit) {
        sendListener = listener
    }

    /** Touche sur le bouton stop (pendant la génération). */
    fun setOnStopClickListener(listener: () -> Unit) {
        stopListener = listener
    }

    /** Touche sur le bouton afficher/masquer la zone de chat. */
    fun setOnChatToggleClickListener(listener: () -> Unit) {
        chatToggleListener = listener
    }

    /**
     * Texte du prompt et position du curseur ([cursor], en caractères UTF-16), tels que les donne
     * `PromptInputBuffer` (phase 5.1-5). Le texte tient sur une ligne : le curseur est dessiné par un
     * trait coloré, et la partie avant lui est tronquée à gauche s'il le faut pour qu'il reste visible.
     * Texte vide : l'invite s'affiche.
     */
    fun setInput(text: String, cursor: Int) {
        inputText = text
        inputCursor = cursor.coerceIn(0, text.length)
        inputBlank = text.isBlank()
        restartBlink()
        renderSendButton()
    }

    /** Le curseur est plein à chaque frappe ou déplacement, puis clignote tant que la barre est affichée. */
    private fun restartBlink() {
        removeCallbacks(blinkRunnable)
        caretVisible = true
        renderInput()
        if (isAttachedToWindow) postDelayed(blinkRunnable, CARET_BLINK_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    private fun caretSpan(): ForegroundColorSpan =
        ForegroundColorSpan(if (caretVisible) context.themeColor(ACCENT_COLOR) else Color.TRANSPARENT)

    private fun renderInput() {
        val width = inputView.width - inputView.paddingLeft - inputView.paddingRight
        renderedInputWidth = inputView.width
        if (inputText.isEmpty()) {
            // Saisie vide : le curseur est dessiné devant l'invite, pour montrer que la frappe arrive dans ce champ.
            val hint = context.getString(if (modelLoading) R.string.prompt_loading_model else R.string.prompt_hint)
            // Lot 20 : TalkBack lit l'invite, sans le trait du curseur.
            inputView.contentDescription = hint
            val empty = SpannableStringBuilder(CARET).append(hint)
            empty.setSpan(caretSpan(), 0, CARET.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            empty.setSpan(
                ForegroundColorSpan(context.themeColor(HINT_COLOR)),
                CARET.length,
                empty.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            inputView.text = empty
            return
        }
        // Lot 20 : TalkBack lit le prompt tel que tapé (sans le trait du curseur ni l'ellipse du début).
        inputView.contentDescription = inputText
        var before = inputText.substring(0, inputCursor)
        val after = inputText.substring(inputCursor)
        if (width > 0) {
            // La partie avant le curseur occupe au plus 70 % de la largeur ; on garde sa fin.
            val fitting = inputView.paint.breakText(before, false, width * BEFORE_CURSOR_SHARE, null)
            if (fitting < before.length) {
                var tail = before.substring(before.length - maxOf(fitting - 1, 0))
                if (tail.isNotEmpty() && Character.isLowSurrogate(tail[0])) tail = tail.substring(1)
                before = "\u2026$tail"
            }
        }
        val display = SpannableStringBuilder(before).append(CARET).append(after)
        display.setSpan(caretSpan(), before.length, before.length + CARET.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        inputView.text = display
    }

    /** Le bouton afficher/masquer n'existe qu'une fois un prompt envoyé (décision 5). */
    fun setChatToggleVisible(visible: Boolean) {
        chatToggleButton.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** La zone de chat est affichée (bouton coloré) ou masquée. */
    fun setChatShown(shown: Boolean) {
        if (chatShown == shown) return
        chatShown = shown
        chatToggleButton.background = if (shown) chatShownBackground else chatHiddenBackground
    }

    /** Même alignement que [CorrectionBarView] : sur la zone centrée des touches en classe large. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val content = KeyboardWidth.forAvailableWidth(available, resources.displayMetrics.density)
        val side = dimen(R.dimen.taipo_bar_padding_horizontal).toInt() + content.leftPx.toInt()
        if (paddingLeft != side || paddingRight != side) {
            setPadding(side, paddingTop, side, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun renderSendButton() {
        if (generating) {
            sendButton.setImageResource(R.drawable.ic_stop)
            sendButton.background = stopBackground
            sendButton.contentDescription = context.getString(R.string.prompt_stop_description)
            ViewCompat.setStateDescription(sendButton, null)
            sendButton.isEnabled = true
            sendButton.alpha = 1f
            sendSpinner.visibility = View.GONE
        } else {
            // Modèle en chargement : la roue remplace l'icône d'envoi (l'envoi reste possible, il attendra).
            if (modelLoading) sendButton.setImageDrawable(null) else sendButton.setImageResource(R.drawable.ic_send)
            sendSpinner.visibility = if (modelLoading) View.VISIBLE else View.GONE
            sendButton.background = sendBackground
            sendButton.contentDescription = context.getString(R.string.prompt_send_description)
            // Lot 20 : pendant le chargement du modèle, le bouton le dit (la roue visuelle est masquée à TalkBack).
            ViewCompat.setStateDescription(sendButton, if (modelLoading) context.getString(R.string.prompt_loading_model) else null)
            // Un prompt vide ne s'envoie pas : le bouton est grisé.
            sendButton.isEnabled = !inputBlank
            sendButton.alpha = if (inputBlank) 0.4f else 1f
        }
    }

    private companion object {
        val BUTTON_COLOR = R.color.surface_button
        val ACCENT_COLOR = R.color.accent
        val PILL_COLOR = R.color.surface_pill
        val HINT_COLOR = R.color.text_hint
        val SEND_COLOR = R.color.action_send
        val STOP_COLOR = R.color.surface_button
        const val CARET = "|"
        const val BEFORE_CURSOR_SHARE = 0.7f
        const val CARET_BLINK_MS = 530L
    }
}
