package fr.junade.taipo.model

/**
 * Dossier du serveur d'Adrien qui héberge les 4 fichiers du modèle vocal (story 8.15). Même domaine que les modèles
 * Gemma 3 (Worker Cloudflare devant R2). **Versionné et immuable** (story 8.8) : ne jamais remplacer un fichier déjà
 * publié sous `v1/` ; un changement de fichier = nouveau dossier (`v2/`), nouvelle empreinte, nouvelle release.
 */
private const val VOICE_MODEL_BASE_URL = "https://taipo-worker.junade-models.workers.dev/voice/v1/"

/**
 * Les 4 fichiers du modèle de transcription vocale (Nemotron 3.5 ASR Streaming, export sherpa-onnx multilingue
 * fr/en — voir docs/decisions-techniques.md, PR sherpa-onnx #3732/#3734, release v1.13.5).
 *
 * Deux façons de les obtenir (comme pour les modèles de texte) : le téléchargement dans l'app (story 8.15, [downloadUrl]
 * et [sha256] ci-dessous) ou, en debug seulement, les fichiers fournis à la main via le sélecteur du téléphone (écran
 * « Modèle vocal local »). Dans les deux cas le fichier finit dans `filesDir/voice-model/<id>.onnx` (`tokens.txt` pour
 * les tokens), voir [VoiceModelFileResolver].
 *
 * sherpa-onnx attend un modèle "transducer" à 3 fichiers ONNX (encoder, decoder, joiner) + un fichier tokens.txt
 * (vérifié le 24/09/2026 sur
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt,
 * y compris un exemple de configuration Nemotron streaming avec exactement ces 4 fichiers).
 *
 * [sha256] : empreinte de référence, **null tant qu'elle n'est pas relevée** (comme pour [AiModel.sha256]) : en release,
 * aucun téléchargement sans empreinte ; en debug, le téléchargement est permis et l'empreinte réelle est écrite dans
 * logcat (tag `VoiceModelDownloadWorker`) pour être figée ici. [approxBytes] : taille indicative (**provisoire**, à
 * remplacer par les tailles réelles des fichiers hébergés) ; elle ne sert qu'au contrôle d'espace avant le
 * téléchargement et à la progression globale, jamais à valider le fichier (le `Content-Length` et l'empreinte le font).
 */
enum class VoiceModelFile(
    val id: String,
    val label: String,
    val filenameHint: String,
    val downloadUrl: String,
    val approxBytes: Long,
    val sha256: String? = null,
) {
    ENCODER(
        "encoder", "Encoder", "encoder.int8.onnx (ou encoder.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "encoder.int8.onnx",
        approxBytes = 600_000_000L,
    ),
    DECODER(
        "decoder", "Decoder", "decoder.int8.onnx (ou decoder.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "decoder.int8.onnx",
        approxBytes = 15_000_000L,
    ),
    JOINER(
        "joiner", "Joiner", "joiner.int8.onnx (ou joiner.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "joiner.int8.onnx",
        approxBytes = 10_000_000L,
    ),
    TOKENS(
        "tokens", "Tokens", "tokens.txt",
        downloadUrl = VOICE_MODEL_BASE_URL + "tokens.txt",
        approxBytes = 100_000L,
    );

    companion object {
        fun all(): List<VoiceModelFile> = values().toList()

        /** Taille indicative du modèle vocal complet (somme des fichiers), pour l'affichage. */
        fun totalApproxBytes(): Long = values().sumOf { it.approxBytes }

        /** Devine le rôle d'un fichier à partir de son nom (aide au tri lors d'une sélection multiple). */
        fun guessFromFileName(name: String?): VoiceModelFile? {
            if (name == null) return null
            val lower = name.lowercase()
            return when {
                lower.contains("encoder") -> ENCODER
                lower.contains("decoder") -> DECODER
                lower.contains("joiner") -> JOINER
                lower == "tokens.txt" || lower.contains("tokens") -> TOKENS
                else -> null
            }
        }
    }
}
