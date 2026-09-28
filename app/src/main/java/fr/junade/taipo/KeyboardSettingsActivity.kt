package fr.junade.taipo

import android.app.Activity
import android.os.Bundle
import android.widget.CompoundButton

/**
 * Écran « Paramètres du clavier ». Contient pour l'instant l'activation de la
 * rangée de chiffres (story 1.5) ; les autres réglages du clavier (retour
 * haptique 1.11, hauteur 1.12...) s'ajouteront ici.
 */
class KeyboardSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_settings)
        applySystemBarInsets()
        val preferences = KeyboardPreferences(this)

        findViewById<CompoundButton>(R.id.switch_number_row).apply {
            isChecked = preferences.isNumberRowEnabled
            setOnCheckedChangeListener { _, checked -> preferences.isNumberRowEnabled = checked }
        }
    }
}
