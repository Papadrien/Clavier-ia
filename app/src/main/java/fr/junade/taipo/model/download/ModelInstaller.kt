package fr.junade.taipo.model.download

import fr.junade.taipo.model.sameSha256
import java.io.File
import java.io.FileOutputStream
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

    /** Plus d'octets reçus que la taille annoncée : fichier incohérent (le partiel est supprimé). */
    SIZE_MISMATCH,

    /** Flux terminé avant la taille annoncée : le partiel est conservé pour une reprise (`Range`). */
    TRUNCATED,

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
 * Le flux est écrit dans `<fichier>.tmp`, l'empreinte SHA-256 est calculée pendant l'écriture, puis le fichier est
 * renommé atomiquement vers sa destination. Un fichier partiel n'est jamais pris pour un modèle valide.
 *
 * Reprise (point 1.7 de l'audit) : une coupure du flux (réseau, arrêt du système) **conserve** le `.tmp` ; à la
 * relance, [install] le relit une fois pour continuer le SHA-256 (le condensat Java ne se sauvegarde pas), puis
 * ajoute la suite. Une annulation demandée par l'utilisateur, une empreinte fausse, un fichier trop gros ou une
 * erreur d'écriture (disque plein) suppriment le temporaire.
 */
object ModelInstaller {

    private const val BUFFER_SIZE = 1 shl 20

    fun temporaryFileFor(destination: File) = File(destination.parentFile, "${destination.name}.tmp")

    fun sha256MarkerFor(destination: File) = File(destination.parentFile, "${destination.name}.sha256")

    /** Marqueur de reprise du `.tmp` : voir [ResumeMarker]. */
    fun resumeMarkerFor(destination: File) = File(destination.parentFile, "${destination.name}.tmp.resume")

    /**
     * @param expectedBytes taille TOTALE du fichier (pas seulement ce qui reste à recevoir), ou une valeur <= 0 si inconnue
     * @param expectedSha256 empreinte de référence ; null = pas de comparaison (debug seulement, voir [DownloadPolicy])
     * @param onProgress octets cumulés, la partie déjà présente comprise
     * @param beforeCommit appelé une fois le fichier vérifié, juste avant de remplacer la destination
     *   (ex. fermer le moteur qui aurait l'ancien fichier ouvert)
     * @param onVerifying appelé quand tout le flux est reçu et qu'on passe aux contrôles finaux
     * @param resumeFrom taille du partiel à reprendre : [input] doit alors fournir la suite du fichier à partir de cet
     *   octet. Ignoré (on repart de zéro) si le `.tmp` n'a pas exactement cette taille.
     * @param keepPartial interrogé quand [isCancelled] devient vrai : vrai = arrêt temporaire (le partiel est gardé),
     *   faux = annulation (le partiel est supprimé)
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
        resumeFrom: Long = 0L,
        keepPartial: () -> Boolean = { false },
    ): InstallResult {
        val directory = destination.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            return InstallResult.Failed(InstallFailure.IO, IOException("Models folder inaccessible"))
        }
        val temp = temporaryFileFor(destination)
        val resuming = resumeFrom > 0 && temp.isFile && temp.length() == resumeFrom
        if (!resuming) temp.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L

        fun interrupted(): InstallResult {
            if (!keepPartial()) PartialDownload.discard(destination)
            return InstallResult.Cancelled
        }

        if (resuming) {
            // Relecture du partiel pour reprendre le SHA-256 là où il s'était arrêté.
            try {
                temp.inputStream().use { existing ->
                    while (true) {
                        if (isCancelled()) return interrupted()
                        val read = existing.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            } catch (e: IOException) {
                PartialDownload.discard(destination)
                return InstallResult.Failed(InstallFailure.IO, e)
            }
        }

        var readFailure: IOException? = null
        try {
            FileOutputStream(temp, resuming).use { output ->
                while (true) {
                    if (isCancelled()) return interrupted()
                    val read = try {
                        input.read(buffer)
                    } catch (e: IOException) {
                        readFailure = e
                        break
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        } catch (e: IOException) {
            // Écriture ou fermeture du fichier (disque plein…) : rien à reprendre.
            PartialDownload.discard(destination)
            return InstallResult.Failed(InstallFailure.IO, e)
        }
        val interruption = readFailure
        if (interruption != null) {
            // Flux coupé (réseau) : le partiel est gardé pour une reprise.
            return InstallResult.Failed(InstallFailure.IO, interruption)
        }

        if (expectedBytes > 0 && total > expectedBytes) {
            PartialDownload.discard(destination)
            return InstallResult.Failed(InstallFailure.SIZE_MISMATCH)
        }
        if (expectedBytes > 0 && total < expectedBytes) {
            return InstallResult.Failed(InstallFailure.TRUNCATED)
        }
        onVerifying()
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !sameSha256(expectedSha256, actualSha256)) {
            PartialDownload.discard(destination)
            return InstallResult.Failed(InstallFailure.HASH_MISMATCH)
        }
        if (isCancelled()) return interrupted()

        try {
            beforeCommit()
            move(temp, destination)
            sha256MarkerFor(destination).writeText(actualSha256)
        } catch (e: IOException) {
            PartialDownload.discard(destination)
            return InstallResult.Failed(InstallFailure.IO, e)
        }
        resumeMarkerFor(destination).delete()
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
