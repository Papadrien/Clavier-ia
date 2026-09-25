package fr.papadrien.clavier.model

/**
 * Les 4 modèles IA prévus pour le clavier (Ultra-léger / Léger / Équilibré / Performant).
 *
 * Pour ce prototype, aucun téléchargement n'est implémenté : l'utilisateur doit
 * récupérer lui-même le fichier .litertlm correspondant (typiquement sur Hugging
 * Face, dépôt indiqué par [huggingFaceRepo]) et le fournir via le sélecteur de
 * fichiers du téléphone.
 *
 * Tailles et dépôts vérifiés le 24/09/2026 sur Hugging Face / ai.google.dev :
 * les valeurs exactes dépendent de la variante de quantification choisie par
 * l'utilisateur (plusieurs fichiers existent par modèle), donc [approxSizeBytesMin]/
 * [approxSizeBytesMax] sont des bornes indicatives, pas une valeur exacte attendue.
 */
enum class AiModel(
    val id: String,
    val displayName: String,
    val technicalName: String,
    val huggingFaceRepo: String,
    val approxSizeBytesMin: Long,
    val approxSizeBytesMax: Long,
    val fileHint: String,
) {
    ULTRA_LEGER(
        id = "ultra_leger",
        displayName = "Ultra-léger",
        technicalName = "Gemma 3 270M (instruct)",
        huggingFaceRepo = "litert-community/gemma-3-270m-it",
        approxSizeBytesMin = 250_000_000L,
        approxSizeBytesMax = 350_000_000L,
        fileHint = "Fichier attendu : gemma3-270m-it-q8.litertlm (~300 Mo).",
    ),
    LEGER(
        id = "leger",
        displayName = "Léger",
        technicalName = "Gemma 3 1B (instruct)",
        huggingFaceRepo = "litert-community/Gemma3-1B-IT",
        approxSizeBytesMin = 500_000_000L,
        approxSizeBytesMax = 1_100_000_000L,
        fileHint = "Plusieurs variantes existent (int4 ≈ 580–660 Mo, q8 ≈ 1 Go). " +
            "Prendre une variante générique (_q8_ ou _q4_), pas une variante liée à un " +
            "SoC précis (_qualcomm_, _mediatek_…).",
    ),
    EQUILIBRE(
        id = "equilibre",
        displayName = "Équilibré",
        technicalName = "Gemma 4 E2B (instruct)",
        huggingFaceRepo = "litert-community/gemma-4-E2B-it-litert-lm",
        approxSizeBytesMin = 2_300_000_000L,
        approxSizeBytesMax = 2_800_000_000L,
        fileHint = "Fichier attendu : gemma-4-E2B-it.litertlm (~2,6 Go). Éviter les " +
            "variantes *-web.litertlm (plus petites, réservées au web) et celles liées " +
            "à un SoC précis.",
    ),
    PERFORMANT(
        id = "performant",
        displayName = "Performant",
        technicalName = "Gemma 4 E4B (instruct)",
        huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        approxSizeBytesMin = 3_300_000_000L,
        approxSizeBytesMax = 3_900_000_000L,
        fileHint = "Fichier attendu : gemma-4-E4B-it.litertlm (~3,65 Go).",
    );

    companion object {
        /** Extension de fichier attendue pour tous les modèles (format LiteRT-LM). */
        const val EXPECTED_EXTENSION = ".litertlm"

        fun byId(id: String?): AiModel? = values().firstOrNull { it.id == id }

        /** Liste ordonnée des 4 modèles, dans l'ordre Ultra-léger → Performant. */
        fun entriesOrdered(): List<AiModel> = values().toList()
    }
}

/**
 * Message d'avertissement si la taille du fichier fourni par l'utilisateur
 * s'écarte franchement de ce qui est attendu pour ce modèle, ou `null` si la
 * taille semble plausible.
 *
 * Vérification indicative uniquement : ce prototype n'embarque pas de checksum
 * de référence (contrairement à ce qui est prévu pour la V1), donc rien ne
 * garantit que le fichier est le bon modèle - seulement que sa taille est
 * cohérente avec ce qui est attendu.
 */
fun AiModel.sizeWarning(actualSizeBytes: Long): String? {
    if (actualSizeBytes <= 0) return "Impossible de lire la taille du fichier sélectionné."
    val minMb = approxSizeBytesMin / 1_000_000.0
    val maxMb = approxSizeBytesMax / 1_000_000.0
    val actualMb = actualSizeBytes / 1_000_000.0
    return when {
        actualSizeBytes < approxSizeBytesMin ->
            "Le fichier sélectionné (%.0f Mo) semble plus petit qu'attendu pour ce modèle (%.0f–%.0f Mo). Vérifiez que c'est le bon fichier."
                .format(actualMb, minMb, maxMb)

        actualSizeBytes > approxSizeBytesMax ->
            "Le fichier sélectionné (%.0f Mo) semble plus gros qu'attendu pour ce modèle (%.0f–%.0f Mo). Vérifiez que c'est le bon fichier."
                .format(actualMb, minMb, maxMb)

        else -> null
    }
}
