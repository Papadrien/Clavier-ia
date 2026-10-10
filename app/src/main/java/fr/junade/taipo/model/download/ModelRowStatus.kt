package fr.junade.taipo.model.download

/**
 * Statut d'une ligne de l'écran « Modèle IA » (story 8.1). « Corrompu » et « incompatible » n'existent pas à
 * part : ils sont regroupés dans [RETRY].
 */
enum class ModelRowStatus {
    /** Pas téléchargeable dans l'app pour l'instant (ex. Gemma 3). */
    UNAVAILABLE,

    /** Téléchargeable, pas installé. */
    DOWNLOAD,

    /** Téléchargement en cours (pourcentage dans l'état). */
    DOWNLOADING,

    /** Téléchargement terminé, contrôle d'intégrité en cours. */
    VERIFYING,

    /** Le dernier téléchargement a échoué. */
    RETRY,

    /** Fichier présent et utilisable. */
    INSTALLED,

    /** Fichier présent et utilisable, mais l'empreinte du catalogue a changé : mise à jour proposée (story 8.14). */
    UPDATE_AVAILABLE,
    ;

    companion object {
        fun of(
            installed: Boolean,
            downloadable: Boolean,
            state: DownloadState,
            updateAvailable: Boolean = false,
        ): ModelRowStatus = when {
            state is DownloadState.Downloading -> DOWNLOADING
            state is DownloadState.Verifying -> VERIFYING
            installed && updateAvailable -> UPDATE_AVAILABLE
            installed -> INSTALLED
            state is DownloadState.Failed -> RETRY
            downloadable -> DOWNLOAD
            else -> UNAVAILABLE
        }
    }
}
