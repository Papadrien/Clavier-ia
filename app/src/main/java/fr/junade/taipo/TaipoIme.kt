package fr.junade.taipo

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.RequiresApi
import android.widget.Toast
import fr.junade.taipo.ai.AiTranscriptionEngine
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.ProtectedWords
import fr.junade.taipo.ai.TranscriptionPromptPreferences
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.emoji.EmojiCatalog
import fr.junade.taipo.emoji.EmojiText
import fr.junade.taipo.emoji.MessagingFieldPolicy
import fr.junade.taipo.suggestion.EmailText
import fr.junade.taipo.suggestion.NextWordRepository
import fr.junade.taipo.suggestion.SuggestionPolicy
import fr.junade.taipo.suggestion.WordText
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.model.AiModel
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

    // Contexte commun des hôtes des contrôleurs (audit 2.1, étape 5) : voir ImeHosts.kt. Chaque membre délègue au service,
    // toujours par `this@TaipoIme` (un nom nu désignerait le membre du contexte lui-même).
    private val imeContext: ImeHostContext = object : ImeHostContext {
        override val prompt: PromptModeController get() = this@TaipoIme.prompt
        override val voice: VoiceController get() = this@TaipoIme.voice
        override val clipboard: ClipboardController get() = this@TaipoIme.clipboard
        override val emoji: EmojiController get() = this@TaipoIme.emojiController
        override val correction: CorrectionAiController get() = this@TaipoIme.correction
        override val suggestions: SuggestionController get() = this@TaipoIme.suggestions
        override val deleteSwipe: DeleteSwipeController get() = this@TaipoIme.deleteSwipe
        override val undo: UndoController get() = this@TaipoIme.undoController
        override val aiTranscription: AiTranscriptionController get() = this@TaipoIme.aiTranscription
        override val messagePopup: MessagePopup get() = this@TaipoIme.messagePopup
        override val viewComposer: ImeViewComposer get() = this@TaipoIme.viewComposer
        override val correctionBar: CorrectionBarView get() = this@TaipoIme.correctionBar
        override val keyboardView: KeyboardView get() = this@TaipoIme.keyboardView
        override val keyboardPreferences: KeyboardPreferences get() = this@TaipoIme.keyboardPreferences

        override val messagingField: Boolean get() = this@TaipoIme.messagingField
        override val sensitiveField: Boolean get() = this@TaipoIme.sensitiveField
        override val lastSelectionStart: Int get() = this@TaipoIme.lastSelectionStart
        override val lastSelectionEnd: Int get() = this@TaipoIme.lastSelectionEnd
        override val keyboardLanguage: KeyboardLanguage get() = this@TaipoIme.controller.state.language
        override val heightScale: Float get() = this@TaipoIme.keyboardHeight.scale

        override fun inputConnection(): InputConnection? = this@TaipoIme.currentInputConnection
        override fun isInputViewShown(): Boolean = this@TaipoIme.isInputViewShown
        override fun string(resId: Int): String = this@TaipoIme.getString(resId)
        override fun hasSelection(): Boolean = this@TaipoIme.hasSelection()
        override fun activeModel(): AiModel? =
            ModelAvailability.usable(this@TaipoIme.modelPreferences.activeModel(), this@TaipoIme.modelPreferences::isInstalled)
        override fun personalWords(): List<String> = this@TaipoIme.personalDictionary.snapshot()
        override fun protectedWordsIn(text: String): List<String> = this@TaipoIme.protectedWordsIn(text)

        override fun haptic() = this@TaipoIme.hapticFeedback.perform(this@TaipoIme.hapticIntensity)
        override fun hapticCursorMove() =
            this@TaipoIme.hapticFeedback.perform(this@TaipoIme.hapticIntensity.cursorMoveFeedback())
        override fun hapticAi(cue: AiHapticCue) = this@TaipoIme.hapticFeedback.perform(cue, this@TaipoIme.hapticIntensity)
        override fun clearHighlight() = this@TaipoIme.clearHighlightIfNeeded()
        override fun clearPendingAutocorrection() {
            this@TaipoIme.pendingAutocorrection = null
        }
        override fun onUserTyped() = this@TaipoIme.onUserTyped()
        override fun recordUndoEdit(inserted: String, original: String) =
            this@TaipoIme.recordUndoEdit(inserted = inserted, original = original)
        override fun recordUndoAction(undo: UndoHistory.Undo) = this@TaipoIme.recordUndoAction(undo)
        override fun recordUndoDeletion(
            deleted: String,
            anchor: String,
            before: String,
            mergeable: Boolean,
            offsetProvider: () -> Int,
        ) = this@TaipoIme.recordUndoDeletion(deleted, anchor, before, mergeable, offsetProvider)
        override fun learnFromTyping(terminator: String) = this@TaipoIme.learnFromTyping(terminator)
        override fun clearSuggestions() = this@TaipoIme.clearSuggestions()
        override fun syncAutoCapitalization() = this@TaipoIme.syncAutoCapitalization()
        override fun applyState() = this@TaipoIme.applyState()
        override fun updateCorrectionBarVisibility() = this@TaipoIme.updateCorrectionBarVisibility()
        override fun afterPromptEdit() = this@TaipoIme.afterPromptEdit()
        override fun deleteSelectedText(): Boolean = this@TaipoIme.deleteSelectedText()
        override fun deleteLastCluster() = this@TaipoIme.deleteLastCluster()
        override fun insertGeneratedText(text: String): Boolean = this@TaipoIme.insertGeneratedText(text)
        override fun replaceFieldText(text: String): Boolean = this@TaipoIme.replaceFieldText(text)
        override fun onWordSuggestionTapped(suggestion: WordSuggestion) = this@TaipoIme.onWordSuggestionTapped(suggestion)
        override fun onKeyPressed(key: Key) = this@TaipoIme.onKeyPressed(key)
        override fun onCursorMoved(steps: Int) = this@TaipoIme.onCursorMoved(steps)
        override fun onGlobeSelected() = this@TaipoIme.onGlobeSelected()
        override fun openAppHome() = this@TaipoIme.openAppHome()
    }

    // Emojis (story 1.15 : panneau, barre des récents, insertion) : voir EmojiController (audit 2.1).
    private val emojiController: EmojiController by lazy {
        EmojiController(
            EmojiHost(imeContext),
        )
    }

    // Smart Clipboard (puce de collage, panneau, épinglés) : voir ClipboardController (lot 2.3 de la revue).
    private val clipboard: ClipboardController by lazy {
        ClipboardController(
            this,
            serviceScope,
            mainHandler,
            ClipboardHost(imeContext),
        )
    }

    // Suggestions de mots et d'emoji, prédiction du mot suivant, apprentissage local : voir
    // SuggestionController (lot 2.3 de la revue).
    private val suggestions: SuggestionController by lazy {
        SuggestionController(
            this,
            serviceScope,
            SuggestionHost(imeContext),
        )
    }

    private val nextWords: NextWordRepository get() = suggestions.nextWords
    private val currentWordSuggestions: List<WordSuggestion?> get() = suggestions.currentWordSuggestions
    private val currentEmojiSuggestion: String? get() = suggestions.currentEmojiSuggestion

    // Pop-ups d'information et liste des langues : voir MessagePopup (audit 2.1).
    private val messagePopup: MessagePopup by lazy {
        MessagePopup(
            this,
            MessagePopupHost(imeContext),
        )
    }

    // Mode prompt (conversation, génération, zone de chat) : voir PromptModeController (lot 2.3 de la revue).
    private val prompt: PromptModeController by lazy {
        PromptModeController(
            this,
            serviceScope,
            mainHandler,
            llmHost,
            PromptHost(imeContext),
        )
    }

    /** Vrai quand le champ courant est celui d'une messagerie, relu à chaque champ. */
    private var messagingField = false

    /** Story 1.18 : type du champ courant (e-mail, URL, numérique...), fixé à chaque ouverture de champ. */
    private var fieldType = FieldType.TEXT

    /** Story 10.1 : le champ en cours est sensible (mot de passe) : actions IA et Smart Clipboard grisés. */
    private var sensitiveField = false

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Moteur LiteRT-LM unique, partagé par la correction et (épopée 5) la génération par prompt.
    private val llmHost by lazy { LlmEngineHost(applicationContext) }
    private val correctionEngine by lazy { CorrectionEngine(applicationContext, llmHost) }
    private val modelPreferences by lazy { ModelPreferences(applicationContext) }
    private val keyboardPreferences by lazy { KeyboardPreferences(applicationContext) }
    private val hapticFeedback by lazy { HapticFeedbackPlayer(applicationContext) }
    private val keySounds by lazy { KeySoundPlayer(applicationContext) }

    // Story 1.5 : rangée de chiffres, relue à chaque ouverture de champ
    // (onStartInputView) pour prendre en compte un changement fait dans les paramètres.
    private var numberRowEnabled = false

    // Story 1.11 : niveau de retour haptique, relu de la même façon.
    private var hapticIntensity = HapticIntensity.DEFAULT

    // Son des touches (tic synthétisé), relu de la même façon.
    private var keySoundEnabled = KeyboardPreferences.DEFAULT_KEY_SOUND
    private var keyboardHeight = KeyboardHeight.DEFAULT

    private val personalDictionary by lazy { PersonalDictionaryProvider.repository(applicationContext) }

    // Correction IA : voir CorrectionAiController (lot 2.3 de la revue).
    private val correction: CorrectionAiController by lazy {
        CorrectionAiController(
            this,
            serviceScope,
            { correctionEngine },
            CorrectionHost(imeContext),
        )
    }

    // Bouton Annuler (à droite du bouton Smart Clipboard) : historique des 10 dernières actions, voir UndoController.
    private val undoController: UndoController by lazy {
        UndoController(
            this,
            UndoHost(imeContext),
        )
    }

    private val voice: VoiceController by lazy {
        VoiceController(
            this,
            serviceScope,
            mainHandler,
            VoiceHost(imeContext),
        )
    }

    // Épopée 14 : transcription IA par modèle multimodal, voir AiTranscriptionController.
    private val aiTranscription: AiTranscriptionController by lazy {
        AiTranscriptionController(
            this,
            serviceScope,
            AiTranscriptionEngine(llmHost, TranscriptionPromptPreferences(applicationContext)),
            AiTranscriptionHost(imeContext),
        )
    }

    // Construction de la vue du clavier : voir ImeViewComposer (lot 2.3 de la revue).
    // Glissement de suppression depuis la touche retour arrière (story 1.9) : voir DeleteSwipeController (audit 2.1).
    private val deleteSwipe: DeleteSwipeController by lazy {
        DeleteSwipeController(
            DeleteSwipeHost(imeContext),
        )
    }

    private val viewComposer: ImeViewComposer by lazy {
        ImeViewComposer(
            this,
            prompt,
            clipboard,
            ViewComposerHost(imeContext),
        )
    }

    private val correctionInProgress: Boolean get() = correction.inProgress

    /**
     * Dernière autocorrection du dictionnaire, tant que rien d'autre n'a été fait depuis :
     * une suppression immédiate la rétablit (mot tel que tapé), comme sur les claviers usuels.
     */
    private class AppliedAutocorrection(
        val original: String,
        val corrected: String,
        val boundary: String = "",
        // Texte avant le curseur au moment de la correction (pour les habitudes) ; vide si non renseigné.
        val textBefore: String = "",
    )

    private var pendingAutocorrection: AppliedAutocorrection? = null

    // Sélection connue du champ (suivie par CorrectionAiController, qui s'en sert pour le surlignage).
    private val lastSelectionStart: Int get() = correction.lastSelectionStart
    private val lastSelectionEnd: Int get() = correction.lastSelectionEnd

    // Saisie vocale : voir VoiceController (lot 2.3 de la revue).
    private val isRecording: Boolean get() = voice.isRecording

    // Auto-remplissage en ligne (gestionnaire de mots de passe) : voir InlineSuggestionsController (audit 2.1).
    private val inlineSuggestions: InlineSuggestionsController by lazy {
        InlineSuggestionsController(
            this,
            mainHandler,
            InlineSuggestionsHost(imeContext),
        )
    }


    override fun onCreate() {
        super.onCreate()
        // Story 1.4 : l'ouverture de la base chiffrée du dictionnaire
        // personnel est asynchrone ; on la déclenche dès la création du
        // service pour qu'elle soit prête avant la première frappe.
        personalDictionary
        nextWords // l'ouverture du modèle appris est asynchrone : on la déclenche dès la création
        suggestions.correctionHabits // idem pour les habitudes de correction
        clipboard.start() // bases du presse-papiers + retour de l'écran de modification (story 2.6)
        suggestions.preloadDictionaries()
        preloadEmojiCatalog()
        numberRowEnabled = keyboardPreferences.isNumberRowEnabled
        hapticIntensity = keyboardPreferences.hapticIntensity
        refreshKeySound()
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
        if (!restarting) undoController.clear(notify = false)
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
        refreshKeySound()
        keyboardHeight = keyboardPreferences.keyboardHeight
        keyboardView.heightScale = keyboardHeight.scale
        emojiController.hidePanel(resync = false)
        clipboard.hidePanel(resync = false)
        correctionBar.collapseMenu()
        suggestions.suggestionsAllowed = SuggestionPolicy.allowsSuggestions(info?.inputType ?: 0)
        // IME_FLAG_NO_PERSONALIZED_LEARNING : navigation privée, champ confidentiel... rien n'est appris ni proposé.
        suggestions.learningAllowed = suggestions.suggestionsAllowed &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        suggestions.refreshHabitLearning(keyboardPreferences.isHabitLearningEnabled)
        // Adresses e-mail : proposées et apprises aussi dans les champs d'adresse (là où les mots ne le sont pas), jamais en navigation privée.
        suggestions.emailFieldAllowed = SuggestionPolicy.allowsEmailSuggestions(info?.inputType ?: 0) &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        clipboard.pasteAllowedInField = SuggestionPolicy.allowsPasteSuggestion(info?.inputType ?: 0)
        fieldType = FieldType.of(info?.inputType ?: 0)
        // Story 10.1 : champ de mot de passe, actions IA et Smart Clipboard grisés (touchables, avec un message).
        sensitiveField = SensitiveFieldPolicy.isSensitive(info?.inputType ?: 0)
        Analytics.sensitiveFieldActive = sensitiveField // mesure d'usage coupée tant qu'un champ de mot de passe est affiché
        correctionBar.aiActionsAllowed = !sensitiveField
        // Story 14.5 : le bouton Transcription IA n'existe que si le modèle actif (installé) a une entrée audio.
        correctionBar.aiTranscriptionAvailable =
            ModelAvailability.usable(modelPreferences.activeModel(), modelPreferences::isInstalled)?.hasAudioInput == true
        suggestions.isEmailAddressField = fieldType == FieldType.EMAIL
        messagingField = MessagingFieldPolicy.isMessagingField(info?.packageName, info?.inputType ?: 0)
        controller.setAutoCapitalization(
            fieldType.autoCapitalizes && FieldType.requestsCapitalization(info?.inputType ?: 0),
        )
        suggestions.invalidate()
        controller.setLanguage(currentKeyboardLanguage())
        trackKeyboardLanguage()
        suggestions.preloadDictionaries() // la langue a pu changer dans les paramètres : charge son dictionnaire si besoin
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
        emojiController.refreshRecentBar()
        // À chaque ouverture du clavier, la barre des emojis récents repart calée à droite, sur le plus récent.
        viewComposer.recentEmojiBar.scrollToLatest()
        updateCorrectionBarVisibility()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) inlineSuggestions.applyPending()
    }

    /** Dernière langue envoyée à la mesure d'usage : l'événement ne repart que si elle change (pas à chaque champ). */
    private var lastTrackedLanguage: KeyboardLanguage? = null

    private fun trackKeyboardLanguage() {
        val language = controller.state.language
        if (sensitiveField || language == lastTrackedLanguage) return
        lastTrackedLanguage = language
        Analytics.track(
            this,
            AnalyticsEvents.KEYBOARD_LANGUAGE,
            "lang" to language.tag,
            "layout" to AnalyticsEvents.layoutOf(language),
            "auto" to (keyboardPreferences.keyboardLanguage == null),
        )
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        controller.setLanguage(currentKeyboardLanguage())
        if (viewComposer.isComposed) {
            syncAutoCapitalization()
            applyState()
        }
    }

    /**
     * Langue active : celle choisie dans les paramètres du clavier ; en mode « automatique », la première langue
     * du système que le clavier gère, sinon l'anglais (voir [KeyboardPreferences.keyboardLanguage]).
     */
    private fun currentKeyboardLanguage(): KeyboardLanguage =
        KeyboardLanguageChoice.resolve(
            keyboardPreferences.keyboardLanguage,
            resources.configuration.locales.let { list -> (0 until list.size()).map { list.get(it).toLanguageTag() } },
            keyboardPreferences.multilingualLanguages,
            keyboardPreferences.activeMultilingualLanguage,
        )

    /**
     * Clavier multilingue : touche globe de la bulle d'appui long sur Entrée. Deux langues cochées : on passe
     * directement à l'autre ; davantage : liste de choix. Sans effet si le mode n'est pas actif.
     */
    private fun onGlobeSelected() {
        val languages = keyboardPreferences.multilingualLanguages
        when {
            languages.size == 2 -> MultilingualChoice.next(languages, controller.state.language)?.let { switchLanguage(it) }
            languages.size > 2 -> showLanguagePicker(languages)
        }
    }

    /** Liste des langues cochées (au-dessus du clavier) : un appui sur une langue la rend active et ferme la liste. */
    private fun showLanguagePicker(languages: List<KeyboardLanguage>) {
        // Pas de fenêtre où accrocher la liste : on passe simplement à la langue suivante.
        fun nextLanguage() {
            MultilingualChoice.next(languages, controller.state.language)?.let { switchLanguage(it) }
        }
        messagePopup.showChoice(
            title = R.string.keyboard_settings_language_title,
            items = languages.map { it.displayName }.toTypedArray(),
            checked = languages.indexOf(controller.state.language),
            onChosen = { which -> switchLanguage(languages[which]) },
            onUnavailable = ::nextLanguage,
        )
    }

    /** Passe le clavier à [next] tout de suite (disposition, dictionnaire, suggestions) et la retient pour les prochains champs. */
    private fun switchLanguage(next: KeyboardLanguage) {
        if (next == controller.state.language) return
        keyboardPreferences.activeMultilingualLanguage = next
        hapticFeedback.perform(hapticIntensity)
        controller.setLanguage(next)
        pendingAutocorrection = null
        suggestions.invalidate() // les suggestions affichées étaient celles de l'ancienne langue
        suggestions.preloadDictionaries()
        trackKeyboardLanguage()
        syncAutoCapitalization()
        applyState()
        Toast.makeText(this, next.displayName, Toast.LENGTH_SHORT).show()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Le panneau emoji est calé sur les dimensions du clavier : on revient aux touches après
        // une rotation ou un changement de taille de fenêtre, plutôt que d'afficher un panneau mal ajusté.
        if (emojiController.isPanelVisible) emojiController.hidePanel()
        if (clipboard.isPanelVisible) clipboard.hidePanel()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Analytics.sensitiveFieldActive = false
        suggestions.finishField() // une adresse tapée en dernier, sans espace après elle, est retenue avant la sauvegarde
        nextWords.flush()
        suggestions.correctionHabits.flush()
        inlineSuggestions.clear()
        messagePopup.dismiss()
        if (viewComposer.isComposed) emojiController.hidePanel(resync = false)
        clipboard.hidePanel(resync = false)
        correction.clearHighlightState()
        if (finishingInput) undoController.clear(notify = false)
        clipboard.stopListening()
        if (isRecording) {
            voice.cancelRecording()
        }
        aiTranscription.onInputFinished()
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
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? =
        inlineSuggestions.createRequest(uiExtras)

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean =
        inlineSuggestions.onResponse(response)

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
        suggestions.correctionHabits.flushBlocking()
        suggestions.release()
        messagePopup.dismiss()
        aiTranscription.release()
        llmHost.close()
        voice.release()
        clipboard.release()
        keySounds.release()
        serviceJob.cancel()
    }

    /** Relit le réglage du son des touches et prépare les sons (une seule fois) s'il est activé. */
    private fun refreshKeySound() {
        keySoundEnabled = keyboardPreferences.isKeySoundEnabled
        if (keySoundEnabled) keySounds.prepare()
    }

    private fun onKeyPressed(key: Key) {
        hapticFeedback.perform(hapticIntensity)
        if (keySoundEnabled) keySounds.play(KeySound.forAction(key.action))
        suggestions.markTyping() // le curseur qui bouge ensuite suit la frappe (suggestions de complétion)
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
            emojiController.showPanel()
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
            suggestions.habitAutocorrectionApplied(autocorrection.textBefore, autocorrection.original, autocorrection.corrected)
            recordUndoEdit(inserted = autocorrection.corrected + boundary, original = autocorrection.original + boundary)
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
        if (!before.isNullOrEmpty() && before.length >= length) {
            val text = before.toString()
            val deleted = text.takeLast(length)
            recordUndoDeletion(deleted, text.dropLast(length).takeLast(DeletionUndo.ANCHOR_LENGTH), text, mergeable = true) {
                selectionStartOffset(ic) - length
            }
        }
        ic.deleteSurroundingText(length, 0)
    }

    // ------------------------------------------------------------------
    // Page d'accueil et saisie utilisateur (le panneau Smart Clipboard est dans ClipboardController)
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
     * emoji, suggestion, collage, suppression, dictée). Il referme le menu « ··· » : retour aux
     * suggestions de mots dès la frappe (la puce de collage n'est pas écartée : elle revient quand le champ est vide). Les
     * touches sans texte (Maj, changement de disposition) et les simples déplacements du curseur ne
     * l'appellent pas. L'appelant met la barre à jour ensuite (updateCorrectionBarVisibility).
     */
    private fun onUserTyped() {
        if (viewComposer.isComposed) correctionBar.collapseMenu()
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
            emojiController.showPanel()
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
            suggestions.habitAutocorrectionApplied(autocorrection.textBefore, autocorrection.original, autocorrection.corrected)
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
        val original = before.takeLast(edit.deleteCount)
        suggestions.recordCorrection(original, edit.insert)
        return AppliedAutocorrection(original = original, corrected = edit.insert, textBefore = before)
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
        suggestions.habitAutocorrectionUndone(pending.original, pending.corrected)
        Analytics.track(this, AnalyticsEvents.AUTOCORRECT_UNDONE)
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
        if (suggestion.kind == WordSuggestion.Kind.EMAIL) {
            val before = prompt.buffer.textBeforeCursor.takeLast(TEXT_CONTEXT_LOOKBEHIND)
            val typedEmailStart = EmailText.trailingToken(before, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND) ?: return
            hapticFeedback.perform(hapticIntensity)
            pendingAutocorrection = null
            prompt.buffer.deleteBefore(typedEmailStart.length)
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
        val separator = separatorAfterSuggestion(textAfter, wordAfter) // pas d'espace ajoutée devant une virgule
        hapticFeedback.perform(hapticIntensity)
        // Le mot tel que tapé peut différer du mot affiché à gauche : forme d'origine d'un mot déjà corrigé.
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty() || suggestion.text != typed) {
            prompt.buffer.deleteBefore(typedBefore.length)
            prompt.buffer.deleteAfter(wordAfter.length + if (spaceAfter) 1 else 0)
            prompt.buffer.insert("${suggestion.text}$separator")
        } else if (separator.isNotEmpty()) {
            if (spaceAfter) prompt.buffer.deleteAfter(1)
            prompt.buffer.insert(separator)
        }
        pendingAutocorrection = if (suggestion.replacesOnSpace) {
            suggestions.recordCorrection(typed, suggestion.text)
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = separator)
        } else {
            null
        }
        learnFromTyping() // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        afterPromptEdit()
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
        // Position inconnue : le texte sélectionné a été remis devant l'ajout (il remplaçait la sélection).
        recordUndoEdit(inserted = toInsert, original = if (toInsert != text) selected else "")
        return true
    }

    /**
     * Bouton « Remplacer le texte » du chat : [text] remplace la sélection de l'application s'il y en a une,
     * sinon tout le texte accessible du champ. Une seule édition groupée (un seul « annuler » côté application).
     * Rien n'est appris de ce texte. Faux si le champ n'est pas accessible.
     */
    private fun replaceFieldText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        hapticFeedback.perform(hapticIntensity)
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        correction.markSelfEdit()
        var replaced = ""
        ic.beginBatchEdit()
        try {
            val selected = ic.getSelectedText(0)
            if (selected.isNullOrEmpty()) {
                val before = ic.getTextBeforeCursor(REPLACE_MAX_CHARS, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(REPLACE_MAX_CHARS, 0)?.toString().orEmpty()
                if (before.length + after.length > 0) {
                    ic.deleteSurroundingText(before.length, after.length)
                    replaced = before + after
                }
            } else {
                replaced = selected.toString()
            }
            ic.commitText(text, 1) // avec une sélection, commitText la remplace
        } finally {
            ic.endBatchEdit()
        }
        recordUndoEdit(inserted = text, original = replaced)
        return true
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
        if (suggestion.kind == WordSuggestion.Kind.EMAIL) {
            insertSuggestedEmail(ic, suggestion.text)
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
        // Une virgule suit le mot : aucune espace n'est ajoutée (« mot, » et non « mot , »).
        val separator = separatorAfterSuggestion(textAfter, wordAfter)
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()

        ic.beginBatchEdit()
        // Le mot tel que tapé peut différer du mot affiché à gauche : forme d'origine d'un mot déjà corrigé.
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty() || suggestion.text != typed) {
            ic.deleteSurroundingText(typedBefore.length, wordAfter.length + if (spaceAfter) 1 else 0)
            ic.commitText("${suggestion.text}$separator", 1)
            recordUndoEdit(inserted = "${suggestion.text}$separator", original = typedBefore + wordAfter + if (spaceAfter) " " else "")
        } else if (separator.isNotEmpty()) {
            if (spaceAfter) ic.deleteSurroundingText(0, 1)
            ic.commitText(separator, 1)
            recordUndoEdit(inserted = separator, original = if (spaceAfter) " " else "")
        }
        ic.endBatchEdit()

        pendingAutocorrection = if (suggestion.replacesOnSpace) {
            suggestions.recordCorrection(typed, suggestion.text)
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = separator)
        } else {
            null
        }
        learnFromTyping() // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        syncAutoCapitalization()
        applyState()
        updateCorrectionBarVisibility()
    }

    /**
     * Touche sur une adresse e-mail suggérée : elle remplace le début d'adresse tapé, suivie d'une espace (sauf dans un
     * champ d'adresse, où il n'y en a pas à ajouter).
     */
    private fun insertSuggestedEmail(ic: InputConnection, email: String) {
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val typed = EmailText.trailingToken(before, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND) ?: return
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        pendingAutocorrection = null
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        val insertedEmail = if (fieldType == FieldType.EMAIL) email else "$email "
        ic.commitText(insertedEmail, 1)
        ic.endBatchEdit()
        recordUndoEdit(inserted = insertedEmail, original = typed)
        learnFromTyping() // l'adresse choisie compte comme une adresse tapée
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
        recordUndoEdit(inserted = "$word ", original = "")
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

    /**
     * Séparateur ajouté après un mot suggéré touché : une espace, sauf si une virgule suit déjà le mot
     * ([textAfter] = texte après le curseur, [wordAfter] = suite du mot), pour ne pas obtenir « mot , ».
     */
    private fun separatorAfterSuggestion(textAfter: String, wordAfter: String): String =
        if (textAfter.getOrNull(wordAfter.length) == ',') "" else " "

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
        val original = before.takeLast(edit.deleteCount)
        suggestions.recordCorrection(original, edit.insert)
        return AppliedAutocorrection(original = original, corrected = edit.insert, textBefore = before)
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
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(DeletionUndo.ANCHOR_LENGTH, 0)?.toString().orEmpty()
            val offset = selectionStartOffset(ic)
            recordUndoDeletion(selected, before, before, mergeable = false) { offset }
        }
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
        suggestions.habitAutocorrectionUndone(pending.original, pending.corrected)
        Analytics.track(this, AnalyticsEvents.AUTOCORRECT_UNDONE)
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

    /**
     * Enregistre dans l'historique du bouton Annuler une insertion faite par le clavier : [inserted] a pris la place de
     * [original] (vide pour une insertion pure). Rien n'est gardé dans un champ sensible (mot de passe).
     */
    private fun recordUndoEdit(inserted: String, original: String) {
        if (!sensitiveField) undoController.recordEdit(inserted, original)
    }

    /**
     * Enregistre dans l'historique du bouton Annuler un texte effacé avec la touche supprimer (retour arrière, sélection,
     * glissement) : [deleted] se trouvait juste après [anchor]. Rien n'est gardé dans un champ sensible (mot de passe).
     */
    private fun recordUndoDeletion(
        deleted: String,
        anchor: String,
        before: String,
        mergeable: Boolean,
        offsetProvider: () -> Int,
    ) {
        if (!sensitiveField) undoController.recordDeletion(deleted, anchor, before, mergeable, offsetProvider)
    }

    /** Position absolue du début de la sélection (ou du curseur) dans le champ, ou -1 si l'application ne la donne pas. */
    private fun selectionStartOffset(ic: InputConnection): Int {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        if (extracted.startOffset < 0) return -1
        return extracted.startOffset + minOf(extracted.selectionStart, extracted.selectionEnd)
    }

    /** Idem pour une action dont le geste d'annulation est spécifique (correction IA). */
    private fun recordUndoAction(undo: UndoHistory.Undo) {
        if (!sensitiveField) undoController.record(undo)
    }


    /** Décision 3.3 : le bouton Corriger n'est visible que si le champ contient du texte. */
    private fun updateCorrectionBarVisibility() {
        clipboard.refreshPasteSuggestion() // story 2.2 : la puce suit l'état courant (dictée, correction, frappe, expiration…)
        correctionBar.setUndoAvailable(undoController.canUndo) // bouton Annuler : actif tant qu'une action est annulable
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
        keyboardView.isEnterLongPressEnabled = MultilingualChoice.isActive(keyboardPreferences.multilingualLanguages)
    }

    companion object {
        private const val TAG = "TaipoIme"

        /** « Remplacer le texte » : nombre maximal de caractères lus de part et d'autre du curseur pour vider le champ. */
        private const val REPLACE_MAX_CHARS = 10_000

        /** Longueur du texte retenu quand une autocorrection est annulée (voir [revertedAutocorrectionTail]). */
        private const val REVERTED_TAIL_LENGTH = 12
    }
}
