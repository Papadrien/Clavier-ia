package fr.junade.taipo.dictionary.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import fr.junade.taipo.AppLog
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.dictionary.DictionaryLoader
import fr.junade.taipo.model.download.ModelFileFetcher
import fr.junade.taipo.model.download.ModelInstaller
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Télécharge, au mieux, les bigrammes d'une langue (point 1 de docs/autocorrection-roadmap.md : `bigrams/<code>.txt`, voir
 * [DictionaryFiles.bigramFile]). Ils sont facultatifs : un échec (fichier pas encore sur le serveur, coupure) n'affiche rien et ne
 * change pas l'état de la langue ; l'autocorrection reste celle d'avant, sans le mot précédent. Une fois le fichier là, le
 * dictionnaire est relu au prochain champ ([DictionaryLoader.evict]) pour s'en servir.
 */
class BigramDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val language = KeyboardLanguage.fromTag(inputData.getString(KEY_LANGUAGE)) ?: return Result.failure()
        val filesDir = applicationContext.filesDir
        if (!DictionaryFiles.bigramsMissing(filesDir, language)) return Result.success()
        val destination = DictionaryFiles.bigramFile(filesDir, language)
        destination.parentFile?.mkdirs()
        val outcome = withContext(Dispatchers.IO) {
            val scope = this
            ModelFileFetcher.fetchWithRetry(
                context = applicationContext,
                url = DictionaryFiles.bigramRemoteUrl(language),
                destination = destination,
                expectedSha256 = null, // contrôle de forme à la place (beforeCommit)
                acceptContentLength = { it in 1..MAX_FILE_BYTES },
                allowMobileData = true, // quelques Mo, comme les listes de mots
                isActive = { scope.isActive },
                beforeCommit = {
                    DictionaryContentCheck.requireValid(ModelInstaller.temporaryFileFor(destination), DictionaryContentCheck.Kind.BIGRAMS)
                },
                allowResume = false,
            )
        }
        if (outcome !is ModelFileFetcher.Outcome.Done) {
            AppLog.w(TAG, "bigrammes ${language.tag} non téléchargés : autocorrection sans contexte", null)
            return Result.failure()
        }
        DictionaryLoader.evict(language)
        return Result.success()
    }

    companion object {
        private const val TAG = "BigramDownloadWorker"
        private const val KEY_LANGUAGE = "language"

        /** Garde-fou : les bigrammes font quelques Mo. */
        private const val MAX_FILE_BYTES = 32_000_000L

        // Une seule tentative par langue et par processus : sans cela, un fichier absent du serveur serait redemandé à chaque champ.
        private val attempted: MutableSet<KeyboardLanguage> = Collections.synchronizedSet(HashSet())

        /** Lance le téléchargement des bigrammes de [language] s'ils manquent (une fois par processus), sans rien attendre. */
        fun ensure(context: Context, language: KeyboardLanguage) {
            val app = context.applicationContext
            if (language in attempted || !DictionaryFiles.bigramsMissing(app.filesDir, language) || !attempted.add(language)) return
            val request = OneTimeWorkRequestBuilder<BigramDownloadWorker>()
                .setInputData(workDataOf(KEY_LANGUAGE to language.tag))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork("bigram-download-${language.tag}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
