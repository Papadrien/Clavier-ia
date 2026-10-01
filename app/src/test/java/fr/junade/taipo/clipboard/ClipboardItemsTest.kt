package fr.junade.taipo.clipboard

import fr.junade.taipo.clipboard.ClipboardItems.EditResult
import fr.junade.taipo.clipboard.ClipboardItems.LabelResult
import fr.junade.taipo.clipboard.ClipboardItems.PinResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Story 2.5 : cartes du panneau (dernière copie puis épinglés) et règles d'épinglage. */
class ClipboardItemsTest {

    private fun pinned(id: Long, text: String) = PinnedClip(id, text, pinnedAtMillis = id)
    private fun last(text: String, sensitive: Boolean = false) = ClipboardSuggestionState.Suggestion(text, sensitive)

    @Test
    fun `sans copie ni epingle le panneau est vide`() {
        assertTrue(ClipboardItems.build(null, emptyList()).isEmpty())
    }

    @Test
    fun `la derniere copie vient en premier puis les epingles dans l ordre donne`() {
        val items = ClipboardItems.build(last("copie"), listOf(pinned(2, "b"), pinned(1, "a")))
        assertEquals(listOf("copie", "b", "a"), items.map { it.text })
        assertEquals(listOf(true, false, false), items.map { it.isLastClip })
        assertEquals(listOf(null, 2L, 1L), items.map { it.pinnedId })
    }

    @Test
    fun `une copie deja epinglee n apparait qu une fois, comme carte epinglee`() {
        val items = ClipboardItems.build(last("a"), listOf(pinned(1, "a"), pinned(2, "b")))
        assertEquals(listOf("a", "b"), items.map { it.text })
        assertTrue(items[0].pinned)
        assertTrue(items[0].isLastClip)
        assertFalse(items[1].isLastClip)
    }

    @Test
    fun `sans copie seuls les epingles sont montres`() {
        val items = ClipboardItems.build(null, listOf(pinned(1, "a")))
        assertEquals(1, items.size)
        assertTrue(items[0].pinned)
        assertFalse(items[0].isLastClip)
    }

    @Test
    fun `le caractere sensible de la derniere copie est conserve`() {
        val item = ClipboardItems.build(last("4970 1012", sensitive = true), emptyList()).single()
        assertTrue(item.sensitive)
        assertFalse(item.pinned)
    }

    @Test
    fun `un texte normal peut etre epingle`() {
        assertNull(ClipboardItems.pinRefusal("Bonjour", sensitive = false))
    }

