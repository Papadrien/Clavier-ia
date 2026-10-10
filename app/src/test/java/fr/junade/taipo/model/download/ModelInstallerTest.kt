package fr.junade.taipo.model.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class ModelInstallerTest {

    @TempDir
    lateinit var dir: File

    private val payload = ByteArray(3_000_000) { (it % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun destination() = File(File(dir, "models"), "equilibre.litertlm")

    @Test
    fun `installation reussie renomme le temporaire et ecrit l empreinte`() {
        val destination = destination()
        val progress = mutableListOf<Long>()
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            onProgress = { progress += it },
        )
        assertEquals(InstallResult.Installed(payload.size.toLong(), payloadSha), result)
        assertTrue(destination.isFile)
        assertEquals(payload.size.toLong(), destination.length())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertEquals(payloadSha, ModelInstaller.sha256MarkerFor(destination).readText())
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test
    fun `l empreinte est comparee sans tenir compte de la casse`() {
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination(),
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha.uppercase(),
        )
        assertTrue(result is InstallResult.Installed)
    }

    @Test
    fun `empreinte differente supprime le temporaire et ne cree aucun modele`() {
        val destination = destination()
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = "0".repeat(64),
        )
        assertEquals(InstallResult.Failed(InstallFailure.HASH_MISMATCH), result)
        assertFalse(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertFalse(ModelInstaller.sha256MarkerFor(destination).exists())
    }

    @Test
    fun `flux tronque par rapport au Content-Length est refuse`() {
        val destination = destination()
        val result = ModelInstaller.install(
            input = payload.copyOf(1_000_000).inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
        )
        assertEquals(InstallResult.Failed(InstallFailure.SIZE_MISMATCH), result)
        assertFalse(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }

    @Test
    fun `sans empreinte de reference l installation reussit et renvoie l empreinte calculee`() {
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination(),
            expectedBytes = payload.size.toLong(),
            expectedSha256 = null,
        )
        assertEquals(InstallResult.Installed(payload.size.toLong(), payloadSha), result)
    }

    @Test
    fun `annulation en cours de route ne laisse aucun fichier`() {
        val destination = destination()
        var calls = 0
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            isCancelled = { ++calls > 1 }, // après le premier bloc
        )
        assertEquals(InstallResult.Cancelled, result)
        assertFalse(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }

    @Test
    fun `erreur de lecture reseau ne laisse aucun fichier`() {
        val destination = destination()
        val broken = object : InputStream() {
            private var sent = false
            override fun read(): Int = throw IOException("coupé")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!sent) {
                    sent = true
                    b.fill(1, off, off + 1000)
                    return 1000
                }
                throw IOException("Connection reset")
            }
        }
        val result = ModelInstaller.install(broken, destination, expectedBytes = 5_000L, expectedSha256 = null)
        assertTrue(result is InstallResult.Failed)
        assertEquals(InstallFailure.IO, (result as InstallResult.Failed).reason)
        assertFalse(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }

    @Test
    fun `un fichier existant est remplace seulement apres verification`() {
        val destination = destination()
        destination.parentFile.mkdirs()
        destination.writeText("ancien")
        // Échec de vérification : l'ancien fichier reste intact.
        ModelInstaller.install(payload.inputStream(), destination, payload.size.toLong(), "0".repeat(64))
        assertEquals("ancien", destination.readText())
        // Succès : remplacé, et beforeCommit a été appelé avant le remplacement.
        var committedWhileOldStillThere = false
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            beforeCommit = { committedWhileOldStillThere = destination.readText() == "ancien" },
        )
        assertTrue(result is InstallResult.Installed)
        assertTrue(committedWhileOldStillThere)
        assertEquals(payload.size.toLong(), destination.length())
    }

    @Test
    fun `un temporaire laisse par un precedent echec est ecrase`() {
        val destination = destination()
        destination.parentFile.mkdirs()
        ModelInstaller.temporaryFileFor(destination).writeText("reste")
        val result = ModelInstaller.install(payload.inputStream(), destination, payload.size.toLong(), payloadSha)
        assertTrue(result is InstallResult.Installed)
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }
}
