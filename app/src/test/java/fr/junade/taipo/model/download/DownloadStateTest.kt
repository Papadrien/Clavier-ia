package fr.junade.taipo.model.download

import fr.junade.taipo.model.AiModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class DownloadStateTest {

    @AfterEach
    fun reset() {
        AiModel.entriesOrdered().forEach { DownloadTracker.update(it, DownloadState.Idle) }
    }

    @Test
    fun `pourcentage borne et inconnu tant que la taille manque`() {
        assertNull(DownloadState.Downloading(10, -1).percent)
        assertEquals(0, DownloadState.Downloading(0, 100).percent)
        assertEquals(42, DownloadState.Downloading(42, 100).percent)
        assertEquals(100, DownloadState.Downloading(500, 100).percent)
    }

    @Test
    fun `le tracker notifie les ecouteurs et retombe a Idle`() {
        val seen = mutableListOf<Pair<AiModel, DownloadState>>()
        val listener = DownloadTracker.Listener { model, state -> seen += model to state }
        DownloadTracker.addListener(listener)
        DownloadTracker.update(AiModel.EQUILIBRE, DownloadState.Verifying)
        assertTrue(DownloadTracker.isBusy(AiModel.EQUILIBRE))
        DownloadTracker.update(AiModel.EQUILIBRE, DownloadState.Idle)
        DownloadTracker.removeListener(listener)
        DownloadTracker.update(AiModel.EQUILIBRE, DownloadState.Verifying) // plus écouté
        assertEquals(2, seen.size)
        assertFalse(DownloadTracker.isBusy(AiModel.PERFORMANT))
    }

    @Test
    fun `statut de ligne selon l installation et le telechargement`() {
        val idle = DownloadState.Idle
        assertEquals(ModelRowStatus.UNAVAILABLE, ModelRowStatus.of(installed = false, downloadable = false, state = idle))
        assertEquals(ModelRowStatus.DOWNLOAD, ModelRowStatus.of(installed = false, downloadable = true, state = idle))
        assertEquals(ModelRowStatus.INSTALLED, ModelRowStatus.of(installed = true, downloadable = false, state = idle))
        assertEquals(
            ModelRowStatus.DOWNLOADING,
            ModelRowStatus.of(false, true, DownloadState.Downloading(1, 2)),
        )
        assertEquals(ModelRowStatus.VERIFYING, ModelRowStatus.of(false, true, DownloadState.Verifying))
        // Échec (corrompu, incompatible, réseau…) : un seul statut « Réessayer ».
        assertEquals(ModelRowStatus.RETRY, ModelRowStatus.of(false, true, DownloadState.Failed(DownloadFailure.CORRUPTED)))
        // Un modèle déjà installé n'est pas dégradé par un échec de re-téléchargement.
        assertEquals(ModelRowStatus.INSTALLED, ModelRowStatus.of(true, true, DownloadState.Failed(DownloadFailure.NETWORK)))
    }

    @Test
    fun `causes d echec traduites`() {
        assertEquals(
            DownloadFailure.CORRUPTED,
            DownloadFailureMapper.from(InstallResult.Failed(InstallFailure.HASH_MISMATCH)),
        )
        assertEquals(
            DownloadFailure.CORRUPTED,
            DownloadFailureMapper.from(InstallResult.Failed(InstallFailure.SIZE_MISMATCH)),
        )
        assertEquals(DownloadFailure.NETWORK, DownloadFailureMapper.fromException(UnknownHostException()))
        assertEquals(DownloadFailure.NETWORK, DownloadFailureMapper.fromException(SocketTimeoutException()))
        assertEquals(
            DownloadFailure.NO_SPACE,
            DownloadFailureMapper.fromException(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(DownloadFailure.OTHER, DownloadFailureMapper.fromException(IOException("autre")))
        assertEquals(DownloadFailure.OTHER, DownloadFailureMapper.fromException(null))
    }

    @Test
    fun `le tracker vocal notifie les ecouteurs et retombe a Idle`() {
        val seen = mutableListOf<DownloadState>()
        val listener = DownloadTracker.VoiceListener { state -> seen += state }
        DownloadTracker.addVoiceListener(listener)
        try {
            DownloadTracker.updateVoice(DownloadState.Downloading(10, 100))
            assertTrue(DownloadTracker.isVoiceBusy())
            DownloadTracker.updateVoice(DownloadState.Failed(DownloadFailure.NETWORK))
            assertFalse(DownloadTracker.isVoiceBusy())
            DownloadTracker.updateVoice(DownloadState.Idle)
        } finally {
            DownloadTracker.removeVoiceListener(listener)
            DownloadTracker.updateVoice(DownloadState.Idle)
        }
        assertEquals(3, seen.size)
        assertEquals(DownloadState.Idle, DownloadTracker.voiceStateOf())
    }

    @Test
    fun `le tracker vocal est independant de celui des modeles de texte`() {
        DownloadTracker.updateVoice(DownloadState.Verifying)
        try {
            assertFalse(DownloadTracker.isBusy(AiModel.EQUILIBRE))
        } finally {
            DownloadTracker.updateVoice(DownloadState.Idle)
        }
    }
}
