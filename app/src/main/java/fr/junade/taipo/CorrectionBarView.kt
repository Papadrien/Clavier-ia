package fr.junade.taipo

import android.graphics.Rect
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Barre au-dessus du clavier, à deux états ([BarMode], règles dans [SuggestionZoneState] et [BarVisibility]).
 *
 * - **Normale** (champ vide) : Paramètres, Smart Clipboard (puce de collage à côté), Annuler, Chat, Corriger (grisé : rien à
 *   corriger) et Transcription IA, en boutons ronds ; Vocal fixé à droite.
 * - **Saisie** : le robot (menu de gauche) est ancré à gauche, la bande de mots (ou l'auto-remplissage) prend la place
 *   entre lui et les boutons de droite, Corriger (quand aucune suggestion n'est affichée) et Vocal sont ancrés à droite.
 *   Robot touché : la bande s'efface et la rangée devient Robot (actif), Paramètres, Smart Clipboard, Annuler, Chat,
 *   Corriger, Transcription et Vocal, à espacement égal ([SpaceBetweenRow]) ; Chat, Corriger et Transcription passent en
 *   pilules icône + texte quand la largeur de l'écran le permet, en boutons ronds sinon.
 * - Panneau Smart Clipboard ouvert : la barre ne change pas, le bouton Smart Clipboard est coloré.
 *
 * Décision 3.3 : Corriger n'est visible que si du texte est présent. Décision 8.7 : un état « chargement » distinct est
 * affiché si le modèle de correction doit être rechargé. Le bouton Vocal est posé par-dessus les deux rangées : il ne bouge
 * jamais, mais compte comme dernier élément de leur répartition.
 *
 * Animations : un seul ressort ([BarReveal.springProgress]). Normale vers saisie : la rangée normale monte et sort par le haut
 * de la barre, la rangée de saisie monte depuis le bas à sa suite et le robot entre par la gauche ; le sens inverse fait le
 * chemin contraire. Un bouton qui change de place glisse en ressort ([SpaceBetweenRow]).
 */
@SuppressLint("ViewConstructor")
class CorrectionBarView(context: Context) : FrameLayout(context) {

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
    private val barHeight = dimen(R.dimen.taipo_bar_height).toInt()
    private val iconButtonWidth = dimen(R.dimen.taipo_bar_icon_button_width).toInt()
    private val minGap = dimen(R.dimen.taipo_bar_min_gap).toInt()
    private val fillGap = dimen(R.dimen.taipo_bar_gap).toInt()
    private val paddingV = dimen(R.dimen.taipo_bar_padding_vertical).toInt()
    private val keyInset = dimen(R.dimen.taipo_key_inset).toInt()

    private val zoneState = SuggestionZoneState()

    /** Rangée de la barre normale (champ vide) et rangée de saisie, superposées. */
    private val normalRow = SpaceBetweenRow(context)
    private val typingRow = SpaceBetweenRow(context)

    /** Paramètres, Smart Clipboard et Annuler existent en deux jeux : rangée normale, et menu ouvert de la rangée de saisie. */
    private val normalButtons = UtilityButtons(context, style)
    private val menuButtons = UtilityButtons(context, style)
    private val pasteChip = PasteChipView(context, style)

    /** Bande de mots (ou suggestions d'auto-remplissage), entre le robot et les boutons de droite. */
    private val zoneView = SuggestionZoneView(context)

    /** Robot : menu de gauche de la rangée de saisie. Repos ↔ actif (fond violet, icône animée). */
    private val robotButton = ImageButton(context)
    private val robotBackgrounds = style.toggleBackgrounds()
    private var robotIcon: RobotIconDrawable? = null
    private var robotActive: Boolean? = null

    private val micIcon = MicIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())
    private val voiceBackgrounds = HashMap<Int, RoundKeyDrawable>()

    /**
     * Chat (story 5.1, bascule la barre en mode prompt), Corriger et Transcription IA (story 14.5, enregistrement envoyé à un
     * modèle Gemma 4) existent en deux jeux : rangée normale (boutons ronds, icône seule) et menu ouvert de la rangée de
     * saisie (pilules icône + texte si la rangée tient).
     */
    private val normalActions = AiActionButtons(context, style)
    private val menuActions = AiActionButtons(context, style)
    val voiceButton = ImageButton(context)

    private var listener: OnCorrectListener? = null
    private var cancelListener: (() -> Unit)? = null
    private var generateListener: (() -> Unit)? = null
    private var actionsMenuClickListener: (() -> Unit)? = null
    private var clipboardCloseListener: (() -> Unit)? = null

    /** État précédent du bouton Corriger : CORRECTING → IDLE (correction réussie) déclenche le retracé de la coche. */
    private var previousCorrectState = CorrectionBarState.HIDDEN

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
     * Story 10.1 : faux dans un champ sensible (mot de passe) : Corriger, Chat, Vocal, Transcription IA et le bouton Smart
     * Clipboard sont grisés (opacité réduite) mais restent touchables : l'IME explique alors la raison.
     */
    var aiActionsAllowed: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            normalButtons.setClipboardRestricted(!value)
            menuButtons.setClipboardRestricted(!value)
            renderCorrect()
            renderVoice()
            renderAiTranscription()
        }

    /** Faux si le modèle actif n'a pas d'entrée audio (Fourmi, Colibri) : le bouton Transcription IA n'est alors pas affiché. */
    var aiTranscriptionAvailable: Boolean = false
        set(value) {
            field = value
            render()
        }

    var aiTranscriptionState: AiTranscriptionBarState = AiTranscriptionBarState.IDLE
        set(value) {
            field = value
            renderAiTranscription()
        }

    /** La bande de mots affiche au moins un mot (ou un emoji) : le bouton Corriger est alors caché (voir [BarVisibility.actionButtons]). */
    private var wordsShown = false
    private var emojiShown = false

    /** Menu ouvert : Chat, Corriger et Transcription passent en pilules icône + texte, si la rangée tient. */
    private var labelsWanted = false

    /** État de barre affiché (ou vers lequel la transition en cours se dirige) et la transition elle-même. */
    private var shownMode = BarMode.NORMAL
    private var modeAnimator: ValueAnimator? = null

    init {
        // Fond transparent : la barre laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        val rowHeight = barHeight + 2 * paddingV
        for (row in listOf(normalRow, typingRow)) {
            row.minGapPx = minGap
            row.fillGapPx = fillGap
            row.reservedEndPx = iconButtonWidth
            row.setPadding(keyInset, paddingV, keyInset, paddingV)
        }

        // Rangée normale : Paramètres, Smart Clipboard, puce de collage, Annuler (puis Vocal, posé par-dessus).
        normalRow.addView(normalButtons.settingsButton, roundParams())
        normalRow.addView(normalButtons.clipboardButton, roundParams())
        pasteChip.visibility = View.GONE
        normalRow.addView(pasteChip, SpaceBetweenRow.LayoutParams(LayoutParams.WRAP_CONTENT, barHeight, RowRole.SHRINK))
        normalRow.addView(normalButtons.undoButton, roundParams())
        // Actions IA de la barre normale : Chat, Corriger, Transcription IA (avant Vocal, posé par-dessus).
        for (view in normalActions.views) normalRow.addView(view, roundParams())

        // Rangée de saisie : Robot, [menu ouvert : Paramètres, Smart Clipboard, Annuler, Chat], bande, Corriger, Transcription.
        style.styleRoundIconButton(robotButton, R.drawable.ic_robot)
        robotIcon = RobotIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt()).also {
            robotButton.setImageDrawable(it) // même tracé que ic_robot, animé quand le menu s'ouvre
        }
        robotButton.contentDescription = context.getString(R.string.actions_menu_description)
        robotButton.setOnClickListener {
            actionsMenuClickListener?.invoke()
            // Panneau Smart Clipboard ouvert : la barre est figée, le panneau se referme (la barre suit alors l'état réel).
            val panelWasOpen = zoneState.clipboardPanelOpen
            if (panelWasOpen) clipboardCloseListener?.invoke()
            // Le menu reste ouvert pendant le panneau (réellement comme à l'affichage) : ce robot referme le panneau, puis le menu.
            zoneState.toggleMenu()
            render(reveal = true)
        }
        typingRow.addView(robotButton, roundParams())
        for (view in menuButtons.views) {
            view.visibility = View.GONE
            typingRow.addView(view, roundParams())
        }

        // Story 5.1 : « Chat » (mode prompt). Icône seule (bouton rond) ou pilule selon la largeur.
        for (button in listOf(normalActions.generateButton, menuActions.generateButton)) {
            button.setOnClickListener {
                // Le moteur est partagé : pas de génération pendant une écoute, une correction ou une transcription IA.
                if (generateBlocked()) return@setOnClickListener
                collapseMenu()
                generateListener?.invoke()
            }
        }
        menuActions.generateButton.visibility = View.GONE
        typingRow.addView(menuActions.generateButton, roundParams())

        typingRow.addView(zoneView, SpaceBetweenRow.LayoutParams(0, barHeight, RowRole.FILL))

        // Icône seule, grise comme Vocal ; l'état (chargement, correction…) passe par la transparence et la description d'accessibilité.
        for (button in listOf(normalActions.correctButton, menuActions.correctButton)) {
            button.setOnClickListener {
                // Transcription en cours (Vocal ou Transcription IA) : le bouton est grisé, l'appui est ignoré.
                if (transcribing()) return@setOnClickListener
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
        }
        typingRow.addView(menuActions.correctButton, roundParams())

        // Story 14.5 : bouton « voix vers texte ». Le clic est relié par l'IME.
        menuActions.transcriptionButton.visibility = View.GONE
        typingRow.addView(menuActions.transcriptionButton, roundParams())

        addView(normalRow, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        addView(typingRow, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight))
        typingRow.visibility = View.GONE

        // Lot 08 : Vocal est un bouton rond à icône micro ; son état (écoute, chargement…) passe par la couleur,
        // la transparence et la description d'accessibilité, plus par le texte. Posé par-dessus les rangées, à droite.
        style.styleRoundIconButton(voiceButton, R.drawable.ic_mic)
        voiceButton.setImageDrawable(micIcon) // même tracé que ic_mic ; la capsule s'anime (chargement, écoute)
        // Le geste réel passe par setOnTouchListener (côté appelant, qui consomme tout toucher) ; le clic, lui, n'est déclenché
        // que par l'accessibilité (TalkBack, double appui) : l'appelant le relie à la bascule d'écoute (lot 20).
        voiceButton.setOnClickListener { }
        addView(
            voiceButton,
            LayoutParams(iconButtonWidth, barHeight, Gravity.END or Gravity.TOP).apply {
                topMargin = paddingV
                marginEnd = keyInset
            },
        )

        // Lot 20 : zone tactile des boutons étendue à 48 dp (36 dp dessinés), sans changer le rendu.
        expandChildTouchTargets(this::touchTargets)

        renderCorrect()
        renderVoice()
    }

    private fun roundParams() = SpaceBetweenRow.LayoutParams(iconButtonWidth, barHeight, RowRole.ROUND)

    /** Boutons que la zone tactile étendue peut viser : ceux des rangées affichées, et Vocal. */
    private fun touchTargets(): List<View> {
        val targets = ArrayList<View>()
        for (row in listOf(normalRow, typingRow)) {
            if (row.visibility != View.VISIBLE) continue
            for (index in 0 until row.childCount) targets.add(row.getChildAt(index))
        }
        targets.add(voiceButton)
        return targets
    }

    /** Touche sur le robot (menu de gauche) : sert au retour haptique et au son, le menu s'ouvre ou se ferme ensuite. */
    fun setOnActionsMenuClickListener(listener: () -> Unit) {
        actionsMenuClickListener = listener
    }

    /** Touche sur la roue crantée : ouvrir la page d'accueil de l'application. */
    fun setOnSettingsClickListener(listener: () -> Unit) {
        normalButtons.setOnSettingsClickListener(listener)
        menuButtons.setOnSettingsClickListener(listener)
    }

    /** Touche sur Transcription IA (barre normale ou menu ouvert) : relié par l'IME. */
    fun setOnAiTranscriptionClickListener(listener: () -> Unit) {
        normalActions.transcriptionButton.setOnClickListener { listener() }
        menuActions.transcriptionButton.setOnClickListener { listener() }
    }

    /** Touche sur « Chat » : entrer en mode prompt (story 5.1). */
    fun setOnGenerateClickListener(listener: () -> Unit) {
        generateListener = listener
    }

    /** Le panneau Smart Clipboard doit se fermer (robot touché pendant qu'il est ouvert). */
    fun setOnClipboardCloseClickListener(listener: () -> Unit) {
        clipboardCloseListener = listener
    }

    /**
     * Le panneau Smart Clipboard est ouvert (ou refermé) : la barre ne change pas, le bouton Smart Clipboard est coloré (actif)
     * et un nouvel appui dessus referme le panneau.
     */
    fun setClipboardPanelOpen(open: Boolean) {
        if (zoneState.clipboardPanelOpen == open) return
        zoneState.setClipboardPanelOpen(open)
        render()
    }

    override fun onDetachedFromWindow() {
        robotIcon?.cancel()
        modeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    /**
     * Story 1.14 : en classe de largeur large (≥ 600 dp), les boutons restent alignés sur la zone centrée des touches (le
     * fond de la barre garde toute la largeur). Marges gauche et droite identiques : les boutons extrêmes (Paramètres / Robot et Vocal) se calent sur les bords des touches (inset de touche).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val content = KeyboardWidth.forAvailableWidth(available.toFloat(), resources.displayMetrics.density)
        // Même marge des deux côtés : le bord gauche (Paramètres / Robot) se cale sur les touches, comme Vocal à droite.
        val side = keyInset + content.leftPx.toInt()
        val sideEnd = side
        for (row in listOf(normalRow, typingRow)) {
            if (row.paddingLeft != side || row.paddingRight != sideEnd) row.setPadding(side, paddingV, sideEnd, paddingV)
        }
        val voiceParams = voiceButton.layoutParams as LayoutParams
        if (voiceParams.marginEnd != sideEnd) {
            voiceParams.marginEnd = sideEnd
            voiceButton.layoutParams = voiceParams
        }
        val showLabels = labelsWanted && labeledRowFits(available - side - sideEnd)
        for (button in menuActions.views) button.labeled = showLabels
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * Les pilules icône + texte tiennent-elles dans [availableWidth] avec les autres boutons du menu ouvert, Vocal et l'écart
     * minimal ? Sinon (écran étroit, grande police), les actions restent des boutons ronds : le texte ne déborde jamais.
     */
    private fun labeledRowFits(availableWidth: Int): Boolean {
        var total = iconButtonWidth // Vocal
        var count = 1
        for (index in 0 until typingRow.childCount) {
            val child = typingRow.getChildAt(index)
            if (child === zoneView || child.visibility == View.GONE) continue
            total += if (child is LabeledIconButton) child.labeledWidth() else iconButtonWidth
            count++
        }
        total += (count - 1) * minGap
        return total <= availableWidth
    }

    /**
     * Affiche les [views] d'auto-remplissage en ligne à la place des suggestions de mots ; une liste vide retire les
     * suggestions. Des suggestions en ligne rendent la barre de saisie, même sur un champ vide.
     */
    fun setInlineSuggestions(views: List<View>) {
        zoneView.setInlineViews(views)
        zoneState.setInlineAvailable(views.isNotEmpty())
        render()
    }

    /** Story 1.16 : propose [emoji] dans le 4e emplacement de la bande de suggestions (null = aucun). */
    fun setEmojiSuggestion(emoji: String?) {
        zoneView.setEmojiSuggestion(emoji)
        val shown = emoji != null
        if (emojiShown != shown) {
            emojiShown = shown
            render() // Corriger se cache tant qu'une suggestion est affichée
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
            render() // Corriger se cache tant qu'une suggestion est affichée
        }
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        zoneView.setOnWordSuggestionClickListener(listener)
    }

    fun setOnCorrectListener(listener: OnCorrectListener) {
        this.listener = listener
    }

    /** Touche sur le bouton Annuler : défaire la dernière action. */
    fun setOnUndoListener(listener: () -> Unit) {
        normalButtons.setOnUndoClickListener(listener)
        menuButtons.setOnUndoClickListener(listener)
    }

    /** Le bouton Annuler est actif quand au moins une action est annulable, grisé sinon. */
    fun setUndoAvailable(available: Boolean) {
        normalButtons.setUndoEnabled(available, animate = isLaidOut)
        menuButtons.setUndoEnabled(available, animate = isLaidOut)
    }

    /** Nouvelle touche sur Corriger pendant le chargement ou la correction : abandonner la correction en cours. */
    fun setOnCancelListener(listener: () -> Unit) {
        cancelListener = listener
    }

    fun setOnClipboardClickListener(listener: OnClipboardClickListener) {
        normalButtons.setOnClipboardClickListener { listener.onClipboardClick() }
        menuButtons.setOnClipboardClickListener { listener.onClipboardClick() }
    }

    fun setOnPasteClickListener(listener: OnPasteClickListener) {
        pasteChip.setOnPasteClickListener { listener.onPasteClick() }
        zoneView.setOnPasteClickListener { listener.onPasteClick() }
    }

    /**
     * Story 2.2 : propose le collage avec l'aperçu [preview] (déjà mis en forme, éventuellement masqué), ou retire la puce
     * si [preview] est null. [sensitive] : l'aperçu est masqué, la description d'accessibilité ne révèle donc pas non plus le texte.
     */
    fun setPasteSuggestion(preview: String?, sensitive: Boolean) {
        if (preview == null) {
            if (!zoneState.pasteAvailable) return
            zoneState.setPasteAvailable(false)
        } else {
            zoneState.setPasteAvailable(true)
        }
        pasteChip.setPasteText(preview, sensitive)
        zoneView.setPasteSuggestion(preview, sensitive)
        render()
    }

    /** Le champ contient du texte (barre de saisie) ou est vide (barre normale). */
    fun setFieldHasText(hasText: Boolean) {
        zoneState.onFieldTextChanged(hasText)
        render()
    }

    /** Story 2.1/2.4 : début de la frappe, retour aux suggestions de mots (le menu se referme). */
    fun collapseMenu() {
        removeCallbacks(hideDigitTipRunnable)
        zoneState.onTyping()
        render()
    }

    private val hideDigitTipRunnable = Runnable { hideDigitTip() }

    /** Tip « Swipe vers le bas » à la place des suggestions, jusqu'à la prochaine frappe ou [DIGIT_TIP_DURATION_MS]. */
    fun showDigitTip() {
        zoneState.showTip()
        render()
        removeCallbacks(hideDigitTipRunnable)
        postDelayed(hideDigitTipRunnable, DIGIT_TIP_DURATION_MS)
    }

    fun hideDigitTip() {
        removeCallbacks(hideDigitTipRunnable)
        zoneState.hideTip()
        render()
    }

    /**
     * Met à jour toute la barre d'après l'état courant : rangée affichée (avec sa transition), boutons visibles, robot actif,
     * bouton Smart Clipboard actif. [reveal] : l'appel vient d'un appui sur le robot ; les boutons qui apparaissent jaillissent
     * alors du robot en ressort, sinon (frappe, copie…) ils se fondent simplement ([BarReveal]).
     */
    private fun render(reveal: Boolean = false) {
        // Une écoute lancée depuis la barre normale la garde affichée jusqu'à sa fin (la dictée insère du texte au fil de l'eau).
        // Sauf si un mot touché pour le corriger affiche ses alternatives (seul cas où la bande de mots est publiée pendant
        // la dictée) : la barre de saisie, qui porte la bande, prend alors le relais.
        zoneState.holdNormalWhile(
            (voiceState != VoiceBarState.IDLE && !wordsShown) || aiTranscriptionState != AiTranscriptionBarState.IDLE,
        )
        val mode = BarVisibility.mode(zoneState)
        val content = BarVisibility.content(zoneState, mode)
        val actions = BarVisibility.actionButtons(
            voiceState,
            state,
            zoneState,
            mode,
            wordsShown || emojiShown,
            aiTranscriptionState,
        )
        val wantLabels = BarVisibility.labeledActions(zoneState, mode)
        if (labelsWanted != wantLabels) {
            labelsWanted = wantLabels
            requestLayout() // onMeasure décide si la rangée tient en pilules
        }
        showMode(mode)

        // Rangée de saisie.
        robotButton.visibility = View.VISIBLE
        zoneView.render(content.words, content.inline, content.tip)
        setRevealed(zoneView, content.words || content.inline || content.tip, order = 0, full = reveal, row = typingRow)
        val spring = reveal && content.menuUtilityButtons
        setRevealed(menuButtons.settingsButton, content.menuUtilityButtons, order = 0, full = reveal, row = typingRow, spring = spring)
        setRevealed(
            menuButtons.clipboardButton, content.menuUtilityButtons, order = 1, full = reveal, row = typingRow, spring = spring,
            restingAlpha = menuButtons::clipboardRestingAlpha,
        )
        setRevealed(
            menuButtons.undoButton, content.menuUtilityButtons, order = 2, full = reveal, row = typingRow, spring = spring,
            restingAlpha = menuButtons::undoRestingAlpha,
        )
        setRevealed(
            menuActions.generateButton, actions.generate, order = 3, full = reveal, row = typingRow, spring = spring,
            restingAlpha = ::generateRestingAlpha,
        )
        setRevealed(
            menuActions.correctButton, actions.correct, order = 4, full = reveal, row = typingRow, spring = spring,
            restingAlpha = ::correctRestingAlpha,
        )
        setRevealed(
            menuActions.transcriptionButton, actions.aiTranscription && aiTranscriptionAvailable, order = 5, full = reveal,
            row = typingRow, spring = spring, restingAlpha = ::transcriptionRestingAlpha,
        )

        // Rangée normale : la puce de collage apparaît à côté du bouton Smart Clipboard, suivie des actions IA.
        setRevealed(pasteChip, content.pasteChip, order = 0, full = false, row = normalRow)
        val normal = BarVisibility.normalRowActions(mode)
        setRevealed(
            normalActions.generateButton, normal.generate, order = 3, full = false, row = normalRow,
            restingAlpha = ::generateRestingAlpha,
        )
        setRevealed(
            normalActions.correctButton, normal.correct, order = 4, full = false, row = normalRow,
            restingAlpha = ::correctRestingAlpha,
        )
        setRevealed(
            normalActions.transcriptionButton, normal.aiTranscription && aiTranscriptionAvailable, order = 5, full = false,
            row = normalRow, restingAlpha = ::transcriptionRestingAlpha,
        )

        val menuOpen = mode == BarMode.TYPING && zoneState.menuOpen
        if (robotActive != menuOpen) {
            robotActive = menuOpen
            robotButton.background = robotBackgrounds.get(menuOpen)
            robotIcon?.setActive(menuOpen, animate = isLaidOut)
        }
        normalButtons.setClipboardActive(zoneState.clipboardPanelOpen, animate = isLaidOut)
        menuButtons.setClipboardActive(zoneState.clipboardPanelOpen, animate = isLaidOut)
        applyFieldRestriction()
    }

    /**
     * Change la visibilité de [view], enfant de [row] ; un bouton qui apparaît sur une barre déjà affichée s'anime
     * ([BarReveal]) : en ressort depuis le robot si [spring], sinon rideau ([full]) ou fondu. Aucune animation tant que sa
     * rangée n'est pas affichée ou qu'une transition de rangée est en cours (elle porte déjà le mouvement). Un bouton qui
     * disparaît interrompt son animation.
     */
    private fun setRevealed(
        view: View,
        visible: Boolean,
        order: Int,
        full: Boolean,
        row: View,
        spring: Boolean = false,
        restingAlpha: (() -> Float)? = null,
    ) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        val animate = isLaidOut && row.visibility == View.VISIBLE && row.isLaidOut && modeAnimator == null
        if (visible && !wasVisible && animate) {
            if (spring) {
                val offset = BarReveal.SPRING_SLIDE_DP * resources.displayMetrics.density
                BarReveal.enterSpring(view, fromEnd = false, order = order, offsetPx = offset, restingAlpha = restingAlpha)
            } else {
                BarReveal.enter(view, fromEnd = false, order = order, full = full, restingAlpha = restingAlpha)
            }
        } else if (!visible && wasVisible) {
            BarReveal.cancel(view)
        }
    }

    /** Passe à la rangée de [mode], avec la transition en ressort (sans animation tant que la barre n'est pas affichée). */
    private fun showMode(mode: BarMode) {
        if (mode == shownMode) return
        modeAnimator?.cancel()
        modeAnimator = null
        settleRows(shownMode)
        shownMode = mode
        val barHeightPx = height.toFloat()
        if (!isLaidOut || barHeightPx <= 0f || !ValueAnimator.areAnimatorsEnabled()) {
            settleRows(mode)
            return
        }
        val toTyping = mode == BarMode.TYPING
        val enterRow = if (toTyping) typingRow else normalRow
        val leaveRow = if (toTyping) normalRow else typingRow
        enterRow.forgetPositions()
        enterRow.visibility = View.VISIBLE
        enterRow.alpha = 0f
        // La rangée de saisie arrive du bas, la rangée normale du haut ; la rangée normale sort par le haut, l'autre par le bas.
        val enterFromY = if (toTyping) barHeightPx else -barHeightPx
        val leaveToY = if (toTyping) -barHeightPx else barHeightPx
        val robotTravel = (typingRow.paddingLeft + iconButtonWidth).toFloat()
        val total = BarReveal.SPRING_DURATION_MS + ROW_ENTER_DELAY_MS
        // Pendant la transition, les rangées ne dépassent ni en haut (barre d'emojis récents) ni en bas (clavier) : elles
        // disparaissent sous le bord de la barre. La racine du clavier ne coupe pas ses enfants (clipChildren = false, pour la
        // bulle d'accents), et le rognage des rangées par la barre n'a lieu qu'à ce prix : on rogne donc la barre elle-même.
        clipBounds = Rect(-SIDE_CLIP_MARGIN_PX, 0, width + SIDE_CLIP_MARGIN_PX, height)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = LinearInterpolator()
            addUpdateListener { update ->
                val elapsed = (update.animatedValue as Float) * total
                val leave = (elapsed / BarReveal.SPRING_DURATION_MS).coerceIn(0f, 1f)
                val enter = ((elapsed - ROW_ENTER_DELAY_MS) / BarReveal.SPRING_DURATION_MS).coerceIn(0f, 1f)
                enterRow.translationY = BarReveal.glideOffset(enterFromY, enter)
                enterRow.alpha = (enter / ROW_FADE_IN).coerceIn(0f, 1f)
                leaveRow.translationY = leaveToY * BarReveal.springProgress(leave)
                leaveRow.alpha = 1f - (leave / ROW_FADE_OUT).coerceIn(0f, 1f)
                // Le robot entre par la gauche de l'écran (ou y retourne) pendant que sa rangée monte (ou descend).
                robotButton.translationX = if (toTyping) {
                    BarReveal.glideOffset(-robotTravel, enter)
                } else {
                    -robotTravel * BarReveal.springProgress(leave)
                }
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (cancelled) return
                        modeAnimator = null
                        settleRows(shownMode)
                    }
                },
            )
        }
        modeAnimator = animator
        animator.start()
    }

    /** Rangée de [mode] seule affichée, à sa place : plus aucun décalage, opacité pleine. */
    private fun settleRows(mode: BarMode) {
        clipBounds = null
        val typing = mode == BarMode.TYPING
        normalRow.visibility = if (typing) View.GONE else View.VISIBLE
        typingRow.visibility = if (typing) View.VISIBLE else View.GONE
        for (row in listOf(normalRow, typingRow)) {
            row.translationY = 0f
            row.alpha = 1f
            if (row.visibility != View.VISIBLE) row.forgetPositions()
        }
        robotButton.translationX = 0f
    }

    private fun renderCorrect() {
        val appearance = BarVisibility.correctAppearance(state, transcribing())
        for (actions in listOf(menuActions, normalActions)) {
            val button = actions.correctButton
            appearance?.let {
                button.isEnabled = it.enabled
                button.alpha = it.alpha
                button.face = it.face
                button.contentDescription = context.getString(it.label)
                button.label = context.getString(R.string.action_pill_correction)
            }
            // Chargement du modèle ou correction : un faisceau balaie l'icône (le bouton reste désactivé). Correction réussie
            // (CORRECTING puis IDLE) : la coche se retrace avec un rebond. Annulation ou erreur : retour direct au repos.
            actions.correctIcon.setWorking(appearance?.loading == true, animate = isLaidOut)
            if (state == CorrectionBarState.IDLE && previousCorrectState == CorrectionBarState.CORRECTING) actions.correctIcon.playDone()
        }
        // Barre normale (champ vide) : rien à corriger, le bouton reste visible mais grisé et inactif.
        if (appearance == null) {
            normalActions.correctButton.isEnabled = false
            normalActions.correctButton.alpha = RESTRICTED_ALPHA
            normalActions.correctButton.face = R.color.surface_button
            normalActions.correctButton.contentDescription = context.getString(R.string.correction_button_idle)
        }
        previousCorrectState = state
        render()
    }

    /** Niveau sonore de l'écoute (0 à 1) : fait monter et descendre la jauge de la capsule du micro. */
    fun setVoiceLevel(level: Float) = micIcon.setLevel(level)

    private fun renderVoice() {
        val appearance = BarVisibility.voiceAppearance(voiceState, aiTranscriptionState)
        voiceButton.contentDescription = context.getString(appearance.label)
        voiceButton.background = voiceBackgrounds.getOrPut(appearance.background) { style.roundBackground(appearance.background) }
        voiceButton.alpha = appearance.alpha
        micIcon.setMode(MicIconMode.of(voiceState), animate = isLaidOut)
        applyAiTranscriptionAppearance() // Transcription IA est grisée pendant une saisie vocale
        renderCorrect() // Corriger est grisé pendant une transcription (rend la barre à la fin)
    }

    /** Niveau sonore de l'enregistrement (0 à 1) : fait monter et descendre les barres de l'icône Transcription IA. */
    fun setAiTranscriptionLevel(level: Float) {
        menuActions.transcriptionIcon.setLevel(level)
        normalActions.transcriptionIcon.setLevel(level)
    }

    private fun renderAiTranscription() {
        // Pendant le traitement, le bouton reste visible (en petit) même menu fermé : voir BarVisibility.actionButtons.
        // Vocal, Corriger et Chat sont grisés tant qu'une transcription IA est en cours (renderVoice rend la barre à la fin).
        renderVoice()
    }

    /** Face, opacité, description et icône de Transcription IA ; grisée et inactive pendant une saisie vocale. */
    private fun applyAiTranscriptionAppearance() {
        val description = context.getString(
            when (aiTranscriptionState) {
                AiTranscriptionBarState.IDLE -> R.string.ai_transcription_button_description
                AiTranscriptionBarState.RECORDING -> R.string.ai_transcription_button_recording
                AiTranscriptionBarState.PROCESSING -> R.string.ai_transcription_button_processing
                AiTranscriptionBarState.CANCELLING -> R.string.ai_transcription_button_cancelling
            },
        )
        val appearance = BarVisibility.aiTranscriptionAppearance(aiTranscriptionState, voiceState)
        val blocked = BarVisibility.aiTranscriptionBlocked(voiceState, aiTranscriptionState)
        for (actions in listOf(menuActions, normalActions)) {
            actions.transcriptionButton.contentDescription = description
            actions.transcriptionButton.face = appearance.face
            actions.transcriptionButton.alpha = appearance.alpha
            actions.transcriptionButton.isEnabled = !blocked
            actions.transcriptionIcon.setMode(WaveIconMode.of(aiTranscriptionState), animate = isLaidOut)
        }
    }

    /**
     * Story 10.1 : dans un champ sensible, les boutons d'action IA sont grisés par-dessus leur apparence normale (appelé en
     * dernier par chaque rendu, qui réécrit l'opacité). Hors champ sensible, « Chat » reprend son opacité pleine.
     */
    private fun applyFieldRestriction() {
        // Chat est grisé et inactif pendant une écoute, une transcription ou une correction : le moteur est partagé.
        val blocked = generateBlocked()
        // Un bouton qui joue son animation d'apparition (en attente de son tour, donc encore transparent) garde l'opacité que
        // l'animation lui donne : la lui rendre ici le ferait surgir, petit, avant les autres boutons du menu.
        for (button in listOf(menuActions.generateButton, normalActions.generateButton)) {
            button.isEnabled = !blocked
            if (!BarReveal.isEntering(button)) button.alpha = generateRestingAlpha()
        }
        if (aiActionsAllowed) return
        val restricted = listOf(voiceButton) + menuActions.views + normalActions.views
        restricted.filterNot { BarReveal.isEntering(it) }.forEach { it.alpha = RESTRICTED_ALPHA }
    }

    /** Transcription en cours (Vocal ou Transcription IA) : Vocal, Corriger et Chat sont grisés et inactifs. */
    private fun transcribing(): Boolean = BarVisibility.transcriptionInProgress(voiceState, aiTranscriptionState)

    /** Opacité de repos de Corriger : grisé tant que le champ est vide (état HIDDEN) ou qu'une transcription est en cours. */
    private fun correctRestingAlpha(): Float =
        BarVisibility.correctAppearance(state, transcribing())?.alpha ?: RESTRICTED_ALPHA

    /** Chat est grisé et inactif : voir [BarVisibility.generateBlocked]. */
    private fun generateBlocked(): Boolean = BarVisibility.generateBlocked(voiceState, state, aiTranscriptionState)

    /** Opacité de repos de Chat : grisé pendant une écoute, une transcription ou une correction. */
    private fun generateRestingAlpha(): Float = if (generateBlocked()) RESTRICTED_ALPHA else 1f

    /** Opacité de repos de Transcription IA : grisée pendant une saisie vocale. */
    private fun transcriptionRestingAlpha(): Float =
        BarVisibility.aiTranscriptionAppearance(aiTranscriptionState, voiceState).alpha

    private companion object {
        /** Durée d'affichage du tip « Swipe vers le bas » si l'utilisateur ne tape rien ensuite. */
        const val DIGIT_TIP_DURATION_MS = 5000L

        /** La rangée de saisie (ou la rangée normale) arrive juste après le départ de l'autre. */
        const val ROW_ENTER_DELAY_MS = 60L

        /** Part de la durée sur laquelle la rangée qui arrive se fond (entrée) ou disparaît (sortie). */
        const val ROW_FADE_IN = 0.3f
        const val ROW_FADE_OUT = 0.5f

        /** Marge latérale du rognage de transition : large, car seuls le haut et le bas de la barre doivent couper. */
        const val SIDE_CLIP_MARGIN_PX = 2000
    }
}
