package fr.junade.taipo

import android.os.SystemClock
import android.view.View
import android.view.inputmethod.InputConnection
import fr.junade.taipo.ai.InferenceFailureClassifier
import fr.junade.taipo.ai.RewriteCancelledException
import fr.junade.taipo.ai.RewriteEngine
import fr.junade.taipo.ai.RewriteSafeguard
import fr.junade.taipo.ai.Tone
import fr.junade.taipo.ai.TonePrompt
import fr.junade.taipo.ai.TonePromptPreferences
import fr.junade.taipo.ai.ToneSupport
import fr.junade.taipo.ai.messageRes
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Panneau IA (20/11/2026) : ouverture et fermeture (bouton IA de la barre), lancement des actions, épinglage dans la barre (appui
 * long, deux actions au plus, [AiPins]) et page « Changer le ton ».
 *
 * Changer le ton : le texte visé est la sélection du champ, ou à défaut tout son texte. Chaque ton touché réécrit le texte
 * d'ORIGINE (pas la version précédente : essayer plusieurs tons ne déforme pas le message), et la version obtenue remplace la
 * précédente dans le champ ; « Rétablir » remet l'original. Chaque remplacement est une action du bouton Annuler. Avec une
 * sélection, la version écrite reste sélectionnée, pour que le ton suivant la remplace.
 *
 * Toutes les méthodes sont à appeler depuis le thread principal.
 */
