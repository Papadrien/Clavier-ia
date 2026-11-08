package fr.junade.taipo

import fr.junade.taipo.ai.LlmEngineHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mémoire du clavier (sortie de [TaipoIme], revue de code) : libère le modèle IA et le modèle de dictée quand Android manque
 * de mémoire ou que le clavier reste masqué ([MemoryRelease]). [voice] et [llmHost] sont lus seulement au moment de libérer
 * (ils sont créés à la demande dans l'IME).
 */
internal class ImeMemoryManager(
    private val scope: CoroutineScope,
    private val voice: () -> VoiceController,
    private val llmHost: () -> LlmEngineHost,
    private val keyboardShown: () -> Boolean,
) {

    /** Libération différée des moteurs quand le clavier reste masqué ([MemoryRelease.IDLE_RELEASE_MS]). */
    private var idleReleaseJob: Job? = null

    /** Clavier masqué : les gros moteurs seront libérés s'il le reste [MemoryRelease.IDLE_RELEASE_MS]. */
    fun onWindowHidden() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(MemoryRelease.IDLE_RELEASE_MS)
            releaseHeavyEngines("clavier masqué depuis ${MemoryRelease.IDLE_RELEASE_MS / 60_000} min")
        }
    }

    fun onWindowShown() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }

    /** Android manque de mémoire : les gros moteurs sont libérés selon [MemoryRelease.shouldRelease]. */
    fun onTrimMemory(level: Int) {
        if (MemoryRelease.shouldRelease(level, keyboardShown = keyboardShown())) releaseHeavyEngines("mémoire basse (niveau $level)")
    }

    /**
     * Libère le modèle IA et le modèle de dictée s'ils ne servent pas en ce moment (ils se rechargent à la prochaine
     * utilisation). Le moteur IA est fermé hors du thread principal (la libération d'un gros modèle peut prendre un instant).
     */
    private fun releaseHeavyEngines(reason: String) {
        val voiceReleased = voice().releaseEngineIfIdle()
        scope.launch {
            val aiReleased = withContext(Dispatchers.Default) { llmHost().releaseIfIdle() }
            if (aiReleased || voiceReleased) AppLog.d(TAG, "moteurs libérés ($reason) : IA=$aiReleased dictée=$voiceReleased")
        }
    }

    private companion object {
        const val TAG = "TaipoIme"
    }
}
