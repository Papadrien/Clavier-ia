package fr.junade.taipo.suggestion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextWordModelTest {

    private val grin = "\uD83D\uDE05" // 😅

    /** Simule la frappe : le modèle apprend à chaque espace, comme le clavier. */
    private fun NextWordModel.type(text: String, truncated: Boolean = false) {
        text.forEachIndexed { index, c -> if (c == ' ') learn(text.substring(0, index + 1), truncated) }
    }

    @Test
    fun `decoupe en mots minuscules, fins de phrase et nombres`() {
        assertEquals(
            listOf("je", "suis", "arrivé", "^", "il", "pleut", "^"),
            NextWordModel.tokenize("Je suis arrivé. Il pleut !"),
        )
        assertEquals(listOf("mon", "code", "est", "#"), NextWordModel.tokenize("Mon code est 1234"))
        assertEquals(listOf("j'ai", "faim"), NextWordModel.tokenize("J\u2019ai, faim"))
    }

    @Test
    fun `un emoji est un seul jeton, meme compose`() {
        assertEquals(listOf("ok", grin), NextWordModel.tokenize("ok $grin"))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(listOf(family), NextWordModel.tokenize(family))
        val flag = "\uD83C\uDDEB\uD83C\uDDF7" // 🇫🇷
        assertEquals(listOf(flag, flag), NextWordModel.tokenize(flag + flag))
        assertTrue(NextWordModel.isEmoji(grin))
        assertFalse(NextWordModel.isEmoji("arrivé"))
    }

    @Test
    fun `un modele vide ne propose rien`() {
        assertTrue(NextWordModel().predict("Je suis arrivé ", false).isEmpty)
    }

    @Test
    fun `propose les mots qui suivent le plus souvent, les triplets d abord`() {
        val model = NextWordModel()
        model.type("Je suis arrivé en retard ")
        model.type("Je suis arrivé en retard ")
        model.type("Je suis arrivé à l'heure ")
        model.type("Elle est arrivée tôt ")
        assertEquals(listOf("en", "à"), model.predict("Je suis arrivé ", false).words)
    }

    @Test
    fun `au plus trois mots sont proposes`() {
        val model = NextWordModel()
        for (word in listOf("a1", "b1", "c1", "d1", "e1")) model.type("Je suis arrivé $word ")
        // Chiffres exclus : ces mots contiennent un chiffre, donc rien n'est appris.
        assertTrue(model.predict("Je suis arrivé ", false).isEmpty)
        val letters = NextWordModel()
        for (word in listOf("alpha", "bravo", "charlie", "delta", "echo")) letters.type("Je suis arrivé $word ")
        assertEquals(3, letters.predict("Je suis arrivé ", false).words.size)
    }

    @Test
    fun `l emoji n est propose qu apres deux occurrences`() {
        val model = NextWordModel()
        model.learn("Je suis arrivé $grin", false)
        assertNull(model.predict("Je suis arrivé ", false).emoji)
        model.learn("Je suis arrivé $grin", false)
        val prediction = model.predict("Je suis arrivé ", false)
        assertEquals(grin, prediction.emoji)
        assertFalse(grin in prediction.words)
    }

    @Test
    fun `propose les debuts de phrase habituels avec une majuscule`() {
        val model = NextWordModel()
        model.type("Bonjour tout le monde ")
        model.type("Bonjour à tous ")
        model.type("Merci beaucoup ")
        assertEquals(listOf("Bonjour", "Merci"), model.predict("Salut. ", false).words)
    }

    @Test
    fun `rien n est propose sans espace finale ni apres un nombre`() {
        val model = NextWordModel()
        model.type("Je suis arrivé en retard ")
        assertTrue(model.predict("Je suis arrivé", false).isEmpty)
        assertTrue(model.predict("Je suis arrivé 12 ", false).isEmpty)
    }

    @Test
    fun `un nombre n est jamais appris et coupe le contexte`() {
        val model = NextWordModel()
        model.type("Mon code est 1234 ")
        assertFalse(model.learn("Mon code est 1234 ", false))
        assertTrue(model.predict("Mon code est 1234 ", false).isEmpty)
    }

    @Test
    fun `le premier mot d une fenetre tronquee est ignore`() {
        val model = NextWordModel()
        model.learn("ur mot en ", truncated = true)
        assertEquals(listOf("en"), model.predict("mot ", false).words)
        assertTrue(model.predict("ur ", false).isEmpty)
    }

    @Test
    fun `la sauvegarde et la relecture donnent les memes propositions`() {
        val model = NextWordModel()
        model.type("Je suis arrivé en retard ")
        model.type("Je suis arrivé en retard ")
        model.learn("Je suis arrivé $grin", false)
        model.learn("Je suis arrivé $grin", false)
        val restored = NextWordModel.parse(model.serialize())
        assertEquals(model.size(), restored.size())
        assertEquals(model.predict("Je suis arrivé ", false), restored.predict("Je suis arrivé ", false))
    }

    @Test
    fun `les lignes illisibles de la sauvegarde sont ignorees`() {
        val restored = NextWordModel.parse("n'importe quoi\nB\tje\tsuis\t3\nB\tje\tsuis\tabc\nX\ta\tb\t1\n")
        assertEquals(1, restored.size())
        assertEquals(listOf("suis"), restored.predict("je ", false).words)
    }

    @Test
    fun `fusionner additionne les comptes`() {
        val a = NextWordModel()
        a.type("Je suis arrivé en retard ")
        val b = NextWordModel()
        b.type("Je suis arrivé à l'heure ")
        b.type("Je suis arrivé à l'heure ")
        a.mergeFrom(b)
        assertEquals(listOf("à", "en"), a.predict("Je suis arrivé ", false).words)
    }

    @Test
    fun `effacer remet le modele a zero`() {
        val model = NextWordModel()
        model.type("Je suis arrivé en retard ")
        model.clear()
        assertTrue(model.isEmpty())
        assertTrue(model.predict("Je suis arrivé ", false).isEmpty)
    }

    @Test
    fun `le nombre d entrees reste borne`() {
        val model = NextWordModel(maxEntries = 10)
        for (i in 0 until 30) model.type("mot${'a' + (i % 26)}x mot${'a' + ((i + 7) % 26)}y suite${'a' + ((i * 3) % 26)}z ")
        assertTrue(model.size() <= 10)
    }
}
