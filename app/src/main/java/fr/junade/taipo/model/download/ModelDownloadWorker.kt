package fr.junade.taipo.model.download

import android.app.PendingIntent
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.junade.taipo.AppLog
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.R
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.sizeWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Télécharge un modèle de texte (épopée 8) : GET HTTPS en flux vers `models/<id>.litertlm.tmp`, SHA-256 calculé
 * pendant le flux, rename atomique (voir [ModelFileFetcher] et [ModelInstaller]). Service au premier plan avec
 * notification : un téléchargement de plusieurs Go doit continuer écran éteint.
 *
 * Reprise (point 1.7 de l'audit) : une coupure réseau (relancée automatiquement, voir
 * [ModelFileFetcher.fetchWithRetry]) ou un arrêt par le système (délai du service `dataSync` d'Android 15, contrainte
 * perdue) conserve le `.tmp` ; « Réessayer » reprend là où il s'est arrêté. Seule une annulation par l'utilisateur
 * supprime le partiel.
 *
 * L'état affiché par les écrans passe par [DownloadTracker]. Le modèle vocal a son propre worker
 * ([VoiceModelDownloadWorker]).
 */
class ModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val model: AiModel? = AiModel.byId(inputData.getString(ModelDownloadScheduler.KEY_MODEL_ID))

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(percent = null)

    override suspend fun doWork(): Result {
        val model = model ?: return Result.failure()
        val allowMobileData = inputData.getBoolean(ModelDownloadScheduler.KEY_MOBILE_DATA_ACCEPTED, false)
        if (!DownloadPolicy.isDownloadable(model, allowUnverified = BuildConfig.DEBUG)) {
            DownloadTracker.update(model, DownloadState.Failed(DownloadFailure.SERVER))
            return Result.failure()
        }

        DownloadTracker.update(model, DownloadState.Downloading(downloadedBytes = 0, totalBytes = -1))
        Analytics.track(applicationContext, AnalyticsEvents.MODEL_DOWNLOAD_STARTED, "model" to model.id)
        try {
            // Peut être refusé (Android 12+ : service au premier plan lancé depuis l'arrière-plan) : le
            // téléchargement continue alors comme un travail ordinaire, sans notification.
            setForeground(foregroundInfo(percent = null))
        } catch (e: Exception) {
            AppLog.w(TAG, "Service au premier plan refusé", e)
        }

        val outcome = try {
            download(model, allowMobileData)
        } catch (e: CancellationException) {
            // Arrêt pendant une attente entre deux tentatives (hors du flux) : même traitement qu'un arrêt en cours de flux.
            settleAfterStop(model)
            throw e
        }
        return when (outcome) {
            is ModelFileFetcher.Outcome.Done -> {
                val appContext = applicationContext
                val preferences = ModelPreferences(appContext)
                preferences.assignDownloaded(model, outcome.sizeBytes, outcome.sha256)
                // Premier modèle installé : il devient le modèle actif (il reste modifiable dans l'écran).
                val active = preferences.activeModel()
                if (active == null || !preferences.isInstalled(active)) preferences.setActiveModel(model)
                // Debug : relever l'empreinte réelle pour la figer dans AiModel.sha256 (story 8.8).
                if (model.sha256 == null) AppLog.i(TAG, "SHA-256 de ${model.id} : ${outcome.sha256} (${outcome.sizeBytes} octets)")
                DownloadTracker.update(model, DownloadState.Idle)
                Analytics.track(appContext, AnalyticsEvents.MODEL_DOWNLOAD_DONE, "model" to model.id)
                Result.success()
            }
            is ModelFileFetcher.Outcome.Cancelled -> {
                settleAfterStop(model)
                Result.failure()
            }
            is ModelFileFetcher.Outcome.Failed -> {
                DownloadTracker.update(model, DownloadState.Failed(outcome.reason))
                Analytics.track(
                    applicationContext,
                    AnalyticsEvents.MODEL_DOWNLOAD_FAILED,
                    "model" to model.id,
                    "reason" to outcome.reason.name.lowercase(),
                )
                Result.failure()
            }
        }
    }

    /**
     * Après un arrêt du travail : annulation par l'utilisateur (écran ou notification) = retour à « Télécharger » et
     * partiel supprimé ; tout autre arrêt (délai du service au premier plan d'Android 15+, contrainte perdue…) vient du
     * système : on l'annonce (« Réessayer ») et le partiel est conservé pour la reprise.
     */
    private fun settleAfterStop(model: AiModel) {
        if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            PartialDownload.discard(ModelFileResolver.localFileFor(applicationContext, model))
            DownloadTracker.update(model, DownloadState.Idle)
        } else {
            DownloadTracker.update(model, DownloadState.Failed(DownloadFailure.SYSTEM_STOP))
        }
    }

    private suspend fun download(model: AiModel, allowMobileData: Boolean): ModelFileFetcher.Outcome =
        withContext(Dispatchers.IO) {
            val scope = this
            val appContext = applicationContext
            val url = requireNotNull(model.downloadUrl) { "Model without a download URL: ${model.id}" }
            var total = -1L
            ModelFileFetcher.fetchWithRetry(
                context = appContext,
                url = url,
                destination = ModelFileResolver.localFileFor(appContext, model),
                expectedSha256 = model.sha256,
                // La taille annoncée doit rester dans la plage du modèle : protège aussi d'une mauvaise variante.
                acceptContentLength = { model.sizeWarning(it) == null },
                allowMobileData = allowMobileData,
                isActive = { scope.isActive },
                keepPartialOnStop = { stopReason != WorkInfo.STOP_REASON_CANCELLED_BY_APP },
                onTotal = {
                    total = it
                    DownloadTracker.update(model, DownloadState.Downloading(0, it))
                },
                onProgress = { bytes ->
                    DownloadTracker.update(model, DownloadState.Downloading(bytes, total))
                    if (total > 0) notifyProgress((bytes * 100 / total).toInt())
                },
                onVerifying = { DownloadTracker.update(model, DownloadState.Verifying) },
                // Le moteur ne doit pas avoir l'ancien fichier ouvert au moment du remplacement.
                beforeCommit = {
                    LlmEngineHost.releaseModelEverywhere(model)
                    // Mise à jour (8.14) : un cache construit sur l'ancien fichier ne doit pas survivre au remplacement.
                    ModelFileResolver.deleteInferenceCache(appContext, model)
                },
            )
        }

    // --- Notification (service au premier plan) -------------------------------------------------------------

    private fun title(): String =
        applicationContext.getString(R.string.model_download_notification_title, model?.displayName(applicationContext).orEmpty())

    private fun cancelIntent(): PendingIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)

    private fun foregroundInfo(percent: Int?): ForegroundInfo =
        DownloadNotification.foregroundInfo(applicationContext, NOTIFICATION_ID, title(), percent, cancelIntent())

    private fun notifyProgress(percent: Int) {
        DownloadNotification.notifyProgress(applicationContext, NOTIFICATION_ID, title(), percent, cancelIntent())
    }

    private companion object {
        const val TAG = "ModelDownloadWorker"
        const val NOTIFICATION_ID = 4101
    }
}
