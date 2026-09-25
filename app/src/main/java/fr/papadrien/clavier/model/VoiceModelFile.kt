package fr.papadrien.clavier.model

/**
 * Les 4 fichiers attendus pour charger le modèle de transcription vocale
 * (Nemotron 3.5 ASR Streaming, export sherpa-onnx multilingue fr/en — voir
 * décision ai-keyboard.md du 23/09/2026, PR sherpa-onnx #3732/#3734,
 * release v1.13.5). Pas de téléchargement dans ce prototype : l'utilisateur
 * fournit les 4 fichiers lui-même via le sélecteur de fichiers du téléphone.
 *
 * sherpa-onnx attend un modèle "transducer" à 3 fichiers ONNX (encoder,
 * decoder, joiner) + un fichier tokens.txt (vérifié le 24/09/2026 sur
 * https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OnlineRecognizer.kt,
 * y compris un exemple de configuration Nemotron streaming avec exactement
 * ces 4 fichiers).
 */
enum class VoiceModelFile(val id: String, val label: String, val filenameHint: String) {
    ENCODER("encoder", "Encoder", "encoder.int8.onnx (ou encoder.onnx)"),
    DECODER("decoder", "Decoder", "decoder.int8.onnx (ou decoder.onnx)"),
    JOINER("joiner", "Joiner", "joiner.int8.onnx (ou joiner.onnx)"),
    TOKENS("tokens", "Tokens", "tokens.txt");

    companion object {
        fun all(): List<VoiceModelFile> = values().toList()

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
