package fr.junade.taipo.ai

import fr.junade.taipo.model.AiModel

/**
 * Chemin de la Transcription IA selon le modèle (22/11/2026). Gemma 3 et Gemma 4 passent par la dictée (modèle vocal sherpa-onnx)
 * puis la reformulation du texte par le modèle ([AiTranscriptionEngine.dictate] puis [AiTranscriptionEngine.rewrite]) : l'audio
 * natif de Gemma 4 était bien trop lent (moteur rechargé avec l'audio, puis tout l'enregistrement traité d'un bloc). Seul le
 * modèle personnalisé réglé « accepte l'audio » sur sa page garde l'audio natif. Logique pure, testée en JVM.
 */
object TranscriptionRoute {

    /** Vrai : l'audio est confié au modèle ; faux : dictée puis reformulation. [customAcceptsAudio] : réglage du modèle personnalisé. */
    fun nativeAudio(model: AiModel, customAcceptsAudio: Boolean): Boolean = model == AiModel.CUSTOM && customAcceptsAudio
}
