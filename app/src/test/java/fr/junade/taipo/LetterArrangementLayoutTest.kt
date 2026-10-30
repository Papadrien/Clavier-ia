package fr.junade.taipo

import fr.junade.taipo.dictionary.KeyProximityFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Disposition des lettres choisie pour une langue (écran « Langues » > détail d'une langue). */
class LetterArrangementLayoutTest {

    private fun letters(
        language: KeyboardLanguage,
        arrangement: LetterArrangement,
        numberRow: Boolean = false,
    ) = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow, arrangement = arrangement)

    private fun chars(row: List<Key>): String = row.mapNotNull { (it.action as? KeyAction.TypeChar)?.char }.joinToString("")

    private val combinations = KeyboardLanguage.entries.flatMap { language -> LetterArrangement.entries.map { language to it } }

    @Test
    fun `la disposition d origine de chaque langue est inchangee`() {
        assertEquals(Keyboards.letters, letters(KeyboardLanguage.FR, LetterArrangement.AZERTY))
        assertEquals(Keyboards.lettersEn, letters(KeyboardLanguage.EN, LetterArrangement.QWERTY))
        KeyboardLanguage.entries.forEach { language ->
            val origin = LetterArrangement.defaultFor(language)
            assertEquals(Keyboards.layoutOf(LayoutId.LETTERS, language), letters(language, origin), "$language")
            assertEquals(
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = true),
                letters(language, origin, numberRow = true),
                "$language avec rangée de chiffres",
            )
        }
    }

    @Test
    fun `toutes les combinaisons langue et disposition ont les quatre rangees et des ids uniques`() {
        combinations.forEach { (language, arrangement) ->
            val plain = letters(language, arrangement)
            val numbered = letters(language, arrangement, numberRow = true)
            assertEquals(4, plain.rows.size, "$language $arrangement")
            assertEquals(5, numbered.rows.size, "$language $arrangement")
            val ids = plain.rows.flatten().map { it.id }
            assertEquals(ids.size, ids.distinct().size, "ids en double : $language $arrangement")
            assertEquals(10f, plain.referenceUnits, 0.001f, "largeur de référence : $language $arrangement")
            assertTrue(plain.rows.all { rowUnits(it) <= 10.001f }, "rangée trop large : $language $arrangement")
            assertTrue(plain.rows.flatten().all { it.weight > 0f })
        }
    }

    @Test
    fun `chaque disposition contient les 26 lettres une seule fois`() {
        combinations.forEach { (language, arrangement) ->
            val all = letters(language, arrangement).rows.flatMap { row -> chars(row).filter { it in 'a'..'z' }.toList() }
            assertEquals(('a'..'z').toList(), all.sorted(), "$language $arrangement")
        }
    }

    @Test
    fun `l apercu de chaque disposition est celui des vraies touches`() {
        LetterArrangement.entries.forEach { arrangement ->
            val rows = letters(KeyboardLanguage.EN, arrangement).rows.take(3)
            arrangement.previewRows.forEachIndexed { index, preview ->
                assertEquals(
                    preview.filter { it.isLetter() },
                    chars(rows[index]).filter { it.isLetter() },
                    "$arrangement, rangée ${index + 1}",
                )
            }
        }
    }

    @Test
    fun `les rangees des dispositions sont celles attendues`() {
        assertEquals("qwertyuiop", chars(letters(KeyboardLanguage.FR, LetterArrangement.QWERTY).rows[0]))
        assertEquals("qsdfghjklm", chars(letters(KeyboardLanguage.EN, LetterArrangement.AZERTY).rows[1]))
        assertEquals("qwertzuiop", chars(letters(KeyboardLanguage.EN, LetterArrangement.QWERTZ).rows[0]))
        assertEquals("asdfghjklñ", chars(letters(KeyboardLanguage.EN, LetterArrangement.QWERTY_N).rows[1]))
        assertEquals("qwfpgjluy'", chars(letters(KeyboardLanguage.EN, LetterArrangement.COLEMAK).rows[0]))
        assertEquals("arstdhneio", chars(letters(KeyboardLanguage.EN, LetterArrangement.COLEMAK).rows[1]))
    }

    @Test
    fun `sans rangee de chiffres les dix touches du haut saisissent 1 a 0 par appui long`() {
        combinations.forEach { (language, arrangement) ->
            val top = letters(language, arrangement).rows.first()
            assertEquals(10, top.size, "$language $arrangement")
            assertEquals("1234567890".toList(), top.map { it.longPressChar }, "$language $arrangement")
        }
    }

    @Test
    fun `les accents de la langue suivent la disposition choisie`() {
        val germanAzerty = letters(KeyboardLanguage.DE, LetterArrangement.AZERTY).rows.flatten().first { it.id == "letter_a" }
        assertEquals('ä', germanAzerty.defaultPopupChar)
        val frenchColemak = letters(KeyboardLanguage.FR, LetterArrangement.COLEMAK).rows.flatten().first { it.id == "letter_e" }
        assertEquals('é', frenchColemak.defaultPopupChar)
    }

    @Test
    fun `l espagnol garde le n tilde sur toutes les dispositions`() {
        LetterArrangement.entries.forEach { arrangement ->
            val keys = letters(KeyboardLanguage.ES, arrangement).rows.flatten()
            val reachable = keys.any { (it.action as? KeyAction.TypeChar)?.char == 'ñ' } ||
                keys.first { it.id == "letter_n" }.popup.flatten().contains('ñ')
            assertTrue(reachable, "ñ inaccessible en espagnol sur $arrangement")
        }
    }

    @Test
    fun `l espagnol garde les points d interrogation et d exclamation inverses sur le point`() {
        LetterArrangement.entries.forEach { arrangement ->
            val period = letters(KeyboardLanguage.ES, arrangement).rows.last().first { it.id == "period" }
            assertTrue(period.popup.flatten().containsAll(listOf('¿', '¡')), "$arrangement")
        }
        val englishPeriod = letters(KeyboardLanguage.EN, LetterArrangement.QWERTY).rows.last().first { it.id == "period" }
        assertFalse('¿' in englishPeriod.popup.flatten())
    }

    @Test
    fun `les pages de symboles ignorent la disposition`() {
        LetterArrangement.entries.forEach { arrangement ->
            assertEquals(Keyboards.symbols, Keyboards.layoutOf(LayoutId.SYMBOLS, KeyboardLanguage.FR, arrangement = arrangement))
            assertEquals(Keyboards.symbolsMore, Keyboards.layoutOf(LayoutId.SYMBOLS_2, KeyboardLanguage.FR, arrangement = arrangement))
        }
    }

    @Test
    fun `les variantes de champ gardent la disposition choisie`() {
        val colemakEmail = Keyboards.layoutOf(
            LayoutId.LETTERS,
            KeyboardLanguage.EN,
            fieldType = FieldType.EMAIL,
            arrangement = LetterArrangement.COLEMAK,
        )
        assertEquals("qwfpgjluy'", chars(colemakEmail.rows[0]))
        assertTrue(colemakEmail.rows.flatten().any { it.id == "email_at" })
        val qwertyEmail = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.EN, fieldType = FieldType.EMAIL)
        assertNotEquals(colemakEmail, qwertyEmail)
    }

    @Test
    fun `la proximite des touches suit la disposition choisie`() {
        val azertyFrench = KeyProximityFactory.forLanguage(KeyboardLanguage.FR)
        val qwertyFrench = KeyProximityFactory.forLanguage(KeyboardLanguage.FR, LetterArrangement.QWERTY)
        assertTrue(azertyFrench.areNeighbors('a', 'z'))
        assertTrue(qwertyFrench.areNeighbors('q', 'w'))
        // A et W ne se touchent pas en AZERTY (deux rangées d'écart) mais sont voisines en QWERTY (W au-dessus-droite de A).
        assertFalse(azertyFrench.areNeighbors('a', 'w'))
        assertTrue(qwertyFrench.areNeighbors('a', 'w'))
        val colemak = KeyProximityFactory.forLanguage(KeyboardLanguage.EN, LetterArrangement.COLEMAK)
        assertTrue(colemak.areNeighbors('a', 'r'))
        assertFalse(colemak.areNeighbors('a', 's'))
    }
}
