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
    fun `une langue sans liste de demarrage reste vide`() {
        assertFalse(DictionaryLoader.loadStarter(testContext, KeyboardLanguage.DE))
        assertTrue(DictionaryLoader.forLanguage(testContext, KeyboardLanguage.DE).frequencyOf("hallo") == 0L)
    }
}
