package fr.junade.taipo.model

import java.io.InputStream
import java.security.MessageDigest

/**
 * Empreinte SHA-256 d'un fichier (lot 3.4, S3), calculée en flux : un modèle fait plusieurs Go, il ne
 * faut jamais le charger en mémoire. À appeler hors du thread principal.
 *
 * [onProgress] reçoit le nombre total d'octets lus (après chaque bloc de 1 Mo). [isCancelled] est testé
 * à chaque bloc : s'il passe à vrai, le calcul s'arrête et la fonction renvoie null.
 */
fun sha256HexOrNull(
    input: InputStream,
    isCancelled: () -> Boolean = { false },
    onProgress: ((Long) -> Unit)? = null,
): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 20)
    var total = 0L
    while (true) {
        if (isCancelled()) return null
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        digest.update(buffer, 0, read)
        total += read
        onProgress?.invoke(total)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha256Hex(input: InputStream, onProgress: ((Long) -> Unit)? = null): String =
    checkNotNull(sha256HexOrNull(input, onProgress = onProgress))

/** Compare deux empreintes hexadécimales sans tenir compte de la casse ni des espaces autour. */
fun sameSha256(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

/**
 * Message d'avertissement si l'empreinte [actual] diffère de l'empreinte [reference] attendue, ou null
 * (identiques, ou aucune référence connue).
 */
fun checksumWarningFor(reference: String?, actual: String): String? {
    if (reference == null || sameSha256(reference, actual)) return null
    return "L'empreinte SHA-256 du fichier ne correspond pas à celle attendue pour ce modèle : fichier " +
        "corrompu, incomplet, ou autre variante. Retéléchargez-le depuis le dépôt de référence."
}

/** Même contrôle pour un [AiModel] : sans effet tant que [AiModel.sha256] n'est pas renseigné. */
fun AiModel.checksumWarning(actual: String): String? = checksumWarningFor(sha256, actual)
