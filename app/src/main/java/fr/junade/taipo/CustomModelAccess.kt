package fr.junade.taipo

/**
 * Modèle IA personnalisé ([fr.junade.taipo.model.AiModel.CUSTOM]) : un fichier `.litertlm` fourni par l'utilisateur, avec ses propres
 * prompts système. Réservé au pack premium ; toujours ouvert dans un build debug, pour essayer un nouveau modèle avant de l'ajouter au
 * catalogue d'une version publiée. Sans le pack, le fichier et les prompts restent enregistrés mais ne servent pas.
 */
object CustomModelAccess {

    /**
     * Version de LiteRT-LM intégrée à l'application (`litertlm` dans `gradle/libs.versions.toml`, vérifié par CustomModelAccessTest) :
     * le modèle fourni doit pouvoir être chargé par cette version.
     */
    const val LITERT_LM_VERSION = "0.17.1"

    /** Logique pure (testée en JVM) : pack premium actif, ou build debug. */
    fun isAllowed(premium: Boolean, debugBuild: Boolean): Boolean = premium || debugBuild

    /** Le modèle personnalisé peut être choisi et utilisé maintenant. */
    val allowed: Boolean get() = isAllowed(PremiumPass.isActive, BuildConfig.DEBUG)
}
