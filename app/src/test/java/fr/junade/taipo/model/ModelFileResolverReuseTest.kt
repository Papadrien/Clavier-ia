package fr.junade.taipo.model

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ModelFileResolverReuseTest {

    @TempDir
    lateinit var dir: File

    private fun copyWith(content: String, sha: String?): File {
        val copy = File(dir, "performant.litertlm").apply { writeText(content) }
        val marker = ModelFileResolver.sha256MarkerFor(copy)
        if (sha != null) marker.writeText(sha) else marker.delete()
        return copy
    }

    @Test
    fun `taille differente - copie perimee`() {
        val copy = copyWith("abcd", "aa")
        assertFalse(ModelFileResolver.isCopyReusable(copy, expectedSize = 5, expectedSha256 = "aa"))
    }

    @Test
    fun `meme taille mais empreinte differente - copie perimee`() {
        val copy = copyWith("abcd", "aa")
        assertFalse(ModelFileResolver.isCopyReusable(copy, expectedSize = 4, expectedSha256 = "bb"))
    }

    @Test
    fun `meme taille et meme empreinte sans tenir compte de la casse - reutilisee`() {
        val copy = copyWith("abcd", "AA")
        assertTrue(ModelFileResolver.isCopyReusable(copy, expectedSize = 4, expectedSha256 = "aa"))
    }

    @Test
    fun `empreinte du fichier choisi pas encore calculee - taille seule`() {
        val copy = copyWith("abcd", "aa")
        assertTrue(ModelFileResolver.isCopyReusable(copy, expectedSize = 4, expectedSha256 = null))
    }

    @Test
    fun `ancienne copie sans fichier d'empreinte - taille seule`() {
        val copy = copyWith("abcd", null)
        assertTrue(ModelFileResolver.isCopyReusable(copy, expectedSize = 4, expectedSha256 = "bb"))
    }
}
