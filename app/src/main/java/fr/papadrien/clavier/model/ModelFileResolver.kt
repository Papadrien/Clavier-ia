package fr.papadrien.clavier.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Résout un [AiModel] vers un fichier local utilisable par LiteRT-LM.
 *
 * LiteRT-LM (`EngineConfig.modelPath`) attend un chemin de fichier réel, pas
 * une URI SAF `content://` — le fichier choisi par l'utilisateur (voir
 * [ModelPreferences]) est donc copié une seule fois dans le stockage interne
 * de l'app. Les appels suivants réutilisent la copie si sa taille correspond
 * toujours à celle enregistrée au moment de la sélection.
 */
object ModelFileResolver {

    suspend fun resolve(context: Context, model: AiModel): File = withContext(Dispatchers.IO) {
        val prefs = ModelPreferences(context)
        val sourceUri = prefs.savedUriFor(model)
            ?: throw IllegalStateException(
                "Aucun fichier fourni pour le modèle ${model.displayName}. " +
                    "Sélectionnez-le d'abord dans les paramètres du modèle IA.",
            )
        val expectedSize = prefs.savedFileSizeFor(model)

        val destination = localFileFor(context, model)
        if (destination.exists() && (expectedSize <= 0 || destination.length() == expectedSize)) {
            return@withContext destination
        }

        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException(
                "Impossible d'ouvrir le fichier sélectionné pour ${model.displayName} (permission perdue ? à re-sélectionner).",
            )
        input.use { source ->
            tempFile.outputStream().use { output ->
                source.copyTo(output, bufferSize = 1 shl 20)
            }
        }

        if (!tempFile.renameTo(destination)) {
            throw IllegalStateException("Impossible de finaliser la copie du modèle ${model.displayName}.")
        }
        destination
    }

    fun localFileFor(context: Context, model: AiModel): File =
        File(File(context.filesDir, "models"), "${model.id}.litertlm")
}
