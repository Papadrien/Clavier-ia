package fr.junade.taipo.model

import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Sha256Test {

    @Test
    fun `vecteurs connus`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArrayInputStream(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test
    fun `un flux plus grand que le tampon donne le meme resultat que le calcul direct`() {
        val data = ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, sha256Hex(ByteArrayInputStream(data)))
    }

    @Test
    fun `la progression est notifiee et se termine au total`() {
        val data = ByteArray(2_500_000)
        val seen = mutableListOf<Long>()
        sha256Hex(ByteArrayInputStream(data)) { seen += it }
        assertTrue(seen.isNotEmpty())
        assertEquals(data.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `une annulation interrompt le calcul`() {
        val result = sha256HexOrNull(ByteArrayInputStream(ByteArray(5_000_000)), isCancelled = { true })
        assertNull(result)
    }

    @Test
    fun `comparaison insensible a la casse et aux espaces`() {
        assertTrue(sameSha256("ABC123", " abc123\n"))
        assertTrue(!sameSha256("abc123", "abc124"))
    }

    @Test
    fun `sans empreinte de reference aucun avertissement`() {
        assertNull(AiModel.LEGER.checksumWarning("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
    }

    @Test
    fun `avec une empreinte de reference une difference est signalee`() {
        val reference = "a".repeat(64)
        assertNull(checksumWarningFor(reference, reference.uppercase()))
        assertTrue(checksumWarningFor(reference, "b".repeat(64))!!.isNotBlank())
    }
}
