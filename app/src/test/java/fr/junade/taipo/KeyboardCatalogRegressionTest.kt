package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Lot 22 (non-régression de la refonte graphique) : le catalogue des touches ne dépend pas du rendu, mais c'est lui que
 * la vue dessine et que le contrôleur interprète. Ce test balaie **toutes** les combinaisons (disposition × langue ×
 * rangée de chiffres × type de champ) et vérifie les invariants dont dépend le nouveau moteur visuel : identifiants
 * uniques, libellés cohérents avec le caractère saisi, poids positifs, touches vitales (Entrée, Effacer, Espace, Maj)
 * présentes exactement une fois là où elles doivent l'être.
 */
class KeyboardCatalogRegressionTest {

    private class Variant(val id: LayoutId, val language: KeyboardLanguage, val numberRow: Boolean, val fieldType: FieldType) {
        val layout: KeyboardLayout get() = Keyboards.layoutOf(id, language, numberRow, fieldType)
        override fun toString() = "$id/$language/numberRow=$numberRow/$fieldType"
    }

    private val variants: List<Variant> = buildList {
        for (id in LayoutId.entries) {
            for (language in KeyboardLanguage.entries) {
                for (numberRow in listOf(false, true)) {
                    for (fieldType in FieldType.entries) add(Variant(id, language, numberRow, fieldType))
                }
            }
        }
    }

    private fun KeyboardLayout.keys(): List<Key> = rows.flatten()

    @Test
    fun `aucune combinaison n est vide et toutes ont des rangees non vides`() {
        for (v in variants) {
            val layout = v.layout
            assertTrue(layout.rows.isNotEmpty(), "$v : aucune rangee")
            layout.rows.forEachIndexed { i, row -> assertTrue(row.isNotEmpty(), "$v : rangee $i vide") }
        }
    }

    @Test
    fun `les identifiants de touches sont uniques dans chaque disposition`() {
        for (v in variants) {
            val ids = v.layout.keys().map { it.id }
            assertEquals(ids.size, ids.toSet().size, "$v : identifiants en double ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        }
    }

    @Test
    fun `chaque touche a un poids positif et un libelle coherent`() {
        for (v in variants) {
            for (key in v.layout.keys()) {
                assertTrue(key.weight > 0f, "$v : poids nul pour ${key.id}")
                when (val action = key.action) {
                    is KeyAction.TypeChar -> assertEquals(action.char.toString(), key.label, "$v : libelle de ${key.id}")
                    KeyAction.Space -> Unit // la barre espace n'a pas de libelle (barre grise dessinee, lot 06)
                    else -> assertTrue(key.label.isNotEmpty(), "$v : libelle vide pour ${key.id}")
                }
            }
        }
    }

    @Test
    fun `entree et effacer sont presents exactement une fois partout`() {
        for (v in variants) {
            val actions = v.layout.keys().map { it.action }
            assertEquals(1, actions.count { it == KeyAction.Enter }, "$v : Entree")
            assertEquals(1, actions.count { it == KeyAction.Backspace }, "$v : Effacer")
        }
    }

    @Test
    fun `la barre espace est presente une fois sauf sur le pave telephone`() {
        for (v in variants) {
            val spaces = v.layout.keys().count { it.action == KeyAction.Space }
            val expected = if (v.fieldType == FieldType.PHONE) 0 else 1
            assertEquals(expected, spaces, "$v : barre espace")
        }
    }

    @Test
    fun `maj est present une fois sur les claviers de lettres et absent ailleurs`() {
        for (v in variants) {
            val layout = v.layout
            val shifts = layout.keys().count { it.action == KeyAction.Shift }
            val expected = if (layout.id == LayoutId.LETTERS) 1 else 0
            assertEquals(expected, shifts, "$v : Maj")
        }
    }

    @Test
    fun `les claviers de lettres et de symboles ont une bascule et un bouton emoji`() {
        for (v in variants) {
            val layout = v.layout
            if (layout.id == LayoutId.PAD) continue
            assertEquals(1, layout.keys().count { it.action == KeyAction.ToggleLayout }, "$v : bascule")
            assertEquals(1, layout.keys().count { it.action == KeyAction.Emoji }, "$v : emoji")
        }
    }

    @Test
    fun `les bulles d appui long ne contiennent ni vide ni doublon`() {
        for (v in variants) {
            for (key in v.layout.keys()) {
                if (key.popup.isEmpty()) continue
                val chars = key.popup.flatten()
                assertTrue(chars.isNotEmpty(), "$v : bulle vide pour ${key.id}")
                assertEquals(chars.size, chars.toSet().size, "$v : doublon dans la bulle de ${key.id}")
                key.defaultPopupChar?.let { assertTrue(it in chars, "$v : choix par defaut absent de la bulle de ${key.id}") }
            }
        }
    }
}
