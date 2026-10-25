package fr.junade.taipo.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptConversationTest {

    @Test
    fun `une conversation neuve est vide`() {
        val conversation = PromptConversation()
        assertTrue(conversation.isEmpty)
        assertFalse(conversation.isGenerating)
        assertTrue(conversation.messages.isEmpty())
        assertTrue(conversation.history().isEmpty())
    }

    @Test
    fun `start ouvre un echange en cours et retire les espaces de bord`() {
        val conversation = PromptConversation()
        assertTrue(conversation.start("  Bonjour  \n"))
        assertFalse(conversation.isEmpty)
        assertTrue(conversation.isGenerating)
        assertEquals(
            listOf(PromptMessage("Bonjour", "", PromptMessageStatus.IN_PROGRESS)),
            conversation.messages,
        )
    }

    @Test
    fun `un prompt vide ou blanc est refuse`() {
        val conversation = PromptConversation()
        assertFalse(conversation.start(""))
        assertFalse(conversation.start("  \n "))
        assertTrue(conversation.isEmpty)
    }

    @Test
    fun `un nouveau prompt est refuse pendant une generation`() {
        val conversation = PromptConversation()
        conversation.start("un")
        assertFalse(conversation.start("deux"))
        assertEquals(1, conversation.messages.size)
    }

    @Test
    fun `les morceaux s accumulent dans la reponse en cours`() {
        val conversation = PromptConversation()
        conversation.start("Salut")
        conversation.appendResponse("Bon")
        conversation.appendResponse("jour")
        conversation.appendResponse("")
        assertEquals("Bonjour", conversation.messages.single().response)
        assertTrue(conversation.isGenerating)
    }

    @Test
    fun `appendResponse sans echange en cours est sans effet`() {
        val conversation = PromptConversation()
        conversation.appendResponse("x")
        assertTrue(conversation.isEmpty)
        conversation.start("a")
        conversation.appendResponse("b")
        conversation.complete()
        conversation.appendResponse("c")
        assertEquals("b", conversation.messages.single().response)
    }

    @Test
    fun `complete termine l echange et le met dans l historique`() {
        val conversation = PromptConversation()
        conversation.start("Salut")
        conversation.appendResponse("Bonjour !")
        conversation.complete()
        assertFalse(conversation.isGenerating)
        assertEquals(PromptMessageStatus.COMPLETED, conversation.messages.single().status)
        assertEquals(listOf(ChatExchange("Salut", "Bonjour !")), conversation.history())
    }

    @Test
    fun `finish avec un texte final remplace la reponse accumulee`() {
        val conversation = PromptConversation()
        conversation.start("Salut")
        conversation.appendResponse("Bon")
        conversation.finish("Bonjour !", completed = true)
        assertEquals("Bonjour !", conversation.messages.single().response)
    }

    @Test
    fun `interrupt garde la reponse partielle dans la bulle et l historique`() {
        val conversation = PromptConversation()
        conversation.start("Raconte")
        conversation.appendResponse("Il était une")
        conversation.interrupt()
        assertFalse(conversation.isGenerating)
        assertEquals(
            PromptMessage("Raconte", "Il était une", PromptMessageStatus.INTERRUPTED),
            conversation.messages.single(),
        )
        assertEquals(listOf(ChatExchange("Raconte", "Il était une")), conversation.history())
    }

    @Test
    fun `un echange interrompu sans reponse reste affiche mais pas dans l historique`() {
        val conversation = PromptConversation()
        conversation.start("Raconte")
        conversation.interrupt()
        assertEquals(1, conversation.messages.size)
        assertTrue(conversation.history().isEmpty())
    }

    @Test
    fun `l historique exclut l echange en cours et garde l ordre`() {
        val conversation = PromptConversation()
        conversation.start("un")
        conversation.appendResponse(" r1 ")
        conversation.complete()
        conversation.start("deux")
        conversation.appendResponse("r2")
        conversation.complete()
        conversation.start("trois")
        conversation.appendResponse("r3 partielle")
        assertEquals(
            listOf(ChatExchange("un", "r1"), ChatExchange("deux", "r2")),
            conversation.history(),
        )
    }

    @Test
    fun `finish sans echange en cours est sans effet`() {
        val conversation = PromptConversation()
        conversation.finish("x", completed = true)
        assertTrue(conversation.isEmpty)
    }

    @Test
    fun `discardInProgress retire l echange en cours et rend son prompt`() {
        val conversation = PromptConversation()
        conversation.start("un")
        conversation.appendResponse("r1")
        conversation.complete()
        conversation.start("deux")
        assertEquals("deux", conversation.discardInProgress())
        assertEquals(1, conversation.messages.size)
        assertFalse(conversation.isGenerating)
        assertNull(conversation.discardInProgress())
    }

    @Test
    fun `clear efface tout`() {
        val conversation = PromptConversation()
        conversation.start("un")
        conversation.appendResponse("r1")
        conversation.clear()
        assertTrue(conversation.isEmpty)
        assertFalse(conversation.isGenerating)
        assertTrue(conversation.start("deux"))
    }

    @Test
    fun `messages renvoie une copie`() {
        val conversation = PromptConversation()
        conversation.start("un")
        val snapshot = conversation.messages
        conversation.appendResponse("r1")
        assertEquals("", snapshot.single().response)
    }

    // --- Story 5.2 : contexte du champ -----------------------------------------------------------

    @Test
    fun `contextToAttach joint le texte au premier prompt`() {
        val conversation = PromptConversation()
        assertEquals("Salut", conversation.contextToAttach("Salut"))
    }

    @Test
    fun `un champ vide ne joint rien`() {
        assertNull(PromptConversation().contextToAttach(null))
    }

    @Test
    fun `un texte inchange depuis le dernier envoi n est pas rejoint`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.appendResponse("Bonjour")
        conversation.complete()
        assertEquals("Salut", conversation.lastSentContext())
        assertNull(conversation.contextToAttach("Salut"))
        assertEquals("Salut Paul", conversation.contextToAttach("Salut Paul"))
    }

    @Test
    fun `l historique rejoue le texte reellement envoye mais la bulle garde le prompt`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.appendResponse("Bonjour")
        conversation.complete()
        assertEquals("Reformule", conversation.messages.single().prompt)
        assertEquals(
            listOf(ChatExchange("Champ :\n\"\"\"\nSalut\n\"\"\"\n\nReformule", "Bonjour")),
            conversation.history(),
        )
    }

    @Test
    fun `un echange interrompu sans reponse ne compte pas comme contexte envoye`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.interrupt() // stop avant le premier morceau : rien dans l'historique
        assertNull(conversation.lastSentContext())
        assertEquals("Salut", conversation.contextToAttach("Salut"))
    }

    @Test
    fun `un echange retire apres une erreur ne compte pas comme contexte envoye`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.discardInProgress()
        assertNull(conversation.lastSentContext())
        assertEquals("Salut", conversation.contextToAttach("Salut"))
    }

    @Test
    fun `une reponse partielle interrompue compte comme contexte envoye`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.appendResponse("Bon")
        conversation.interrupt()
        assertNull(conversation.contextToAttach("Salut"))
    }

    @Test
    fun `un tour sans contexte garde le dernier texte envoye`() {
        val conversation = PromptConversation()
        conversation.start("Reformule", fieldContext = "Salut", contextHeader = "Champ :")
        conversation.appendResponse("Bonjour")
        conversation.complete()
        conversation.start("Plus court") // texte inchangé : prompt seul
        conversation.appendResponse("Salut !")
        conversation.complete()
        assertEquals("Salut", conversation.lastSentContext())
        assertEquals(
            listOf("Champ :\n\"\"\"\nSalut\n\"\"\"\n\nReformule", "Plus court"),
            conversation.history().map { it.prompt },
        )
    }
}
