package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceTextSyncTest {

    /** Champ factice : le curseur est toujours en fin de texte. [available] = faux simule un champ muet. */
    private class FakeField(var text: String = "", var available: Boolean = true) : VoiceField {
        var batchDepth = 0

        override fun textBeforeCursor(length: Int): String? = if (available) text.takeLast(length) else null

        override fun deleteBeforeCursor(length: Int) {
            text = text.dropLast(length)
        }

        override fun commit(text: String) {
            this.text += text
        }

        override fun beginBatchEdit() {
            batchDepth++
        }

        override fun endBatchEdit() {
            batchDepth--
        }
    }

    private var selection = false
    private var insertions = 0
    private val sync = VoiceTextSync(hasSelection = { selection }, onTextInserted = { insertions++ })

    @Test
    fun `chaque hypothese remplace la precedente`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        sync.applyPartial(field, "bonjour tout")
        assertEquals("bonjour tout", field.text)
    }

    @Test
    fun `le decodeur peut reviser un mot deja affiche`() {
        val field = FakeField()
        sync.applyPartial(field, "la scie")
        sync.applyPartial(field, "la saisie vocale")
        assertEquals("la saisie vocale", field.text)
    }

    @Test
    fun `une hypothese identique ne touche pas au champ`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        insertions = 0
        sync.applyPartial(field, "bonjour")
        assertEquals("bonjour", field.text)
        assertEquals(0, insertions)
    }

    @Test
    fun `le texte modifie par l utilisateur est fige et jamais efface`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        field.text += " X" // l'utilisateur tape pendant la dictee
        sync.applyPartial(field, "bonjour tout")
        assertEquals("bonjour X tout", field.text)
        assertEquals(7, sync.frozenLength)
    }

    @Test
    fun `pas d espace en trop a la reprise si le texte finit deja par un blanc`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        field.text += " "
        sync.applyPartial(field, "bonjour tout")
        assertEquals("bonjour tout", field.text)
    }

    @Test
    fun `une selection suspend les mises a jour partielles mais pas la fin`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        selection = true
        sync.applyPartial(field, "bonjour tout")
        assertEquals("bonjour", field.text)
        sync.finish(field, "bonjour tout le monde")
        assertEquals("bonjour tout le monde", field.text)
    }

    @Test
    fun `finish remplace la derniere hypothese par le texte final`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour tout")
        sync.finish(field, "Bonjour tout.")
        assertEquals("Bonjour tout.", field.text)
    }

    @Test
    fun `l annulation retire le texte insere`() {
        val field = FakeField("avant ")
        sync.applyPartial(field, "bonjour")
        sync.finish(field, null)
        assertEquals("avant ", field.text)
    }

    @Test
    fun `l annulation n efface pas un texte que l utilisateur a modifie`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        field.text += "!"
        sync.finish(field, null)
        assertEquals("bonjour!", field.text)
    }

    @Test
    fun `un champ muet laisse tout en l etat`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        field.available = false
        sync.applyPartial(field, "bonjour tout")
        assertEquals("bonjour", field.text)
    }

    @Test
    fun `onTextInserted n est appele que quand du texte est insere`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        assertEquals(1, insertions)
        sync.finish(field, null)
        assertEquals(1, insertions)
    }

    @Test
    fun `les modifications par lot sont toujours refermees`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        sync.finish(field, "bonjour tout")
        assertEquals(0, field.batchDepth)
    }

    @Test
    fun `reset remet l etat a zero`() {
        val field = FakeField()
        sync.applyPartial(field, "bonjour")
        sync.reset()
        assertEquals("", sync.insertedText)
        assertEquals(0, sync.frozenLength)
        assertEquals("", sync.lastHypothesis)
        assertTrue(field.text.isNotEmpty()) // reset ne touche pas au champ
    }
}
