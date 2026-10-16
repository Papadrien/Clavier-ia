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
        assertEquals(KeyboardLanguage.DE, KeyboardLanguageChoice.resolve(KeyboardLanguage.DE, listOf("fr-FR")))
    }

    @Test
    fun `en automatique le clavier suit la langue du systeme quand il la gere`() {
        assertEquals(KeyboardLanguage.ES, KeyboardLanguageChoice.resolve(null, listOf("es-MX")))
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(null, listOf("en-US")))
    }

    @Test
    fun `en automatique une langue non geree donne l anglais`() {
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(null, listOf("ja-JP")))
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(null, emptyList()))
    }

    @Test
    fun `en automatique la premiere langue geree de la liste est retenue`() {
        assertEquals(KeyboardLanguage.FR, KeyboardLanguageChoice.resolve(null, listOf("ja-JP", "fr-FR", "en-US")))
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(null, listOf("pl-PL", "en-GB")))
    }

    @Test
    fun `en multilingue la langue active est prise dans la liste`() {
        val langs = listOf(KeyboardLanguage.FR, KeyboardLanguage.EN)
        assertEquals(KeyboardLanguage.EN, KeyboardLanguageChoice.resolve(KeyboardLanguage.DE, listOf("es-ES"), langs, KeyboardLanguage.EN))
        // Langue active absente de la liste (décochée depuis) : première langue de la liste.
        assertEquals(KeyboardLanguage.FR, KeyboardLanguageChoice.resolve(null, listOf("es-ES"), langs, KeyboardLanguage.DE))
        assertEquals(KeyboardLanguage.FR, KeyboardLanguageChoice.resolve(null, listOf("es-ES"), langs, null))
    }

    @Test
    fun `une seule langue cochee n active pas le mode multilingue`() {
        assertEquals(
            KeyboardLanguage.ES,
            KeyboardLanguageChoice.resolve(null, listOf("es-ES"), listOf(KeyboardLanguage.FR), KeyboardLanguage.FR),
        )
    }
}

class MultilingualChoiceTest {

    @Test
    fun `le changement de langue est circulaire`() {
        val langs = listOf(KeyboardLanguage.FR, KeyboardLanguage.EN, KeyboardLanguage.DE)
        assertEquals(KeyboardLanguage.EN, MultilingualChoice.next(langs, KeyboardLanguage.FR))
        assertEquals(KeyboardLanguage.DE, MultilingualChoice.next(langs, KeyboardLanguage.EN))
        assertEquals(KeyboardLanguage.FR, MultilingualChoice.next(langs, KeyboardLanguage.DE))
    }

    @Test
    fun `une langue hors liste mene a la premiere`() {
        assertEquals(KeyboardLanguage.FR, MultilingualChoice.next(listOf(KeyboardLanguage.FR, KeyboardLanguage.EN), KeyboardLanguage.NL))
    }

    @Test
    fun `moins de deux langues ne change rien`() {
        assertEquals(null, MultilingualChoice.next(emptyList(), KeyboardLanguage.FR))
        assertEquals(null, MultilingualChoice.next(listOf(KeyboardLanguage.FR), KeyboardLanguage.FR))
    }

    @Test
    fun `encodage et decodage conservent l ordre de l enumeration et ignorent l inconnu`() {
        assertEquals("fr,en", MultilingualChoice.encode(listOf(KeyboardLanguage.EN, KeyboardLanguage.FR)))
        assertEquals(listOf(KeyboardLanguage.FR, KeyboardLanguage.DE), MultilingualChoice.decode("de, xx ,fr,fr"))
        assertEquals(emptyList<KeyboardLanguage>(), MultilingualChoice.decode(null))
        assertEquals(emptyList<KeyboardLanguage>(), MultilingualChoice.decode(""))
    }
}
