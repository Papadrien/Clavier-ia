package fr.junade.taipo

import android.text.InputType
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.testing.TestField
import fr.junade.taipo.testing.fake
import fr.junade.taipo.testing.testContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit TEST-01 : ce que la correction IA ([CorrectionAiController]) lit du champ avant d'appeler le modèle, et le suivi de
 * ses propres modifications (sans moteur : le modèle n'est pas chargé dans ces tests).
 */
@RunWith(RobolectricTestRunner::class)
class CorrectionCaptureTest {

    private fun controller() = CorrectionAiController(
        testContext,
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        { error("moteur IA non prévu") as CorrectionEngine },
        fake(CorrectionAiController.Host::class),
    )

    @Test
    fun `le texte du champ et la position du curseur sont captures`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        field.setText("Bonjour à tous.\nÇa va ?", cursor = 7)
        val captured = checkNotNull(controller().captureFieldText(field.connection))
        assertEquals("Bonjour à tous.\nÇa va ?", captured.text)
        assertEquals(7, captured.beforeCursor)
        assertEquals(captured.text.length - 7, captured.afterCursor)
        assertEquals(0, captured.startOffset)
    }

    @Test
    fun `une sélection est captee avec le texte qui l entoure`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("un deux trois")
        field.select(3, 7)
        val captured = checkNotNull(controller().captureFieldText(field.connection))
        assertEquals(3, captured.beforeCursor)
        assertEquals(6, captured.afterCursor) // « trois » et l'espace qui le précède
    }

    @Test
    fun `ses propres modifications sont reconnues un court instant`() {
        val correction = controller()
        assertFalse(correction.isSelfEditEcho())
        correction.markSelfEdit()
        assertTrue(correction.isSelfEditEcho())
    }

    @Test
    fun `la selection suivie est celle que signale le champ`() {
        val correction = controller()
        correction.onSelectionUpdated(4, 9)
        assertEquals(4, correction.lastSelectionStart)
        assertEquals(9, correction.lastSelectionEnd)
    }
}
