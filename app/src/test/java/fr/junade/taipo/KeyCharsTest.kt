package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lot 20 : chaque signe affiché sur une touche ou dans une bulle d'appui long a un nom que TalkBack peut prononcer. */
class KeyCharsTest {

    private val allLayouts = listOf(Keyboards.letters, Keyboards.lettersEn, Keyboards.symbols)

    private fun typedChars(): Set<Char> = allLayouts.flatMap { it.rows.flatten() }.flatMap { key ->
        val own = (key.action as? KeyAction.TypeChar)?.char
        listOfNotNull(own, key.longPressChar) + key.popup.flatten()
    }.toSet()

    @Test
    fun `tout signe de ponctuation ou symbole des claviers a un nom parle`() {
        val missing = typedChars().filterNot { KeyChars.isSelfSpoken(it) || KeyChars.nameRes(it) != null }
        assertTrue(missing.isEmpty(), "Signes sans nom parlé pour TalkBack : $missing")
    }

    @Test
    fun `lettres et chiffres se lisent tels quels`() {
        "abcxyzé0159".forEach {
            assertTrue(KeyChars.isSelfSpoken(it))
            assertNull(KeyChars.nameRes(it))
        }
    }

    @Test
    fun `les signes courants sont nommes`() {
        listOf(',', '.', '\'', '@', '/', '-', '_', '!', '?', '€', '(', ')').forEach { assertNotNull(KeyChars.nameRes(it), "« $it »") }
        assertFalse(KeyChars.isSelfSpoken('.'))
    }

    @Test
    fun `deux signes differents n ont pas le meme nom`() {
        val ids = typedChars().mapNotNull { c -> KeyChars.nameRes(c)?.let { c to it } }
        // L'apostrophe droite et l'apostrophe typographique partagent volontairement leur nom.
        val byId = ids.filterNot { it.first == '\u2019' }.groupBy({ it.second }, { it.first })
        byId.values.forEach { assertEquals(1, it.size, "Même nom pour : $it") }
    }
}
