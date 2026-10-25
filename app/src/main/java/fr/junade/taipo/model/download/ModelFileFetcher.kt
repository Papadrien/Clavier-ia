package fr.junade.taipo.model.download

import android.content.Context
import android.os.SystemClock
import fr.junade.taipo.AppLog
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Téléchargement sûr d'un seul fichier (stories 8.3, 8.4, 8.8 et 8.9), partagé par le worker des modèles de texte et
 * celui du modèle vocal : GET HTTPS en flux, contrôle de la taille annoncée et de l'espace libre, arrêt propre si le
 * réseau change, puis installation atomique par [ModelInstaller] (`.tmp`, SHA-256 pendant le flux, rename).
 *
 * **Reprise (point 1.7 de l'audit).** Une coupure du réseau ou un arrêt par le système conserve le `.tmp` ; la tentative
 * suivante demande la suite du fichier (`Range: bytes=N-`, avec `If-Range` si le serveur a donné un validateur). Le
 * contrôle final SHA-256 porte toujours sur le fichier entier : un partiel obsolète ou altéré ne peut pas être installé.
 * Si le serveur répond 200 (il ignore `Range`, ou le fichier a changé), on repart de zéro ; sur 416, le partiel est
 * supprimé et on repart de zéro aussi.
 *
 * Bloquant : [fetch] est à appeler hors du thread principal (le worker le fait sous `Dispatchers.IO`).
 * [fetchWithRetry] ajoute les relances automatiques après une coupure réseau.
 */
object ModelFileFetcher {

    sealed interface Outcome {
        data class Done(val sizeBytes: Long, val sha256: String) : Outcome
        data object Cancelled : Outcome

        /**
         * [retryable] : coupure réseau ou fichier incohérent, qu'une nouvelle tentative (reprise) peut résoudre. Faux pour
         * un arrêt voulu (Wi-Fi quitté sans accord données mobiles), un refus du serveur, un disque plein…
         */
        data class Failed(val reason: DownloadFailure, val retryable: Boolean = false) : Outcome
    }

    /** [requestedFrom] : premier octet demandé (0 = fichier entier). */
    private class Response(val connection: HttpURLConnection, val code: Int, val requestedFrom: Long)

    /** Ce que la réponse du serveur permet de faire : transférer (à partir de [resumeFrom]) ou s'arrêter avec [Stop.outcome]. */
    private sealed interface Plan {
        data class Transfer(val total: Long, val resumeFrom: Long) : Plan
        data class Stop(val outcome: Outcome) : Plan
    }

    private const val TAG = "ModelFileFetcher"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_INTERVAL_MS = 400L
    private const val NETWORK_CHECK_INTERVAL_MS = 2_000L

    /**
     * @param acceptContentLength la taille TOTALE annoncée par le serveur est-elle plausible pour ce fichier ? (protège
     *   d'une mauvaise variante) ; une taille <= 0 est toujours refusée
     * @param isActive faux dès que le travail est annulé ou arrêté
     * @param keepPartialOnStop quand [isActive] devient faux : vrai = arrêt temporaire (on garde le partiel pour
     *   reprendre), faux = annulation par l'utilisateur (le partiel est supprimé)
     * @param onTotal appelé une fois, avec la taille totale, avant le premier octet
     * @param onProgress octets cumulés (partie déjà présente comprise), au plus toutes les 400 ms
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
        keepPartialOnStop: () -> Boolean = { false },
        onTotal: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
        beforeCommit: () -> Unit = {},
    ): Outcome {
        val parsed = URL(url)
        if (parsed.protocol != "https") return Outcome.Failed(DownloadFailure.SERVER)

        val offset = PartialDownload.usableOffset(destination, System.currentTimeMillis())
        val savedMarker = if (offset > 0) PartialDownload.readMarker(destination) else null

        val response = try {
            openResponse(parsed, offset, savedMarker?.validator, destination)
        } catch (e: IOException) {
            AppLog.w(TAG, "Connexion impossible", e)
            return failedFrom(e)
        }
        val connection = response.connection
        try {
            val plan = planTransfer(response, savedMarker, context, destination, acceptContentLength)
            val (total, resumeFrom) = when (plan) {
                is Plan.Stop -> return plan.outcome
                is Plan.Transfer -> plan
            }

            PartialDownload.writeMarker(
                destination,
                ResumeMarker(
                    totalBytes = total,
                    validator = PartialDownload.validatorOf(
                        connection.getHeaderField("ETag"),
                        connection.getHeaderField("Last-Modified"),
                    ),
                ),
            )
            onTotal(total)

            var networkLost = false
            var lostKind: NetworkKind? = null
            var lastNetworkCheck = 0L
            var lastReport = 0L
            val isCancelled: () -> Boolean = {
                if (!isActive()) {
                    true
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNetworkCheck >= NETWORK_CHECK_INTERVAL_MS) {
                        lastNetworkCheck = now
                        val kind = DownloadEnvironment.networkKind(context)
                        if (DownloadPolicy.shouldAbortForNetwork(kind, allowMobileData)) {
                            networkLost = true
                            lostKind = kind
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
                        expectedBytes = total,
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
                        resumeFrom = resumeFrom,
                        // Réseau perdu : arrêt temporaire, le partiel sert à la reprise.
                        keepPartial = { networkLost || keepPartialOnStop() },
                    )
                }
            } catch (e: IOException) {
                AppLog.w(TAG, "Flux interrompu", e)
                return failedFrom(e)
            }

            return when (result) {
                is InstallResult.Installed -> Outcome.Done(result.sizeBytes, result.sha256)
                is InstallResult.Cancelled ->
                    // Coupure de connexion : on retente tout seul ; Wi-Fi quitté sans accord données mobiles : on s'arrête.
                    if (networkLost) Outcome.Failed(DownloadFailure.NETWORK, retryable = lostKind == NetworkKind.NONE)
                    else Outcome.Cancelled
                is InstallResult.Failed -> {
                    AppLog.w(TAG, "Installation échouée : ${result.reason}", result.cause)
                    val reason = DownloadFailureMapper.from(result)
                    Outcome.Failed(reason, retryable = reason == DownloadFailure.NETWORK)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * [fetch] avec relances automatiques : après une coupure réseau, on attend (2 s, 6 s, 18 s…) puis on reprend le
     * fichier là où il s'est arrêté. On abandonne (vers « Réessayer », partiel conservé) après
     * [DownloadPolicy.MAX_FAILURES_WITHOUT_PROGRESS] échecs d'affilée sans un octet de plus ; chaque octet gagné
     * remet le compteur à zéro. Même paramètres que [fetch].
     */
    suspend fun fetchWithRetry(
        context: Context,
        url: String,
        destination: File,
        expectedSha256: String?,
        acceptContentLength: (Long) -> Boolean,
        allowMobileData: Boolean,
        isActive: () -> Boolean,
        keepPartialOnStop: () -> Boolean = { false },
        onTotal: (Long) -> Unit = {},
        onProgress: (Long) -> Unit = {},
        onVerifying: () -> Unit = {},
        beforeCommit: () -> Unit = {},
    ): Outcome {
        var lastOffset = PartialDownload.currentOffset(destination)
        var failuresWithoutProgress = 0
        while (true) {
            val outcome = fetch(
                context, url, destination, expectedSha256, acceptContentLength, allowMobileData, isActive,
                keepPartialOnStop, onTotal, onProgress, onVerifying, beforeCommit,
            )
            if (outcome !is Outcome.Failed || !outcome.retryable || !isActive()) return outcome

            val offset = PartialDownload.currentOffset(destination)
            // Des octets ont été gagnés depuis la dernière tentative : cet échec est le premier d'une nouvelle série.
            failuresWithoutProgress = if (offset > lastOffset) 1 else failuresWithoutProgress + 1
            lastOffset = offset
            if (!DownloadPolicy.canAutoRetry(failuresWithoutProgress)) return outcome

            val wait = DownloadPolicy.retryDelayMs(failuresWithoutProgress)
            AppLog.w(TAG, "Nouvelle tentative dans $wait ms (à partir de l'octet $offset)", null)
            delay(wait)
        }
    }

    /** Ouvre la connexion ; sur 416 (partiel incohérent avec le fichier du serveur), supprime le partiel et repart de zéro. */
    private fun openResponse(url: URL, offset: Long, validator: String?, destination: File): Response {
        val first = request(url, offset, validator)
        if (offset > 0 && first.code == HTTP_RANGE_NOT_SATISFIABLE) {
            first.connection.disconnect()
            PartialDownload.discard(destination)
            return request(url, 0L)
        }
        return first
    }

    /** Décide, d'après la réponse du serveur, de ce qu'on transfère (taille totale, point de reprise) ou de l'arrêt. */
    private fun planTransfer(
        response: Response,
        savedMarker: ResumeMarker?,
        context: Context,
        destination: File,
        acceptContentLength: (Long) -> Boolean,
    ): Plan {
        val connection = response.connection
        // HTTPS obligatoire jusqu'au bout, redirections comprises (story 8.8).
        if (connection.url.protocol != "https") {
            AppLog.w(TAG, "Redirection hors HTTPS refusée")
            return Plan.Stop(Outcome.Failed(DownloadFailure.SERVER))
        }

        val requestedFrom = response.requestedFrom
        var resumeFrom = requestedFrom
        val total: Long = when (response.code) {
            HttpURLConnection.HTTP_OK -> {
                // Réponse complète : le serveur ignore `Range`, ou le fichier a changé (If-Range) : on repart de zéro.
                resumeFrom = 0L
                connection.contentLengthLong
            }
            HttpURLConnection.HTTP_PARTIAL -> {
                val range = ContentRange.parse(connection.getHeaderField("Content-Range"))
                if (requestedFrom <= 0 || range == null || range.start != requestedFrom || range.end != range.total - 1) {
                    AppLog.w(TAG, "Réponse 206 inattendue")
                    return Plan.Stop(Outcome.Failed(DownloadFailure.SERVER))
                }
                if (savedMarker != null && savedMarker.totalBytes != range.total) {
                    // Le fichier du serveur a changé depuis le début du partiel : celui-ci est inutilisable.
                    AppLog.w(TAG, "Taille totale différente de celle du partiel : reprise abandonnée")
                    PartialDownload.discard(destination)
                    return Plan.Stop(Outcome.Failed(DownloadFailure.CORRUPTED, retryable = true))
                }
                range.total
            }
            else -> {
                AppLog.w(TAG, "Réponse inattendue : HTTP ${response.code}")
                return Plan.Stop(Outcome.Failed(DownloadFailure.SERVER))
            }
        }
        // Le serveur doit annoncer une taille cohérente avec le fichier attendu.
        if (total <= 0 || !acceptContentLength(total)) {
            AppLog.w(TAG, "Taille annoncée inattendue : $total")
            return Plan.Stop(Outcome.Failed(DownloadFailure.SERVER))
        }
        // Seul ce qu'il reste à recevoir doit tenir sur le disque.
        if (!DownloadPolicy.hasEnoughSpace(DownloadEnvironment.freeBytes(context), total - resumeFrom)) {
            return Plan.Stop(Outcome.Failed(DownloadFailure.NO_SPACE))
        }
        return Plan.Transfer(total, resumeFrom)
    }

    private fun request(url: URL, resumeFrom: Long, validator: String? = null): Response {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            // Pas de compression : le Content-Length est la vraie taille du fichier.
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (resumeFrom > 0) {
                connection.setRequestProperty("Range", "bytes=$resumeFrom-")
                if (validator != null) connection.setRequestProperty("If-Range", validator)
            }
            connection.connect()
            return Response(connection, connection.responseCode, resumeFrom)
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private fun failedFrom(e: IOException): Outcome.Failed {
        val reason = DownloadFailureMapper.fromException(e)
        return Outcome.Failed(reason, retryable = reason == DownloadFailure.NETWORK)
    }

    private const val HTTP_RANGE_NOT_SATISFIABLE = 416
}
