package fr.junade.taipo.model.download

import java.io.File
import java.io.IOException

/**
 * Ce qu'on sait du fichier partiel `<fichier>.tmp` pour pouvoir le reprendre (point 1.7 de l'audit) : taille totale
 * attendue et validateur HTTP (ETag fort, sinon Last-Modified) à renvoyer en `If-Range`. Écrit dans
 * `<fichier>.tmp.resume` ; sans ce fichier, un `.tmp` n'est jamais repris (il peut venir d'une copie locale en debug).
 */
data class ResumeMarker(val totalBytes: Long, val validator: String?) {

    fun serialize(): String = buildString {
        append("total=").append(totalBytes).append('\n')
        if (validator != null) append("validator=").append(validator).append('\n')
    }

    companion object {
        /** Null si le texte n'a pas de taille totale exploitable. */
        fun parse(text: String): ResumeMarker? {
            var total = -1L
            var validator: String? = null
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("total=") -> total = line.removePrefix("total=").trim().toLongOrNull() ?: -1L
                    line.startsWith("validator=") -> validator = line.removePrefix("validator=").trim().ifEmpty { null }
                }
            }
            return if (total > 0) ResumeMarker(total, validator) else null
        }
    }
}

/** En-tête `Content-Range: bytes début-fin/total` d'une réponse HTTP 206. */
data class ContentRange(val start: Long, val end: Long, val total: Long) {

    companion object {
        private val PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        /** Null si l'en-tête est absent, mal formé, incohérent ou de taille totale inconnue (`*`). */
        fun parse(header: String?): ContentRange? {
            val match = PATTERN.matchEntire(header?.trim() ?: return null) ?: return null
            val (startText, endText, totalText) = match.destructured
            val start = startText.toLongOrNull() ?: return null
            val end = endText.toLongOrNull() ?: return null
            val total = totalText.toLongOrNull() ?: return null
            if (end < start || total <= end) return null
            return ContentRange(start, end, total)
        }
    }
}

/** Règles du fichier partiel, sans dépendance Android : testées en JVM. */
object PartialDownload {

    /** Un partiel non touché depuis plus de 7 jours est abandonné : plusieurs Go ne doivent pas rester oubliés. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** Validateur à renvoyer en `If-Range` : ETag fort de préférence (un ETag faible n'est pas permis), sinon Last-Modified. */
    fun validatorOf(etag: String?, lastModified: String?): String? = when {
        etag != null && etag.isNotBlank() && !etag.startsWith("W/") -> etag.trim()
        lastModified != null && lastModified.isNotBlank() -> lastModified.trim()
        else -> null
    }

    /** Supprime le partiel et son marqueur ; ne touche jamais au fichier de destination. */
    fun discard(destination: File) {
        ModelInstaller.temporaryFileFor(destination).delete()
        ModelInstaller.resumeMarkerFor(destination).delete()
    }

    /** Octets déjà conservés pour [destination] (0 sans partiel repris). Ne supprime rien. */
    fun currentOffset(destination: File): Long {
        val temp = ModelInstaller.temporaryFileFor(destination)
        return if (temp.isFile && ModelInstaller.resumeMarkerFor(destination).isFile) temp.length() else 0L
    }

    fun readMarker(destination: File): ResumeMarker? = try {
        ResumeMarker.parse(ModelInstaller.resumeMarkerFor(destination).readText())
    } catch (_: IOException) {
        null
    }

    /** Sans marqueur le téléchargement fonctionne, mais ne pourra pas être repris : l'échec est donc sans gravité. */
    fun writeMarker(destination: File, marker: ResumeMarker): Boolean = try {
        destination.parentFile?.mkdirs()
        ModelInstaller.resumeMarkerFor(destination).writeText(marker.serialize())
        true
    } catch (_: IOException) {
        false
    }

    /**
     * Position de reprise : la taille du partiel s'il est exploitable, sinon 0 (et le partiel inutilisable est supprimé).
     * Exploitable = marqueur lisible, partiel non vide, plus petit que la taille totale annoncée, et récent.
     */
    fun usableOffset(destination: File, nowMs: Long): Long {
        val temp = ModelInstaller.temporaryFileFor(destination)
        if (!temp.isFile) {
            ModelInstaller.resumeMarkerFor(destination).delete()
            return 0L
        }
        val marker = readMarker(destination)
        val length = temp.length()
        val usable = marker != null && length > 0 && length < marker.totalBytes && nowMs - temp.lastModified() <= MAX_AGE_MS
        if (!usable) {
            discard(destination)
            return 0L
        }
        return length
    }
}
