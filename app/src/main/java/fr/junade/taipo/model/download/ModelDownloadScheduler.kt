package fr.junade.taipo.model.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import fr.junade.taipo.model.AiModel

/** Lance et annule les téléchargements de modèles (WorkManager, un travail unique par modèle). */
object ModelDownloadScheduler {

    const val KEY_MODEL_ID = "model_id"
    const val KEY_MOBILE_DATA_ACCEPTED = "mobile_data_accepted"

    fun uniqueWorkName(model: AiModel) = "model-download-${model.id}"

    /** Travail unique du modèle vocal (story 8.15) : ses 4 fichiers sont téléchargés par un seul travail. */
    const val VOICE_WORK_NAME = "voice-model-download"

    /**
     * Démarre le téléchargement de [model]. Sans effet si un téléchargement du même modèle est déjà en cours.
     * [mobileDataAccepted] : l'utilisateur a confirmé le téléchargement en données mobiles (story 8.3).
     */
    fun start(context: Context, model: AiModel, mobileDataAccepted: Boolean) {
        if (DownloadTracker.isBusy(model)) return
        DownloadTracker.update(model, DownloadState.Downloading(downloadedBytes = 0, totalBytes = -1))
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(
                workDataOf(
                    KEY_MODEL_ID to model.id,
                    KEY_MOBILE_DATA_ACCEPTED to mobileDataAccepted,
                ),
            )
            // Le contrôle Wi-Fi / données mobiles est fait par le worker (arrêt propre vers « Réessayer »,
            // sans reprise automatique qui repartirait de zéro) : la contrainte ne exige qu'une connexion.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(uniqueWorkName(model), ExistingWorkPolicy.KEEP, request)
    }

    /** Annule le téléchargement de [model] ; le worker supprime le fichier temporaire. */
    fun cancel(context: Context, model: AiModel) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueWorkName(model))
        DownloadTracker.update(model, DownloadState.Idle)
    }

    /**
     * Démarre le téléchargement du modèle vocal (story 8.15). Sans effet s'il est déjà en cours. Mêmes règles réseau que
     * pour les modèles de texte : le worker s'arrête vers « Réessayer » si le Wi-Fi est perdu sans accord données mobiles.
     */
    fun startVoice(context: Context, mobileDataAccepted: Boolean) {
        if (DownloadTracker.isVoiceBusy()) return
        DownloadTracker.updateVoice(DownloadState.Downloading(downloadedBytes = 0, totalBytes = -1))
        val request = OneTimeWorkRequestBuilder<VoiceModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MOBILE_DATA_ACCEPTED to mobileDataAccepted))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(VOICE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Annule le téléchargement du modèle vocal ; le worker supprime le fichier temporaire en cours. */
    fun cancelVoice(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(VOICE_WORK_NAME)
        DownloadTracker.updateVoice(DownloadState.Idle)
    }
}
