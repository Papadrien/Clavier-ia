package fr.papadrien.clavier.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Persiste, pour chaque [AiModel], le fichier local choisi par l'utilisateur
 * (URI SAF, avec prise de permission persistante) ainsi que le modèle
 * actuellement actif.
 *
 * Pas de téléchargement dans ce prototype : uniquement des fichiers fournis
 * manuellement par l'utilisateur via le sélecteur de fichiers du système.
 */
class ModelPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = appContext.contentResolver

    fun activeModel(): AiModel? = AiModel.byId(prefs.getString(KEY_ACTIVE_MODEL, null))

    fun setActiveModel(model: AiModel) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, model.id).apply()
    }

    fun savedUriFor(model: AiModel): Uri? =
        prefs.getString(uriKey(model), null)?.let { Uri.parse(it) }

    fun savedFileNameFor(model: AiModel): String? = prefs.getString(fileNameKey(model), null)

    fun savedFileSizeFor(model: AiModel): Long = prefs.getLong(fileSizeKey(model), -1L)

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
            .apply()
    }

    /** Oublie le fichier associé à [model] (ex. si l'utilisateur veut recommencer). */
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
            .apply()
    }

    private fun uriKey(model: AiModel) = "uri_${model.id}"
    private fun fileNameKey(model: AiModel) = "name_${model.id}"
    private fun fileSizeKey(model: AiModel) = "size_${model.id}"

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"
        private const val KEY_ACTIVE_MODEL = "active_model"
    }
}
