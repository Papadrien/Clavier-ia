package fr.junade.taipo.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Génère le profil de démarrage : démarrage de l'application, ouverture du clavier dans un champ, frappe
 * (suggestions, autocorrection). Le fichier produit (`*-baseline-prof.txt`) est à copier dans
 * `app/src/main/baseline-prof.txt` : voir docs/performance.md. À rejouer après un gros changement de code.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        selectTaipoKeyboard()
        startActivityAndWait()
        openKeyboardInPersonalDictionary()
        typeSomeKeys()
    }
}
