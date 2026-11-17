package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Boutons de la barre ([BarReveal.springFrame]) et bulle d'appui long ([PopupOpenPose.styled]) suivent le style d'animation
 * choisi (17/11/2026) : Envol monte, Goutte s'étire depuis le bas, Glisse glisse de plus loin ; tous se posent à leur place.
 */
class StyledEntranceTest {

    @Test
    fun `chaque style finit pose a sa place`() {
        MotionStyle.entries.forEach { style ->
            val end = BarReveal.springFrame(1f, style)
            assertEquals(1f, end.alpha, 0.001f, "$style")
            assertEquals(0f, end.slide, 0.03f, "$style")
            assertEquals(0f, end.rise, 0.001f, "$style")
            assertEquals(1f, end.scaleX, 0.03f, "$style")
            assertEquals(1f, end.scaleY, 0.03f, "$style")
            val open = PopupOpenPose.styled(style, 1f)
            assertEquals(1f, open.scaleX, 0.001f, "$style")
            assertEquals(1f, open.scaleY, 0.001f, "$style")
            assertEquals(0f, open.offsetY, 0.001f, "$style")
            assertEquals(1f, open.alpha, 0.001f, "$style")
        }
    }

    @Test
    fun `au depart, chaque style a son propre mouvement`() {
        val spring = BarReveal.springFrame(0f, MotionStyle.SPRING)
        assertEquals(1f, spring.slide, 0.001f)
        assertEquals(BarReveal.SPRING_START_SCALE, spring.scaleX, 0.001f)

        val flight = BarReveal.springFrame(0f, MotionStyle.FLIGHT)
        assertTrue(flight.rise > 0.3f, "Envol part d'en dessous")
        assertEquals(0f, flight.slide, 0.001f)

        val drop = BarReveal.springFrame(0f, MotionStyle.DROP)
        assertTrue(drop.fromBottom, "Goutte grandit depuis le bas")
        assertTrue(drop.scaleY < 0.3f && drop.scaleX > drop.scaleY, "Goutte part écrasée, plus large que haute")

        val glide = BarReveal.springFrame(0f, MotionStyle.GLIDE)
        assertTrue(glide.slide > 1f, "Glisse part de plus loin que Ressort")
        assertEquals(1f, glide.scaleX, 0.001f)
    }

    @Test
    fun `glisse se pose sans rebond, goutte rebondit`() {
        val glideSlides = (0..100).map { BarReveal.springFrame(it / 100f, MotionStyle.GLIDE).slide }
        assertTrue(glideSlides.all { it >= -0.0001f }, "Glisse ne dépasse pas sa place")
        val dropPeak = (0..100).maxOf { BarReveal.springFrame(it / 100f, MotionStyle.DROP).scaleY }
        assertTrue(dropPeak > 1.02f, "Goutte dépasse sa hauteur puis se pose ($dropPeak)")
    }

    @Test
    fun `bulle d appui long, envol monte et goutte s etire depuis la touche`() {
        val start = PopupOpenPose.START_SCALE
        val flight = PopupOpenPose.styled(MotionStyle.FLIGHT, start)
        assertTrue(flight.offsetY > 0.3f, "Envol part plus bas")
        assertEquals(0f, flight.alpha, 0.001f)
        val drop = PopupOpenPose.styled(MotionStyle.DROP, start)
        assertTrue(drop.scaleX > drop.scaleY, "Goutte plus large que haute au départ")
        // Ressort, Vif et Gelée gardent l'ouverture d'origine.
        val spring = PopupOpenPose.styled(MotionStyle.SPRING, 0.7f)
        assertEquals(0.7f, spring.scaleX, 0.001f)
        assertEquals(PopupOpenPose.alpha(0.7f), spring.alpha, 0.001f)
    }
}
