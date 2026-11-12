package fr.junade.taipo.voice

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpokenNumbersTest {

    private fun fr(text: String) = SpokenNumbers.toDigits(text, KeyboardLanguage.FR)

    private fun en(text: String) = SpokenNumbers.toDigits(text, KeyboardLanguage.EN)

    private val nnbsp = " "

    @Test
    fun `unites dizaines et nombres composes en francais`() {
        val cases = mapOf(
            "zéro" to "0", "deux" to "2", "dix" to "10", "onze" to "11", "seize" to "16", "dix-sept" to "17", "dix neuf" to "19",
            "vingt" to "20", "vingt et un" to "21", "vingt-et-un" to "21", "vingt-deux" to "22", "trente et une" to "31",
            "soixante-dix" to "70", "soixante et onze" to "71", "soixante-douze" to "72", "soixante dix sept" to "77",
            "quatre-vingts" to "80", "quatre-vingt-un" to "81", "quatre vingt dix" to "90", "quatre-vingt-dix-neuf" to "99",
            "septante" to "70", "septante-trois" to "73", "huitante" to "80", "octante et un" to "81", "nonante-neuf" to "99",
        )
        for ((spoken, digits) in cases) assertEquals(digits, fr(spoken), spoken)
    }

    @Test
    fun `centaines milliers et millions en francais`() {
        val cases = mapOf(
            "cent" to "100", "cent un" to "101", "deux cents" to "200", "trois cent quarante-cinq" to "345",
            "mille" to "1000", "mille deux cents" to "1200", "deux mille vingt-six" to "2026", "mille neuf cent quatre-vingt-dix" to "1990",
            "dix mille" to "10${nnbsp}000", "cinquante mille" to "50${nnbsp}000", "trois cent mille" to "300${nnbsp}000",
            "un million" to "1${nnbsp}000${nnbsp}000", "deux millions cinq cent mille" to "2${nnbsp}500${nnbsp}000",
            "un milliard" to "1${nnbsp}000${nnbsp}000${nnbsp}000", "deux-cent-trois" to "203", "mille-deux-cents" to "1200",
        )
        for ((spoken, digits) in cases) assertEquals(digits, fr(spoken), spoken)
    }

    @Test
    fun `les nombres sont remplaces dans la phrase`() {
        assertEquals("J'ai 23 ans et 2 enfants.", fr("J'ai vingt-trois ans et deux enfants."))
        assertEquals("Rendez-vous le 14 novembre à 9 heures 30.", fr("Rendez-vous le quatorze novembre à neuf heures trente."))
        assertEquals("Vingt ? Non, 30.".let { "20 ? Non, 30." }, fr("Vingt ? Non, trente."))
        assertEquals("On est en 2026", fr("On est en deux mille vingt-six"))
        assertEquals("Il en reste 3 ou 4", fr("Il en reste trois ou quatre"))
    }

    @Test
    fun `deux nombres separes restent separes`() {
        assertEquals("2 et 3", fr("deux et trois"))
        assertEquals("20, 30", fr("vingt, trente"))
        assertEquals("1000 1000", fr("mille mille"))
    }

    @Test
    fun `les mots ambigus seuls restent en lettres en francais`() {
        assertEquals("un chat et une souris", fr("un chat et une souris"))
        assertEquals("Un jour", fr("Un jour"))
        assertEquals("qu'un", fr("qu'un"))
        assertEquals("un vélo neuf.", fr("un vélo neuf."))
        assertEquals("tout neuf et propre", fr("tout neuf et propre"))
        assertEquals("9 ans", fr("neuf ans"))
        assertEquals("des millions de gens", fr("des millions de gens"))
        assertEquals("peut-être", fr("peut-être"))
        assertEquals("le premier et le deuxième", fr("le premier et le deuxième"))
    }

    @Test
    fun `decimales et pourcentages en francais`() {
        assertEquals("3,5", fr("trois virgule cinq"))
        assertEquals("3,05", fr("trois virgule zéro cinq"))
        assertEquals("2,1", fr("deux virgule un"))
        assertEquals("20$nnbsp%", fr("vingt pour cent"))
        assertEquals("Une hausse de 2,5$nnbsp% cette année", fr("Une hausse de deux virgule cinq pour cent cette année"))
        assertEquals("3 virgule", fr("trois virgule"))
    }

    @Test
    fun `nombres en anglais`() {
        val cases = mapOf(
            "zero" to "0", "two" to "2", "twelve" to "12", "twenty" to "20", "twenty-one" to "21", "forty two" to "42",
            "one hundred" to "100", "a hundred" to "100", "one hundred and five" to "105", "three hundred twenty" to "320",
            "two thousand" to "2000", "two thousand and five" to "2005", "two thousand twenty-six" to "2026",
            "fifty thousand" to "50,000", "a million" to "1,000,000", "one million two hundred thousand" to "1,200,000",
        )
        for ((spoken, digits) in cases) assertEquals(digits, en(spoken), spoken)
    }

    @Test
    fun `phrases en anglais`() {
        assertEquals("I have 2 cats and 3 dogs.", en("I have two cats and three dogs."))
        assertEquals("one of them", en("one of them"))
        assertEquals("a cat", en("a cat"))
        assertEquals("3.5", en("three point five"))
        assertEquals("20%", en("twenty percent"))
        assertEquals("15%", en("fifteen per cent"))
        assertEquals("2 and 3", en("two and three"))
    }

    @Test
    fun `les mots d'une autre langue ne changent pas`() {
        assertEquals("vingt", SpokenNumbers.toDigits("vingt", KeyboardLanguage.DE))
        assertEquals("zwanzig", fr("zwanzig"))
    }

    @Test
    fun `texte sans nombre ni mot`() {
        assertEquals("", fr(""))
        assertEquals("Bonjour, ça va ?", fr("Bonjour, ça va ?"))
        assertEquals("déjà 12", fr("déjà 12"))
    }
}
