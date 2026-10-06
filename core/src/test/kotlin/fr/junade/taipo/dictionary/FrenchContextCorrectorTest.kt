package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrenchContextCorrectorTest {

    /** Mots « connus » (fréquence suffisante) : formes à la fois infinitives et participiales des verbes testés. */
    private val known = setOf(
        "manger", "mangé", "parler", "parlé", "arriver", "arrivé", "partir", "parti", "partie", "finir", "fini",
        "faire", "fait", "voir", "vu", "passer", "passé", "danger", "plaisir", "côté", "demain", "aller", "allé",
        "dîner", "dîné", "marché", "marcher", "été",
    )
    private val frequencyOf: (String) -> Long = { word -> if (word in known) 1_000L else 0L }

    private fun correct(text: String, truncated: Boolean = false, retro: Boolean = true): String? =
        FrenchContextCorrector.correct(text, truncated, retro, frequencyOf)

    // --- a / à ---

    @Test
    fun `a en debut de phrase devient a accent grave avec le mot suivant`() {
        assertEquals("À demain", correct("A demain"))
        assertEquals("à demain", correct("a demain"))
        assertEquals("Salut. À demain", correct("Salut. A demain"))
    }

    @Test
    fun `un a seul au tout debut du texte attend le mot suivant`() {
        assertNull(correct("a"))
        assertNull(correct("A"))
    }

    @Test
    fun `a apres aller devient a accent grave sans attendre le mot suivant`() {
        assertEquals("Je vais à", correct("Je vais a"))
        assertEquals("il est allé à", correct("il est allé a"))
    }

    @Test
    fun `jusqu'a devient jusqu'a accent grave`() {
        assertEquals("jusqu'à", correct("jusqu'a"))
    }

    @Test
    fun `a devant un infinitif apres un mot qui exclut le verbe avoir`() {
        assertEquals("quelque chose à manger", correct("quelque chose a manger"))
        assertEquals("j'ai à manger", correct("j'ai a manger"))
        assertEquals("je n'ai rien à faire", correct("je n'ai rien a faire"))
    }

    @Test
    fun `a verbe n'est pas touche`() {
        assertNull(correct("il a mangé"))
        assertNull(correct("elle a"))
        assertNull(correct("il y a"))
        assertNull(correct("il y a du monde"))
        assertNull(correct("qui a fait"))
    }

    @Test
    fun `a devant un infinitif apres un nom reste inchange car ambigu`() {
        assertNull(correct("le chat a manger"))
    }

    @Test
    fun `pas de revision a rebours quand elle est desactivee`() {
        assertNull(correct("quelque chose a manger", retro = false))
    }

    @Test
    fun `une fenetre tronquee ne fait pas de debut de phrase`() {
        assertNull(correct("a demain", truncated = true))
    }

    // --- participe passé / infinitif ---

    @Test
    fun `infinitif apres un auxiliaire devient un participe`() {
        assertEquals("il a mangé", correct("il a manger"))
        assertEquals("j'ai parlé", correct("j'ai parler"))
        assertEquals("ils ont mangé", correct("ils ont manger"))
        assertEquals("tu as fait", correct("tu as faire"))
        assertEquals("je n'ai pas mangé", correct("je n'ai pas manger"))
        assertEquals("on a vu", correct("on a voir"))
        assertEquals("elle l'a fini", correct("elle l'a finir"))
    }

    @Test
    fun `etre et un infinitif donnent un participe accorde avec elle`() {
        assertEquals("il est parti", correct("il est partir"))
        assertEquals("elle est partie", correct("elle est partir"))
        assertEquals("je suis arrivé", correct("je suis arriver"))
    }

    @Test
    fun `c'est et un infinitif ne changent pas`() {
        assertNull(correct("c'est manger"))
        assertNull(correct("le but est manger"))
    }

    @Test
    fun `un a ambigu ne rend pas l'auxiliaire certain`() {
        assertNull(correct("le chat a manger", retro = false))
        assertEquals("Paul a mangé", correct("Paul a manger"))
    }

    @Test
    fun `participe apres un modal devient un infinitif`() {
        assertEquals("il faut manger", correct("il faut mangé"))
        assertEquals("je vais manger", correct("je vais mangé"))
        assertEquals("je veux pas manger", correct("je veux pas mangé"))
        assertEquals("pour manger", correct("pour mangé"))
        assertEquals("il a pu manger", correct("il a pu mangé"))
    }

    @Test
    fun `un nom en e accent aigu n'est jamais change en infinitif`() {
        assertNull(correct("à côté"))
        assertNull(correct("il va au marché"))
        assertNull(correct("pour passé"))
        assertNull(correct("en été"))
    }

    @Test
    fun `un participe apres un auxiliaire reste inchange`() {
        assertNull(correct("il a mangé"))
        assertNull(correct("j'ai parlé"))
        assertNull(correct("je dois manger"))
    }

    @Test
    fun `un nom en er n'est pas pris pour un infinitif`() {
        assertNull(correct("il a danger"))
        assertNull(correct("j'ai plaisir"))
    }

    @Test
    fun `la casse du mot corrige est conservee`() {
        assertEquals("il a Mangé", correct("il a Manger"))
    }

    @Test
    fun `rien a corriger sur un texte vide ou termine par une espace`() {
        assertNull(correct(""))
        assertNull(correct("il a manger "))
    }
}
