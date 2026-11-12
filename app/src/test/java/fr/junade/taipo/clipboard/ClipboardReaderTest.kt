package fr.junade.taipo.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import fr.junade.taipo.testing.testContext
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

/** Audit SEC-01, PERF-02 et TEST-01 : lecture du presse-papiers du système par [ClipboardReader]. */
@RunWith(RobolectricTestRunner::class)
class ClipboardReaderTest {

    private val manager = testContext.getSystemService(ClipboardManager::class.java)
    private val reader = ClipboardReader(testContext) {}

    @Test
    fun `une copie de texte est lue`() {
        manager.setPrimaryClip(ClipData.newPlainText("note", "Bonjour"))
        val snapshot = checkNotNull(reader.read())
        assertEquals("Bonjour", snapshot.text)
        assertFalse(snapshot.sensitive)
    }

    @Test
    fun `une copie signalee sensible par l application est reconnue`() {
        val clip = ClipData.newPlainText("mot de passe", "s3cr3t!")
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        manager.setPrimaryClip(clip)
        assertTrue(checkNotNull(reader.read()).sensitive)
    }

    @Test
    fun `une copie qui n est pas du texte est ignoree`() {
        manager.setPrimaryClip(ClipData("image", arrayOf("image/png"), ClipData.Item(android.net.Uri.parse("content://x/1"))))
        assertNull(reader.read())
    }

    @Test
    fun `une nouvelle copie de meme libelle est relue quand le temps passe`() {
        manager.setPrimaryClip(ClipData.newPlainText("note", "premier"))
        assertEquals("premier", reader.read()?.text)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        manager.setPrimaryClip(ClipData.newPlainText("note", "second"))
        assertEquals("second", reader.read()?.text)
    }

    @Test
    fun `une nouvelle copie remplace la precedente`() {
        manager.setPrimaryClip(ClipData.newPlainText("a", "premier"))
        assertEquals("premier", reader.read()?.text)
        manager.setPrimaryClip(ClipData.newPlainText("b", "second"))
        assertEquals("second", reader.read()?.text)
    }
}
