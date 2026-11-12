package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Étape « Autorisations » de l'accueil : autorisations demandées selon la version d'Android. */
class AppPermissionTest {

    @Test
    fun `pas de notifications a demander avant Android 13`() {
        assertEquals(
            listOf(AppPermission.MICROPHONE, AppPermission.SCREENSHOTS, AppPermission.USER_DICTIONARY),
            AppPermission.applicable(32),
        )
        assertEquals(AppPermission.entries.toList(), AppPermission.applicable(33))
    }

    @Test
    fun `captures selon la version d'Android`() {
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), AppPermission.SCREENSHOTS.manifestNames(32))
        assertEquals(listOf("android.permission.READ_MEDIA_IMAGES"), AppPermission.SCREENSHOTS.manifestNames(33))
        assertEquals(
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"),
            AppPermission.SCREENSHOTS.manifestNames(34),
        )
        // L'accès complet décide, pas l'accès partiel (audit UX-04).
        assertEquals("android.permission.READ_MEDIA_IMAGES", AppPermission.SCREENSHOTS.grantingName(34))
    }

    @Test
    fun `demande groupee des autorisations manquantes`() {
        assertArrayEquals(
            arrayOf("android.permission.RECORD_AUDIO", "android.permission.READ_USER_DICTIONARY"),
            AppPermission.toRequest(listOf(AppPermission.MICROPHONE, AppPermission.USER_DICTIONARY), 34),
        )
    }

    @Test
    fun `etape faite quand tout est accorde ou que l'utilisateur a repondu`() {
        val all = AppPermission.applicable(34).toSet()
        assertTrue(AppPermission.stepDone(all, 34, answered = false))
        assertFalse(AppPermission.stepDone(setOf(AppPermission.MICROPHONE), 34, answered = false))
        assertTrue(AppPermission.stepDone(emptySet(), 34, answered = true))
        // Avant Android 13, les notifications ne manquent pas.
        assertTrue(AppPermission.stepDone(all - AppPermission.NOTIFICATIONS, 32, answered = false))
    }

    @Test
    fun `chaque autorisation demandee est declaree dans le manifeste`() {
        val manifest = listOf(java.io.File("src/main/AndroidManifest.xml"), java.io.File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        for (sdk in listOf(26, 33, 34)) {
            for (name in AppPermission.toRequest(AppPermission.applicable(sdk), sdk)) {
                assertTrue("\"$name\"" in manifest, "$name absente du manifeste")
            }
        }
    }
}
