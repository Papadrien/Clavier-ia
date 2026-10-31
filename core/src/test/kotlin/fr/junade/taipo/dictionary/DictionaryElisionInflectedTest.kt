package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Régression trouvée par le banc d'autocorrection (phase 0, 30/10/2026) : une élision oubliée devant un mot dont seul
 * le rétablissement des accents donne une FORME FLÉCHIE (« loles » -> « l'olés », « olés » n'étant pas dans les listes)
 * faisait planter `Dictionary.correctionFor` (NoSuchElementException dans `elidedWord`).
 */
class DictionaryElisionInflectedTest {

    private val fr = Dictionary.withFrequencies(
        mapOf("olé" to 5_000L, "la" to 1_000_000L),
        InflectionRules.FRENCH,
        100L,
    )

    @Test
    fun `une elision devant une forme flechie retablie ne plante plus et n'est pas appliquee`() {
        assertNull(fr.correctionFor("loles"))
    }
}
