package fr.junade.taipo.model.download

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Traduit un échec technique d'installation en cause affichable ([DownloadFailure]). Pur, testé en JVM. */
object DownloadFailureMapper {

    fun from(failure: InstallResult.Failed): DownloadFailure = when (failure.reason) {
        InstallFailure.HASH_MISMATCH, InstallFailure.SIZE_MISMATCH -> DownloadFailure.CORRUPTED
        InstallFailure.IO -> fromException(failure.cause)
    }

    fun fromException(cause: Throwable?): DownloadFailure = when {
        cause == null -> DownloadFailure.OTHER
        isDiskFull(cause) -> DownloadFailure.NO_SPACE
        cause is SocketException || cause is SocketTimeoutException ||
            cause is UnknownHostException || cause is SSLException -> DownloadFailure.NETWORK
        else -> DownloadFailure.OTHER
    }

    private fun isDiskFull(cause: Throwable): Boolean {
        val message = (cause as? IOException)?.message ?: return false
        return message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }
}
