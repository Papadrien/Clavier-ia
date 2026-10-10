package fr.junade.taipo.model

/**
 * Story 8.14 : un modèle téléchargé est « à mettre à jour » quand l'empreinte du fichier installé n'est plus celle du
 * catalogue de l'app (une release a changé le fichier du modèle). Logique pure, testée en JVM.
 *
 * Ne concerne que les modèles téléchargés : un fichier chargé à la main (debug) n'a pas de référence de catalogue.
 * Sans empreinte installée connue ou sans empreinte au catalogue, on ne signale rien (pas de fausse alerte).
 */
object ModelUpdate {

    fun isAvailable(downloaded: Boolean, installedSha256: String?, catalogSha256: String?): Boolean {
        if (!downloaded || installedSha256 == null || catalogSha256 == null) return false
        return !sameSha256(installedSha256, catalogSha256)
    }
}
