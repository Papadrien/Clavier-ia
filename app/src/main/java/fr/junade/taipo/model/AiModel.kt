package fr.junade.taipo.model

/**
 * Les 4 modèles IA prévus pour le clavier (Ultra-léger / Léger / Équilibré / Performant).
 *
 * Deux façons d'obtenir un modèle : le téléchargement dans l'app (épopée 8, seulement les modèles qui ont une
 * [downloadUrl] : Gemma 3 et Gemma 4) ou le fichier .litertlm fourni à la main via le sélecteur de fichiers
 * du téléphone (écran « Modèle IA local », dépôt indiqué par [huggingFaceRepo]). Dans les deux cas le fichier
 * est stocké dans `filesDir/models/<id>.litertlm`.
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
    /**
     * Empreinte SHA-256 de référence du fichier attendu, ou null tant qu'elle n'est pas relevée (lot 3.4,
     * S3). À copier depuis la page du fichier sur Hugging Face (« Copy SHA256 ») : jamais à deviner.
     * Plusieurs variantes existent pour certains modèles (voir [fileHint]) : ne renseigner que si une
     * seule variante est supportée, sinon la comparaison signalerait à tort les autres.
     */
    val sha256: String? = null,
    /**
     * Épopée 8 (8.8) : URL de téléchargement HTTPS, ou null si le modèle n'est pas (encore) téléchargeable
     * dans l'app (aucun pour l'instant : les 4 en ont une). Gemma 4 : source provisoire Hugging Face (`litert-community`) ; Gemma 3 :
     * serveur d'Adrien (Worker Cloudflare devant R2). Avant la publication, tout passe sur son serveur (URL versionnée et immuable).
     */
    val downloadUrl: String? = null,
    /** Taille de téléchargement habituelle, pour l'affichage et le contrôle d'espace avant d'avoir le `Content-Length`. */
    val approxDownloadBytes: Long? = null,
) {
    ULTRA_LEGER(
        id = "ultra_leger",
        displayName = "Ultra-léger",
        technicalName = "Gemma 3 270M (instruct)",
        huggingFaceRepo = "litert-community/gemma-3-270m-it",
        approxSizeBytesMin = 250_000_000L,
        approxSizeBytesMax = 350_000_000L,
        fileHint = "Fichier attendu : gemma3-270m-it-q8.litertlm (~300 Mo).",
        sha256 = "757e9119fa5bd667a2774fb470ac4afcd3190a21c677f8e69a5d6bc908abdd63",
        downloadUrl = "https://taipo-worker.junade-models.workers.dev/gemma3-270m-it-q8.litertlm",
        approxDownloadBytes = 300_000_000L,
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
            "SoC précis (_qualcomm_, _mediatek_…). Fichier hébergé : gemma3-1b-it-int4.litertlm.",
        sha256 = "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be",
        downloadUrl = "https://taipo-worker.junade-models.workers.dev/gemma3-1b-it-int4.litertlm",
        approxDownloadBytes = 600_000_000L,
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
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        approxDownloadBytes = 2_600_000_000L,
    ),
    PERFORMANT(
        id = "performant",
        displayName = "Performant",
        technicalName = "Gemma 4 E4B (instruct)",
        huggingFaceRepo = "litert-community/gemma-4-E4B-it-litert-lm",
        approxSizeBytesMin = 3_300_000_000L,
        approxSizeBytesMax = 3_900_000_000L,
        fileHint = "Fichier attendu : gemma-4-E4B-it.litertlm (~3,65 Go).",
        sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        approxDownloadBytes = 3_650_000_000L,
    );

    /** Vrai si l'app sait télécharger ce modèle (URL connue). */
    val hasDownloadUrl: Boolean get() = downloadUrl != null

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
 * Vérification indicative uniquement : sans empreinte de référence ([AiModel.sha256], null
 * pour l'instant), rien ne garantit que le fichier est le bon modèle - seulement que sa taille
 * est cohérente avec ce qui est attendu. L'empreinte réelle est calculée à la sélection
 * ([sha256Hex]) et affichée pour comparaison manuelle.
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
