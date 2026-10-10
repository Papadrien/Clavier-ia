package fr.junade.taipo.model.download

import fr.junade.taipo.model.AiModel

/** Type de réseau actif au moment de lancer (ou de poursuivre) un téléchargement. */
enum class NetworkKind {
    /** Aucune connexion. */
    NONE,

    /** Réseau non mesuré (Wi-Fi, Ethernet). */
    UNMETERED,

    /** Réseau mesuré (données mobiles, point d'accès partagé). */
    METERED,
}

/** Décision prise avant de lancer un téléchargement (stories 8.3, 8.4 et 8.8). */
sealed interface DownloadDecision {
    /** Le téléchargement peut démarrer tout de suite. */
    data object Start : DownloadDecision

    /** Réseau mesuré : demander confirmation (avec la taille) avant de démarrer. */
    data object ConfirmMobileData : DownloadDecision

    /** Aucune connexion : message clair, rien n'est lancé. */
    data object NoConnection : DownloadDecision

    /** Pas assez de place : [requiredBytes] nécessaires (marge comprise), [freeBytes] libres. */
    data class InsufficientSpace(val requiredBytes: Long, val freeBytes: Long) : DownloadDecision

    /** Le modèle n'est pas téléchargeable (pas d'URL, ou empreinte absente en release). */
    data object NotAvailable : DownloadDecision
}

/**
 * Règles de décision du téléchargement, sans dépendance Android : testées en JVM.
 *
 * Ordre des contrôles : modèle téléchargeable, connexion, espace disque, puis confirmation données mobiles
 * (inutile de demander une confirmation pour un téléchargement qui ne rentrerait de toute façon pas).
 */
object DownloadPolicy {

    /** Marge de sécurité ajoutée à la taille du modèle pour le contrôle d'espace libre (200 Mo). */
    const val SAFETY_MARGIN_BYTES = 200_000_000L

    /**
     * Un modèle est téléchargeable s'il a une URL HTTPS et, en release, une empreinte SHA-256 de référence
     * (story 8.8 : aucun téléchargement sans empreinte). En debug ([allowUnverified]), l'empreinte peut manquer :
     * c'est ce qui permet de relever l'empreinte réelle (journal et écran) avant de la figer dans [AiModel.sha256].
     */
    fun isDownloadable(model: AiModel, allowUnverified: Boolean): Boolean {
        val url = model.downloadUrl ?: return false
        if (!url.startsWith("https://")) return false
        return model.sha256 != null || allowUnverified
    }

    fun requiredBytes(sizeBytes: Long): Long = sizeBytes + SAFETY_MARGIN_BYTES

    fun hasEnoughSpace(freeBytes: Long, sizeBytes: Long): Boolean = freeBytes >= requiredBytes(sizeBytes)

    fun decide(
        model: AiModel,
        network: NetworkKind,
        freeBytes: Long,
        mobileDataAccepted: Boolean,
        allowUnverified: Boolean,
    ): DownloadDecision = decideFor(
        downloadable = isDownloadable(model, allowUnverified),
        sizeBytes = model.approxDownloadBytes ?: model.approxSizeBytesMax,
        network = network,
        freeBytes = freeBytes,
        mobileDataAccepted = mobileDataAccepted,
    )

    /**
     * Même décision pour le modèle vocal (story 8.15) : [missingBytes] = taille indicative des fichiers qu'il reste à
     * télécharger (voir [VoiceModelDownload.missingBytes]).
     */
    fun decideVoice(
        missingBytes: Long,
        network: NetworkKind,
        freeBytes: Long,
        mobileDataAccepted: Boolean,
        allowUnverified: Boolean,
    ): DownloadDecision = decideFor(
        downloadable = VoiceModelDownload.isDownloadable(allowUnverified),
        sizeBytes = missingBytes,
        network = network,
        freeBytes = freeBytes,
        mobileDataAccepted = mobileDataAccepted,
    )

    private fun decideFor(
        downloadable: Boolean,
        sizeBytes: Long,
        network: NetworkKind,
        freeBytes: Long,
        mobileDataAccepted: Boolean,
    ): DownloadDecision {
        if (!downloadable) return DownloadDecision.NotAvailable
        if (network == NetworkKind.NONE) return DownloadDecision.NoConnection
        if (!hasEnoughSpace(freeBytes, sizeBytes)) {
            return DownloadDecision.InsufficientSpace(requiredBytes(sizeBytes), freeBytes)
        }
        if (network == NetworkKind.METERED && !mobileDataAccepted) return DownloadDecision.ConfirmMobileData
        return DownloadDecision.Start
    }

    /**
     * Pendant le téléchargement : faut-il s'arrêter parce que le réseau a changé ? Vrai si la connexion est
     * perdue, ou si on est passé sur un réseau mesuré sans accord (story 8.3).
     */
    fun shouldAbortForNetwork(network: NetworkKind, mobileDataAccepted: Boolean): Boolean = when (network) {
        NetworkKind.NONE -> true
        NetworkKind.METERED -> !mobileDataAccepted
        NetworkKind.UNMETERED -> false
    }
}
