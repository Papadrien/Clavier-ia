package fr.junade.taipo.suggestion

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement du fichier du modèle « mot suivant » (lot 3.4, S1). Logique pure (aucune API Android) :
 * le secret vient de `DatabasePassphraseProvider` (module app) et est passé en paramètre.
 *
 * Deux formats de fichier :
 * - **v2 (actuel)** : [MAGIC] (4 octets), IV (12 octets), texte chiffré + étiquette GCM. La clé AES-256 est
 *   dérivée du secret par HKDF-SHA256 avec un libellé d'usage ([HKDF_INFO]) ; l'en-tête est authentifié
 *   (donnée associée GCM) : le modifier fait échouer le déchiffrement.
 * - **v1 (historique)** : IV (12 octets) puis texte chiffré ; clé = SHA-256 du secret. Toujours lisible,
 *   pour migrer sans perdre ce que le clavier a appris : [decrypt] l'indique par [Decrypted.legacyFormat]
 *   et l'appelant réécrit alors le fichier en v2. Plus jamais écrit.
 *
 * Le secret est un tirage aléatoire de 256 bits : le SHA-256 historique n'était pas une faille. HKDF
 * apporte une dérivation standard, séparée par usage, et le format versionné permet d'évoluer.
 */
object NextWordCrypto {

    /** Début d'un fichier v2 : « TNW » + numéro de version 2. */
    val MAGIC = byteArrayOf('T'.code.toByte(), 'N'.code.toByte(), 'W'.code.toByte(), 2)

    const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HKDF_INFO = "fr.junade.taipo/next-word-model/aes-256-gcm/v2"
    private const val KEY_BYTES = 32

    class Decrypted(val plain: ByteArray, val legacyFormat: Boolean)

    fun encrypt(secret: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val keyBytes = hkdfSha256(secret, ByteArray(0), HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            return MAGIC + iv + cipher.doFinal(plain)
        } finally {
            keyBytes.fill(0)
        }
    }

    /** Lève une exception si le fichier est illisible (mauvaise clé, corruption, trop court). */
    fun decrypt(secret: ByteArray, stored: ByteArray): Decrypted {
        if (stored.size > MAGIC.size + IV_BYTES && stored.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            try {
                return Decrypted(decryptV2(secret, stored), legacyFormat = false)
            } catch (_: GeneralSecurityException) {
                // Un fichier v1 dont l'IV (aléatoire) commence par MAGIC : probabilité 2^-32, mais on
                // retombe sur le format historique plutôt que de perdre le modèle.
            }
        }
        return Decrypted(decryptLegacy(secret, stored), legacyFormat = true)
    }

    private fun decryptV2(secret: ByteArray, stored: ByteArray): ByteArray {
        val keyBytes = hkdfSha256(secret, ByteArray(0), HKDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES)
        try {
            val iv = stored.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            return cipher.doFinal(stored, MAGIC.size + IV_BYTES, stored.size - MAGIC.size - IV_BYTES)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun decryptLegacy(secret: ByteArray, stored: ByteArray): ByteArray {
        require(stored.size > IV_BYTES) { "Fichier du modèle trop court" }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(secret)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, stored.copyOfRange(0, IV_BYTES)),
            )
            return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * HKDF (RFC 5869) avec HMAC-SHA256 : extraction puis expansion. Un [salt] vide équivaut à 32 octets
     * nuls (règle de la RFC ; `Mac` refuse une clé vide). Exposée pour être testée avec les vecteurs de la RFC.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "Longueur HKDF invalide : $length" }
        val extractKey = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmac(extractKey, ikm)
        try {
            val output = ByteArray(length)
            var previous = ByteArray(0)
            var written = 0
            var counter = 1
            while (written < length) {
                previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                val count = minOf(previous.size, length - written)
                previous.copyInto(output, written, 0, count)
                written += count
                counter++
            }
            return output
        } finally {
            prk.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
