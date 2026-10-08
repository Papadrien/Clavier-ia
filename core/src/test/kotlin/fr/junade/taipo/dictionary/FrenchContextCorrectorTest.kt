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

    // --- accent oublié d'un participe après un auxiliaire ---

    /** Fréquences réalistes (ordre de grandeur des listes) : le rapport -é / -e décide, pas la seule présence. */
    private val accentFrequencies = mapOf(
        "parle" to 120_000L, "parlé" to 89_000L,
        "pense" to 240_000L, "pensé" to 57_000L,
        "calme" to 44_000L, "calmé" to 1_000L,
        "envie" to 100_000L, "envié" to 200L,
        "reste" to 150_000L, "resté" to 12_000L,
        "arrive" to 150_000L, "arrivé" to 90_000L, "arrivée" to 20_000L,
    )

    private fun correctAccent(text: String): String? =
        FrenchContextCorrector.correct(text, false, true) { accentFrequencies[it] ?: 0L }

    @Test
    fun `le participe oublie son accent apres un auxiliaire`() {
        assertEquals("il a parlé", correctAccent("il a parle"))
        assertEquals("j'ai pensé", correctAccent("j'ai pense"))
        assertEquals("nous avons parlé", correctAccent("nous avons parle"))
        assertEquals("il a bien parlé", correctAccent("il a bien parle"))
        assertEquals("il a Parlé", correctAccent("il a Parle"))
    }

    @Test
    fun `le participe oublie son accent avec etre et s'accorde avec elle`() {
        assertEquals("il est arrivé", correctAccent("il est arrive"))
        assertEquals("elle est arrivée", correctAccent("elle est arrive"))
    }

    @Test
    fun `les noms et adjectifs en e ne deviennent jamais des participes`() {
        // Forme en -é trop rare face à la forme en -e, ou pas assez fréquente pour être un vrai mot.
        assertNull(correctAccent("j'ai envie"))
        assertNull(correctAccent("il est calme"))
        assertNull(correctAccent("il a reste"))
    }

    @Test
    fun `sans auxiliaire clair l'accent d'un mot en e n'est pas ajoute`() {
        assertNull(correctAccent("il parle"))
        assertNull(correctAccent("le chat parle"))
        assertNull(correctAccent("parle"))
        // « il y a » : « a » est le verbe, mais « il y a pense » n'est pas un passé composé.
        assertNull(correctAccent("il y a pense"))
        // « c'est » n'est pas l'auxiliaire d'un passé composé.
        assertNull(correctAccent("c'est arrive"))
    }
}
