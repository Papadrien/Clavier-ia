package fr.junade.taipo.model

/**
 * Licence sous laquelle un modèle est distribué (story 8.11).
 *
 * - [GEMMA_TERMS] : Gemma 3 (Ultra-léger 270M, Léger 1B), sous les « Gemma Terms of Use » de Google et sa
 *   « Prohibited Use Policy ». L'utilisateur doit en être informé avant le téléchargement ([requiresAcceptance]).
 * - [APACHE_2] : Gemma 4 (Équilibré E2B, Performant E4B), sous licence Apache 2.0 : texte et mentions conservés,
 *   consultables dans l'écran « Licences » (aucune acceptation préalable n'est exigée par cette licence).
 *
 * URLs et phrase de notice vérifiées sur ai.google.dev le 10/10/2026 (pages « Gemma Terms of Use », « Gemma 4 license »).
 * Ce n'est pas un avis juridique : relire les textes officiels avant la soumission Play (voir docs).
 */
enum class ModelLicense(
    /** Page des conditions ou du texte de licence. */
    val termsUrl: String,
    /** Politique d'usage interdit, ou null si la licence n'en a pas. */
    val policyUrl: String?,
    /** Vrai si la mention doit être affichée et acceptée avant le premier téléchargement. */
    val requiresAcceptance: Boolean,
) {
    GEMMA_TERMS(
        termsUrl = "https://ai.google.dev/gemma/terms",
        policyUrl = "https://ai.google.dev/gemma/prohibited_use_policy",
        requiresAcceptance = true,
    ),
    APACHE_2(
        termsUrl = "https://ai.google.dev/gemma/apache_2",
        policyUrl = null,
        requiresAcceptance = false,
    );

    /** Vrai si l'écran de mention doit s'afficher avant ce téléchargement, selon que la licence a déjà été acceptée. */
    fun mustAskBeforeDownload(alreadyAccepted: Boolean): Boolean = requiresAcceptance && !alreadyAccepted
}

/** Licence applicable au modèle (Gemma 3 → conditions Gemma, Gemma 4 → Apache 2.0). */
val AiModel.license: ModelLicense
    get() = when (this) {
        AiModel.ULTRA_LEGER, AiModel.LEGER -> ModelLicense.GEMMA_TERMS
        AiModel.EQUILIBRE, AiModel.PERFORMANT -> ModelLicense.APACHE_2
    }
