package fr.junade.taipo.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Persiste, pour chaque [VoiceModelFile], son origine : le fichier téléchargé dans l'app (story 8.15) ou, en debug,
 * le fichier local choisi par l'utilisateur (URI SAF, avec prise de permission persistante).
 *
 * Même logique que [ModelPreferences] pour les modèles de texte : un fichier a une seule origine à la fois, le
 * dernier installé remplace l'autre (story 8.12), et le fichier réel est toujours dans
 * `filesDir/voice-model/` ([VoiceModelFileResolver]).
 */
class VoiceModelPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = appContext.contentResolver

    fun savedUriFor(file: VoiceModelFile): Uri? =
        prefs.getString(uriKey(file), null)?.let { Uri.parse(it) }

    fun savedFileNameFor(file: VoiceModelFile): String? = prefs.getString(fileNameKey(file), null)

    /** Vrai si le fichier a été téléchargé dans l'app (il est alors directement dans `voice-model/`). */
    fun isDownloaded(file: VoiceModelFile): Boolean = downloadedSizeFor(file) > 0

    /** Taille en octets du fichier téléchargé, contrôlée avant chaque chargement (story 8.9), ou -1. */
    fun downloadedSizeFor(file: VoiceModelFile): Long = prefs.getLong(downloadedSizeKey(file), -1L)

    /** Empreinte SHA-256 enregistrée à l'installation d'un fichier téléchargé, ou null. */
    fun savedSha256For(file: VoiceModelFile): String? = prefs.getString(sha256Key(file), null)

    /**
     * Le fichier est présent : téléchargé ET bien là, à la taille enregistrée (après une restauration sur un autre
     * appareil, les préférences peuvent subsister sans le fichier), ou fourni à la main.
     */
    fun isFilePresent(file: VoiceModelFile): Boolean {
        if (isDownloaded(file)) {
            val local = VoiceModelFileResolver.localFileFor(appContext, file)
            return local.isFile && local.length() == downloadedSizeFor(file)
        }
        return savedUriFor(file) != null
    }

    /** Story 8.14 appliquée au modèle vocal : fichier téléchargé dont l'empreinte diffère de celle du catalogue. */
    fun updateAvailable(file: VoiceModelFile): Boolean =
        isFilePresent(file) && ModelUpdate.isAvailable(isDownloaded(file), savedSha256For(file), file.sha256)

    /** Vrai si au moins un fichier téléchargé a une nouvelle version au catalogue. Le modèle reste utilisable en attendant. */
    fun updateAvailable(): Boolean = VoiceModelFile.all().any { updateAvailable(it) }

    /** Vrai si au moins un fichier a été chargé à la main (debug) : le modèle s'affiche alors « Fichier local ». */
    fun hasLocalFile(): Boolean = VoiceModelFile.all().any { isFilePresent(it) && !isDownloaded(it) }

    fun isComplete(): Boolean = VoiceModelFile.all().all { isFilePresent(it) }

    /** Nombre de fichiers du modèle vocal présents (sur [VoiceModelFile.all]) : sert au statut de l'accueil. */
    fun providedCount(): Int = VoiceModelFile.all().count { isFilePresent(it) }

    /**
     * Enregistre un fichier téléchargé et vérifié. Remplace un éventuel fichier fourni à la main : la référence SAF est
     * oubliée (le fichier de `voice-model/` est désormais celui du téléchargement).
     */
    fun assignDownloaded(file: VoiceModelFile, sizeBytes: Long, sha256: String) {
        releaseUriPermission(file)
        prefs.edit()
            .remove(uriKey(file))
            .remove(fileNameKey(file))
            .putLong(downloadedSizeKey(file), sizeBytes)
            .putString(sha256Key(file), sha256)
            .apply()
    }

    fun assignUri(file: VoiceModelFile, uri: Uri, fileName: String?) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Le fournisseur de documents ne supporte pas la permission persistante.
        }
        // Le fichier fourni remplace un éventuel téléchargement, et la copie interne de l'ancien fichier ne vaut plus :
        // elle serait sinon réutilisée à la place du nouveau fichier (le dernier installé remplace l'autre, 8.12).
        VoiceModelFileResolver.deleteLocalCopy(appContext, file)
        prefs.edit()
            .putString(uriKey(file), uri.toString())
            .putString(fileNameKey(file), fileName)
            .remove(downloadedSizeKey(file))
            .remove(sha256Key(file))
            .apply()
    }

    /**
     * Oublie tout le modèle vocal (story 8.15, suppression) : références, préférences et fichiers copiés. Le moteur
     * doit être fermé avant (voir `VoiceEngine.releaseEverywhere`). Retourne le nombre d'octets libérés.
     */
    fun clearAndDeleteAll(): Long {
        var freed = 0L
        VoiceModelFile.all().forEach { file ->
            releaseUriPermission(file)
            prefs.edit()
                .remove(uriKey(file))
                .remove(fileNameKey(file))
                .remove(downloadedSizeKey(file))
                .remove(sha256Key(file))
                .apply()
            freed += VoiceModelFileResolver.deleteLocalCopy(appContext, file)
        }
        return freed
    }

    private fun releaseUriPermission(file: VoiceModelFile) {
        savedUriFor(file)?.let { uri ->
            try {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // La permission n'existait déjà plus : rien à faire.
            }
        }
    }

    private fun uriKey(file: VoiceModelFile) = "voice_uri_${file.id}"
    private fun fileNameKey(file: VoiceModelFile) = "voice_name_${file.id}"
    private fun downloadedSizeKey(file: VoiceModelFile) = "voice_downloaded_size_${file.id}"
    private fun sha256Key(file: VoiceModelFile) = "voice_sha256_${file.id}"

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"
    }
}
