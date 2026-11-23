package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône du bouton IA « Panneau qui se dessine » (21/11/2026) : étincelles au repos, quatre cases panneau ouvert. */
class AiPanelIconPoseTest {

    @Test
    fun `au repos les etincelles, ouvert les quatre cases`() {
        assertEquals(1f, AiPanelIconPose.sparkAlpha(0f))
        assertEquals(1f, AiPanelIconPose.sparkScale(0f))
        AiPanelIconPose.TILES.indices.forEach { assertEquals(0f, AiPanelIconPose.tileScale(it, 0f)) }
        assertEquals(0f, AiPanelIconPose.sparkAlpha(1f))
        AiPanelIconPose.TILES.indices.forEach { assertEquals(1f, AiPanelIconPose.tileScale(it, 1f), 0.0001f) }
    }

    @Test
    fun `les cases apparaissent l une apres l autre`() {
        val w = 0.3f
        assertTrue(AiPanelIconPose.tileScale(0, w) > AiPanelIconPose.tileScale(1, w))
        assertTrue(AiPanelIconPose.tileScale(1, w) > AiPanelIconPose.tileScale(2, w))
        assertEquals(0f, AiPanelIconPose.tileScale(3, w))
        // Le ressort dépasse un peu : les cases aussi.
        assertTrue(AiPanelIconPose.tileScale(0, 1.1f) > 1f)
    }
}
