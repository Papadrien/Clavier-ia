package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Cache des lectures du champ : il doit économiser les allers-retours sans jamais rendre un texte faux. */
class InputTextCacheTest {

    /** Faux champ : un texte et un curseur, et le nombre de lectures réelles. */
    private class Field(var text: String, var cursor: Int = text.length) {
        var reads = 0
        fun before(n: Int): String {
            reads++
            return text.substring(maxOf(0, cursor - n), cursor)
        }
        fun after(n: Int): String {
            reads++
            return text.substring(cursor, minOf(text.length, cursor + n))
        }
        fun commit(insert: String) {
            text = text.substring(0, cursor) + insert + text.substring(cursor)
            cursor += insert.length
        }
        fun delete(count: Int) {
            val from = maxOf(0, cursor - count)
            text = text.substring(0, from) + text.substring(cursor)
            cursor = from
        }
    }

    private var now = 1_000L
    private fun cache(field: Field) = InputTextCache({ now }).apply { reset(field.cursor, field.cursor) }

    @Test
    fun `une seconde lecture est servie par le cache`() {
        val field = Field("Bonjour tout le monde")
        val cache = cache(field)
        assertEquals("monde", cache.textBefore(5, field::before))
        assertEquals("le monde", cache.textBefore(8, field::before))
        assertEquals("e", cache.textBefore(1, field::before))
        assertEquals(1, field.reads)
    }

    @Test
    fun `debut du champ atteint - une demande plus grande est servie`() {
        val field = Field("Salut")
        val cache = cache(field)
        assertEquals("Salut", cache.textBefore(50, field::before))
        assertEquals("Salut", cache.textBefore(200, field::before))
        assertEquals(1, field.reads)
    }

    @Test
    fun `commitText met a jour le texte avant le curseur sans relire`() {
        val field = Field("Bonjour", cursor = 7)
        val cache = cache(field)
        cache.textBefore(50, field::before)
        cache.textAfter(50, field::after)
        field.commit(" à")
        cache.onCommit(" à", 1)
        assertEquals("Bonjour à", cache.textBefore(50, field::before))
        assertEquals("", cache.textAfter(50, field::after))
        assertNull(cache.selectedText { error("pas de lecture : rien n'est sélectionné après commitText") })
        assertEquals(2, field.reads)
    }

    @Test
    fun `l'echo de nos ecritures garde le cache, un autre deplacement le vide`() {
        val field = Field("abc")
        val cache = cache(field)
        cache.textBefore(50, field::before)
        field.commit("d")
        cache.onCommit("d", 1)
        cache.onSelectionUpdate(4, 4, -1, -1) // écho attendu
        assertEquals("abcd", cache.textBefore(50, field::before))
        assertEquals(1, field.reads)
        field.cursor = 1 // l'utilisateur touche le champ ailleurs
        cache.onSelectionUpdate(1, 1, -1, -1)
        assertEquals("a", cache.textBefore(50, field::before))
        assertEquals(2, field.reads)
    }

    @Test
    fun `frappe rapide - les echos en retard ne videant pas le cache`() {
        val field = Field("")
        val cache = cache(field)
        cache.textBefore(50, field::before)
        for (c in listOf("a", "b", "c")) {
            field.commit(c)
            cache.onCommit(c, 1)
        }
        cache.onSelectionUpdate(1, 1, -1, -1)
        cache.onSelectionUpdate(2, 2, -1, -1)
        assertEquals("abc", cache.textBefore(50, field::before))
        cache.onSelectionUpdate(3, 3, -1, -1)
        assertEquals("abc", cache.textBefore(50, field::before))
        assertEquals(1, field.reads)
    }

    @Test
    fun `retour arriere met a jour le texte et la fin du champ`() {
        val field = Field("chat", cursor = 4)
        val cache = cache(field)
        cache.textBefore(50, field::before)
        field.delete(1)
        cache.onDeleteSurrounding(1, 0)
        assertEquals("cha", cache.textBefore(50, field::before))
        field.delete(3)
        cache.onDeleteSurrounding(3, 0)
        assertEquals("", cache.textBefore(50, field::before))
        field.delete(5) // rien à effacer de plus
        cache.onDeleteSurrounding(5, 0)
        assertEquals("", cache.textBefore(50, field::before))
        assertEquals(1, field.reads)
    }

    @Test
    fun `retour arriere au-dela de la fenetre lue - relecture`() {
        val field = Field("x".repeat(100))
        val cache = cache(field)
        assertEquals(50, cache.textBefore(50, field::before)!!.length)
        field.delete(60)
        cache.onDeleteSurrounding(60, 0)
        assertEquals(40, cache.textBefore(50, field::before)!!.length)
        assertEquals(2, field.reads)
    }

    @Test
    fun `une autre ecriture vide le cache et rend le curseur inconnu`() {
        val field = Field("abc")
        val cache = cache(field)
        cache.textBefore(50, field::before)
        cache.onOtherWrite()
        cache.textBefore(50, field::before)
        assertEquals(2, field.reads)
        // Curseur inconnu : un commitText ne peut pas être suivi sur place.
        field.commit("d")
        cache.onCommit("d", 1)
        assertEquals("abcd", cache.textBefore(50, field::before))
        assertEquals(3, field.reads)
    }

    @Test
    fun `lecture trop ancienne - relue`() {
        val field = Field("abc")
        val cache = cache(field)
        cache.textBefore(50, field::before)
        now += InputTextCache.TTL_MS + 1
        cache.textBefore(50, field::before)
        assertEquals(2, field.reads)
    }

    @Test
    fun `texte en composition ou selection - rien n'est garde`() {
        val field = Field("abc")
        val cache = cache(field)
        cache.textBefore(50, field::before)
        cache.onSelectionUpdate(3, 3, 0, 3)
        cache.textBefore(50, field::before)
        cache.onSelectionUpdate(1, 3, -1, -1)
        cache.selectedText {
            field.reads++
            "bc"
        }
        assertEquals("bc", cache.selectedText { error("déjà lu") })
        assertEquals(3, field.reads)
    }

    @Test
    fun `une lecture refusee par le champ n'est pas mise en cache`() {
        val field = Field("abc")
        val cache = cache(field)
        assertNull(
            cache.textBefore(50) {
                field.reads++
                null
            },
        )
        assertEquals("abc", cache.textBefore(50, field::before))
        assertEquals(2, field.reads)
    }
}
