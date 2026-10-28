package fr.junade.taipo

import fr.junade.taipo.dictionary.NextLetterModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Zones de frappe adaptatives, étape 4 : le mot dont on prédit la lettre suivante, d'après le texte avant le curseur. */
class NextLetterContextTest {

    private val delta = 1e-9

    private val model = NextLetterModel.fromFrequencies(
        mapOf(
            "bonjour" to 100L, "bon" to 50L, "time" to 80L, "timber" to 10L, "aujourd'hui" to 40L,
            "étoile" to 30L, "être" to 20L, "peut-être" to 15L, "ami" to 10L, "maison" to 10L,
        ),
    )

    private fun predict(text: String) = NextLetterContext.predict(model, text)

    @Test
    fun `le mot en cours donne la lettre suivante`() {
        val p = predict("Il fait tim")!!
        assertEquals(80.0 / 90.0, p.of('e'), delta)
        assertEquals(10.0 / 90.0, p.of('b'), delta)
    }

    @Test
    fun `apres une espace ou une ponctuation la prediction porte sur la premiere lettre`() {
        listOf("", "Bonjour ", "Bonjour, ", "(", "Fin.\n").forEach { text ->
            val p = predict(text)
            assertNotNull(p, "texte « $text »")
            assertEquals(0.0, p!!.endOfWord, 0.0)
            assertEquals(1.0, p.next.values.sum(), delta)
        }
    }

    @Test
    fun `un mot inconnu ou un debut de mot rare n a pas de prediction`() {
        assertNull(predict("Il s'appelle xqz"))
        assertNull(predict("zzz"))
    }

    @Test
    fun `une elision ou un trait d union repart du mot qui suit`() {
        // « l' » : aucun mot du dictionnaire ne commence ainsi, la prédiction porte sur la première lettre du mot suivant.
        val afterElision = predict("Voici l'")!!
        assertEquals(afterElision.next, predict("Voici l\u2019")!!.next)
        assertEquals(0.0, afterElision.endOfWord, 0.0)
        // « peut-ê » : le mot complet est connu, la prédiction vient de lui.
        assertEquals(1.0, predict("peut-ê")!!.of('t'), delta)
        // « xx-ê » : le mot complet est inconnu, on repart après le trait d'union.
        assertEquals(1.0, predict("xx-ê")!!.of('t'), delta)
        // Après l'apostrophe d'un mot du dictionnaire (« aujourd' »), la lettre est celle de ce mot.
        assertEquals(1.0, predict("aujourd'")!!.of('h'), delta)
    }

    @Test
    fun `un nombre une adresse ou un identifiant n ont pas de prediction`() {
        assertNull(predict("rdv 12b"))
        assertNull(predict("jean@exemple"))
        assertNull(predict("https://exemple"))
        assertNull(predict("mon_pseudo"))
    }

    @Test
    fun `les accents et la casse sont replies par le modele`() {
        assertEquals(predict("etoi")!!.next, predict("Étoi")!!.next)
    }
}
