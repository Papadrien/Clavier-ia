package fr.junade.taipo.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap

/**
 * Enveloppe autour de sherpa-onnx (`OnlineRecognizer`) pour la transcription
 * vocale brute (épopée 6 du backlog V1, sans retravail LLM pour ce prototype
 * — décision du 24/09/2026).
 *
 * API vérifiée le 24/09/2026 sur le code source officiel :
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
 * (qui contient un exemple de configuration Nemotron streaming avec
 * exactement les 4 fichiers attendus ici). Configuration validée à l'usage sur
 * appareil (retour d'Adrien, 03/10/2026).
 *
 * Contrairement à LiteRT-LM, il n'existe pas de coordonnée Maven officielle
 * simple pour sherpa-onnx sur Android natif (hors Flutter/React Native) :
 * l'intégration retenue ici n'utilise pas d'.aar mais les bibliothèques natives
 * dans app/src/main/jniLibs/<abi>/ et les sources Kotlin du binding JNI dans
 * com.k2fsa.sherpa.onnx, réduites aux 5 fichiers utilisés (voir les README de ces deux dossiers).
 */
class VoiceEngine(private val appContext: Context) {

    /**
     * Audit BUG-08 : le moteur natif et ses flux ne sont touchés que sous [lock]. Une demande de fermeture pendant une dictée
     * (modèle vocal supprimé depuis les réglages) est différée jusqu'à la libération du dernier flux : libérer le moteur
     * pendant un décodage, ou avant ses flux, plante le code natif.
     */
    private val lock = Any()
    private val loadMutex = Mutex()

    @Volatile
    private var recognizer: OnlineRecognizer? = null
    private var openStreams = 0
    private var closeRequested = false

    init {
        synchronized(liveEngines) { liveEngines.add(this) }
    }

    suspend fun ensureLoaded(): OnlineRecognizer {
        recognizer?.let { return it }
        // Deux appuis rapprochés ne chargent pas deux moteurs (audit BUG-08).
        return loadMutex.withLock {
            recognizer ?: withContext(Dispatchers.IO) {
                val encoder = VoiceModelFileResolver.resolve(appContext, VoiceModelFile.ENCODER)
                val decoder = VoiceModelFileResolver.resolve(appContext, VoiceModelFile.DECODER)
                val joiner = VoiceModelFileResolver.resolve(appContext, VoiceModelFile.JOINER)
                val tokens = VoiceModelFileResolver.resolve(appContext, VoiceModelFile.TOKENS)

                val config = OnlineRecognizerConfig(
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.path,
                            decoder = decoder.path,
                            joiner = joiner.path,
                        ),
                        tokens = tokens.path,
                        numThreads = 2,
                        provider = "cpu",
                    ),
                    // Une seule session par pression du bouton vocal (pas de découpage
                    // automatique en plusieurs énoncés) : le silence prolongé est géré
                    // nous-mêmes côté clavier (VoiceRecorder, décision 6.3), pas par la
                    // détection de fin d'énoncé native de sherpa-onnx.
                    enableEndpoint = false,
                )
                val newRecognizer = OnlineRecognizer(config = config)
                synchronized(lock) {
                    recognizer = newRecognizer
                    closeRequested = false
                }
                newRecognizer
            }
        }
    }

    fun createStream(): OnlineStream = synchronized(lock) {
        val r = requireNotNull(recognizer) { "Voice model not loaded: call ensureLoaded() first." }
        r.createStream().also { openStreams++ }
    }

    /** Donne des échantillons au flux (sans effet si le moteur a été libéré entre-temps). */
    fun accept(stream: OnlineStream, samples: FloatArray, sampleRate: Int) {
        synchronized(lock) {
            if (recognizer != null && stream.ptr != 0L) stream.acceptWaveform(samples, sampleRate)
        }
    }

    /** Fin de l'audio : le moteur termine le décodage avec ce qu'il a. */
    fun finishInput(stream: OnlineStream) {
        synchronized(lock) {
            if (recognizer != null && stream.ptr != 0L) stream.inputFinished()
        }
    }

    /** Fait avancer le décodage tant que le moteur a de quoi produire un résultat. */
    fun decodeAvailable(stream: OnlineStream) {
        synchronized(lock) {
            val r = recognizer ?: return
            if (stream.ptr == 0L) return
            while (r.isReady(stream)) {
                r.decode(stream)
            }
        }
    }

    fun currentText(stream: OnlineStream): String = synchronized(lock) {
        if (stream.ptr == 0L) "" else recognizer?.getResult(stream)?.text.orEmpty()
    }

    /** Libère un flux créé par [createStream] (avant le moteur : l'ordre compte pour le code natif). */
    fun releaseStream(stream: OnlineStream) {
        synchronized(lock) {
            if (stream.ptr == 0L) return
            stream.release()
            openStreams = (openStreams - 1).coerceAtLeast(0)
            if (closeRequested && openStreams == 0) releaseRecognizer()
        }
    }

    /** Libère le moteur, ou dès que le dernier flux ouvert sera libéré. */
    fun close() {
        synchronized(lock) {
            if (openStreams > 0) closeRequested = true else releaseRecognizer()
        }
    }

    private fun releaseRecognizer() {
        recognizer?.release()
        recognizer = null
        closeRequested = false
    }

    companion object {
        private val liveEngines = Collections.newSetFromMap(WeakHashMap<VoiceEngine, Boolean>())

        /**
         * Ferme, dans tout le processus, les moteurs de dictée (story 8.15) : à appeler avant de supprimer ou de
         * remplacer les fichiers du modèle vocal, pour qu'aucun moteur ne garde l'ancien modèle en mémoire. Le
         * moteur se recharge au prochain appui sur Vocal. À appeler hors du thread principal.
         */
        fun releaseEverywhere() {
            val engines = synchronized(liveEngines) { liveEngines.toList() }
            engines.forEach { it.close() }
        }
    }
}
