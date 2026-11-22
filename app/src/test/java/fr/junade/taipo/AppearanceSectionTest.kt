package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Pages de l'apparence ouvertes depuis l'accueil (22/11/2026) : chaque groupe de la page appartient à une seule section. */
class AppearanceSectionTest {

    @Test
    fun `les sections se partagent les six groupes sans doublon`() {
        val groups = AppearanceSection.allGroups
        assertEquals(6, groups.size)
        assertEquals(groups.size, groups.toSet().size)
    }

    @Test
    fun `chaque section se retrouve par sa cle, toute la page sans cle`() {
        for (section in AppearanceSection.entries) assertEquals(section, AppearanceSection.fromKey(section.storageKey))
        assertNull(AppearanceSection.fromKey(null))
        assertNull(AppearanceSection.fromKey("inconnue"))
    }
}
