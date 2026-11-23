package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Contexte de la reformulation de la Transcription IA (23/11/2026). */
class DictationContextTest {

    @Test
    fun `type de champ`() {
        assertEquals(DictationContext.FieldKind.MESSAGE, DictationContext.fieldKind("com.whatsapp", 0, messagingField = true))
        assertEquals(DictationContext.FieldKind.EMAIL, DictationContext.fieldKind("com.google.android.gm", 0x1, messagingField = false))
        assertEquals(DictationContext.FieldKind.EMAIL, DictationContext.fieldKind("autre.app", 0x31, messagingField = false))
        assertEquals(DictationContext.FieldKind.OTHER, DictationContext.fieldKind("autre.app", 0x1, messagingField = false))
    }

    @Test
    fun `fin du texte coupee a un debut de mot`() {
        assertEquals("court", DictationContext.tail("  court  "))
        val long = "a".repeat(10) + " " + "mot ".repeat(100)
        val tail = DictationContext.tail(long, max = 20)
        assertTrue(tail.length <= 20)
        assertTrue(tail.startsWith("mot"))
    }

    @Test
    fun `mots personnels proches du texte dicte`() {
        val personal = listOf("Ritsanko", "Junadé", "Taipo", "Zébulon", "OK")
        val names = DictationContext.relevantNames(personal, "Dis à rit sanko que junade a fini taypo")
        assertEquals(listOf("Ritsanko", "Junadé", "Taipo"), names)
    }

    @Test
    fun `le texte deja reformule prolonge le contexte`() {
        val context = DictationContext(textBefore = "Salut,", kind = DictationContext.FieldKind.MESSAGE)
        assertEquals("Salut, Je arrive.", context.continuedBy("Je arrive.").textBefore)
        assertEquals(context, context.continuedBy(""))
    }

    @Test
    fun `le prompt porte le ton, les noms et le texte precedent`() {
        val turn = TranscriptionPrompt.dictationUserTurn(
            "on se voit demain",
            DictationContext("Salut Léa,", DictationContext.FieldKind.MESSAGE, listOf("Léa")),
        )
        assertTrue(turn.contains("chat message"))
        assertTrue(turn.contains("Léa."))
        assertTrue(turn.contains("Salut Léa,"))
        assertTrue(turn.endsWith("Dictated text:\non se voit demain"))
    }
}
