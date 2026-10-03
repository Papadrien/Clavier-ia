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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Story 5.1, phase 5.1-4 : la barre du haut en mode prompt (décision 8), qui prend la place de
 * [CorrectionBarView] tant que le mode est actif. De gauche à droite :
 *
 * - le bouton afficher/masquer la zone de chat, visible une fois le premier prompt envoyé (décision 5) ;
 * - la pilule de saisie, avec la croix « annuler » à gauche, dedans, qui quitte le mode prompt ;
 * - le bouton rond d'envoi, qui devient un bouton stop pendant la génération (décision 10).
 *
 * Vue seule : elle affiche ce qu'on lui donne et signale les appuis. Le texte du prompt vient de
 * `PromptInputBuffer` (phase 5.1-5), l'envoi (5.1-6) et le stop (5.1-7) sont branchés par `ClavierIme`. Pas de
 * bouton Vocal : la dictée dans le prompt est hors périmètre de la 5.1 (décision 13).
 *
 * Même hauteur que [CorrectionBarView] (36 dp de contenu, 6 dp de marge verticale) pour que la
 * barre ne saute pas à la bascule, et même alignement sur la zone des touches en classe large.
 */
@SuppressLint("ViewConstructor")
class PromptBarView(context: Context) : LinearLayout(context) {

    private val chatToggleButton = ImageButton(context)
    private val pill = LinearLayout(context)
    private val cancelButton = TextView(context)
    private val inputView = TextView(context)
    private val sendButton = ImageButton(context)
    private val sendSpinner = ProgressBar(context)

    private var cancelListener: (() -> Unit)? = null
    private var sendListener: (() -> Unit)? = null
    private var stopListener: (() -> Unit)? = null
    private var chatToggleListener: (() -> Unit)? = null

    private var inputBlank = true
    private var inputText = ""
    private var inputCursor = 0
    private var renderedInputWidth = -1
    private var chatShown: Boolean? = null

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
            inputView.hint = context.getString(if (value) R.string.prompt_loading_model else R.string.prompt_hint)
            renderSendButton()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Fond transparent : la barre laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        val paddingH = dp(12f).toInt()
        val paddingV = dp(6f).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        // Bouton afficher/masquer le chat : caché tant qu'aucun prompt n'a été envoyé.
        chatToggleButton.setImageResource(R.drawable.ic_chat)
        chatToggleButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        chatToggleButton.setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        chatToggleButton.contentDescription = context.getString(R.string.prompt_chat_toggle_description)
        chatToggleButton.setOnClickListener { chatToggleListener?.invoke() }
        chatToggleButton.visibility = View.GONE
        addView(
            chatToggleButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        // Pilule de saisie : croix « annuler » à gauche, texte du prompt (ou invite) au centre.
        pill.orientation = HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = roundedBackground(PILL_COLOR)

        cancelButton.text = "\u2715"
        cancelButton.setTextColor(Color.WHITE)
        cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        cancelButton.gravity = Gravity.CENTER
        cancelButton.contentDescription = context.getString(R.string.prompt_cancel_description)
        cancelButton.setOnClickListener { cancelListener?.invoke() }
        pill.addView(cancelButton, LayoutParams(dp(40f).toInt(), LayoutParams.MATCH_PARENT))

        inputView.setTextColor(Color.WHITE)
        inputView.setHintTextColor(Color.parseColor(HINT_COLOR))
        inputView.hint = context.getString(R.string.prompt_hint)
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputView.gravity = Gravity.CENTER_VERTICAL
        inputView.isSingleLine = true
        // Le texte est découpé à la main autour du curseur (voir renderInput) : la fin seule est tronquée.
        inputView.ellipsize = TextUtils.TruncateAt.END
        inputView.setPadding(0, 0, dp(14f).toInt(), 0)
        pill.addView(inputView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        // La largeur n'est connue qu'après la mise en page : on redessine alors le texte autour du curseur.
        inputView.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width != renderedInputWidth) renderInput()
        }

        addView(pill, LayoutParams(0, dp(36f).toInt(), 1f).apply { marginEnd = dp(8f).toInt() })

        // Bouton rond : envoyer au repos, stop pendant la génération.
        sendButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val padding = dp(8f).toInt()
        sendButton.setPadding(padding, padding, padding, padding)
        sendButton.setOnClickListener {
            if (generating) stopListener?.invoke() else sendListener?.invoke()
        }
        val sendHolder = FrameLayout(context)
        sendHolder.addView(sendButton, FrameLayout.LayoutParams(dp(36f).toInt(), dp(36f).toInt()))
        sendSpinner.isIndeterminate = true
        sendSpinner.indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        sendSpinner.visibility = View.GONE
        sendHolder.addView(
            sendSpinner,
            FrameLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt(), Gravity.CENTER),
        )
        addView(sendHolder, LayoutParams(dp(36f).toInt(), dp(36f).toInt()))

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
        renderInput()
        renderSendButton()
    }

    private fun renderInput() {
        val width = inputView.width - inputView.paddingLeft - inputView.paddingRight
        renderedInputWidth = inputView.width
        if (inputText.isEmpty()) {
            inputView.text = "" // l'invite (hint) s'affiche
            return
        }
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
        display.setSpan(
            ForegroundColorSpan(Color.parseColor(ACCENT_COLOR)),
            before.length,
            before.length + CARET.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
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
        chatToggleButton.background = roundedBackground(if (shown) ACCENT_COLOR else BUTTON_COLOR)
    }

    /** Même alignement que [CorrectionBarView] : sur la zone centrée des touches en classe large. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val content = KeyboardWidth.forAvailableWidth(available, resources.displayMetrics.density)
        val side = dp(12f).toInt() + content.leftPx.toInt()
        if (paddingLeft != side || paddingRight != side) {
            setPadding(side, paddingTop, side, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun renderSendButton() {
        if (generating) {
            sendButton.setImageResource(R.drawable.ic_stop)
            sendButton.background = roundedBackground(STOP_COLOR)
            sendButton.contentDescription = context.getString(R.string.prompt_stop_description)
            sendButton.isEnabled = true
            sendButton.alpha = 1f
            sendSpinner.visibility = View.GONE
        } else {
            // Modèle en chargement : la roue remplace l'icône d'envoi (l'envoi reste possible, il attendra).
            if (modelLoading) sendButton.setImageDrawable(null) else sendButton.setImageResource(R.drawable.ic_send)
            sendSpinner.visibility = if (modelLoading) View.VISIBLE else View.GONE
            sendButton.background = roundedBackground(SEND_COLOR)
            sendButton.contentDescription = context.getString(R.string.prompt_send_description)
            // Un prompt vide ne s'envoie pas : le bouton est grisé.
            sendButton.isEnabled = !inputBlank
            sendButton.alpha = if (inputBlank) 0.4f else 1f
        }
    }

    private fun roundedBackground(colorHex: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(Color.parseColor(colorHex))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )

    private companion object {
        const val BUTTON_COLOR = "#3A3F47"
        const val ACCENT_COLOR = "#5A7FD4"
        const val PILL_COLOR = "#2A2D33"
        const val HINT_COLOR = "#8A9099"
        const val SEND_COLOR = "#2FA37A"
        const val STOP_COLOR = "#C0392B"
        const val CARET = "|"
        const val BEFORE_CURSOR_SHARE = 0.7f
    }
}
