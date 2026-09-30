package fr.junade.taipo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import fr.junade.taipo.ai.ChangedRange
import fr.junade.taipo.ai.CorrectedSentenceMemory
import fr.junade.taipo.ai.CorrectionDiff
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.CorrectionPlanner
import fr.junade.taipo.ai.TextBlock
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.ai.VoiceRecorder
import fr.junade.taipo.clipboard.ClipboardItems
import fr.junade.taipo.clipboard.ClipboardPanelView
import fr.junade.taipo.clipboard.ClipboardProvider
import fr.junade.taipo.clipboard.ClipboardPreview
import fr.junade.taipo.clipboard.ClipboardReader
import fr.junade.taipo.clipboard.ClipboardSuggestionState
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.emoji.EmojiPanelView
import fr.junade.taipo.emoji.EmojiText
import fr.junade.taipo.emoji.RecentEmojis
import fr.junade.taipo.suggestion.EmojiSuggesterLoader
import fr.junade.taipo.suggestion.SuggestionPolicy
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.VoiceModelPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.math.abs

class ClavierIme : InputMethodService() {

    private val controller = KeyboardController()
    private lateinit var keyboardView: KeyboardView
    private lateinit var correctionBar: CorrectionBarView

    // Story 1.15 : panneau emoji, superposé au clavier (même taille) tant qu'il est affiché.
    private lateinit var emojiPanel: EmojiPanelView

    // Story 2.1 : panneau Smart Clipboard (coquille), ouvert par le bouton « Presse-papiers » de la barre.
    private lateinit var clipboardPanel: ClipboardPanelView

    // Story 2.2 : puce de collage après une copie récente. Seule la dernière copie est gardée, en
    // mémoire vive ; l'expiration à 10 minutes (story 2.3) est planifiée avec [pasteExpiryRunnable].
    private val clipboardState = ClipboardSuggestionState(clock = { System.currentTimeMillis() })
    private val clipboardReader by lazy { ClipboardReader(applicationContext) { onClipboardChanged() } }
    private var currentPasteSuggestion: ClipboardSuggestionState.Suggestion? = null

    // Story 2.5 : éléments épinglés (base chiffrée séparée de celle du dictionnaire personnel).
    // Le dépôt est instancié dès la création du service : l'ouverture de la base (Keystore) est
    // asynchrone et doit être terminée avant la première ouverture du panneau.
    private val clipboardRepository by lazy { ClipboardProvider.repository(applicationContext) }
    private var clipboardPanelJob: Job? = null
    private val pasteExpiryRunnable = Runnable { refreshPasteSuggestion() }

    // Story 1.16 : emoji suggéré d'après le dernier mot (4e emplacement de la barre de suggestions).
    private val emojiSuggester by lazy { EmojiSuggesterLoader.get(applicationContext) }
    private var currentEmojiSuggestion: String? = null

    // Story 1.17 : mots suggérés d'après le mot en cours de frappe (3 premiers emplacements).
    private var currentWordSuggestions: List<WordSuggestion?> = emptyList()

    // Entrée de la dernière mise à jour des suggestions : évite de tout recalculer quand la même
    // mise à jour est demandée deux fois de suite (touche puis onUpdateSelection).
    private var lastSuggestionInput: SuggestionInput? = null

    private data class SuggestionInput(val text: String, val language: KeyboardLanguage, val available: Boolean)

    /** Faux dans les champs sans suggestions (mot de passe, e-mail, URL, nombre...), relu à chaque champ. */
    private var suggestionsAllowed = true

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val correctionEngine by lazy { CorrectionEngine(applicationContext) }
    private val modelPreferences by lazy { ModelPreferences(applicationContext) }
    private val keyboardPreferences by lazy { KeyboardPreferences(applicationContext) }
    private val hapticFeedback by lazy { HapticFeedbackPlayer(applicationContext) }

    // Story 1.5 : rangée de chiffres, relue à chaque ouverture de champ
    // (onStartInputView) pour prendre en compte un changement fait dans les paramètres.
    private var numberRowEnabled = false

    // Story 1.11 : niveau de retour haptique, relu de la même façon.
    private var hapticIntensity = HapticIntensity.DEFAULT
    private var keyboardHeight = KeyboardHeight.DEFAULT

    private val personalDictionary by lazy { PersonalDictionaryProvider.repository(applicationContext) }

    private val voiceEngine by lazy { VoiceEngine(applicationContext) }
    private val voiceModelPreferences by lazy { VoiceModelPreferences(applicationContext) }
    private var voiceRecorder: VoiceRecorder? = null

    private var correctionInProgress = false

    /**
     * Dernière autocorrection du dictionnaire, tant que rien d'autre n'a été fait depuis :
     * une suppression immédiate la rétablit (mot tel que tapé), comme sur les claviers usuels.
     */
    private class AppliedAutocorrection(val original: String, val corrected: String, val boundary: String = "")

    private var pendingAutocorrection: AppliedAutocorrection? = null

    // Phrases déjà corrigées (mémoire vive uniquement) : une phrase identique n'est pas renvoyée à l'IA.
    private val correctedSentences = CorrectedSentenceMemory()

    // État du surlignage temporaire après correction (décision 3.2). Seuls les mots corrigés
    // sont surlignés. Le surlignage disparaît dès la première action utilisateur ailleurs :
    // touche de ce clavier, bouton Corriger/Vocal, ou déplacement du curseur dans le champ.
    // Pour le retirer, on remplace la zone [premier mot corrigé → dernier mot corrigé] par le
    // même texte sans surlignage (voir removeCorrectionHighlight).
    private var correctionHighlightActive = false

    /** Texte brut (sans surlignage) de la zone à re-saisir : du premier au dernier mot corrigé. */
    private var correctionHighlightText = ""

    /**
     * Offset absolu, dans le champ, de la fin de la zone surlignée ; -1 si l'app ne permet pas
     * de le connaître (dans ce cas la zone re-saisie est tout le texte, le curseur est à la fin,
     * et le surlignage n'est retiré qu'à la frappe d'une touche).
     */
    private var correctionZoneEnd = -1

    /** Sélection (offsets absolus) laissée par la correction : si elle bouge, l'utilisateur a agi ailleurs. */
    private var correctionSelectionStart = -1
    private var correctionSelectionEnd = -1

    /** Dernière sélection (offsets absolus) connue, mise à jour par onUpdateSelection. */
    private var lastSelectionStart = -1
    private var lastSelectionEnd = -1

    /** Les mises à jour de sélection reçues avant cet instant sont l'écho de nos propres modifications. */
    private var ignoreSelectionUpdatesUntil = 0L

    // Saisie vocale (décision 6.1) : appui bref = bascule marche/arrêt,
    // appui long = écoute tant que le doigt reste sur le bouton.
    private var isRecording = false
    private var isHoldModeRecording = false
    private var longPressTriggered = false

