package fr.junade.taipo.clipboard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stories 2.2 et 2.3 : quand la puce de collage est proposée (copie récente, non collée, non écartée). */
class ClipboardSuggestionStateTest {

    private var now = 10_000_000L
    private val state = ClipboardSuggestionState(clock = { now })

    private val tenMinutes = ClipboardSuggestionState.EXPIRATION_MILLIS

    private fun copy(text: String?, at: Long = now, sensitive: Boolean = false) =
        state.onClipRead(text, at, sensitive)

    @Test
    fun `sans copie aucune puce n est proposee`() {
        assertNull(state.suggestion())
        assertNull(state.expiresInMillis())
    }

    @Test
    fun `une copie recente est proposee avec son texte`() {
        copy("Bonjour")
        assertEquals(ClipboardSuggestionState.Suggestion("Bonjour", sensitive = false), state.suggestion())
    }

    @Test
    fun `une copie vide ou faite d espaces n est pas proposee`() {
        copy("")
        assertNull(state.suggestion())
        copy(" \n\t ")
        assertNull(state.suggestion())
    }

    @Test
    fun `un presse-papiers vide ou illisible retire la puce`() {
        copy("Bonjour")
        copy(null)
        assertNull(state.suggestion())
    }

    @Test
    fun `une copie trop grosse n est pas proposee`() {
        copy("a".repeat(ClipboardSuggestionState.MAX_CHARS + 1))
        assertNull(state.suggestion())
    }

    @Test
    fun `une copie a la taille maximale est proposee`() {
        copy("a".repeat(ClipboardSuggestionState.MAX_CHARS))
        assertNotNull(state.suggestion())
    }

    @Test
    fun `une copie collee n est plus proposee`() {
        copy("Bonjour")
        state.onPasted()
        assertNull(state.suggestion())
    }

    @Test
    fun `relire la meme copie apres un collage ne ressuscite pas la puce`() {
        copy("Bonjour", at = now)
        state.onPasted()
        now += 1_000
        copy("Bonjour", at = now - 1_000)
        assertNull(state.suggestion())
    }

    @Test
    fun `une frappe ecarte la puce pour cette copie`() {
        copy("Bonjour")
        state.onTyping()
        assertNull(state.suggestion())
    }

    @Test
    fun `relire la meme copie apres une frappe ne ressuscite pas la puce`() {
        copy("Bonjour", at = now)
        state.onTyping()
        copy("Bonjour", at = now)
        assertNull(state.suggestion())
    }

    @Test
    fun `une nouvelle copie du meme texte fait revenir la puce`() {
        copy("Bonjour", at = now)
        state.onTyping()
        now += 5_000
        copy("Bonjour", at = now)
        assertNotNull(state.suggestion())
    }

    @Test
    fun `une nouvelle copie d un autre texte remplace la precedente et fait revenir la puce`() {
        copy("Bonjour", at = now)
        state.onPasted()
        now += 5_000
        copy("Au revoir", at = now)
        assertEquals("Au revoir", state.suggestion()?.text)
    }

    @Test
    fun `une frappe sans copie ne bloque pas la copie suivante`() {
        state.onTyping()
        copy("Bonjour")
        assertNotNull(state.suggestion())
    }

    @Test
    fun `la puce est proposee jusqu a l expiration puis disparait`() {
        copy("Bonjour", at = now)
        now += tenMinutes - 1
        assertNotNull(state.suggestion())
        now += 1
        assertNull(state.suggestion())
    }

    @Test
    fun `une copie deja ancienne a la lecture n est pas proposee`() {
        copy("Bonjour", at = now - tenMinutes - 1)
        assertNull(state.suggestion())
    }

    @Test
    fun `une relecture ne repousse pas l expiration`() {
        copy("Bonjour", at = now)
        now += tenMinutes - 1_000
        copy("Bonjour", at = now - (tenMinutes - 1_000))
        now += 1_000
        assertNull(state.suggestion())
    }

