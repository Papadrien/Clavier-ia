package fr.junade.taipo.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Résout un [AiModel] vers un fichier local utilisable par LiteRT-LM.
 *
 * LiteRT-LM (`EngineConfig.modelPath`) attend un chemin de fichier réel, pas
 * une URI SAF `content://` — le fichier choisi par l'utilisateur (voir
 * [ModelPreferences]) est donc copié une seule fois dans le stockage interne
 * de l'app. Les appels suivants réutilisent la copie si sa taille (et son empreinte SHA-256, quand
 * elle est connue) correspond toujours à celle du fichier choisi.
 *
 * Un modèle téléchargé dans l'app (épopée 8) n'a pas d'URI : son fichier est déjà dans `models/`, il est
 * renvoyé tel quel après contrôle de sa taille.
 */
object ModelFileResolver {

    suspend fun resolve(context: Context, model: AiModel): File = withContext(Dispatchers.IO) {
        val prefs = ModelPreferences(context)

        // Modèle téléchargé (épopée 8) : le fichier est déjà dans models/, on ne le recopie jamais (story 8.9).
        // Sa taille est recontrôlée à chaque chargement du moteur.
        if (prefs.isDownloaded(model)) {
            val downloaded = localFileFor(context, model)
            if (downloaded.isFile && downloaded.length() == prefs.downloadedSizeFor(model)) {
                return@withContext downloaded
            }
            throw ModelFileException(
                "Le fichier du modèle ${model.displayName} est introuvable ou incomplet. " +
                    "Retéléchargez-le depuis l'écran Modèle IA.",
            )
        }

        val sourceUri = prefs.savedUriFor(model)
            ?: throw ModelFileException(
                "Aucun fichier fourni pour le modèle ${model.displayName}. " +
                    "Sélectionnez-le d'abord dans les paramètres du modèle IA.",
            )
        val expectedSize = prefs.savedFileSizeFor(model)

        val destination = localFileFor(context, model)
        if (destination.exists() && isCopyReusable(destination, expectedSize, prefs.savedSha256For(model))) {
            return@withContext destination
        }

        // Copie absente, ou périmée (autre taille / autre empreinte) : on repart de zéro.
        deleteLocalCopy(context, model)

        destination.parentFile?.mkdirs()
        val tempFile = File(destination.parentFile, "${destination.name}.tmp")
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw ModelFileException(
                "Impossible d'ouvrir le fichier sélectionné pour ${model.displayName} (permission perdue ? à re-sélectionner).",
            )
        // L'empreinte de la copie est calculée pendant la copie (sans relecture) et gardée à côté,
        // pour pouvoir vérifier plus tard que la copie correspond bien au fichier choisi.
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { source ->
            tempFile.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }

        if (!tempFile.renameTo(destination)) {
            throw ModelFileException("Impossible de finaliser la copie du modèle ${model.displayName}.")
        }
        sha256MarkerFor(destination).writeText(digest.digest().joinToString("") { "%02x".format(it) })
        destination
    }

    /**
     * Une copie existante n'est réutilisée que si sa taille correspond ET, quand l'empreinte du fichier
     * choisi est connue, si l'empreinte relevée à la copie est la même. Si la copie n'a pas d'empreinte
     * enregistrée (ancienne version de l'app), on se rabat sur la taille seule.
     */
    internal fun isCopyReusable(copy: File, expectedSize: Long, expectedSha256: String?): Boolean {
        if (expectedSize > 0 && copy.length() != expectedSize) return false
        if (expectedSha256 == null) return true
        val marker = sha256MarkerFor(copy)
        if (!marker.exists()) return true
        return sameSha256(marker.readText(), expectedSha256)
    }

    /**
     * Supprime la copie interne du modèle (plusieurs Go), son fichier d'empreinte, les restes d'une copie
     * interrompue et le cache XNNPACK du modèle. Ne ferme pas le moteur : voir [LlmEngineHost.releaseModel],
     * à appeler avant. Retourne le nombre d'octets libérés.
     */
    fun deleteLocalCopy(context: Context, model: AiModel): Long {
        val copy = localFileFor(context, model)
        var freed = 0L
        val targets = listOf(copy, File(copy.parentFile, "${copy.name}.tmp"), sha256MarkerFor(copy)) +
            xnnpackCacheFiles(context, model)
        targets.forEach { file ->
            if (file.exists()) {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        return freed
    }

    /**
     * Supprime seulement le cache XNNPACK du modèle (story 8.14) : à faire quand le fichier du modèle est remplacé par
     * une nouvelle version, pour qu'un cache construit sur l'ancien contenu ne soit pas réutilisé. Retourne les octets libérés.
     */
    fun deleteInferenceCache(context: Context, model: AiModel): Long {
        var freed = 0L
        xnnpackCacheFiles(context, model).forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    /** Taille occupée par la copie interne du modèle (0 si elle n'existe pas). */
    fun localCopySize(context: Context, model: AiModel): Long =
        localFileFor(context, model).takeIf { it.exists() }?.length() ?: 0L

    /**
     * Fichiers de cache XNNPACK du modèle dans `cacheDir`. LiteRT-LM les nomme à partir du nom du fichier
     * modèle : on prend ceux dont le nom commence par celui de la copie (ex. `performant.litertlm...`).
     */
    private fun xnnpackCacheFiles(context: Context, model: AiModel): List<File> {
        val prefix = localFileFor(context, model).name
        return context.cacheDir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }?.toList().orEmpty()
    }

    internal fun sha256MarkerFor(copy: File) = File(copy.parentFile, "${copy.name}.sha256")

    /** Sous-dossier de `filesDir` des modèles (plusieurs Go) : exclu des sauvegardes, voir les fichiers backup_rules.xml et data_extraction_rules.xml dans res/xml. */
    const val DIRECTORY_NAME = "models"

    fun localFileFor(context: Context, model: AiModel): File =
        File(File(context.filesDir, DIRECTORY_NAME), "${model.id}.litertlm")
}
