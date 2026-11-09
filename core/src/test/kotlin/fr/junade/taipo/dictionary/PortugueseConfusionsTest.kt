package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Règles de contexte du portugais ([PortugueseConfusions]) : corrections attendues et phrases justes jamais touchées. */
class PortugueseConfusionsTest {

    private fun typed(sentence: String) =
        typeWithContext(sentence) { PortugueseContextCorrector.correct(it, textMayBeTruncated = false, allowRetro = true) { 0L } }

    private fun assertFixed(expected: String, typed: String) = assertEquals(expected, typed(typed))

    private fun assertUntouched(sentence: String) = assertEquals(sentence, typed(sentence))

    @Test
    fun `e devient é quand la conjonction est impossible`() {
        assertFixed("Não é verdade.", "Não e verdade.")
        assertFixed("Isso é verdade.", "Isso e verdade.")
        assertFixed("Ela é muito simpática.", "Ela e muito simpática.")
        assertUntouched("Ele e ela foram ao cinema.")
        assertUntouched("Isso e aquilo são diferentes.")
    }

    @Test
    fun `esta devient está apres un sujet`() {
        assertFixed("Você está bem?", "Você esta bem?")
        assertFixed("Onde está o meu livro?", "Onde esta o meu livro?")
        assertUntouched("Esta casa é muito bonita.")
        assertUntouched("Como esta casa é bonita!")
    }

    @Test
    fun `há mais et a gente`() {
        assertFixed("Moro aqui há muito tempo.", "Moro aqui a muito tempo.")
        assertFixed("Nunca mais faço isso.", "Nunca mas faço isso.")
        assertFixed("Cada vez mais difícil.", "Cada vez mas difícil.")
        assertFixed("A gente vai amanhã.", "Agente vai amanhã.")
        assertUntouched("O agente da polícia chegou cedo.")
        assertUntouched("Mas eu não quero ir.")
        assertUntouched("Vou a Lisboa amanhã.")
    }

    @Test
    fun `mots soudes`() {
        assertFixed("Com certeza vou.", "Concerteza vou.")
        assertFixed("Vamos tentar de novo.", "Vamos tentar denovo.")
    }
}
