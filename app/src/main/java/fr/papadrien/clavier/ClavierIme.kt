package fr.papadrien.clavier

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
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import fr.papadrien.clavier.ai.CorrectionEngine
import fr.papadrien.clavier.ai.VoiceEngine
import fr.papadrien.clavier.ai.VoiceRecorder
import fr.papadrien.clavier.model.ModelPreferences
import fr.papadrien.clavier.model.VoiceModelPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isHoldModeRecording = true
        startVoiceRecording()
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
                    resources.displayMetrics.heightPixels / 3,
                ),
            )
        }

        applyState()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!this::keyboardView.isInitialized) return
        controller.reset()
        clearHighlightState()
        applyState()
        updateCorrectionBarVisibility()
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

        val result = controller.onKey(key)

        result.commit?.let { text -> currentInputConnection?.commitText(text, 1) }
        if (result.deleteBefore > 0) {
            currentInputConnection?.deleteSurroundingText(result.deleteBefore, 0)
        }
        if (result.isEnter) {
            pressEnter()
        }
        applyState()
        updateCorrectionBarVisibility()
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
            try {
                val text = recorder.stopAndGetResult()
                if (text.isNotBlank()) {
                    // Décision 6.4 : insertion automatique au curseur, sans aperçu, sans surlignage.
                    currentInputConnection?.commitText(text, 1)
                }
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
            try {
                recorder.stopAndGetResult()
            } catch (_: Throwable) {
                // Le clavier se ferme de toute façon : rien à faire de plus.
            }
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
        keyboardView.layout = Keyboards.layoutOf(controller.state.activeLayout)
        keyboardView.isShifted = controller.state.isShifted
    }

    companion object {
        private const val TAG = "ClavierIme"
        private const val MAX_ACCESSIBLE_CHARS = 10_000

        /** #5A7FD4 (couleur accent existante du clavier) avec transparence (alpha 0x55). */
        private const val HIGHLIGHT_COLOR = 0x555A7FD4
    }
}
