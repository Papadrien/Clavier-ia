package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pose de l'icône robot animée : calculs purs (antenne, yeux, bouche). */
class RobotIconPoseTest {

    @Test
    fun `au repos la pose est celle de ic_robot`() {
        assertEquals(4f, RobotIconPose.antennaTopY(0f))
        assertEquals(0f, RobotIconPose.eyeShiftY(0f))
        assertEquals(0f, RobotIconPose.eyeSquint(0f))
        assertEquals(0f, RobotIconPose.mouthAlpha(0f))
    }

    @Test
    fun `actif l'antenne descend, les yeux montent et se plissent, la bouche apparait`() {
        assertTrue(RobotIconPose.antennaTopY(1f) > RobotIconPose.antennaTopY(0f))
        assertTrue(RobotIconPose.antennaTopY(1f) < RobotIconPose.ANTENNA_BASE_Y)
        assertTrue(RobotIconPose.eyeShiftY(1f) < 0f)
        assertTrue(RobotIconPose.eyeSquint(1f) > 0f)
        assertEquals(1f, RobotIconPose.mouthAlpha(1f))
        assertEquals(1f, RobotIconPose.mouthScaleX(1f))
    }

    @Test
    fun `le ressort dépasse sans casser la bouche ni les yeux`() {
        assertTrue(RobotIconPose.antennaTopY(1.1f) > RobotIconPose.antennaTopY(1f))
        assertEquals(1f, RobotIconPose.mouthAlpha(1.1f))
        assertEquals(0f, RobotIconPose.mouthAlpha(-0.1f))
        assertEquals(RobotIconPose.MOUTH_MIN_SCALE, RobotIconPose.mouthScaleX(-0.1f))
        assertTrue(RobotIconPose.eyeSquint(3f) <= RobotIconPose.EYE_SQUINT_MAX)
        assertEquals(0f, RobotIconPose.eyeSquint(-0.5f))
    }

    @Test
    fun `l'antenne garde une tige visible même en fin de ressort`() {
        assertTrue(RobotIconPose.ANTENNA_BASE_Y - RobotIconPose.antennaTopY(1.2f) > 0f)
    }
}
