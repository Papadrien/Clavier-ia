package fr.junade.taipo

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.junade.taipo.ai.PromptMessage
import fr.junade.taipo.ai.PromptMessageStatus

/**
 * Story 5.1, phase 5.1-2 : zone de chat du mode prompt, au-dessus de la barre du haut du clavier
 * (décisions 3, 4 et 6). Le prompt de l'utilisateur est une bulle à droite, la réponse du modèle une
 * bulle à gauche, en dessous.
 *
 * La hauteur est plafonnée à la moitié de celle de [heightReference] (le `KeyboardStackLayout`, donc
 * la hauteur du clavier, réglage et orientation compris) ; au-delà, le contenu défile à l'intérieur.
 * La vue se mesure à partir de la hauteur « naturelle » du clavier, car elle est mesurée avant lui
 * dans le `LinearLayout` racine.
 *
 * Phase 5.1-6 : la zone reflète la conversation du mode prompt. [showMessages] reconstruit toutes les
 * bulles depuis `PromptConversation` (envoi d'un prompt, échange retiré, fermeture du clavier) ;
 * [updateLastResponse] met à jour la seule dernière bulle de réponse au fil du flux. Le défilement
 * suit le bas de la conversation tant que l'utilisateur ne l'a pas remontée à la main.
 */
class ChatZoneView(context: Context) : ScrollView(context) {

    /** Vue dont la hauteur sert de référence au plafond (la moitié de sa hauteur). */
    var heightReference: View? = null

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** Dernière bulle de réponse du modèle, celle que le flux complète ; null si la zone est vide. */
    private var lastResponseBubble: TextView? = null

    /** Story 5.3 : bouton « Ajouter le texte » de la dernière réponse, affiché une fois la réponse terminée ou interrompue. */
    private var lastAddTextButton: TextView? = null

    /** Appui sur « Ajouter le texte » : reçoit le rang de l'échange dans la conversation. */
    private var addTextListener: ((Int) -> Unit)? = null

    /** Vrai tant que la vue est calée en bas : le contenu qui grandit la fait alors défiler. */
    private var followLatest = true

    init {
        // Fond transparent : la zone laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // Le contenu grandit avec le flux : une fois la mise en page faite, on reste calé en bas.
        column.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (followLatest && bottom - top != oldBottom - oldTop) {
                scrollTo(0, maxOf(0, bottom - top - height))
            }
        }
    }

    /** Ajoute le prompt de l'utilisateur (bulle à droite). */
    fun addPromptBubble(text: String): TextView = addBubble(text, fromUser = true)

    /** Ajoute une réponse du modèle (bulle à gauche) ; le texte renvoyé pourra être complété au fil du flux. */
    fun addResponseBubble(text: String): TextView = addBubble(text, fromUser = false)

    /** Story 5.3 : appui sur le bouton « Ajouter le texte » placé sous une bulle de réponse (rang de l'échange). */
    fun setOnAddTextClickListener(listener: (Int) -> Unit) {
        addTextListener = listener
    }

    fun clearBubbles() {
        column.removeAllViews()
        lastResponseBubble = null
        lastAddTextButton = null
    }

    /**
     * Reconstruit toute la zone depuis [messages] (du plus ancien au plus récent) : le prompt à droite,
     * la réponse en dessous à gauche. La dernière bulle de réponse devient celle que le flux complète.
     */
    fun showMessages(messages: List<PromptMessage>) {
        clearBubbles()
        messages.forEachIndexed { index, message ->
            addPromptBubble(message.prompt)
            lastResponseBubble = addResponseBubble(displayText(message))
            lastAddTextButton = addAddTextButton(index, canAddText(message))
        }
        scrollToLatest()
    }

    /** Met à jour la dernière bulle de réponse (morceau reçu, fin de génération). Sans effet si la zone est vide. */
    fun updateLastResponse(message: PromptMessage) {
        setLastResponseText(displayText(message))
        lastAddTextButton?.visibility = if (canAddText(message)) View.VISIBLE else View.GONE
    }

    /** « Ajouter le texte » n'existe que pour une réponse non vide dont la génération est finie ou interrompue (décision 7). */
    private fun canAddText(message: PromptMessage): Boolean =
        message.status != PromptMessageStatus.IN_PROGRESS && message.response.isNotBlank()

    /** Remplace le texte de la dernière bulle de réponse (par exemple par l'état « chargement du modèle »). */
    fun setLastResponseText(text: String) {
        val bubble = lastResponseBubble ?: return
        if (bubble.text.toString() == text) return
        bubble.text = text
    }

    /** Fait défiler jusqu'à la dernière bulle et reprend le suivi du bas (prompt envoyé, zone reconstruite). */
    fun scrollToLatest() {
        followLatest = true
        post { scrollTo(0, maxOf(0, column.height - height)) }
    }

    /** L'utilisateur remonte la conversation : on ne le ramène plus en bas tant qu'il n'y est pas revenu. */
    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        followLatest = t + height >= column.height - dp(12)
    }

    /** Texte d'une bulle de réponse : le texte reçu ; sinon « … » pendant la génération, un message à la fin. */
    private fun displayText(message: PromptMessage): String = when {
        message.response.isNotEmpty() -> message.response
        message.status == PromptMessageStatus.IN_PROGRESS -> context.getString(R.string.prompt_response_pending)
        message.status == PromptMessageStatus.INTERRUPTED -> context.getString(R.string.prompt_response_interrupted)
        else -> context.getString(R.string.prompt_response_empty)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val reference = heightReference
        var cap = Int.MAX_VALUE
        if (reference != null) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            // Le KeyboardStackLayout ignore la hauteur proposée : il rend celle du clavier.
            reference.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            cap = reference.measuredHeight / 2
        }
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            cap = minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
        }
        val spec = if (cap == Int.MAX_VALUE) {
            heightMeasureSpec
        } else {
            MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, spec)
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        val bubble = TextView(context).apply {
            this.text = text
            setTextColor(context.themeColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            // Largeur plafonnée pour que la bulle ne touche pas le bord opposé.
            maxWidth = (resources.displayMetrics.widthPixels * MAX_WIDTH_RATIO).toInt()
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(context.themeColor(if (fromUser) USER_COLOR else MODEL_COLOR))
            }
            setTextIsSelectable(false)
        }
        column.addView(
            bubble,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (fromUser) Gravity.END else Gravity.START
                topMargin = dp(if (column.childCount == 0) 0 else 6)
            },
        )
        return bubble
    }

    /** Bouton sous une bulle de réponse (à gauche) ; [index] est le rang de l'échange. Caché tant qu'il n'y a rien à ajouter. */
    private fun addAddTextButton(index: Int, visible: Boolean): TextView {
        val button = TextView(context).apply {
            text = context.getString(R.string.prompt_add_text)
            setTextColor(context.themeColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minHeight = dp(36)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(context.themeColor(BUTTON_COLOR))
                setStroke(dp(1), context.themeColor(USER_COLOR))
            }
            visibility = if (visible) View.VISIBLE else View.GONE
            setOnClickListener { addTextListener?.invoke(index) }
        }
        column.addView(
            button,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.START
                topMargin = dp(4)
            },
        )
        return button
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val TEXT_SIZE_SP = 15f
        const val MAX_WIDTH_RATIO = 0.8f
        val USER_COLOR = R.color.accent
        val MODEL_COLOR = R.color.surface_button
        val BUTTON_COLOR = R.color.surface_pill
    }
}
