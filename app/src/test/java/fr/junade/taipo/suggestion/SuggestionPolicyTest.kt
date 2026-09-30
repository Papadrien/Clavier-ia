package fr.junade.taipo.suggestion

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `texte libre, multiligne et a majuscule automatique sont autorises`() {
        assertTrue(SuggestionPolicy.allowsSuggestions(text))
        assertTrue(SuggestionPolicy.allowsSuggestions(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(SuggestionPolicy.allowsSuggestions(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES))
        assertTrue(SuggestionPolicy.allowsSuggestions(text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(SuggestionPolicy.allowsSuggestions(text or InputType.TYPE_TEXT_VARIATION_NORMAL))
    }

    @Test
    fun `mots de passe, e-mail, URL et filtre sont exclus`() {
        listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        ).forEach { variation ->
            assertFalse(SuggestionPolicy.allowsSuggestions(text or variation), "variation $variation")
        }
    }

    @Test
    fun `l application peut refuser les suggestions`() {
        assertFalse(SuggestionPolicy.allowsSuggestions(text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun `champs non textuels et absence de type sont exclus`() {
        assertFalse(SuggestionPolicy.allowsSuggestions(InputType.TYPE_CLASS_NUMBER))
        assertFalse(SuggestionPolicy.allowsSuggestions(InputType.TYPE_CLASS_PHONE))
        assertFalse(SuggestionPolicy.allowsSuggestions(InputType.TYPE_CLASS_DATETIME))
        assertFalse(SuggestionPolicy.allowsSuggestions(InputType.TYPE_NULL))
    }
}
