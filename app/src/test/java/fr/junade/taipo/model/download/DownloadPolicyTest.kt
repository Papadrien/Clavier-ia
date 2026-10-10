package fr.junade.taipo.model.download

import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.VoiceModelFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadPolicyTest {

    private val gemma4 = AiModel.EQUILIBRE
    private val plenty = 100_000_000_000L

    private fun decide(
        model: AiModel = gemma4,
        network: NetworkKind = NetworkKind.UNMETERED,
        free: Long = plenty,
        accepted: Boolean = false,
        allowUnverified: Boolean = true,
    ) = DownloadPolicy.decide(model, network, free, accepted, allowUnverified)

    @Test
    fun `wifi et place suffisante demarre le telechargement`() {
        assertEquals(DownloadDecision.Start, decide())
    }

    @Test
    fun `donnees mobiles demande confirmation puis demarre apres accord`() {
        assertEquals(DownloadDecision.ConfirmMobileData, decide(network = NetworkKind.METERED))
        assertEquals(DownloadDecision.Start, decide(network = NetworkKind.METERED, accepted = true))
    }

    @Test
    fun `sans connexion rien n est lance`() {
        assertEquals(DownloadDecision.NoConnection, decide(network = NetworkKind.NONE))
    }

    @Test
    fun `espace insuffisant est signale avant tout telechargement`() {
        val size = gemma4.approxDownloadBytes!!
        val decision = decide(free = size) // la marge de sécurité manque
        assertTrue(decision is DownloadDecision.InsufficientSpace)
        decision as DownloadDecision.InsufficientSpace
        assertEquals(size + DownloadPolicy.SAFETY_MARGIN_BYTES, decision.requiredBytes)
        assertEquals(size, decision.freeBytes)
    }

    @Test
    fun `l espace est controle avant la confirmation donnees mobiles`() {
        val decision = decide(network = NetworkKind.METERED, free = 1L)
        assertTrue(decision is DownloadDecision.InsufficientSpace)
    }

    @Test
    fun `espace exactement egal a la taille plus la marge suffit`() {
        val size = gemma4.approxDownloadBytes!!
        assertTrue(DownloadPolicy.hasEnoughSpace(size + DownloadPolicy.SAFETY_MARGIN_BYTES, size))
        assertFalse(DownloadPolicy.hasEnoughSpace(size + DownloadPolicy.SAFETY_MARGIN_BYTES - 1, size))
    }

    @Test
    fun `sans empreinte le telechargement n est permis qu en debug`() {
        // Story 8.8 : tant que AiModel.sha256 n'est pas figé, seul le debug peut télécharger (pour relever l'empreinte).
        // Une fois l'empreinte renseignée, le modèle devient téléchargeable en release aussi.
        assertTrue(DownloadPolicy.isDownloadable(gemma4, allowUnverified = true))
        assertEquals(gemma4.sha256 != null, DownloadPolicy.isDownloadable(gemma4, allowUnverified = false))
        if (gemma4.sha256 == null) assertEquals(DownloadDecision.NotAvailable, decide(allowUnverified = false))
    }

    @Test
    fun `les URL des modeles Gemma 4 sont en HTTPS et pointent vers le bon fichier`() {
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            AiModel.EQUILIBRE.downloadUrl,
        )
        assertEquals(
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            AiModel.PERFORMANT.downloadUrl,
        )
        assertEquals(
            "https://taipo-worker.junade-models.workers.dev/gemma3-270m-it-q8.litertlm",
            AiModel.ULTRA_LEGER.downloadUrl,
        )
        assertEquals(
            "https://taipo-worker.junade-models.workers.dev/gemma3-1b-it-int4.litertlm",
            AiModel.LEGER.downloadUrl,
        )
        AiModel.entriesOrdered().mapNotNull { it.downloadUrl }.forEach { assertTrue(it.startsWith("https://")) }
    }

    @Test
    fun `les 4 modeles sont telechargeables`() {
        val downloadable = AiModel.entriesOrdered().filter { it.hasDownloadUrl }
        assertEquals(AiModel.entriesOrdered(), downloadable)
    }

    @Test
    fun `la taille de telechargement annoncee reste dans la plage attendue du modele`() {
        AiModel.entriesOrdered().filter { it.hasDownloadUrl }.forEach {
            val size = it.approxDownloadBytes!!
            assertTrue(size in it.approxSizeBytesMin..it.approxSizeBytesMax, it.id)
        }
    }

    @Test
    fun `arret en cours de telechargement selon le reseau`() {
        assertTrue(DownloadPolicy.shouldAbortForNetwork(NetworkKind.NONE, mobileDataAccepted = true))
        assertTrue(DownloadPolicy.shouldAbortForNetwork(NetworkKind.METERED, mobileDataAccepted = false))
        assertFalse(DownloadPolicy.shouldAbortForNetwork(NetworkKind.METERED, mobileDataAccepted = true))
        assertFalse(DownloadPolicy.shouldAbortForNetwork(NetworkKind.UNMETERED, mobileDataAccepted = false))
    }

    // --- Story 8.15 : modèle vocal -------------------------------------------------------------------------------

    private fun decideVoice(
        missing: Long = 600_000_000L,
        network: NetworkKind = NetworkKind.UNMETERED,
        free: Long = plenty,
        accepted: Boolean = false,
        allowUnverified: Boolean = true,
    ) = DownloadPolicy.decideVoice(missing, network, free, accepted, allowUnverified)

    @Test
    fun `modele vocal - wifi et place suffisante demarre`() {
        assertEquals(DownloadDecision.Start, decideVoice())
    }

    @Test
    fun `modele vocal - sans empreinte il n est telechargeable qu en debug`() {
        // Tant qu'une empreinte manque (VoiceModelFile.sha256 null), la release refuse : story 8.8.
        val allFrozen = VoiceModelFile.all().all { it.sha256 != null }
        val release = decideVoice(allowUnverified = false)
        if (allFrozen) assertEquals(DownloadDecision.Start, release) else assertEquals(DownloadDecision.NotAvailable, release)
        assertEquals(DownloadDecision.Start, decideVoice(allowUnverified = true))
    }

    @Test
    fun `modele vocal - donnees mobiles, connexion et espace`() {
        assertEquals(DownloadDecision.ConfirmMobileData, decideVoice(network = NetworkKind.METERED))
        assertEquals(DownloadDecision.Start, decideVoice(network = NetworkKind.METERED, accepted = true))
        assertEquals(DownloadDecision.NoConnection, decideVoice(network = NetworkKind.NONE))
        val decision = decideVoice(missing = 600_000_000L, free = 600_000_000L)
        assertTrue(decision is DownloadDecision.InsufficientSpace)
        decision as DownloadDecision.InsufficientSpace
        assertEquals(600_000_000L + DownloadPolicy.SAFETY_MARGIN_BYTES, decision.requiredBytes)
    }
}
