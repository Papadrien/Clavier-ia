package fr.junade.taipo

import android.text.InputType
import android.view.inputmethod.EditorInfo
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.testing.TestField
import fr.junade.taipo.testing.fake
import fr.junade.taipo.testing.testContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Audit TEST-01 : [FieldEditor] sur un vrai champ Android (Robolectric). Vérifie en particulier la touche Entrée (audit
 * BUG-01) : l'action de l'éditeur arrive à l'application, sans touche simulée ni retour à la ligne.
 */
@RunWith(RobolectricTestRunner::class)
class FieldEditorTest {

    private val edits = mutableListOf<Pair<String, String>>()
    private val deletions = mutableListOf<String>()
    private val sentKeys = mutableListOf<Int>()
    private var selection = -1 to -1

    private fun editorFor(field: TestField): FieldEditor {
        val correction = CorrectionAiController(
            testContext,
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            { error("moteur IA non prévu") as CorrectionEngine },
            fake(CorrectionAiController.Host::class),
        )
        val ime = fake(
            ImeHostContext::class,
            "inputConnection" to { _ -> field.connection },
            "editorInfo" to { _ -> field.info },
            "getCorrection" to { _ -> correction },
            "getLastSelectionStart" to { _ -> selection.first },
            "getLastSelectionEnd" to { _ -> selection.second },
            "recordUndoEdit" to { args -> edits += (args[0] as String) to (args[1] as String) },
            "recordUndoDeletion" to { args -> deletions += args[0] as String },
        )
        return FieldEditor(ime) { keyCode -> sentKeys += keyCode }
    }

    @Test
    fun `Entree execute l action Rechercher du champ`() {
        val actions = mutableListOf<Int>()
        val field = TestField {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                actions += actionId
                true
            }
        }
        field.setText("chaussures")
        editorFor(field).pressEnter()
        assertEquals(listOf(EditorInfo.IME_ACTION_SEARCH), actions)
        assertEquals("chaussures", field.text) // aucun retour à la ligne
        assertTrue(sentKeys.isEmpty()) // aucune touche simulée
    }

    @Test
    fun `Entree execute une action personnalisee`() {
        val actions = mutableListOf<Int>()
        val field = TestField {
            inputType = InputType.TYPE_CLASS_TEXT
            setImeActionLabel("Publier", CUSTOM_ACTION)
            setOnEditorActionListener { _, actionId, _ ->
                actions += actionId
                true
            }
        }
        editorFor(field).pressEnter()
        assertEquals(listOf(CUSTOM_ACTION), actions)
    }

    @Test
    fun `Entree passe a la ligne dans un champ multiligne`() {
        val field = TestField {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        field.setText("Bonjour")
        editorFor(field).pressEnter()
        assertEquals("Bonjour\n", field.text)
        assertTrue(sentKeys.isEmpty())
    }

    @Test
    fun `le retour arriere efface un emoji entier`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("ok 👍🏽")
        editorFor(field).deleteLastCluster()
        assertEquals("ok ", field.text)
        assertEquals(listOf("👍🏽"), deletions)
    }

    @Test
    fun `une selection est effacee d un coup et enregistree pour l annulation`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("un deux trois")
        field.select(3, 8)
        selection = 3 to 8
        assertTrue(editorFor(field).deleteSelectedText())
        assertEquals("un trois", field.text)
        assertEquals(listOf("deux "), deletions)
    }

    @Test
    fun `remplacer le texte du champ garde l original pour l annulation`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("Bonjuor a tous", cursor = 4)
        assertTrue(editorFor(field).replaceFieldText("Bonjour à tous"))
        assertEquals("Bonjour à tous", field.text)
        assertEquals(listOf("Bonjour à tous" to "Bonjuor a tous"), edits)
    }

    @Test
    fun `le texte genere s ajoute apres la selection sans la remplacer`() {
        val field = TestField { inputType = InputType.TYPE_CLASS_TEXT }
        field.setText("Merci.")
        field.select(0, 6)
        selection = 0 to 6
        assertTrue(editorFor(field).insertGeneratedText(" À bientôt."))
        assertEquals("Merci. À bientôt.", field.text)
    }

    private companion object {
        const val CUSTOM_ACTION = 42
    }
}
