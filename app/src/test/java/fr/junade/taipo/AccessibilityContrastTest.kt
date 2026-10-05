package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * Lot 20 (accessibilité) : garde les contrastes de la charte (WCAG 2.1) en lisant `colors.xml`, la source unique des couleurs.
 * Texte : 4,5:1 au minimum (AA). Éléments graphiques significatifs : 3:1. Une couleur de la charte qui bougerait sous ces
 * seuils fait échouer le test ; le détail des écarts connus (touches très proches du fond, voulu par la plaquette) est dans
 * docs/accessibilite.md.
 */
class AccessibilityContrastTest {

    private val colors: Map<String, String> by lazy {
        val candidates = listOf(File("src/main/res/values/colors.xml"), File("app/src/main/res/values/colors.xml"))
        val file = candidates.firstOrNull { it.isFile } ?: fail("colors.xml introuvable (cherché dans $candidates)")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("color")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    /** Couleur ARGB d'un nom, en suivant les références `@color/...`. */
    private fun argb(name: String): Long {
        var value = colors[name] ?: fail("Couleur inconnue : $name")
        while (value.startsWith("@color/")) value = colors[value.removePrefix("@color/")] ?: fail("Référence cassée : $value")
        val hex = value.removePrefix("#")
        return when (hex.length) {
            6 -> 0xFF000000L or hex.toLong(16)
            8 -> hex.toLong(16)
            else -> fail("Format de couleur inattendu : $value")
        }
    }

    private fun channel(color: Long, shift: Int): Double = ((color shr shift) and 0xFF).toDouble()

    /** Superpose [fg] (avec son alpha) sur [bg] opaque. */
    private fun over(fg: Long, bg: Long): Long {
        val alpha = channel(fg, 24) / 255.0
        fun mix(shift: Int) = Math.round(alpha * channel(fg, shift) + (1 - alpha) * channel(bg, shift)).toLong()
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun luminance(color: Long): Double {
        fun lin(shift: Int): Double {
            val c = channel(color, shift) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    private fun contrast(foreground: String, background: String): Double {
        val bg = argb(background)
        val a = luminance(over(argb(foreground), bg))
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun assertText(foreground: String, background: String, minimum: Double = TEXT_MIN) {
        val ratio = contrast(foreground, background)
        assertTrue(ratio >= minimum, "$foreground sur $background : %.2f:1 (minimum $minimum:1)".format(ratio))
    }

    @Test
    fun `le calcul de contraste est exact sur les cas connus`() {
        assertEquals(1.0, contrast("taipo_text", "taipo_text"), 0.001)
        assertEquals(19.3, contrast("taipo_text", "taipo_background"), 0.05)
        assertEquals(5.9, contrast("taipo_text", "taipo_purple"), 0.05)
    }

    @Test
    fun `texte des touches du clavier`() {
        assertText("key_label", "key_normal")
        assertText("key_label", "key_functional")
        assertText("key_label", "key_enter")
        assertText("key_label", "key_pressed") // touche à bascule active (Maj, symboles)
        assertText("key_label", "key_popup")
        assertText("key_label", "key_popup_selection")
    }

    @Test
    fun `indices et textes attenues`() {
        // Le blanc à 60 % est composé sur chaque fond sur lequel il est posé.
        assertText("key_hint", "key_normal")
        assertText("key_hint", "key_functional")
        assertText("text_hint", "taipo_background")
        assertText("text_hint", "surface_pill")
        assertText("text_hint", "input_background")
        assertText("emoji_header_text", "taipo_background")
        assertText("clip_text_muted", "clip_item")
        assertText("clip_text_muted", "clip_item_pinned")
    }

    @Test
    fun `texte des boutons et des barres`() {
        assertText("text_primary", "surface_button")
        assertText("text_primary", "accent")
        assertText("text_primary", "action_send")
        assertText("text_primary", "action_danger") // bouton Vocal en écoute
        assertText("text_primary", "button_primary")
        assertText("text_primary", "button_secondary")
        assertText("text_primary", "dialog_background")
        assertText("text_primary", "clip_item")
        assertText("text_primary", "clip_item_pinned")
        assertText("text_primary", "clip_pin_badge") // étiquette violette
        assertText("text_primary", "settings_card")
        assertText("text_primary", "input_background")
    }

    @Test
    fun `textes d avertissement et de danger`() {
        assertText("clip_danger_text", "clip_dialog")
        assertText("settings_warning_text", "settings_card")
        assertText("settings_warning_text", "taipo_background")
    }

    @Test
    fun `elements graphiques significatifs`() {
        assertText("taipo_space_bar", "taipo_key_secondary", GRAPHIC_MIN) // barre de l'espace sur sa touche (grise)
        assertText("taipo_purple", "taipo_background", GRAPHIC_MIN) // onglet emoji, commutateur activé, focus
        assertText("input_stroke_focused", "taipo_background", GRAPHIC_MIN)
        assertText("control_unchecked", "taipo_background", GRAPHIC_MIN) // bouton radio / commutateur non coché
        assertText("switch_thumb", "switch_track_off", GRAPHIC_MIN) // pastille sur piste éteinte
    }

    @Test
    fun `les deux palettes sont identiques`() {
        val night = listOf(File("src/main/res/values-night/colors.xml"), File("app/src/main/res/values-night/colors.xml"))
            .first { it.isFile }.readText()
        val day = listOf(File("src/main/res/values/colors.xml"), File("app/src/main/res/values/colors.xml"))
            .first { it.isFile }.readText()
        assertEquals(day, night)
    }

    private companion object {
        const val TEXT_MIN = 4.5
        const val GRAPHIC_MIN = 3.0
    }
}
