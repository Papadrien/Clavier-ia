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

/**
 * États du bouton Transcription IA (épopée 14) : enregistrement, traitement par le modèle, puis [CANCELLING] (arrêt natif
 * demandé, en attente de la fin du moteur : un nouvel appui est ignoré).
 */
enum class AiTranscriptionBarState { IDLE, RECORDING, PROCESSING, CANCELLING }
