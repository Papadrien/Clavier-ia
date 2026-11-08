package fr.junade.taipo

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

class AccentPaletteTest {

    @Test
    fun `le violet est la couleur par defaut et ne change rien`() {
        assertEquals(AccentPalette.VIOLET, AccentPalette.DEFAULT)
        for (color in listOf(0xFF8C00FF.toInt(), 0xFF43384C.toInt(), 0xFFF2F2F4.toInt())) {
            assertEquals(color, AccentPalette.VIOLET.recolor(color))
        }
    }

    @Test
    fun `les tons du violet deviennent ceux de la palette, les autres couleurs restent`() {
        val violet = AccentPalette.VIOLET
        for (palette in AccentPalette.entries) {
            assertEquals(palette.primary, palette.recolor(violet.primary))
            assertEquals(palette.secondary, palette.recolor(violet.secondary))
            assertEquals(palette.onDarkKey, palette.recolor(violet.onDarkKey))
            assertEquals(palette.darkKey, palette.recolor(violet.darkKey))
            assertEquals(palette.darkKeyShadow, palette.recolor(violet.darkKeyShadow))
            // Gris des touches claires, fond sombre, blanc : jamais teintés.
            for (neutral in listOf(0xFFF2F2F4.toInt(), 0xFFCDCDD3.toInt(), 0xFF0E0E0E.toInt(), 0xFFFFFFFF.toInt())) {
                assertEquals(neutral, palette.recolor(neutral))
            }
        }
    }

    @Test
    fun `le violet de l enum est celui de la charte`() {
        val night = colors("values-night")
        val day = colors("values")
        val violet = AccentPalette.VIOLET
        assertEquals(violet.primary, argb(day, "taipo_purple"))
        assertEquals(violet.primary, argb(night, "taipo_purple"))
        assertEquals(violet.secondary, argb(night, "taipo_purple_shadow"))
        assertEquals(violet.onDarkKey, argb(night, "taipo_purple_light"))
        assertEquals(violet.darkKey, argb(night, "taipo_key"))
        assertEquals(violet.darkKeyShadow, argb(night, "taipo_key_shadow"))
        // Le remplacement se fait par valeur : aucune couleur de la palette claire ne doit ressembler à un ton sombre du violet.
        val lightValues = day.keys.mapNotNull { runCatching { argb(day, it) }.getOrNull() }.toSet()
        assertFalse(violet.darkKey in lightValues)
        assertFalse(violet.darkKeyShadow in lightValues)
    }

    @Test
    fun `cles de stockage uniques et relues`() {
        assertEquals(AccentPalette.entries.size, AccentPalette.entries.map { it.storageKey }.toSet().size)
        for (palette in AccentPalette.entries) assertEquals(palette, AccentPalette.fromStorageKey(palette.storageKey))
        assertEquals(AccentPalette.DEFAULT, AccentPalette.fromStorageKey(null))
        assertEquals(AccentPalette.DEFAULT, AccentPalette.fromStorageKey("inconnue"))
    }

    @Test
    fun `sans pass premium seule la couleur par defaut est utilisable`() {
        for (palette in AccentPalette.entries) {
            assertTrue(palette.isAvailable(premium = true))
            assertEquals(palette == AccentPalette.DEFAULT, palette.isAvailable(premium = false))
            assertEquals(palette, AccentPalette.effective(palette, premium = true))
            val withoutPass = AccentPalette.effective(palette, premium = false)
            assertEquals(if (palette.requiresPremium) AccentPalette.DEFAULT else palette, withoutPass)
        }
    }

    @Test
    fun `contrastes de chaque palette`() {
        val lightSecondaryKey = 0xFFCDCDD3.toInt()
        for (palette in AccentPalette.entries) {
            // Texte et icônes blancs sur la touche Entrée et les boutons : 4,5:1 (AA).
            assertTrue(contrast(0xFFFFFFFF.toInt(), palette.primary) >= 4.5, "${palette.name} : blanc sur principale")
            // Accent sur une touche claire (chiffres d'appui long, trait de la barre d'espace) : 3:1, élément graphique.
            assertTrue(contrast(palette.primary, lightSecondaryKey) >= 3.0, "${palette.name} : principale sur touche claire")
            // Accent sur une touche sombre : au moins le niveau du violet de la charte (4,46:1).
            assertTrue(contrast(palette.onDarkKey, palette.darkKey) >= 4.4, "${palette.name} : accent sur touche sombre")
        }
    }

    private fun colors(directory: String): Map<String, String> {
        val file = listOf(File("src/main/res/$directory/colors.xml"), File("app/src/main/res/$directory/colors.xml")).first { it.isFile }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("color")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent.trim() }
    }

    private fun argb(colors: Map<String, String>, name: String): Int {
        var value = colors.getValue(name)
        while (value.startsWith("@color/")) value = colors.getValue(value.removePrefix("@color/"))
        val hex = value.removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000L or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> error("Format inattendu : $value")
        }
    }

    private fun luminance(color: Int): Double {
        fun lin(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
