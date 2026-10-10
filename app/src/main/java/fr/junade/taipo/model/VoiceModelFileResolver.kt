package fr.junade.taipo.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Résout un [VoiceModelFile] vers un fichier local réel (sherpa-onnx attend des chemins de fichiers, pas des URI SAF
 * `content://`). Même logique que [ModelFileResolver] pour les modèles de texte : un fichier téléchargé est utilisé
 * directement (jamais recopié) ; un fichier fourni à la main (debug) est copié une fois dans `voice-model/`.
 */
object VoiceModelFileResolver {

    suspend fun resolve(context: Context, file: VoiceModelFile): File = withContext(Dispatchers.IO) {
        val prefs = VoiceModelPreferences(context)
        val destination = localFileFor(context, file)

        // Fichier téléchargé (story 8.15) : utilisé tel quel, après contrôle de sa taille (story 8.9).
        if (prefs.isDownloaded(file)) {
            if (destination.isFile && destination.length() == prefs.downloadedSizeFor(file)) {
                return@withContext destination
            }
            throw IllegalStateException(
                "Le fichier ${file.label} du modèle vocal est introuvable ou incomplet. " +
                    "Retéléchargez le modèle vocal dans l'écran « Modèle IA ».",
            )
        }

        val sourceUri = prefs.savedUriFor(file)
            ?: throw IllegalStateException(
                "Fichier ${file.label} manquant pour le modèle vocal. " +
                    "Téléchargez le modèle vocal dans l'écran « Modèle IA ».",
            )

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

    /**
     * Supprime le fichier local de [file], son temporaire et son fichier d'empreinte. Ne ferme pas le moteur : voir
     * `VoiceEngine.releaseEverywhere`, à appeler avant. Retourne le nombre d'octets libérés.
     */
    fun deleteLocalCopy(context: Context, file: VoiceModelFile): Long {
        val local = localFileFor(context, file)
        var freed = 0L
        listOf(local, File(local.parentFile, "${local.name}.tmp"), File(local.parentFile, "${local.name}.sha256"))
            .forEach { target ->
                if (target.exists()) {
                    val size = target.length()
                    if (target.delete()) freed += size
                }
            }
        return freed
    }

    /** Sous-dossier de `filesDir` du modèle vocal : exclu des sauvegardes, voir les fichiers backup_rules.xml et data_extraction_rules.xml dans res/xml. */
    const val DIRECTORY_NAME = "voice-model"

    fun localFileFor(context: Context, file: VoiceModelFile): File =
        File(File(context.filesDir, DIRECTORY_NAME), file.id + extensionFor(file))

    private fun extensionFor(file: VoiceModelFile): String =
        if (file == VoiceModelFile.TOKENS) ".txt" else ".onnx"
}
