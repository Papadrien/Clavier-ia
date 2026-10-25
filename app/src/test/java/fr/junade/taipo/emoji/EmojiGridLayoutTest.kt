package fr.junade.taipo.emoji

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EmojiGridLayoutTest {

    private fun emojis(prefix: String, n: Int) = (0 until n).map { "$prefix$it" }

    // 4 colonnes, cellules de 10, en-têtes de 6.
    // Récents (vide, avec message) : 0..16 ; A (9 emojis = 3 rangées) : 16..52 ; B (4 emojis = 1 rangée) : 52..68.
    private fun layout() = EmojiGridLayout(
        sections = listOf(
            EmojiSection(EmojiCategoryId.RECENT, "Récents", emptyList(), emptyMessage = "vide"),
            EmojiSection(EmojiCategoryId.SMILEYS, "A", emojis("a", 9)),
            EmojiSection(EmojiCategoryId.PEOPLE, "B", emojis("b", 4)),
        ),
        columns = 4,
        cellSize = 10f,
        headerHeight = 6f,
    )

    @Test
    fun `hauteurs et positions des sections`() {
        val l = layout()
        assertEquals(0f, l.sectionTop(0))
        assertEquals(16f, l.sectionTop(1))
        assertEquals(52f, l.sectionTop(2))
        assertEquals(68f, l.contentHeight)
    }

    @Test
    fun `section sans emoji ni message n occupe que son en-tete`() {
        val l = EmojiGridLayout(listOf(EmojiSection(EmojiCategoryId.FOOD, "x", emptyList())), 4, 10f, 6f)
        assertEquals(6f, l.contentHeight)
    }

    @Test
    fun `defilement maximal`() {
        val l = layout()
        assertEquals(18f, l.maxScroll(50f))
        assertEquals(0f, l.maxScroll(100f))
    }

    @Test
    fun `sectionAt donne la section de l ordonnee`() {
        val l = layout()
        assertEquals(0, l.sectionAt(0f))
        assertEquals(0, l.sectionAt(15.9f))
        assertEquals(1, l.sectionAt(16f))
        assertEquals(1, l.sectionAt(51f))
        assertEquals(2, l.sectionAt(52f))
        assertEquals(2, l.sectionAt(500f))
    }

    @Test
    fun `hitTest trouve l emoji touche`() {
        val l = layout()
        // Section A : corps à partir de y = 22. Rangée 1, colonne 2 → indice 6.
        val hit = l.hitTest(25f, 22f + 10f + 3f)!!
        assertEquals("a6", hit.emoji)
        assertEquals(20f, hit.left)
        assertEquals(32f, hit.top)
    }

    @Test
    fun `hitTest ignore en-tete, message, cellule vide et hors grille`() {
        val l = layout()
        assertNull(l.hitTest(5f, 3f)) // en-tête Récents
        assertNull(l.hitTest(5f, 10f)) // message « vide »
        assertNull(l.hitTest(5f, 16f + 2f)) // en-tête A
        assertNull(l.hitTest(35f, 22f + 20f + 2f)) // dernière rangée de A : seule la colonne 0 est remplie (a8)
        assertNull(l.hitTest(45f, 30f)) // à droite de la 4e colonne
        assertNull(l.hitTest(5f, 68f)) // sous le contenu
        assertNull(l.hitTest(-1f, 30f))
    }

    @Test
    fun `forEachVisible ne parcourt que ce qui touche la zone visible`() {
        val l = layout()
        val cells = mutableListOf<String>()
        val headers = mutableListOf<Int>()
        val messages = mutableListOf<Int>()
        l.forEachVisible(30f, 45f, object : EmojiGridLayout.Visitor {
            override fun header(section: Int, y: Float) { headers += section }
            override fun message(section: Int, y: Float) { messages += section }
            override fun cell(emoji: String, x: Float, y: Float) { cells += emoji }
        })
        // Zone 30..45 : seule la section A est touchée (corps de 22 à 52). Sa 1re rangée (22..32) déborde
        // dans la zone, la 3e (42..52) aussi ; l'en-tête (16..22) et la section B (dès 52) sont hors zone.
        assertEquals(emojis("a", 9), cells)
        assertEquals(emptyList<Int>(), headers)
        assertEquals(emptyList<Int>(), messages)
    }

    @Test
    fun `forEachVisible sur tout le contenu donne en-tetes, message et tous les emojis`() {
        val l = layout()
        val cells = mutableListOf<String>()
        val headers = mutableListOf<Int>()
        val messages = mutableListOf<Int>()
        l.forEachVisible(0f, l.contentHeight, object : EmojiGridLayout.Visitor {
            override fun header(section: Int, y: Float) { headers += section }
            override fun message(section: Int, y: Float) { messages += section }
            override fun cell(emoji: String, x: Float, y: Float) { cells += emoji }
        })
        assertEquals(listOf(0, 1, 2), headers)
        assertEquals(listOf(0), messages)
        assertEquals(emojis("a", 9) + emojis("b", 4), cells)
    }

    @Test
    fun `nombre de colonnes`() {
        assertEquals(9, EmojiGridLayout.columnsFor(1080f, 115f))
        assertEquals(1, EmojiGridLayout.columnsFor(10f, 115f))
        assertEquals(1, EmojiGridLayout.columnsFor(500f, 0f))
    }

    @Test
    fun `locate donne la section et le rang de l emoji (lot 20)`() {
        val l = layout()
        // Section A : corps à partir de y = 22 ; rangée 1, colonne 2 -> rang 6.
        val ref = l.locate(25f, 22f + 10f + 3f)!!
        assertEquals(1, ref.section)
        assertEquals(6, ref.index)
        assertEquals("a6", ref.emoji)
        assertEquals(20f, ref.left)
        assertEquals(32f, ref.top)
    }

    @Test
    fun `locate et hitTest concordent`() {
        val l = layout()
        val hit = l.hitTest(5f, 25f)!!
        val ref = l.locate(5f, 25f)!!
        assertEquals(hit.emoji, ref.emoji)
        assertEquals(hit.left, ref.left)
        assertEquals(hit.top, ref.top)
        assertNull(l.locate(5f, 1f)) // en-tête
    }

    @Test
    fun `forEachVisible transmet la section et le rang a cellAt`() {
        val l = layout()
        val seen = mutableListOf<Triple<Int, Int, String>>()
        l.forEachVisible(
            0f,
            68f,
            object : EmojiGridLayout.Visitor {
                override fun header(section: Int, y: Float) = Unit
                override fun message(section: Int, y: Float) = Unit
                override fun cell(emoji: String, x: Float, y: Float) = Unit
                override fun cellAt(section: Int, index: Int, emoji: String, x: Float, y: Float) {
                    seen += Triple(section, index, emoji)
                }
            },
        )
        assertEquals(13, seen.size)
        assertEquals(Triple(1, 0, "a0"), seen.first())
        assertEquals(Triple(2, 3, "b3"), seen.last())
    }
}
