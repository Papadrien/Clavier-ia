package fr.junade.taipo.emoji

import android.text.InputType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessagingFieldPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun `une variation message court ou long est un champ de messagerie`() {
        assertTrue(MessagingFieldPolicy.isMessagingField("com.exemple.inconnue", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE))
        assertTrue(MessagingFieldPolicy.isMessagingField(null, text or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE))
    }

    @Test
    fun `une messagerie connue est reconnue meme avec un champ de texte ordinaire`() {
        assertTrue(MessagingFieldPolicy.isMessagingField("com.whatsapp", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertTrue(MessagingFieldPolicy.isMessagingField("org.telegram.messenger", text))
    }

    @Test
    fun `l indicateur NO_SUGGESTIONS n empeche pas la barre`() {
        assertTrue(MessagingFieldPolicy.isMessagingField("com.whatsapp", text or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS))
    }

    @Test
    fun `une autre application avec un champ ordinaire n est pas une messagerie`() {
        assertFalse(MessagingFieldPolicy.isMessagingField("com.google.android.keep", text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(MessagingFieldPolicy.isMessagingField(null, text))
    }

    @Test
    fun `les champs sans texte libre sont exclus meme dans une messagerie`() {
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_URI,
            text or InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_PHONE,
        ).forEach { assertFalse(MessagingFieldPolicy.isMessagingField("com.whatsapp", it), "inputType $it") }
    }
}
