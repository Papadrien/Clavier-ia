package fr.junade.taipo

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import androidx.autofill.inline.v1.InlineSuggestionUi
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import fr.junade.taipo.ai.ChangedRange
import fr.junade.taipo.ai.CorrectedSentenceMemory
import fr.junade.taipo.ai.CorrectionDiff
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.CorrectionPlanner
import fr.junade.taipo.ai.CorrectionSafeguard
import fr.junade.taipo.ai.ProtectedWords
import fr.junade.taipo.ai.TextBlock
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.ai.VoiceRecorder
import fr.junade.taipo.clipboard.ClipboardEditBridge
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
import fr.junade.taipo.emoji.MessagingFieldPolicy
import fr.junade.taipo.emoji.RecentEmojiBarView
import fr.junade.taipo.emoji.RecentEmojis
import fr.junade.taipo.suggestion.EmojiSuggesterLoader
import fr.junade.taipo.suggestion.NextWordModel
import fr.junade.taipo.suggestion.NextWordProvider
import fr.junade.taipo.suggestion.SuggestionPolicy
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.VoiceModelPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

    // Story 2.9 : historique des copies (1 h, chiffré), dans la même base que les éléments épinglés.
    private val clipHistoryRepository by lazy { ClipboardProvider.historyRepository(applicationContext) }
    private var clipboardPanelJob: Job? = null
    private val pasteExpiryRunnable = Runnable { refreshPasteSuggestion() }

    // Story 1.16 : emoji suggéré d'après le dernier mot (4e emplacement de la barre de suggestions).
    private val emojiSuggester by lazy { EmojiSuggesterLoader.get(applicationContext) }
    private var currentEmojiSuggestion: String? = null

    // Prédiction du mot suivant d'après les habitudes d'écriture (apprentissage local, chiffré).
    private val nextWords by lazy { NextWordProvider.repository(applicationContext) }

    /** Faux dans les champs sans suggestions et quand l'application demande de ne pas apprendre (mode privé). */
    private var learningAllowed = true

    // Story 1.17 : mots suggérés d'après le mot en cours de frappe (3 premiers emplacements).
    private var currentWordSuggestions: List<WordSuggestion?> = emptyList()

    // Entrée de la dernière mise à jour des suggestions : évite de tout recalculer quand la même
    // mise à jour est demandée deux fois de suite (touche puis onUpdateSelection).
    private var lastSuggestionInput: SuggestionInput? = null

    private data class SuggestionInput(val text: String, val language: KeyboardLanguage, val available: Boolean)

    /** Faux dans les champs sans suggestions (mot de passe, e-mail, URL, nombre...), relu à chaque champ. */
    private var suggestionsAllowed = true

    /** Pop-up d'information en cours (remplace les toasts), s'il y en a un. */
    private var messageDialog: AlertDialog? = null

    /** Barre des emojis récents (champs de messagerie), au-dessus de la barre du haut. */
    private lateinit var recentEmojiBar: RecentEmojiBarView

    /** Vrai quand le champ courant est celui d'une messagerie, relu à chaque champ. */
    private var messagingField = false

    /** Story 1.18 : type du champ courant (e-mail, URL, numérique...), fixé à chaque ouverture de champ. */
    private var fieldType = FieldType.TEXT

    /** Faux dans les champs de mot de passe : la puce de collage y est masquée (story 10.1), relu à chaque champ. */
    private var pasteAllowedInField = true

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

    /** Numéro de la dernière réponse d'auto-remplissage en ligne : les vues d'une réponse périmée sont ignorées. */
    private var inlineGeneration = 0

    // Démarrage en cours (chargement du modèle puis ouverture du micro) : annulable tant que
    // l'écoute n'a pas réellement commencé (second appui, relâchement en mode maintenu, clavier fermé).
    private var voiceStartJob: Job? = null
    private var insertedPartialText = ""

    // Édition pendant la dictée : l'hypothèse du décodeur couvre toute la session depuis son début,
    // alors que le texte du champ peut avoir été modifié ou le curseur déplacé par l'utilisateur.
    // [voiceFrozenLength] = nombre de caractères de l'hypothèse déjà « remis » à l'utilisateur (ils
    // font désormais partie de son texte et ne sont plus jamais touchés ni réinsérés) ;
    // [voiceLastHypothesis] = dernière hypothèse effectivement appliquée au champ.
    private var voiceFrozenLength = 0
    private var voiceLastHypothesis = ""
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
        nextWords
        clipboardRepository
        clipHistoryRepository
        ClipboardEditBridge.onLastClipEdited = { text -> onLastClipEdited(text) } // story 2.6
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
        keyboardView.setOnDeleteSwipeListener(object : KeyboardView.OnDeleteSwipeListener {
            override fun onDeleteSwipeUpdate(words: Int) = this@ClavierIme.onDeleteSwipeUpdate(words)
            override fun onDeleteSwipeRelease() = this@ClavierIme.onDeleteSwipeRelease()
            override fun onDeleteSwipeCancel() = this@ClavierIme.onDeleteSwipeCancel()
        })

        recentEmojiBar = RecentEmojiBarView(this)
        recentEmojiBar.visibility = View.GONE
        recentEmojiBar.setOnEmojiClickListener { emoji -> onRecentEmojiBarTapped(emoji) }

        correctionBar = CorrectionBarView(this)
        correctionBar.setOnCorrectListener { onCorrectClicked() }
        correctionBar.voiceButton.setOnTouchListener { _, event -> onVoiceButtonTouch(event) }
        correctionBar.setOnEmojiSuggestionClickListener { onEmojiSuggestionTapped() }
        // Le même bouton ouvre le panneau et, tant qu'il est ouvert (bouton coloré), le referme.
        correctionBar.setOnClipboardClickListener {
            if (clipboardPanelVisible()) {
                hapticFeedback.perform(hapticIntensity)
                hideClipboardPanel()
            } else {
                showClipboardPanel()
            }
        }
        correctionBar.setOnClipboardCloseClickListener { hideClipboardPanel() }
        correctionBar.setOnSettingsClickListener { openAppHome() }
        correctionBar.setOnPasteClickListener { onPasteTapped() }
        correctionBar.setOnWordSuggestionClickListener { suggestion -> onWordSuggestionTapped(suggestion) }

        emojiPanel = EmojiPanelView(this)
        emojiPanel.visibility = View.GONE
        emojiPanel.setOnEmojiSelectedListener { emoji -> onEmojiSelected(emoji) }
        emojiPanel.setOnBackspaceListener { onEmojiBackspace() }
        emojiPanel.setOnCloseListener { hideEmojiPanel() }

        clipboardPanel = ClipboardPanelView(this)
        clipboardPanel.visibility = View.GONE
        clipboardPanel.setOnPasteListener { item -> onClipboardItemTapped(item) }
        clipboardPanel.setOnPinListener { item -> onClipboardItemPin(item) }
        clipboardPanel.setOnEditListener { item -> onClipboardItemEdit(item) }
        clipboardPanel.setOnLabelListener { item -> onClipboardItemLabel(item) }
        clipboardPanel.setOnDeleteLabelListener { item -> onClipboardItemDeleteLabel(item) }
        clipboardPanel.setOnDeleteListener { item -> onClipboardItemDelete(item) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Fond commun à la barre du haut et au clavier (les vues enfants sont transparentes) :
            // les animations de fond futures se dessineront dans ce seul drawable.
            background = KeyboardBackgroundDrawable()
            // Story 1.8 : la bulle d'accents des touches du haut est dessinée par le clavier
            // au-dessus de sa propre zone, par-dessus la barre d'actions.
            clipChildren = false
            addView(
                recentEmojiBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                correctionBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            // Les panneaux (emoji, Smart Clipboard) recouvrent exactement le clavier (le clavier reste
            // mesuré, seulement masqué) : la hauteur est celle du clavier et ne change pas en
            // basculant, même si le contenu d'un panneau est plus haut (rotation et réglage compris).
            addView(
                KeyboardStackLayout(this@ClavierIme).apply {
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
        // IME_FLAG_NO_PERSONALIZED_LEARNING : navigation privée, champ confidentiel... rien n'est appris ni proposé.
        learningAllowed = suggestionsAllowed &&
            (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
        pasteAllowedInField = SuggestionPolicy.allowsPasteSuggestion(info?.inputType ?: 0)
        fieldType = FieldType.of(info?.inputType ?: 0)
        messagingField = MessagingFieldPolicy.isMessagingField(info?.packageName, info?.inputType ?: 0)
        controller.setAutoCapitalization(fieldType.autoCapitalizes)
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
        // Diagnostic temporaire (puce de collage absente dans certaines applis) : aucun texte copié n'est journalisé.
        AppLog.d(
            TAG,
            "puce collage: pkg=${info?.packageName} inputType=0x${Integer.toHexString(info?.inputType ?: 0)} " +
                "suggestionsAllowed=$suggestionsAllowed pasteAllowedInField=$pasteAllowedInField " +
                "copieLue=${clipboardState.lastClip() != null} puce=${clipboardState.suggestion() != null}",
        )
        refreshRecentEmojiBar()
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
        nextWords.flush()
        clearInlineSuggestions()
        dismissMessage()
        if (this::emojiPanel.isInitialized) hideEmojiPanel(resync = false)
        if (this::clipboardPanel.isInitialized) hideClipboardPanel(resync = false)
        clearHighlightState()
        clipboardReader.stop()
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        if (isRecording) {
            cancelVoiceRecording()
        }
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
            .addStyle(InlineSuggestionUi.newStyleBuilder().build())
            .build()
        val spec = InlinePresentationSpec.Builder(
            Size((INLINE_SUGGESTION_MIN_WIDTH_DP * density).toInt(), height),
            Size((INLINE_SUGGESTION_MAX_WIDTH_DP * density).toInt(), height),
        ).setStyle(styles).build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_SUGGESTION_MAX_COUNT)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        val generation = ++inlineGeneration
        if (suggestions.isEmpty() || !this::correctionBar.isInitialized) {
            clearInlineSuggestions()
            return false
        }
        val slots = arrayOfNulls<View>(suggestions.size)
        val wrap = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(this, Size(wrap, wrap), mainExecutor) { view ->
                // Réponse périodiquement remplacée par une plus récente : on ignore les vues en retard.
                if (generation != inlineGeneration || !this::correctionBar.isInitialized) return@inflate
                slots[index] = view
                correctionBar.setInlineSuggestions(slots.filterNotNull())
            }
        }
        return true
    }

    /** Retire les suggestions d'auto-remplissage : la barre retrouve ses suggestions habituelles. */
    private fun clearInlineSuggestions() {
        inlineGeneration++
        if (this::correctionBar.isInitialized) correctionBar.setInlineSuggestions(emptyList())
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
        nextWords.flush()
        dismissMessage()
        correctionEngine.close()
        voiceEngine.close()
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        clipboardReader.stop()
        clipboardState.clear()
        ClipboardEditBridge.onLastClipEdited = null
        ClipboardEditBridge.lastClipText = null
        serviceJob.cancel()
    }

    /**
     * Affiche [message] dans un pop-up avec un bouton OK (les toasts passaient inaperçus). Le pop-up
     * est rattaché à la fenêtre du clavier, qui garde la saisie en cours. Si le clavier n'est plus
     * affiché (pas de fenêtre pour accrocher le pop-up), repli sur un toast.
     */
    private fun showMessage(message: String) {
        val token = if (this::keyboardView.isInitialized && keyboardView.isAttachedToWindow) keyboardView.windowToken else null
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

    private fun showClipboardPanel() {
        if (isRecording || correctionInProgress) return
        hapticFeedback.perform(hapticIntensity)
        correctionBar.collapseMenu() // story 2.4 : au retour des touches, mots ou puce, pas le menu ouvert
        hideEmojiPanel(resync = false)
        clipboardPanel.configure(keyboardView.bottomInsetPx())
        keyboardView.visibility = View.INVISIBLE
        clipboardPanel.visibility = View.VISIBLE
        correctionBar.setClipboardPanelOpen(true)
        refreshRecentEmojiBar()
        clearSuggestions()
        // Stories 2.5 et 2.9 : les cartes suivent les éléments épinglés et l'historique tant que le
        // panneau est ouvert ; les copies expirées sont purgées à l'ouverture.
        refreshClipboardPanel()
        clipboardPanelJob?.cancel()
        clipboardPanelJob = serviceScope.launch {
            launch { clipboardRepository.pinned.collect { refreshClipboardPanel() } }
            launch { clipHistoryRepository.history.collect { refreshClipboardPanel() } }
            launch {
                try {
                    clipHistoryRepository.purgeExpired()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Échec de la purge de l'historique du presse-papiers", t)
                }
            }
        }
    }

    /** Retour aux touches (croix de la barre, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    private fun hideClipboardPanel(resync: Boolean = true) {
        if (!clipboardPanelVisible()) return
        clipboardPanelJob?.cancel()
        clipboardPanelJob = null
        clipboardPanel.closeMenu()
        clipboardPanel.visibility = View.GONE
        correctionBar.setClipboardPanelOpen(false)
        keyboardView.visibility = View.VISIBLE
        refreshRecentEmojiBar()
        if (resync) {
            syncAutoCapitalization()
            applyState()
            updateCorrectionBarVisibility()
        }
    }

    private fun clipboardPanelVisible(): Boolean =
        this::clipboardPanel.isInitialized && clipboardPanel.visibility == View.VISIBLE

    /**
     * Cartes du panneau : dernière copie (même écartée ou collée), copies récentes de l'historique
     * (moins d'1 h), puis éléments épinglés.
     */
    private fun refreshClipboardPanel() {
        if (!clipboardPanelVisible()) return
        clipboardPanel.setItems(
            ClipboardItems.build(
                lastClip = clipboardState.lastClip(),
                pinned = clipboardRepository.snapshot(),
                history = clipHistoryRepository.snapshot(),
                nowMillis = System.currentTimeMillis(),
            ),
        )
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
            showMessage(pinMessage(it))
            return
        }
        serviceScope.launch {
            try {
                val result = clipboardRepository.pin(item.text)
                showMessage(pinMessage(result))
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de l'épinglage", t)
                showMessage(getString(R.string.clipboard_pin_error, t.message ?: t.javaClass.simpleName))
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
     * Story 2.6, menu d'appui long, « Modifier » : ouvre l'écran de modification. Un élément épinglé
     * ou une ligne d'historique (story 2.9) y est identifié par son id ; la dernière copie, qui
     * existe en mémoire ici, lui est passée par [ClipboardEditBridge] et revient par [onLastClipEdited].
     */
    private fun onClipboardItemEdit(item: ClipboardItems.Item) {
        if (!ClipboardItems.canEdit(item)) return
        val intent = Intent(this, ClipboardEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pinnedId = item.pinnedId
        val historyId = item.historyId
        if (pinnedId != null) {
            intent.putExtra(ClipboardEditActivity.EXTRA_PINNED_ID, pinnedId)
        } else if (!item.isLastClip && historyId != null) {
            // Story 2.9 : ligne d'historique (pas la dernière copie) : relue et réécrite par son id.
            intent.putExtra(ClipboardEditActivity.EXTRA_HISTORY_ID, historyId)
        } else {
            ClipboardEditBridge.lastClipText = item.text
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Échec de l'ouverture de l'écran de modification", t)
            ClipboardEditBridge.lastClipText = null
            showMessage(getString(R.string.clipboard_edit_error, t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * Stories 2.7 et 2.8, menu d'appui long, « Ajouter une étiquette » / « Modifier l'étiquette » :
     * ouvre le pop-up de saisie de l'étiquette de l'élément épinglé (identifié par son id).
     */
    private fun onClipboardItemLabel(item: ClipboardItems.Item) {
        if (!ClipboardItems.canLabel(item)) return
        val id = item.pinnedId ?: return
        val intent = Intent(this, ClipboardLabelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ClipboardLabelActivity.EXTRA_PINNED_ID, id)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Échec de l'ouverture du pop-up d'étiquette", t)
            showMessage(getString(R.string.clipboard_label_error, t.message ?: t.javaClass.simpleName))
        }
    }

    /** Story 2.8, « Supprimer l'étiquette » (après confirmation dans le panneau) : le panneau suit le flux des éléments épinglés. */
    private fun onClipboardItemDeleteLabel(item: ClipboardItems.Item) {
        val id = item.pinnedId ?: return
        serviceScope.launch {
            try {
                clipboardRepository.clearLabel(id)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de la suppression d'une étiquette", t)
                showMessage(getString(R.string.clipboard_label_delete_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /** Retour de l'écran de modification pour la dernière copie : le panneau et la puce montrent le nouveau texte. */
    private fun onLastClipEdited(text: String) {
        val previous = clipboardState.lastClip()?.text
        clipboardState.onEdited(text)
        if (previous != null && previous != text) {
            // Story 2.9 : sa ligne d'historique suit, sinon l'ancien texte réapparaîtrait en doublon.
            serviceScope.launch {
                try {
                    clipHistoryRepository.replaceText(previous, text)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Échec de la mise à jour de l'historique après modification", t)
                }
            }
        }
        refreshClipboardPanel()
        refreshPasteSuggestion()
    }

    /**
     * Menu d'appui long, « Supprimer » (après confirmation dans le panneau) : un élément épinglé est
     * retiré de la base ; la dernière copie est retirée du panneau (et de la puce) jusqu'à la
     * prochaine copie ; la ligne d'historique qui porte le même texte (story 2.9) est supprimée
     * aussi, sinon la carte reviendrait aussitôt comme copie récente.
     */
    private fun onClipboardItemDelete(item: ClipboardItems.Item) {
        if (item.isLastClip) {
            clipboardState.onDeleted()
            refreshClipboardPanel()
        }
        val pinnedId = item.pinnedId
        val historyId = item.historyId
        if (pinnedId == null && historyId == null) return
        serviceScope.launch {
            try {
                // Le panneau se met à jour via les flux des éléments épinglés et de l'historique.
                if (pinnedId != null) clipboardRepository.remove(pinnedId)
                if (historyId != null) clipHistoryRepository.remove(historyId)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de la suppression d'un élément du presse-papiers", t)
                showMessage(getString(R.string.clipboard_delete_error, t.message ?: t.javaClass.simpleName))
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
        val isNewCopy = clipboardState.onClipRead(snapshot?.text, snapshot?.copiedAtMillis ?: 0L, snapshot?.sensitive ?: false)
        if (isNewCopy && snapshot != null) recordInClipHistory(snapshot)
    }

    /**
     * Story 2.9 : une nouvelle copie entre dans l'historique chiffré (1 h). Une copie vide ou de
     * plus de 10 000 caractères reste en mémoire du clavier seulement ; une copie sensible est
     * enregistrée comme les autres, avec son drapeau (le panneau en masque l'aperçu). Le texte
     * copié n'est jamais journalisé.
     */
    private fun recordInClipHistory(snapshot: ClipboardReader.Snapshot) {
        if (ClipboardItems.historyRefusal(snapshot.text)) return
        serviceScope.launch {
            try {
                clipHistoryRepository.record(snapshot.text, snapshot.copiedAtMillis, snapshot.sensitive)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de l'enregistrement dans l'historique du presse-papiers", t)
            }
        }
    }

    /**
     * Rien n'est proposé pendant une dictée ou une correction, sous un panneau (emoji, presse-papiers),
     * ni dans les champs sans suggestions (mot de passe, e-mail, URL…).
     */
    private fun pasteSuggestionAllowed(): Boolean =
        pasteAllowedInField && !isRecording && !correctionInProgress && !emojiPanelVisible() && !clipboardPanelVisible()

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

    /** La barre n'est visible que dans un champ de messagerie, avec des récents, sans panneau ouvert. */
    private fun refreshRecentEmojiBar() {
        if (!this::recentEmojiBar.isInitialized) return
        val recents = keyboardPreferences.recentEmojis
        val visible = messagingField && recents.isNotEmpty() &&
            !(this::emojiPanel.isInitialized && emojiPanel.visibility == View.VISIBLE) &&
            !clipboardPanelVisible()
        if (visible) recentEmojiBar.setEmojis(recents)
        recentEmojiBar.visibility = if (visible) View.VISIBLE else View.GONE
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
        hapticFeedback.perform(hapticIntensity.cursorMoveFeedback()) // toujours faible, absent si désactivé
        repeat(abs(steps)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        syncAutoCapitalization()
        applyState()
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
     * Pendant la frappe d'un mot (le curseur suit une lettre, et la lettre suivante, s'il y en a une,
     * n'appartient pas au même mot), les mots sont ceux du dictionnaire (complétions, autocorrection).
     * Après une espace, ce sont les mots qui suivent le plus souvent ce qui précède, d'après les
     * habitudes d'écriture apprises sur l'appareil (le dictionnaire reste fixe). L'emoji du dernier
     * mot reste proposé après lui (story 1.16) ; à défaut, l'emoji qui suit habituellement ce contexte.
     */
    private fun refreshSuggestions(textBeforeCursor: String) {
        if (!this::correctionBar.isInitialized) return
        val language = controller.state.language
        val available = suggestionsAllowed && !isRecording && !correctionInProgress &&
            !emojiPanelVisible() && !clipboardPanelVisible() && !hasSelection()
        val input = SuggestionInput(textBeforeCursor, language, available)
        if (input == lastSuggestionInput) return
        lastSuggestionInput = input

        var emoji = if (available) emojiSuggester.suggest(textBeforeCursor, language) else null
        var words = if (available) wordSuggestionsFor(textBeforeCursor, language) else emptyList()
        if (available && words.isEmpty()) {
            // Aucun mot en cours de frappe : mots (et emoji, si l'emoji du mot précédent n'en propose pas)
            // qui suivent le plus souvent ce qui précède, d'après les habitudes d'écriture.
            val prediction = predictNext(textBeforeCursor)
            words = prediction.words.map { WordSuggestion(it, WordSuggestion.Kind.PREDICTION) }
            if (emoji == null) emoji = prediction.emoji
        }
        currentEmojiSuggestion = emoji
        correctionBar.setEmojiSuggestion(emoji)
        currentWordSuggestions = words
        correctionBar.setWordSuggestions(words)
    }

    /** Mots et emoji probables après [textBeforeCursor] (qui doit finir par une espace), selon ce que le clavier a appris. */
    private fun predictNext(textBeforeCursor: String): NextWordModel.Prediction {
        if (!learningAllowed || textBeforeCursor.isEmpty()) return NextWordModel.Prediction.NONE
        // Curseur au milieu d'un mot : insérer un mot entier à cet endroit serait trompeur.
        val after = currentInputConnection?.getTextAfterCursor(1, 0)?.firstOrNull()
        if (after != null && isWordChar(after)) return NextWordModel.Prediction.NONE
        return nextWords.model.predict(
            textBeforeCursor,
            truncated = textBeforeCursor.length >= TEXT_CONTEXT_LOOKBEHIND,
            maxWords = SuggestionStripView.WORD_SLOT_COUNT,
        )
    }

    /**
     * Apprend, pour la prédiction du mot suivant, le dernier mot (ou emoji) terminé avant le curseur.
     * Appelé après une saisie de l'utilisateur uniquement (espace, retour à la ligne, suggestion ou emoji
     * touchés), jamais pour du texte dicté ou collé, ni dans un champ sans suggestions.
     */
    private fun learnFromTyping() {
        if (!learningAllowed || isRecording || correctionInProgress || hasSelection()) return
        val before = currentInputConnection?.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString() ?: return
        val repository = nextWords
        if (repository.model.learn(before, truncated = before.length >= TEXT_CONTEXT_LOOKBEHIND)) repository.markDirty()
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
        if (suggestion.kind == WordSuggestion.Kind.PREDICTION) {
            insertPredictedWord(ic, suggestion.text)
            return
        }
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

    private fun isWordChar(c: Char): Boolean = c.isLetter() || c == '\'' || c == '-'

    /** Dernier "mot" avant le curseur : lettres/apostrophes/traits d'union contigus en fin de texte. */
    private fun trailingWord(textBeforeCursor: String): String {
        var start = textBeforeCursor.length
        while (start > 0 && isWordChar(textBeforeCursor[start - 1])) start--
        return textBeforeCursor.substring(start)
    }

    /**
     * Story 1.18 : pas d'autocorrection dans les champs e-mail, URL, mot de passe et numériques, ni
     * quand l'application demande de ne rien suggérer (même règle que les suggestions de mots).
     */
    private fun autocorrectionAllowed(): Boolean = suggestionsAllowed && fieldType.autoCorrects

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
            showMessage(getString(R.string.correction_no_model_selected))
            return
        }

        // On ne renvoie à l'IA que les phrases pas encore corrigées (nouvelles ou modifiées).
        val blocks = CorrectionPlanner.blocksToCorrect(captured.text, correctedSentences::contains)
        if (blocks.isEmpty()) {
            showMessage(getString(R.string.correction_nothing_new))
            return
        }

        launchCorrection {
            val correctedBlocks = blocks.map { block ->
                val original = captured.text.substring(block.start, block.endExclusive)
                val corrected = correctionEngine.correct(model, original, protectedWordsIn(original)) {
                    correctionBar.state = CorrectionBarState.LOADING
                }
                // null : réponse vide ou tronquée, le texte d'origine est alors conservé.
                CorrectionSafeguard.accept(original, corrected)
            }
            correctionBar.state = CorrectionBarState.CORRECTING
            applyCorrection(ic, captured, blocks, correctedBlocks)
            if (correctedBlocks.any { it == null }) {
                showMessage(getString(R.string.correction_partial))
            }
        }
    }

    /** Mots du dictionnaire personnel présents dans [text] : signalés à l'IA pour qu'elle ne les corrige pas. */
    private fun protectedWordsIn(text: String): List<String> =
        ProtectedWords.inText(text, personalDictionary.snapshot())

    /** Corrige uniquement le texte sélectionné ; les espaces en bordure de sélection sont conservés. */
    private fun correctSelection(ic: InputConnection, selected: String) {
        if (selected.isBlank()) return
        val model = modelPreferences.activeModel()
        if (model == null) {
            showMessage(getString(R.string.correction_no_model_selected))
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
            val corrected = correctByParagraph(model, core)
            correctionBar.state = CorrectionBarState.CORRECTING
            applySelectionCorrection(ic, selected, leading, trailing, core, corrected, absSelectionStart)
        }
    }

    /**
     * Corrige [text] paragraphe par paragraphe (un appel au modèle par ligne non vide) en gardant
     * les retours à la ligne d'origine. Les petits modèles tronquent souvent leur réponse au
     * premier retour à la ligne : corrigé d'un bloc, un texte de deux paragraphes perdait le second.
     */
    private suspend fun correctByParagraph(model: AiModel, text: String): String {
        val result = StringBuilder()
        var cursor = 0
        for (separator in Regex("\n+").findAll(text)) {
            result.append(correctParagraph(model, text.substring(cursor, separator.range.first)))
            result.append(separator.value)
            cursor = separator.range.last + 1
        }
        result.append(correctParagraph(model, text.substring(cursor)))
        return result.toString()
    }

    /** Corrige un paragraphe (espaces en bordure conservés) ; réponse vide ou tronquée : paragraphe inchangé. */
    private suspend fun correctParagraph(model: AiModel, paragraph: String): String {
        val core = paragraph.trim()
        if (core.isEmpty()) return paragraph
        val leading = paragraph.length - paragraph.trimStart().length
        val trailing = paragraph.length - paragraph.trimEnd().length
        val corrected = correctionEngine.correct(model, core, protectedWordsIn(core)) {
            correctionBar.state = CorrectionBarState.LOADING
        }
        val accepted = CorrectionSafeguard.accept(core, corrected)
        if (accepted == null) AppLog.w(TAG, "correction refusée par le garde-fou (réponse vide ou tronquée) : texte conservé")
        return paragraph.substring(0, leading) + (accepted ?: core) + paragraph.substring(paragraph.length - trailing)
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
            showMessage(getString(R.string.correction_no_change))
            return
        }
        // La sélection a pu changer pendant que le modèle travaillait : on ne remplace que si elle est identique.
        if (ic.getSelectedText(0)?.toString() != selected) {
            showMessage(getString(R.string.correction_text_changed))
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
                AppLog.e(TAG, "Échec de la correction IA", t)
                showMessage(getString(R.string.correction_error, t.message ?: t.javaClass.simpleName))
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
        correctedBlocks: List<String?>,
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
            val corrected = correctedBlocks[index] ?: original
            val offset = replacement.length
            CorrectionDiff.changedRanges(original, corrected).forEach {
                ranges += ChangedRange(it.start + offset, it.endExclusive + offset)
            }
            replacement.append(corrected)
            cursor = block.endExclusive
        }
        val newSpan = replacement.toString()

        // Ces phrases sont désormais corrigées, qu'elles aient changé ou non. Une zone rejetée par le
        // garde-fou n'est pas mémorisée : elle sera renvoyée au modèle à la prochaine correction.
        correctedBlocks.filterNotNull().forEach { correctedSentences.remember(it) }

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
            showMessage(getString(R.string.correction_text_changed))
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
            showMessage(getString(R.string.correction_text_changed))
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
            showMessage(getString(R.string.voice_permission_denied))
            startActivity(
                Intent(this, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (!voiceModelPreferences.isComplete()) {
            showMessage(getString(R.string.voice_no_model_selected))
            return
        }

        clearHighlightIfNeeded()
        isRecording = true
        clearSuggestions()
        insertedPartialText = ""
        voiceFrozenLength = 0
        voiceLastHypothesis = ""
        // Le bouton n'affiche « Écoute… » qu'une fois le micro réellement ouvert : tant que le modèle
        // se charge (premier usage, ou rechargé après libération), il affiche « Chargement… ».
        correctionBar.voiceState = VoiceBarState.LOADING
        // UNDISPATCHED : si le modèle est déjà chargé, aucune suspension, donc « Chargement… » n'apparaît pas.
        voiceStartJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
                correctionBar.voiceState = VoiceBarState.RECORDING // le micro capte vraiment, à partir de maintenant
                voiceStartJob = null
                // Insertion au fur et à mesure : chaque nouvelle hypothèse remplace
                // entièrement la précédente (le décodeur streaming peut réviser des
                // mots déjà affichés), le texte final restera inséré par
                // stopVoiceRecording()/cancelVoiceRecording() une fois l'écoute arrêtée.
                voicePartialJob = serviceScope.launch {
                    recorder.partialText.collect { partial -> applyVoicePartialText(partial) }
                }
            } catch (e: CancellationException) {
                throw e // démarrage annulé par l'utilisateur : l'état a déjà été remis à zéro
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec du démarrage de l'enregistrement vocal", t)
                voiceStartJob = null
                isRecording = false
                correctionBar.voiceState = VoiceBarState.IDLE
                refreshPasteSuggestion()
                showMessage(getString(R.string.voice_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun stopVoiceRecording() {
        val recorder = voiceRecorder ?: run {
            // Arrêt demandé pendant le chargement : on abandonne le démarrage, rien n'a été enregistré.
            voiceStartJob?.cancel()
            voiceStartJob = null
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
                AppLog.e(TAG, "Échec de la transcription vocale", t)
                showMessage(getString(R.string.voice_error, t.message ?: t.javaClass.simpleName))
            } finally {
                correctionBar.voiceState = VoiceBarState.IDLE
                updateCorrectionBarVisibility()
                syncAutoCapitalization() // relance la suggestion d'emoji, coupée pendant l'écoute
            }
        }
    }

    /** Utilisé quand le clavier disparaît pendant un enregistrement (décision de sécurité, pas de fuite audio). */
    private fun cancelVoiceRecording() {
        val recorder = voiceRecorder ?: run {
            // Clavier fermé pendant le chargement : on abandonne le démarrage.
            voiceStartJob?.cancel()
            voiceStartJob = null
            isRecording = false
            correctionBar.voiceState = VoiceBarState.IDLE
            return
        }
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
     * Fin de dictée : remplace la dernière hypothèse insérée par [finalText] ("null" pour un simple
     * retrait, sans rien insérer à la place — cas de l'annulation). Même règle que pendant la dictée :
     * si l'utilisateur a modifié le texte entre-temps, ce qu'il a fait n'est pas écrasé.
     */
    private fun replaceInsertedPartialText(finalText: String?) {
        val ic = currentInputConnection
        if (ic != null) {
            // Une sélection restante obligerait à insérer par-dessus : on la replie d'abord.
            if (finalText != null && hasSelection() && lastSelectionEnd >= 0) {
                ic.setSelection(lastSelectionEnd, lastSelectionEnd)
            }
            syncVoiceText(ic, finalText, isFinal = true)
        }
        insertedPartialText = ""
        voiceFrozenLength = 0
        voiceLastHypothesis = ""
    }

    /**
     * Insère l'hypothèse de transcription courante à la place de la précédente pendant
     * l'enregistrement. Le décodeur en streaming peut réviser des mots déjà « affichés » au fil des
     * mots suivants : on remplace donc l'insertion précédente en bloc plutôt que de concaténer.
     *
     * Le champ reste modifiable pendant la dictée : voir [syncVoiceText].
     */
    private fun applyVoicePartialText(partial: String) {
        if (partial == voiceLastHypothesis) return
        val ic = currentInputConnection ?: return
        syncVoiceText(ic, partial, isFinal = false)
    }

    /**
     * Met le champ en accord avec l'hypothèse [hypothesis] (null = retrait seul, annulation).
     *
     * Le texte inséré par la dictée n'est supprimé que s'il se trouve toujours exactement avant le
     * curseur. Sinon (curseur déplacé, frappe, suppression ou correction faite par l'utilisateur), ce
     * qui a déjà été inséré lui appartient : on le « fige » et seule la suite de l'hypothèse, au-delà
     * de la partie figée, est insérée à l'endroit où se trouve maintenant le curseur. Ainsi la reprise
     * de la dictée n'efface rien d'imprévu et ne réinsère pas ce que l'utilisateur a supprimé.
     * Tant qu'une sélection existe (l'utilisateur copie ou remplace du texte), la mise à jour attend.
     */
    private fun syncVoiceText(ic: InputConnection, hypothesis: String?, isFinal: Boolean) {
        if (!isFinal && hasSelection()) return

        val inserted = insertedPartialText
        var intact = true
        if (inserted.isNotEmpty()) {
            val before = ic.getTextBeforeCursor(inserted.length, 0)?.toString() ?: return
            intact = before == inserted
        }
        if (!intact) {
            voiceFrozenLength = voiceLastHypothesis.length
            insertedPartialText = ""
        }
        if (hypothesis == null) {
            if (intact && inserted.isNotEmpty()) ic.deleteSurroundingText(inserted.length, 0)
            return
        }

        ic.beginBatchEdit()
        try {
            if (intact && inserted.isNotEmpty()) ic.deleteSurroundingText(inserted.length, 0)
            var display = if (hypothesis.length > voiceFrozenLength) hypothesis.substring(voiceFrozenLength) else ""
            if (voiceFrozenLength > 0 && display.startsWith(" ")) {
                // Reprise après une modification : pas d'espace en trop si le texte avant le curseur finit déjà par un blanc.
                val last = ic.getTextBeforeCursor(1, 0)?.lastOrNull()
                if (last == null || last.isWhitespace()) display = display.trimStart(' ')
            }
            if (display.isNotEmpty()) ic.commitText(display, 1)
            insertedPartialText = display
            voiceLastHypothesis = hypothesis
            if (display.isNotEmpty()) onUserTyped() // story 2.4 : du texte a été inséré par la dictée
        } finally {
            ic.endBatchEdit()
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
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout, controller.state.language, numberRowEnabled, fieldType)
        keyboardView.isShifted = controller.state.isShifted
        keyboardView.isCapsLock = controller.state.isCapsLock
    }

    companion object {
        private const val TAG = "ClavierIme"
        private const val MAX_ACCESSIBLE_CHARS = 10_000

        /** Nombre de caractères avant le curseur récupérés pour la majuscule automatique (1.2) et le dictionnaire local (1.3). */
        private const val TEXT_CONTEXT_LOOKBEHIND = 50

        // Suggestions d'auto-remplissage en ligne : hauteur alignée sur la zone de la barre (36 dp, moins la marge).
        private const val INLINE_SUGGESTION_HEIGHT_DP = 32f
        private const val INLINE_SUGGESTION_MIN_WIDTH_DP = 48f
        private const val INLINE_SUGGESTION_MAX_WIDTH_DP = 320f
        private const val INLINE_SUGGESTION_MAX_COUNT = 5

        /** #5A7FD4 (couleur accent existante du clavier) avec transparence (alpha 0x55). */
        private const val HIGHLIGHT_COLOR = 0x555A7FD4

        /** Délai pendant lequel les mises à jour de sélection sont considérées comme l'écho de nos propres modifications. */
        private const val SELF_EDIT_GRACE_MS = 500L

        /** Marge ajoutée au délai d'expiration de la puce, pour que l'horloge ait bien dépassé l'échéance. */
        private const val PASTE_EXPIRY_MARGIN_MS = 50L
    }
}
