package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pages des paramètres du clavier ouvertes depuis l'accueil (22/11/2026) : clé de la section, toutes les sections sans clé. */
class KeyboardSettingsSectionTest {

    @Test
    fun `chaque section se retrouve par sa cle`() {
        for (section in KeyboardSettingsSection.entries) assertEquals(section, KeyboardSettingsSection.fromKey(section.storageKey))
        assertEquals(KeyboardSettingsSection.entries.size, KeyboardSettingsSection.entries.map { it.storageKey }.toSet().size)
    }

    @Test
    fun `sans cle ou cle inconnue, toutes les sections`() {
        assertNull(KeyboardSettingsSection.fromKey(null))
        assertNull(KeyboardSettingsSection.fromKey("inconnue"))
    }
}
