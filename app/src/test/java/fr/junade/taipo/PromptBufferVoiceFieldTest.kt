package fr.junade.taipo

import fr.junade.taipo.ai.VoiceTextSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PromptBufferVoiceFieldTest {

    private val buffer = PromptInputBuffer()
    private var active = true
    private var changes = 0
    private val field = PromptBufferVoiceField(buffer, isActive = { active }, onChanged = { changes++ })
    private val sync = VoiceTextSync(hasSelection = { false }, onTextInserted = {})

    @Test
    fun `la dictee s'insere dans le prompt, hypothese apres hypothese`() {
        sync.applyPartial(field, "bonjour")
        assertEquals("bonjour", buffer.text)
        // Le décodeur révise l'hypothèse : elle remplace la précédente, elle ne s'y ajoute pas.
        sync.applyPartial(field, "bonjour à tous")
        assertEquals("bonjour à tous", buffer.text)
        sync.finish(field, "Bonjour à tous.")
        assertEquals("Bonjour à tous.", buffer.text)
        assertEquals(buffer.text.length, buffer.cursor)
    }

    @Test
    fun `la dictee s'insere au curseur, au milieu du texte deja saisi`() {
        buffer.insert("ab")
        buffer.moveCursor(-1)
        sync.applyPartial(field, "x")
        sync.finish(field, "xy")
        assertEquals("axyb", buffer.text)
        assertEquals(3, buffer.cursor)
    }

    @Test
    fun `annuler la dictee retire le texte insere`() {
        buffer.insert("début ")
        sync.applyPartial(field, "texte dicté")
        sync.finish(field, null)
        assertEquals("début ", buffer.text)
    }

    @Test
    fun `ce que l'utilisateur tape pendant la dictee n'est pas efface`() {
        sync.applyPartial(field, "bonjour")
        buffer.insert("!") // frappe pendant l'écoute : le texte dicté n'est plus juste avant le curseur
        sync.applyPartial(field, "bonjour à tous")
        assertEquals("bonjour! à tous", buffer.text)
    }

    @Test
    fun `une fois le mode quitte, rien n'est ecrit dans le prompt`() {
        sync.applyPartial(field, "bonjour")
        active = false
        buffer.clear() // la sortie du mode vide le tampon
        sync.applyPartial(field, "bonjour à tous")
        sync.finish(field, "Bonjour à tous.")
        assertEquals("", buffer.text)
        assertNull(field.textBeforeCursor(5))
    }

    @Test
    fun `un seul redessin par mise a jour, pas un par suppression et insertion`() {
        sync.applyPartial(field, "bon")
        assertEquals(1, changes)
        sync.applyPartial(field, "bonjour") // supprime « bon » puis insère « bonjour » : un lot
        assertEquals(2, changes)
    }

    @Test
    fun `textBeforeCursor renvoie au plus la longueur demandee`() {
        buffer.insert("bonjour")
        assertEquals("our", field.textBeforeCursor(3))
        assertEquals("bonjour", field.textBeforeCursor(50))
    }
}
