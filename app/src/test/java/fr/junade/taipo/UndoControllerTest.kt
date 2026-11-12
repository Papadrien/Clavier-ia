package fr.junade.taipo

import android.text.InputType
import fr.junade.taipo.testing.TestField
import fr.junade.taipo.testing.fake
import fr.junade.taipo.testing.testContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Audit TEST-01 : le bouton Annuler ([UndoController]) sur un vrai champ Android. */
@RunWith(RobolectricTestRunner::class)
class UndoControllerTest {

    private val messages = mutableListOf<String>()
    private val availability = mutableListOf<Boolean>()

    private fun controllerFor(field: TestField) = UndoController(
        testContext,
        fake(
            UndoController.Host::class,
            "inputConnection" to { _ -> field.connection },
            "showMessage" to { args -> messages += args[0] as String },
            "onUndoAvailabilityChanged" to { args -> availability += args[0] as Boolean },
        ),
    )

    @Test
    fun `annuler une autocorrection retablit le mot tape`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("Je dis bonjour ")
        val undo = controllerFor(field)
        undo.recordEdit(inserted = "bonjour ", original = "bonjuor ")
        assertTrue(undo.canUndo)
        undo.onUndoClicked()
        assertEquals("Je dis bonjuor ", field.text)
        assertFalse(undo.canUndo)
        assertEquals(listOf(true, false), availability)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `un texte disparu du champ n est pas annule et l utilisateur est prevenu`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("bonjour ")
        val undo = controllerFor(field)
        undo.recordEdit(inserted = "bonjour ", original = "bonjuor ")
        field.setText("autre chose")
        undo.onUndoClicked()
        assertEquals("autre chose", field.text)
        assertEquals(listOf(testContext.getString(R.string.undo_failed)), messages)
    }

    @Test
    fun `effacer l historique grise le bouton`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        val undo = controllerFor(field)
        undo.recordEdit(inserted = "a", original = "")
        undo.clear()
        assertFalse(undo.canUndo)
        assertEquals(listOf(true, false), availability)
    }
}
