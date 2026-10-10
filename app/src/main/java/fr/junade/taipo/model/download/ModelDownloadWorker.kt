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
import fr.junade.taipo.ai.LlmEngineHost
import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.ModelFileResolver
import fr.junade.taipo.model.ModelPreferences
import fr.junade.taipo.model.sizeWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Télécharge un modèle de texte (épopée 8) : GET HTTPS en flux vers `models/<id>.litertlm.tmp`, SHA-256 calculé
 * pendant le flux, rename atomique (voir [ModelFileFetcher] et [ModelInstaller]). Service au premier plan avec
 * notification : un téléchargement de plusieurs Go doit continuer écran éteint. Aucune reprise : un téléchargement
 * interrompu repart de zéro (V2).
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
        try {
            // Peut être refusé (Android 12+ : service au premier plan lancé depuis l'arrière-plan) : le
            // téléchargement continue alors comme un travail ordinaire, sans notification.
            setForeground(foregroundInfo(percent = null))
        } catch (e: Exception) {
            AppLog.w(TAG, "Service au premier plan refusé", e)
        }

        return when (val outcome = download(model, allowMobileData)) {
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
                Result.success()
            }
            is ModelFileFetcher.Outcome.Cancelled -> {
                DownloadTracker.update(model, DownloadState.Idle)
                Result.failure()
            }
            is ModelFileFetcher.Outcome.Failed -> {
                DownloadTracker.update(model, DownloadState.Failed(outcome.reason))
                Result.failure()
            }
        }
    }

    private suspend fun download(model: AiModel, allowMobileData: Boolean): ModelFileFetcher.Outcome =
        withContext(Dispatchers.IO) {
            val scope = this
            val appContext = applicationContext
            val url = requireNotNull(model.downloadUrl) { "Modèle sans URL de téléchargement : ${model.id}" }
            var total = -1L
            ModelFileFetcher.fetch(
                context = appContext,
                url = url,
                destination = ModelFileResolver.localFileFor(appContext, model),
                expectedSha256 = model.sha256,
                // La taille annoncée doit rester dans la plage du modèle : protège aussi d'une mauvaise variante.
                acceptContentLength = { model.sizeWarning(it) == null },
                allowMobileData = allowMobileData,
                isActive = { scope.isActive },
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
        applicationContext.getString(R.string.model_download_notification_title, model?.displayName.orEmpty())

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
