package fr.junade.taipo

import fr.junade.taipo.analytics.AnalyticsProps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LanguageSetupTest {

    private val fr = KeyboardLanguage.FR
    private val en = KeyboardLanguage.EN
    private val es = KeyboardLanguage.ES
    private val de = KeyboardLanguage.DE

    private val automatic = LanguageSetup(chosen = null, selection = emptyList(), multilingualEnabled = false)

    @Test
    fun `sans mode multilingue la seule langue utilisee est la langue choisie ou celle du systeme`() {
        assertEquals(listOf(fr), automatic.inUse(automatic = fr))
        assertEquals(listOf(de), automatic.copy(chosen = de).inUse(automatic = fr))
    }

    @Test
    fun `une selection de moins de deux langues n active pas le mode multilingue`() {
        val setup = LanguageSetup(chosen = es, selection = listOf(en), multilingualEnabled = true)
        assertEquals(listOf(es), setup.inUse(automatic = fr))
    }

    @Test
    fun `ajouter une langue a la langue du systeme rend le clavier multilingue`() {
        val added = LanguageList.add(automatic, automatic = fr, language = en)
        assertTrue(added.multilingualEnabled)
        assertEquals(listOf(fr, en), added.selection)
        assertNull(added.chosen)
        assertEquals(listOf(fr, en), added.inUse(automatic = fr))
    }

    @Test
    fun `les langues sont rangees dans l ordre de l enumeration`() {
        val added = LanguageList.add(automatic.copy(chosen = de), automatic = fr, language = en)
        assertEquals(listOf(en, de), added.selection)
    }

    @Test
    fun `ajouter une langue deja utilisee ne change rien`() {
        val setup = LanguageList.add(automatic, automatic = fr, language = en)
        assertEquals(setup, LanguageList.add(setup, automatic = fr, language = en))
        assertEquals(automatic, LanguageList.add(automatic, automatic = fr, language = fr))
    }

    @Test
    fun `les langues disponibles sont celles qui ne sont pas utilisees`() {
        val setup = LanguageList.add(automatic, automatic = fr, language = en)
        val available = LanguageList.available(setup, automatic = fr)
        assertFalse(fr in available)
        assertFalse(en in available)
        assertEquals(KeyboardLanguage.entries.size - 2, available.size)
    }

    @Test
    fun `la derniere langue ne se retire pas`() {
        assertFalse(LanguageList.canRemove(automatic, automatic = fr, language = fr))
        assertEquals(automatic, LanguageList.remove(automatic, automatic = fr, language = fr))
    }

    @Test
    fun `une langue absente de la liste ne se retire pas`() {
        val setup = LanguageList.add(automatic, automatic = fr, language = en)
        assertFalse(LanguageList.canRemove(setup, automatic = fr, language = de))
        assertEquals(setup, LanguageList.remove(setup, automatic = fr, language = de))
    }

    @Test
    fun `retirer une langue sur trois garde le mode multilingue`() {
        var setup = LanguageList.add(automatic, automatic = fr, language = en)
        setup = LanguageList.add(setup, automatic = fr, language = es)
        val removed = LanguageList.remove(setup, automatic = fr, language = en)
        assertTrue(removed.multilingualEnabled)
        assertEquals(listOf(fr, es), removed.inUse(automatic = fr))
    }

    @Test
    fun `retirer la deuxieme langue eteint le mode et garde la langue du systeme en automatique`() {
        val setup = LanguageList.add(automatic, automatic = fr, language = en)
        val removed = LanguageList.remove(setup, automatic = fr, language = en)
        assertFalse(removed.multilingualEnabled)
        assertNull(removed.chosen)
        assertEquals(listOf(fr), removed.inUse(automatic = fr))
    }

    @Test
    fun `retirer la langue du systeme laisse l autre langue comme langue choisie`() {
        val setup = LanguageList.add(automatic, automatic = fr, language = en)
        val removed = LanguageList.remove(setup, automatic = fr, language = fr)
        assertFalse(removed.multilingualEnabled)
        assertEquals(en, removed.chosen)
        assertEquals(listOf(en), removed.inUse(automatic = fr))
    }

    @Test
    fun `la disposition d origine suit la langue`() {
        assertEquals(LetterArrangement.AZERTY, LetterArrangement.defaultFor(fr))
        assertEquals(LetterArrangement.QWERTZ, LetterArrangement.defaultFor(de))
        assertEquals(LetterArrangement.QWERTY_N, LetterArrangement.defaultFor(es))
        assertEquals(LetterArrangement.QWERTY, LetterArrangement.defaultFor(en))
        assertEquals(LetterArrangement.QWERTY, LetterArrangement.defaultFor(KeyboardLanguage.NL))
    }

    @Test
    fun `une disposition se retrouve par son code et un code inconnu donne null`() {
        LetterArrangement.entries.forEach { assertEquals(it, LetterArrangement.fromTag(it.tag)) }
        assertNull(LetterArrangement.fromTag("dvorak"))
        assertNull(LetterArrangement.fromTag(null))
    }

    @Test
    fun `chaque apercu a trois rangees de lettres`() {
        LetterArrangement.entries.forEach { assertEquals(3, it.previewRows.size, it.name) }
    }

    @Test
    fun `les codes de disposition sont des mots cles valides pour la mesure d usage`() {
        LetterArrangement.entries.forEach { assertTrue(AnalyticsProps.isKeyword(it.tag), it.tag) }
    }
}
