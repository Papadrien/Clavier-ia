package fr.junade.taipo

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 10.1 : quels champs sont sensibles (actions IA et Smart Clipboard grisés). */
class SensitiveFieldPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `les champs de mot de passe sont sensibles`() {
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        ).forEach { inputType -> assertTrue(SensitiveFieldPolicy.isSensitive(inputType), "inputType $inputType") }
    }

    @Test
    fun `les champs normaux ne sont pas sensibles`() {
        listOf(
            text,
            text or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_NULL,
        ).forEach { inputType -> assertFalse(SensitiveFieldPolicy.isSensitive(inputType), "inputType $inputType") }
    }

    @Test
    fun `un drapeau de texte n'est pas confondu avec un mot de passe numerique`() {
        // TYPE_NUMBER_VARIATION_PASSWORD et TYPE_TEXT_VARIATION_PASSWORD ont des valeurs différentes : chaque classe lit la sienne.
        assertFalse(SensitiveFieldPolicy.isSensitive(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD))
    }
}
