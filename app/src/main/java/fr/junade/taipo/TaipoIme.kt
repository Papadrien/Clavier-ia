package fr.junade.taipo

import android.app.AlertDialog
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import android.util.Size
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import android.widget.Toast
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.ProtectedWords
import fr.junade.taipo.ai.VoiceField
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.emoji.EmojiCatalog
import fr.junade.taipo.emoji.EmojiPanelView
import fr.junade.taipo.emoji.EmojiText
import fr.junade.taipo.emoji.MessagingFieldPolicy
import fr.junade.taipo.emoji.RecentEmojiBarView
import fr.junade.taipo.emoji.RecentEmojis
import fr.junade.taipo.suggestion.NextWordRepository
import fr.junade.taipo.suggestion.SuggestionPolicy
import fr.junade.taipo.suggestion.WordText
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.model.ModelAvailability
import fr.junade.taipo.model.ModelPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class TaipoIme : InputMethodService() {

    private val controller = KeyboardController()
    // Vues du clavier : créées par ImeViewComposer (lot 2.3 de la revue), lues ici à travers lui.
    private val keyboardView: KeyboardView get() = viewComposer.keyboardView
    private val correctionBar: CorrectionBarView get() = viewComposer.correctionBar

    // Story 1.15 : panneau emoji, superposé au clavier (même taille) tant qu'il est affiché.
    private val emojiPanel: EmojiPanelView get() = viewComposer.emojiPanel

    // Smart Clipboard (puce de collage, panneau, épinglés) : voir ClipboardController (lot 2.3 de la revue).
    private val clipboard: ClipboardController by lazy {
        ClipboardController(
            this,
            serviceScope,
            mainHandler,
            object : ClipboardController.Host {
                override fun showMessage(message: String) = this@TaipoIme.showMessage(message)
                override fun inputConnection() = currentInputConnection
                override fun haptic() = hapticFeedback.perform(hapticIntensity)
                override fun clearHighlight() = clearHighlightIfNeeded()
                override fun clearPendingAutocorrection() {
                    pendingAutocorrection = null
                }
                override fun barReady(): Boolean = viewComposer.isComposed
                override fun collapseMenu() = correctionBar.collapseMenu()
                override fun setClipboardPanelOpen(open: Boolean) = correctionBar.setClipboardPanelOpen(open)
                override fun setPasteSuggestion(preview: String?, sensitive: Boolean) =
                    correctionBar.setPasteSuggestion(preview, sensitive)
                override fun isRecording(): Boolean = voice.isRecording
                override fun isCorrectionInProgress(): Boolean = correctionInProgress
                override fun isEmojiPanelVisible(): Boolean = emojiPanelVisible()
                override fun hideEmojiPanel() = this@TaipoIme.hideEmojiPanel(resync = false)
                override fun keyboardBottomInsetPx(): Int = keyboardView.bottomInsetPx()
                override fun setKeyboardVisible(visible: Boolean) {
                    keyboardView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                }
                override fun refreshRecentEmojiBar() = this@TaipoIme.refreshRecentEmojiBar()
                override fun clearSuggestions() = this@TaipoIme.clearSuggestions()
                override fun resyncKeyboard() {
                    syncAutoCapitalization()
                    applyState()
                    updateCorrectionBarVisibility()
                }
            },
        )
    }

    // Suggestions de mots et d'emoji, prédiction du mot suivant, apprentissage local : voir
    // SuggestionController (lot 2.3 de la revue).
    private val suggestions: SuggestionController by lazy {
        SuggestionController(
            this,
            serviceScope,
            object : SuggestionController.Host {
                override fun inputConnection() = currentInputConnection
                override fun promptActive(): Boolean = prompt.active
                override fun promptTextBeforeCursor(): String = prompt.buffer.textBeforeCursor
                override fun promptTextAfterCursor(): String = prompt.buffer.textAfterCursor
                override fun barReady(): Boolean = viewComposer.isComposed
                override fun language(): KeyboardLanguage = controller.state.language
                override fun isRecording(): Boolean = voice.isRecording
                override fun isCorrectionInProgress(): Boolean = correctionInProgress
                override fun isEmojiPanelVisible(): Boolean = emojiPanelVisible()
                override fun isClipboardPanelVisible(): Boolean = clipboard.isPanelVisible
                override fun hasSelection(): Boolean = this@TaipoIme.hasSelection()
                override fun isInputViewShown(): Boolean = this@TaipoIme.isInputViewShown
                override fun personalWords(): List<String> = personalDictionary.snapshot()
                override fun showEmoji(emoji: String?) {
                    // En mode prompt, la barre du haut est remplacée par celle du prompt : la bande s'affiche dans sa propre rangée.
                    if (prompt.active) prompt.setSuggestionEmoji(emoji) else correctionBar.setEmojiSuggestion(emoji)
                }
                override fun showWords(words: List<WordSuggestion?>) {
                    if (prompt.active) prompt.setSuggestionWords(words) else correctionBar.setWordSuggestions(words)
                }
                override fun clearBars() {
                    correctionBar.setEmojiSuggestion(null)
                    correctionBar.setWordSuggestions(emptyList())
                    prompt.clearSuggestionBar()
                    clipboard.refreshPasteSuggestion() // dictée, correction ou panneau en cours : la puce disparaît aussi
                }
            },
        )
    }

    private val nextWords: NextWordRepository get() = suggestions.nextWords
    private val currentWordSuggestions: List<WordSuggestion?> get() = suggestions.currentWordSuggestions
    private val currentEmojiSuggestion: String? get() = suggestions.currentEmojiSuggestion

    /** Pop-up d'information en cours (remplace les toasts), s'il y en a un. */
    private var messageDialog: AlertDialog? = null

    /** Barre des emojis récents (champs de messagerie), au-dessus de la barre du haut. */
    private val recentEmojiBar: RecentEmojiBarView get() = viewComposer.recentEmojiBar

    // Mode prompt (conversation, génération, zone de chat) : voir PromptModeController (lot 2.3 de la revue).
    private val prompt: PromptModeController by lazy {
        PromptModeController(
            this,
            serviceScope,
            mainHandler,
            llmHost,
            object : PromptModeController.Host {
                override fun showMessage(message: String) = this@TaipoIme.showMessage(message)
                override fun haptic() = hapticFeedback.perform(hapticIntensity)
                override fun onPromptCursorMoved() {
                    hapticFeedback.perform(hapticIntensity.cursorMoveFeedback())
                    syncAutoCapitalization()
                    applyState()
                }
                override fun isRecording(): Boolean = voice.isRecording
                override fun cancelVoice() {
                    if (voice.isRecording) voice.cancelRecording()
                }
                override fun isCorrectionInProgress(): Boolean = correctionInProgress
                override fun barReady(): Boolean = viewComposer.isComposed
                override fun setNormalBarVisible(visible: Boolean) {
                    correctionBar.visibility = if (visible) View.VISIBLE else View.GONE
                }
                override fun collapseMenu() = correctionBar.collapseMenu()
                override fun hideEmojiPanel() {
                    if (viewComposer.isComposed) this@TaipoIme.hideEmojiPanel(resync = false)
                }
                override fun hideClipboardPanel() = clipboard.hidePanel(resync = false)
                override fun refreshRecentEmojiBar() = this@TaipoIme.refreshRecentEmojiBar()
                override fun clearPendingAutocorrection() {
                    pendingAutocorrection = null
                }
                override fun clearSuggestions() = this@TaipoIme.clearSuggestions()
                override fun cancelDeleteSwipe() {
                    deleteSwipe.active = false
                }
                override fun syncAutoCapitalization() = this@TaipoIme.syncAutoCapitalization()
                override fun applyState() = this@TaipoIme.applyState()
                override fun updateCorrectionBarVisibility() = this@TaipoIme.updateCorrectionBarVisibility()
                override fun activeModel() = ModelAvailability.usable(modelPreferences.activeModel(), modelPreferences::isInstalled)
                override fun capturedFieldText(): String? = currentInputConnection?.let { correction.captureFieldText(it) }?.text
                override fun learnFromTyping(terminator: String) = this@TaipoIme.learnFromTyping(terminator)
                override fun insertGeneratedText(text: String): Boolean = this@TaipoIme.insertGeneratedText(text)
                override fun onWordSuggestionTapped(suggestion: WordSuggestion) =
                    this@TaipoIme.onWordSuggestionTapped(suggestion)
                override fun onEmojiSuggestionTapped() = this@TaipoIme.onEmojiSuggestionTapped()
            },
        )
    }

    /** Vrai quand le champ courant est celui d'une messagerie, relu à chaque champ. */
    private var messagingField = false

    /** Story 1.18 : type du champ courant (e-mail, URL, numérique...), fixé à chaque ouverture de champ. */
    private var fieldType = FieldType.TEXT

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Moteur LiteRT-LM unique, partagé par la correction et (épopée 5) la génération par prompt.
    private val llmHost by lazy { LlmEngineHost(applicationContext) }
    private val correctionEngine by lazy { CorrectionEngine(applicationContext, llmHost) }
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

    // Correction IA : voir CorrectionAiController (lot 2.3 de la revue).
    private val correction: CorrectionAiController by lazy {
        CorrectionAiController(
            this,
            serviceScope,
            { correctionEngine },
            object : CorrectionAiController.Host {
                override fun showMessage(message: String) = this@TaipoIme.showMessage(message)
                override fun inputConnection() = currentInputConnection
                override fun generationBusy(): Boolean = prompt.isGenerating // le moteur est pris par la génération
                override fun activeModel() = ModelAvailability.usable(modelPreferences.activeModel(), modelPreferences::isInstalled)
                override fun protectedWords(text: String): List<String> = protectedWordsIn(text)
                override fun setCorrectionBarState(state: CorrectionBarState) {
                    correctionBar.state = state
                }
                override fun clearSuggestions() = this@TaipoIme.clearSuggestions()
                override fun updateCorrectionBarVisibility() = this@TaipoIme.updateCorrectionBarVisibility()
                override fun syncAutoCapitalization() = this@TaipoIme.syncAutoCapitalization()
                override fun clearPendingAutocorrection() {
                    pendingAutocorrection = null
                }
            },
        )
    }

    private val voice: VoiceController by lazy {
        VoiceController(
            this,
            serviceScope,
            mainHandler,
            object : VoiceController.Host {
                override fun showMessage(message: String) = this@TaipoIme.showMessage(message)
                override fun generationBusy(): Boolean = prompt.generationBusy()
                override fun clearHighlight() = clearHighlightIfNeeded()
                override fun clearSuggestions() = this@TaipoIme.clearSuggestions()
                override fun refreshPasteSuggestion() = clipboard.refreshPasteSuggestion()
                override fun updateCorrectionBarVisibility() = this@TaipoIme.updateCorrectionBarVisibility()
                override fun syncAutoCapitalization() = this@TaipoIme.syncAutoCapitalization()
                override fun onUserTyped() = this@TaipoIme.onUserTyped()
                override fun setVoiceBarState(state: VoiceBarState) {
                    correctionBar.voiceState = state
                    prompt.setVoiceState(state) // le bouton micro de la barre du prompt suit le même état
                }
                override fun inputConnection() = currentInputConnection
                override fun promptActive(): Boolean = prompt.active
                override fun promptVoiceField(): VoiceField = prompt.voiceField
                override fun hasSelection(): Boolean = this@TaipoIme.hasSelection()
                override fun collapseSelectionBeforeInsert() {
                    if (hasSelection() && lastSelectionEnd >= 0) {
                        currentInputConnection?.setSelection(lastSelectionEnd, lastSelectionEnd)
                    }
                }
            },
        )
    }

    // Construction de la vue du clavier : voir ImeViewComposer (lot 2.3 de la revue).
    private val viewComposer: ImeViewComposer by lazy {
        ImeViewComposer(
            this,
            prompt,
            clipboard,
            object : ImeViewComposer.Host {
                override fun heightScale(): Float = keyboardHeight.scale
                override fun onKey(key: Key) = onKeyPressed(key)
                override fun onCursorMoved(steps: Int) = this@TaipoIme.onCursorMoved(steps)
                override fun onDeleteSwipeUpdate(words: Int) = this@TaipoIme.onDeleteSwipeUpdate(words)
                override fun onDeleteSwipeRelease() = this@TaipoIme.onDeleteSwipeRelease()
                override fun onDeleteSwipeCancel() = this@TaipoIme.onDeleteSwipeCancel()
                override fun onRecentEmojiClicked(emoji: String) = onRecentEmojiBarTapped(emoji)
                override fun onCorrectClicked() = correction.onCorrectClicked()
                override fun onUndoClicked() = correction.onUndoClicked()
                override fun onTopBarAction() = correction.dropUndo()
                override fun onVoiceTouch(event: MotionEvent): Boolean = voice.onButtonTouch(event)
                override fun onVoiceClick() = voice.onAccessibilityClick()
                override fun onEmojiSuggestionClicked() = onEmojiSuggestionTapped()
                override fun onWordSuggestionClicked(suggestion: WordSuggestion) = onWordSuggestionTapped(suggestion)
                override fun openAppHome() = this@TaipoIme.openAppHome()
                override fun onEmojiSelected(emoji: String) = this@TaipoIme.onEmojiSelected(emoji)
                override fun onEmojiBackspace() = this@TaipoIme.onEmojiBackspace()
                override fun onEmojiPanelClose() = hideEmojiPanel()
                override fun onViewsCreated() = applyState()
            },
        )
    }

    private val correctionInProgress: Boolean get() = correction.inProgress

    /**
     * Dernière autocorrection du dictionnaire, tant que rien d'autre n'a été fait depuis :
     * une suppression immédiate la rétablit (mot tel que tapé), comme sur les claviers usuels.
     */
    private class AppliedAutocorrection(val original: String, val corrected: String, val boundary: String = "")

    private var pendingAutocorrection: AppliedAutocorrection? = null

    // Sélection connue du champ (suivie par CorrectionAiController, qui s'en sert pour le surlignage).
    private val lastSelectionStart: Int get() = correction.lastSelectionStart
    private val lastSelectionEnd: Int get() = correction.lastSelectionEnd

    // Saisie vocale : voir VoiceController (lot 2.3 de la revue).
    private val isRecording: Boolean get() = voice.isRecording

    /** Numéro de la dernière réponse d'auto-remplissage en ligne : les vues d'une réponse périmée sont ignorées. */
    private var inlineGeneration = 0

    // Réponse d'auto-remplissage reçue avant la construction de la vue (InlineSuggestionsResponse, API 30+ : typée Any pour rester chargeable sous l'API 26).
    private var pendingInlineResponse: Any? = null


    override fun onCreate() {
        super.onCreate()
        // Story 1.4 : l'ouverture de la base chiffrée du dictionnaire
        // personnel est asynchrone ; on la déclenche dès la création du
        // service pour qu'elle soit prête avant la première frappe.
        personalDictionary
        nextWords // l'ouverture du modèle appris est asynchrone : on la déclenche dès la création
        clipboard.start() // bases du presse-papiers + retour de l'écran de modification (story 2.6)
        suggestions.preloadDictionaries()
        preloadEmojiCatalog()
        numberRowEnabled = keyboardPreferences.isNumberRowEnabled
        hapticIntensity = keyboardPreferences.hapticIntensity
        keyboardHeight = keyboardPreferences.keyboardHeight
    }

    /**
     * Lot 21 : le catalogue d'emojis (lecture + `hasGlyph` pour chaque emoji) est préparé en tâche de fond : sans cela, la
     * première ouverture du panneau Emoji le faisait sur le fil principal (saccade visible). En cas d'échec ici, le
     * panneau retentera au moment de l'ouverture, comme avant.
     */
    private fun preloadEmojiCatalog() {
        serviceScope.launch(Dispatchers.Default) {
            runCatching { EmojiCatalog.load(applicationContext) }
        }
    }

    /**
     * Story 1.13 : pas de mode plein écran (zone d'édition « extraite ») en paysage : le champ de
     * l'application reste visible au-dessus du clavier, comme sur Gboard.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View = viewComposer.compose()

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Lot 3.6 : section de trace pour mesurer l'ouverture du clavier (le `return` reste local à la fonction).
        // Même champ relancé par l'application (restarting) : l'annulation reste possible, le texte est retrouvé par son contenu.
        if (!restarting) correction.dropUndo(notify = false)
        traced(Sections.START_INPUT_VIEW) { startInputView(info) }
    }

    private fun startInputView(info: EditorInfo?) {
        if (!viewComposer.isComposed) return
        // onCurrentInputMethodSubtypeChanged() ne se déclenche que sur un
        // *changement* de subtype : on resynchronise ici explicitement au cas
        // où le subtype actif (choisi avant l'affichage du clavier, ou par
        // défaut au premier lancement) n'a jamais généré de callback.
        numberRowEnabled = keyboardPreferences.isNumberRowEnabled
        hapticIntensity = keyboardPreferences.hapticIntensity
        keyboardHeight = keyboardPreferences.keyboardHeight
        keyboardView.heightScale = keyboardHeight.scale
        hideEmojiPanel(resync = false)
        clipboard.hidePanel(resync = false)
        correctionBar.collapseMenu()
        suggestions.suggestionsAllowed = SuggestionPolicy.allowsSuggestions(info?.inputType ?: 0)
        // IME_FLAG_NO_PERSONALIZED_LEARNING : navigation privée, champ confidentiel... rien n'est appris ni proposé.
        suggestions.learningAllowed = suggestions.suggestionsAllowed &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        clipboard.pasteAllowedInField = SuggestionPolicy.allowsPasteSuggestion(info?.inputType ?: 0)
        fieldType = FieldType.of(info?.inputType ?: 0)
        messagingField = MessagingFieldPolicy.isMessagingField(info?.packageName, info?.inputType ?: 0)
        controller.setAutoCapitalization(fieldType.autoCapitalizes)
        suggestions.invalidate()
        controller.setLanguage(currentKeyboardLanguage())
        controller.reset()
        syncAutoCapitalization()
        correction.clearHighlightState()
        applyState()
        // Story 2.2 : écoute des copies tant que le clavier est actif, et relecture à l'ouverture du
        // champ (le processus du clavier a pu être tué depuis la dernière copie).
        clipboard.startListening()
        // Diagnostic temporaire (puce de collage absente dans certaines applis) : aucun texte copié n'est journalisé.
        AppLog.d(
            TAG,
            "puce collage: pkg=${info?.packageName} inputType=0x${Integer.toHexString(info?.inputType ?: 0)} " +
                "suggestionsAllowed=${suggestions.suggestionsAllowed} pasteAllowedInField=${clipboard.pasteAllowedInField} " +
                "copieLue=${clipboard.hasLastClip} puce=${clipboard.hasSuggestion}",
        )
        refreshRecentEmojiBar()
        updateCorrectionBarVisibility()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) applyPendingInlineResponse()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        controller.setLanguage(languageForSubtype(newSubtype))
        if (viewComposer.isComposed) {
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
        if (viewComposer.isComposed && emojiPanel.visibility == View.VISIBLE) hideEmojiPanel()
        if (clipboard.isPanelVisible) clipboard.hidePanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        nextWords.flush()
        clearInlineSuggestions()
        dismissMessage()
        if (viewComposer.isComposed) hideEmojiPanel(resync = false)
        clipboard.hidePanel(resync = false)
        correction.clearHighlightState()
        if (finishingInput) correction.dropUndo(notify = false)
        clipboard.stopListening()
        if (isRecording) {
            voice.cancelRecording()
        }
        // Changement de champ : la génération en cours est interrompue (partiel figé), la conversation reste.
        prompt.stopGeneration()
        prompt.exit(resync = false)
    }

    /**
     * Fermeture du clavier (fenêtre masquée) : la conversation du mode prompt est effacée et la
     * génération en cours interrompue (décision 14). Un simple changement de champ, clavier
     * affiché, ne masque pas la fenêtre et garde donc la conversation (comportement validé sur appareil
     * le 03/10/2026).
     */
    override fun onWindowHidden() {
        super.onWindowHidden()
        prompt.resetConversation()
    }

    // ------------------------------------------------------------------
    // Auto-remplissage en ligne (gestionnaire de mots de passe : Bitwarden, etc.)
    // ------------------------------------------------------------------

    /**
     * Demande au service d'auto-remplissage actif (Bitwarden…) de fournir ses suggestions sous forme
     * de vues à afficher dans la barre du clavier, à la place des suggestions de frappe. Le service
     * doit avoir l'auto-remplissage « en ligne » activé dans ses propres réglages.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        val density = resources.displayMetrics.density
        val height = (INLINE_SUGGESTION_HEIGHT_DP * density).toInt()
        val styles = UiVersions.newStylesBuilder()
            .addStyle(buildInlineSuggestionStyle(this))
            .build()
        val spec = InlinePresentationSpec.Builder(
            Size((INLINE_SUGGESTION_MIN_WIDTH_DP * density).toInt(), height),
            Size((INLINE_SUGGESTION_MAX_WIDTH_DP * density).toInt(), height),
        ).setStyle(styles).build()
        AppLog.d(TAG, "autofill en ligne : demande envoyée au système (hauteur ${height}px)")
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_SUGGESTION_MAX_COUNT)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        AppLog.d(
            TAG,
            "autofill en ligne : réponse de ${response.inlineSuggestions.size} suggestion(s), vue du clavier prête=${viewComposer.isComposed}",
        )
        if (!viewComposer.isComposed) {
            // Première ouverture : la réponse peut arriver avant la construction de la vue du clavier. Elle est
            // gardée et appliquée à l'ouverture du champ (le système ne la renvoie pas de lui-même).
            pendingInlineResponse = response
            return response.inlineSuggestions.isNotEmpty()
        }
        pendingInlineResponse = null
        return showInlineSuggestions(response)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        val generation = ++inlineGeneration
        if (suggestions.isEmpty()) {
            clearInlineSuggestions()
            return false
        }
        val slots = arrayOfNulls<View>(suggestions.size)
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        suggestions.forEachIndexed { index, suggestion ->
            try {
                suggestion.inflate(this, Size(wrap, wrap), mainExecutor) { view ->
                    // Réponse périodiquement remplacée par une plus récente : on ignore les vues en retard.
                    if (generation != inlineGeneration || !viewComposer.isComposed) return@inflate
                    if (view == null) {
                        AppLog.w(TAG, "autofill en ligne : vue $index non rendue par le service d'auto-remplissage")
                        return@inflate
                    }
                    slots[index] = view
                    correctionBar.setInlineSuggestions(slots.filterNotNull())
                    if (BuildConfig.DEBUG) {
                        mainHandler.postDelayed(
                            { AppLog.d(TAG, "autofill en ligne : vue $index ${view.width}x${view.height} px, affichée=${view.isShown}") },
                            INLINE_LAYOUT_LOG_DELAY_MS,
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                AppLog.e(TAG, "autofill en ligne : taille refusée pour la suggestion $index", e)
            }
        }
        return true
    }

    /** Applique la réponse d'auto-remplissage arrivée avant la construction de la vue du clavier, s'il y en a une. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyPendingInlineResponse() {
        val pending = pendingInlineResponse as? InlineSuggestionsResponse ?: return
        pendingInlineResponse = null
        AppLog.d(TAG, "autofill en ligne : application de la réponse reçue avant la vue du clavier")
        showInlineSuggestions(pending)
    }

    /** Retire les suggestions d'auto-remplissage : la barre retrouve ses suggestions habituelles. */
    private fun clearInlineSuggestions() {
        inlineGeneration++
        pendingInlineResponse = null
        if (viewComposer.isComposed) correctionBar.setInlineSuggestions(emptyList())
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
        // Mode prompt : un tap de l'utilisateur dans le champ de l'application (curseur ou sélection déplacés,
        // hors écho de nos propres insertions comme « Ajouter le texte ») ferme le mode et lui rend le champ.
        val selectionMoved = newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (viewComposer.isComposed && prompt.active && selectionMoved && !correction.isSelfEditEcho()) {
            prompt.onFieldTapped()
        }
        correction.onSelectionUpdated(newSelStart, newSelEnd)
        if (!correctionInProgress && viewComposer.isComposed) {
            updateCorrectionBarVisibility()
        }
        // Le curseur a pu bouger pour une raison hors de notre contrôle (tap de
        // l'utilisateur ailleurs dans le champ, action d'une autre fonctionnalité
        // comme la correction ou la saisie vocale) : la majuscule automatique
        // (story 1.2) doit rester synchronisée avec le nouveau contexte.
        if (viewComposer.isComposed) {
            syncAutoCapitalization()
            applyState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Écriture terminée avant de rendre la main (lot 2.6 de la revue) : pas de launch asynchrone ici.
        nextWords.flushBlocking()
        dismissMessage()
        llmHost.close()
        voice.release()
        clipboard.release()
        serviceJob.cancel()
    }

    /**
     * Affiche [message] dans un pop-up avec un bouton OK (les toasts passaient inaperçus). Le pop-up
     * est rattaché à la fenêtre du clavier, qui garde la saisie en cours. Si le clavier n'est plus
     * affiché (pas de fenêtre pour accrocher le pop-up), repli sur un toast.
     */
    private fun showMessage(message: String) {
        val token = if (viewComposer.isComposed && keyboardView.isAttachedToWindow) keyboardView.windowToken else null
        if (token == null) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        dismissMessage()
        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setMessage(message)
            .setPositiveButton(R.string.message_dialog_ok, null)
            .create()
        dialog.window?.let { window ->
            val params = window.attributes
            params.token = token
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        messageDialog = dialog
        try {
            dialog.show()
            dialog.window?.decorView?.applyTaipoFontToTree()
        } catch (t: Throwable) {
            messageDialog = null
            AppLog.e(TAG, "Impossible d'afficher le pop-up", t)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun dismissMessage() {
        messageDialog?.dismiss()
        messageDialog = null
    }

    private fun onKeyPressed(key: Key) {
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded()

        // Story 5.1 : en mode prompt, tout passe par le tampon du prompt, avec les mêmes traitements que
        // le champ de l'application (autocorrection et annulation, double espace, apprentissage,
        // suggestions) ; rien n'est écrit dans le champ de l'application.
        if (prompt.active) {
            onPromptKeyPressed(key)
            return
        }

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
        val autocorrection = if (isWordBoundaryKey(key) && autocorrectionAllowed()) applyDictionaryAutocorrection() else null

        // Double espace : l'espace précédent est remplacé par ". " (pas de sélection active).
        val doubleSpaceContext = if (key.action == KeyAction.Space && autocorrection == null && !hasSelection() &&
            fieldType.doubleSpacePeriod
        ) {
            currentInputConnection?.getTextBeforeCursor(2, 0)?.toString()
        } else null

        val result = controller.onKey(key, doubleSpaceContext, SystemClock.uptimeMillis())

        // La suppression précède l'insertion (double espace : on retire l'espace avant d'ajouter ". ").
        if (result.deleteBefore > 0) {
            if (key.action == KeyAction.Backspace) deleteLastCluster() else deleteBeforeCursor(result.deleteBefore)
        }
        // Mot corrigé par l'espace alors qu'une espace suit déjà le curseur : on la remplace, pas de double espace.
        if (autocorrection != null && key.action == KeyAction.Space && result.commit == " ") {
            val ic = currentInputConnection
            if (ic != null && ic.getTextAfterCursor(1, 0)?.toString() == " ") ic.deleteSurroundingText(0, 1)
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
            // Un mot vient d'être terminé par une espace (pas une conversion en « . ») ou un retour à la ligne.
            if ((key.action == KeyAction.Space && result.deleteBefore == 0) || key.action == KeyAction.Enter) learnFromTyping()
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
        refreshRecentEmojiBar()
        clearSuggestions()
    }

    /** Retour aux touches (bouton ABC, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    private fun hideEmojiPanel(resync: Boolean = true) {
        if (emojiPanel.visibility != View.VISIBLE) return
        emojiPanel.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        refreshRecentEmojiBar()
        if (resync) {
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
        }
    }

    // ------------------------------------------------------------------
    // Panneau Smart Clipboard (stories 2.1 et 2.5)
    // ------------------------------------------------------------------

    /** Roue crantée de la barre : ouvre la page d'accueil de l'application. */
    private fun openAppHome() {
        hapticFeedback.perform(hapticIntensity)
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            AppLog.e(TAG, "Échec de l'ouverture de la page d'accueil", t)
        }
    }

    /**
     * Story 2.4 : point d'entrée unique pour tout texte saisi ou supprimé par l'utilisateur (touche,
     * emoji, suggestion, collage, suppression, dictée). Il écarte la puce de collage pour cette copie
     * (story 2.2) et referme le menu « ··· » : retour aux suggestions de mots dès la frappe. Les
     * touches sans texte (Maj, changement de disposition) et les simples déplacements du curseur ne
     * l'appellent pas. L'appelant met la barre à jour ensuite (updateCorrectionBarVisibility).
     */
    private fun onUserTyped() {
        clipboard.onTyping()
        if (viewComposer.isComposed) correctionBar.collapseMenu()
    }

    private fun onEmojiSelected(emoji: String) {
        hapticFeedback.perform(hapticIntensity)
        if (prompt.active) {
            pendingAutocorrection = null
            prompt.buffer.insert(emoji)
            keyboardPreferences.recentEmojis = RecentEmojis.add(keyboardPreferences.recentEmojis, emoji)
            learnFromTyping()
            prompt.refreshInput()
            return
        }
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        // commitText remplace une éventuelle sélection, comme n'importe quelle saisie.
        currentInputConnection?.commitText(emoji, 1)
        keyboardPreferences.recentEmojis = RecentEmojis.add(keyboardPreferences.recentEmojis, emoji)
        learnFromTyping()
        updateCorrectionBarVisibility()
    }

    /**
     * Un emoji de la barre des récents est inséré au curseur (il remplace une éventuelle sélection) et
     * rejoint la tête des récents ; la barre garde son ordre jusqu'à la prochaine ouverture de champ
     * ou du panneau, pour ne pas bouger sous le doigt.
     */
    private fun onRecentEmojiBarTapped(emoji: String) {
        if (isRecording || correctionInProgress) return
        onEmojiSelected(emoji)
        syncAutoCapitalization()
        applyState()
    }

    /**
     * La barre n'est affichée que dans un champ de messagerie, avec des récents, hors mode prompt. Quand un panneau (emoji,
     * Smart Clipboard) la masque, elle reste INVISIBLE et garde sa place : la hauteur du clavier ne varie pas, donc l'application
     * n'a pas à rescroller (sinon le dernier message ou le curseur passe sous la barre). GONE seulement hors messagerie / mode prompt.
     */
    private fun refreshRecentEmojiBar() {
        if (!viewComposer.isComposed) return
        val recents = keyboardPreferences.recentEmojis
        // Décision 12 : masquée en mode prompt, quelle que soit la règle habituelle.
        val reserved = !prompt.active && messagingField && recents.isNotEmpty()
        val visible = reserved &&
            !(viewComposer.isComposed && emojiPanel.visibility == View.VISIBLE) &&
            !clipboard.isPanelVisible
        if (visible) recentEmojiBar.setEmojis(recents)
        recentEmojiBar.visibility = when {
            visible -> View.VISIBLE
            reserved -> View.INVISIBLE
            else -> View.GONE
        }
    }

    // ------------------------------------------------------------------
    // Mode prompt : traitement des touches (story 5.1) ; le reste est dans PromptModeController
    // ------------------------------------------------------------------

    /**
     * Une touche pressée en mode prompt : mêmes traitements que dans le champ de l'application, appliqués
     * au tampon du prompt au lieu de l'InputConnection : annulation d'autocorrection (suppression juste
     * après une correction), autocorrection du dictionnaire à la fin d'un mot, double espace, apprentissage
     * et suggestions. Le prompt est un champ de texte libre : ces règles ne dépendent pas du type du champ
     * de l'application (seul l'apprentissage suit `learningAllowed`, pour respecter un mode privé).
     */
    private fun onPromptKeyPressed(key: Key) {
        if (key.action == KeyAction.Emoji) {
            pendingAutocorrection = null
            showEmojiPanel()
            return
        }
        // Suppression juste après une autocorrection : on rétablit le mot tapé au lieu d'effacer un caractère.
        if (key.action == KeyAction.Backspace && undoLastPromptAutocorrection()) {
            afterPromptEdit()
            return
        }
        pendingAutocorrection = null

        // Le mot qui vient de se terminer est vérifié juste avant que la touche qui le termine soit traitée.
        val autocorrection = if (isWordBoundaryKey(key)) applyPromptAutocorrection() else null

        // Double espace : l'espace précédente devient « . » (la correction d'un mot, elle, passe avant).
        val doubleSpaceContext = if (key.action == KeyAction.Space && autocorrection == null &&
            FieldType.TEXT.doubleSpacePeriod
        ) {
            prompt.buffer.textBeforeCursor.takeLast(2)
        } else null

        // Le contrôleur gère aussi Maj, le verrouillage des majuscules et la bascule des symboles.
        val result = controller.onKey(key, doubleSpaceContext, SystemClock.uptimeMillis())
        // La suppression précède l'insertion (double espace : on retire l'espace avant d'ajouter ". ").
        if (result.deleteBefore > 0) {
            if (key.action == KeyAction.Backspace) prompt.buffer.backspace() else prompt.buffer.deleteBefore(result.deleteBefore)
        }
        // Mot corrigé par l'espace alors qu'une espace suit déjà le curseur : on la remplace, pas de double espace.
        if (autocorrection != null && key.action == KeyAction.Space && result.commit == " " &&
            prompt.buffer.textAfterCursor.startsWith(" ")
        ) {
            prompt.buffer.deleteAfter(1)
        }
        result.commit?.let { prompt.buffer.insert(it) }
        val boundary = result.commit
        if (autocorrection != null && boundary != null) {
            pendingAutocorrection = AppliedAutocorrection(autocorrection.original, autocorrection.corrected, boundary)
        }
        // Un mot vient d'être terminé par une espace (pas une conversion en « . »).
        if (key.action == KeyAction.Space && result.deleteBefore == 0) learnFromTyping()

        prompt.refreshInput()
        // Un seul recalcul : seule une touche qui change le texte réinitialise le choix manuel de Maj.
        if (result.commit != null || result.deleteBefore > 0) syncAutoCapitalization()
        applyState()
        // Entrée envoie le prompt (la pilule est sur une seule ligne) : décision provisoire, point ouvert 6.2.
        if (result.isEnter) prompt.requestSend()
    }

    /** Après une modification du prompt hors frappe (suggestion touchée, correction annulée) : pilule, majuscule, suggestions, touches. */
    private fun afterPromptEdit() {
        prompt.refreshInput()
        syncAutoCapitalization()
        applyState()
    }

    /** Autocorrection du dictionnaire sur le mot qui précède le curseur du prompt (même règle que dans le champ). */
    private fun applyPromptAutocorrection(): AppliedAutocorrection? {
        val before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
        val edit = suggestions.autocorrectionFor(before, allowContextualRetro(before)) ?: return null
        prompt.buffer.deleteBefore(edit.deleteCount)
        prompt.buffer.insert(edit.insert)
        return AppliedAutocorrection(original = before.takeLast(edit.deleteCount), corrected = edit.insert)
    }

    /**
     * Rétablit le mot tel que tapé si la dernière action était une autocorrection du prompt (mot corrigé
     * et séparateur juste avant le curseur). Renvoie faux si rien n'a été annulé.
     */
    private fun undoLastPromptAutocorrection(): Boolean {
        val pending = pendingAutocorrection ?: return false
        pendingAutocorrection = null
        val expected = pending.corrected + pending.boundary
        if (!prompt.buffer.textBeforeCursor.endsWith(expected)) return false // le texte ou le curseur a changé entre-temps
        prompt.buffer.deleteBefore(expected.length)
        prompt.buffer.insert(pending.original + pending.boundary)
        revertedAutocorrectionTail = (pending.original + pending.boundary).takeLast(REVERTED_TAIL_LENGTH)
        return true
    }

    /** Touche sur un mot suggéré en mode prompt (mêmes règles que [onWordSuggestionTapped] dans le champ). */
    private fun onPromptWordSuggestionTapped(suggestion: WordSuggestion) {
        if (suggestion !in currentWordSuggestions) return
        if (suggestion.kind == WordSuggestion.Kind.PREDICTION) {
            hapticFeedback.perform(hapticIntensity)
            pendingAutocorrection = null
            prompt.buffer.insert("${suggestion.text} ")
            learnFromTyping()
            afterPromptEdit()
            return
        }
        val typedBefore = trailingWord(prompt.buffer.textBeforeCursor)
        // Curseur placé dans un mot : le mot entier est visé ; une espace déjà présente après lui est remplacée.
        val textAfter = prompt.buffer.textAfterCursor
        val wordAfter = WordText.leadingWord(textAfter)
        val typed = typedBefore + wordAfter
        if (typed.isEmpty()) return
        val spaceAfter = textAfter.getOrNull(wordAfter.length) == ' '
        hapticFeedback.perform(hapticIntensity)
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty()) {
            prompt.buffer.deleteBefore(typedBefore.length)
            prompt.buffer.deleteAfter(wordAfter.length + if (spaceAfter) 1 else 0)
            prompt.buffer.insert(if (suggestion.kind == WordSuggestion.Kind.TYPED) "$typed " else "${suggestion.text} ")
        } else {
            if (spaceAfter) prompt.buffer.deleteAfter(1)
            prompt.buffer.insert(" ")
        }
        pendingAutocorrection = if (suggestion.replacesOnSpace) {
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = " ")
        } else {
            null
        }
        learnFromTyping() // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        afterPromptEdit()
    }

    /** Glissement de suppression en mode prompt : mots visés calculés sur le tampon, suppression au relâchement. */
    private fun onPromptDeleteSwipeUpdate(words: Int) {
        if (!deleteSwipe.active) {
            deleteSwipe.active = true
            deleteSwipe.base = prompt.buffer.cursor
            deleteSwipe.end = prompt.buffer.cursor
            deleteSwipe.length = 0
            deleteSwipe.before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
        }
        val length = deleteSwipeLength(words)
        if (length == deleteSwipe.length) return
        deleteSwipe.length = length
        hapticFeedback.perform(hapticIntensity.cursorMoveFeedback()) // un cran par changement de zone
    }

    private fun onPromptDeleteSwipeRelease() {
        val swipe = deleteSwipe
        if (!swipe.active) return
        swipe.active = false
        if (swipe.length == 0) return
        hapticFeedback.perform(hapticIntensity)
        prompt.buffer.deleteBefore(swipe.length)
        prompt.refreshInput()
        syncAutoCapitalization()
        applyState()
    }

    /**
     * Story 5.3, « Ajouter le texte » : insère [text] à la position du curseur de l'application, sans rien
     * remplacer. Une sélection dans le champ est d'abord repliée à sa fin, pour que le texte sélectionné
     * reste en place. Rien n'est appris de ce texte. Faux si le champ n'est pas accessible.
     */
    private fun insertGeneratedText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded() // un surlignage de correction ne vaut plus une fois le texte modifié
        pendingAutocorrection = null
        var toInsert = text
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty() || (lastSelectionStart >= 0 && lastSelectionEnd >= 0 && lastSelectionStart != lastSelectionEnd)) {
            if (lastSelectionStart >= 0 && lastSelectionEnd >= 0) {
                val end = maxOf(lastSelectionStart, lastSelectionEnd)
                ic.setSelection(end, end) // le texte sélectionné reste en place, l'ajout vient après
            } else {
                toInsert = selected + text // position inconnue : on remet le texte sélectionné, puis l'ajout
            }
        }
        correction.markSelfEdit()
        ic.commitText(toInsert, 1)
        return true
    }

    private fun onEmojiBackspace() {
        hapticFeedback.perform(hapticIntensity)
        if (prompt.active) {
            pendingAutocorrection = null
            prompt.buffer.backspace()
            prompt.refreshInput()
            return
        }
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
        if (prompt.active) {
            hapticFeedback.perform(hapticIntensity.cursorMoveFeedback())
            prompt.buffer.moveCursor(steps)
            prompt.refreshInput()
            syncAutoCapitalization()
            applyState()
            return
        }
        val ic = currentInputConnection ?: return
        // Début ou fin du texte : une touche directionnelle que le champ ne peut plus absorber déplace le focus et le curseur
        // quitte le champ. On n'envoie donc que les pas qui restent dans le texte (voir CursorSteps).
        val allowed = abs(allowedCursorSteps(ic, steps))
        if (allowed == 0) return
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        hapticFeedback.perform(hapticIntensity.cursorMoveFeedback()) // toujours faible, absent si désactivé
        repeat(allowed) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        syncAutoCapitalization()
        applyState()
    }

    /**
     * Pas de curseur réellement possibles dans le champ de l'application pour [steps] demandés. Si le champ ne dit rien
     * de son texte (réponse nulle), on garde le comportement d'origine : tous les pas.
     */
    private fun allowedCursorSteps(ic: InputConnection, steps: Int): Int {
        val limit = CursorSteps.fetchLimit(steps)
        val before = ic.getTextBeforeCursor(limit, 0)
        val after = ic.getTextAfterCursor(limit, 0)
        if (before == null || after == null) return steps
        return CursorSteps.allowed(steps, before, after, hasSelection())
    }

    /**
     * Story 1.9 : glissement vers la gauche depuis la touche retour arrière. Pendant le geste, rien
     * n'est supprimé : la zone qui le serait (les [words] mots précédant le curseur de départ, espaces
     * de fin compris) est sélectionnée, ce qui la surligne et place le curseur à sa gauche. Revenir vers
     * la droite réduit la zone. La suppression a lieu au relâchement ([onDeleteSwipeRelease]).
     * S'il n'y a pas de mot immédiatement avant le curseur (ponctuation), un seul caractère est visé
     * pour que le geste ne reste jamais sans effet.
     */
    private fun onDeleteSwipeUpdate(words: Int) {
        if (prompt.active) {
            onPromptDeleteSwipeUpdate(words)
            return
        }
        val ic = currentInputConnection ?: return
        if (!deleteSwipe.active) {
            if (!beginDeleteSwipe(ic)) return
        }
        val length = deleteSwipeLength(words)
        if (length == deleteSwipe.length) return
        deleteSwipe.length = length
        hapticFeedback.perform(hapticIntensity.cursorMoveFeedback()) // un cran par changement de zone, toujours faible
        ic.setSelection(deleteSwipe.base - length, deleteSwipe.end)
    }

    /** Capture le point de départ du glissement ; faux si la position du curseur est inconnue. */
    private fun beginDeleteSwipe(ic: InputConnection): Boolean {
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val base: Int
        val end: Int
        if (extracted != null) {
            base = extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
            end = extracted.startOffset + maxOf(extracted.selectionStart, extracted.selectionEnd)
        } else if (lastSelectionStart >= 0 && lastSelectionEnd >= 0) {
            base = minOf(lastSelectionStart, lastSelectionEnd)
            end = maxOf(lastSelectionStart, lastSelectionEnd)
        } else {
            return false
        }
        // Une éventuelle sélection de départ est incluse dans la zone supprimée (comme la touche retour arrière seule).
        deleteSwipe.active = true
        deleteSwipe.base = base
        deleteSwipe.end = end
        deleteSwipe.length = 0
        deleteSwipe.before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        return true
    }

    /** Nombre de caractères couverts par [words] mots avant le point de départ (borné au texte connu). */
    private fun deleteSwipeLength(words: Int): Int {
        var total = 0
        repeat(words) {
            val remaining = deleteSwipe.before.substring(0, deleteSwipe.before.length - total)
            val piece = wordToDeleteBeforeCursor(remaining)
            if (piece.isEmpty()) return total
            total += piece.length
        }
        return total
    }

    private fun onDeleteSwipeRelease() {
        if (prompt.active) {
            onPromptDeleteSwipeRelease()
            return
        }
        val ic = currentInputConnection
        val swipe = deleteSwipe
        if (!swipe.active || ic == null) {
            swipe.active = false
            return
        }
        swipe.active = false
        if (swipe.length == 0) {
            // Doigt revenu au point de départ : on annule, la sélection d'origine est rétablie.
            ic.setSelection(swipe.base, swipe.end)
            syncAutoCapitalization()
            applyState()
            return
        }
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        ic.beginBatchEdit()
        try {
            ic.setSelection(swipe.end, swipe.end)
            ic.deleteSurroundingText(swipe.length + (swipe.end - swipe.base), 0)
        } finally {
            ic.endBatchEdit()
        }
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    private fun onDeleteSwipeCancel() {
        val swipe = deleteSwipe
        if (!swipe.active) return
        if (prompt.active) {
            swipe.active = false
            return
        }
        swipe.active = false
        currentInputConnection?.setSelection(swipe.base, swipe.end)
        syncAutoCapitalization()
        applyState()
    }

    /** État du glissement de suppression en cours (offsets absolus dans le champ). */
    private class DeleteSwipeState {
        var active = false
        var base = 0 // début de la sélection de départ (= curseur s'il n'y en avait pas)
        var end = 0 // fin de la sélection de départ
        var length = 0 // caractères surlignés à gauche de [base]
        var before = "" // texte précédant [base] au départ du geste
    }

    private val deleteSwipe = DeleteSwipeState()

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
        if (prompt.active) {
            // En mode prompt, la majuscule et les suggestions suivent le texte du prompt.
            val before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
            controller.applyTextContext(before)
            refreshSuggestions(before)
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        controller.applyTextContext(before)
        refreshSuggestions(before)
    }

    // Suggestions de mots et d'emoji, prédiction du mot suivant, apprentissage : voir SuggestionController.

    private fun refreshSuggestions(textBeforeCursor: String) = suggestions.refresh(textBeforeCursor)

    /** Vide la bande de suggestions (panneau emoji, dictée ou correction en cours). */
    private fun clearSuggestions() = suggestions.clear()

    /**
     * Apprend, pour la prédiction du mot suivant, le dernier mot (ou emoji) terminé avant le curseur.
     * Appelé après une saisie de l'utilisateur uniquement (espace, retour à la ligne, suggestion ou emoji
     * touchés), jamais pour du texte dicté ou collé, ni dans un champ sans suggestions.
     */
    private fun learnFromTyping(terminator: String = "") = suggestions.learnFromTyping(terminator)

    /**
     * Touche sur un mot suggéré, suivi d'une espace :
     * - autocorrection (centre, gras) : remplace le mot tapé, comme le ferait l'espace ; une
     *   suppression immédiate rétablit le mot tapé ;
     * - mot tapé (entre guillemets) : conservé tel quel, sans correction ;
     * - complétion : remplace le mot en cours.
     */
    private fun onWordSuggestionTapped(suggestion: WordSuggestion) {
        if (prompt.active) {
            onPromptWordSuggestionTapped(suggestion)
            return
        }
        if (suggestion !in currentWordSuggestions) return
        if (isRecording || correctionInProgress || hasSelection()) return
        val ic = currentInputConnection ?: return
        if (suggestion.kind == WordSuggestion.Kind.PREDICTION) {
            insertPredictedWord(ic, suggestion.text)
            return
        }
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val typedBefore = trailingWord(before)
        // Curseur placé dans un mot : le mot entier est visé (la suite du mot après le curseur en fait partie).
        val textAfter = ic.getTextAfterCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val wordAfter = WordText.leadingWord(textAfter)
        val typed = typedBefore + wordAfter
        if (typed.isEmpty()) return
        // Une espace suit déjà le mot : elle est remplacée par celle qu'on ajoute, jamais doublée.
        val spaceAfter = textAfter.getOrNull(wordAfter.length) == ' '
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()

        ic.beginBatchEdit()
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty()) {
            ic.deleteSurroundingText(typedBefore.length, wordAfter.length + if (spaceAfter) 1 else 0)
            ic.commitText(if (suggestion.kind == WordSuggestion.Kind.TYPED) "$typed " else "${suggestion.text} ", 1)
        } else {
            if (spaceAfter) ic.deleteSurroundingText(0, 1)
            ic.commitText(" ", 1)
        }
        ic.endBatchEdit()

        pendingAutocorrection = if (suggestion.replacesOnSpace) {
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = " ")
        } else {
            null
        }
        learnFromTyping() // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    /** Touche sur un mot prédit : il s'ajoute au curseur (après l'espace déjà tapée), suivi d'une espace. */
    private fun insertPredictedWord(ic: InputConnection, word: String) {
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        ic.commitText("$word ", 1)
        learnFromTyping()
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    private fun emojiPanelVisible(): Boolean =
        viewComposer.isComposed && emojiPanel.visibility == View.VISIBLE

    /**
     * Touche sur l'emoji suggéré : il est inséré au curseur, précédé d'une espace si le mot vient
     * d'être tapé sans espace après lui. Il rejoint aussi les emojis récents.
     */
    private fun onEmojiSuggestionTapped() {
        val emoji = currentEmojiSuggestion ?: return
        if (prompt.active) {
            // Même règle que dans le champ : précédé d'une espace si le mot vient d'être tapé sans espace après lui.
            hapticFeedback.perform(hapticIntensity)
            pendingAutocorrection = null
            val needsSpace = prompt.buffer.textBeforeCursor.lastOrNull()?.isLetterOrDigit() == true
            prompt.buffer.insert(if (needsSpace) " $emoji" else emoji)
            keyboardPreferences.recentEmojis = RecentEmojis.add(keyboardPreferences.recentEmojis, emoji)
            learnFromTyping()
            afterPromptEdit()
            return
        }
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
        learnFromTyping()
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

    private fun isWordChar(c: Char): Boolean = WordText.isWordChar(c)

    /** Dernier "mot" avant le curseur : lettres/apostrophes/traits d'union contigus en fin de texte. */
    private fun trailingWord(textBeforeCursor: String): String = WordText.trailingWord(textBeforeCursor)

    /**
     * Story 1.18 : pas d'autocorrection dans les champs e-mail, URL, mot de passe et numériques, ni
     * quand l'application demande de ne rien suggérer (même règle que les suggestions de mots).
     */
    private fun autocorrectionAllowed(): Boolean = suggestions.suggestionsAllowed && fieldType.autoCorrects

    private fun applyDictionaryAutocorrection(): AppliedAutocorrection? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val edit = suggestions.autocorrectionFor(before, allowContextualRetro(before)) ?: return null

        ic.beginBatchEdit()
        ic.deleteSurroundingText(edit.deleteCount, 0)
        ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        return AppliedAutocorrection(original = before.takeLast(edit.deleteCount), corrected = edit.insert)
    }

    /**
     * Texte qui entourait le curseur quand l'utilisateur a annulé une autocorrection (retour arrière juste après) :
     * le mot qu'il vient de rétablir n'est pas recorrigé à rebours quand le mot suivant est tapé.
     */
    private var revertedAutocorrectionTail: String? = null

    /** Faux si le mot précédent vient d'être rétabli par l'utilisateur : il ne doit pas être revu à rebours. */
    private fun allowContextualRetro(textBeforeCursor: String): Boolean {
        val tail = revertedAutocorrectionTail ?: return true
        revertedAutocorrectionTail = null
        return !textBeforeCursor.contains(tail)
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
        revertedAutocorrectionTail = (pending.original + pending.boundary).takeLast(REVERTED_TAIL_LENGTH)
        return true
    }

    // ------------------------------------------------------------------
    // Correction IA (épopée 3) : voir CorrectionAiController (lot 2.3 de la revue)
    // ------------------------------------------------------------------

    /** Mots du dictionnaire personnel présents dans [text] : signalés à l'IA pour qu'elle ne les corrige pas. */
    private fun protectedWordsIn(text: String): List<String> =
        ProtectedWords.inText(text, personalDictionary.snapshot())

    /** Appelée avant toute action de l'utilisateur (touche, Corriger, Vocal) : le surlignage disparaît. */
    private fun clearHighlightIfNeeded() = correction.clearHighlightIfNeeded()


    /** Décision 3.3 : le bouton Corriger n'est visible que si le champ contient du texte. */
    private fun updateCorrectionBarVisibility() {
        clipboard.refreshPasteSuggestion() // story 2.2 : la puce suit l'état courant (dictée, correction, frappe, expiration…)
        if (correctionInProgress) return
        val ic = currentInputConnection
        val hasText = ic != null &&
            (
                !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() ||
                    !ic.getTextAfterCursor(1, 0).isNullOrEmpty() ||
                    !ic.getSelectedText(0).isNullOrEmpty() // tout le texte peut être sélectionné
                )
        correctionBar.setFieldHasText(hasText)
        correctionBar.state = when {
            !hasText -> CorrectionBarState.HIDDEN
            correction.canUndo -> CorrectionBarState.UNDO // juste après une correction : le bouton annule
            else -> CorrectionBarState.IDLE
        }
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
        // Hauteur constante : les pages de symboles (4 rangées) prennent la hauteur du clavier de lettres affiché,
        // qui compte une rangée de plus avec la rangée de chiffres. Évite de faire défiler l'application dessous.
        keyboardView.minRowCount =
            Keyboards.layoutOf(LayoutId.LETTERS, controller.state.language, numberRowEnabled, fieldType).rows.size
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout, controller.state.language, numberRowEnabled, fieldType)
        keyboardView.isShifted = controller.state.isShifted
        keyboardView.isCapsLock = controller.state.isCapsLock
    }

    companion object {
        private const val TAG = "TaipoIme"

        /** Longueur du texte retenu quand une autocorrection est annulée (voir [revertedAutocorrectionTail]). */
        private const val REVERTED_TAIL_LENGTH = 12

        // Suggestions d'auto-remplissage en ligne : hauteur alignée sur la zone de la barre (36 dp, moins la marge).
        private const val INLINE_SUGGESTION_HEIGHT_DP = 32f
        private const val INLINE_SUGGESTION_MIN_WIDTH_DP = 48f
        private const val INLINE_SUGGESTION_MAX_WIDTH_DP = 320f
        private const val INLINE_SUGGESTION_MAX_COUNT = 5
        private const val INLINE_LAYOUT_LOG_DELAY_MS = 500L
    }
}