    @Test
    fun `un contenu sensible ne peut pas etre epingle`() {
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal("Bonjour", sensitive = true))
    }

    @Test
    fun `un texte vide ou blanc ne peut pas etre epingle`() {
        assertEquals(PinResult.EMPTY, ClipboardItems.pinRefusal("", sensitive = false))
        assertEquals(PinResult.EMPTY, ClipboardItems.pinRefusal(" \n ", sensitive = false))
    }

    @Test
    fun `la taille maximale est acceptee et au dela refusee`() {
        assertNull(ClipboardItems.pinRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS), sensitive = false))
        assertEquals(
            PinResult.TOO_LONG,
            ClipboardItems.pinRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS + 1), sensitive = false),
        )
    }

    @Test
    fun `le sensible l emporte sur les autres refus`() {
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal("", sensitive = true))
    }

    // Story 2.6

    @Test
    fun `un texte modifie vide ou trop long est refuse, un doublon n est pas un refus`() {
        assertEquals(EditResult.EMPTY, ClipboardItems.editRefusal(""))
        assertEquals(EditResult.EMPTY, ClipboardItems.editRefusal("  \n "))
        assertEquals(EditResult.TOO_LONG, ClipboardItems.editRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS + 1)))
        assertNull(ClipboardItems.editRefusal("a".repeat(ClipboardItems.MAX_PINNED_CHARS)))
        assertNull(ClipboardItems.editRefusal("texte déjà épinglé ailleurs"))
    }

    @Test
    fun `modifier est propose pour un epingle et pour la derniere copie`() {
        val items = ClipboardItems.build(last("copie"), listOf(pinned(1, "a")))
        assertTrue(items.all { ClipboardItems.canEdit(it) })
    }

    @Test
    fun `modifier n est pas propose pour un contenu sensible ni trop long`() {
        val sensitive = ClipboardItems.build(last("secret", sensitive = true), emptyList()).single()
        assertFalse(ClipboardItems.canEdit(sensitive))
        val tooLong = ClipboardItems.build(last("a".repeat(ClipboardItems.MAX_PINNED_CHARS + 1)), emptyList()).single()
        assertFalse(ClipboardItems.canEdit(tooLong))
        val atLimit = ClipboardItems.build(last("a".repeat(ClipboardItems.MAX_PINNED_CHARS)), emptyList()).single()
        assertTrue(ClipboardItems.canEdit(atLimit))
    }

    @Test
    fun `deux epingles de meme texte apres modification restent deux cartes`() {
        val items = ClipboardItems.build(null, listOf(pinned(2, "b"), pinned(1, "b")))
        assertEquals(listOf(2L, 1L), items.map { it.pinnedId })
    }

    // Stories 2.7 et 2.8

    @Test
    fun `une etiquette est normalisee par retrait des espaces autour seulement`() {
        assertEquals("Mon Code", ClipboardItems.normalizeLabel("  Mon Code \n"))
        assertEquals("a  b", ClipboardItems.normalizeLabel("a  b"))
    }

    @Test
    fun `etiquette vide, trop longue ou valide`() {
        assertEquals(LabelResult.EMPTY, ClipboardItems.labelRefusal(""))
        assertEquals(LabelResult.EMPTY, ClipboardItems.labelRefusal("   "))
        assertEquals(LabelResult.TOO_LONG, ClipboardItems.labelRefusal("a".repeat(ClipboardItems.MAX_LABEL_CHARS + 1)))
        assertNull(ClipboardItems.labelRefusal("a".repeat(ClipboardItems.MAX_LABEL_CHARS)))
    }

    @Test
    fun `les espaces autour ne comptent pas dans la limite de 15 caracteres`() {
        assertNull(ClipboardItems.labelRefusal("  " + "a".repeat(ClipboardItems.MAX_LABEL_CHARS) + "  "))
    }

    @Test
    fun `la carte epinglee porte son etiquette, pas la derniere copie`() {
        val items = ClipboardItems.build(last("copie"), listOf(PinnedClip(1, "a", 1, label = "Perso"), pinned(2, "b")))
        assertEquals(listOf<String?>(null, "Perso", null), items.map { it.label })
    }

    @Test
    fun `seuls les elements epingles peuvent recevoir une etiquette`() {
        val items = ClipboardItems.build(last("copie"), listOf(pinned(1, "a")))
        assertEquals(listOf(false, true), items.map { ClipboardItems.canLabel(it) })
    }
    // Story 2.9

    private val now = 10_000_000L
    private val hour = ClipboardItems.HISTORY_RETENTION_MILLIS

    private fun entry(id: Long, text: String, ageMillis: Long = 0, sensitive: Boolean = false) =
        ClipHistoryEntry(id, text, copiedAtMillis = now - ageMillis, sensitive = sensitive)

    private fun build(
        lastClip: ClipboardSuggestionState.Suggestion? = null,
        pinned: List<PinnedClip> = emptyList(),
        history: List<ClipHistoryEntry> = emptyList(),
    ) = ClipboardItems.build(lastClip, pinned, history, nowMillis = now)

    @Test
    fun `l historique s affiche du plus recent au plus ancien, avant les epingles`() {
        val items = build(
            pinned = listOf(pinned(1, "epingle")),
            history = listOf(entry(1, "ancien", ageMillis = 2_000), entry(2, "recent", ageMillis = 1_000)),
        )
        assertEquals(listOf("recent", "ancien", "epingle"), items.map { it.text })
        assertEquals(listOf(2L, 1L, null), items.map { it.historyId })
        assertEquals(listOf(false, false, true), items.map { it.pinned })
    }

    @Test
    fun `la derniere copie vient en tete et sa ligne d historique n est pas repetee`() {
        val items = build(last("copie"), history = listOf(entry(7, "copie"), entry(8, "autre", ageMillis = 1_000)))
        assertEquals(listOf("copie", "autre"), items.map { it.text })
        assertEquals(listOf(true, false), items.map { it.isLastClip })
        assertEquals(listOf(7L, 8L), items.map { it.historyId })
    }

    @Test
    fun `une ligne d historique de plus d une heure n est pas montree`() {
        val items = build(history = listOf(entry(1, "juste avant", ageMillis = hour - 1), entry(2, "expiree", ageMillis = hour)))
        assertEquals(listOf("juste avant"), items.map { it.text })
    }

    @Test
    fun `un texte epingle n apparait que comme carte epinglee`() {
        val items = build(pinned = listOf(pinned(1, "a")), history = listOf(entry(5, "a"), entry(6, "b", ageMillis = 1_000)))
        assertEquals(listOf("b", "a"), items.map { it.text })
        assertTrue(items[1].pinned)
        assertEquals(5L, items[1].historyId)
    }

    @Test
    fun `une derniere copie epinglee n est montree que comme carte epinglee`() {
        val items = build(last("a"), pinned = listOf(pinned(1, "a")), history = listOf(entry(5, "a")))
        assertEquals(1, items.size)
        assertTrue(items.single().pinned)
        assertTrue(items.single().isLastClip)
    }

    @Test
    fun `deux lignes de meme texte ne font qu une carte, la plus recente`() {
        val items = build(history = listOf(entry(1, "a", ageMillis = 5_000), entry(2, "a", ageMillis = 1_000)))
        assertEquals(listOf(2L), items.map { it.historyId })
    }

    @Test
    fun `le drapeau sensible d une ligne d historique est conserve`() {
        val item = build(history = listOf(entry(1, "4970 1012", sensitive = true))).single()
        assertTrue(item.sensitive)
        assertFalse(ClipboardItems.canEdit(item))
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal(item.text, item.sensitive))
    }

    @Test
    fun `une ligne d historique peut etre modifiee et epinglee, pas etiquetee`() {
        val item = build(history = listOf(entry(1, "a"))).single()
        assertTrue(ClipboardItems.canEdit(item))
        assertFalse(ClipboardItems.canLabel(item))
        assertNull(ClipboardItems.pinRefusal(item.text, item.sensitive))
    }

    @Test
    fun `sans historique la construction est celle de la story 2 5`() {
        val items = build(last("copie"), pinned = listOf(pinned(2, "b"), pinned(1, "a")))
        assertEquals(listOf("copie", "b", "a"), items.map { it.text })
        assertTrue(items.all { it.historyId == null })
    }

    @Test
    fun `l historique refuse un texte vide ou de plus de 10 000 caracteres`() {
        assertTrue(ClipboardItems.historyRefusal(" \n "))
        assertTrue(ClipboardItems.historyRefusal("a".repeat(ClipboardItems.MAX_HISTORY_CHARS + 1)))
        assertFalse(ClipboardItems.historyRefusal("a".repeat(ClipboardItems.MAX_HISTORY_CHARS)))
    }
    // Story 2.10

    private val card = "4111 1111 1111 1111"

    @Test
    fun `une derniere copie qui ressemble a une carte est sensible sans drapeau`() {
        val item = build(last(card)).single()
        assertTrue(item.sensitive)
        assertFalse(ClipboardItems.canEdit(item))
    }

    @Test
    fun `une ligne d historique qui ressemble a une carte est sensible sans drapeau`() {
        val items = build(history = listOf(entry(1, card), entry(2, "Bonjour", ageMillis = 1_000)))
        assertEquals(listOf(true, false), items.map { it.sensitive })
    }

    @Test
    fun `une carte ne peut pas etre epinglee meme sans drapeau de l application source`() {
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal(card, sensitive = false))
        assertEquals(PinResult.SENSITIVE, ClipboardItems.pinRefusal(" 4111111111111111 ", sensitive = false))
        assertNull(ClipboardItems.pinRefusal("Ma carte : $card", sensitive = false))
        assertNull(ClipboardItems.pinRefusal("FR76 3000 6000 0112 3456 7890 189", sensitive = false))
    }

    @Test
    fun `un element deja epingle n est pas masque retroactivement`() {
        val items = build(pinned = listOf(pinned(1, card)))
        assertFalse(items.single().sensitive)
        assertTrue(items.single().pinned)
        assertTrue(ClipboardItems.canEdit(items.single()))
    }

    @Test
    fun `isSensitive combine le drapeau et le contenu`() {
        assertTrue(ClipboardItems.isSensitive("Bonjour", flagged = true))
        assertTrue(ClipboardItems.isSensitive(card, flagged = false))
        assertFalse(ClipboardItems.isSensitive("Bonjour", flagged = false))
    }
}
