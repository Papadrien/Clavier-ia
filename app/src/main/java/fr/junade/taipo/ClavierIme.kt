package fr.junade.taipo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
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
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.ai.VoiceRecorder
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.dictionary.PersonalDictionaryProvider
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.VoiceModelPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class ClavierIme : InputMethodService() {

    private val controller = KeyboardController()
    private lateinit var keyboardView: KeyboardView
    private lateinit var correctionBar: CorrectionBarView

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val correctionEngine by lazy { CorrectionEngine(applicationContext) }
    private val modelPreferences by lazy { ModelPreferences(applicationContext) }
    private val personalDictionary by lazy { PersonalDictionaryProvider.repository(applicationContext) }

    private val voiceEngine by lazy { VoiceEngine(applicationContext) }
    private val voiceModelPreferences by lazy { VoiceModelPreferences(applicationContext) }
    private var voiceRecorder: VoiceRecorder? = null

    private var correctionInProgress = false

    // État du surlignage temporaire après correction (décision 3.2). Le
    // surlignage disparaît dès la première action utilisateur : si c'est une
    // touche de ce clavier, on le retire nous-mêmes avant de traiter la
    // touche (cas sûr, cf. clearHighlightIfNeeded) ; si le curseur a bougé
    // autrement (tap direct dans le champ), on abandonne juste l'idée de le
    // retirer plutôt que de risquer de modifier le mauvais texte.
    private var correctionHighlightActive = false
    private var correctionHighlightText = ""
    private var awaitingOwnSelectionReport = false

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
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        keyboardView.setOnKeyListener { key -> onKeyPressed(key) }

        correctionBar = CorrectionBarView(this)
        correctionBar.setOnCorrectListener { onCorrectClicked() }
        correctionBar.voiceButton.setOnTouchListener { _, event -> onVoiceButtonTouch(event) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                correctionBar,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            addView(
                keyboardView,
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
        controller.setLanguage(currentKeyboardLanguage())
        controller.reset()
        syncAutoCapitalization()
        clearHighlightState()
        applyState()
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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        clearHighlightState()
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
        if (awaitingOwnSelectionReport) {
            // Écho de notre propre remplacement de texte après correction : ignoré.
            awaitingOwnSelectionReport = false
        } else if (correctionHighlightActive) {
            correctionHighlightActive = false
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
        serviceJob.cancel()
    }

    private fun onKeyPressed(key: Key) {
        clearHighlightIfNeeded()

        // Story 1.3 : dictionnaire local pour l'autocorrection (pas d'IA, pas
        // d'apprentissage auto) — le mot qui vient de se terminer est vérifié
        // juste avant que la touche de ponctuation/espace/entrée qui le
        // termine ne soit elle-même traitée.
        if (isWordBoundaryKey(key)) {
            applyDictionaryAutocorrection()
        }

        val result = controller.onKey(key)

        result.commit?.let { text -> currentInputConnection?.commitText(text, 1) }
        if (result.deleteBefore > 0) {
            currentInputConnection?.deleteSurroundingText(result.deleteBefore, 0)
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
            syncAutoCapitalization()
        }
        applyState()
        updateCorrectionBarVisibility()
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

    private fun applyDictionaryAutocorrection() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(TEXT_CONTEXT_LOOKBEHIND, 0)?.toString().orEmpty()
        val word = trailingWord(before)
        if (word.isEmpty()) return

        val dictionary = DictionaryLoader.forLanguage(applicationContext, controller.state.language)
        // Story 1.4 : les mots du dictionnaire personnel ne sont jamais
        // corrigés et servent aussi de candidats de correction.
        val correction = dictionary.correctionFor(word, personalWords = personalDictionary.snapshot()) ?: return
        if (correction == word) return

        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(correction, 1)
        ic.endBatchEdit()
    }

    // ------------------------------------------------------------------
    // Correction IA (épopée 3)
    // ------------------------------------------------------------------

    private fun onCorrectClicked() {
        if (correctionInProgress) return
        val ic = currentInputConnection ?: return
        val captured = captureFieldText(ic) ?: return
        if (captured.text.isBlank()) return

        val model = modelPreferences.activeModel()
        if (model == null) {
            Toast.makeText(this, getString(R.string.correction_no_model_selected), Toast.LENGTH_SHORT).show()
            return
        }

        correctionInProgress = true
        correctionBar.state = CorrectionBarState.LOADING
        serviceScope.launch {
            try {
                val corrected = correctionEngine.correct(model, captured.text) {
                    correctionBar.state = CorrectionBarState.LOADING
                }
                correctionBar.state = CorrectionBarState.CORRECTING
                applyCorrection(ic, captured, corrected)
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
            }
        }
    }

    /** Texte capturé et sa longueur avant/après le curseur, pour pouvoir le remplacer précisément. */
    private data class CapturedText(val text: String, val beforeCursor: Int, val afterCursor: Int)

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
            return CapturedText(text, beforeCursor = selStart, afterCursor = text.length - selEnd)
        }
        // Repli si l'app ne fournit pas d'ExtractedText.
        val before = ic.getTextBeforeCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty() && after.isEmpty()) return null
        return CapturedText(before + after, beforeCursor = before.length, afterCursor = after.length)
    }

    private fun applyCorrection(ic: InputConnection, captured: CapturedText, correctedText: String) {
        if (correctedText.isBlank() || correctedText == captured.text) {
            correctionHighlightActive = false
            return
        }
        val spannable = SpannableString(correctedText).apply {
            setSpan(BackgroundColorSpan(HIGHLIGHT_COLOR), 0, correctedText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(captured.beforeCursor, captured.afterCursor)
        ic.commitText(spannable, 1)
        ic.endBatchEdit()

        correctionHighlightActive = true
        correctionHighlightText = correctedText
        awaitingOwnSelectionReport = true
    }

    /**
     * Retire le surlignage si le curseur est toujours juste après le texte
     * corrigé (cas normal : l'utilisateur tape la touche suivante sur ce
     * clavier). Si le curseur a bougé ailleurs entre-temps, on ne tente rien
     * (voir onUpdateSelection) pour ne pas risquer de modifier le mauvais texte.
     */
    private fun clearHighlightIfNeeded() {
        if (!correctionHighlightActive) return
        correctionHighlightActive = false
        val ic = currentInputConnection ?: return
        val length = correctionHighlightText.length
        if (length <= 0) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(length, 0)
        ic.commitText(correctionHighlightText, 1)
        ic.endBatchEdit()
    }

    private fun clearHighlightState() {
        correctionHighlightActive = false
        correctionHighlightText = ""
        awaitingOwnSelectionReport = false
    }

    /** Décision 3.3 : le bouton Corriger n'est visible que si le champ contient du texte. */
    private fun updateCorrectionBarVisibility() {
        if (correctionInProgress) return
        val ic = currentInputConnection
        val hasText = ic != null &&
            (!ic.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic.getTextAfterCursor(1, 0).isNullOrEmpty())
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

        isRecording = true
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
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout, controller.state.language)
        keyboardView.isShifted = controller.state.isShifted
    }

    companion object {
        private const val TAG = "ClavierIme"
        private const val MAX_ACCESSIBLE_CHARS = 10_000

        /** Nombre de caractères avant le curseur récupérés pour la majuscule automatique (1.2) et le dictionnaire local (1.3). */
        private const val TEXT_CONTEXT_LOOKBEHIND = 50

        /** #5A7FD4 (couleur accent existante du clavier) avec transparence (alpha 0x55). */
        private const val HIGHLIGHT_COLOR = 0x555A7FD4
    }
}
