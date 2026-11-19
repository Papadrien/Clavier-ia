package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.testing.testContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit UX-01 et TEST-01 : sans liste téléchargée, le clavier corrige avec la liste de démarrage embarquée (assets réels de
 * l'app, lus par Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class StarterDictionaryLoadTest {

    @Test
    fun `la liste de demarrage sert tant que la liste complete manque`() {
        assertFalse(DictionaryFiles.isInstalled(testContext.filesDir, KeyboardLanguage.FR))
        val dictionary = DictionaryLoader.forLanguage(testContext, KeyboardLanguage.FR)
        assertTrue(dictionary.frequencyOf("bonjour") > 0)
        assertNotNull(DictionaryLoader.peek(KeyboardLanguage.FR))
        assertFalse(DictionaryLoader.isFullyLoaded(KeyboardLanguage.FR)) // la liste complète la remplacera
    }

    @Test
    fun `chaque langue a sa liste de demarrage`() {
        // 19/11/2026 : l'espagnol, l'allemand, l'italien, le portugais et le néerlandais n'en avaient pas (aucune correction avant la
        // fin du téléchargement) ; toutes les langues en ont désormais une.
        for (language in KeyboardLanguage.entries) {
            assertTrue(language.tag, DictionaryLoader.loadStarter(testContext, language))
            assertNotNull(language.tag, DictionaryLoader.peek(language))
        }
        assertTrue(DictionaryLoader.forLanguage(testContext, KeyboardLanguage.DE).frequencyOf("hallo") > 0L)
        assertTrue(DictionaryLoader.forLanguage(testContext, KeyboardLanguage.ES).frequencyOf("gracias") > 0L)
    }
}
