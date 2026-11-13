package fr.junade.taipo.dictionary.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.junade.taipo.AppLog
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.dictionary.DictionaryFile
import fr.junade.taipo.dictionary.DictionaryFiles
import fr.junade.taipo.model.download.DownloadFailure
import fr.junade.taipo.model.download.ModelFileFetcher
import fr.junade.taipo.model.download.ModelInstaller
import fr.junade.taipo.model.download.PartialDownload
import fr.junade.taipo.model.download.stoppedByApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Télécharge les fichiers manquants du dictionnaire d'une langue (prénoms, liste complète), l'un après
 * l'autre, avec le mécanisme des modèles ([ModelFileFetcher] : HTTPS, `.tmp`, relances après une coupure, rename atomique),
 * mais sans reprise `Range` : chaque tentative repart de zéro (quelques Mo), pour ne jamais recoller deux versions d'un fichier.
 * Pas d'empreinte exacte (retirée le 13/11/2026) : une liste mise à jour sur le serveur doit s'installer ; [DictionaryContentCheck]
 * écarte seulement ce qui n'est pas une liste de la langue. Pas de service au premier plan : quelques Mo à peine.
 */
class DictionaryDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val language = KeyboardLanguage.fromTag(inputData.getString(DictionaryDownloadScheduler.KEY_LANGUAGE))
            ?: return Result.failure()
        val pending = DictionaryFiles.missing(applicationContext.filesDir, language)
        if (pending.isEmpty()) {
            DictionaryDownloadTracker.update(language, DictionaryDownloadState.Installed)
            return Result.success()
        }
        DictionaryDownloadTracker.update(language, DictionaryDownloadState.Downloading(0))
        val outcome = try {
            downloadAll(language, pending)
        } catch (e: CancellationException) {
            settleAfterStop(language)
            throw e
        }
        return when (outcome) {
            is ModelFileFetcher.Outcome.Done -> {
                DictionaryDownloadTracker.update(language, DictionaryDownloadState.Installed)
                Result.success()
            }
            is ModelFileFetcher.Outcome.Cancelled -> {
                settleAfterStop(language)
                Result.failure()
            }
            is ModelFileFetcher.Outcome.Failed -> {
                DictionaryDownloadTracker.update(language, DictionaryDownloadState.Failed(outcome.reason))
                Result.failure()
            }
        }
    }

    /** Annulation par l'application : partiels supprimés ; tout autre arrêt vient du système : partiels gardés, « Réessayer ». */
    private fun settleAfterStop(language: KeyboardLanguage) {
        if (stoppedByApp()) {
            DictionaryFile.entries.forEach {
                PartialDownload.discard(DictionaryFiles.localFile(applicationContext.filesDir, language, it))
            }
            DictionaryDownloadTracker.update(language, DictionaryDownloadState.Idle)
        } else {
            DictionaryDownloadTracker.update(language, DictionaryDownloadState.Failed(DownloadFailure.SYSTEM_STOP))
        }
    }

    private suspend fun downloadAll(language: KeyboardLanguage, pending: List<DictionaryFile>): ModelFileFetcher.Outcome =
        withContext(Dispatchers.IO) {
            val scope = this
            val filesDir = applicationContext.filesDir
            // Les petits fichiers d'abord ; la liste complète, la plus lourde, en dernier.
            val ordered = pending.sortedBy { DictionaryFiles.approxBytes(language, it) }
            var finishedBytes = 0L
            var last: ModelFileFetcher.Outcome = ModelFileFetcher.Outcome.Done(0L, "")
            for ((index, file) in ordered.withIndex()) {
                val remainingApprox = ordered.drop(index + 1).sumOf { DictionaryFiles.approxBytes(language, it) }
                var currentTotal = DictionaryFiles.approxBytes(language, file)
                val done = finishedBytes
                fun report(current: Long) {
                    val all = (done + currentTotal + remainingApprox).coerceAtLeast(1L)
                    val percent = ((done + current) * 100 / all).toInt().coerceIn(0, 99)
                    DictionaryDownloadTracker.update(language, DictionaryDownloadState.Downloading(percent))
                }
                val destination = DictionaryFiles.localFile(filesDir, language, file)
                var rejected = false
                val outcome = ModelFileFetcher.fetchWithRetry(
                    context = applicationContext,
                    url = DictionaryFiles.remoteUrl(language, file),
                    destination = destination,
                    expectedSha256 = null, // contrôle de forme et de taille à la place (beforeCommit)
                    acceptContentLength = { it in 1..MAX_FILE_BYTES },
                    // Quelques Mo : pas de confirmation en données mobiles.
                    allowMobileData = true,
                    isActive = { scope.isActive },
                    keepPartialOnStop = { !stoppedByApp() },
                    allowResume = false,
                    onTotal = {
                        currentTotal = it
                        report(0L)
                    },
                    onProgress = { report(it) },
                    beforeCommit = {
                        try {
                            val temp = ModelInstaller.temporaryFileFor(destination)
                            when (file) {
                                DictionaryFile.FULL -> DictionaryContentCheck.requireValid(
                                    temp,
                                    DictionaryContentCheck.Kind.WORDS,
                                    DictionaryFiles.approxBytes(language, file),
                                )
                                DictionaryFile.NAMES -> DictionaryContentCheck.requireValid(temp, DictionaryContentCheck.Kind.NAMES)
                            }
                        } catch (e: java.io.IOException) {
                            rejected = true
                            throw e
                        }
                    },
                )
                if (outcome is ModelFileFetcher.Outcome.Failed && rejected) {
                    AppLog.w(TAG, "contenu inattendu pour ${file.relativePath(language)}", null)
                    return@withContext ModelFileFetcher.Outcome.Failed(DownloadFailure.SERVER)
                }
                if (outcome !is ModelFileFetcher.Outcome.Done) return@withContext outcome
                finishedBytes += outcome.sizeBytes
                last = outcome
            }
            last
        }

    private companion object {
        const val TAG = "DictionaryDownloadWorker"

        /** Garde-fou : aucune liste n'approche cette taille. */
        const val MAX_FILE_BYTES = 64_000_000L
    }
}
