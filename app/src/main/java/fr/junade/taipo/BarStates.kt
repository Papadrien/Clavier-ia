package fr.junade.taipo

/** États du bouton Corriger. */
enum class CorrectionBarState { HIDDEN, IDLE, LOADING, CORRECTING }

/** États du bouton Vocal. */
enum class VoiceBarState {
    IDLE,

    /** Appui reçu, modèle en cours de chargement : le micro n'écoute pas encore. */
    LOADING,

    /** Le micro capte réellement. */
    RECORDING,
    TRANSCRIBING,
}
