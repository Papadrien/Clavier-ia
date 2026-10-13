package fr.junade.taipo

import fr.junade.taipo.dictionary.KeyProximityFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Toutes les touches de lettres ont la même largeur, dans toutes les langues (QWERTY, QWERTZ, AZERTY). */
class KeyboardKeyWidthTest {

    private val allLetterLayouts: List<Pair<String, KeyboardLayout>> = KeyboardLanguage.entries.flatMap { language ->
        listOf(false, true).map { numberRow -> "$language numberRow=$numberRow" to Keyboards.lettersOf(language, numberRow) }
    }

    private fun isLetterKey(key: Key): Boolean = (key.action as? KeyAction.TypeChar)?.char?.isLetter() == true

    @Test
    fun `toutes les lettres pesent une unite et la reference est de 10 unites`() {
        allLetterLayouts.forEach { (name, layout) ->
            assertEquals(10f, layout.referenceUnits, 0.001f, name)
            layout.rows.flatten().filter(::isLetterKey).forEach { key ->
                assertEquals(1f, key.weight, "$name : poids de ${key.id}")
            }
        }
    }

    @Test
    fun `aucune rangee ne depasse la reference et les rangees courtes sont centrees`() {
        allLetterLayouts.forEach { (name, layout) ->
            layout.rows.forEach { row ->
                val units = rowUnits(row)
                assertTrue(units <= layout.referenceUnits + 0.001f, "$name : rangée trop large")
                // Marge à gauche + rangée + marge à droite = référence.
                assertEquals(layout.referenceUnits, layout.rowStartUnits(row) * 2f + units, 0.001f, name)
            }
        }
    }

    @Test
    fun `la rangee de 9 lettres du QWERTY et du QWERTZ est centree avec une demi touche de marge`() {
        listOf(KeyboardLanguage.EN, KeyboardLanguage.DE, KeyboardLanguage.IT, KeyboardLanguage.PT, KeyboardLanguage.NL)
            .forEach { language ->
                val layout = Keyboards.lettersOf(language)
                val row = layout.rows[1]
                assertEquals(9, row.size, "$language")
                assertEquals(0.5f, layout.rowStartUnits(row), 0.001f, "$language")
            }
    }

    @Test
    fun `les rangees completes ne sont pas decalees`() {
        listOf(KeyboardLanguage.FR, KeyboardLanguage.ES).forEach { language ->
            val layout = Keyboards.lettersOf(language)
            layout.rows.forEach { assertEquals(0f, layout.rowStartUnits(it), 0.001f, "$language") }
        }
    }

    @Test
    fun `les pages de symboles et les paves gardent leur largeur`() {
        listOf(Keyboards.symbols, Keyboards.symbolsMore).forEach { layout ->
            assertEquals(10f, layout.referenceUnits, 0.001f)
            layout.rows.forEach { assertEquals(0f, layout.rowStartUnits(it), 0.001f) }
        }
        val pad = Keyboards.layoutOf(LayoutId.PAD, KeyboardLanguage.FR, fieldType = FieldType.NUMBER)
        pad.rows.forEach { assertEquals(0f, pad.rowStartUnits(it), 0.001f) }
    }

    @Test
    fun `la proximite des touches suit la rangee centree`() {
        val de = KeyProximityFactory.forLanguage(KeyboardLanguage.DE)
        // « a » est sous « q » et « w » (rangée décalée d'une demi-touche), pas sous « e ».
        assertTrue(de.areNeighbors('a', 'q'))
        assertTrue(de.areNeighbors('a', 'w'))
        assertFalse(de.areNeighbors('a', 'e'))
        // Rangée du bas : « y » est sous « s ».
        assertTrue(de.areNeighbors('y', 's'))
    }
}
