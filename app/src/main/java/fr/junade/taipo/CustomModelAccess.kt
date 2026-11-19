package fr.junade.taipo

/**
 * Modèle IA personnalisé ([fr.junade.taipo.model.AiModel.CUSTOM]) : un fichier `.litertlm` fourni par l'utilisateur, avec ses propres
 * prompts système. Réservé au pack premium (verrouillé aussi en debug depuis le 19/11/2026 : pour l'essayer, forcer le pack actif avec
 * le bouton « Debug » de l'accueil, [PremiumPass.DebugOverride]). Sans le pack, le fichier et les prompts restent enregistrés mais ne
 * servent pas.
 */
object CustomModelAccess {

    /**
     * Version de LiteRT-LM intégrée à l'application, lue dans `gradle/libs.versions.toml` (clé `litertlm`) à la compilation : jamais
     * écrite à la main, elle suit les montées de version. Le modèle fourni doit pouvoir être chargé par cette version.
     */
    val LITERT_LM_VERSION: String get() = BuildConfig.LITERT_LM_VERSION

    /** Logique pure (testée en JVM) : pack premium actif, seulement. */
    fun isAllowed(premium: Boolean): Boolean = premium

    /** Le modèle personnalisé peut être choisi et utilisé maintenant. */
    val allowed: Boolean get() = isAllowed(PremiumPass.isActive)
}
