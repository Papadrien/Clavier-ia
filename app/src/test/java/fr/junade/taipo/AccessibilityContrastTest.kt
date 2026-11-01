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
 *
 * Thèmes du clavier (lot T3) : les mêmes vérifications tournent sur la palette sombre (`values-night`) et sur la palette claire
 * (`values`). Le texte posé sur un fond violet utilise `text_on_accent` (blanc dans les deux palettes), pas `text_primary`.
 */
class AccessibilityContrastTest {

    /** Palette en cours de vérification (posée par [onPalette]). */
    private var colors: Map<String, String> = emptyMap()
    private var paletteName = ""

    private fun load(directory: String): Map<String, String> {
        val candidates = listOf(File("src/main/res/$directory/colors.xml"), File("app/src/main/res/$directory/colors.xml"))
        val file = candidates.firstOrNull { it.isFile } ?: fail("colors.xml introuvable (cherché dans $candidates)")
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("color")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun onPalette(directory: String, block: () -> Unit) {
        colors = load(directory)
        paletteName = directory
        block()
    }

    /** Exécute [block] sur la palette sombre, puis sur la palette claire. */
    private fun eachPalette(block: () -> Unit) {
        onPalette("values-night", block)
        onPalette("values", block)
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
        fun mix(shift: Int) = Math.round(alpha * channel(fg, shift) + (1 - alpha) * channel(bg, shift))
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
        assertTrue(ratio >= minimum, "[$paletteName] $foreground sur $background : %.2f:1 (minimum $minimum:1)".format(ratio))
    }

    @Test
    fun `le calcul de contraste est exact sur les cas connus`() {
        onPalette("values-night") {
            assertEquals(1.0, contrast("taipo_text", "taipo_text"), 0.001)
            assertEquals(19.3, contrast("taipo_text", "taipo_background"), 0.05)
            assertEquals(5.9, contrast("taipo_text", "taipo_purple"), 0.05)
            assertEquals(5.9, contrast("text_on_accent", "taipo_purple"), 0.05)
        }
    }

    @Test
    fun `texte des touches du clavier`() {
        eachPalette {
            assertText("key_label", "key_normal")
            assertText("key_label", "key_functional")
            assertText("text_on_accent", "key_enter")
            assertText("key_label", "key_pressed") // touche à bascule active (Maj, symboles)
            assertText("key_label", "key_popup")
            assertText("text_on_accent", "key_popup_selection")
        }
    }

    @Test
    fun `indices et textes attenues`() {
        eachPalette {
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
    }

    @Test
    fun `texte des boutons et des barres`() {
        eachPalette {
            assertText("text_primary", "surface_button")
            assertText("text_on_accent", "accent")
            assertText("text_on_accent", "action_send")
            assertText("text_on_accent", "action_danger") // bouton Vocal en écoute
            assertText("text_on_accent", "button_primary")
            assertText("text_primary", "button_secondary")
            assertText("text_primary", "dialog_background")
            assertText("text_primary", "clip_item")
            assertText("text_primary", "clip_item_pinned")
            assertText("text_on_accent", "clip_pin_badge") // étiquette violette
            assertText("text_primary", "settings_card")
            assertText("text_primary", "input_background")
        }
    }

    @Test
    fun `textes d avertissement et de danger`() {
        eachPalette {
            assertText("clip_danger_text", "clip_dialog")
            assertText("settings_warning_text", "settings_card")
            assertText("settings_warning_text", "taipo_background")
            assertText("settings_warning_text", "input_background") // pastille « à fournir » de l'accueil (lot UX 4)
        }
    }

    @Test
    fun `elements graphiques significatifs`() {
        eachPalette {
            assertText("taipo_space_bar", "taipo_key_secondary", GRAPHIC_MIN) // barre de l'espace sur sa touche (grise)
            assertText("taipo_purple", "taipo_background", GRAPHIC_MIN) // onglet emoji, commutateur activé, focus
            assertText("input_stroke_focused", "taipo_background", GRAPHIC_MIN)
            assertText("control_unchecked", "taipo_background", GRAPHIC_MIN) // bouton radio / commutateur non coché
            assertText("switch_thumb", "switch_track_off", GRAPHIC_MIN) // pastille sur piste éteinte
        }
    }

    @Test
    fun `accent pose sur les touches grises, lot T6`() {
        eachPalette {
            // Chiffres d'appui long et trait étiré de la barre d'espace : éléments graphiques, 3:1.
            assertText("accent_on_key", "key_normal", GRAPHIC_MIN)
            assertText("accent_on_key", "key_functional", GRAPHIC_MIN)
            assertText("accent_on_key", "taipo_background", GRAPHIC_MIN)
        }
        // Le violet clair fixe, lui, ne tient pas sur les touches claires : c'est pourquoi la palette claire a son propre rôle.
        onPalette("values") {
            assertTrue(contrast("taipo_purple_light", "key_functional") < GRAPHIC_MIN)
            assertEquals(argb("taipo_purple"), argb("accent_on_key"))
        }
        onPalette("values-night") {
            assertEquals(argb("taipo_purple_light"), argb("accent_on_key"))
        }
    }

    @Test
    fun `les pastilles d auto-remplissage reprennent la couleur des boutons de leur palette`() {
        // Dessins lus par le service d'auto-remplissage hors du thème du clavier : couleur écrite en dur, à garder d'accord.
        fun chip(name: String): Long {
            val candidates = listOf(File("src/main/res/drawable/$name.xml"), File("app/src/main/res/drawable/$name.xml"))
            val file = candidates.firstOrNull { it.isFile } ?: fail("$name.xml introuvable")
            val solid = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("solid").item(0) as Element
            return 0xFF000000L or solid.getAttribute("android:color").removePrefix("#").toLong(16)
        }
        onPalette("values-night") { assertEquals(argb("taipo_key_secondary"), chip("bg_inline_chip_dark")) }
        onPalette("values") { assertEquals(argb("taipo_key_secondary"), chip("bg_inline_chip_light")) }
    }

    @Test
    fun `textes des pastilles d auto-remplissage en theme clair`() {
        onPalette("values") {
            // Couleurs pleines envoyées au service (pas de transparence) : texte 4,5:1 sur la pastille.
            val chip = argb("taipo_key_secondary")
            for (text in listOf(0xFF1B1B1FL.toInt(), 0xFF505055L.toInt())) {
                val a = luminance(0xFF000000L or (text.toLong() and 0xFFFFFF))
                val b = luminance(chip)
                val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                assertTrue(ratio >= TEXT_MIN, "texte %08X sur la pastille claire : %.2f:1".format(text, ratio))
            }
        }
    }

    @Test
    fun `les palettes de couleur dynamique declarent les memes noms et seulement des noms de la charte`() {
        val base = load("values").keys
        val light = load("values-desk-v31")
        val dark = load("values-desk-night-v31")
        assertEquals(light.keys, dark.keys, "Les deux palettes dynamiques doivent déclarer les mêmes couleurs")
        assertEquals(emptySet<String>(), light.keys - base, "Couleurs dynamiques inconnues de la charte")
        // Toute la charte hors blancs fixes est reprise du fond d'écran : pas de valeur en dur, que des tons du système.
        for (value in light.values + dark.values) {
            assertTrue(value.startsWith("@android:color/system_accent1_") || value.startsWith("@android:color/system_neutral1_"), value)
        }
        // Le texte posé sur l'accent reste blanc : accent1_600 (ton clair-moyen) doit porter du blanc.
        assertEquals("@android:color/system_accent1_600", light["taipo_purple"])
        assertEquals("@android:color/system_accent1_600", dark["taipo_purple"])
    }

    @Test
    fun `les deux palettes declarent les memes couleurs`() {
        val night = load("values-night").keys
        val day = load("values").keys
        assertEquals(emptySet<String>(), night - day, "Couleurs absentes de la palette claire")
        assertEquals(emptySet<String>(), day - night, "Couleurs absentes de la palette sombre")
    }

    @Test
    fun `le texte sur fond violet est blanc dans les deux palettes`() {
        eachPalette {
            assertEquals(argb("taipo_purple"), argb("accent"), "[$paletteName] accent")
            assertEquals(0xFFFFFFFFL, argb("text_on_accent"), "[$paletteName] text_on_accent")
        }
    }

    @Test
    fun `le fond du lanceur et du splash ne suivent pas le theme`() {
        eachPalette {
            assertEquals(0xFF0E0E0EL, argb("ic_launcher_background"), "[$paletteName] ic_launcher_background")
            assertEquals(0xFF0E0E0EL, argb("splash_background"), "[$paletteName] splash_background")
        }
    }

    private companion object {
        const val TEXT_MIN = 4.5
        const val GRAPHIC_MIN = 3.0
    }
}
