package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import fr.junade.taipo.dictionary.WordSuggestion

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
 *
 * Lot 4.2 : les règles d'affichage sont dans [BarVisibility] (logique pure testée), le style commun dans
 * [BarStyle] et la zone de gauche (mots, Smart Clipboard, puce de collage, auto-remplissage) dans
 * [SuggestionZoneView] ; cette classe garde l'assemblage, les boutons d'action et les écouteurs.
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

    private val style = BarStyle(context)
    private val correctButton = Button(context)

    /**
     * Zone de gauche : bande de mots, bouton Smart Clipboard, puce de collage ou suggestions
     * d'auto-remplissage selon [zoneState] (barre « avant saisie » ou saisie en cours).
     */
    private val zoneView = SuggestionZoneView(context, style)

    /** Roue crantée : ouvre la page d'accueil de l'application, visible hors saisie seulement. */
    private val settingsButton = ImageButton(context)
    private var settingsListener: (() -> Unit)? = null

    /** Story 2.1 : bouton « menu », visible pendant la saisie, qui donne accès au bouton Smart Clipboard. */
    private val menuButton = ImageButton(context)

    /** Menu de droite : range les boutons Vocal et Corriger pendant les suggestions de mots. */
    private val actionsMenuButton = ImageButton(context)

    /** Croix de fermeture du panneau Smart Clipboard (à gauche de la barre) : visible seulement tant qu'il est ouvert. */
    private val closeButton = ImageButton(context)
    private var clipboardPanelOpen = false
    private var clipboardCloseListener: (() -> Unit)? = null
    private var actionsMenuBackgroundExpanded: Boolean? = null

    // Lot 21 : fonds des boutons à bascule et du bouton vocal créés une fois (un Drawable par état), jamais recréés.
    private val menuBackgrounds = style.toggleBackgrounds()
    private val actionsMenuBackgrounds = style.toggleBackgrounds()
    private val voiceBackgrounds = HashMap<Int, RoundKeyDrawable>()
    private val zoneState = SuggestionZoneState()
    private var menuBackgroundExpanded: Boolean? = null
    val voiceButton = ImageButton(context)
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
        val barHeight = dimen(R.dimen.taipo_bar_height).toInt()
        val iconButtonWidth = dimen(R.dimen.taipo_bar_icon_button_width).toInt()
        val gap = dimen(R.dimen.taipo_bar_gap).toInt()
        val paddingH = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        val paddingV = dimen(R.dimen.taipo_bar_padding_vertical).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        // Croix de fermeture du panneau Smart Clipboard, à l'extrémité gauche de la barre.
        style.styleBarIconButton(closeButton, R.drawable.ic_close)
        closeButton.contentDescription = context.getString(R.string.clipboard_panel_close_description)
        closeButton.setOnClickListener { clipboardCloseListener?.invoke() }
        closeButton.visibility = View.GONE
        addView(
            closeButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        style.styleBarIconButton(menuButton, R.drawable.ic_more)
        menuButton.contentDescription = context.getString(R.string.clipboard_menu_description)
        menuButton.setOnClickListener {
            zoneState.toggleMenu()
            renderZone()
        }
        addView(
            menuButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Bouton « roue crantée » entre le menu « ··· » et le bouton Smart Clipboard.
        style.styleRoundIconButton(settingsButton, R.drawable.ic_settings)
        settingsButton.contentDescription = context.getString(R.string.settings_button_description)
        settingsButton.setOnClickListener { settingsListener?.invoke() }
        addView(
            settingsButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        addView(zoneView, LayoutParams(0, barHeight, 1f).apply { marginEnd = gap })

        // Story 5.1 : « Générer », à gauche de Vocal. Icône seule (40 dp) pour ménager la place de la zone de gauche.
        style.styleRoundIconButton(generateButton, R.drawable.ic_generate)
        generateButton.contentDescription = context.getString(R.string.generate_button_description)
        generateButton.setOnClickListener {
            collapseMenu()
            generateListener?.invoke()
        }
        addView(
            generateButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Lot 08 : Vocal devient un bouton rond à icône micro ; son état (écoute, chargement…) passe par la couleur,
        // la transparence et la description d'accessibilité, plus par le texte.
        style.styleRoundIconButton(voiceButton, R.drawable.ic_mic)
        // Le geste réel passe par setOnTouchListener (côté appelant, qui consomme tout toucher) ; le clic, lui, n'est déclenché
        // que par l'accessibilité (TalkBack, double appui) : l'appelant le relie à la bascule d'écoute (lot 20).
        voiceButton.setOnClickListener { }
        addView(
            voiceButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        style.styleButton(correctButton, R.color.accent)
        correctButton.setOnClickListener {
            if (state == CorrectionBarState.IDLE) {
                collapseMenu()
                listener?.onCorrectClicked()
            }
        }
        addView(correctButton, LayoutParams(LayoutParams.WRAP_CONTENT, barHeight))

        // Menu de droite : pendant les suggestions de mots, il range derrière lui Vocal et Corriger.
        style.styleBarIconButton(actionsMenuButton, R.drawable.ic_more)
        actionsMenuButton.contentDescription = context.getString(R.string.actions_menu_description)
        actionsMenuButton.setOnClickListener {
            zoneState.toggleActionsMenu()
            renderZone()
        }
        addView(
            actionsMenuButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginStart = gap },
        )

        // Lot 20 : zone tactile des boutons étendue à 48 dp (36 dp dessinés), sans changer le rendu.
        expandChildTouchTargets()

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
        val side = dimen(R.dimen.taipo_bar_padding_horizontal).toInt() + content.leftPx.toInt()
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
        zoneView.setInlineViews(views)
        zoneState.setInlineAvailable(views.isNotEmpty())
        renderZone()
    }

    /** Story 1.16 : propose [emoji] dans le 4e emplacement de la bande de suggestions (null = aucun). */
    fun setEmojiSuggestion(emoji: String?) {
        zoneView.setEmojiSuggestion(emoji)
    }

    fun setOnEmojiSuggestionClickListener(listener: SuggestionStripView.OnEmojiClickListener) {
        zoneView.setOnEmojiSuggestionClickListener(listener)
    }

    /** Story 1.17 : remplit les 3 emplacements de mots (liste vide ou null = emplacement vide). */
    fun setWordSuggestions(words: List<WordSuggestion?>) {
        zoneView.setWordSuggestions(words)
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        zoneView.setOnWordSuggestionClickListener(listener)
    }

    fun setOnCorrectListener(listener: OnCorrectListener) {
        this.listener = listener
    }

    fun setOnClipboardClickListener(listener: OnClipboardClickListener) {
        zoneView.setOnClipboardClickListener(listener)
    }

    fun setOnPasteClickListener(listener: OnPasteClickListener) {
        zoneView.setOnPasteClickListener(listener)
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
        } else {
            zoneState.setPasteAvailable(true)
        }
        zoneView.setPasteText(preview, sensitive)
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

    private fun renderActionButtons() {
        val visible = BarVisibility.actionButtons(voiceState, state, zoneState, clipboardPanelOpen)
        voiceButton.visibility = if (visible.voice) View.VISIBLE else View.GONE
        generateButton.visibility = if (visible.generate) View.VISIBLE else View.GONE
        correctButton.visibility = if (visible.correct) View.VISIBLE else View.GONE
        actionsMenuButton.visibility = if (visible.actionsMenu) View.VISIBLE else View.GONE
        closeButton.visibility = View.GONE // remplacée par le bouton Smart Clipboard actif, qui referme le panneau
    }

    private fun renderZone() {
        renderActionButtons()
        val visible = BarVisibility.zoneContent(zoneState, clipboardPanelOpen)
        zoneView.render(visible, clipboardPanelOpen)
        menuButton.visibility = if (visible.menuButton) View.VISIBLE else View.GONE
        settingsButton.visibility = if (visible.settingsButton) View.VISIBLE else View.GONE
        if (menuBackgroundExpanded != zoneState.menuExpanded) {
            menuBackgroundExpanded = zoneState.menuExpanded
            menuButton.background = menuBackgrounds.get(zoneState.menuExpanded)
        }
        if (actionsMenuBackgroundExpanded != zoneState.actionsExpanded) {
            actionsMenuBackgroundExpanded = zoneState.actionsExpanded
            actionsMenuButton.background = actionsMenuBackgrounds.get(zoneState.actionsExpanded)
        }
    }

    private fun renderCorrect() {
        BarVisibility.correctAppearance(state)?.let { appearance ->
            correctButton.isEnabled = appearance.enabled
            correctButton.alpha = appearance.alpha
            correctButton.text = context.getString(appearance.label)
        }
        renderActionButtons()
    }

    private fun renderVoice() {
        val appearance = BarVisibility.voiceAppearance(voiceState)
        voiceButton.contentDescription = context.getString(appearance.label)
        voiceButton.background = voiceBackgrounds.getOrPut(appearance.background) { style.roundBackground(appearance.background) }
        voiceButton.alpha = appearance.alpha
        renderActionButtons()
    }
}