    @Test
    fun `le delai restant avant expiration decroit avec l horloge`() {
        copy("Bonjour", at = now)
        assertEquals(tenMinutes, state.expiresInMillis())
        now += 60_000
        assertEquals(tenMinutes - 60_000, state.expiresInMillis())
    }

    @Test
    fun `aucun delai a planifier quand la puce n est pas proposee`() {
        copy("Bonjour")
        state.onTyping()
        assertNull(state.expiresInMillis())
    }

    @Test
    fun `sans horodatage la copie se reconnait a son contenu et expire depuis la premiere lecture`() {
        copy("Bonjour", at = 0)
        state.onTyping()
        now += 60_000
        copy("Bonjour", at = 0)
        assertNull(state.suggestion())
        now += tenMinutes
        assertNull(state.suggestion())
    }

    @Test
    fun `sans horodatage un autre texte est une nouvelle copie`() {
        copy("Bonjour", at = 0)
        state.onTyping()
        copy("Au revoir", at = 0)
        assertEquals("Au revoir", state.suggestion()?.text)
    }

    @Test
    fun `le drapeau sensible est transmis a la suggestion`() {
        copy("4970 1012 3456 7890", sensitive = true)
        assertTrue(state.suggestion()!!.sensitive)
    }

    @Test
    fun `une relecture qui signale le contenu sensible le garde sensible`() {
        copy("secret", at = now, sensitive = false)
        copy("secret", at = now, sensitive = true)
        assertTrue(state.suggestion()!!.sensitive)
    }

    @Test
    fun `clear retire la copie`() {
        copy("Bonjour")
        state.clear()
        assertNull(state.suggestion())
        assertFalse(state.expiresInMillis() != null)
    }

    // --- Story 2.5 : dernière copie du panneau, suppression ---

    @Test
    fun `lastClip montre la copie meme ecartee par une frappe ou collee`() {
        copy("Bonjour")
        state.onTyping()
        assertEquals("Bonjour", state.lastClip()?.text)
        state.onPasted()
        assertEquals("Bonjour", state.lastClip()?.text)
    }

    @Test
    fun `lastClip montre la copie meme expiree`() {
        copy("Bonjour", at = now)
        now += tenMinutes * 3
        assertNull(state.suggestion())
        assertEquals("Bonjour", state.lastClip()?.text)
    }

    @Test
    fun `lastClip est nul sans copie et pour une copie vide ou trop grosse`() {
        assertNull(state.lastClip())
        copy(" \n ")
        assertNull(state.lastClip())
        copy("a".repeat(ClipboardSuggestionState.MAX_CHARS + 1))
        assertNull(state.lastClip())
    }

    @Test
    fun `lastClip garde le drapeau sensible`() {
        copy("4970 1012", sensitive = true)
        assertTrue(state.lastClip()!!.sensitive)
    }

    @Test
    fun `une copie supprimee disparait du panneau et de la puce`() {
        copy("Bonjour")
        state.onDeleted()
        assertNull(state.lastClip())
        assertNull(state.suggestion())
    }

    @Test
    fun `relire la meme copie apres sa suppression ne la ressuscite pas`() {
        copy("Bonjour", at = now)
        state.onDeleted()
        copy("Bonjour", at = now)
        assertNull(state.lastClip())
    }

    @Test
    fun `une nouvelle copie apres une suppression revient dans le panneau et la puce`() {
        copy("Bonjour", at = now)
        state.onDeleted()
        now += 5_000
        copy("Bonjour", at = now)
        assertEquals("Bonjour", state.lastClip()?.text)
        assertNotNull(state.suggestion())
    }

    // Story 2.6

    @Test
    fun `une copie modifiee montre le nouveau texte dans le panneau et la puce`() {
        copy("Bonjour")
        state.onEdited("Bonjour à tous")
        assertEquals("Bonjour à tous", state.lastClip()?.text)
        assertEquals("Bonjour à tous", state.suggestion()?.text)
    }

