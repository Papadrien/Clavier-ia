package fr.junade.taipo.model.download

import fr.junade.taipo.model.sameSha256
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Cause d'échec d'une installation de modèle (statut « Réessayer », story 8.1). */
enum class InstallFailure {
    /** Empreinte SHA-256 différente de la référence : fichier corrompu ou autre variante. */
    HASH_MISMATCH,

    /** Nombre d'octets reçus différent du `Content-Length` annoncé : téléchargement tronqué. */
    SIZE_MISMATCH,

    /** Erreur de lecture ou d'écriture (réseau coupé, disque plein…). */
    IO,
}

sealed interface InstallResult {
    /** Fichier installé et vérifié ; [sha256] est l'empreinte calculée pendant le flux. */
    data class Installed(val sizeBytes: Long, val sha256: String) : InstallResult

    /** Annulé à la demande : aucun fichier laissé. */
    data object Cancelled : InstallResult

    data class Failed(val reason: InstallFailure, val cause: Throwable? = null) : InstallResult
}

/**
 * Installation sûre d'un modèle téléchargé (story 8.9), sans dépendance Android : testée en JVM.
 *
 * Le flux est écrit dans `<fichier>.tmp`, l'empreinte SHA-256 est calculée pendant l'écriture (aucune relecture
 * de plusieurs Go), puis le fichier est renommé atomiquement vers sa destination. Tout échec ou toute
 * annulation supprime le temporaire : un fichier partiel n'est jamais pris pour un modèle valide.
 */
object ModelInstaller {

    private const val BUFFER_SIZE = 1 shl 20

    fun temporaryFileFor(destination: File) = File(destination.parentFile, "${destination.name}.tmp")

    fun sha256MarkerFor(destination: File) = File(destination.parentFile, "${destination.name}.sha256")

    /**
     * @param expectedBytes taille annoncée par le serveur (`Content-Length`), ou une valeur <= 0 si inconnue
     * @param expectedSha256 empreinte de référence ; null = pas de comparaison (debug seulement, voir [DownloadPolicy])
     * @param beforeCommit appelé une fois le fichier vérifié, juste avant de remplacer la destination
     *   (ex. fermer le moteur qui aurait l'ancien fichier ouvert)
     * @param onVerifying appelé quand tout le flux est reçu et qu'on passe aux contrôles finaux
     */
    fun install(
        input: InputStream,
        destination: File,
        expectedBytes: Long,
        expectedSha256: String?,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
        beforeCommit: () -> Unit = {},
    ): InstallResult {
        val directory = destination.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return InstallResult.Failed(InstallFailure.IO, IOException("Dossier des modèles inaccessible"))
        }
        val temp = temporaryFileFor(destination)
        temp.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) {
                        temp.delete()
                        return InstallResult.Cancelled
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        } catch (e: IOException) {
            temp.delete()
            return InstallResult.Failed(InstallFailure.IO, e)
        }

        onVerifying()
        if (expectedBytes > 0 && total != expectedBytes) {
            temp.delete()
            return InstallResult.Failed(InstallFailure.SIZE_MISMATCH)
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !sameSha256(expectedSha256, actualSha256)) {
            temp.delete()
            return InstallResult.Failed(InstallFailure.HASH_MISMATCH)
        }
        if (isCancelled()) {
            temp.delete()
            return InstallResult.Cancelled
        }

        try {
            beforeCommit()
            move(temp, destination)
            sha256MarkerFor(destination).writeText(actualSha256)
        } catch (e: IOException) {
            temp.delete()
            return InstallResult.Failed(InstallFailure.IO, e)
        }
        return InstallResult.Installed(total, actualSha256)
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
