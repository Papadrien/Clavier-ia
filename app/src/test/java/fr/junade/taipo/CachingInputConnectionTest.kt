package fr.junade.taipo

import android.text.InputFilter
import android.text.InputType
import fr.junade.taipo.testing.TestField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit BUG-05 et TEST-01 : le cache des lectures du champ ([InputTextCache] derrière [CachingInputConnection]) sur un vrai
 * champ Android. Le cas qui comptait : l'éditeur refuse une partie du texte (longueur maximale) sans que le cache le sache.
 */
@RunWith(RobolectricTestRunner::class)
class CachingInputConnectionTest {

    private var now = 0L

    private fun cached(field: TestField): CachingInputConnection {
        val cache = InputTextCache({ now })
        cache.reset(field.view.selectionStart, field.view.selectionEnd) // comme onStartInput
        return CachingInputConnection(field.connection, cache)
    }

    @Test
    fun `les lectures en cache suivent les ecritures du clavier`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("Bonjour")
        val ic = cached(field)
        assertEquals("Bonjour", ic.getTextBeforeCursor(20, 0).toString())
        ic.commitText(" à tous", 1)
        assertEquals("Bonjour à tous", ic.getTextBeforeCursor(20, 0).toString())
        ic.deleteSurroundingText(4, 0)
        assertEquals("Bonjour à ", ic.getTextBeforeCursor(20, 0).toString())
        assertEquals(field.text, ic.getTextBeforeCursor(20, 0).toString())
    }

    @Test
    fun `un texte refuse par l editeur est detecte avant d effacer`() {
        val field = TestField {
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(5))
        }
        field.setText("abc")
        val ic = cached(field)
        ic.getTextBeforeCursor(20, 0) // remplit le cache
        ic.commitText("def", 1) // l'éditeur ne garde que « de »
        assertEquals("abcde", field.text)
        // Le cache croit encore à « abcdef » : la vérification sans cache le dément et vide le cache.
        assertFalse(ic.confirmBefore("abcdef"))
        assertEquals("abcde", ic.getTextBeforeCursor(20, 0).toString())
        assertTrue(ic.confirmBefore("cde"))
    }

    @Test
    fun `le cache expire`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("un")
        val ic = cached(field)
        assertEquals("un", ic.getTextBeforeCursor(20, 0).toString())
        field.setText("deux") // modifié par l'application, sans passer par le clavier
        now += InputTextCache.TTL_MS + 1
        assertEquals("deux", ic.getTextBeforeCursor(20, 0).toString())
    }
}
