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
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.dictionary.WordSuggestion

/** États du bouton Corriger. */
enum class CorrectionBarState { HIDDEN, IDLE, LOADING, CORRECTING }

/** États du bouton Vocal. */
enum class VoiceBarState {
    IDLE,

    /** Appui reçu, modèle en cours de chargement : le micro n'écoute pas encore. */
    LOADING,

    /** Le micro capte réellement. */
    RECORDING,
    TRANSCRIBING,
}

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
    private val clipboardButton = ImageButton(context)

    /** Roue crantée : ouvre la page d'accueil de l'application, visible hors saisie seulement. */
    private val settingsButton = ImageButton(context)
    private var settingsListener: (() -> Unit)? = null

    /**
     * Story 2.2 : puce de collage (aperçu du texte copié récemment), dans la même zone : elle prend
     * la place de la bande de mots et du bouton Smart Clipboard tant qu'un collage est proposé.
     */
    private val pasteChip = TextView(context)

    /**
     * Suggestions d'auto-remplissage en ligne (gestionnaire de mots de passe) : des vues fournies par
     * le service d'auto-remplissage, rangées en ligne et défilables, à la place de la bande de mots.
     */
    private val inlineRow = LinearLayout(context)
    private val inlineScroll = HorizontalScrollView(context)
    private var pasteListener: OnPasteClickListener? = null

    /** Story 2.1 : bouton « menu », visible pendant la saisie, qui donne accès au bouton Smart Clipboard. */
    private val menuButton = Button(context)

    /** Menu de droite : range les boutons Vocal et Corriger pendant les suggestions de mots. */
    private val actionsMenuButton = Button(context)

    /** Croix de fermeture du panneau Smart Clipboard (à gauche de la barre) : visible seulement tant qu'il est ouvert. */
    private val closeButton = Button(context)
    private var clipboardPanelOpen = false
    private var clipboardButtonActiveBackground: Boolean? = null
    private var clipboardCloseListener: (() -> Unit)? = null
    private var actionsMenuBackgroundExpanded: Boolean? = null
    private val zoneState = SuggestionZoneState()
    private var menuBackgroundExpanded: Boolean? = null
    private var clipboardListener: OnClipboardClickListener? = null
    val voiceButton = Button(context)
    private var listener: OnCorrectListener? = null

    /** Story 5.1 : bouton « Générer » (icône), qui bascule la barre en mode prompt ; rangé avec Vocal et Corriger. */
    private val generateButton = ImageButton(context)
    private var generateListener: (() -> Unit)? = null

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
        // Fond transparent : la barre laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        val paddingH = dp(12f).toInt()
        val paddingV = dp(6f).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        // Croix de fermeture du panneau Smart Clipboard, à l'extrémité gauche de la barre.
        styleButton(closeButton, "#3A3F47")
        compact(closeButton, paddingDp = 0f)
        closeButton.text = "\u2715"
        closeButton.contentDescription = context.getString(R.string.clipboard_panel_close_description)
        closeButton.setOnClickListener { clipboardCloseListener?.invoke() }
        closeButton.visibility = View.GONE
        addView(
            closeButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

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

        // Bouton « roue crantée » entre le menu « ··· » et le bouton Smart Clipboard.
        styleIconButton(settingsButton, R.drawable.ic_settings)
        settingsButton.contentDescription = context.getString(R.string.settings_button_description)
        settingsButton.setOnClickListener { settingsListener?.invoke() }
        addView(
            settingsButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        // Bouton Smart Clipboard : icône « coller » à la place du texte.
        styleIconButton(clipboardButton, R.drawable.ic_paste)
        clipboardButton.contentDescription = context.getString(R.string.clipboard_button)
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

        inlineRow.orientation = HORIZONTAL
        inlineRow.gravity = Gravity.CENTER_VERTICAL
        inlineScroll.isHorizontalScrollBarEnabled = false
        inlineScroll.overScrollMode = View.OVER_SCROLL_NEVER
        inlineScroll.addView(
            inlineRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        inlineScroll.visibility = View.GONE

        val zone = FrameLayout(context)
        zone.addView(suggestionStrip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        zone.addView(
            clipboardButton,
            FrameLayout.LayoutParams(dp(48f).toInt(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        zone.addView(
            pasteChip,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START),
        )
        zone.addView(
            inlineScroll,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        addView(zone, LayoutParams(0, dp(36f).toInt(), 1f).apply { marginEnd = dp(8f).toInt() })

        // Story 5.1 : « Générer », à gauche de Vocal. Icône seule (40 dp) pour ménager la place de la zone de gauche.
        styleIconButton(generateButton, R.drawable.ic_generate)
        generateButton.contentDescription = context.getString(R.string.generate_button_description)
        generateButton.setOnClickListener {
            collapseMenu()
            generateListener?.invoke()
        }
        addView(
            generateButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        styleButton(voiceButton, "#3A3F47")
        voiceButton.setOnClickListener { /* geste réel géré via setOnTouchListener côté appelant */ }
        addView(
            voiceButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        styleButton(correctButton, "#5A7FD4")
        correctButton.setOnClickListener {
            if (state == CorrectionBarState.IDLE) {
                collapseMenu()
                listener?.onCorrectClicked()
            }
        }
        addView(correctButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()))

        // Menu de droite : pendant les suggestions de mots, il range derrière lui Vocal et Corriger.
        styleButton(actionsMenuButton, "#3A3F47")
        compact(actionsMenuButton, paddingDp = 0f)
        actionsMenuButton.text = "\u00B7\u00B7\u00B7"
        actionsMenuButton.contentDescription = context.getString(R.string.actions_menu_description)
        actionsMenuButton.setOnClickListener {
            zoneState.toggleActionsMenu()
            renderZone()
        }
        addView(
            actionsMenuButton,
            LayoutParams(dp(40f).toInt(), dp(36f).toInt()).apply { marginStart = dp(8f).toInt() },
        )

        renderCorrect()
        renderVoice()
        renderZone()
    }

    /** Touche sur la roue crantée : ouvrir la page d'accueil de l'application. */
    fun setOnSettingsClickListener(listener: () -> Unit) {
        settingsListener = listener
    }

    /** Touche sur « Générer » : entrer en mode prompt (story 5.1). */
    fun setOnGenerateClickListener(listener: () -> Unit) {
        generateListener = listener
    }

    /** Touche sur la croix : le panneau Smart Clipboard doit se fermer. */
    fun setOnClipboardCloseClickListener(listener: () -> Unit) {
        clipboardCloseListener = listener
    }

    /**
     * Le panneau Smart Clipboard est ouvert (ou refermé) : le bouton Smart Clipboard reste affiché,
     * coloré (actif), seul dans la zone de gauche, et un nouvel appui dessus referme le panneau. Les
     * menus « ··· » (celui de gauche n'a plus d'objet) ainsi que la roue crantée se cachent.
     */
    fun setClipboardPanelOpen(open: Boolean) {
        if (clipboardPanelOpen == open) return
        clipboardPanelOpen = open
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

    /**
     * Affiche les [views] d'auto-remplissage en ligne à la place des suggestions de mots ; une liste
     * vide retire les suggestions et rend la zone aux suggestions habituelles.
     */
    fun setInlineSuggestions(views: List<View>) {
        inlineRow.removeAllViews()
        views.forEachIndexed { index, view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            inlineRow.addView(
                view,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply {
                    if (index > 0) marginStart = dp(6f).toInt()
                },
            )
        }
        inlineScroll.scrollTo(0, 0)
        zoneState.setInlineAvailable(views.isNotEmpty())
        renderZone()
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

    /**
     * Vocal et Corriger sont rangés derrière le menu de droite tant que la bande de mots est
     * affichée, sauf pendant une écoute, une transcription ou une correction : leur bouton sert alors
     * d'arrêt ou d'indicateur d'avancement et doit rester visible (même si du texte vient d'être inséré).
     */
    private fun renderActionButtons() {
        val busy = voiceState != VoiceBarState.IDLE ||
            state == CorrectionBarState.LOADING || state == CorrectionBarState.CORRECTING
        val actionsVisible = zoneState.actionButtonsVisible(busy)
        voiceButton.visibility = if (actionsVisible) View.VISIBLE else View.GONE
        // « Générer » se range avec Vocal et Corriger, mais disparaît pendant une écoute, une
        // transcription ou une correction (le moteur est partagé : pas de génération en parallèle).
        generateButton.visibility = if (actionsVisible && !busy) View.VISIBLE else View.GONE
        correctButton.visibility =
            if (actionsVisible && state != CorrectionBarState.HIDDEN) View.VISIBLE else View.GONE
        actionsMenuButton.visibility =
            if (!clipboardPanelOpen && zoneState.actionsMenuButtonVisible(busy)) View.VISIBLE else View.GONE
        closeButton.visibility = View.GONE // remplacée par le bouton Smart Clipboard actif, qui referme le panneau
    }

    private fun renderZone() {
        renderActionButtons()
        // Panneau ouvert : la zone ne montre que le bouton Smart Clipboard (actif), quel que soit l'état de saisie.
        val zone = if (clipboardPanelOpen) SuggestionZoneState.Zone.CLIPBOARD else zoneState.zone
        suggestionStrip.visibility = if (zone == SuggestionZoneState.Zone.WORDS) View.VISIBLE else View.GONE
        clipboardButton.visibility = if (zone == SuggestionZoneState.Zone.CLIPBOARD) View.VISIBLE else View.GONE
        pasteChip.visibility = if (zone == SuggestionZoneState.Zone.PASTE) View.VISIBLE else View.GONE
        inlineScroll.visibility = if (zone == SuggestionZoneState.Zone.INLINE) View.VISIBLE else View.GONE
        if (clipboardButtonActiveBackground != clipboardPanelOpen) {
            clipboardButtonActiveBackground = clipboardPanelOpen
            clipboardButton.background = roundedBackground(if (clipboardPanelOpen) "#5A7FD4" else "#3A3F47")
        }
        menuButton.visibility =
            if (zoneState.menuButtonVisible && !clipboardPanelOpen) View.VISIBLE else View.GONE
        settingsButton.visibility =
            if (zoneState.settingsButtonVisible && !clipboardPanelOpen) View.VISIBLE else View.GONE
        if (menuBackgroundExpanded != zoneState.menuExpanded) {
            menuBackgroundExpanded = zoneState.menuExpanded
            menuButton.background = roundedBackground(if (zoneState.menuExpanded) "#5A7FD4" else "#3A3F47")
        }
        if (actionsMenuBackgroundExpanded != zoneState.actionsExpanded) {
            actionsMenuBackgroundExpanded = zoneState.actionsExpanded
            actionsMenuButton.background =
                roundedBackground(if (zoneState.actionsExpanded) "#5A7FD4" else "#3A3F47")
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

    /** Bouton à icône blanche centrée, sur le même fond arrondi que les autres boutons de la barre. */
    private fun styleIconButton(button: ImageButton, iconRes: Int) {
        button.setImageResource(iconRes)
        button.scaleType = ImageView.ScaleType.CENTER_INSIDE
        button.background = roundedBackground("#3A3F47")
        val padding = dp(8f).toInt()
        button.setPadding(padding, padding, padding, padding)
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
            CorrectionBarState.HIDDEN -> Unit

            CorrectionBarState.IDLE -> {
                correctButton.isEnabled = true
                correctButton.alpha = 1f
                correctButton.text = context.getString(R.string.correction_button_idle)
            }

            CorrectionBarState.LOADING -> {
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_loading)
            }

            CorrectionBarState.CORRECTING -> {
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_correcting)
            }
        }
        renderActionButtons()
    }

    private fun renderVoice() {
        when (voiceState) {
            VoiceBarState.IDLE -> {
                voiceButton.text = context.getString(R.string.voice_button_idle)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 1f
            }

            VoiceBarState.LOADING -> {
                voiceButton.text = context.getString(R.string.voice_button_loading)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 0.6f
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
        renderActionButtons()
    }

    private fun roundedBackground(colorHex: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(Color.parseColor(colorHex))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )
}