    // Insertion progressive de l'hypothèse de transcription pendant
    // l'enregistrement (streaming sherpa-onnx) : on retient ce qui a déjà été
    // inséré pour pouvoir le remplacer entièrement à chaque nouvelle
    // hypothèse plutôt que de simplement l'ajouter à la suite.
    private var voicePartialJob: Job? = null
    private var insertedPartialText = ""
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isHoldModeRecording = true
        startVoiceRecording()
    }

    override fun onCreate() {
        super.onCreate()
        // Story 1.4 : l'ouverture de la base chiffrée du dictionnaire
        // personnel est asynchrone ; on la déclenche dès la création du
        // service pour qu'elle soit prête avant la première frappe.
        personalDictionary
        clipboardRepository
        numberRowEnabled = keyboardPreferences.isNumberRowEnabled
        hapticIntensity = keyboardPreferences.hapticIntensity
        keyboardHeight = keyboardPreferences.keyboardHeight
    }

    /**
     * Story 1.13 : pas de mode plein écran (zone d'édition « extraite ») en paysage : le champ de
     * l'application reste visible au-dessus du clavier, comme sur Gboard.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        keyboardView.heightScale = keyboardHeight.scale
        keyboardView.setOnKeyListener { key -> onKeyPressed(key) }
        keyboardView.setOnCursorMoveListener { steps -> onCursorMoved(steps) }
        keyboardView.setOnDeleteWordListener { onDeleteWord() }

        correctionBar = CorrectionBarView(this)
        correctionBar.setOnCorrectListener { onCorrectClicked() }
        correctionBar.voiceButton.setOnTouchListener { _, event -> onVoiceButtonTouch(event) }
        correctionBar.setOnEmojiSuggestionClickListener { onEmojiSuggestionTapped() }
        correctionBar.setOnClipboardClickListener { showClipboardPanel() }
        correctionBar.setOnPasteClickListener { onPasteTapped() }
        correctionBar.setOnWordSuggestionClickListener { suggestion -> onWordSuggestionTapped(suggestion) }

        emojiPanel = EmojiPanelView(this)
        emojiPanel.visibility = View.GONE
        emojiPanel.setOnEmojiSelectedListener { emoji -> onEmojiSelected(emoji) }
        emojiPanel.setOnBackspaceListener { onEmojiBackspace() }
        emojiPanel.setOnCloseListener { hideEmojiPanel() }

        clipboardPanel = ClipboardPanelView(this)
        clipboardPanel.visibility = View.GONE
        clipboardPanel.setOnCloseListener { hideClipboardPanel() }
        clipboardPanel.setOnPasteListener { item -> onClipboardItemTapped(item) }
        clipboardPanel.setOnPinListener { item -> onClipboardItemPin(item) }
        clipboardPanel.setOnDeleteListener { item -> onClipboardItemDelete(item) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Story 1.8 : la bulle d'accents des touches du haut est dessinée par le clavier
            // au-dessus de sa propre zone, par-dessus la barre d'actions.
            clipChildren = false
            addView(
                correctionBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // Le panneau emoji recouvre exactement le clavier (le clavier reste mesuré, seulement
            // masqué) : la hauteur ne change pas en basculant, rotation et réglage de hauteur compris.
            addView(
                FrameLayout(this@ClavierIme).apply {
                    clipChildren = false
                    addView(
                        keyboardView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        emojiPanel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    addView(
                        clipboardPanel,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        applyState()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!this::keyboardView.isInitialized) return
        // onCurrentInputMethodSubtypeChanged() ne se déclenche que sur un
        // *changement* de subtype : on resynchronise ici explicitement au cas
        // où le subtype actif (choisi avant l'affichage du clavier, ou par
        // défaut au premier lancement) n'a jamais généré de callback.
        numberRowEnabled = keyboardPreferences.isNumberRowEnabled
        hapticIntensity = keyboardPreferences.hapticIntensity
        keyboardHeight = keyboardPreferences.keyboardHeight
        keyboardView.heightScale = keyboardHeight.scale
        hideEmojiPanel(resync = false)
        hideClipboardPanel(resync = false)
        correctionBar.collapseMenu()
        suggestionsAllowed = SuggestionPolicy.allowsSuggestions(info?.inputType ?: 0)
        lastSuggestionInput = null
        controller.setLanguage(currentKeyboardLanguage())
        controller.reset()
        syncAutoCapitalization()
        clearHighlightState()
        applyState()
        // Story 2.2 : écoute des copies tant que le clavier est actif, et relecture à l'ouverture du
        // champ (le processus du clavier a pu être tué depuis la dernière copie).
        clipboardReader.start()
        readClipboard()
        updateCorrectionBarVisibility()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        controller.setLanguage(languageForSubtype(newSubtype))
        if (this::keyboardView.isInitialized) {
            syncAutoCapitalization()
            applyState()
        }
    }

    /** Langue active selon le subtype IME actuellement sélectionné par le système (décision 1.1). */
    private fun currentKeyboardLanguage(): KeyboardLanguage {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        return languageForSubtype(imm?.currentInputMethodSubtype)
    }

    @Suppress("DEPRECATION") // InputMethodSubtype.locale reste la source fiable pour un subtype déclaré via imeSubtypeLocale (voir method.xml).
    private fun languageForSubtype(subtype: InputMethodSubtype?): KeyboardLanguage {
        val locale = subtype?.locale.orEmpty()
        return if (locale.startsWith("en")) KeyboardLanguage.EN else KeyboardLanguage.FR
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Le panneau emoji est calé sur les dimensions du clavier : on revient aux touches après
        // une rotation ou un changement de taille de fenêtre, plutôt que d'afficher un panneau mal ajusté.
        if (this::emojiPanel.isInitialized && emojiPanel.visibility == View.VISIBLE) hideEmojiPanel()
        if (clipboardPanelVisible()) hideClipboardPanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (this::emojiPanel.isInitialized) hideEmojiPanel(resync = false)
        if (this::clipboardPanel.isInitialized) hideClipboardPanel(resync = false)
        clearHighlightState()
        clipboardReader.stop()
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        if (isRecording) {
            cancelVoiceRecording()
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
        if (correctionHighlightActive && SystemClock.uptimeMillis() >= ignoreSelectionUpdatesUntil) {
            val selectionUnchanged = correctionZoneEnd >= 0 &&
                newSelStart == correctionSelectionStart && newSelEnd == correctionSelectionEnd
            if (!selectionUnchanged) {
                // Le curseur a bougé (tap ailleurs, texte ajouté par une autre fonction) : le surlignage disparaît.
                if (correctionZoneEnd >= 0) removeCorrectionHighlight() else correctionHighlightActive = false
            }
        }
        if (!correctionInProgress && this::correctionBar.isInitialized) {
            updateCorrectionBarVisibility()
        }
        // Le curseur a pu bouger pour une raison hors de notre contrôle (tap de
        // l'utilisateur ailleurs dans le champ, action d'une autre fonctionnalité
        // comme la correction ou la saisie vocale) : la majuscule automatique
        // (story 1.2) doit rester synchronisée avec le nouveau contexte.
        if (this::keyboardView.isInitialized) {
            syncAutoCapitalization()
            applyState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        correctionEngine.close()
        voiceEngine.close()
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        clipboardReader.stop()
        clipboardState.clear()
        serviceJob.cancel()
    }

    private fun onKeyPressed(key: Key) {
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded()

        // Story 1.15 : la touche emoji ouvre le panneau, sans saisir de texte.
        if (key.action == KeyAction.Emoji) {
            pendingAutocorrection = null
            showEmojiPanel()
            return
        }

        // Suppression juste après une autocorrection : on annule la correction au lieu d'effacer un caractère.
        if (key.action == KeyAction.Backspace && undoLastAutocorrection()) {
            onUserTyped()
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
            return
        }
        pendingAutocorrection = null

        // Suppression avec une sélection : on efface la sélection, pas le caractère avant son début.
        if (key.action == KeyAction.Backspace && deleteSelectedText()) {
            onUserTyped()
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
            return
        }

        // Story 1.3 : dictionnaire local pour l'autocorrection (pas d'IA, pas
        // d'apprentissage auto) — le mot qui vient de se terminer est vérifié
        // juste avant que la touche de ponctuation/espace/entrée qui le
        // termine ne soit elle-même traitée.
        val autocorrection = if (isWordBoundaryKey(key)) applyDictionaryAutocorrection() else null

        // Double espace : l'espace précédent est remplacé par ". " (pas de sélection active).
        val doubleSpaceContext = if (key.action == KeyAction.Space && autocorrection == null && !hasSelection()) {
            currentInputConnection?.getTextBeforeCursor(2, 0)?.toString()
        } else null

        val result = controller.onKey(key, doubleSpaceContext)

        // La suppression précède l'insertion (double espace : on retire l'espace avant d'ajouter ". ").
        if (result.deleteBefore > 0) {
            if (key.action == KeyAction.Backspace) deleteLastCluster() else deleteBeforeCursor(result.deleteBefore)
        }
        result.commit?.let { text -> currentInputConnection?.commitText(text, 1) }
        // Entrée exclue : le retour à la ligne n'est pas un texte que l'on peut réinsérer à l'identique.
        val boundary = result.commit
        if (autocorrection != null && boundary != null) {
            pendingAutocorrection = AppliedAutocorrection(autocorrection.original, autocorrection.corrected, boundary)
        }
        if (result.isEnter) {
            pressEnter()
        }
        // On ne resynchronise qu'après une touche qui modifie réellement le
        // texte (lettre, espace, suppression, entrée). Un simple appui sur
        // Maj ne change pas le texte : le recalcul écraserait sinon aussitôt
        // le choix manuel de l'utilisateur de désactiver la majuscule
        // automatique pour la lettre suivante (cf. tests KeyboardController).
        val textChanged = result.commit != null || result.deleteBefore > 0 || result.isEnter
        if (textChanged) {
            onUserTyped() // stories 2.2 et 2.4 : la frappe écarte la puce et referme le menu
            syncAutoCapitalization()
        }
        applyState()
        updateCorrectionBarVisibility()
    }

    private fun deleteBeforeCursor(count: Int) {
        currentInputConnection?.deleteSurroundingText(count, 0)
    }

    /**
     * Retour arrière : supprime le dernier « caractère » avant le curseur en entier. Un emoji peut
     * occuper plusieurs caractères UTF-16 (paire de substitution, teinte, drapeau, séquence ZWJ...) :
     * en supprimer un seul en laisserait une moitié illisible. Pour une lettre, c'est 1 caractère.
     */
    private fun deleteLastCluster() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)
        val length = if (before.isNullOrEmpty()) 1 else EmojiText.lastClusterLength(before).coerceAtLeast(1)
        ic.deleteSurroundingText(length, 0)
    }

    // ------------------------------------------------------------------
    // Panneau emoji (story 1.15)
    // ------------------------------------------------------------------

    private fun showEmojiPanel() {
        emojiPanel.configure(keyboardView.rowHeightPx(), keyboardView.bottomInsetPx())
        emojiPanel.show(keyboardPreferences.recentEmojis)
        keyboardView.visibility = View.INVISIBLE
        emojiPanel.visibility = View.VISIBLE
        clearSuggestions()
    }

    /** Retour aux touches (bouton ABC, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    private fun hideEmojiPanel(resync: Boolean = true) {
        if (emojiPanel.visibility != View.VISIBLE) return
        emojiPanel.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        if (resync) {
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
        }
    }

    // ------------------------------------------------------------------
    // Panneau Smart Clipboard (stories 2.1 et 2.5)
    // ------------------------------------------------------------------

    private fun showClipboardPanel() {
        if (isRecording || correctionInProgress) return
        hapticFeedback.perform(hapticIntensity)
        correctionBar.collapseMenu() // story 2.4 : au retour des touches (ABC), mots ou puce, pas le menu ouvert
        hideEmojiPanel(resync = false)
        clipboardPanel.configure(keyboardView.rowHeightPx(), keyboardView.bottomInsetPx())
        keyboardView.visibility = View.INVISIBLE
        clipboardPanel.visibility = View.VISIBLE
        clearSuggestions()
        // Story 2.5 : les cartes suivent les éléments épinglés tant que le panneau est ouvert.
        refreshClipboardPanel()
        clipboardPanelJob?.cancel()
        clipboardPanelJob = serviceScope.launch {
            clipboardRepository.pinned.collect { refreshClipboardPanel() }
        }
    }

    /** Retour aux touches (bouton ABC, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    private fun hideClipboardPanel(resync: Boolean = true) {
        if (!clipboardPanelVisible()) return
        clipboardPanelJob?.cancel()
        clipboardPanelJob = null
        clipboardPanel.closeMenu()
        clipboardPanel.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        if (resync) {
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
        }
    }

    private fun clipboardPanelVisible(): Boolean =
        this::clipboardPanel.isInitialized && clipboardPanel.visibility == View.VISIBLE

    /** Cartes du panneau : dernière copie (même écartée ou collée) puis éléments épinglés. */
    private fun refreshClipboardPanel() {
        if (!clipboardPanelVisible()) return
        clipboardPanel.setItems(ClipboardItems.build(clipboardState.lastClip(), clipboardRepository.snapshot()))
    }

    /**
     * Appui sur une carte : colle son texte au curseur (`commitText` remplace une éventuelle
     * sélection) et revient aux touches. Coller la dernière copie compte comme collage de la puce.
     */
    private fun onClipboardItemTapped(item: ClipboardItems.Item) {
        val ic = currentInputConnection ?: return
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        ic.commitText(item.text, 1)
        if (item.isLastClip) clipboardState.onPasted()
        correctionBar.collapseMenu()
        hideClipboardPanel() // resynchronise majuscule, suggestions et barre
    }

    /** Menu d'appui long, « Épingler » : refus expliqués par un message (sensible, vide, trop long, doublon, plafond). */
    private fun onClipboardItemPin(item: ClipboardItems.Item) {
        ClipboardItems.pinRefusal(item.text, item.sensitive)?.let {
            Toast.makeText(this, pinMessage(it), Toast.LENGTH_SHORT).show()
            return
        }
        serviceScope.launch {
            try {
                val result = clipboardRepository.pin(item.text)
                Toast.makeText(this@ClavierIme, pinMessage(result), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.e(TAG, "Échec de l'épinglage", t)
                Toast.makeText(
                    this@ClavierIme,
                    getString(R.string.clipboard_pin_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun pinMessage(result: ClipboardItems.PinResult): String = getString(
        when (result) {
            ClipboardItems.PinResult.PINNED -> R.string.clipboard_pin_done
            ClipboardItems.PinResult.ALREADY_PINNED -> R.string.clipboard_pin_already
            ClipboardItems.PinResult.FULL -> R.string.clipboard_pin_full
            ClipboardItems.PinResult.TOO_LONG -> R.string.clipboard_pin_too_long
            ClipboardItems.PinResult.SENSITIVE -> R.string.clipboard_pin_sensitive
            ClipboardItems.PinResult.EMPTY -> R.string.clipboard_pin_empty
        },
    )

    /**
     * Menu d'appui long, « Supprimer » : un élément épinglé est retiré de la base ; la dernière copie
     * est retirée du panneau (et de la puce) jusqu'à la prochaine copie.
     */
    private fun onClipboardItemDelete(item: ClipboardItems.Item) {
        if (item.isLastClip) {
            clipboardState.onDeleted()
            refreshClipboardPanel()
        }
        val id = item.pinnedId ?: return
        serviceScope.launch {
            try {
                clipboardRepository.remove(id) // le panneau se met à jour via le flux des éléments épinglés
            } catch (t: Throwable) {
                Log.e(TAG, "Échec de la suppression d'un élément épinglé", t)
                Toast.makeText(
                    this@ClavierIme,
                    getString(R.string.clipboard_delete_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // Puce de collage après une copie récente (stories 2.2 et 2.3)
    // ------------------------------------------------------------------

    /**
     * Story 2.4 : point d'entrée unique pour tout texte saisi ou supprimé par l'utilisateur (touche,
     * emoji, suggestion, collage, suppression, dictée). Il écarte la puce de collage pour cette copie
     * (story 2.2) et referme le menu « ··· » : retour aux suggestions de mots dès la frappe. Les
     * touches sans texte (Maj, changement de disposition) et les simples déplacements du curseur ne
     * l'appellent pas. L'appelant met la barre à jour ensuite (updateCorrectionBarVisibility).
     */
    private fun onUserTyped() {
        clipboardState.onTyping()
        if (this::correctionBar.isInitialized) correctionBar.collapseMenu()
    }

    /** Une copie vient d'être faite (écoute active tant que le clavier est affiché). */
    private fun onClipboardChanged() {
        readClipboard()
        refreshPasteSuggestion()
        refreshClipboardPanel() // story 2.5 : une copie faite pendant que le panneau est ouvert s'y ajoute
    }

    private fun readClipboard() {
        val snapshot = clipboardReader.read()
        clipboardState.onClipRead(snapshot?.text, snapshot?.copiedAtMillis ?: 0L, snapshot?.sensitive ?: false)
    }

    /**
     * Rien n'est proposé pendant une dictée ou une correction, sous un panneau (emoji, presse-papiers),
     * ni dans les champs sans suggestions (mot de passe, e-mail, URL…).
     */
    private fun pasteSuggestionAllowed(): Boolean =
        suggestionsAllowed && !isRecording && !correctionInProgress && !emojiPanelVisible() && !clipboardPanelVisible()

    /** Met la puce de la barre d'accord avec l'état de la copie, et planifie son expiration (story 2.3). */
    private fun refreshPasteSuggestion() {
        if (!this::correctionBar.isInitialized) return
        val suggestion = if (pasteSuggestionAllowed()) clipboardState.suggestion() else null
        if (suggestion != currentPasteSuggestion) {
            currentPasteSuggestion = suggestion
            if (suggestion == null) {
                correctionBar.setPasteSuggestion(null, sensitive = false)
            } else {
                val preview = ClipboardPreview.forDisplay(suggestion.text, suggestion.sensitive)
                correctionBar.setPasteSuggestion(preview, suggestion.sensitive)
            }
        }
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        clipboardState.expiresInMillis()?.let { mainHandler.postDelayed(pasteExpiryRunnable, it + PASTE_EXPIRY_MARGIN_MS) }
    }

    /**
     * Appui sur la puce : colle le texte copié au curseur (`commitText` remplace une éventuelle
     * sélection), réinitialise l'autocorrection en attente et resynchronise la barre.
     */
    private fun onPasteTapped() {
        val suggestion = currentPasteSuggestion ?: return
        if (isRecording || correctionInProgress) return
        val ic = currentInputConnection ?: return
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        ic.commitText(suggestion.text, 1)
        clipboardState.onPasted()
        correctionBar.collapseMenu()
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    private fun onEmojiSelected(emoji: String) {
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        // commitText remplace une éventuelle sélection, comme n'importe quelle saisie.
        currentInputConnection?.commitText(emoji, 1)
        keyboardPreferences.recentEmojis = RecentEmojis.add(keyboardPreferences.recentEmojis, emoji)
        updateCorrectionBarVisibility()
    }

    private fun onEmojiBackspace() {
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        if (!deleteSelectedText()) deleteLastCluster()
        updateCorrectionBarVisibility()
    }

    /**
     * Story 1.7 : glissement sur la barre espace. Déplace le curseur de [steps] caractères
     * (négatif = vers la gauche) via des touches directionnelles, ce qui replie aussi une
     * éventuelle sélection et respecte les caractères composés du champ.
     */
    private fun onCursorMoved(steps: Int) {
        val ic = currentInputConnection ?: return
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        syncAutoCapitalization()
        applyState()
    }

    /**
     * Story 1.9 : glissement vers la gauche depuis la touche retour arrière. Supprime le mot entier
     * précédent (espaces/tabulations de fin comprises), ou, s'il n'y a pas de mot immédiatement
     * avant le curseur (ponctuation, ou aucun texte), un seul caractère pour que le geste ne reste
     * jamais sans effet.
     */
    private fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val toDelete = wordToDeleteBeforeCursor(before)
        if (toDelete.isEmpty()) return
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        ic.deleteSurroundingText(toDelete.length, 0)
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    /** Portion de [textBeforeCursor], en partant de la fin, à supprimer pour la story 1.9. */
    private fun wordToDeleteBeforeCursor(textBeforeCursor: String): String {
        if (textBeforeCursor.isEmpty()) return ""
        var start = textBeforeCursor.length
        while (start > 0 && (textBeforeCursor[start - 1] == ' ' || textBeforeCursor[start - 1] == '\t')) start--
        val afterTrailingSpace = start
        while (start > 0 && isWordChar(textBeforeCursor[start - 1])) start--
        return if (start < afterTrailingSpace) {
            textBeforeCursor.substring(start)
        } else if (afterTrailingSpace < textBeforeCursor.length) {
            textBeforeCursor.substring(afterTrailingSpace) // que des espaces/tabulations
        } else {
            textBeforeCursor.substring(textBeforeCursor.length - 1) // ni mot ni espace : un caractère
        }
    }

    /**
     * Recalcule la majuscule automatique (story 1.2) à partir du texte
     * réellement présent juste avant le curseur dans le champ actif, plutôt
     * que de se fier au seul historique des touches pressées sur ce clavier
     * (le champ peut déjà contenir du texte à l'ouverture, ou avoir été
     * modifié par la correction IA / la saisie vocale).
     */
    private fun syncAutoCapitalization() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        controller.applyTextContext(before)
        refreshSuggestions(before)
    }

    /**
     * Stories 1.16 et 1.17 : met à jour les suggestions de la barre (mots et emoji) d'après le texte
     * avant le curseur. Rien n'est proposé quand le panneau emoji est ouvert, pendant une dictée ou
     * une correction, avec une sélection, ou dans un champ sans suggestions.
     *
     * Les mots ne sont proposés que pendant la frappe d'un mot (le curseur suit une lettre, et la
     * lettre suivante, s'il y en a une, n'appartient pas au même mot) : après une espace ou une
     * ponctuation, il n'y a pas de prédiction du mot suivant (le dictionnaire est fixe, sans
     * apprentissage). L'emoji, lui, reste proposé après le mot suivi d'une espace (story 1.16).
     */
    private fun refreshSuggestions(textBeforeCursor: String) {
        if (!this::correctionBar.isInitialized) return
        val language = controller.state.language
        val available = suggestionsAllowed && !isRecording && !correctionInProgress &&
            !emojiPanelVisible() && !clipboardPanelVisible() && !hasSelection()
        val input = SuggestionInput(textBeforeCursor, language, available)
        if (input == lastSuggestionInput) return
        lastSuggestionInput = input

        val emoji = if (available) emojiSuggester.suggest(textBeforeCursor, language) else null
        currentEmojiSuggestion = emoji
        correctionBar.setEmojiSuggestion(emoji)

        val words = if (available) wordSuggestionsFor(textBeforeCursor, language) else emptyList()
        currentWordSuggestions = words
        correctionBar.setWordSuggestions(words)
    }

    private fun wordSuggestionsFor(textBeforeCursor: String, language: KeyboardLanguage): List<WordSuggestion?> {
        val typed = trailingWord(textBeforeCursor)
        if (typed.isEmpty()) return emptyList()
        // Curseur au milieu d'un mot : compléter le début du mot serait trompeur.
        val after = currentInputConnection?.getTextAfterCursor(1, 0)?.firstOrNull()
        if (after != null && isWordChar(after)) return emptyList()
        return DictionaryLoader.forLanguage(applicationContext, language)
            .suggestionSlotsFor(typed, personalWords = personalDictionary.snapshot())
    }

    /** Vide la bande de suggestions (panneau emoji, dictée ou correction en cours). */
    private fun clearSuggestions() {
        lastSuggestionInput = null
        currentEmojiSuggestion = null
        currentWordSuggestions = emptyList()
        if (!this::correctionBar.isInitialized) return
        correctionBar.setEmojiSuggestion(null)
        correctionBar.setWordSuggestions(emptyList())
        refreshPasteSuggestion() // dictée, correction ou panneau en cours : la puce disparaît aussi
    }

    /**
     * Touche sur un mot suggéré, suivi d'une espace :
     * - autocorrection (centre, gras) : remplace le mot tapé, comme le ferait l'espace ; une
     *   suppression immédiate rétablit le mot tapé ;
     * - mot tapé (entre guillemets) : conservé tel quel, sans correction ;
     * - complétion : remplace le mot en cours.
     */
    private fun onWordSuggestionTapped(suggestion: WordSuggestion) {
        if (suggestion !in currentWordSuggestions) return
        if (isRecording || correctionInProgress || hasSelection()) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val typed = trailingWord(before)
        if (typed.isEmpty()) return
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()

        ic.beginBatchEdit()
        if (suggestion.kind != WordSuggestion.Kind.TYPED) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(if (suggestion.kind == WordSuggestion.Kind.TYPED) " " else "${suggestion.text} ", 1)
        ic.endBatchEdit()

        pendingAutocorrection = if (suggestion.replacesOnSpace) {
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = " ")
        } else {
            null
        }
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    private fun emojiPanelVisible(): Boolean =
        this::emojiPanel.isInitialized && emojiPanel.visibility == View.VISIBLE

    /**
     * Touche sur l'emoji suggéré : il est inséré au curseur, précédé d'une espace si le mot vient
     * d'être tapé sans espace après lui. Il rejoint aussi les emojis récents.
     */
    private fun onEmojiSuggestionTapped() {
        val emoji = currentEmojiSuggestion ?: return
        if (isRecording || correctionInProgress || hasSelection()) return
        val ic = currentInputConnection ?: return
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        val lastChar = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
        val needsSpace = lastChar != null && lastChar.isLetterOrDigit()
        ic.commitText(if (needsSpace) " $emoji" else emoji, 1)
        keyboardPreferences.recentEmojis = RecentEmojis.add(keyboardPreferences.recentEmojis, emoji)
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    // ------------------------------------------------------------------
    // Dictionnaire local / autocorrection (story 1.3)
    // ------------------------------------------------------------------

    /** Touches qui terminent un mot et déclenchent donc une vérification dictionnaire. */
    private fun isWordBoundaryKey(key: Key): Boolean = when (val action = key.action) {
        KeyAction.Space, KeyAction.Enter -> true
        is KeyAction.TypeChar -> !action.char.isLetterOrDigit() && action.char != '\'' && action.char != '-'
        else -> false
    }

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '-'

    /** Dernier "mot" avant le curseur : lettres/apostrophes/traits d'union contigus en fin de texte. */
    private fun trailingWord(textBeforeCursor: String): String {
        var start = textBeforeCursor.length
        while (start > 0 && isWordChar(textBeforeCursor[start - 1])) start--
        return textBeforeCursor.substring(start)
    }

    private fun applyDictionaryAutocorrection(): AppliedAutocorrection? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val word = trailingWord(before)
        if (word.isEmpty()) return null

        val dictionary = DictionaryLoader.forLanguage(applicationContext, controller.state.language)
        // Story 1.4 : les mots du dictionnaire personnel ne sont jamais
        // corrigés et servent aussi de candidats de correction.
        val correction = dictionary.correctionFor(word, personalWords = personalDictionary.snapshot()) ?: return null
        if (correction == word) return null

        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(correction, 1)
        ic.endBatchEdit()
        return AppliedAutocorrection(original = word, corrected = correction)
    }

    private fun hasSelection(): Boolean {
        val ic = currentInputConnection ?: return false
        return !ic.getSelectedText(0).isNullOrEmpty() ||
            (lastSelectionStart >= 0 && lastSelectionEnd >= 0 && lastSelectionStart != lastSelectionEnd)
    }

    /** Efface le texte sélectionné dans le champ actif. Renvoie faux s'il n'y a pas de sélection. */
    private fun deleteSelectedText(): Boolean {
        val ic = currentInputConnection ?: return false
        if (!hasSelection()) return false
        ic.commitText("", 1) // remplace la sélection par du vide
        return true
    }

    /**
     * Rétablit le mot tel que tapé si la dernière action était une autocorrection
     * (mot corrigé + espace/ponctuation juste avant le curseur). Le séparateur est conservé :
     * le mot rétabli n'est donc pas recorrigé. Renvoie faux si rien n'a été annulé.
     */
    private fun undoLastAutocorrection(): Boolean {
        val pending = pendingAutocorrection ?: return false
        pendingAutocorrection = null
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val expected = pending.corrected + pending.boundary
        val before = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return false
        if (before != expected) return false // le texte ou le curseur a changé entre-temps

        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(pending.original + pending.boundary, 1)
        ic.endBatchEdit()
        return true
    }

    // ------------------------------------------------------------------
    // Correction IA (épopée 3)
    // ------------------------------------------------------------------

    private fun onCorrectClicked() {
        if (correctionInProgress) return
        clearHighlightIfNeeded()
        val ic = currentInputConnection ?: return

        // Texte sélectionné : seul ce texte est corrigé (sans le principe des phrases déjà corrigées).
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            correctSelection(ic, selected)
            return
        }

        val captured = captureFieldText(ic) ?: return
        if (captured.text.isBlank()) return

        val model = modelPreferences.activeModel()
        if (model == null) {
            Toast.makeText(this, getString(R.string.correction_no_model_selected), Toast.LENGTH_SHORT).show()
            return
        }

        // On ne renvoie à l'IA que les phrases pas encore corrigées (nouvelles ou modifiées).
        val blocks = CorrectionPlanner.blocksToCorrect(captured.text, correctedSentences::contains)
        if (blocks.isEmpty()) {
            Toast.makeText(this, getString(R.string.correction_nothing_new), Toast.LENGTH_SHORT).show()
            return
        }

        launchCorrection {
            val correctedBlocks = blocks.map { block ->
                val original = captured.text.substring(block.start, block.endExclusive)
                val corrected = correctionEngine.correct(model, original) {
                    correctionBar.state = CorrectionBarState.LOADING
                }
                corrected.ifBlank { original }
            }
            correctionBar.state = CorrectionBarState.CORRECTING
            applyCorrection(ic, captured, blocks, correctedBlocks)
        }
    }

    /** Corrige uniquement le texte sélectionné ; les espaces en bordure de sélection sont conservés. */
    private fun correctSelection(ic: InputConnection, selected: String) {
        if (selected.isBlank()) return
        val model = modelPreferences.activeModel()
        if (model == null) {
            Toast.makeText(this, getString(R.string.correction_no_model_selected), Toast.LENGTH_SHORT).show()
            return
        }
        val leading = selected.length - selected.trimStart().length
        val trailing = selected.length - selected.trimEnd().length
        val core = selected.substring(leading, selected.length - trailing)

        // Offset absolu du début de la sélection, s'il est connu (pour pouvoir retirer le surlignage plus tard).
        val captured = captureFieldText(ic)
        val absSelectionStart = if (captured != null && captured.startOffset >= 0) {
            captured.startOffset + captured.beforeCursor
        } else {
            -1
        }

        launchCorrection {
            val corrected = correctionEngine.correct(model, core) {
                correctionBar.state = CorrectionBarState.LOADING
            }.ifBlank { core }
            correctionBar.state = CorrectionBarState.CORRECTING
            applySelectionCorrection(ic, selected, leading, trailing, core, corrected, absSelectionStart)
        }
    }

    private fun applySelectionCorrection(
        ic: InputConnection,
        selected: String,
        leading: Int,
        trailing: Int,
        core: String,
        corrected: String,
        absSelectionStart: Int,
    ) {
        if (corrected == core) {
            clearHighlightState()
            return
        }
        // La sélection a pu changer pendant que le modèle travaillait : on ne remplace que si elle est identique.
        if (ic.getSelectedText(0)?.toString() != selected) {
            Toast.makeText(this, getString(R.string.correction_text_changed), Toast.LENGTH_SHORT).show()
            return
        }

        val ranges = CorrectionDiff.changedRanges(core, corrected).map {
            ChangedRange(it.start + leading, it.endExclusive + leading)
        }
        val newText = selected.substring(0, leading) + corrected + selected.substring(selected.length - trailing)

        clearHighlightState()
        ignoreSelectionUpdatesUntil = SystemClock.uptimeMillis() + SELF_EDIT_GRACE_MS
        ic.commitText(highlighted(newText, ranges), 1) // remplace la sélection ; le curseur se place après le texte corrigé
        if (ranges.isEmpty()) return // uniquement des suppressions : rien à surligner

        correctionHighlightActive = true
        if (absSelectionStart >= 0) {
            correctionHighlightText = newText.substring(ranges.first().start, ranges.last().endExclusive)
            correctionZoneEnd = absSelectionStart + ranges.last().endExclusive
            val cursor = absSelectionStart + newText.length
            correctionSelectionStart = cursor
            correctionSelectionEnd = cursor
            lastSelectionStart = cursor
            lastSelectionEnd = cursor
        } else {
            // Position inconnue : le curseur est juste après le texte corrigé, qu'on re-saisira en entier.
            correctionHighlightText = newText
            correctionZoneEnd = -1
        }
    }

    /** Lance une correction en arrière-plan avec indicateur de chargement et gestion d'erreur communs. */
    private fun launchCorrection(work: suspend () -> Unit) {
        correctionInProgress = true
        clearSuggestions()
        correctionBar.state = CorrectionBarState.LOADING
        serviceScope.launch {
            try {
                work()
            } catch (t: Throwable) {
                Log.e(TAG, "Échec de la correction IA", t)
                Toast.makeText(
                    this@ClavierIme,
                    getString(R.string.correction_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                correctionInProgress = false
                updateCorrectionBarVisibility()
                syncAutoCapitalization() // relance aussi la suggestion d'emoji, coupée pendant la correction
            }
        }
    }

    /** Texte capturé et sa longueur avant/après le curseur, pour pouvoir le remplacer précisément. */
    private data class CapturedText(
        val text: String,
        val beforeCursor: Int,
        val afterCursor: Int,
        /** Offset absolu du début du texte capturé dans le champ, -1 si inconnu. */
        val startOffset: Int = -1,
    )

    /**
     * Capture le texte accessible du champ (décision 3.1 : tout le texte du
     * champ accessible via InputConnection, ou le maximum accessible si
     * l'app ne donne pas accès à la totalité).
     */
    private fun captureFieldText(ic: InputConnection): CapturedText? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val extractedText = extracted?.text
        if (extractedText != null) {
            val text = extractedText.toString()
            val selStart = extracted.selectionStart.coerceIn(0, text.length)
            val selEnd = extracted.selectionEnd.coerceIn(0, text.length)
            return CapturedText(text, beforeCursor = selStart, afterCursor = text.length - selEnd, startOffset = extracted.startOffset)
        }
        // Repli si l'app ne fournit pas d'ExtractedText.
        val before = ic.getTextBeforeCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty() && after.isEmpty()) return null
        return CapturedText(before + after, beforeCursor = before.length, afterCursor = after.length)
    }

    /**
     * Remplace dans le champ uniquement la zone allant de la première à la dernière phrase
     * corrigée ; les phrases déjà corrigées situées avant, après ou entre les zones ne sont pas
     * touchées. Seuls les mots modifiés par l'IA sont surlignés.
     */
    private fun applyCorrection(
        ic: InputConnection,
        captured: CapturedText,
        blocks: List<TextBlock>,
        correctedBlocks: List<String>,
    ) {
        val spanStart = blocks.first().start
        val spanEnd = blocks.last().endExclusive
        val oldSpan = captured.text.substring(spanStart, spanEnd)

        // Reconstitue la zone : phrases corrigées + phrases déjà corrigées intercalées.
        val replacement = StringBuilder()
        val ranges = mutableListOf<ChangedRange>()
        var cursor = spanStart
        blocks.forEachIndexed { index, block ->
            replacement.append(captured.text, cursor, block.start)
            val original = captured.text.substring(block.start, block.endExclusive)
            val corrected = correctedBlocks[index]
            val offset = replacement.length
            CorrectionDiff.changedRanges(original, corrected).forEach {
                ranges += ChangedRange(it.start + offset, it.endExclusive + offset)
            }
            replacement.append(corrected)
            cursor = block.endExclusive
        }
        val newSpan = replacement.toString()

        // Ces phrases sont désormais corrigées, qu'elles aient changé ou non.
        correctedBlocks.forEach { correctedSentences.remember(it) }

        if (newSpan == oldSpan) {
            clearHighlightState()
            return
        }

        clearHighlightState()
        ignoreSelectionUpdatesUntil = SystemClock.uptimeMillis() + SELF_EDIT_GRACE_MS

        if (captured.startOffset >= 0) {
            replaceKnownSpan(ic, captured, spanStart, spanEnd, oldSpan, newSpan, ranges)
        } else {
            replaceWholeText(ic, captured, spanStart, spanEnd, newSpan, ranges)
        }
    }

    private fun highlighted(text: String, ranges: List<ChangedRange>, shift: Int = 0) = SpannableString(text).apply {
        ranges.forEach {
            setSpan(
                BackgroundColorSpan(HIGHLIGHT_COLOR),
                it.start + shift,
                it.endExclusive + shift,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Position exacte connue : remplace uniquement la zone, puis restaure la sélection de l'utilisateur. */
    private fun replaceKnownSpan(
        ic: InputConnection,
        captured: CapturedText,
        spanStart: Int,
        spanEnd: Int,
        oldSpan: String,
        newSpan: String,
        ranges: List<ChangedRange>,
    ) {
        val absStart = captured.startOffset + spanStart
        val absEnd = captured.startOffset + spanEnd
        val selStart = captured.startOffset + captured.beforeCursor
        val selEnd = captured.startOffset + captured.text.length - captured.afterCursor
        val delta = newSpan.length - oldSpan.length
        fun mapOffset(offset: Int) = when {
            offset >= absEnd -> offset + delta
            offset <= absStart -> offset
            else -> absStart + newSpan.length
        }

        var applied = false
        ic.beginBatchEdit()
        try {
            ic.setSelection(absEnd, absEnd)
            // Le texte a pu changer pendant que le modèle travaillait : on ne remplace que s'il est identique.
            if (ic.getTextBeforeCursor(oldSpan.length, 0)?.toString() == oldSpan) {
                ic.deleteSurroundingText(oldSpan.length, 0)
                ic.commitText(highlighted(newSpan, ranges), 1)
                applied = true
            }
            if (applied) {
                ic.setSelection(mapOffset(selStart), mapOffset(selEnd))
            } else {
                ic.setSelection(selStart, selEnd)
            }
        } finally {
            ic.endBatchEdit()
        }

        if (!applied) {
            Toast.makeText(this, getString(R.string.correction_text_changed), Toast.LENGTH_SHORT).show()
            return
        }
        if (ranges.isEmpty()) return // uniquement des suppressions : rien à surligner

        correctionHighlightText = newSpan.substring(ranges.first().start, ranges.last().endExclusive)
        correctionZoneEnd = absStart + ranges.last().endExclusive
        correctionSelectionStart = mapOffset(selStart)
        correctionSelectionEnd = mapOffset(selEnd)
        lastSelectionStart = correctionSelectionStart
        lastSelectionEnd = correctionSelectionEnd
        correctionHighlightActive = true
    }

    /** Position inconnue (l'app ne fournit pas ExtractedText) : remplace tout le texte accessible autour du curseur. */
    private fun replaceWholeText(
        ic: InputConnection,
        captured: CapturedText,
        spanStart: Int,
        spanEnd: Int,
        newSpan: String,
        ranges: List<ChangedRange>,
    ) {
        val newFull = captured.text.substring(0, spanStart) + newSpan + captured.text.substring(spanEnd)
        val beforeExpected = captured.text.substring(0, captured.beforeCursor)
        val afterExpected = captured.text.substring(captured.text.length - captured.afterCursor)

        var applied = false
        ic.beginBatchEdit()
        try {
            val before = ic.getTextBeforeCursor(captured.beforeCursor, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(captured.afterCursor, 0)?.toString().orEmpty()
            if (before == beforeExpected && after == afterExpected) {
                ic.deleteSurroundingText(captured.beforeCursor, captured.afterCursor)
                ic.commitText(highlighted(newFull, ranges, shift = spanStart), 1)
                applied = true
            }
        } finally {
            ic.endBatchEdit()
        }

        if (!applied) {
            Toast.makeText(this, getString(R.string.correction_text_changed), Toast.LENGTH_SHORT).show()
            return
        }
        if (ranges.isEmpty()) return

        correctionHighlightText = newFull
        correctionZoneEnd = -1
        correctionHighlightActive = true
    }

    /**
     * Retire le surlignage de correction : la zone surlignée est remplacée par le même texte
     * sans surlignage, puis la sélection de l'utilisateur est restaurée telle quelle. Si le texte
     * de la zone n'est plus celui attendu (modifié entre-temps), on ne touche à rien pour ne
     * pas risquer de modifier le mauvais texte.
     */
    private fun removeCorrectionHighlight() {
        if (!correctionHighlightActive) return
        val plainText = correctionHighlightText
        val zoneEnd = correctionZoneEnd
        val restoreStart = lastSelectionStart
        val restoreEnd = lastSelectionEnd
        clearHighlightState()

        val ic = currentInputConnection ?: return
        if (plainText.isEmpty()) return
        ignoreSelectionUpdatesUntil = SystemClock.uptimeMillis() + SELF_EDIT_GRACE_MS
        ic.beginBatchEdit()
        try {
            if (zoneEnd >= 0) ic.setSelection(zoneEnd, zoneEnd)
            val beforeCursor = ic.getTextBeforeCursor(plainText.length, 0)?.toString()
            if (beforeCursor == plainText) {
                ic.deleteSurroundingText(plainText.length, 0)
                ic.commitText(plainText, 1)
            }
            if (zoneEnd >= 0 && restoreStart >= 0 && restoreEnd >= 0) {
                ic.setSelection(restoreStart, restoreEnd)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Appelée avant toute action de l'utilisateur (touche, Corriger, Vocal) : le surlignage disparaît. */
    private fun clearHighlightIfNeeded() {
        removeCorrectionHighlight()
    }

    private fun clearHighlightState() {
        pendingAutocorrection = null
        correctionHighlightActive = false
        correctionHighlightText = ""
        correctionZoneEnd = -1
        correctionSelectionStart = -1
        correctionSelectionEnd = -1
    }

    /** Décision 3.3 : le bouton Corriger n'est visible que si le champ contient du texte. */
    private fun updateCorrectionBarVisibility() {
        refreshPasteSuggestion() // story 2.2 : la puce suit l'état courant (dictée, correction, frappe, expiration…)
        if (correctionInProgress) return
        val ic = currentInputConnection
        val hasText = ic != null &&
            (
                !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
                    !ic.getTextAfterCursor(1, 0).isNullOrEmpty() ||
                    !ic.getSelectedText(0).isNullOrEmpty() // tout le texte peut être sélectionné
                )
        correctionBar.setFieldHasText(hasText)
        correctionBar.state = if (hasText) CorrectionBarState.IDLE else CorrectionBarState.HIDDEN
    }

    // ------------------------------------------------------------------
    // Saisie vocale (épopée 6) — transcription brute, sans retravail LLM
    // (décision prototype du 24/09/2026)
    // ------------------------------------------------------------------

    private fun onVoiceButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isRecording) {
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                when {
                    longPressTriggered -> {
                        // Appui long relâché : fin de l'écoute (décision 6.1).
                        stopVoiceRecording()
                    }
                    isRecording -> {
                        // Deuxième appui bref pendant l'écoute : on arrête (décision 6.1).
                        stopVoiceRecording()
                    }
                    else -> {
                        // Premier appui bref : on démarre en mode bascule.
                        isHoldModeRecording = false
                        startVoiceRecording()
                    }
                }
                longPressTriggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    stopVoiceRecording()
                }
                longPressTriggered = false
            }
        }
        return true
    }

    private fun startVoiceRecording() {
        if (isRecording) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.voice_permission_denied), Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (!voiceModelPreferences.isComplete()) {
            Toast.makeText(this, getString(R.string.voice_no_model_selected), Toast.LENGTH_LONG).show()
            return
        }

        clearHighlightIfNeeded()
        isRecording = true
        clearSuggestions()
        insertedPartialText = ""
        correctionBar.voiceState = VoiceBarState.RECORDING
        serviceScope.launch {
            try {
                voiceEngine.ensureLoaded()
                val recorder = VoiceRecorder(voiceEngine, serviceScope)
                recorder.onSilenceTimeout = {
                    // Appelé depuis le thread d'enregistrement (décision 6.3, mode appui
                    // bref uniquement) : on revient sur le thread principal pour arrêter proprement.
                    mainHandler.post {
                        if (!isHoldModeRecording) {
                            stopVoiceRecording()
                        }
                    }
                }
                voiceRecorder = recorder
                recorder.start()
                // Insertion au fur et à mesure : chaque nouvelle hypothèse remplace
                // entièrement la précédente (le décodeur streaming peut réviser des
                // mots déjà affichés), le texte final restera inséré par
                // stopVoiceRecording()/cancelVoiceRecording() une fois l'écoute arrêtée.
                voicePartialJob = serviceScope.launch {
                    recorder.partialText.collect { partial -> applyVoicePartialText(partial) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Échec du démarrage de l'enregistrement vocal", t)
                isRecording = false
                correctionBar.voiceState = VoiceBarState.IDLE
                refreshPasteSuggestion()
                Toast.makeText(
                    this@ClavierIme,
                    getString(R.string.voice_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun stopVoiceRecording() {
        val recorder = voiceRecorder ?: run {
            isRecording = false
            correctionBar.voiceState = VoiceBarState.IDLE
            return
        }
        voiceRecorder = null
        isRecording = false
        correctionBar.voiceState = VoiceBarState.TRANSCRIBING
        serviceScope.launch {
            // On arrête d'abord de suivre les hypothèses partielles pour ne pas
            // risquer une mise à jour concurrente pendant qu'on insère le texte final.
            voicePartialJob?.cancelAndJoin()
            voicePartialJob = null
            try {
                val text = recorder.stopAndGetResult()
                // Décision 6.4 : insertion automatique au curseur, sans aperçu, sans
                // surlignage — le texte final remplace ici la dernière hypothèse
                // partielle déjà insérée pendant l'écoute (peut différer légèrement).
                replaceInsertedPartialText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "Échec de la transcription vocale", t)
                Toast.makeText(
                    this@ClavierIme,
                    getString(R.string.voice_error, t.message ?: t.javaClass.simpleName),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                correctionBar.voiceState = VoiceBarState.IDLE
                updateCorrectionBarVisibility()
                syncAutoCapitalization() // relance la suggestion d'emoji, coupée pendant l'écoute
            }
        }
    }

    /** Utilisé quand le clavier disparaît pendant un enregistrement (décision de sécurité, pas de fuite audio). */
    private fun cancelVoiceRecording() {
        val recorder = voiceRecorder ?: return
        voiceRecorder = null
        isRecording = false
        correctionBar.voiceState = VoiceBarState.IDLE
        serviceScope.launch {
            voicePartialJob?.cancelAndJoin()
            voicePartialJob = null
            try {
                recorder.stopAndGetResult()
            } catch (_: Throwable) {
                // Le clavier se ferme de toute façon : rien à faire de plus.
            }
            // Enregistrement annulé : on retire l'hypothèse partielle déjà insérée
            // pendant l'écoute (best effort — l'InputConnection peut ne plus être
            // valide si le champ a déjà perdu le focus à ce stade).
            replaceInsertedPartialText(null)
        }
    }

    /**
     * Remplace le texte de la dernière hypothèse partielle insérée
     * (voir [insertedPartialText]) par [finalText] ("null" pour un simple
     * retrait, sans rien insérer à la place — cas de l'annulation).
     */
    private fun replaceInsertedPartialText(finalText: String?) {
        val ic = currentInputConnection
        if (ic == null) {
            insertedPartialText = ""
            return
        }
        ic.beginBatchEdit()
        if (insertedPartialText.isNotEmpty()) {
            ic.deleteSurroundingText(insertedPartialText.length, 0)
        }
        if (!finalText.isNullOrBlank()) {
            ic.commitText(finalText, 1)
        }
        ic.endBatchEdit()
        if (!finalText.isNullOrBlank()) onUserTyped() // story 2.4 : texte final inséré par la dictée
        insertedPartialText = ""
    }

    /**
     * Insère l'hypothèse de transcription courante à la place de la
     * précédente pendant l'enregistrement. Le décodeur en streaming peut
     * réviser des mots déjà "affichés" au fil des mots suivants : on
     * remplace donc toujours l'insertion précédente en bloc plutôt que de
     * concaténer.
     */
    private fun applyVoicePartialText(partial: String) {
        if (partial == insertedPartialText) return
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (insertedPartialText.isNotEmpty()) {
            ic.deleteSurroundingText(insertedPartialText.length, 0)
        }
        if (partial.isNotEmpty()) {
            ic.commitText(partial, 1)
        }
        ic.endBatchEdit()
        if (partial.isNotEmpty()) onUserTyped() // story 2.4 : du texte a été inséré par la dictée
        insertedPartialText = partial
    }

    private fun pressEnter() {
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        val isMultiline = options and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        if (isMultiline || !hasAction) {
            currentInputConnection?.commitText("\n", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun applyState() {
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout, controller.state.language, numberRowEnabled)
        keyboardView.isShifted = controller.state.isShifted
    }

    companion object {
        private const val TAG = "ClavierIme"
        private const val MAX_ACCESSIBLE_CHARS = 10_000

        /** Nombre de caractères avant le curseur récupérés pour la majuscule automatique (1.2) et le dictionnaire local (1.3). */
        private const val TEXT_CONTEXT_LOOKBEHIND = 50

        /** #5A7FD4 (couleur accent existante du clavier) avec transparence (alpha 0x55). */
        private const val HIGHLIGHT_COLOR = 0x555A7FD4

        /** Délai pendant lequel les mises à jour de sélection sont considérées comme l'écho de nos propres modifications. */
        private const val SELF_EDIT_GRACE_MS = 500L

        /** Marge ajoutée au délai d'expiration de la puce, pour que l'horloge ait bien dépassé l'échéance. */
        private const val PASTE_EXPIRY_MARGIN_MS = 50L
    }
}
