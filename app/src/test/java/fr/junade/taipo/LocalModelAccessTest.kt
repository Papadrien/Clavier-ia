package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalModelAccessTest {

    @Test
    fun `la ligne locale est visible en debug seulement`() {
        assertTrue(LocalModelAccess.isVisible(debugBuild = true))
        assertFalse(LocalModelAccess.isVisible(debugBuild = false))
    }

    @Test
    fun `un modele charge a la main s affiche Fichier local en debug`() {
        assertTrue(LocalModelAccess.showsAsLocalFile(debugBuild = true, downloaded = false))
    }

    @Test
    fun `un modele telecharge n est jamais Fichier local`() {
        assertFalse(LocalModelAccess.showsAsLocalFile(debugBuild = true, downloaded = true))
        assertFalse(LocalModelAccess.showsAsLocalFile(debugBuild = false, downloaded = true))
    }

    @Test
    fun `hors debug aucun modele n est presente comme fichier local`() {
        assertFalse(LocalModelAccess.showsAsLocalFile(debugBuild = false, downloaded = false))
    }
}
