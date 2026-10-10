package fr.junade.taipo.model.download

import android.content.Context
import android.os.SystemClock
import fr.junade.taipo.AppLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Téléchargement sûr d'un seul fichier (stories 8.3, 8.4, 8.8 et 8.9), partagé par le worker des modèles de texte et
 * celui du modèle vocal : GET HTTPS en flux, contrôle de la taille annoncée et de l'espace libre, arrêt propre si le
 * réseau change, puis installation atomique par [ModelInstaller] (`.tmp`, SHA-256 pendant le flux, rename).
 *
 * Bloquant : à appeler hors du thread principal (le worker le fait sous `Dispatchers.IO`).
 */
object ModelFileFetcher {

    sealed interface Outcome {
        data class Done(val sizeBytes: Long, val sha256: String) : Outcome
        data object Cancelled : Outcome
        data class Failed(val reason: DownloadFailure) : Outcome
    }

    private const val TAG = "ModelFileFetcher"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_INTERVAL_MS = 400L
    private const val NETWORK_CHECK_INTERVAL_MS = 2_000L

    /**
     * @param acceptContentLength la taille annoncée par le serveur est-elle plausible pour ce fichier ? (protège d'une
     *   mauvaise variante) ; une taille <= 0 est toujours refusée
     * @param isActive faux dès que le travail est annulé
     * @param onTotal appelé une fois, avec la taille annoncée, avant le premier octet
     * @param onProgress octets reçus, au plus toutes les 400 ms
     * @param beforeCommit appelé une fois le fichier vérifié, juste avant de remplacer la destination
     */
    fun fetch(
        context: Context,
        url: String,
        destination: File,
        expectedSha256: String?,
        acceptContentLength: (Long) -> Boolean,
        allowMobileData: Boolean,
        isActive: () -> Boolean,
        onTotal: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
        beforeCommit: () -> Unit = {},
    ): Outcome {
        val parsed = URL(url)
        if (parsed.protocol != "https") return Outcome.Failed(DownloadFailure.SERVER)

        val connection = try {
            (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                // Pas de compression : le Content-Length est la vraie taille du fichier.
                setRequestProperty("Accept-Encoding", "identity")
            }
        } catch (e: IOException) {
            return Outcome.Failed(DownloadFailureMapper.fromException(e))
        }

        try {
            val code = try {
                connection.connect()
                connection.responseCode
            } catch (e: IOException) {
                AppLog.w(TAG, "Connexion impossible", e)
                return Outcome.Failed(DownloadFailureMapper.fromException(e))
            }
            // HTTPS obligatoire jusqu'au bout, redirections comprises (story 8.8).
            if (code != HttpURLConnection.HTTP_OK || connection.url.protocol != "https") {
                AppLog.w(TAG, "Réponse inattendue : HTTP $code")
                return Outcome.Failed(DownloadFailure.SERVER)
            }
            val contentLength = connection.contentLengthLong
            // Le serveur doit annoncer une taille cohérente avec le fichier attendu.
            if (contentLength <= 0 || !acceptContentLength(contentLength)) {
                AppLog.w(TAG, "Taille annoncée inattendue : $contentLength")
                return Outcome.Failed(DownloadFailure.SERVER)
            }
            if (!DownloadPolicy.hasEnoughSpace(DownloadEnvironment.freeBytes(context), contentLength)) {
                return Outcome.Failed(DownloadFailure.NO_SPACE)
            }

            onTotal(contentLength)

            var networkLost = false
            var lastNetworkCheck = 0L
            var lastReport = 0L
            val isCancelled: () -> Boolean = {
                if (!isActive()) {
                    true
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNetworkCheck >= NETWORK_CHECK_INTERVAL_MS) {
                        lastNetworkCheck = now
                        if (DownloadPolicy.shouldAbortForNetwork(DownloadEnvironment.networkKind(context), allowMobileData)) {
                            networkLost = true
                        }
                    }
                    networkLost
                }
            }

            val result = try {
                connection.inputStream.use { input ->
                    ModelInstaller.install(
                        input = input,
                        destination = destination,
                        expectedBytes = contentLength,
                        expectedSha256 = expectedSha256,
                        isCancelled = isCancelled,
                        onProgress = { bytes ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(bytes)
                            }
                        },
                        onVerifying = onVerifying,
                        beforeCommit = beforeCommit,
                    )
                }
            } catch (e: IOException) {
                AppLog.w(TAG, "Flux interrompu", e)
                return Outcome.Failed(DownloadFailureMapper.fromException(e))
            }

            return when (result) {
                is InstallResult.Installed -> Outcome.Done(result.sizeBytes, result.sha256)
                is InstallResult.Cancelled ->
                    if (networkLost) Outcome.Failed(DownloadFailure.NETWORK) else Outcome.Cancelled
                is InstallResult.Failed -> {
                    AppLog.w(TAG, "Installation échouée : ${result.reason}", result.cause)
                    Outcome.Failed(DownloadFailureMapper.from(result))
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
