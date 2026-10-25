package fr.junade.taipo.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Persiste, pour chaque [AiModel], le fichier local choisi par l'utilisateur
 * (URI SAF, avec prise de permission persistante) ou le modèle téléchargé dans l'app
 * (épopée 8), ainsi que le modèle actuellement actif.
 *
 * Un modèle a une seule origine à la fois : fichier fourni (URI SAF) ou téléchargé. Le dernier installé
 * remplace l'autre (story 8.12).
 */
class ModelPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = appContext.contentResolver

    fun activeModel(): AiModel? = AiModel.byId(prefs.getString(KEY_ACTIVE_MODEL, null))

    fun setActiveModel(model: AiModel) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, model.id).apply()
    }

    /** Oublie le modèle actif (story 8.13 : le dernier modèle installé vient d'être supprimé, 8.5 s'applique alors). */
    fun clearActiveModel() {
        prefs.edit().remove(KEY_ACTIVE_MODEL).apply()
    }

    /**
     * Story 8.14 : vrai si le modèle installé est un téléchargement dont l'empreinte diffère de celle du catalogue.
     * Le modèle reste utilisable : rien n'est modifié tant que l'utilisateur ne lance pas la mise à jour.
     */
    fun updateAvailable(model: AiModel): Boolean =
        isInstalled(model) && ModelUpdate.isAvailable(isDownloaded(model), savedSha256For(model), model.sha256)

    /** Vrai si le modèle a été téléchargé dans l'app (le fichier est alors directement dans `models/`). */
    fun isDownloaded(model: AiModel): Boolean = prefs.getLong(downloadedSizeKey(model), -1L) > 0

    /** Taille en octets du fichier téléchargé, contrôlée à chaque chargement du moteur (story 8.9), ou -1. */
    fun downloadedSizeFor(model: AiModel): Long = prefs.getLong(downloadedSizeKey(model), -1L)

    /**
     * Enregistre un modèle téléchargé et vérifié. Remplace un éventuel fichier fourni à la main : la référence
     * SAF est oubliée (le fichier de `models/` est désormais celui du téléchargement).
     */
    fun assignDownloaded(model: AiModel, sizeBytes: Long, sha256: String) {
        clear(model)
        prefs.edit()
            .putLong(downloadedSizeKey(model), sizeBytes)
            .putString(sha256Key(model), sha256)
            .apply()
    }

    /**
     * Un modèle est installé si son téléchargement est enregistré ET que le fichier est bien là, à la taille
     * enregistrée (après une restauration sur un autre appareil, les préférences peuvent subsister sans le
     * fichier), ou si un fichier a été fourni à la main.
     */
    fun isInstalled(model: AiModel): Boolean {
        if (isDownloaded(model)) {
            val file = ModelFileResolver.localFileFor(appContext, model)
            return file.isFile && file.length() == downloadedSizeFor(model)
        }
        return savedUriFor(model) != null
    }

    fun savedUriFor(model: AiModel): Uri? =
        prefs.getString(uriKey(model), null)?.let { Uri.parse(it) }

    fun savedFileNameFor(model: AiModel): String? = prefs.getString(fileNameKey(model), null)

    fun savedFileSizeFor(model: AiModel): Long = prefs.getLong(fileSizeKey(model), -1L)

    /** Empreinte SHA-256 (hexadécimal) du fichier choisi, ou null (pas encore calculée, ou fichier changé). */
    fun savedSha256For(model: AiModel): String? = prefs.getString(sha256Key(model), null)

    /**
     * Enregistre l'empreinte calculée pour [uri], seulement si c'est toujours le fichier associé à
     * [model] (l'utilisateur a pu en choisir un autre pendant le calcul). Retourne vrai si enregistrée.
     */
    fun assignSha256(model: AiModel, uri: Uri, sha256: String): Boolean {
        if (savedUriFor(model) != uri) return false
        prefs.edit().putString(sha256Key(model), sha256).apply()
        return true
    }

    /**
     * Associe [uri] au [model] : prend une permission de lecture persistante
     * (indispensable pour pouvoir relire le fichier après redémarrage de
     * l'app - une URI SAF classique n'est valide que le temps de la session)
     * et enregistre nom/taille pour affichage.
     */
    fun assignUri(model: AiModel, uri: Uri, fileName: String?, fileSize: Long) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Le fournisseur de documents ne supporte pas la permission persistante :
            // le fichier reste utilisable pour la session en cours, mais devra être
            // re-sélectionné après redémarrage de l'app.
        }
        prefs.edit()
            .putString(uriKey(model), uri.toString())
            .putString(fileNameKey(model), fileName)
            .putLong(fileSizeKey(model), fileSize)
            .remove(sha256Key(model)) // l'empreinte de l'ancien fichier ne vaut plus
            .remove(downloadedSizeKey(model)) // le fichier fourni remplace un éventuel téléchargement
            .apply()
    }

    /**
     * Oublie le fichier associé à [model] (ex. si l'utilisateur veut recommencer) : référence SAF,
     * préférences, mais aussi la copie interne et le cache XNNPACK. Le moteur doit être fermé avant
     * (voir [LlmEngineHost.releaseModel]). Retourne le nombre d'octets libérés.
     */
    fun clearAndDeleteCopy(model: AiModel): Long {
        clear(model)
        return ModelFileResolver.deleteLocalCopy(appContext, model)
    }

    /** Oublie seulement la référence au fichier (permission + préférences), sans toucher à la copie interne. */
    fun clear(model: AiModel) {
        savedUriFor(model)?.let { uri ->
            try {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // La permission n'existait déjà plus : rien à faire.
            }
        }
        prefs.edit()
            .remove(uriKey(model))
            .remove(fileNameKey(model))
            .remove(fileSizeKey(model))
            .remove(sha256Key(model))
            .remove(downloadedSizeKey(model))
            .apply()
    }

    private fun uriKey(model: AiModel) = "uri_${model.id}"
    private fun fileNameKey(model: AiModel) = "name_${model.id}"
    private fun fileSizeKey(model: AiModel) = "size_${model.id}"
    private fun sha256Key(model: AiModel) = "sha256_${model.id}"
    private fun downloadedSizeKey(model: AiModel) = "downloaded_size_${model.id}"

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
    }
}
