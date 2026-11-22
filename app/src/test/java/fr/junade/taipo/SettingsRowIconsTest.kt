package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Icônes des lignes de réglage (22/11/2026) : toutes les lignes de l'accueil en ont une. */
class SettingsRowIconsTest {

    @Test
    fun `chaque ligne de l'accueil a son icone`() {
        val home = listOf(java.io.File("src/main/res/layout/activity_main.xml"), java.io.File("app/src/main/res/layout/activity_main.xml"))
            .first { it.isFile }.readText()
        val rows = Regex("""android:id="@\+id/(\w+)"\s+layout="@layout/view_settings_row"""").findAll(home).map { it.groupValues[1] }.toList()
        assertTrue(rows.isNotEmpty())
        val withIcon = SettingsRowIcons.ICONS.keys.map { id -> R.id::class.java.fields.first { it.getInt(null) == id }.name }.toSet()
        val missing = rows.filter { it !in withIcon }
        assertTrue(missing.isEmpty(), "lignes sans icône : $missing")
    }
}
