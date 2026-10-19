package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Annuler animée « Rembobinage » : angle visé et ressort (calculs purs). */
class UndoIconPoseTest {

    @Test
    fun `chaque appui ajoute un tour complet a l'envers`() {
        assertEquals(0f, UndoIconPose.targetAngle(0))
        assertEquals(-360f, UndoIconPose.targetAngle(1))
        assertEquals(-720f, UndoIconPose.targetAngle(2))
    }

    @Test
    fun `le ressort depasse l'angle vise puis se pose exactement dessus`() {
        val spring = SpringValue()
        val target = UndoIconPose.targetAngle(1)
        var minValue = 0f
        repeat(300) {
            spring.step(target, 1f / 60f)
            minValue = minOf(minValue, spring.value)
        }
        assertTrue(minValue < target, "dépassement attendu")
        assertTrue(spring.isSettledAngle(target))
    }

    @Test
    fun `l'ecrasement revient a la taille normale en rebondissant`() {
        val spring = SpringValue(UndoIconPose.TAP_SQUASH)
        var maxValue = 0f
        repeat(300) {
            spring.step(1f, 1f / 60f)
            maxValue = maxOf(maxValue, spring.value)
        }
        assertTrue(maxValue > 1f, "rebond attendu")
        assertTrue(spring.isSettled(1f))
    }
}
