package fr.papadrien.clavier.ai

import android.content.Context
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import fr.papadrien.clavier.model.VoiceModelFile
import fr.papadrien.clavier.model.VoiceModelFileResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Enveloppe autour de sherpa-onnx (`OnlineRecognizer`) pour la transcription
 * vocale brute (épopée 6 du backlog V1, sans retravail LLM pour ce prototype
 * — décision du 24/09/2026).
 *
 * API vérifiée le 24/09/2026 sur le code source officiel :
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt
 * (qui contient un exemple de configuration Nemotron streaming avec
 * exactement les 4 fichiers attendus ici). Non testée dans cet environnement
 * (pas de réseau, pas de JNI natif disponible ici) : à vérifier par Adrien au
 * premier build, une fois le .aar sherpa-onnx ajouté dans app/libs (voir
 * build.gradle.kts).
 *
 * Contrairement à LiteRT-LM, il n'existe pas de coordonnée Maven officielle
 * simple pour sherpa-onnx sur Android natif (hors Flutter/React Native) :
 * l'intégration standard documentée par le projet consiste à télécharger un
 * .aar pré-compilé depuis ses releases GitHub et à le placer dans app/libs.
 */
class VoiceEngine(private val appContext: Context) {

    private var recognizer: OnlineRecognizer? = null

    suspend fun ensureLoaded(): OnlineRecognizer {
        recognizer?.let { return it }
        return withContext(Dispatchers.IO) {
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
            recognizer = newRecognizer
            newRecognizer
        }
    }

    fun createStream(): OnlineStream =
        requireNotNull(recognizer) { "Modèle vocal non chargé : appelez ensureLoaded() d'abord." }.createStream()

    /** Fait avancer le décodage tant que le moteur a de quoi produire un résultat. */
    fun decodeAvailable(stream: OnlineStream) {
        val r = recognizer ?: return
        while (r.isReady(stream)) {
            r.decode(stream)
        }
    }

    fun currentText(stream: OnlineStream): String = recognizer?.getResult(stream)?.text.orEmpty()

    fun close() {
        recognizer?.release()
        recognizer = null
    }
}
