package fr.junade.taipo.dictionary

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Audit UX-01 : la liste de démarrage embarquée est le début exact de la liste complète du dépôt (mêmes mots, mêmes
 * fréquences). Pour la régénérer : `head -40000 dictionaries/<code>.txt > app/src/main/assets/dictionaries/starter/<code>.txt`.
 */
class StarterDictionaryTest {

    private fun file(vararg candidates: String): File = candidates.map(::File).first { it.isFile }

    @Test
    fun `la liste de demarrage est le debut de la liste complete`() {
        for (language in DictionaryLoader.STARTER_LANGUAGES) {
            val starter = file(
                "src/main/assets/${DictionaryLoader.starterAssetPath(language)}",
                "app/src/main/assets/${DictionaryLoader.starterAssetPath(language)}",
            ).readLines(Charsets.UTF_8)
            val full = file("../dictionaries/${language.tag}.txt", "dictionaries/${language.tag}.txt").readLines(Charsets.UTF_8)
            assertEquals(STARTER_LINES, starter.size, language.tag)
            assertEquals(full.take(STARTER_LINES), starter, language.tag)
            assertTrue(full.size > starter.size, language.tag)
        }
    }

    private companion object {
        const val STARTER_LINES = 40_000
    }
}