    @Test
    fun `relire le presse-papiers Android ne defait pas la modification`() {
        copy("Bonjour", at = now)
        state.onEdited("Bonjour à tous")
        copy("Bonjour", at = now)
        assertEquals("Bonjour à tous", state.lastClip()?.text)
        assertEquals("Bonjour à tous", state.suggestion()?.text)
    }

    @Test
    fun `une nouvelle copie remplace la modification`() {
        copy("Bonjour", at = now)
        state.onEdited("Bonjour à tous")
        now += 5_000
        copy("Salut", at = now)
        assertEquals("Salut", state.lastClip()?.text)
    }

    @Test
    fun `modifier une copie deja collee ne fait pas revenir la puce`() {
        copy("Bonjour")
        state.onPasted()
        state.onEdited("Bonjour à tous")
        assertNull(state.suggestion())
        assertEquals("Bonjour à tous", state.lastClip()?.text)
    }

    @Test
    fun `la modification ne prolonge pas l expiration de la puce`() {
        copy("Bonjour", at = now)
        state.onEdited("Bonjour à tous")
        now += tenMinutes + 1
        assertNull(state.suggestion())
    }

    @Test
    fun `un texte modifie vide est ignore et sans copie la modification ne fait rien`() {
        state.onEdited("rien")
        assertNull(state.lastClip())
        copy("Bonjour")
        state.onEdited("   ")
        assertEquals("Bonjour", state.lastClip()?.text)
    }
    // Story 2.9

    private val oneHour = ClipboardItems.HISTORY_RETENTION_MILLIS

    @Test
    fun `onClipRead indique une nouvelle copie, pas une relecture ni un presse-papiers vide`() {
        assertTrue(copy("Bonjour"))
        assertFalse(copy("Bonjour"))
        assertTrue(copy("Salut"))
        assertFalse(copy(null))
    }

    @Test
    fun `une nouvelle copie du meme texte avec un autre horodatage est une nouvelle copie`() {
        assertTrue(copy("Bonjour", at = now))
        now += 1_000
        assertTrue(copy("Bonjour", at = now))
    }

    @Test
    fun `sans horodatage une relecture du meme texte n est pas une nouvelle copie`() {
        assertTrue(copy("Bonjour", at = 0))
        now += 5_000
        assertFalse(copy("Bonjour", at = 0))
    }

    @Test
    fun `lastClip reste montree jusqu a une heure apres la copie puis disparait`() {
        copy("Bonjour", at = now)
        now += oneHour - 1
        assertEquals("Bonjour", state.lastClip()?.text)
        now += 1
        assertNull(state.lastClip())
    }

    @Test
    fun `l expiration d une heure se compte depuis la copie, pas depuis la lecture`() {
        copy("Bonjour", at = now - oneHour + 1_000)
        assertEquals("Bonjour", state.lastClip()?.text)
        now += 1_000
        assertNull(state.lastClip())
    }

    @Test
    fun `la modification ne prolonge pas l expiration d une heure`() {
        copy("Bonjour", at = now)
        now += oneHour - 1_000
        state.onEdited("Bonjour à tous")
        now += 1_000
        assertNull(state.lastClip())
    }
    // Story 2.10

    @Test
    fun `une copie qui ressemble a une carte est sensible sans drapeau, pour la puce et le panneau`() {
        copy("4111 1111 1111 1111", sensitive = false)
        assertTrue(state.suggestion()!!.sensitive)
        assertTrue(state.lastClip()!!.sensitive)
    }

    @Test
    fun `un texte ordinaire n est pas sensible sans drapeau`() {
        copy("Bonjour", sensitive = false)
        assertFalse(state.suggestion()!!.sensitive)
        assertFalse(state.lastClip()!!.sensitive)
    }

    @Test
    fun `une copie modifiee en numero de carte devient sensible`() {
        copy("Bonjour")
        state.onEdited("4111 1111 1111 1111")
        assertTrue(state.lastClip()!!.sensitive)
    }
}
