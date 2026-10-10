package fr.junade.taipo.model.download

import android.app.PendingIntent
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.junade.taipo.AppLog
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.R
import fr.junade.taipo.ai.VoiceEngine
import fr.junade.taipo.model.VoiceModelFile
import fr.junade.taipo.model.VoiceModelFileResolver
import fr.junade.taipo.model.VoiceModelPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Télécharge le modèle vocal (story 8.15) : ses 4 fichiers l'un après l'autre, chacun avec le même mécanisme sûr que
 * les modèles de texte ([ModelFileFetcher] : HTTPS, `.tmp`, SHA-256 pendant le flux, rename atomique). Un fichier déjà
 * téléchargé et à jour est sauté : après un échec, « Réessayer » ne reprend que ce qui manque (reprise au fichier près ;
 * la reprise au milieu d'un fichier reste reportée en V2, comme pour les modèles de texte).
 *
 * Le modèle n'est utilisable qu'une fois les 4 fichiers présents ([VoiceModelPreferences.isComplete]). L'état affiché
 * par l'écran « Modèle IA » passe par [DownloadTracker] (état vocal).
 */
class VoiceModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(percent = null)

    override suspend fun doWork(): Result {
        val allowMobileData = inputData.getBoolean(ModelDownloadScheduler.KEY_MOBILE_DATA_ACCEPTED, false)
        if (!VoiceModelDownload.isDownloadable(allowUnverified = BuildConfig.DEBUG)) {
            DownloadTracker.updateVoice(DownloadState.Failed(DownloadFailure.SERVER))
            return Result.failure()
        }

        DownloadTracker.updateVoice(DownloadState.Downloading(downloadedBytes = 0, totalBytes = -1))
        try {
            // Peut être refusé (Android 12+ : service au premier plan lancé depuis l'arrière-plan) : le
            // téléchargement continue alors comme un travail ordinaire, sans notification.
            setForeground(foregroundInfo(percent = null))
        } catch (e: Exception) {
            AppLog.w(TAG, "Service au premier plan refusé", e)
        }

        return when (val outcome = downloadAll(allowMobileData)) {
            is ModelFileFetcher.Outcome.Done -> {
                DownloadTracker.updateVoice(DownloadState.Idle)
                Result.success()
            }
            is ModelFileFetcher.Outcome.Cancelled -> {
                DownloadTracker.updateVoice(DownloadState.Idle)
                Result.failure()
            }
            is ModelFileFetcher.Outcome.Failed -> {
                DownloadTracker.updateVoice(DownloadState.Failed(outcome.reason))
                Result.failure()
            }
        }
    }

    /**
     * Les fichiers à télécharger, dans l'ordre. Le résultat [ModelFileFetcher.Outcome.Done] porte la taille et
     * l'empreinte du dernier fichier ; l'appelant n'en a pas l'usage, chaque fichier est enregistré dès qu'il est
     * installé.
     */
    private suspend fun downloadAll(allowMobileData: Boolean): ModelFileFetcher.Outcome =
        withContext(Dispatchers.IO) {
            val scope = this
            val appContext = applicationContext
            val preferences = VoiceModelPreferences(appContext)
            val pending = VoiceModelFile.all().filter {
                VoiceModelDownload.needsDownload(
                    downloaded = preferences.isDownloaded(it),
                    present = preferences.isFilePresent(it),
                    updateAvailable = preferences.updateAvailable(it),
                )
            }

            var finishedBytes = 0L
            var last: ModelFileFetcher.Outcome = ModelFileFetcher.Outcome.Done(0L, "")
            for ((index, file) in pending.withIndex()) {
                val isLast = index == pending.lastIndex
                val remainingApprox = VoiceModelDownload.missingBytes(pending.drop(index + 1))
                var currentTotal = file.approxBytes
                val done = finishedBytes
                fun report(current: Long) {
                    val percent = VoiceModelDownload.overallPercent(done, current, currentTotal, remainingApprox)
                    DownloadTracker.updateVoice(DownloadState.Downloading(percent.toLong(), 100L))
                    notifyProgress(percent)
                }

                val outcome = ModelFileFetcher.fetch(
                    context = appContext,
                    url = file.downloadUrl,
                    destination = VoiceModelFileResolver.localFileFor(appContext, file),
                    expectedSha256 = file.sha256,
                    // Pas de plage de taille connue : la taille annoncée et l'empreinte valident le fichier.
                    acceptContentLength = { true },
                    allowMobileData = allowMobileData,
                    isActive = { scope.isActive },
                    onTotal = {
                        currentTotal = it
                        report(0L)
                    },
                    onProgress = { report(it) },
                    // L'état « Vérification » n'est affiché qu'au dernier fichier : avant, le pourcentage continue.
                    onVerifying = { if (isLast) DownloadTracker.updateVoice(DownloadState.Verifying) },
                    // Le moteur de dictée ne doit pas avoir l'ancien fichier ouvert au moment du remplacement.
                    beforeCommit = { VoiceEngine.releaseEverywhere() },
                )
                if (outcome !is ModelFileFetcher.Outcome.Done) return@withContext outcome

                preferences.assignDownloaded(file, outcome.sizeBytes, outcome.sha256)
                // Debug : relever l'empreinte réelle pour la figer dans VoiceModelFile.sha256 (story 8.8).
                if (file.sha256 == null) {
                    AppLog.i(TAG, "SHA-256 de voice/${file.id} : ${outcome.sha256} (${outcome.sizeBytes} octets)")
                }
                finishedBytes += outcome.sizeBytes
                last = outcome
            }
            last
        }

    // --- Notification (service au premier plan) -------------------------------------------------------------

    private fun title(): String = applicationContext.getString(R.string.model_download_voice_notification_title)

    private fun cancelIntent(): PendingIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    private fun foregroundInfo(percent: Int?): ForegroundInfo =
        DownloadNotification.foregroundInfo(applicationContext, NOTIFICATION_ID, title(), percent, cancelIntent())

    private fun notifyProgress(percent: Int) {
        DownloadNotification.notifyProgress(applicationContext, NOTIFICATION_ID, title(), percent, cancelIntent())
    }

    private companion object {
        const val TAG = "VoiceModelDownloadWorker"
        const val NOTIFICATION_ID = 4102
    }
}
