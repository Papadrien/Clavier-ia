package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Audit SEC-03 : les empreintes figées dans [DictionaryChecksums] sont celles des listes du dépôt (`dictionaries/`, ce qui est
 * envoyé sur le serveur). Une liste modifiée sans mise à jour de son empreinte serait refusée au téléchargement.
 */
class DictionaryChecksumsTest {

    private fun file(path: String): File =
        listOf(File("../dictionaries/$path"), File("dictionaries/$path")).first { it.isFile }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun `chaque liste a l'empreinte du fichier du depot`() {
        for (language in KeyboardLanguage.entries) {
            for (kind in DictionaryFile.entries) {
                val path = kind.relativePath(language)
                assertEquals(sha256(file(path)), DictionaryChecksums.of(language, kind), path)
            }
        }
    }

    @Test
    fun `les paires de mots ont l'empreinte du fichier du depot`() {
        for (language in KeyboardLanguage.entries) {
            val path = "bigrams/${language.tag}.txt"
            val expected = listOf(File("../dictionaries/$path"), File("dictionaries/$path")).firstOrNull { it.isFile }?.let(::sha256)
            if (language in DictionaryFiles.BIGRAM_LANGUAGES) {
                assertEquals(expected, DictionaryChecksums.bigramsOf(language), path)
            } else {
                assertNull(DictionaryChecksums.bigramsOf(language), path)
            }
        }
    }
}
