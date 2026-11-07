package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Règles de contexte de l'italien ([ItalianConfusions]) : corrections attendues et phrases justes jamais touchées. */
class ItalianConfusionsTest {

    private fun typed(sentence: String) =
        typeWithContext(sentence) { ItalianContextCorrector.correct(it, textMayBeTruncated = false, allowRetro = true) { 0L } }

    private fun assertFixed(expected: String, typed: String) = assertEquals(expected, typed(typed))

    private fun assertUntouched(sentence: String) = assertEquals(sentence, typed(sentence))

    @Test
    fun `e devient è quand la conjonction est impossible`() {
        assertFixed("Non è vero.", "Non e vero.")
        assertFixed("Lui è stato gentile.", "Lui e stato gentile.")
        assertFixed("Forse è meglio aspettare.", "Forse e meglio aspettare.")
        assertUntouched("Lui e lei sono andati al mare.")
        assertUntouched("Io e te dobbiamo parlare.")
    }

    @Test
    fun `c'è et po'`() {
        assertFixed("C'è un problema.", "C'e un problema.")
        assertFixed("Non c'è nessuno a casa.", "Non ce nessuno a casa.")
        assertFixed("Aspetta un po', arrivo.", "Aspetta un po, arrivo.")
        assertUntouched("Ce l'ho fatta, finalmente!")
        assertUntouched("Ce ne sono tanti in giro.")
    }

    @Test
    fun `ho ha et hanno devant un participe`() {
        assertFixed("Io ho fatto i compiti.", "Io o fatto i compiti.")
        assertFixed("Io ho fame.", "Io o fame.")
        assertFixed("Chi ha mangiato la torta?", "Chi a mangiato la torta?")
        assertFixed("Loro hanno vinto la partita.", "Loro anno vinto la partita.")
        assertUntouched("O vieni con noi o resti qui.")
        assertUntouched("Vado a casa dopo il lavoro.")
        assertUntouched("Che anno è?")
    }

    @Test
    fun `dà sì et qual è`() {
        assertFixed("Mi dà fastidio il rumore.", "Mi da fastidio il rumore.")
        assertFixed("Sì, certo.", "Si, certo.")
        assertFixed("Qual è il problema?", "Qual'è il problema?")
        assertUntouched("Vengo da Roma, e tu?")
        assertUntouched("Si dice che pioverà domani.")
    }
}
