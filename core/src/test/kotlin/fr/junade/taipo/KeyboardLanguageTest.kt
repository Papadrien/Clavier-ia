package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardLanguageTest {

    @Test
    fun `le francais reste la premiere langue`() {
        assertEquals(KeyboardLanguage.FR, KeyboardLanguage.entries.first())
    }

    @Test
    fun `les codes sont uniques et en minuscules`() {
        val tags = KeyboardLanguage.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(tags.all { it == it.lowercase() && it.length == 2 })
    }

    @Test
    fun `fromTag reconnait le code, la region et la casse`() {
        assertEquals(KeyboardLanguage.PT, KeyboardLanguage.fromTag("pt"))
        assertEquals(KeyboardLanguage.PT, KeyboardLanguage.fromTag("pt-BR"))
        assertEquals(KeyboardLanguage.DE, KeyboardLanguage.fromTag("de_CH"))
        assertEquals(KeyboardLanguage.FR, KeyboardLanguage.fromTag("FR"))
    }

    @Test
    fun `fromTag renvoie null pour une langue inconnue ou absente`() {
        assertNull(KeyboardLanguage.fromTag("ja"))
        assertNull(KeyboardLanguage.fromTag(""))
        assertNull(KeyboardLanguage.fromTag(null))
    }
}

class KeyboardLanguageChoiceTest {

    @Test
    fun `une langue choisie l emporte sur celle du systeme`() {
        assertEquals(KeyboardLanguage.DE, KeyboardLanguageChoice.resolve(KeyboardLanguage.DE, "fr-FR"))
    }

    @Test
    fun `en automatique le clavier suit la langue du systeme quand il la gere`() {
        assertEquals(KeyboardLanguage.ES, KeyboardLanguageChoice.resolve(null, "es-MX"))
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(null, "en-US"))
    }

    @Test
    fun `en automatique une langue non geree donne le francais`() {
        assertEquals(KeyboardLanguage.FR, KeyboardLanguageChoice.resolve(null, "ja-JP"))
        assertEquals(KeyboardLanguage.FR, KeyboardLanguageChoice.resolve(null, null))
    }
}
