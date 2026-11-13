package fr.junade.taipo

import fr.junade.taipo.model.AiModel
import fr.junade.taipo.model.license
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Modèle IA personnalisé : réservé au pack premium (toujours ouvert en debug), hors catalogue, version de LiteRT-LM affichée juste. */
class CustomModelAccessTest {

    @Test
    fun `pack premium ou build debug`() {
        assertTrue(CustomModelAccess.isAllowed(premium = true, debugBuild = false))
        assertTrue(CustomModelAccess.isAllowed(premium = false, debugBuild = true))
        assertFalse(CustomModelAccess.isAllowed(premium = false, debugBuild = false))
    }

    @Test
    fun `la version annoncee est celle de LiteRT-LM integree`() {
        val toml = listOf(File("../gradle/libs.versions.toml"), File("gradle/libs.versions.toml")).first { it.isFile }.readText()
        val version = Regex("""(?m)^litertlm\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        assertEquals(version, CustomModelAccess.LITERT_LM_VERSION)
        assertTrue(CustomModelAccess.LITERT_LM_VERSION.matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun `le modele personnalise est hors catalogue`() {
        assertFalse(AiModel.CUSTOM in AiModel.entriesOrdered())
        assertFalse(AiModel.CUSTOM in AiModel.entriesForDisplay())
        assertEquals(4, AiModel.entriesOrdered().size)
        assertEquals(AiModel.CUSTOM, AiModel.byId("personnalise"))
        assertNull(AiModel.CUSTOM.downloadUrl)
        assertNull(AiModel.CUSTOM.sha256)
        assertNull(AiModel.CUSTOM.license)
        assertFalse(AiModel.CUSTOM.hasAudioInput) // l'audio du modèle personnalisé se règle sur sa page
    }
}
