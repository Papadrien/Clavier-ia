package fr.junade.taipo.model.download

import fr.junade.taipo.model.VoiceModelFile

/**
 * Règles du téléchargement du modèle vocal (story 8.15), sans dépendance Android : testées en JVM. Le modèle vocal
 * est un ensemble de 4 fichiers ([VoiceModelFile]) téléchargés l'un après l'autre.
 */
object VoiceModelDownload {

    /**
     * Le modèle vocal est téléchargeable si chacun de ses fichiers a une URL HTTPS et, en release, une empreinte
     * SHA-256 de référence (story 8.8 : aucun téléchargement sans empreinte). En debug ([allowUnverified]), les
     * empreintes peuvent manquer : c'est ce qui permet de les relever dans logcat avant de les figer.
     */
    fun isDownloadable(allowUnverified: Boolean, files: List<VoiceModelFile> = VoiceModelFile.all()): Boolean =
        files.all { it.downloadUrl.startsWith("https://") && (it.sha256 != null || allowUnverified) }

    /**
     * Un fichier est à jour s'il a été téléchargé (et non fourni à la main), s'il est bien présent à la taille
     * enregistrée, et si son empreinte enregistrée est celle du catalogue. Sinon il est (re)téléchargé.
     */
    fun needsDownload(downloaded: Boolean, present: Boolean, updateAvailable: Boolean): Boolean =
        !(downloaded && present && !updateAvailable)

    /** Taille indicative de ce qu'il reste à télécharger : somme des [VoiceModelFile.approxBytes] des fichiers concernés. */
    fun missingBytes(needed: List<VoiceModelFile>): Long = needed.sumOf { it.approxBytes }

    /**
     * Progression globale 0..100 d'un téléchargement en plusieurs fichiers : octets déjà terminés + octets du fichier en
     * cours, rapportés au total connu (fichiers terminés + fichier en cours + tailles indicatives de ceux qui restent).
     * Une borne haute évite de dépasser 100 si une taille indicative est sous-estimée.
     */
    fun overallPercent(finishedBytes: Long, currentBytes: Long, currentTotal: Long, remainingApproxBytes: Long): Int {
        val total = finishedBytes + currentTotal + remainingApproxBytes
        if (total <= 0) return 0
        return ((finishedBytes + currentBytes) * 100 / total).coerceIn(0, 100).toInt()
    }
}
