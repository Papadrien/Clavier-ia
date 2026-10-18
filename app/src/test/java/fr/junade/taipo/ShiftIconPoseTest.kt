package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icône Maj animée (« Bond ») : états, poses de repos et ressort réglable. */
class ShiftIconPoseTest {

    @Test
    fun `l'etat vient de isShifted et isCapsLock, le verrouillage prime`() {
        assertEquals(ShiftState.OFF, ShiftState.of(isShifted = false, isCapsLock = false))
        assertEquals(ShiftState.ON, ShiftState.of(isShifted = true, isCapsLock = false))
        assertEquals(ShiftState.LOCK, ShiftState.of(isShifted = true, isCapsLock = true))
        assertEquals(ShiftState.LOCK, ShiftState.of(isShifted = false, isCapsLock = true))
    }

    @Test
    fun `minuscule : fleche creuse, droite, sans barre`() {
        assertEquals(0f, ShiftIconPose.restFill(ShiftState.OFF))
        assertEquals(1f, ShiftIconPose.restScaleY(ShiftState.OFF))
        assertEquals(0f, ShiftIconPose.restLift(ShiftState.OFF))
        assertEquals(0f, ShiftIconPose.restBarOpacity(ShiftState.OFF))
    }

    @Test
    fun `majuscule : fleche pleine de taille normale, sans barre`() {
        assertEquals(1f, ShiftIconPose.restFill(ShiftState.ON))
        assertEquals(1f, ShiftIconPose.restScaleY(ShiftState.ON))
        assertEquals(0f, ShiftIconPose.restBarOpacity(ShiftState.ON))
    }

    @Test
    fun `verrouillee : fleche pleine raccourcie et barre dessous`() {
        assertEquals(1f, ShiftIconPose.restFill(ShiftState.LOCK))
        assertEquals(1f, ShiftIconPose.restBarOpacity(ShiftState.LOCK))
        assertEquals(1f, ShiftIconPose.restBarScale(ShiftState.LOCK))
        // La pointe passe de 3,5 à 2,5 et la base de 19 à 16,5, comme l'ancienne icône verrouillée.
        val tip = ShiftIconPose.PIVOT_Y + (3.5f - ShiftIconPose.PIVOT_Y) * ShiftIconPose.LOCK_SCALE_Y + ShiftIconPose.LOCK_LIFT
        val base = ShiftIconPose.PIVOT_Y + ShiftIconPose.LOCK_LIFT
        assertEquals(2.5f, tip, 1e-3f)
        assertEquals(16.5f, base, 1e-3f)
        assertEquals(-2.5f, ShiftIconPose.LOCK_LIFT, 1e-3f)
    }

    @Test
    fun `le ressort reglable rejoint sa cible en depassant avec un amortissement faible`() {
        val spring = TunedSpring(0f)
        spring.go(1f, 240f, 9f)
        var peak = 0f
        repeat(200) {
            spring.step(0.01f)
            if (spring.value > peak) peak = spring.value
        }
        assertTrue(peak > 1.1f, "rebond attendu")
        assertEquals(1f, spring.value, 1e-6f)
        assertTrue(spring.isSettled)
    }

    @Test
    fun `la vitesse de depart lance le ressort dans son sens`() {
        val spring = TunedSpring(0f)
        spring.go(0f, 200f, 8f, startVelocity = -75f)
        spring.step(0.05f)
        assertTrue(spring.value < -0.5f)
        assertFalse(spring.isSettled)
        spring.jump(3f)
        assertEquals(3f, spring.value)
        assertEquals(3f, spring.target)
        assertEquals(0f, spring.velocity)
        assertTrue(spring.isSettled)
    }
}
