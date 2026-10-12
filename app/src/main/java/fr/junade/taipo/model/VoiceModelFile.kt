package fr.junade.taipo.model

/**
 * Racine du bucket R2 d'Adrien qui héberge les 4 fichiers du modèle vocal (story 8.15), à plat comme les modèles Gemma 3
 * (même domaine : Worker Cloudflare devant R2). Remplacer un de ces fichiers sur le bucket change son empreinte : il faut
 * alors figer la nouvelle `sha256` ici et publier une release (story 8.8), sinon la vérification refuse le téléchargement.
 */
private const val VOICE_MODEL_BASE_URL = "https://taipo-worker.junade-models.workers.dev/"

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
 * [sha256] : empreinte de référence, figée le 11/10/2026 pour les 4 fichiers hébergés (comme [AiModel.sha256]) ; null
 * l'aurait rendue indisponible en release (en debug, une empreinte manquante est écrite dans logcat, tag
 * `VoiceModelDownloadWorker`). [approxBytes] : taille réelle en octets des fichiers hébergés (relevée le 11/10/2026) ; elle
 * ne sert qu'au contrôle d'espace avant le téléchargement et à la progression globale, jamais à valider le fichier (le
 * `Content-Length` et l'empreinte le font).
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
        "encoder", "Encoder", "encoder.onnx (ou encoder.int8.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "encoder.int8.onnx",
        approxBytes = 657_601_403L,
        sha256 = "012e9321373af99021415e0b0eb3ec827b4be3153be6f30d9b448fe65e896e68",
    ),
    DECODER(
        "decoder", "Decoder", "decoder.onnx (ou decoder.int8.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "decoder.int8.onnx",
        approxBytes = 14_978_075L,
        sha256 = "19f9c98fc6d0a2c33a65a43b36fdb2e914c26c0aa9764be3aebc502a1e982fb0",
    ),
    JOINER(
        "joiner", "Joiner", "joiner.onnx (ou joiner.int8.onnx)",
        downloadUrl = VOICE_MODEL_BASE_URL + "joiner.int8.onnx",
        approxBytes = 9_504_438L,
        sha256 = "4101c7c679a0bc30483794b27a059e34e79232aa2068d78d51231a22c8b0d7ce",
    ),
    TOKENS(
        "tokens", "Tokens", "tokens.txt",
        downloadUrl = VOICE_MODEL_BASE_URL + "tokens.txt",
        approxBytes = 131_440L,
        sha256 = "729cc103155bafa785f9cd45746cd41cabe97eab7182fc04d594129587958f8a",
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
