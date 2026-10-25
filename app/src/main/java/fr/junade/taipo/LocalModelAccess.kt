package fr.junade.taipo

/**
 * Story 8.12 : le chargement d'un modèle à la main (« Modèle IA local ») est réservé au build debug. Logique pure,
 * testée en JVM ; l'appelant passe `BuildConfig.DEBUG` (la variante benchmark n'est pas debug).
 */
object LocalModelAccess {

    /** Vrai si l'accueil doit montrer la ligne « Modèle IA local ». */
    fun isVisible(debugBuild: Boolean): Boolean = debugBuild

    /** Vrai si un modèle installé doit s'afficher « Fichier local » (chargé à la main, hors catalogue vérifié). */
    fun showsAsLocalFile(debugBuild: Boolean, downloaded: Boolean): Boolean = debugBuild && !downloaded
}
