package fr.junade.taipo

import android.content.ComponentCallbacks2
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("DEPRECATION")
class MemoryReleaseTest {

    @Test
    fun `memoire critique ou processus en arriere-plan - toujours libere`() {
        for (shown in listOf(true, false)) {
            assertTrue(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, shown))
            assertTrue(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, shown))
            assertTrue(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_COMPLETE, shown))
        }
    }

    @Test
    fun `memoire basse - libere seulement si le clavier est masque`() {
        assertTrue(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, keyboardShown = false))
        assertFalse(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, keyboardShown = true))
    }

    @Test
    fun `ecrans masques ou pression moderee - rien n'est libere`() {
        for (shown in listOf(true, false)) {
            assertFalse(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, shown))
            assertFalse(MemoryRelease.shouldRelease(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE, shown))
        }
    }
}
