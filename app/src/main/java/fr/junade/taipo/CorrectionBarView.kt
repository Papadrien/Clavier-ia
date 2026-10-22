package fr.junade.taipo

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
 * - **Normale** (champ vide) : Paramètres, Smart Clipboard (puce de collage à côté), Annuler ; Vocal fixé à droite.
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
    private val paddingH = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
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
    private val aiTranscriptionIcon = WaveIconDrawable(
        context.dimen(R.dimen.taipo_icon_size).toInt(),
        context.getColor(R.color.taipo_purple_light),
    )
    private val correctIcon = CorrectIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())
    private val voiceBackgrounds = HashMap<Int, RoundKeyDrawable>()

    /** Story 5.1 : bouton « Chat » (icône), qui bascule la barre en mode prompt. */
    private val generateButton = LabeledIconButton(context, style)
    private val correctButton = LabeledIconButton(context, style)

    /** Story 14.5 : transcription IA (enregistrement envoyé à un modèle Gemma 4). */
    internal val aiTranscriptionButton = LabeledIconButton(context, style)
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
            row.setPadding(paddingH, paddingV, paddingH, paddingV)
        }

        // Rangée normale : Paramètres, Smart Clipboard, puce de collage, Annuler (puis Vocal, posé par-dessus).
        normalRow.addView(normalButtons.settingsButton, roundParams())
        normalRow.addView(normalButtons.clipboardButton, roundParams())
        pasteChip.visibility = View.GONE
        normalRow.addView(pasteChip, SpaceBetweenRow.LayoutParams(LayoutParams.WRAP_CONTENT, barHeight, RowRole.SHRINK))
        normalRow.addView(normalButtons.undoButton, roundParams())

        // Rangée de saisie : Robot, [menu ouvert : Paramètres, Smart Clipboard, Annuler, Chat], bande, Corriger, Transcription.
        style.styleBarIconButton(robotButton, R.drawable.ic_robot)
        robotIcon = RobotIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt()).also {
            robotButton.setImageDrawable(it) // même tracé que ic_robot, animé quand le menu s'ouvre
        }
        robotButton.contentDescription = context.getString(R.string.actions_menu_description)
        robotButton.setOnClickListener {
            actionsMenuClickListener?.invoke()
            // Panneau Smart Clipboard ouvert : la barre est figée, le panneau se referme (la barre suit alors l'état réel).
            if (zoneState.clipboardPanelOpen) clipboardCloseListener?.invoke()
            zoneState.toggleMenu()
            render(reveal = true)
        }
        typingRow.addView(robotButton, roundParams())
        for (view in menuButtons.views) {
            view.visibility = View.GONE
            typingRow.addView(view, roundParams())
        }

        // Story 5.1 : « Chat » (mode prompt). Icône seule (bouton rond) ou pilule selon la largeur.
        style.styleRoundIconButton(generateButton, R.drawable.ic_rate_review)
        generateButton.contentDescription = context.getString(R.string.generate_button_description)
        generateButton.label = context.getString(R.string.action_pill_chat)
        generateButton.setOnClickListener {
            collapseMenu()
            generateListener?.invoke()
        }
        generateButton.visibility = View.GONE
        typingRow.addView(generateButton, roundParams())

        typingRow.addView(zoneView, SpaceBetweenRow.LayoutParams(0, barHeight, RowRole.FILL))

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
        typingRow.addView(correctButton, roundParams())

        // Story 14.5 : bouton « voix vers texte ». Le clic est relié par l'IME.
        style.styleRoundIconButton(aiTranscriptionButton, R.drawable.ic_speech_to_text)
        aiTranscriptionButton.setImageDrawable(aiTranscriptionIcon) // trois barres : onde au repos et à l'écoute, lignes pendant le traitement
        aiTranscriptionButton.contentDescription = context.getString(R.string.ai_transcription_button_description)
        aiTranscriptionButton.label = context.getString(R.string.action_pill_transcription)
        aiTranscriptionButton.visibility = View.GONE
        typingRow.addView(aiTranscriptionButton, roundParams())

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
                marginEnd = paddingH
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
     * fond de la barre garde toute la largeur). Vocal, toujours dernier, se cale sur le bord droit des touches (inset de touche).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val content = KeyboardWidth.forAvailableWidth(available.toFloat(), resources.displayMetrics.density)
        val side = paddingH + content.leftPx.toInt()
        val sideEnd = keyInset + content.leftPx.toInt()
        for (row in listOf(normalRow, typingRow)) {
            if (row.paddingLeft != side || row.paddingRight != sideEnd) row.setPadding(side, paddingV, sideEnd, paddingV)
        }
        val voiceParams = voiceButton.layoutParams as LayoutParams
        if (voiceParams.marginEnd != sideEnd) {
            voiceParams.marginEnd = sideEnd
            voiceButton.layoutParams = voiceParams
        }
        val showLabels = labelsWanted && labeledRowFits(available - side - sideEnd)
        generateButton.labeled = showLabels
        correctButton.labeled = showLabels
        aiTranscriptionButton.labeled = showLabels
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
        render()
    }

    /** Le champ contient du texte (barre de saisie) ou est vide (barre normale). */
    fun setFieldHasText(hasText: Boolean) {
        zoneState.onFieldTextChanged(hasText)
        render()
    }

    /** Story 2.1/2.4 : début de la frappe, retour aux suggestions de mots (le menu se referme). */
    fun collapseMenu() {
        zoneState.onTyping()
        render()
    }

    /**
     * Met à jour toute la barre d'après l'état courant : rangée affichée (avec sa transition), boutons visibles, robot actif,
     * bouton Smart Clipboard actif. [reveal] : l'appel vient d'un appui sur le robot ; les boutons qui apparaissent jaillissent
     * alors du robot en ressort, sinon (frappe, copie…) ils se fondent simplement ([BarReveal]).
     */
    private fun render(reveal: Boolean = false) {
        val mode = BarVisibility.mode(zoneState, state, aiTranscriptionState)
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
        zoneView.render(content.words, content.inline)
        setRevealed(zoneView, content.words || content.inline, order = 0, full = reveal, row = typingRow)
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
        setRevealed(generateButton, actions.generate, order = 3, full = reveal, row = typingRow, spring = spring)
        setRevealed(correctButton, actions.correct, order = 4, full = reveal, row = typingRow, spring = spring)
        setRevealed(
            aiTranscriptionButton, actions.aiTranscription && aiTranscriptionAvailable, order = 5, full = reveal,
            row = typingRow, spring = spring,
        )

        // Rangée normale : la puce de collage apparaît à côté du bouton Smart Clipboard.
        setRevealed(pasteChip, content.pasteChip, order = 0, full = false, row = normalRow)

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
        render()
    }

    /** Niveau sonore de l'écoute (0 à 1) : fait monter et descendre la jauge de la capsule du micro. */
    fun setVoiceLevel(level: Float) = micIcon.setLevel(level)

    private fun renderVoice() {
        val appearance = BarVisibility.voiceAppearance(voiceState)
        voiceButton.contentDescription = context.getString(appearance.label)
        voiceButton.background = voiceBackgrounds.getOrPut(appearance.background) { style.roundBackground(appearance.background) }
        voiceButton.alpha = appearance.alpha
        micIcon.setMode(MicIconMode.of(voiceState), animate = isLaidOut)
        render()
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
        // Pendant le traitement, le bouton reste visible (en petit) même menu fermé : voir BarVisibility.actionButtons.
        render()
    }

    /**
     * Story 10.1 : dans un champ sensible, les boutons d'action IA sont grisés par-dessus leur apparence normale (appelé en
     * dernier par chaque rendu, qui réécrit l'opacité). Hors champ sensible, « Chat » reprend son opacité pleine.
     */
    private fun applyFieldRestriction() {
        if (aiActionsAllowed) {
            generateButton.alpha = 1f
            return
        }
        listOf(correctButton, voiceButton, generateButton, aiTranscriptionButton).forEach { it.alpha = RESTRICTED_ALPHA }
    }

    private companion object {
        /** La rangée de saisie (ou la rangée normale) arrive juste après le départ de l'autre. */
        const val ROW_ENTER_DELAY_MS = 60L

        /** Part de la durée sur laquelle la rangée qui arrive se fond (entrée) ou disparaît (sortie). */
        const val ROW_FADE_IN = 0.3f
        const val ROW_FADE_OUT = 0.5f
    }
}
