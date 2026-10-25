package fr.junade.taipo.model.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PartialDownloadTest {

    @TempDir
    lateinit var dir: File

    private val now = 1_800_000_000_000L
    private fun destination() = File(File(dir, "models"), "performant.litertlm")

    private fun partial(bytes: Int, total: Long?, ageMs: Long = 0L): File {
        val destination = destination()
        destination.parentFile!!.mkdirs()
        val temp = ModelInstaller.temporaryFileFor(destination)
        temp.writeBytes(ByteArray(bytes) { 1 })
        temp.setLastModified(now - ageMs)
        if (total != null) PartialDownload.writeMarker(destination, ResumeMarker(total, "\"abc\""))
        return destination
    }

    @Test
    fun `le marqueur survit a l ecriture et a la lecture`() {
        val marker = ResumeMarker(3_650_000_000L, "\"a1b2\"")
        assertEquals(marker, ResumeMarker.parse(marker.serialize()))
        val withoutValidator = ResumeMarker(10L, null)
        assertEquals(withoutValidator, ResumeMarker.parse(withoutValidator.serialize()))
    }

    @Test
    fun `un marqueur sans taille exploitable est refuse`() {
        assertNull(ResumeMarker.parse(""))
        assertNull(ResumeMarker.parse("validator=\"x\""))
        assertNull(ResumeMarker.parse("total=abc"))
        assertNull(ResumeMarker.parse("total=0"))
    }

    @Test
    fun `Content-Range valide`() {
        assertEquals(ContentRange(1000, 1999, 2000), ContentRange.parse("bytes 1000-1999/2000"))
        assertEquals(ContentRange(0, 9, 10), ContentRange.parse("  Bytes 0-9/10 "))
    }

    @Test
    fun `Content-Range absent incoherent ou de taille inconnue est refuse`() {
        assertNull(ContentRange.parse(null))
        assertNull(ContentRange.parse(""))
        assertNull(ContentRange.parse("bytes 1000-1999/*"))
        assertNull(ContentRange.parse("bytes 2000-1999/3000"))
        assertNull(ContentRange.parse("bytes 0-2000/2000"))
        assertNull(ContentRange.parse("items 0-9/10"))
    }

    @Test
    fun `validateur - ETag fort sinon Last-Modified, jamais un ETag faible`() {
        assertEquals("\"abc\"", PartialDownload.validatorOf("\"abc\"", "Wed, 01 Oct 2026 10:00:00 GMT"))
        assertEquals("Wed, 01 Oct 2026 10:00:00 GMT", PartialDownload.validatorOf("W/\"abc\"", "Wed, 01 Oct 2026 10:00:00 GMT"))
        assertEquals("Wed, 01 Oct 2026 10:00:00 GMT", PartialDownload.validatorOf(null, "Wed, 01 Oct 2026 10:00:00 GMT"))
        assertNull(PartialDownload.validatorOf("W/\"abc\"", null))
        assertNull(PartialDownload.validatorOf(null, null))
    }

    @Test
    fun `un partiel recent avec marqueur est repris a sa taille`() {
        val destination = partial(bytes = 5_000, total = 20_000, ageMs = 60_000)
        assertEquals(5_000L, PartialDownload.usableOffset(destination, now))
        assertEquals(5_000L, PartialDownload.currentOffset(destination))
        assertNotNull(PartialDownload.readMarker(destination))
    }

    @Test
    fun `sans partiel il n y a rien a reprendre`() {
        val destination = destination()
        assertEquals(0L, PartialDownload.usableOffset(destination, now))
        assertEquals(0L, PartialDownload.currentOffset(destination))
    }

    @Test
    fun `un temporaire sans marqueur n est jamais repris et est supprime`() {
        val destination = partial(bytes = 5_000, total = null)
        assertEquals(0L, PartialDownload.currentOffset(destination))
        assertEquals(0L, PartialDownload.usableOffset(destination, now))
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
    }

    @Test
    fun `un partiel au moins aussi gros que la taille totale est abandonne`() {
        val destination = partial(bytes = 5_000, total = 5_000)
        assertEquals(0L, PartialDownload.usableOffset(destination, now))
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertFalse(ModelInstaller.resumeMarkerFor(destination).exists())
    }

    @Test
    fun `un partiel de plus de 7 jours est abandonne`() {
        val stale = partial(bytes = 5_000, total = 20_000, ageMs = PartialDownload.MAX_AGE_MS + 1_000)
        assertEquals(0L, PartialDownload.usableOffset(stale, now))
        assertFalse(ModelInstaller.temporaryFileFor(stale).exists())
    }

    @Test
    fun `un partiel de 6 jours est encore repris`() {
        val recent = partial(bytes = 5_000, total = 20_000, ageMs = PartialDownload.MAX_AGE_MS - 24 * 3_600_000L)
        assertEquals(5_000L, PartialDownload.usableOffset(recent, now))
    }

    @Test
    fun `discard supprime le partiel et le marqueur sans toucher au modele installe`() {
        val destination = partial(bytes = 5_000, total = 20_000)
        destination.writeText("modele deja installe")
        PartialDownload.discard(destination)
        assertTrue(destination.exists())
        assertFalse(ModelInstaller.temporaryFileFor(destination).exists())
        assertFalse(ModelInstaller.resumeMarkerFor(destination).exists())
    }
}
