package fr.junade.taipo.dictionary.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.DictionaryFiles

/** Lance le téléchargement des dictionnaires (WorkManager, un travail unique par langue). */
object DictionaryDownloadScheduler {

    const val KEY_LANGUAGE = "language"

    fun uniqueWorkName(language: KeyboardLanguage) = "dictionary-download-${language.tag}"

    /**
     * Lance le téléchargement des fichiers manquants de [language]. Sans effet si la langue est déjà installée ou déjà en
     * cours. Après un échec, seul [retry] relance : sans cela, le clavier referait la requête à chaque ouverture de champ.
     * Pas de confirmation en données mobiles : une langue pèse quelques Mo (voir [DictionaryFiles.approxBytes]).
     * Sans connexion, le travail attend qu'il y en ait une (état « en attente »).
     */
    fun ensure(context: Context, language: KeyboardLanguage, retry: Boolean = false) {
        val app = context.applicationContext
        if (DictionaryFiles.isInstalled(app.filesDir, language)) return
        if (DictionaryDownloadTracker.isBusy(language)) return
        if (!retry && DictionaryDownloadTracker.stateOf(language) is DictionaryDownloadState.Failed) return
        DictionaryDownloadTracker.update(language, DictionaryDownloadState.Queued)
        val request = OneTimeWorkRequestBuilder<DictionaryDownloadWorker>()
            .setInputData(workDataOf(KEY_LANGUAGE to language.tag))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(uniqueWorkName(language), ExistingWorkPolicy.KEEP, request)
    }

    /** « Réessayer » : relance les fichiers manquants (le fichier en cours reprend là où il s'était arrêté). */
    fun retry(context: Context, language: KeyboardLanguage) = ensure(context, language, retry = true)
}
