package fr.junade.taipo.model

import fr.junade.taipo.model.download.DownloadState
import fr.junade.taipo.model.download.ModelRowStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 8.14 : mise à jour d'un modèle quand l'empreinte du catalogue change. */
class ModelUpdateTest {

    private val old = "a".repeat(64)
    private val new = "b".repeat(64)

    @Test
    fun `empreinte installee differente du catalogue signale une mise a jour`() {
        assertTrue(ModelUpdate.isAvailable(downloaded = true, installedSha256 = old, catalogSha256 = new))
    }

    @Test
    fun `empreinte identique ne signale rien, meme avec une casse differente`() {
        assertFalse(ModelUpdate.isAvailable(true, old, old))
        assertFalse(ModelUpdate.isAvailable(true, old.uppercase(), old))
    }

    @Test
    fun `un fichier fourni a la main n est pas concerne`() {
        assertFalse(ModelUpdate.isAvailable(downloaded = false, installedSha256 = old, catalogSha256 = new))
    }

    @Test
    fun `sans empreinte connue d un cote ou de l autre aucune fausse alerte`() {
        assertFalse(ModelUpdate.isAvailable(true, null, new))
        assertFalse(ModelUpdate.isAvailable(true, old, null))
    }

    @Test
    fun `statut Mise a jour disponible pour un modele installe a mettre a jour`() {
        assertEquals(
            ModelRowStatus.UPDATE_AVAILABLE,
            ModelRowStatus.of(installed = true, downloadable = true, state = DownloadState.Idle, updateAvailable = true),
        )
    }

    @Test
    fun `sans mise a jour le statut reste Installe`() {
        assertEquals(
            ModelRowStatus.INSTALLED,
            ModelRowStatus.of(installed = true, downloadable = true, state = DownloadState.Idle),
        )
    }

    @Test
    fun `pendant la mise a jour on voit la progression, le modele reste installe`() {
        assertEquals(
            ModelRowStatus.DOWNLOADING,
            ModelRowStatus.of(true, true, DownloadState.Downloading(1, 2), updateAvailable = true),
        )
        assertEquals(
            ModelRowStatus.VERIFYING,
            ModelRowStatus.of(true, true, DownloadState.Verifying, updateAvailable = true),
        )
    }

    @Test
    fun `une mise a jour echouee laisse le modele installe et la mise a jour proposee`() {
        assertEquals(
            ModelRowStatus.UPDATE_AVAILABLE,
            ModelRowStatus.of(true, true, DownloadState.Failed(fr.junade.taipo.model.download.DownloadFailure.NETWORK), true),
        )
    }
}
