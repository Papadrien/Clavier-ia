package fr.junade.taipo

/**
 * États du bouton Corriger. [UNDO] : une correction IA vient d'être appliquée, le bouton devient « Annuler » (rétablit le
 * texte d'avant) jusqu'à la prochaine saisie ou action de la barre du haut.
 */
enum class CorrectionBarState { HIDDEN, IDLE, LOADING, CORRECTING, CANCELLING, UNDO }

/** États du bouton Vocal. */
enum class VoiceBarState {
    IDLE,

    /** Appui reçu, modèle en cours de chargement : le micro n'écoute pas encore. */
    LOADING,

    /** Le micro capte réellement. */
    RECORDING,
    TRANSCRIBING,
}

/** États du bouton du spike 14.1 (transcription par modèle multimodal, build debug seulement). */
enum class AudioSpikeBarState { IDLE, RECORDING, PROCESSING }
