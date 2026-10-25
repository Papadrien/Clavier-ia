package fr.junade.taipo.suggestion

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lot 3.4 (S1) : [NextWordCrypto]. Le plus important : un fichier écrit par l'ancienne version
 * (clé = SHA-256 du secret, sans en-tête) reste lisible, pour ne pas perdre le modèle appris.
 */
class NextWordCryptoTest {

    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray(Charsets.US_ASCII)
    private val plain = "bonjour 3\nmerci 5\n".toByteArray(Charsets.UTF_8)

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    /** Reproduit à l'identique l'ancien `encrypt` (avant le lot 3.4) : IV + chiffré, clé = SHA-256(secret). */
    private fun legacyEncrypt(secret: ByteArray, plain: ByteArray, iv: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }): ByteArray {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    @Test
    fun `HKDF - vecteur 1 de la RFC 5869`() {
        val okm = NextWordCrypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.toHex(),
        )
    }

    @Test
    fun `HKDF - vecteur 3 de la RFC 5869 (sel et info vides)`() {
        val okm = NextWordCrypto.hkdfSha256(ikm = ByteArray(22) { 0x0b }, salt = ByteArray(0), info = ByteArray(0), length = 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.toHex(),
        )
    }

    @Test
    fun `chiffrer puis dechiffrer redonne le texte au format actuel`() {
        val stored = NextWordCrypto.encrypt(secret, plain)
        assertArrayEquals(NextWordCrypto.MAGIC, stored.copyOfRange(0, 4))
        val result = NextWordCrypto.decrypt(secret, stored)
        assertArrayEquals(plain, result.plain)
        assertFalse(result.legacyFormat)
    }

    @Test
    fun `deux chiffrements du meme texte different (IV aleatoire)`() {
        assertFalse(NextWordCrypto.encrypt(secret, plain).contentEquals(NextWordCrypto.encrypt(secret, plain)))
    }

    @Test
    fun `le texte n'apparait pas en clair dans le fichier`() {
        val stored = String(NextWordCrypto.encrypt(secret, plain), Charsets.ISO_8859_1)
        assertFalse(stored.contains("bonjour"))
    }

    @Test
    fun `un fichier de l'ancien format reste lisible et est signale comme tel`() {
        val result = NextWordCrypto.decrypt(secret, legacyEncrypt(secret, plain))
        assertArrayEquals(plain, result.plain)
        assertTrue(result.legacyFormat)
    }

    @Test
    fun `un ancien fichier dont l'IV commence comme l'en-tete actuel reste lisible`() {
        val iv = NextWordCrypto.MAGIC + ByteArray(8) { it.toByte() }
        val result = NextWordCrypto.decrypt(secret, legacyEncrypt(secret, plain, iv))
        assertArrayEquals(plain, result.plain)
        assertTrue(result.legacyFormat)
    }

    @Test
    fun `migration - un ancien fichier relu puis reecrit devient du format actuel`() {
        val migrated = NextWordCrypto.encrypt(secret, NextWordCrypto.decrypt(secret, legacyEncrypt(secret, plain)).plain)
        val result = NextWordCrypto.decrypt(secret, migrated)
        assertArrayEquals(plain, result.plain)
        assertFalse(result.legacyFormat)
    }

    @Test
    fun `une mauvaise cle est refusee dans les deux formats`() {
        val other = "f".repeat(64).toByteArray(Charsets.US_ASCII)
        assertThrows(GeneralSecurityException::class.java) { NextWordCrypto.decrypt(other, NextWordCrypto.encrypt(secret, plain)) }
        assertThrows(GeneralSecurityException::class.java) { NextWordCrypto.decrypt(other, legacyEncrypt(secret, plain)) }
    }

    @Test
    fun `un octet modifie est detecte, y compris dans l'en-tete`() {
        val stored = NextWordCrypto.encrypt(secret, plain)
        for (index in listOf(0, 3, 4, 15, stored.size - 1)) {
            val tampered = stored.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows(Exception::class.java, { NextWordCrypto.decrypt(secret, tampered) }, "octet $index modifié non détecté")
        }
    }

    @Test
    fun `un fichier tronque ou vide est refuse`() {
        assertThrows(Exception::class.java) { NextWordCrypto.decrypt(secret, ByteArray(0)) }
        assertThrows(Exception::class.java) { NextWordCrypto.decrypt(secret, ByteArray(12)) }
        assertThrows(Exception::class.java) { NextWordCrypto.decrypt(secret, NextWordCrypto.encrypt(secret, plain).copyOf(10)) }
    }

    @Test
    fun `la cle du format actuel n'est pas celle de l'ancien format`() {
        // Garde-fou : si quelqu'un « simplifie » encrypt() vers SHA-256, la migration n'a plus de sens.
        val legacyKey = MessageDigest.getInstance("SHA-256").digest(secret)
        val derived = NextWordCrypto.hkdfSha256(secret, ByteArray(0), "fr.junade.taipo/next-word-model/aes-256-gcm/v2".toByteArray(), 32)
        assertNotEquals(legacyKey.toHex(), derived.toHex())
    }
}
