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
    fun `flux termine trop tot est refuse mais le partiel est garde pour la reprise`() {
        val destination = destination()
        val result = ModelInstaller.install(
            input = payload.copyOf(1_000_000).inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
        )
        assertEquals(InstallResult.Failed(InstallFailure.TRUNCATED), result)
        assertFalse(destination.exists())
        assertEquals(1_000_000L, ModelInstaller.temporaryFileFor(destination).length())
    }

    @Test
    fun `flux plus long que la taille annoncee est refuse et supprime`() {
        val destination = destination()
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = 1_000_000L,
            expectedSha256 = null,
        )
        assertEquals(InstallResult.Failed(InstallFailure.SIZE_MISMATCH), result)
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
    fun `erreur de lecture reseau garde le partiel pour la reprise`() {
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
        assertEquals(1000L, ModelInstaller.temporaryFileFor(destination).length())
    }

    /** Flux qui livre les [bytes] premiers octets de [payload], puis tombe en panne (réseau coupé). */
    private fun cutAfter(bytes: Int) = object : InputStream() {
        private var position = 0
        override fun read(): Int = throw IOException("coupé")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= bytes) throw IOException("Connection reset")
            val count = minOf(len, bytes - position)
            System.arraycopy(payload, position, b, off, count)
            position += count
            return count
        }
    }

    @Test
    fun `reprise d un partiel : empreinte calculee sur le fichier entier`() {
        val destination = destination()
        val first = ModelInstaller.install(cutAfter(1_000_000), destination, payload.size.toLong(), payloadSha)
        assertTrue(first is InstallResult.Failed)
        val temp = ModelInstaller.temporaryFileFor(destination)
        assertEquals(1_000_000L, temp.length())
        PartialDownload.writeMarker(destination, ResumeMarker(payload.size.toLong(), "\"etag\""))

        val progress = mutableListOf<Long>()
        val resumed = ModelInstaller.install(
            input = payload.copyOfRange(1_000_000, payload.size).inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            onProgress = { progress += it },
            resumeFrom = 1_000_000L,
        )
        assertEquals(InstallResult.Installed(payload.size.toLong(), payloadSha), resumed)
        assertEquals(payload.size.toLong(), destination.length())
        assertFalse(temp.exists())
        assertFalse(ModelInstaller.resumeMarkerFor(destination).exists())
        assertEquals(payloadSha, ModelInstaller.sha256MarkerFor(destination).readText())
        // La progression repart du partiel déjà présent et atteint la taille totale.
        assertTrue(progress.first() <= 1_000_000L)
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test
    fun `reprise en plusieurs coupures successives`() {
        val destination = destination()
        ModelInstaller.install(cutAfter(700_000), destination, payload.size.toLong(), payloadSha)
        assertEquals(700_000L, ModelInstaller.temporaryFileFor(destination).length())
        ModelInstaller.install(
            input = payload.copyOfRange(700_000, 1_900_000).inputStream().let { part ->
                object : InputStream() {
                    private var done = false
                    override fun read(): Int = throw IOException("coupé")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val read = part.read(b, off, len)
                        if (read < 0 && !done) {
                            done = true
                            throw IOException("Connection reset")
                        }
                        return read
                    }
                }
            },
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            resumeFrom = 700_000L,
        )
        assertEquals(1_900_000L, ModelInstaller.temporaryFileFor(destination).length())
        val last = ModelInstaller.install(
            input = payload.copyOfRange(1_900_000, payload.size).inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            resumeFrom = 1_900_000L,
        )
        assertEquals(InstallResult.Installed(payload.size.toLong(), payloadSha), last)
    }

    @Test
    fun `reprise demandee mais partiel de taille differente repart de zero`() {
        val destination = destination()
        destination.parentFile!!.mkdirs()
        ModelInstaller.temporaryFileFor(destination).writeText("xx")
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            resumeFrom = 10L,
        )
        assertEquals(InstallResult.Installed(payload.size.toLong(), payloadSha), result)
    }

    @Test
    fun `reprise sur un fichier modifie cote serveur echoue a l empreinte et supprime partiel et marqueur`() {
        val destination = destination()
        ModelInstaller.install(cutAfter(1_000_000), destination, payload.size.toLong(), payloadSha)
        PartialDownload.writeMarker(destination, ResumeMarker(payload.size.toLong(), null))
        // La suite reçue ne correspond plus au même fichier.
        val other = ByteArray(payload.size - 1_000_000) { 7 }
        val result = ModelInstaller.install(
            input = other.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            resumeFrom = 1_000_000L,
        )
        assertEquals(InstallResult.Failed(InstallFailure.HASH_MISMATCH), result)
        assertFalse(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertFalse(ModelInstaller.resumeMarkerFor(destination).exists())
    }

    @Test
    fun `arret temporaire garde le partiel`() {
        val destination = destination()
        var calls = 0
        val result = ModelInstaller.install(
            input = payload.inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            isCancelled = { ++calls > 2 },
            keepPartial = { true },
        )
        assertEquals(InstallResult.Cancelled, result)
        assertFalse(destination.exists())
        assertTrue(ModelInstaller.temporaryFileFor(destination).length() > 0)
    }

    @Test
    fun `annulation de l utilisateur pendant la relecture du partiel supprime tout`() {
        val destination = destination()
        ModelInstaller.install(cutAfter(1_000_000), destination, payload.size.toLong(), payloadSha)
        PartialDownload.writeMarker(destination, ResumeMarker(payload.size.toLong(), null))
        val result = ModelInstaller.install(
            input = payload.copyOfRange(1_000_000, payload.size).inputStream(),
            destination = destination,
            expectedBytes = payload.size.toLong(),
            expectedSha256 = payloadSha,
            isCancelled = { true },
            resumeFrom = 1_000_000L,
            keepPartial = { false },
        )
        assertEquals(InstallResult.Cancelled, result)
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertFalse(ModelInstaller.resumeMarkerFor(destination).exists())
    }

    @Test
    fun `un fichier existant est remplace seulement apres verification`() {
        val destination = destination()
        destination.parentFile!!.mkdirs()
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
        destination.parentFile!!.mkdirs()
        ModelInstaller.temporaryFileFor(destination).writeText("reste")
        val result = ModelInstaller.install(payload.inputStream(), destination, payload.size.toLong(), payloadSha)
        assertTrue(result is InstallResult.Installed)
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }
}
