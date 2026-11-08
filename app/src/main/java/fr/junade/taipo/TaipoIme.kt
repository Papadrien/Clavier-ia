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
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputMethodSubtype
import androidx.annotation.RequiresApi
import androidx.core.view.inputmethod.EditorInfoCompat
import fr.junade.taipo.ai.AiTranscriptionEngine
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.ai.ProtectedWords
import fr.junade.taipo.ai.TranscriptionPromptPreferences
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.dictionary.WordSuggestion
import fr.junade.taipo.emoji.EmojiCatalog
import fr.junade.taipo.emoji.MessagingFieldPolicy
import fr.junade.taipo.suggestion.EmailText
import fr.junade.taipo.suggestion.NextWordRepository
import fr.junade.taipo.suggestion.SuggestionPolicy
import fr.junade.taipo.suggestion.WordText
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.dictionary.SystemDictionaryWords
import fr.junade.taipo.dictionary.SystemUserDictionary
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelAvailability
import fr.junade.taipo.model.ModelPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        override fun editorInfo(): EditorInfo? = this@TaipoIme.currentInputEditorInfo
        override fun isInputViewShown(): Boolean = this@TaipoIme.isInputViewShown
        override fun string(resId: Int): String = this@TaipoIme.getString(resId)
        override fun hasSelection(): Boolean = this@TaipoIme.fieldEditor.hasSelection()
        override fun activeModel(): AiModel? =
            ModelAvailability.usable(this@TaipoIme.modelPreferences.activeModel(), this@TaipoIme.modelPreferences::isInstalled)
        override fun personalWords(): List<String> = this@TaipoIme.knownPersonalWords()
        override fun protectedWordsIn(text: String): List<String> = this@TaipoIme.protectedWordsIn(text)

        override fun haptic() = this@TaipoIme.hapticFeedback.perform(this@TaipoIme.hapticIntensity)
        override fun playBarButtonSound() {
            if (this@TaipoIme.barSoundEnabled) this@TaipoIme.keySounds.play(KeySound.BAR_BUTTON)
        }
        override fun playAiButtonSound() {
            if (this@TaipoIme.barSoundEnabled) this@TaipoIme.keySounds.play(KeySound.AI_BUTTON)
        }
        override fun playSound(sound: KeySound) {
            if (this@TaipoIme.barSoundEnabled) this@TaipoIme.keySounds.play(sound)
        }
        override fun playKeySound(sound: KeySound) {
            if (this@TaipoIme.keySoundEnabled) this@TaipoIme.keySounds.play(sound)
        }
        override fun playVoiceSound(start: Boolean) {
            if (this@TaipoIme.barSoundEnabled) {
                this@TaipoIme.keySounds.play(if (start) KeySound.VOICE_START else KeySound.VOICE_END)
            }
        }
        override fun hapticCursorMove() =
            this@TaipoIme.hapticFeedback.perform(this@TaipoIme.hapticIntensity.cursorMoveFeedback())
        override fun hapticAi(cue: AiHapticCue) = this@TaipoIme.hapticFeedback.perform(cue, this@TaipoIme.hapticIntensity)
        override fun clearHighlight() = this@TaipoIme.clearHighlightIfNeeded()
        override fun clearPendingAutocorrection() {
            this@TaipoIme.autocorrections.pending = null
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
        override fun applyState() = this@TaipoIme.layoutApplier.apply()
        override fun updateCorrectionBarVisibility() = this@TaipoIme.updateCorrectionBarVisibility()
        override fun afterPromptEdit() = this@TaipoIme.promptKeys.afterEdit()
        override fun deleteSelectedText(): Boolean = this@TaipoIme.fieldEditor.deleteSelectedText()
        override fun deleteLastCluster() = this@TaipoIme.fieldEditor.deleteLastCluster()
        override fun insertGeneratedText(text: String): Boolean = this@TaipoIme.fieldEditor.insertGeneratedText(text)
        override fun replaceFieldText(text: String): Boolean = this@TaipoIme.fieldEditor.replaceFieldText(text)
        override fun onWordSuggestionTapped(suggestion: WordSuggestion) = this@TaipoIme.onWordSuggestionTapped(suggestion)
        override fun onKeyPressed(key: Key) = this@TaipoIme.onKeyPressed(key)
        override fun onCursorMoved(steps: Int) = this@TaipoIme.onCursorMoved(steps)
        override fun onGlobeSelected() = this@TaipoIme.languages.onGlobeSelected()
        override fun onOneHandedToggled() = this@TaipoIme.oneHanded.toggle()
        override fun onOneHandedSideSwitched() = this@TaipoIme.oneHanded.switchSide()
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

    /** Revue UX : astuces de gestes, une fois chacune, dans le bandeau du clavier. */
    private val gestureTips by lazy { GestureTips(applicationContext) }

    /** Dernier déplacement du curseur par la barre d'espace (ms) : ce n'est pas un déplacement « à la main » dans le champ. */
    private var lastSpaceCursorMoveAt = 0L

    private fun showGestureTip(tip: GestureTips.Tip?) {
        tip ?: return
        val shown = messagePopup.showTip(
            getString(
                when (tip) {
                    GestureTips.Tip.DELETE_WORDS -> R.string.tip_delete_words
                    GestureTips.Tip.MOVE_CURSOR -> R.string.tip_move_cursor
                    GestureTips.Tip.SWITCH_LANGUAGE -> R.string.tip_switch_language
                    GestureTips.Tip.DIGITS -> R.string.tip_digits
                },
            ),
        )
        if (shown) gestureTips.shown(tip) // pas affichée (clavier pas encore visible) : elle reviendra plus tard
    }
    private val hapticFeedback by lazy { HapticFeedbackPlayer(applicationContext) }
    private val keySounds by lazy { KeySoundPlayer(applicationContext) }

    // Revue de code : parties sorties de l'IME, chacune dans sa classe (elles s'appuient sur imeContext, comme les contrôleurs).
    // Rangée de chiffres, touches du bas et disposition posée sur la vue : KeyboardLayoutApplier (relues à chaque ouverture de champ).
    private val layoutApplier by lazy { KeyboardLayoutApplier(imeContext, controller) { fieldType } }
    private val fieldEditor by lazy { FieldEditor(imeContext) { keyCode -> sendDownUpKeyEvents(keyCode) } }
    private val languages by lazy { ImeLanguages(this, imeContext, controller, gestureTips) }
    private val oneHanded by lazy { OneHandedSwitcher(imeContext) }
    private val memory by lazy { ImeMemoryManager(serviceScope, { voice }, { llmHost }, { isInputViewShown }) }

    // Story 1.11 : niveau de retour haptique, relu de la même façon.
    private var hapticIntensity = HapticIntensity.DEFAULT

    // Son des touches (tic synthétisé), relu de la même façon.
    private var keySoundEnabled = KeyboardPreferences.DEFAULT_KEY_SOUND
    private var barSoundEnabled = KeyboardPreferences.DEFAULT_BAR_SOUND
    private var keyboardHeight = KeyboardHeight.DEFAULT

    private val personalDictionary by lazy { PersonalDictionaryProvider.repository(applicationContext) }

    // Dictionnaire utilisateur du système (lecture seule, permission READ_USER_DICTIONARY) : ses mots s'ajoutent à
    // ceux du dictionnaire personnel pour l'autocorrection (jamais corrigés, proposés comme candidats).
    private val systemDictionary by lazy { SystemUserDictionary.get(applicationContext) }
    private val personalWordsCache = SystemDictionaryWords.MergeCache()
    private var systemDictionaryGranted = false

    /** Mots de Taipo + mots du dictionnaire système pour la langue active : appelé à chaque frappe, sans blocage. */
    private fun knownPersonalWords(): List<String> =
        personalWordsCache.merge(personalDictionary.snapshot(), systemDictionary.words(controller.state.language))

    /**
     * (Re)lit le dictionnaire système si la permission vient d'être accordée ou retirée (réglée dans l'écran du
     * dictionnaire personnel, donc entre deux ouvertures du clavier) ; la lecture se fait hors du thread principal.
     */
    private fun refreshSystemDictionaryIfNeeded() {
        val granted = SystemUserDictionary.isAccessGranted(applicationContext)
        if (granted == systemDictionaryGranted) return
        systemDictionaryGranted = granted
        serviceScope.launch(Dispatchers.IO) {
            systemDictionary.refresh()
            withContext(Dispatchers.Main) { suggestions.invalidate() }
        }
    }

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

    /** Dernière autocorrection (annulable par Supprimer) et mot rétabli : voir [AutocorrectionMemory]. */
    private val autocorrections = AutocorrectionMemory()

    /** Frappe en mode prompt (tampon du prompt au lieu du champ) : voir [PromptKeyHandler]. */
    private val promptKeys by lazy { PromptKeyHandler(this, imeContext, controller, autocorrections) }

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


    /**
     * Revue de code (performance) : lectures du champ mises en cache ([InputTextCache]). Toute la saisie passe par
     * [getCurrentInputConnection], qui renvoie la connexion enveloppée : ni les contrôleurs ni Android n'ont à le savoir.
     */
    private val inputTextCache = InputTextCache(SystemClock::uptimeMillis)
    private var cachingConnection: CachingInputConnection? = null

    override fun getCurrentInputConnection(): InputConnection? {
        val target = super.getCurrentInputConnection() ?: return null
        cachingConnection?.takeIf { it.wrapped === target }?.let { return it }
        inputTextCache.reset() // nouvelle connexion : autre champ, rien n'est connu
        return CachingInputConnection(target, inputTextCache).also { cachingConnection = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Champ (re)lancé : le texte a pu changer ; la sélection initiale donnée par l'éditeur sert de point de départ.
        inputTextCache.reset(attribute?.initialSelStart ?: -1, attribute?.initialSelEnd ?: -1)
    }

    override fun onCreate() {
        super.onCreate()
        KeyboardTheme.updateSystemConfiguration(resources.configuration)
        // Story 1.4 : l'ouverture de la base chiffrée du dictionnaire
        // personnel est asynchrone ; on la déclenche dès la création du
        // service pour qu'elle soit prête avant la première frappe.
        personalDictionary
        nextWords // l'ouverture du modèle appris est asynchrone : on la déclenche dès la création
        suggestions.correctionHabits // idem pour les habitudes de correction
        clipboard.start() // bases du presse-papiers + retour de l'écran de modification (story 2.6)
        suggestions.preloadDictionaries()
        preloadEmojiCatalog()
        layoutApplier.refreshNumberRow()
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

    /** Thème avec lequel les vues du clavier ont été créées pour la dernière fois (voir [recomposeIfThemeChanged]). */
    private var composedThemeLook: KeyboardTheme.Look? = null

    override fun onCreateInputView(): View {
        composedThemeLook = KeyboardTheme.look
        return viewComposer.compose()
    }

    /**
     * Les vues lisent leurs couleurs à leur création : si le thème a changé depuis (réglage modifié pendant que le clavier était
     * masqué), on les recrée comme à une rotation. Sans changement, rien n'est refait.
     */
    private fun recomposeIfThemeChanged() {
        if (!viewComposer.isComposed || composedThemeLook == KeyboardTheme.look) return
        messagePopup.dismiss()
        setInputView(onCreateInputView())
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        recomposeIfThemeChanged()
        // Lot 3.6 : section de trace pour mesurer l'ouverture du clavier (le `return` reste local à la fonction).
        // Même champ relancé par l'application (restarting) : l'annulation reste possible, le texte est retrouvé par son contenu.
        if (!restarting) undoController.clear(notify = false)
        traced(Sections.START_INPUT_VIEW) { startInputView(info) }
    }

    private fun startInputView(info: EditorInfo?) {
        if (!viewComposer.isComposed) return
        layoutApplier.invalidateSettings() // réglages modifiés dans l'application pendant que le clavier était masqué
        // onCurrentInputMethodSubtypeChanged() ne se déclenche que sur un
        // *changement* de subtype : on resynchronise ici explicitement au cas
        // où le subtype actif (choisi avant l'affichage du clavier, ou par
        // défaut au premier lancement) n'a jamais généré de callback.
        layoutApplier.refreshNumberRow()
        hapticIntensity = keyboardPreferences.hapticIntensity
        refreshKeySound()
        keyboardHeight = keyboardPreferences.keyboardHeight
        keyboardView.heightScale = keyboardHeight.scale
        keyboardView.bottomMarginDp = keyboardPreferences.bottomMarginDp
        oneHanded.apply()
        keyboardView.showTouchZones = keyboardPreferences.showTouchZones
        emojiController.hidePanel(resync = false)
        clipboard.hidePanel(resync = false)
        correctionBar.collapseMenu()
        suggestions.suggestionsAllowed = SuggestionPolicy.allowsSuggestions(info?.inputType ?: 0)
        // IME_FLAG_NO_PERSONALIZED_LEARNING : navigation privée, champ confidentiel... rien n'est appris ni proposé.
        suggestions.learningAllowed = suggestions.suggestionsAllowed &&
            SuggestionPolicy.allowsLearning(info?.inputType ?: 0) &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        suggestions.refreshHabitLearning(keyboardPreferences.isHabitLearningEnabled)
        // Adresses e-mail : proposées et apprises aussi dans les champs d'adresse (là où les mots ne le sont pas), jamais en navigation privée.
        suggestions.emailFieldAllowed = SuggestionPolicy.allowsEmailSuggestions(info?.inputType ?: 0) &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        clipboard.pasteAllowedInField = SuggestionPolicy.allowsPasteSuggestion(info?.inputType ?: 0)
        // Types d'images que le champ accepte (capture d'écran proposée au collage) : vide = le champ n'accepte que du texte.
        clipboard.fieldImageMimeTypes = info?.let { EditorInfoCompat.getContentMimeTypes(it).toList() }.orEmpty()
        fieldType = FieldType.of(info?.inputType ?: 0)
        layoutApplier.refreshNumberRow() // rangée de chiffres réservée aux champs mot de passe : dépend du type du champ qui vient de s'ouvrir
        // Adresse web (barre d'adresse et de recherche d'un navigateur) : mots proposés, mais rien n'est remplacé tout seul.
        suggestions.emojiAllowed = SuggestionPolicy.allowsEmojiSuggestions(info?.inputType ?: 0)
        suggestions.autocorrectsInField = autocorrectionAllowed()
        // Story 10.1 : champ de mot de passe, actions IA et Smart Clipboard grisés (touchables, avec un message).
        sensitiveField = SensitiveFieldPolicy.isSensitive(info?.inputType ?: 0)
        Analytics.sensitiveFieldActive = sensitiveField // mesure d'usage coupée tant qu'un champ de mot de passe est affiché
        // Zones de frappe adaptatives : jamais dans un champ sensible, d'adresse e-mail ou web (lettres hors langage courant).
        suggestions.nextLettersAllowed = keyboardPreferences.isAdaptiveTouchEnabled && suggestions.suggestionsAllowed &&
            !sensitiveField && fieldType != FieldType.URL
        keyboardView.setNextLetterPrediction(null) // le nouveau champ repart sans prédiction, la suivante vient du texte du champ
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
        refreshSystemDictionaryIfNeeded()
        controller.setLanguage(languages.current())
        languages.track()
        suggestions.preloadDictionaries() // la langue a pu changer dans les paramètres : charge son dictionnaire si besoin
        controller.reset()
        syncAutoCapitalization()
        correction.clearHighlightState()
        layoutApplier.apply()
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
        // Astuce : clavier multilingue, comment changer de langue (une fois, quand le clavier est affiché).
        if (MultilingualChoice.isActive(keyboardPreferences.multilingualLanguages)) {
            keyboardView.postDelayed({ showGestureTip(gestureTips.onMultilingualField()) }, LANGUAGE_TIP_DELAY_MS)
        }
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        controller.setLanguage(languages.current())
        if (viewComposer.isComposed) {
            syncAutoCapitalization()
            layoutApplier.apply()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // Avant super : Android y recrée la vue du clavier, qui doit déjà connaître le thème de l'appareil (clair ou sombre).
        KeyboardTheme.updateSystemConfiguration(newConfig)
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
        memory.onWindowHidden() // clavier masqué longtemps : les gros moteurs (IA, dictée) sont libérés
    }

    override fun onWindowShown() {
        super.onWindowShown()
        memory.onWindowShown()
    }

    /** Android manque de mémoire : les gros moteurs sont libérés selon [MemoryRelease.shouldRelease]. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        memory.onTrimMemory(level)
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
        inputTextCache.onSelectionUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd) // avant toute lecture du champ
        // Mode prompt : un tap de l'utilisateur dans le champ de l'application (curseur ou sélection déplacés,
        // hors écho de nos propres insertions comme « Ajouter le texte ») ferme le mode et lui rend le champ.
        val selectionMoved = newSelStart != oldSelStart || newSelEnd != oldSelEnd
        if (viewComposer.isComposed && prompt.active && selectionMoved && !correction.isSelfEditEcho()) {
            prompt.onFieldTapped()
        }
        // Dictée en cours : un toucher sur un mot du champ (à corriger) ou l'écho de nos insertions.
        if (isRecording) voice.onCursorMoved(oldSelEnd, newSelStart, newSelEnd, suggestions.typedRecently())
        correction.onSelectionUpdated(newSelStart, newSelEnd)
        // Astuce : le curseur posé à la main dans le champ (pas par la frappe, la dictée, l'IA ni la barre d'espace).
        val manualMove = viewComposer.isComposed && newSelStart == newSelEnd && newSelStart != oldSelStart &&
            !prompt.active && !isRecording && !correctionInProgress && !correction.isSelfEditEcho() &&
            !suggestions.typedRecently() &&
            android.os.SystemClock.uptimeMillis() - lastSpaceCursorMoveAt > SPACE_MOVE_ECHO_MS
        if (manualMove) showGestureTip(gestureTips.onManualCursorMove())
        if (!correctionInProgress && viewComposer.isComposed) {
            updateCorrectionBarVisibility()
        }
        // Le curseur a pu bouger pour une raison hors de notre contrôle (tap de
        // l'utilisateur ailleurs dans le champ, action d'une autre fonctionnalité
        // comme la correction ou la saisie vocale) : la majuscule automatique
        // (story 1.2) doit rester synchronisée avec le nouveau contexte.
        if (viewComposer.isComposed) {
            syncAutoCapitalization()
            layoutApplier.apply()
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

    /** Relit les réglages du son du clavier et des sons de la barre supérieure ; prépare les sons (une seule fois) si l'un est activé. */
    private fun refreshKeySound() {
        keySoundEnabled = keyboardPreferences.isKeySoundEnabled
        barSoundEnabled = keyboardPreferences.isBarSoundEnabled
        keySounds.volumePercent = keyboardPreferences.keySoundVolumePercent
        if (keySoundEnabled || barSoundEnabled) keySounds.prepare()
    }

    private fun onKeyPressed(key: Key) {
        hapticFeedback.perform(hapticIntensity)
        messagePopup.dismissBannerOnKey() // un message d'information s'efface à la frappe suivante (une astuce reste)
        if (keySoundEnabled) keySounds.play(KeySound.forAction(key.action))
        suggestions.markTyping() // le curseur qui bouge ensuite suit la frappe (suggestions de complétion)
        clearHighlightIfNeeded()
        // Astuces : ⌫ appuyé plusieurs fois de suite (effacer des mots en glissant), chiffre tapé depuis les symboles.
        val action = key.action
        val digitFromSymbols = action is KeyAction.TypeChar && action.char.isDigit() &&
            controller.state.activeLayout != LayoutId.LETTERS && !layoutApplier.numberRowEnabled
        showGestureTip(gestureTips.onKey(backspace = action == KeyAction.Backspace, digitFromSymbols = digitFromSymbols))

        // Story 5.1 : en mode prompt, tout passe par le tampon du prompt, avec les mêmes traitements que
        // le champ de l'application (autocorrection et annulation, double espace, apprentissage,
        // suggestions) ; rien n'est écrit dans le champ de l'application.
        if (prompt.active) {
            promptKeys.onKey(key)
            return
        }

        // Story 1.15 : la touche emoji ouvre le panneau, sans saisir de texte.
        if (key.action == KeyAction.Emoji) {
            autocorrections.pending = null
            emojiController.showPanel()
            return
        }

        // Suppression juste après une autocorrection : on annule la correction au lieu d'effacer un caractère.
        if (key.action == KeyAction.Backspace && undoLastAutocorrection()) {
            onUserTyped()
            syncAutoCapitalization()
            layoutApplier.apply()
            updateCorrectionBarVisibility()
            return
        }
        autocorrections.pending = null

        // Suppression avec une sélection : on efface la sélection, pas le caractère avant son début.
        if (key.action == KeyAction.Backspace && fieldEditor.deleteSelectedText()) {
            onUserTyped()
            syncAutoCapitalization()
            layoutApplier.apply()
            updateCorrectionBarVisibility()
            return
        }

        // Story 1.3 : dictionnaire local pour l'autocorrection (pas d'IA, pas
        // d'apprentissage auto) — le mot qui vient de se terminer est vérifié
        // juste avant que la touche de ponctuation/espace/entrée qui le
        // termine ne soit elle-même traitée.
        val autocorrection = if (TypingRules.isWordBoundaryKey(key) && autocorrectionAllowed()) applyDictionaryAutocorrection() else null

        // Double espace : l'espace précédent est remplacé par ". " (pas de sélection active).
        val doubleSpaceContext = if (key.action == KeyAction.Space && autocorrection == null && !fieldEditor.hasSelection() &&
            fieldType.doubleSpacePeriod
        ) {
            currentInputConnection?.getTextBeforeCursor(2, 0)?.toString()
        } else null

        val result = controller.onKey(key, doubleSpaceContext, SystemClock.uptimeMillis())

        // La suppression précède l'insertion (double espace : on retire l'espace avant d'ajouter ". ").
        if (result.deleteBefore > 0) {
            if (key.action == KeyAction.Backspace) fieldEditor.deleteLastCluster() else fieldEditor.deleteBeforeCursor(result.deleteBefore)
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
            autocorrections.pending = AppliedAutocorrection(autocorrection.original, autocorrection.corrected, boundary)
            suggestions.habitAutocorrectionApplied(autocorrection.textBefore, autocorrection.original, autocorrection.corrected)
            recordUndoEdit(inserted = autocorrection.corrected + boundary, original = autocorrection.original + boundary)
        }
        if (result.isEnter) {
            fieldEditor.pressEnter()
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
        layoutApplier.apply()
        updateCorrectionBarVisibility()
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
     * emoji, suggestion, collage, suppression, dictée). Il referme le menu du robot : retour aux
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
     * Story 1.7 : glissement sur la barre espace. Déplace le curseur de [steps] caractères
     * (négatif = vers la gauche) via des touches directionnelles, ce qui replie aussi une
     * éventuelle sélection et respecte les caractères composés du champ.
     */
    private fun onCursorMoved(steps: Int) {
        lastSpaceCursorMoveAt = android.os.SystemClock.uptimeMillis()
        gestureTips.learned(GestureTips.Tip.MOVE_CURSOR)
        if (prompt.active) {
            hapticFeedback.perform(hapticIntensity.cursorMoveFeedback())
            if (keySoundEnabled) keySounds.play(KeySound.CURSOR_MOVE)
            prompt.buffer.moveCursor(steps)
            prompt.refreshInput()
            syncAutoCapitalization()
            layoutApplier.apply()
            return
        }
        val ic = currentInputConnection ?: return
        // Début ou fin du texte : une touche directionnelle que le champ ne peut plus absorber déplace le focus et le curseur
        // quitte le champ. On n'envoie donc que les pas qui restent dans le texte (voir CursorSteps).
        val allowed = abs(allowedCursorSteps(ic, steps))
        if (allowed == 0) return
        clearHighlightIfNeeded()
        autocorrections.pending = null
        val keyCode = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        hapticFeedback.perform(hapticIntensity.cursorMoveFeedback()) // toujours faible, absent si désactivé
        if (keySoundEnabled) keySounds.play(KeySound.CURSOR_MOVE) // un cran « goutte » par déplacement, pas aux bords du texte
        repeat(allowed) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        syncAutoCapitalization()
        layoutApplier.apply()
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
        return CursorSteps.allowed(steps, before, after, fieldEditor.hasSelection())
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
            promptKeys.onWordSuggestionTapped(suggestion)
            return
        }
        if (suggestion !in currentWordSuggestions) return
        // Pendant la dictée, seules les alternatives d'un mot touché pour le corriger sont proposées (voir VoiceWordPick).
        if ((isRecording && !voice.wordPickActive) || correctionInProgress || fieldEditor.hasSelection()) return
        val ic = currentInputConnection ?: return
        if (suggestion.kind == WordSuggestion.Kind.PREDICTION) {
            insertPredictedWord(ic, suggestion.text)
            return
        }
        if (suggestion.kind == WordSuggestion.Kind.EMAIL) {
            insertSuggestedEmail(ic, suggestion.text)
            return
        }
        if (suggestion.kind == WordSuggestion.Kind.COMPOUND) {
            insertSuggestedCompound(ic, suggestion.text)
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
        val separator = TypingRules.separatorAfterSuggestion(textAfter, wordAfter)
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()

        val cursorBefore = lastSelectionEnd // avant la modification : sert à replacer le curseur d'une dictée en cours
        var lengthDelta = 0
        ic.beginBatchEdit()
        // Le mot tel que tapé peut différer du mot affiché à gauche : forme d'origine d'un mot déjà corrigé.
        if (suggestion.kind != WordSuggestion.Kind.TYPED || wordAfter.isNotEmpty() || suggestion.text != typed) {
            ic.deleteSurroundingText(typedBefore.length, wordAfter.length + if (spaceAfter) 1 else 0)
            ic.commitText("${suggestion.text}$separator", 1)
            lengthDelta = "${suggestion.text}$separator".length - (typedBefore.length + wordAfter.length + if (spaceAfter) 1 else 0)
            recordUndoEdit(inserted = "${suggestion.text}$separator", original = typedBefore + wordAfter + if (spaceAfter) " " else "")
        } else if (separator.isNotEmpty()) {
            if (spaceAfter) ic.deleteSurroundingText(0, 1)
            ic.commitText(separator, 1)
            lengthDelta = separator.length - if (spaceAfter) 1 else 0
            recordUndoEdit(inserted = separator, original = if (spaceAfter) " " else "")
        }
        ic.endBatchEdit()
        // Mot corrigé pendant la dictée : le curseur retourne où elle en était, la suite s'écrit au bout du texte.
        if (isRecording) voice.onWordReplaced(cursorBefore, lengthDelta)

        autocorrections.pending = if (suggestion.replacesOnSpace) {
            suggestions.recordCorrection(typed, suggestion.text)
            AppliedAutocorrection(original = typed, corrected = suggestion.text, boundary = separator)
        } else {
            null
        }
        learnFromTyping() // le mot choisi (complété ou corrigé) est celui qui compte pour la prédiction
        syncAutoCapitalization()
        layoutApplier.apply()
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
        autocorrections.pending = null
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        val insertedEmail = if (fieldType == FieldType.EMAIL) email else "$email "
        ic.commitText(insertedEmail, 1)
        ic.endBatchEdit()
        recordUndoEdit(inserted = insertedEmail, original = typed)
        learnFromTyping() // l'adresse choisie compte comme une adresse tapée
        syncAutoCapitalization()
        layoutApplier.apply()
        updateCorrectionBarVisibility()
    }

    /**
     * Touche sur un composé proposé (allemand : « Haus tür » -> « Haustür ») : il remplace les DEUX derniers mots tapés (et l'espace entre eux),
     * suivi d'une espace. Rien n'est fait si le texte avant le curseur n'est plus « mot espace mot ».
     */
    private fun insertSuggestedCompound(ic: InputConnection, compound: String) {
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val second = trailingWord(before)
        val first = WordText.wordBeforeSingleSpace(before.dropLast(second.length))
        if (second.isEmpty() || first.isEmpty()) return
        val original = "$first $second"
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        autocorrections.pending = null
        ic.beginBatchEdit()
        ic.deleteSurroundingText(original.length, 0)
        ic.commitText("$compound ", 1)
        ic.endBatchEdit()
        recordUndoEdit(inserted = "$compound ", original = original)
        learnFromTyping() // le composé choisi compte comme un mot tapé
        syncAutoCapitalization()
        layoutApplier.apply()
        updateCorrectionBarVisibility()
    }

    /** Touche sur un mot prédit : il s'ajoute au curseur (après l'espace déjà tapée), suivi d'une espace. */
    private fun insertPredictedWord(ic: InputConnection, word: String) {
        hapticFeedback.perform(hapticIntensity)
        onUserTyped()
        clearHighlightIfNeeded()
        autocorrections.pending = null
        ic.commitText("$word ", 1)
        recordUndoEdit(inserted = "$word ", original = "")
        learnFromTyping()
        syncAutoCapitalization()
        layoutApplier.apply()
        updateCorrectionBarVisibility()
    }

    // ------------------------------------------------------------------
    // Dictionnaire local / autocorrection (story 1.3)
    // ------------------------------------------------------------------

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
        val edit = suggestions.autocorrectionFor(before, autocorrections.allowContextualRetro(before)) ?: return null

        ic.beginBatchEdit()
        ic.deleteSurroundingText(edit.deleteCount, 0)
        ic.commitText(edit.insert, 1)
        ic.endBatchEdit()
        val original = before.takeLast(edit.deleteCount)
        suggestions.recordCorrection(before, edit)
        return AppliedAutocorrection(original = original, corrected = edit.insert, textBefore = before)
    }

    /**
     * Rétablit le mot tel que tapé si la dernière action était une autocorrection
     * (mot corrigé + espace/ponctuation juste avant le curseur). Le séparateur est conservé :
     * le mot rétabli n'est donc pas recorrigé. Renvoie faux si rien n'a été annulé.
     */
    private fun undoLastAutocorrection(): Boolean {
        val pending = autocorrections.pending ?: return false
        autocorrections.pending = null
        if (pending.isUndoWindowExpired()) return false // trop tard : Supprimer efface un caractère comme d'habitude
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return false
        val expected = pending.corrected + pending.boundary
        val before = ic.getTextBeforeCursor(expected.length, 0)?.toString() ?: return false
        if (before != expected) return false // le texte ou le curseur a changé entre-temps

        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(pending.original + pending.boundary, 1)
        ic.endBatchEdit()
        autocorrections.onReverted(pending.original + pending.boundary)
        suggestions.habitAutocorrectionUndone(pending.original, pending.corrected)
        Analytics.track(this, AnalyticsEvents.AUTOCORRECT_UNDONE)
        return true
    }

    // ------------------------------------------------------------------
    // Correction IA (épopée 3) : voir CorrectionAiController (lot 2.3 de la revue)
    // ------------------------------------------------------------------

    /** Mots du dictionnaire personnel présents dans [text] : signalés à l'IA pour qu'elle ne les corrige pas. */
    private fun protectedWordsIn(text: String): List<String> =
        ProtectedWords.inText(text, knownPersonalWords())

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

    companion object {
        private const val TAG = "TaipoIme"

        /** Astuces : délai avant celle de la langue (le clavier finit d'apparaître), écho d'un déplacement par l'espace. */
        private const val LANGUAGE_TIP_DELAY_MS = 800L
        private const val SPACE_MOVE_ECHO_MS = 600L
    }
}
