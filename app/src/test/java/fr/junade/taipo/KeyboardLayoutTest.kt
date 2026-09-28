package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardLayoutTest {

    private val layouts = listOf(Keyboards.letters, Keyboards.lettersEn, Keyboards.symbols)

    @Test
    fun `chaque layout a 4 rangees`() {
        layouts.forEach { layout ->
            assertEquals(4, layout.rows.size, "Layout ${layout.id} doit avoir 4 rangées")
        }
    }

    @Test
    fun `chacune des rangees a au plus 10 touches`() {
        layouts.forEach { layout ->
            layout.rows.forEachIndexed { index, row ->
                assertTrue(row.size <= 10, "Rangée $index de ${layout.id} a ${row.size} touches")
            }
        }
    }

    @Test
    fun `aucune touche ne doit avoir un id vide`() {
        layouts.forEach { layout ->
            layout.rows.flatten().forEach { key ->
                assertTrue(key.id.isNotBlank(), "Touche sans id dans ${layout.id}")
            }
        }
    }

    @Test
    fun `toutes les touches ont un poids positif`() {
        layouts.forEach { layout ->
            layout.rows.flatten().forEach { key ->
                assertTrue(key.weight > 0f, "Poids invalide pour ${key.id} dans ${layout.id}")
            }
        }
    }

    @Test
    fun `le clavier lettres contient exactement les 26 lettres de l alphabet`() {
        val letters = Keyboards.letters.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isLetter() }

        assertEquals(26, letters.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz".toList(), letters.sorted())
    }

    @Test
    fun `le clavier lettres a les touches maj effacer entree et bascule`() {
        val allKeys = Keyboards.letters.rows.flatten()
        assertActionPresent(Keyboards.letters, KeyAction.Shift)
        assertActionPresent(Keyboards.letters, KeyAction.Backspace)
        assertActionPresent(Keyboards.letters, KeyAction.Enter)
        assertActionPresent(Keyboards.letters, KeyAction.ToggleLayout)
        assertTrue(allKeys.count { it.action == KeyAction.Space } == 1)
    }

    @Test
    fun `le clavier symboles a les chiffres 0 a 9`() {
        val digits = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isDigit() }

        assertEquals(('0'..'9').toList(), digits)
    }

    @Test
    fun `le clavier symboles a la ponctuation et les touches effacer entree bascule`() {
        assertActionPresent(Keyboards.symbols, KeyAction.Backspace)
        assertActionPresent(Keyboards.symbols, KeyAction.Enter)
        assertActionPresent(Keyboards.symbols, KeyAction.ToggleLayout)

        val chars = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
        listOf(',', '.', '\'', '(', ')', '&', 'é', 'è', 'à', 'ç').forEach { c ->
            assertTrue(c in chars, "Le caractère $c doit être présent sur le clavier symboles")
        }
    }

    @Test
    fun `le clavier symboles contient les accents et symboles speciaux`() {
        val chars = Keyboards.symbols.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }

        listOf('@', '+', '*', '#', '$', '%', '€', '=', '_', '-').forEach { c ->
            assertTrue(c in chars, "Le caractère $c doit être présent sur le clavier symboles")
        }
    }

    @Test
    fun `le clavier lettres anglais contient exactement les 26 lettres de l alphabet`() {
        val letters = Keyboards.lettersEn.rows.flatten()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .filter { it.isLetter() }

        assertEquals(26, letters.size)
        assertEquals("abcdefghijklmnopqrstuvwxyz".toList(), letters.sorted())
    }

    @Test
    fun `le clavier lettres anglais suit la disposition qwerty et pas azerty`() {
        val topRow = Keyboards.lettersEn.rows.first()
            .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            .joinToString("")

        assertEquals("qwertyuiop", topRow)
    }

    @Test
    fun `le clavier lettres anglais a les touches maj effacer entree et bascule`() {
        val allKeys = Keyboards.lettersEn.rows.flatten()
        assertActionPresent(Keyboards.lettersEn, KeyAction.Shift)
        assertActionPresent(Keyboards.lettersEn, KeyAction.Backspace)
        assertActionPresent(Keyboards.lettersEn, KeyAction.Enter)
        assertActionPresent(Keyboards.lettersEn, KeyAction.ToggleLayout)
        assertTrue(allKeys.count { it.action == KeyAction.Space } == 1)
    }

    @Test
    fun `layoutOf retourne l azerty en francais et le qwerty en anglais`() {
        assertEquals(Keyboards.letters, Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR))
        assertEquals(Keyboards.lettersEn, Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.EN))
        assertEquals(KeyboardLanguage.FR, KeyboardLanguage.entries.first())
    }

    @Test
    fun `layoutOf ignore la langue pour le clavier symboles`() {
        assertEquals(Keyboards.symbols, Keyboards.layoutOf(LayoutId.SYMBOLS, KeyboardLanguage.FR))
        assertEquals(Keyboards.symbols, Keyboards.layoutOf(LayoutId.SYMBOLS, KeyboardLanguage.EN))
    }

    // Story 1.5 : rangée de chiffres activable.

    private val languages = KeyboardLanguage.entries

    private fun lettersWithNumberRow(language: KeyboardLanguage) =
        Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = true)

    @Test
    fun `layoutOf sans rangee de chiffres est le comportement par defaut`() {
        languages.forEach { language ->
            assertEquals(
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false),
                Keyboards.layoutOf(LayoutId.LETTERS, language),
            )
            assertEquals(4, Keyboards.layoutOf(LayoutId.LETTERS, language).rows.size)
        }
    }

    @Test
    fun `la rangee de chiffres ajoute en haut les chiffres 1 a 0 dans l ordre`() {
        languages.forEach { language ->
            val layout = lettersWithNumberRow(language)
            assertEquals(5, layout.rows.size)
            val topRow = layout.rows.first().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            assertEquals("1234567890".toList(), topRow)
        }
    }

    @Test
    fun `la rangee de chiffres laisse les autres rangees inchangees`() {
        languages.forEach { language ->
            // Les chiffres en appui long (story 1.6) disparaissent avec la rangée de chiffres :
            // on compare donc sans eux.
            val baseLayout = Keyboards.layoutOf(LayoutId.LETTERS, language)
            val base = baseLayout.rows.map { row -> row.map { it.copy(longPressChar = null) } }
            assertEquals(base, lettersWithNumberRow(language).rows.drop(1))
            assertEquals(baseLayout.id, lettersWithNumberRow(language).id)
        }
    }

    @Test
    fun `la rangee de chiffres respecte les regles des layouts`() {
        languages.forEach { language ->
            val layout = lettersWithNumberRow(language)
            val ids = layout.rows.flatten().map { it.id }
            assertEquals(ids.size, ids.distinct().size, "Ids en double dans ${layout.id} ($language)")
            assertTrue(layout.rows.all { it.size <= 10 })
            assertTrue(layout.rows.flatten().all { it.weight > 0f })
        }
    }

    @Test
    fun `la rangee de chiffres est sans effet sur le clavier symboles`() {
        languages.forEach { language ->
            assertEquals(
                Keyboards.symbols,
                Keyboards.layoutOf(LayoutId.SYMBOLS, language, numberRow = true),
            )
        }
    }

    // Story 1.6 : rangée de chiffres désactivée → chiffres via `123` ou appui long sur les touches du haut.

    @Test
    fun `sans rangee de chiffres les touches du haut saisissent 1 a 0 par appui long`() {
        languages.forEach { language ->
            val topRow = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false).rows.first()
            assertEquals(10, topRow.size)
            assertEquals("1234567890".toList(), topRow.map { it.longPressChar })
        }
    }

    @Test
    fun `l appui long des chiffres suit la disposition AZERTY et QWERTY`() {
        val fr = Keyboards.letters.rows.first().associate { (it.action as KeyAction.TypeChar).char to it.longPressChar }
        assertEquals('1', fr['a'])
        assertEquals('2', fr['z'])
        assertEquals('0', fr['p'])
        val en = Keyboards.lettersEn.rows.first().associate { (it.action as KeyAction.TypeChar).char to it.longPressChar }
        assertEquals('1', en['q'])
        assertEquals('2', en['w'])
        assertEquals('0', en['p'])
    }

    @Test
    fun `seule la rangee du haut porte des chiffres en appui long`() {
        languages.forEach { language ->
            val layout = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false)
            assertTrue(layout.rows.drop(1).flatten().all { it.longPressChar == null })
        }
    }

    @Test
    fun `avec la rangee de chiffres aucune touche n a de chiffre en appui long`() {
        languages.forEach { language ->
            assertTrue(lettersWithNumberRow(language).rows.flatten().all { it.longPressChar == null })
        }
    }

    @Test
    fun `le clavier symboles reste accessible avec les chiffres quand la rangee est desactivee`() {
        languages.forEach { language ->
            val layout = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false)
            assertActionPresent(layout, KeyAction.ToggleLayout)
            val digits = Keyboards.layoutOf(LayoutId.SYMBOLS, language).rows.flatten()
                .mapNotNull { (it.action as? KeyAction.TypeChar)?.char }.filter { it.isDigit() }
            assertEquals("0123456789".toList().sorted(), digits.sorted())
        }
    }

    @Test
    fun `un chiffre saisi par appui long est commit tel quel meme avec Maj actif`() {
        val controller = KeyboardController(KeyboardState(isShifted = true))
        val result = controller.onKey(Key("longpress_1", "1", KeyAction.TypeChar('1')))
        assertEquals("1", result.commit)
    }

    // Apostrophe, rangée du bas et appui long sur le point.

    private fun chars(layout: KeyboardLayout) =
        layout.rows.flatten().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }

    @Test
    fun `l apostrophe est entre le n et effacer sur le clavier francais uniquement`() {
        val row = Keyboards.letters.rows[2]
        val index = row.indexOfFirst { it.id == "apostrophe" }
        assertEquals("letter_n", row[index - 1].id)
        assertEquals("backspace", row[index + 1].id)
        assertTrue('\'' !in chars(Keyboards.lettersEn))
    }

    @Test
    fun `la touche maj francaise est plus etroite et les lettres gardent la meme largeur`() {
        val frRow = Keyboards.letters.rows[2]
        assertTrue(frRow.first { it.id == "shift" }.weight < 1.6f)
        assertEquals(10f, frRow.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
        assertEquals(1.6f, Keyboards.lettersEn.rows[2].first { it.id == "shift" }.weight)
    }

    @Test
    fun `la rangee du bas n a plus ni point d exclamation ni point d interrogation`() {
        listOf(Keyboards.letters, Keyboards.lettersEn, Keyboards.symbols).forEach { layout ->
            val bottom = layout.rows.last().mapNotNull { (it.action as? KeyAction.TypeChar)?.char }
            assertEquals(listOf(',', '.'), bottom)
            assertEquals(10f, layout.rows.last().sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
        }
    }

    @Test
    fun `l appui long sur le point propose tous les autres symboles`() {
        val period = Keyboards.letters.rows.last().first { it.id == "period" }
        assertEquals(3, period.popup.size)
        assertTrue(period.popup.all { it.size == 6 })
        val symbols = period.popup.flatten()
        assertEquals(symbols.size, symbols.distinct().size)
        listOf('&', '%', '+', '"', '_', ';', '/', '-', ':', '\'', '@', '(', ')', '#', '!', ',', '?').forEach { c ->
            assertTrue(c in symbols, "Le symbole $c doit être dans la bulle du point")
        }
        assertTrue('.' !in symbols)
    }

    // Story 1.8 : appui long sur une lettre = bulle d'accents, sélection par glissement.

    private fun letterKey(layout: KeyboardLayout, c: Char) =
        layout.rows.flatten().first { it.id == "letter_$c" }

    /** Caractères de la bulle par ordre de priorité (la rangée du bas est la plus proche du doigt). */
    private fun popupItems(key: Key) = key.popup.reversed().flatten()

    @Test
    fun `les voyelles francaises proposent leurs accents en appui long`() {
        val letters = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, numberRow = true)
        assertEquals("éèêë", popupItems(letterKey(letters, 'e')).take(4).joinToString(""))
        assertEquals("àâæ", popupItems(letterKey(letters, 'a')).take(3).joinToString(""))
        assertEquals("îï", popupItems(letterKey(letters, 'i')).take(2).joinToString(""))
        assertEquals("ôœ", popupItems(letterKey(letters, 'o')).take(2).joinToString(""))
        assertEquals("ùûü", popupItems(letterKey(letters, 'u')).take(3).joinToString(""))
        assertEquals('ç', popupItems(letterKey(letters, 'c')).first())
        assertEquals('ÿ', popupItems(letterKey(letters, 'y')).first())
    }

    @Test
    fun `le clavier anglais propose aussi des accents en appui long`() {
        val letters = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.EN, numberRow = true)
        assertEquals('é', popupItems(letterKey(letters, 'e')).first())
        assertTrue('ñ' in popupItems(letterKey(letters, 'n')))
    }

    @Test
    fun `les lettres sans variante n ont pas de bulle`() {
        val letters = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, numberRow = true)
        listOf('q', 'b', 'd', 'k', 'w').forEach { c ->
            assertTrue(letterKey(letters, c).popup.isEmpty(), "La lettre $c ne doit pas avoir de bulle")
        }
    }

    @Test
    fun `les bulles d accents sont bien formees`() {
        languages.forEach { language ->
            listOf(true, false).forEach { numberRow ->
                Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow).rows.flatten()
                    .filter { it.id.startsWith("letter_") && it.popup.isNotEmpty() }
                    .forEach { key ->
                        val items = key.popup.flatten()
                        assertEquals(items.size, items.distinct().size, "Doublon dans la bulle de ${key.id}")
                        assertTrue(key.popup.all { row -> row.isNotEmpty() && row.size <= 5 }, key.id)
                        assertTrue(key.popup.size <= 2, "Au plus 2 rangées pour ${key.id}")
                    }
            }
        }
    }

    @Test
    fun `sans rangee de chiffres le chiffre est le premier choix de la bulle des touches du haut`() {
        val fr = Keyboards.layoutOf(LayoutId.LETTERS, KeyboardLanguage.FR, numberRow = false)
        assertEquals('3', popupItems(letterKey(fr, 'e')).first())
        assertEquals("3éèê", popupItems(letterKey(fr, 'e')).take(4).joinToString(""))
        assertEquals('3', letterKey(fr, 'e').longPressChar)
        // Sans accents : pas de bulle, l'appui long saisit directement le chiffre (story 1.6).
        assertTrue(letterKey(fr, 'z').popup.isNotEmpty()) // z a des variantes (ž)
        assertTrue(letterKey(fr, 'r').popup.isEmpty())
        assertEquals('4', letterKey(fr, 'r').longPressChar)
    }

    @Test
    fun `avec la rangee de chiffres la bulle ne contient que des lettres`() {
        languages.forEach { language ->
            lettersWithNumberRow(language).rows.flatten()
                .filter { it.id.startsWith("letter_") }
                .forEach { key -> assertTrue(key.popup.flatten().none { it.isDigit() }, key.id) }
        }
    }

    @Test
    fun `seules les lettres du haut ont un chiffre en tete de bulle`() {
        languages.forEach { language ->
            val layout = Keyboards.layoutOf(LayoutId.LETTERS, language, numberRow = false)
            layout.rows.drop(1).flatten().forEach { key ->
                assertTrue(key.popup.flatten().none { it.isDigit() }, key.id)
            }
        }
    }

    @Test
    fun `un accent choisi dans la bulle passe en majuscule avec Maj actif`() {
        val controller = KeyboardController(KeyboardState(isShifted = true))
        val result = controller.onKey(Key("popup_é", "é", KeyAction.TypeChar('é')))
        assertEquals("É", result.commit)
        assertEquals(false, result.newState.isShifted)
    }

    @Test
    fun `un accent choisi dans la bulle reste en minuscule sans Maj`() {
        val controller = KeyboardController()
        assertEquals("é", controller.onKey(Key("popup_é", "é", KeyAction.TypeChar('é'))).commit)
    }

    @Test
    fun `la bulle du point n a pas change`() {
        val period = Keyboards.letters.rows.last().first { it.id == "period" }
        assertEquals(3, period.popup.size)
    }

    private fun assertActionPresent(layout: KeyboardLayout, action: KeyAction) {
        assertTrue(
            layout.rows.flatten().any { it.action == action },
            "L'action $action doit être présente sur le layout ${layout.id}",
        )
    }
}