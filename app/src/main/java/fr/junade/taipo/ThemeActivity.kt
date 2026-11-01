package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import fr.junade.taipo.analytics.Analytics

/**
 * Page « Thème » (paramètres, lots T5 et T7) : cartes d'aperçu, Système (automatique), Clair, Sombre et, dès Android 12,
 * Couleur dynamique ; un appui choisit le thème du clavier, la coche et le contour violet suivent. Le choix est enregistré tout de suite ([KeyboardPreferences.themeChoice]) ;
 * le clavier le prend à la prochaine ouverture d'un champ (lot T4). Les écrans de l'application, celui-ci compris, restent sombres.
 */
class ThemeActivity : Activity() {

    private class Card(val choice: ThemeChoice, val view: View, val check: View)

    private lateinit var preferences: KeyboardPreferences
    private val cards = ArrayList<Card>()

    // Écrans de l'application : toujours sombres, quel que soit le thème du clavier (voir KeyboardTheme.wrap).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme)
        preferences = KeyboardPreferences(this)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.theme_heading), true)

        val container = findViewById<LinearLayout>(R.id.theme_cards)
        val inflater = LayoutInflater.from(this)
        val gap = resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_sm)
        // Rangées de trois cartes de même largeur ; la dernière est complétée par des vides invisibles pour garder cette largeur.
        val available = ThemeChoice.entries.filter { it.isAvailable(Build.VERSION.SDK_INT) }
        available.chunked(CARDS_PER_ROW).forEachIndexed { rowIndex, choices ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.isBaselineAligned = false
            repeat(CARDS_PER_ROW) { index ->
                val choice = choices.getOrNull(index)
                val view = if (choice != null) {
                    inflater.inflate(R.layout.item_theme_card, row, false).also { card ->
                        card.findViewById<ThemePreviewView>(R.id.theme_preview).choice = choice
                        card.findViewById<TextView>(R.id.theme_label).setText(shortLabel(choice))
                        card.setOnClickListener { select(choice) }
                        cards += Card(choice, card, card.findViewById(R.id.theme_check))
                    }
                } else {
                    View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
                }
                (view.layoutParams as LinearLayout.LayoutParams).apply { if (index > 0) marginStart = gap }
                row.addView(view)
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (rowIndex > 0) params.topMargin = gap
            container.addView(row, params)
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        render(preferences.themeChoice)
    }

    private fun select(choice: ThemeChoice) {
        if (choice == preferences.themeChoice) return
        preferences.themeChoice = choice
        Analytics.setting(this, "theme_choice", choice.storageKey)
        render(choice)
    }

    /** Pose la carte choisie (contour, coche) et l'état lu par TalkBack : un choix unique parmi les cartes, comme des boutons radio. */
    private fun render(selected: ThemeChoice) {
        for (card in cards) {
            val isSelected = card.choice == selected
            card.view.isSelected = isSelected
            card.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            card.view.contentDescription = getString(fullLabel(card.choice))
            ViewCompat.setAccessibilityDelegate(card.view, radioDelegate(isSelected))
        }
    }

    private fun radioDelegate(checked: Boolean) = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = RadioButton::class.java.name
            info.isCheckable = true
            info.isChecked = checked
        }
    }

    @StringRes
    private fun shortLabel(choice: ThemeChoice): Int = when (choice) {
        ThemeChoice.SYSTEM -> R.string.theme_choice_system
        ThemeChoice.LIGHT -> R.string.theme_choice_light
        ThemeChoice.DARK -> R.string.theme_choice_dark
        ThemeChoice.DYNAMIC -> R.string.theme_choice_dynamic
    }

    /** Libellé complet lu par TalkBack (« Système, automatique » ; les deux autres n'ont pas de précision). */
    @StringRes
    private fun fullLabel(choice: ThemeChoice): Int =
        if (choice == ThemeChoice.SYSTEM) R.string.theme_choice_system_description else shortLabel(choice)

    private companion object {
        const val CARDS_PER_ROW = 3
    }
}

/** Libellé du thème, pour le sous-titre de la ligne « Thème » de l'accueil. */
@StringRes
internal fun ThemeChoice.labelRes(): Int = when (this) {
    ThemeChoice.SYSTEM -> R.string.theme_choice_system_description
    ThemeChoice.LIGHT -> R.string.theme_choice_light
    ThemeChoice.DARK -> R.string.theme_choice_dark
    ThemeChoice.DYNAMIC -> R.string.theme_choice_dynamic
}
