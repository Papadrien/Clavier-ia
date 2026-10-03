package fr.junade.taipo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputConnection
import fr.junade.taipo.ai.CaptureError
import fr.junade.taipo.ai.MicUnavailableException
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.ai.VoiceField
import fr.junade.taipo.ai.VoiceRecorder
import fr.junade.taipo.ai.VoiceTextSync
import fr.junade.taipo.model.VoiceModelPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * Saisie vocale (épopée 6) : transcription brute, sans retravail LLM (décision prototype du
 * 24/09/2026). Extrait de `TaipoIme` au lot 2.3 de la revue de code, sans changement de
 * comportement : appui bref = bascule marche/arrêt, appui long = écoute tant que le doigt reste sur
 * le bouton (décision 6.1), arrêt automatique après 20 s de silence en mode appui bref (6.3),
 * insertion directe au curseur sans aperçu (6.4), insertion progressive pendant l'écoute.
 *
 * Tout ce qui touche à l'IME (champ de saisie, barre, messages, suggestions) passe par [Host].
 * Toutes les méthodes sont à appeler depuis le thread principal.
 */
class VoiceController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val host: Host,
) {

    /** Ce que la dictée demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        /** Une génération (mode prompt) est en cours : la dictée est refusée. */
        fun generationBusy(): Boolean

        /** Fait disparaître le surlignage de correction (action de l'utilisateur). */
        fun clearHighlight()

        fun clearSuggestions()

        fun refreshPasteSuggestion()

        fun updateCorrectionBarVisibility()

        /** Relance la suggestion d'emoji, coupée pendant l'écoute. */
        fun syncAutoCapitalization()

        /** Du texte vient d'être inséré par la dictée (story 2.4). */
        fun onUserTyped()

        fun setVoiceBarState(state: VoiceBarState)

        fun inputConnection(): InputConnection?

        fun hasSelection(): Boolean

        /** Replie la sélection restante à sa fin, pour insérer sans la remplacer. */
        fun collapseSelectionBeforeInsert()
    }

    private val voiceEngine by lazy { VoiceEngine(context.applicationContext) }
    private val voiceModelPreferences by lazy { VoiceModelPreferences(context.applicationContext) }
    private var voiceRecorder: VoiceRecorder? = null

    /** Écoute en cours (ou démarrage en cours) : lu par l'IME pour suspendre suggestions, collage, etc. */
    var isRecording = false
        private set
    private var isHoldModeRecording = false
    private var longPressTriggered = false

    private var voicePartialJob: Job? = null

    // Démarrage en cours (chargement du modèle puis ouverture du micro) : annulable tant que
    // l'écoute n'a pas réellement commencé (second appui, relâchement en mode maintenu, clavier fermé).
    private var voiceStartJob: Job? = null

    private val textSync = VoiceTextSync(
        hasSelection = { host.hasSelection() },
        onTextInserted = { host.onUserTyped() },
    )

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        isHoldModeRecording = true
        startRecording()
    }

    /** Libère le moteur vocal et les rappels en attente (destruction du service). */
    fun release() {
        voiceEngine.close()
        mainHandler.removeCallbacks(longPressRunnable)
    }

    fun onButtonTouch(event: MotionEvent): Boolean {
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
                        stopRecording()
                    }
                    isRecording -> {
                        // Deuxième appui bref pendant l'écoute : on arrête (décision 6.1).
                        stopRecording()
                    }
                    else -> {
                        // Premier appui bref : on démarre en mode bascule.
                        isHoldModeRecording = false
                        startRecording()
                    }
                }
                longPressTriggered = false
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (longPressTriggered) {
                    stopRecording()
                }
                longPressTriggered = false
            }
        }
        return true
    }

    private fun startRecording() {
        if (isRecording) return
        if (host.generationBusy()) {
            host.showMessage(context.getString(R.string.generation_busy))
            return
        }

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            host.showMessage(context.getString(R.string.voice_permission_denied))
            context.startActivity(
                Intent(context, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (!voiceModelPreferences.isComplete()) {
            host.showMessage(context.getString(R.string.voice_no_model_selected))
            return
        }

        host.clearHighlight()
        isRecording = true
        host.clearSuggestions()
        textSync.reset()
        // Le bouton n'affiche « Écoute… » qu'une fois le micro réellement ouvert : tant que le modèle
        // se charge (premier usage, ou rechargé après libération), il affiche « Chargement… ».
        host.setVoiceBarState(VoiceBarState.LOADING)
        // UNDISPATCHED : si le modèle est déjà chargé, aucune suspension, donc « Chargement… » n'apparaît pas.
        voiceStartJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                voiceEngine.ensureLoaded()
                val recorder = VoiceRecorder(voiceEngine, scope)
                recorder.onSilenceTimeout = {
                    // Appelé depuis le thread d'enregistrement (décision 6.3, mode appui
                    // bref uniquement) : on revient sur le thread principal pour arrêter proprement.
                    mainHandler.post {
                        if (!isHoldModeRecording) {
                            stopRecording()
                        }
                    }
                }
                recorder.onCaptureError = { error ->
                    // Appelé depuis le thread d'enregistrement (lot 2.5 de la revue) : micro perdu ou
                    // décodage trop en retard. On arrête proprement (le texte déjà transcrit est
                    // inséré) après avoir prévenu, quel que soit le mode (appui bref ou maintenu).
                    mainHandler.post {
                        if (voiceRecorder === recorder) {
                            host.showMessage(
                                context.getString(
                                    when (error) {
                                        CaptureError.MIC_LOST -> R.string.voice_mic_lost
                                        CaptureError.BACKLOG_OVERFLOW -> R.string.voice_backlog_overflow
                                    },
                                ),
                            )
                            stopRecording()
                        }
                    }
                }
                voiceRecorder = recorder
                recorder.start()
                host.setVoiceBarState(VoiceBarState.RECORDING) // le micro capte vraiment, à partir de maintenant
                voiceStartJob = null
                // Insertion au fur et à mesure : chaque nouvelle hypothèse remplace
                // entièrement la précédente (le décodeur streaming peut réviser des
                // mots déjà affichés), le texte final restera inséré par
                // stopRecording()/cancelRecording() une fois l'écoute arrêtée.
                voicePartialJob = scope.launch {
                    recorder.partialText.collect { partial -> applyPartialText(partial) }
                }
            } catch (e: CancellationException) {
                throw e // démarrage annulé par l'utilisateur : l'état a déjà été remis à zéro
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec du démarrage de l'enregistrement vocal", t)
                voiceStartJob = null
                voiceRecorder = null // un recorder dont start() a échoué n'a rien à arrêter ni à libérer
                isRecording = false
                host.setVoiceBarState(VoiceBarState.IDLE)
                host.refreshPasteSuggestion()
                host.showMessage(
                    if (t is MicUnavailableException) {
                        context.getString(R.string.voice_mic_unavailable)
                    } else {
                        context.getString(R.string.voice_error, t.message ?: t.javaClass.simpleName)
                    },
                )
            }
        }
    }

    fun stopRecording() {
        val recorder = voiceRecorder ?: run {
            // Arrêt demandé pendant le chargement : on abandonne le démarrage, rien n'a été enregistré.
            voiceStartJob?.cancel()
            voiceStartJob = null
            isRecording = false
            host.setVoiceBarState(VoiceBarState.IDLE)
            return
        }
        voiceRecorder = null
        isRecording = false
        host.setVoiceBarState(VoiceBarState.TRANSCRIBING)
        scope.launch {
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
                host.showMessage(context.getString(R.string.voice_error, t.message ?: t.javaClass.simpleName))
            } finally {
                host.setVoiceBarState(VoiceBarState.IDLE)
                host.updateCorrectionBarVisibility()
                host.syncAutoCapitalization() // relance la suggestion d'emoji, coupée pendant l'écoute
            }
        }
    }

    /** Utilisé quand le clavier disparaît pendant un enregistrement (décision de sécurité, pas de fuite audio). */
    fun cancelRecording() {
        val recorder = voiceRecorder ?: run {
            // Clavier fermé pendant le chargement : on abandonne le démarrage.
            voiceStartJob?.cancel()
            voiceStartJob = null
            isRecording = false
            host.setVoiceBarState(VoiceBarState.IDLE)
            return
        }
        voiceRecorder = null
        isRecording = false
        host.setVoiceBarState(VoiceBarState.IDLE)
        scope.launch {
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
     * Fin de dictée : remplace la dernière hypothèse insérée par [finalText] (null pour un simple
     * retrait, cas de l'annulation). Si l'utilisateur a modifié le texte entre-temps, ce qu'il a fait
     * n'est pas écrasé (voir [VoiceTextSync]).
     */
    private fun replaceInsertedPartialText(finalText: String?) {
        val ic = host.inputConnection()
        if (ic != null) {
            // Une sélection restante obligerait à insérer par-dessus : on la replie d'abord.
            if (finalText != null) host.collapseSelectionBeforeInsert()
            textSync.finish(InputConnectionVoiceField(ic), finalText)
        }
        textSync.reset()
    }

    /** Insère l'hypothèse courante à la place de la précédente pendant l'enregistrement. */
    private fun applyPartialText(partial: String) {
        val ic = host.inputConnection() ?: return
        textSync.applyPartial(InputConnectionVoiceField(ic), partial)
    }

    private class InputConnectionVoiceField(private val ic: InputConnection) : VoiceField {
        override fun textBeforeCursor(length: Int): String? = ic.getTextBeforeCursor(length, 0)?.toString()

        override fun deleteBeforeCursor(length: Int) {
            ic.deleteSurroundingText(length, 0)
        }

        override fun commit(text: String) {
            ic.commitText(text, 1)
        }

        override fun beginBatchEdit() {
            ic.beginBatchEdit()
        }

        override fun endBatchEdit() {
            ic.endBatchEdit()
        }
    }

    private companion object {
        private const val TAG = "VoiceController"
    }
}