internal class AiPanelController(
    private val ime: ImeHostContext,
    private val scope: CoroutineScope,
    private val engine: () -> RewriteEngine,
) : AiPanelView.Listener {

    private val panel: AiPanelView get() = ime.viewComposer.aiPanel
    private val tonePromptPreferences by lazy { TonePromptPreferences(ime.keyboardView.context) }

    val isPanelVisible: Boolean get() = ime.viewComposer.isComposed && panel.visibility == View.VISIBLE

    /** Un changement de ton tourne (le moteur est pris). */
    val toneBusy: Boolean get() = toneJob?.isActive == true

    private var toneJob: Job? = null

    @Volatile private var toneCancelled = false

    /** Session de changement de ton : texte d'origine, dernière version écrite, ton choisi. */
    private var original: String? = null
    private var lastWritten: String? = null
    private var selectedTone: Tone? = null

    /** Début (offset absolu) de la sélection visée, -1 : tout le texte du champ. */
    private var selectionStart = -1

    /** Bouton IA de la barre : ouvre le panneau, ou le referme s'il est ouvert. */
    fun onAiButtonClicked() {
        ime.playAiButtonSound()
        if (isPanelVisible) {
            ime.haptic()
            hidePanel()
        } else {
            showPanel(tonePage = false)
        }
    }

    /** Action « Ton » épinglée dans la barre : le panneau s'ouvre directement sur les tons. */
    fun onToneButtonClicked() {
        ime.playAiButtonSound()
        if (blockedBySensitiveField()) return
        showPanel(tonePage = true)
    }

    /** Action « Traduire » épinglée dans la barre (ou case du panneau) : mode Traduction. */
    fun onTranslateButtonClicked() {
        if (blockedBySensitiveField()) return
        ime.playAiButtonSound()
        hidePanel(resync = false)
        ime.prompt.enterTranslation()
    }

    fun showPanel(tonePage: Boolean) {
        if (!ime.viewComposer.isComposed) return
        if (ime.prompt.active) return
        ime.haptic()
        ime.emoji.hidePanel(resync = false)
        ime.clipboard.hidePanel(resync = false)
        val keyboard = ime.keyboardView
        panel.configure(keyboard.rowHeightPx(), keyboard.bottomInsetPx())
        panel.listener = this
        val wasVisible = isPanelVisible
        keyboard.visibility = View.INVISIBLE
        panel.visibility = View.VISIBLE
        ime.correctionBar.setAiPanelOpen(true)
        ime.emoji.refreshRecentBar()
        ime.clearSuggestions()
        if (!wasVisible) ime.keyboardPreferences.aiPanelOpenCount = ime.keyboardPreferences.aiPanelOpenCount + 1
        if (tonePage) openTonePage() else showActions()
    }

    /** Retour aux touches (ABC, bouton IA, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    fun hidePanel(resync: Boolean = true) {
        if (!isPanelVisible) return
        endToneSession()
        panel.visibility = View.GONE
        ime.keyboardView.visibility = View.VISIBLE
        ime.correctionBar.setAiPanelOpen(false)
        ime.emoji.refreshRecentBar()
        if (resync) ime.resyncKeyboard()
    }

    private fun showActions() {
        panel.showTonePage(false)
        renderActions()
    }

    private fun renderActions() {
        val pins = ime.keyboardPreferences.pinnedAiActions
        val unavailable = buildSet {
            if (!ime.correctionBar.aiTranscriptionAvailable) add(AiAction.TRANSCRIPTION)
            if (ime.sensitiveField) addAll(AiAction.entries)
        }
        panel.renderActions(pins, unavailable, AiPins.showsPinTip(pins, ime.keyboardPreferences.aiPanelOpenCount))
    }

    // ------------------------------------------------------------------
    // Page des actions
    // ------------------------------------------------------------------

    override fun onActionTapped(action: AiAction) {
        if (blockedBySensitiveField()) return
        when (action) {
            AiAction.TONE -> {
                ime.haptic()
                openTonePage()
            }
            AiAction.TRANSLATE -> onTranslateButtonClicked()
            AiAction.CORRECT -> {
                hidePanel()
                ime.playAiButtonSound()
                ime.correction.onCorrectClicked()
            }
            AiAction.CHAT -> {
                hidePanel(resync = false)
                ime.playAiButtonSound()
                ime.prompt.enter()
            }
            AiAction.TRANSCRIPTION -> {
                if (!ime.correctionBar.aiTranscriptionAvailable) {
                    ime.showMessage(ime.string(R.string.ai_panel_transcription_unavailable))
                    return
                }
                hidePanel()
                ime.playAiButtonSound()
                ime.aiTranscription.onClick()
            }
        }
    }

    override fun onActionLongPressed(action: AiAction) {
        val preferences = ime.keyboardPreferences
        val label = ime.string(AiPanelView.labelOf(action))
        when (val result = AiPins.toggle(preferences.pinnedAiActions, action)) {
            is AiPins.Toggle.Pinned -> {
                ime.hapticAi(AiHapticCue.ACTION_START)
                preferences.pinnedAiActions = result.pins
                ime.showMessage(String.format(ime.string(R.string.ai_panel_pinned), label))
            }
            is AiPins.Toggle.Unpinned -> {
                ime.haptic()
                preferences.pinnedAiActions = result.pins
                ime.showMessage(String.format(ime.string(R.string.ai_panel_unpinned), label))
            }
            is AiPins.Toggle.Full -> {
                ime.haptic()
                ime.showMessage(String.format(ime.string(R.string.ai_panel_pins_full), AiPins.MAX))
            }
        }
        ime.correctionBar.pinnedAiActions = preferences.pinnedAiActions
        renderActions()
    }

    override fun onClose() {
        ime.haptic()
        hidePanel()
    }

    // ------------------------------------------------------------------
    // Changer le ton
    // ------------------------------------------------------------------

    private fun openTonePage() {
        panel.showTonePage(true)
        renderTones()
    }

    override fun onBackToActions() {
        ime.haptic()
        endToneSession()
        showActions()
    }

    private fun renderTones() {
        val busy = toneBusy
        val status = when {
            busy -> ime.string(R.string.ai_tone_working)
            selectedTone != null -> String.format(ime.string(R.string.ai_tone_applied), ime.string(AiPanelView.toneLabel(selectedTone!!)))
            else -> ime.string(R.string.ai_tone_title)
        }
        panel.renderTones(selectedTone, status, canRestore = original != null && lastWritten != null, busy = busy)
    }

    override fun onToneTapped(tone: Tone) {
        val model = toneModelOrNull() ?: return
        val ic = ime.inputConnection() ?: return
        val source = sessionSource(ic)
        if (source.isNullOrBlank()) {
            ime.showMessage(ime.string(R.string.ai_tone_nothing))
            return
        }
        ime.hapticAi(AiHapticCue.ACTION_START)
        toneCancelled = false
        val job = scope.launch { rewriteTone(model, tone, source) }
        toneJob = job
        job.invokeOnCompletion {
            ime.keyboardView.post {
                if (toneJob === job) toneJob = null
                if (isPanelVisible && panel.tonePageShown) renderTones()
            }
        }
        renderTones()
    }

    /** Modèle avec lequel changer le ton maintenant, ou null (avec le message ou l'écran qui convient). */
    private fun toneModelOrNull(): AiModel? {
        if (toneBusy || blockedBySensitiveField()) return null
        val model = ime.activeModel()
        if (model == null) {
            ModelScreenRedirect.open(ime.keyboardView.context)
            return null
        }
        if (!ToneSupport.supports(model)) {
            ime.showMessage(String.format(ime.string(R.string.ai_tone_model_too_small), ime.string(R.string.model_name_leger)))
            return null
        }
        if (engineBusy()) {
            ime.showMessage(ime.string(R.string.ai_action_busy))
            return null
        }
        return model
    }

    /** Dictée, correction, transcription ou génération en cours : le moteur (ou le micro) est pris. */
    private fun engineBusy(): Boolean =
        ime.isRecording() || ime.isCorrectionInProgress() || ime.isTranscriptionInProgress() || ime.prompt.generationBusy()

    /** Réécrit [source] dans le ton [tone] et l'écrit à la place de la version précédente. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // échec du moteur : message à l'utilisateur, pas de plantage
    private suspend fun rewriteTone(model: AiModel, tone: Tone, source: String) {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val response = engine().rewrite(
                model,
                tonePromptPreferences.get(),
                TonePrompt.userTurn(tone, source.trim()),
                "ton",
                isCancelled = { toneCancelled },
            )
            if (toneCancelled) return
            val rewritten = RewriteSafeguard.clean(response)
            if (rewritten == null) {
                ime.showMessage(ime.string(R.string.ai_tone_failed))
                return
            }
            val leading = source.takeWhile { it.isWhitespace() }
            val trailing = source.takeLastWhile { it.isWhitespace() }
            if (write(leading + rewritten + trailing)) {
                selectedTone = tone
                ime.hapticAi(AiHapticCue.PROCESSING_DONE)
                Analytics.aiAction(ime.keyboardView.context, AnalyticsEvents.AiType.TONE, model.id, startedAt)
            }
        } catch (e: RewriteCancelledException) {
            AppLog.i(TAG, "changement de ton abandonné")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (!toneCancelled) {
                val cause = InferenceFailureClassifier.classify(t)
                AppLog.e(TAG, "Échec du changement de ton ($cause)", t)
                Analytics.aiAction(ime.keyboardView.context, AnalyticsEvents.AiType.TONE, model.id, startedAt, failure = cause)
                ime.showMessage(ime.string(cause.messageRes()))
            }
        }
    }

    /**
     * Texte d'origine de la session : le même tant que le champ montre la dernière version écrite ; sinon (première fois, ou texte
     * modifié entre-temps) une nouvelle session commence sur la sélection, ou sur tout le texte du champ.
     */
    private fun sessionSource(ic: InputConnection): String? {
        val current = currentTarget(ic)
        val previous = original
        if (previous != null && lastWritten != null && current == lastWritten) return previous
        // Nouvelle session.
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            selectionStart = FieldEditor.selectionStartOffset(ic)
            original = selected
        } else {
            selectionStart = -1
            original = ime.correction.captureFieldText(ic)?.text
        }
        lastWritten = null
        selectedTone = null
        return original
    }

    /** Ce que la session remplacerait maintenant : la sélection (mode sélection), ou tout le texte du champ. */
    private fun currentTarget(ic: InputConnection): String? =
        if (selectionStart >= 0) ic.getSelectedText(0)?.toString() else ime.correction.captureFieldText(ic)?.text

    /** Écrit [text] à la place de la cible ; avec une sélection connue, la version écrite reste sélectionnée. */
    private fun write(text: String): Boolean {
        val ic = ime.inputConnection() ?: return false
        if (lastWritten != null && currentTarget(ic) != lastWritten) {
            ime.showMessage(ime.string(R.string.correction_text_changed))
            endToneSession()
            return false
        }
        if (!ime.replaceFieldText(text)) return false
        lastWritten = text
        if (selectionStart >= 0) ic.setSelection(selectionStart, selectionStart + text.length)
        return true
    }

    override fun onRestoreOriginal() {
        val text = original ?: return
        if (toneBusy) return
        ime.haptic()
        if (write(text)) {
            selectedTone = null
            lastWritten = null
            original = null
            selectionStart = -1
        }
        renderTones()
    }

    /** Fin de la session (panneau refermé, retour aux actions, champ changé) : une réécriture en cours est abandonnée. */
    private fun endToneSession() {
        val running = toneJob
        if (running?.isActive == true) {
            toneCancelled = true
            scope.launch(Dispatchers.Default) { engine().cancel() }
        }
        original = null
        lastWritten = null
        selectedTone = null
        selectionStart = -1
    }

    private fun blockedBySensitiveField(): Boolean {
        if (!ime.sensitiveField) return false
        ime.showMessage(ime.string(R.string.sensitive_field_actions_disabled))
        return true
    }

    private companion object {
        const val TAG = "AiPanelController"
    }
}
