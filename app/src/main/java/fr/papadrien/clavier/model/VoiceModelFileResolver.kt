package fr.papadrien.clavier.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Résout un [VoiceModelFile] vers un fichier local réel (sherpa-onnx attend
 * des chemins de fichiers, pas des URI SAF `content://`). Même logique que
 * [ModelFileResolver] pour les modèles de texte.
 */
object VoiceModelFileResolver {

    suspend fun resolve(context: Context, file: VoiceModelFile): File = withContext(Dispatchers.IO) {
        val prefs = VoiceModelPreferences(context)
        val sourceUri = prefs.savedUriFor(file)
            ?: throw IllegalStateException(
                "Fichier ${file.label} manquant pour le modèle vocal. " +
                    "Sélectionnez-le dans les paramètres du modèle vocal.",
            )

        val destination = localFileFor(context, file)
        if (destination.exists() && destination.length() > 0) {
            return@withContext destination
        }

        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException(
                "Impossible d'ouvrir le fichier ${file.label} (permission perdue ? à re-sélectionner).",
            )
        input.use { source ->
            tempFile.outputStream().use { output ->
                source.copyTo(output, bufferSize = 1 shl 20)
            }
        }

        if (!tempFile.renameTo(destination)) {
            throw IllegalStateException("Impossible de finaliser la copie du fichier ${file.label}.")
        }
        destination
    }

    fun localFileFor(context: Context, file: VoiceModelFile): File =
        File(File(context.filesDir, "voice-model"), file.id + extensionFor(file))

    private fun extensionFor(file: VoiceModelFile): String =
        if (file == VoiceModelFile.TOKENS) ".txt" else ".onnx"
}
