package fr.junade.taipo.dictionary

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lot 3.3 (T2) : [DatabasePassphraseProvider] avec le vrai Android Keystore. Préférences et alias de
 * test : les vraies clés des bases de l'utilisateur ne sont jamais lues, créées ni supprimées.
 */
@RunWith(AndroidJUnit4::class)
class DatabasePassphraseProviderTest {

    private lateinit var context: Context
    private lateinit var provider: DatabasePassphraseProvider

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        provider = newProvider()
        provider.reset()
    }

    @After
    fun tearDown() {
        provider.reset()
    }

    private fun newProvider() = DatabasePassphraseProvider(context, TEST_PREFS, TEST_ALIAS)

    @Test
    fun la_cle_fait_64_caracteres_hexadecimaux() {
        val key = provider.getOrCreate()
        assertEquals(64, key.size)
        assertTrue(String(key, Charsets.US_ASCII).all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun la_cle_est_stable_d_un_appel_et_d_une_instance_a_l_autre() {
        val first = provider.getOrCreate()
        assertArrayEquals(first, provider.getOrCreate())
        // Une nouvelle instance relit les préférences et déchiffre avec la clé du Keystore.
        assertArrayEquals(first, newProvider().getOrCreate())
    }

    @Test
    fun la_cle_n_est_pas_stockee_en_clair_dans_les_preferences() {
        val key = String(provider.getOrCreate(), Charsets.US_ASCII)
        val stored = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE).all.values.joinToString("|")
        assertTrue("Aucune valeur enregistrée", stored.isNotEmpty())
        assertFalse("La clé apparaît en clair dans les préférences", stored.contains(key))
    }

    @Test
    fun reset_fait_generer_une_nouvelle_cle() {
        val first = provider.getOrCreate()
        provider.reset()
        val second = provider.getOrCreate()
        assertFalse("La clé est identique après reset", first.contentEquals(second))
    }

    private companion object {
        const val TEST_PREFS = "taipo_test_passphrase_prefs"
        const val TEST_ALIAS = "taipo_test_passphrase_alias"
    }
}
