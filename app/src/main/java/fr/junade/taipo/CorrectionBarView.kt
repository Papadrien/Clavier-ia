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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.dictionary.WordSuggestion

/** États du bouton Corriger. */
enum class CorrectionBarState { HIDDEN, IDLE, LOADING, CORRECTING }

/** États du bouton Vocal. */
enum class VoiceBarState { IDLE, RECORDING, TRANSCRIBING }

/**
 * Barre au-dessus du clavier contenant les boutons Corriger et Vocal.
 *
 * Décision 3.3 : le bouton Corriger n'est visible que si du texte est
 * présent dans le champ. Décision 7.1/7.2 : le bouton Vocal, lui, est
 * toujours visible. Décision 8.7 : un état "chargement" distinct est affiché
 * si le modèle de correction doit être rechargé.
 *
 * Simplification assumée pour ce prototype : l'animation "labyrinthe" prévue
 * dans les décisions de design n'est pas implémentée ici (l'asset de motif
 * vectoriel de référence n'est pas disponible dans cet environnement) ; les
 * états "en cours" sont indiqués simplement par un texte de bouton différent
 * et un bouton désactivé (ou une couleur différente pour l'écoute vocale).
 */
@SuppressLint("ViewConstructor")
class CorrectionBarView(context: Context) : LinearLayout(context) {

    fun interface OnCorrectListener {
        fun onCorrectClicked()
    }

    /** Story 2.1 : touche sur le bouton Smart Clipboard. */
    fun interface OnClipboardClickListener {
        fun onClipboardClick()
    }

    /** Story 2.2 : touche sur la puce de collage. */
    fun interface OnPasteClickListener {
        fun onPasteClick()
    }

    private val correctButton = Button(context)

    /** Stories 1.16/1.17 : bande de suggestions (3 mots + 1 emoji), à gauche des boutons d'action. */
    private val suggestionStrip = SuggestionStripView(context)

    /**
     * Story 2.1 : bouton Smart Clipboard, dans la même zone que la bande de suggestions : la zone
     * montre l'un ou l'autre selon [zoneState] (barre « avant saisie » ou saisie en cours).
     */
    private val clipboardButton = Button(context)

    /**
     * Story 2.2 : puce de collage (aperçu du texte copié récemment), dans la même zone : elle prend
     * la place de la bande de mots et du bouton Smart Clipboard tant qu'un collage est proposé.
     */
    private val pasteChip = TextView(context)
    private var pasteListener: OnPasteClickListener? = null

    /** Story 2.1 : bouton « menu », visible pendant la saisie, qui donne accès au bouton Smart Clipboard. */
    private val menuButton = Button(context)
    private val zoneState = SuggestionZoneState()
    private var menuBackgroundExpanded: Boolean? = null
    private var clipboardListener: OnClipboardClickListener? = null
    val voiceButton = Button(context)
    private var listener: OnCorrectListener? = null

    var state: CorrectionBarState = CorrectionBarState.HIDDEN
        set(value) {
            field = value
            renderCorrect()
        }

