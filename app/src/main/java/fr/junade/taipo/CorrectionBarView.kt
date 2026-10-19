package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
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
    private val correctButton = LabeledIconButton(context, style)

    /**
     * Zone de gauche : bande de mots, bouton Smart Clipboard, puce de collage ou suggestions
     * d'auto-remplissage selon [zoneState] (barre « avant saisie » ou saisie en cours).
     */
    private val zoneView = SuggestionZoneView(context, style)

    /** Roue crantée : ouvre la page d'accueil de l'application, visible hors saisie, ou quand le menu « ··· » de gauche est ouvert. */
    private val settingsButton = ImageButton(context)

    /** Roue crantée qui tourne en ressort quand on touche le bouton, le temps que l'application s'ouvre. */
    private val settingsIcon = SpinIconDrawable(context, R.drawable.ic_settings, context.dimen(R.dimen.taipo_icon_size).toInt())
    private var settingsListener: (() -> Unit)? = null
    private var menuClickListener: (() -> Unit)? = null
    private var actionsMenuClickListener: (() -> Unit)? = null

    /** Story 2.1 : bouton « menu », visible pendant la saisie, qui donne accès au bouton Smart Clipboard. */
    private val menuButton = ImageButton(context)
    private var menuChevron: RotatingDrawable? = null

    /**
     * Menu « robot » : range Transcription IA et Générer pendant les suggestions de mots (Corriger, à sa gauche, n'est visible
     * que si aucune suggestion n'est affichée) ; Vocal, à sa droite, est toujours visible. Ouvert, il met les actions IA en pilules icône + texte.
     */
    private val actionsMenuButton = ImageButton(context)

    /** Icône robot animée (antenne, yeux, bouche) : repos ↔ actif (fond violet), 300 ms avec léger ressort. */
    private var robotIcon: RobotIconDrawable? = null

    /** Croix de fermeture du panneau Smart Clipboard (à gauche de la barre) : visible seulement tant qu'il est ouvert. */
    private val closeButton = ImageButton(context)
    private var clipboardPanelOpen = false
    private var clipboardCloseListener: (() -> Unit)? = null
    private var actionsMenuBackgroundExpanded: Boolean? = null

    // Lot 21 : fonds des boutons à bascule et du bouton vocal créés une fois (un Drawable par état), jamais recréés.
    private val menuBackgrounds = style.toggleBackgrounds()
    private val actionsMenuBackgrounds = style.toggleBackgrounds()
    private val voiceBackgrounds = HashMap<Int, RoundKeyDrawable>()
    private val micIcon = MicIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())
    private val aiTranscriptionIcon = WaveIconDrawable(
        context.dimen(R.dimen.taipo_icon_size).toInt(),
        context.getColor(R.color.taipo_purple_light),
    )
    private val correctIcon = CorrectIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    /** État précédent du bouton Corriger : CORRECTING → IDLE (correction réussie) déclenche le retracé de la coche. */
    private var previousCorrectState = CorrectionBarState.HIDDEN
    private val zoneState = SuggestionZoneState()
    private var menuBackgroundExpanded: Boolean? = null
    val voiceButton = ImageButton(context)
    private var listener: OnCorrectListener? = null
    private var cancelListener: (() -> Unit)? = null

    /** Story 5.1 : bouton « Générer » (icône, mode chat), qui bascule la barre en mode prompt ; rangé avec Vocal derrière le menu de droite. */
    private val generateButton = LabeledIconButton(context, style)
    private var generateListener: (() -> Unit)? = null

    /** Story 14.5 : transcription IA (enregistrement envoyé à un modèle Gemma 4), entre Corriger et le menu « robot », visible avec les actions IA. */
    internal val aiTranscriptionButton = LabeledIconButton(context, style)

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

    /**
     * Story 10.1 : faux dans un champ sensible (mot de passe) : Corriger, Générer, Vocal, Transcription IA et le bouton Smart
     * Clipboard sont grisés (opacité réduite) mais restent touchables : l'IME explique alors la raison.
     */
    var aiActionsAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            zoneView.setClipboardButtonRestricted(!value)
            renderCorrect()
            renderVoice()
            renderAiTranscription()
        }

    /** Faux si le modèle actif n'a pas d'entrée audio (Ultra-léger, Léger) : le bouton Transcription IA n'est alors pas affiché. */
    var aiTranscriptionAvailable: Boolean = false
        set(value) {
            field = value
            renderActionButtons()
        }

    var aiTranscriptionState: AiTranscriptionBarState = AiTranscriptionBarState.IDLE
        set(value) {
            field = value
            renderAiTranscription()
        }

    /** La bande de mots affiche au moins un mot (ou un emoji) : le bouton Corriger est alors caché (voir [BarVisibility.actionButtons]). */
    private var wordsShown = false
    private var emojiShown = false

    /** Menu « robot » ouvert : les actions IA (Chat, Transcription, Correction) passent en pilules icône + texte, si la rangée tient. */
    private var labelsWanted = false

    /** Vocal, toujours visible, est le dernier bouton de la barre : celle-ci se cale sur le bord droit des touches. */
    private var voiceAnchoredRight = false

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

        style.styleBarIconButton(menuButton, R.drawable.ic_keyboard_right)
        menuChevron = RotatingDrawable(menuButton.drawable).also {
            menuButton.setImageDrawable(it) // le chevron pivote d'un demi-tour quand le menu s'ouvre
        }
        menuButton.contentDescription = context.getString(R.string.clipboard_menu_description)
        menuButton.setOnClickListener {
            menuClickListener?.invoke()
            zoneState.toggleMenu()
            renderZone(reveal = true)
        }
        addView(
            menuButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Bouton « roue crantée » entre le menu « ··· » et le bouton Smart Clipboard.
        style.styleRoundIconButton(settingsButton, R.drawable.ic_settings)
        settingsButton.contentDescription = context.getString(R.string.settings_button_description)
        settingsButton.setImageDrawable(settingsIcon) // même tracé que ic_settings au repos
        settingsButton.setOnClickListener {
            settingsIcon.spin()
            settingsListener?.invoke()
        }
        addView(
            settingsButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        addView(zoneView, LayoutParams(0, barHeight, 1f).apply { marginEnd = gap })

        // Story 5.1 : « Générer » (mode chat), le bouton le plus à gauche des actions. Icône seule (40 dp) pour ménager la place de la zone de gauche.
        style.styleRoundIconButton(generateButton, R.drawable.ic_rate_review)
        generateButton.contentDescription = context.getString(R.string.generate_button_description)
        generateButton.label = context.getString(R.string.action_pill_chat)
        generateButton.setOnClickListener {
            collapseMenu()
            generateListener?.invoke()
        }
        addView(
            generateButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Icône seule, grise comme Vocal ; l'état (chargement, correction…) passe par la transparence et la description d'accessibilité.
        style.styleRoundIconButton(correctButton, R.drawable.ic_spellcheck)
        correctButton.setImageDrawable(correctIcon) // même tracé que ic_spellcheck ; animé pendant la correction
        correctButton.setOnClickListener {
            when (state) {
                CorrectionBarState.IDLE -> {
                    collapseMenu()
                    listener?.onCorrectClicked()
                }
                // Chargement ou correction en cours : le même bouton l'annule.
                CorrectionBarState.LOADING -> cancelListener?.invoke()
                else -> Unit
            }
        }
        addView(
            correctButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Story 14.5 : bouton « voix vers texte », entre Corriger et le menu « robot ». Le clic est relié par l'IME.
        style.styleRoundIconButton(aiTranscriptionButton, R.drawable.ic_speech_to_text)
        aiTranscriptionButton.setImageDrawable(aiTranscriptionIcon) // trois barres : onde au repos et à l'écoute, lignes pendant le traitement
        aiTranscriptionButton.contentDescription = context.getString(R.string.ai_transcription_button_description)
        aiTranscriptionButton.label = context.getString(R.string.action_pill_transcription)
        addView(
            aiTranscriptionButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Menu « robot » : pendant les suggestions de mots, il range derrière lui Transcription IA et Générer (Corriger reste
        // visible à sa gauche) ; Vocal, à sa droite, n'en dépend pas.
        style.styleBarIconButton(actionsMenuButton, R.drawable.ic_robot)
        robotIcon = RobotIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt()).also {
            actionsMenuButton.setImageDrawable(it) // même tracé que ic_robot, animé quand le menu s'ouvre
        }
        actionsMenuButton.contentDescription = context.getString(R.string.actions_menu_description)
        actionsMenuButton.setOnClickListener {
            actionsMenuClickListener?.invoke()
            zoneState.toggleActionsMenu()
            renderZone(reveal = true)
        }
        addView(
            actionsMenuButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Lot 08 : Vocal devient un bouton rond à icône micro ; son état (écoute, chargement…) passe par la couleur,
        // la transparence et la description d'accessibilité, plus par le texte.
        style.styleRoundIconButton(voiceButton, R.drawable.ic_mic)
        voiceButton.setImageDrawable(micIcon) // même tracé que ic_mic ; la capsule s'anime (chargement, écoute)
        // Le geste réel passe par setOnTouchListener (côté appelant, qui consomme tout toucher) ; le clic, lui, n'est déclenché
        // que par l'accessibilité (TalkBack, double appui) : l'appelant le relie à la bascule d'écoute (lot 20).
        voiceButton.setOnClickListener { }
        addView(
            voiceButton,
            LayoutParams(iconButtonWidth, barHeight).apply { marginEnd = gap },
        )

        // Lot 20 : zone tactile des boutons étendue à 48 dp (36 dp dessinés), sans changer le rendu.
        expandChildTouchTargets()

        renderCorrect()
        renderVoice()
        renderZone()
    }

    /** Touche sur la roue crantée : ouvrir la page d'accueil de l'application. */
    /** Touche sur le bouton « ··· » de gauche (chevron) : sert au retour haptique, le menu s'ouvre ou se ferme ensuite. */
    fun setOnMenuClickListener(listener: () -> Unit) {
        menuClickListener = listener
    }

    /** Touche sur le bouton Action IA (robot) : sert au retour haptique, le menu s'ouvre ou se ferme ensuite. */
    fun setOnActionsMenuClickListener(listener: () -> Unit) {
        actionsMenuClickListener = listener
    }

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
     * coloré (actif), seul dans la zone de gauche, et un nouvel appui dessus referme le panneau. Le
     * menu « ··· » de gauche (il n'a plus d'objet) se cache ; la roue crantée reste affichée.
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
    override fun onDetachedFromWindow() {
        robotIcon?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).toFloat()
        val content = KeyboardWidth.forAvailableWidth(available, resources.displayMetrics.density)
        val side = dimen(R.dimen.taipo_bar_padding_horizontal).toInt() + content.leftPx.toInt()
        val endInset = if (voiceAnchoredRight) dimen(R.dimen.taipo_key_inset).toInt() else dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
        val sideEnd = endInset + content.leftPx.toInt()
        if (paddingLeft != side || paddingRight != sideEnd) {
            setPadding(side, paddingTop, sideEnd, paddingBottom)
        }
        val showLabels = labelsWanted && labeledRowFits(MeasureSpec.getSize(widthMeasureSpec) - side - sideEnd)
        generateButton.labeled = showLabels
        correctButton.labeled = showLabels
        aiTranscriptionButton.labeled = showLabels
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Les pilules icône + texte tiennent-elles dans [availableWidth] avec les autres boutons visibles (robot, Vocal) et les
     * écarts ? Sinon (écran étroit, grande police), les actions restent en icônes seules : le texte ne déborde jamais.
     */
    private fun labeledRowFits(availableWidth: Int): Boolean {
        val gap = dimen(R.dimen.taipo_bar_gap).toInt()
        val iconWidth = dimen(R.dimen.taipo_bar_icon_button_width).toInt()
        var total = gap // marge de fin de la zone de gauche (vide quand le menu est ouvert)
        var count = 0
        for (button in listOf(generateButton, correctButton, aiTranscriptionButton, actionsMenuButton, voiceButton)) {
            if (button.visibility != View.VISIBLE) continue
            total += if (button is LabeledIconButton) button.labeledWidth() else iconWidth
            count++
        }
        total += (count - 1).coerceAtLeast(0) * gap
        return total <= availableWidth
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
        val shown = emoji != null
        if (emojiShown != shown) {
            emojiShown = shown
            renderActionButtons() // Corriger se cache tant qu'une suggestion est affichée
        }
    }

    fun setOnEmojiSuggestionClickListener(listener: SuggestionStripView.OnEmojiClickListener) {
        zoneView.setOnEmojiSuggestionClickListener(listener)
    }

    /** Story 1.17 : remplit les 3 emplacements de mots (liste vide ou null = emplacement vide). */
    fun setWordSuggestions(words: List<WordSuggestion?>) {
        zoneView.setWordSuggestions(words)
        val shown = words.any { it != null }
        if (wordsShown != shown) {
            wordsShown = shown
            renderActionButtons() // Corriger se cache tant qu'une suggestion est affichée
        }
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        zoneView.setOnWordSuggestionClickListener(listener)
    }

    fun setOnCorrectListener(listener: OnCorrectListener) {
        this.listener = listener
    }

    /** Touche sur le bouton Annuler (à droite du bouton Smart Clipboard) : défaire la dernière action. */
    fun setOnUndoListener(listener: () -> Unit) {
        zoneView.setOnUndoClickListener(listener)
    }

    /** Le bouton Annuler est actif quand au moins une action est annulable, grisé sinon. */
    fun setUndoAvailable(available: Boolean) {
        zoneView.setUndoEnabled(available)
    }

    /** Nouvelle touche sur Corriger pendant le chargement ou la correction : abandonner la correction en cours. */
    fun setOnCancelListener(listener: () -> Unit) {
        cancelListener = listener
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

    /**
     * [reveal] : l'appel vient d'un appui sur un menu « ··· » ; les boutons qui apparaissent se dévoilent alors en
     * cascade depuis le menu robot (le plus proche en premier), sinon ils se fondent simplement ([BarReveal]).
     */
    private fun renderActionButtons(reveal: Boolean = false) {
        val visible = BarVisibility.actionButtons(voiceState, state, zoneState, clipboardPanelOpen, wordsShown || emojiShown)
        setRevealed(voiceButton, visible.voice, order = 0, full = reveal)
        setRevealed(generateButton, visible.generate, order = 2, full = reveal)
        setRevealed(aiTranscriptionButton, visible.aiTranscription && aiTranscriptionAvailable, order = 0, full = reveal)
        setRevealed(correctButton, visible.correct, order = 1, full = reveal)
        setRevealed(actionsMenuButton, visible.actionsMenu, order = 0, full = reveal)
        closeButton.visibility = View.GONE // remplacée par le bouton Smart Clipboard actif, qui referme le panneau
        anchorTrailingButtonsRight()
    }

    /**
     * Ordre de droite à gauche : Vocal (toujours visible, hors du groupe des actions IA), menu « robot » (quand il existe),
     * Transcription IA, Corriger, Générer. L'écart [R.dimen.taipo_bar_gap] sépare chaque bouton visible du suivant ; le
     * dernier bouton visible (Vocal) n'a pas de marge de fin et se cale sur le bord droit des touches (inset de touche)
     * au lieu de la marge de la barre.
     */
    private fun anchorTrailingButtonsRight() {
        val gap = dimen(R.dimen.taipo_bar_gap).toInt()
        val row = listOf(generateButton, correctButton, aiTranscriptionButton, actionsMenuButton, voiceButton)
        val lastVisible = row.lastOrNull { it.visibility == View.VISIBLE }
        row.forEach { button ->
            val params = button.layoutParams as LayoutParams
            val endMargin = if (button === lastVisible) 0 else gap
            if (params.marginEnd != endMargin) {
                params.marginEnd = endMargin
                button.layoutParams = params
            }
        }
        val voiceIsLast = lastVisible === voiceButton
        if (voiceAnchoredRight != voiceIsLast) {
            voiceAnchoredRight = voiceIsLast
            requestLayout() // onMeasure recalcule la marge droite de la barre
        }
    }

    /**
     * Change la visibilité de [view] ; un bouton qui apparaît sur une barre déjà affichée s'anime ([BarReveal]) depuis la
     * droite, ou depuis la gauche quand [fromEnd] est faux ; un bouton qui disparaît interrompt son animation.
     */
    private fun setRevealed(view: View, visible: Boolean, order: Int, full: Boolean, fromEnd: Boolean = true) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !wasVisible && isLaidOut) {
            BarReveal.enter(view, fromEnd = fromEnd, order = order, full = full)
        } else if (!visible && wasVisible) {
            BarReveal.cancel(view)
        }
    }

    /** [reveal] : appui sur un menu « ··· » (dévoilement complet) ; sinon (frappe, copie…) simple fondu. */
    private fun renderZone(reveal: Boolean = false) {
        val wanted = BarVisibility.labeledActions(zoneState, clipboardPanelOpen)
        if (labelsWanted != wanted) {
            labelsWanted = wanted
            requestLayout() // onMeasure décide si la rangée tient en pilules
        }
        renderActionButtons(reveal)
        val visible = BarVisibility.zoneContent(zoneState, clipboardPanelOpen)
        zoneView.render(visible, clipboardPanelOpen, reveal = reveal && isLaidOut)
        setRevealed(menuButton, visible.menuButton, order = 0, full = reveal, fromEnd = false)
        setRevealed(settingsButton, visible.settingsButton, order = 0, full = reveal, fromEnd = false)
        if (menuBackgroundExpanded != zoneState.menuExpanded) {
            menuBackgroundExpanded = zoneState.menuExpanded
            menuButton.background = menuBackgrounds.get(zoneState.menuExpanded)
            menuChevron?.rotateTo(if (zoneState.menuExpanded) 180f else 0f, animate = isLaidOut)
        }
        if (actionsMenuBackgroundExpanded != zoneState.actionsExpanded) {
            actionsMenuBackgroundExpanded = zoneState.actionsExpanded
            actionsMenuButton.background = actionsMenuBackgrounds.get(zoneState.actionsExpanded)
            robotIcon?.setActive(zoneState.actionsExpanded, animate = isLaidOut)
        }
    }

    private fun renderCorrect() {
        val appearance = BarVisibility.correctAppearance(state)
        appearance?.let {
            correctButton.isEnabled = it.enabled
            correctButton.alpha = it.alpha
            correctButton.face = it.face
            correctButton.contentDescription = context.getString(it.label)
            correctButton.label = context.getString(R.string.action_pill_correction)
        }
        // Chargement du modèle ou correction : un faisceau balaie l'icône (le bouton reste désactivé). Correction réussie
        // (CORRECTING puis IDLE) : la coche se retrace avec un rebond. Annulation ou erreur : retour direct au repos.
        correctIcon.setWorking(appearance?.loading == true, animate = isLaidOut)
        if (state == CorrectionBarState.IDLE && previousCorrectState == CorrectionBarState.CORRECTING) correctIcon.playDone()
        previousCorrectState = state
        renderActionButtons()
        applyFieldRestriction()
    }

    /** Niveau sonore de l'écoute (0 à 1) : fait monter et descendre la jauge de la capsule du micro. */
    fun setVoiceLevel(level: Float) = micIcon.setLevel(level)

    private fun renderVoice() {
        val appearance = BarVisibility.voiceAppearance(voiceState)
        voiceButton.contentDescription = context.getString(appearance.label)
        voiceButton.background = voiceBackgrounds.getOrPut(appearance.background) { style.roundBackground(appearance.background) }
        voiceButton.alpha = appearance.alpha
        micIcon.setMode(MicIconMode.of(voiceState), animate = isLaidOut)
        renderActionButtons()
        applyFieldRestriction()
    }

    /** Niveau sonore de l'enregistrement (0 à 1) : fait monter et descendre les barres de l'icône Transcription IA. */
    fun setAiTranscriptionLevel(level: Float) = aiTranscriptionIcon.setLevel(level)

    private fun renderAiTranscription() {
        aiTranscriptionButton.contentDescription = context.getString(
            when (aiTranscriptionState) {
                AiTranscriptionBarState.IDLE -> R.string.ai_transcription_button_description
                AiTranscriptionBarState.RECORDING -> R.string.ai_transcription_button_recording
                AiTranscriptionBarState.PROCESSING -> R.string.ai_transcription_button_processing
                AiTranscriptionBarState.CANCELLING -> R.string.ai_transcription_button_cancelling
            },
        )
        val appearance = BarVisibility.aiTranscriptionAppearance(aiTranscriptionState)
        aiTranscriptionButton.face = appearance.face
        aiTranscriptionButton.alpha = appearance.alpha
        aiTranscriptionIcon.setMode(WaveIconMode.of(aiTranscriptionState), animate = isLaidOut)
        applyFieldRestriction()
    }

    /**
     * Story 10.1 : dans un champ sensible, les boutons d'action IA sont grisés par-dessus leur apparence normale (appelé en
     * dernier par chaque rendu, qui réécrit l'opacité). Hors champ sensible, « Générer » reprend son opacité pleine.
     */
    private fun applyFieldRestriction() {
        if (aiActionsAllowed) {
            generateButton.alpha = 1f
            return
        }
        listOf(correctButton, voiceButton, generateButton, aiTranscriptionButton).forEach { it.alpha = RESTRICTED_ALPHA }
    }
}
