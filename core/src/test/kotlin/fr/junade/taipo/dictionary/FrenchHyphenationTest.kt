package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Traits d'union oubliés (phase 1 de la refonte de l'autocorrecteur) : règles, casse, prudence. */
class FrenchHyphenationTest {

    private fun fix(text: String, truncated: Boolean = false) = FrenchHyphenation.fix(text, truncated)

    @Test
    fun `est ce que en debut de question prend son trait d'union`() {
        assertEquals("Est-ce que", fix("Est ce que"))
        assertEquals("Où est-ce que", fix("Où est ce que"))
        assertEquals("Qu'est-ce que", fix("Qu'est ce que"))
        assertEquals("Qu'est-ce qu'il", fix("Qu'est ce qu'il"))
        assertEquals("N'est-ce pas", fix("N'est ce pas"))
        assertEquals("Il dort. Est-ce qui", fix("Il dort. Est ce qui"))
    }

    @Test
    fun `est ce apres un sujet reste intact`() {
        assertNull(fix("Il est ce que"))
        assertNull(fix("Voilà ce qui est ce qu'on"))
        assertNull(fix("C'est ce que"))
        assertNull(fix("Est ce pas"))
    }

    @Test
    fun `le t euphonique est insere entre le verbe et le pronom`() {
        assertEquals("Va-t-il", fix("Va t il"))
        assertEquals("Y a-t-il", fix("Y a t il"))
        assertEquals("Aime-t-elle", fix("Aime t elle"))
        assertEquals("Que pense-t-on", fix("Que pense t on"))
    }

    @Test
    fun `l'imperatif en debut de proposition prend le trait d'union`() {
        assertEquals("Dis-moi", fix("Dis moi"))
        assertEquals("Oui, dépêche-toi", fix("Oui, dépêche toi"))
        assertEquals("Rends-lui", fix("Rends lui"))
        assertEquals("Il dort. Donne-moi", fix("Il dort. Donne moi"))
    }

    @Test
    fun `l'imperatif pas en debut de proposition ou avec un autre pronom reste intact`() {
        assertNull(fix("Elle passe lui"))
        assertNull(fix("Il me dit toi"))
        assertNull(fix("Fais le"))
        assertNull(fix("Écoutez vous"))
        assertNull(fix("Dis moi", truncated = true)) // début de texte coupé : peut-être au milieu d'une phrase
    }

    @Test
    fun `les locutions figees prennent leurs traits d'union et gardent la casse`() {
        assertEquals("Rendez-vous", fix("Rendez vous"))
        assertEquals("Il habite au-dessus", fix("Il habite au dessus"))
        assertEquals("Mon grand-père", fix("Mon grand père"))
        assertEquals("C'est-à-dire", fix("C'est a dire"))
        assertEquals("C'est-à-dire", fix("C'est à dire"))
        assertEquals("Il est là-bas", fix("Il est la bas"))
        assertEquals("Aujourd'hui", fix("Aujourd hui"))
        assertEquals("Ce week-end", fix("Ce week end"))
        assertEquals("peut-être", fix("peutetre"))
        assertEquals("Un arc-en-ciel", fix("Un arc en ciel"))
    }

    @Test
    fun `une locution se corrige apres n'importe quel mot et pas sur un texte termine par une espace`() {
        assertEquals("Les rendez-vous", fix("Les rendez vous"))
        assertNull(fix("Il a pris un autre grand"))
        assertNull(fix("Il est monté au dessus de nous ")) // texte terminé par une espace : rien à faire
    }

    @Test
    fun `l'apostrophe typographique est reconnue`() {
        assertEquals("Qu’est-ce que", fix("Qu’est ce que"))
        assertEquals("C’est-à-dire", fix("C’est a dire"))
    }
}