    var voiceState: VoiceBarState = VoiceBarState.IDLE
        set(value) {
            field = value
            renderVoice()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#17181B"))
        val paddingH = dp(12f).toInt()
        val paddingV = dp(6f).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        styleButton(menuButton, "#3A3F47")
        compact(menuButton, paddingDp = 0f)
        menuButton.text = "\u00B7\u00B7\u00B7"
        menuButton.contentDescription = context.getString(R.string.clipboard_menu_description)
        menuButton.setOnClickListener {
            zoneState.toggleMenu()
            renderZone()
        }
        addView(
            menuButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        styleButton(clipboardButton, "#3A3F47")
        compact(clipboardButton, paddingDp = 12f)
        clipboardButton.text = context.getString(R.string.clipboard_button)
        clipboardButton.maxLines = 1
        clipboardButton.ellipsize = TextUtils.TruncateAt.END
        clipboardButton.setOnClickListener { clipboardListener?.onClipboardClick() }

        pasteChip.setTextColor(Color.WHITE)
        pasteChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        pasteChip.gravity = Gravity.CENTER_VERTICAL
        pasteChip.maxLines = 1
        pasteChip.ellipsize = TextUtils.TruncateAt.END
        pasteChip.setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        pasteChip.background = roundedBackground("#3A3F47")
        pasteChip.setOnClickListener { pasteListener?.onPasteClick() }
        pasteChip.visibility = View.GONE

        val zone = FrameLayout(context)
        zone.addView(suggestionStrip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        zone.addView(
            clipboardButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        zone.addView(
            pasteChip,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        addView(zone, LayoutParams(0, dp(36f).toInt(), 1f).apply { marginEnd = dp(8f).toInt() })

        styleButton(voiceButton, "#3A3F47")
        voiceButton.setOnClickListener { /* geste réel géré via setOnTouchListener côté appelant */ }
        addView(
            voiceButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        styleButton(correctButton, "#5A7FD4")
        correctButton.setOnClickListener {
            if (state == CorrectionBarState.IDLE) {
                listener?.onCorrectClicked()
            }
        }
        addView(correctButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()))

        renderCorrect()
        renderVoice()
        renderZone()
    }

    /**
     * Story 1.14 : en classe de largeur large (≥ 600 dp), les boutons restent alignés sur la zone
     * centrée des touches (le fond de la barre garde toute la largeur).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val content = KeyboardWidth.forAvailableWidth(available, resources.displayMetrics.density)
        val side = dp(12f).toInt() + content.leftPx.toInt()
        if (paddingLeft != side || paddingRight != side) {
            setPadding(side, paddingTop, side, paddingBottom)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** Story 1.16 : propose [emoji] dans le 4e emplacement de la bande de suggestions (null = aucun). */
    fun setEmojiSuggestion(emoji: String?) {
        suggestionStrip.setEmoji(emoji)
    }

    fun setOnEmojiSuggestionClickListener(listener: SuggestionStripView.OnEmojiClickListener) {
        suggestionStrip.setOnEmojiClickListener(listener)
    }

    /** Story 1.17 : remplit les 3 emplacements de mots (liste vide ou null = emplacement vide). */
    fun setWordSuggestions(words: List<WordSuggestion?>) {
        suggestionStrip.setWords(words)
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        suggestionStrip.setOnWordClickListener(listener)
    }

    fun setOnCorrectListener(listener: OnCorrectListener) {
        this.listener = listener
    }

    fun setOnClipboardClickListener(listener: OnClipboardClickListener) {
        clipboardListener = listener
    }

    fun setOnPasteClickListener(listener: OnPasteClickListener) {
        pasteListener = listener
    }

    /**
     * Story 2.2 : propose le collage avec l'aperçu [preview] (déjà mis en forme, éventuellement
     * masqué), ou retire la puce si [preview] est null. [sensitive] : l'aperçu est masqué, la
     * description d'accessibilité ne révèle donc pas non plus le texte.
     */
    fun setPasteSuggestion(preview: String?, sensitive: Boolean) {
        if (preview == null) {
            if (!zoneState.pasteAvailable) return
            zoneState.setPasteAvailable(false)
            pasteChip.text = ""
        } else {
            pasteChip.text = preview
            pasteChip.contentDescription = if (sensitive) {
                context.getString(R.string.clipboard_paste_description_masked)
            } else {
                context.getString(R.string.clipboard_paste_description, preview)
            }
            zoneState.setPasteAvailable(true)
        }
        renderZone()
    }

    /**
     * Story 2.1 : le champ contient du texte (barre de saisie : suggestions de mots, bouton
     * « menu ») ou est vide (barre « avant saisie » : bouton Smart Clipboard).
     */
    fun setFieldHasText(hasText: Boolean) {
        zoneState.onFieldTextChanged(hasText)
        renderZone()
    }

    /** Story 2.1/2.4 : début de la frappe, retour aux suggestions de mots (le menu se referme). */
    fun collapseMenu() {
        zoneState.onTyping()
        renderZone()
    }

    private fun renderZone() {
        val words = zoneState.zone == SuggestionZoneState.Zone.WORDS
        suggestionStrip.visibility = if (words) View.VISIBLE else View.GONE
        clipboardButton.visibility = if (zoneState.zone == SuggestionZoneState.Zone.CLIPBOARD) View.VISIBLE else View.GONE
        pasteChip.visibility = if (zoneState.zone == SuggestionZoneState.Zone.PASTE) View.VISIBLE else View.GONE
        menuButton.visibility = if (zoneState.menuButtonVisible) View.VISIBLE else View.GONE
        if (menuBackgroundExpanded != zoneState.menuExpanded) {
            menuBackgroundExpanded = zoneState.menuExpanded
            menuButton.background = roundedBackground(if (zoneState.menuExpanded) "#5A7FD4" else "#3A3F47")
        }
    }

    /** Boutons compacts : sans la largeur et la hauteur minimales par défaut des boutons Material. */
    private fun compact(button: Button, paddingDp: Float) {
        button.minWidth = 0
        button.minimumWidth = 0
        button.minHeight = 0
        button.minimumHeight = 0
        button.setPadding(dp(paddingDp).toInt(), 0, dp(paddingDp).toInt(), 0)
    }

    private fun styleButton(button: Button, backgroundColor: String) {
        button.setTextColor(Color.WHITE)
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.isAllCaps = false
        button.background = roundedBackground(backgroundColor)
        button.setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
    }

    private fun renderCorrect() {
        when (state) {
            CorrectionBarState.HIDDEN -> {
                correctButton.visibility = View.GONE
            }

            CorrectionBarState.IDLE -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = true
                correctButton.alpha = 1f
                correctButton.text = context.getString(R.string.correction_button_idle)
            }

            CorrectionBarState.LOADING -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_loading)
            }

            CorrectionBarState.CORRECTING -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_correcting)
            }
        }
    }

    private fun renderVoice() {
        when (voiceState) {
            VoiceBarState.IDLE -> {
                voiceButton.text = context.getString(R.string.voice_button_idle)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 1f
            }

            VoiceBarState.RECORDING -> {
                voiceButton.text = context.getString(R.string.voice_button_recording)
                voiceButton.background = roundedBackground("#C0392B")
                voiceButton.alpha = 1f
            }

            VoiceBarState.TRANSCRIBING -> {
                voiceButton.text = context.getString(R.string.voice_button_transcribing)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 0.6f
            }
        }
    }

    private fun roundedBackground(colorHex: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(Color.parseColor(colorHex))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )
}
