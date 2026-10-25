package fr.junade.taipo.model

import android.content.Context

/**
 * Mémorise que l'utilisateur a pris connaissance d'une licence de modèle avant de télécharger (story 8.11). Une
 * seule acceptation par licence : les deux modèles Gemma 3 partagent les mêmes conditions.
 */
class ModelLicenseAcceptance(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAccepted(license: ModelLicense): Boolean = prefs.getBoolean(key(license), false)

    fun accept(license: ModelLicense) {
        prefs.edit().putBoolean(key(license), true).apply()
    }

    private fun key(license: ModelLicense) = "accepted_${license.name}"

    private companion object {
        const val PREFS_NAME = "model_license_prefs"
    }
}
