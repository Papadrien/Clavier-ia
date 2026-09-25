package fr.papadrien.clavier.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Persiste, pour chaque [VoiceModelFile], le fichier local choisi par
 * l'utilisateur (URI SAF, avec prise de permission persistante).
 *
 * Même logique que [ModelPreferences] pour les modèles de texte, appliquée
 * ici aux 4 fichiers du modèle vocal.
 */
class VoiceModelPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val resolver: ContentResolver = appContext.contentResolver

    fun savedUriFor(file: VoiceModelFile): Uri? =
        prefs.getString(uriKey(file), null)?.let { Uri.parse(it) }

    fun savedFileNameFor(file: VoiceModelFile): String? = prefs.getString(fileNameKey(file), null)

    fun isComplete(): Boolean = VoiceModelFile.all().all { savedUriFor(it) != null }

    fun assignUri(file: VoiceModelFile, uri: Uri, fileName: String?) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Le fournisseur de documents ne supporte pas la permission persistante.
        }
        prefs.edit()
            .putString(uriKey(file), uri.toString())
            .putString(fileNameKey(file), fileName)
            .apply()
    }

    private fun uriKey(file: VoiceModelFile) = "voice_uri_${file.id}"
    private fun fileNameKey(file: VoiceModelFile) = "voice_name_${file.id}"

    companion object {
        private const val PREFS_NAME = "ai_model_prefs"
    }
}
