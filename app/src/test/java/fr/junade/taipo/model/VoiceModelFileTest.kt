package fr.junade.taipo.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceModelFileTest {

    @Test
    fun `les 4 fichiers ont une URL HTTPS versionnee et distincte, sur le domaine des modeles`() {
        val urls = VoiceModelFile.all().map { it.downloadUrl }
        assertEquals(4, urls.size)
        assertEquals(urls.size, urls.toSet().size)
        urls.forEach {
            assertTrue(it.startsWith("https://taipo-worker.junade-models.workers.dev/voice/v"), it)
        }
    }

    @Test
    fun `les identifiants sont uniques et servent de nom de fichier local`() {
        val ids = VoiceModelFile.all().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it.matches(Regex("[a-z]+")), it) }
    }

    @Test
    fun `une empreinte figee est un SHA-256 hexadecimal de 64 caracteres`() {
        VoiceModelFile.all().mapNotNull { it.sha256 }.forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{64}")), it)
        }
    }

    @Test
    fun `tailles indicatives positives et total coherent`() {
        VoiceModelFile.all().forEach { assertTrue(it.approxBytes > 0, it.id) }
        assertEquals(VoiceModelFile.all().sumOf { it.approxBytes }, VoiceModelFile.totalApproxBytes())
    }

    @Test
    fun `le role d un fichier est devine depuis son nom`() {
        assertEquals(VoiceModelFile.ENCODER, VoiceModelFile.guessFromFileName("encoder.int8.onnx"))
        assertEquals(VoiceModelFile.TOKENS, VoiceModelFile.guessFromFileName("tokens.txt"))
        assertNotNull(VoiceModelFile.guessFromFileName("Joiner.onnx"))
    }
}
