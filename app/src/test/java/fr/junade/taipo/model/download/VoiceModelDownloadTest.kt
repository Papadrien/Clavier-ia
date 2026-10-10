package fr.junade.taipo.model.download

import fr.junade.taipo.model.VoiceModelFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceModelDownloadTest {

    @Test
    fun `telechargeable en debug meme sans empreinte, en release seulement si toutes sont figees`() {
        assertTrue(VoiceModelDownload.isDownloadable(allowUnverified = true))
        val allFrozen = VoiceModelFile.all().all { it.sha256 != null }
        assertEquals(allFrozen, VoiceModelDownload.isDownloadable(allowUnverified = false))
    }

    @Test
    fun `un fichier est a jour seulement s il est telecharge, present et non perime`() {
        assertFalse(VoiceModelDownload.needsDownload(downloaded = true, present = true, updateAvailable = false))
        assertTrue(VoiceModelDownload.needsDownload(downloaded = true, present = false, updateAvailable = false))
        assertTrue(VoiceModelDownload.needsDownload(downloaded = true, present = true, updateAvailable = true))
        // Fourni à la main (debug) : un téléchargement le remplace.
        assertTrue(VoiceModelDownload.needsDownload(downloaded = false, present = true, updateAvailable = false))
        assertTrue(VoiceModelDownload.needsDownload(downloaded = false, present = false, updateAvailable = false))
    }

    @Test
    fun `taille manquante = somme des tailles indicatives des fichiers a telecharger`() {
        assertEquals(0L, VoiceModelDownload.missingBytes(emptyList()))
        assertEquals(
            VoiceModelFile.ENCODER.approxBytes + VoiceModelFile.TOKENS.approxBytes,
            VoiceModelDownload.missingBytes(listOf(VoiceModelFile.ENCODER, VoiceModelFile.TOKENS)),
        )
        assertEquals(VoiceModelFile.totalApproxBytes(), VoiceModelDownload.missingBytes(VoiceModelFile.all()))
    }

    @Test
    fun `progression globale sur plusieurs fichiers`() {
        // Rien reçu.
        assertEquals(0, VoiceModelDownload.overallPercent(0, 0, 600, 400))
        // Moitié du premier fichier (600 sur 1000 au total).
        assertEquals(30, VoiceModelDownload.overallPercent(0, 300, 600, 400))
        // Premier fichier fini (600), deuxième à moitié (200 sur 400).
        assertEquals(80, VoiceModelDownload.overallPercent(600, 200, 400, 0))
        // Tout reçu.
        assertEquals(100, VoiceModelDownload.overallPercent(600, 400, 400, 0))
    }

    @Test
    fun `progression bornee et sans division par zero`() {
        assertEquals(0, VoiceModelDownload.overallPercent(0, 0, 0, 0))
        // Le fichier est plus gros que prévu : jamais au-delà de 100.
        assertEquals(100, VoiceModelDownload.overallPercent(0, 900, 100, 0))
    }
}
