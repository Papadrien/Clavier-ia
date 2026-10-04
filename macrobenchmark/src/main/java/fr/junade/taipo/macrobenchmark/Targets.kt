package fr.junade.taipo.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** Application mesurée (variante « benchmark » de :app). */
const val PACKAGE = "fr.junade.taipo"

/** Service du clavier : composant à activer puis à sélectionner avant de mesurer. */
const val IME_ID = "fr.junade.taipo/.TaipoIme"

/**
 * Noms des sections de trace posées dans l'application (`fr.junade.taipo.Sections`). Si un nom change dans
 * l'application, il faut le changer ici aussi : une section introuvable donne simplement une mesure vide.
 */
object Sections {
    const val START_INPUT_VIEW = "Taipo.startInputView"
    const val DICTIONARY_LOAD = "Taipo.dictionaryLoad"
    const val SUGGESTIONS = "Taipo.suggestions"
    const val AUTOCORRECTION = "Taipo.autocorrection"
}

private const val UI_TIMEOUT_MS = 5_000L

/** Active Taipo et le choisit comme clavier courant (commandes shell `ime`). */
fun MacrobenchmarkScope.selectTaipoKeyboard() {
    device.executeShellCommand("ime enable $IME_ID")
    device.executeShellCommand("ime set $IME_ID")
}

/**
 * Écran « Dictionnaire personnel » (activité non exportée : on y accède par l'interface, comme
 * l'utilisateur), puis focus sur son champ de saisie, ce qui ouvre le clavier.
 */
fun MacrobenchmarkScope.openKeyboardInPersonalDictionary() {
    device.wait(Until.hasObject(By.res(PACKAGE, "button_personal_dictionary")), UI_TIMEOUT_MS)
    device.findObject(By.res(PACKAGE, "button_personal_dictionary")).click()
    device.wait(Until.hasObject(By.res(PACKAGE, "personal_dictionary_input")), UI_TIMEOUT_MS)
    device.findObject(By.res(PACKAGE, "personal_dictionary_input")).click()
    device.waitForIdle()
}

/**
 * Touches réparties sur la zone des lettres du clavier, par fractions de l'écran. Les touches de la
 * disposition sont dessinées sur une vue unique (pas de noeuds d'accessibilité) : les positions sont
 * approximatives, ce qui suffit pour exercer la frappe, les suggestions et l'autocorrection. À vérifier
 * à l'oeil une fois (le clavier doit recevoir des lettres) et à ajuster ici si la hauteur du clavier change.
 */
fun MacrobenchmarkScope.typeSomeKeys(rounds: Int = 3) {
    val width = device.displayWidth
    val height = device.displayHeight
    val rows = listOf(0.77f, 0.83f, 0.89f)
    val columns = listOf(0.10f, 0.22f, 0.34f, 0.46f, 0.58f, 0.70f, 0.82f)
    repeat(rounds) {
        for (row in rows) {
            for (column in columns) {
                device.click((width * column).toInt(), (height * row).toInt())
            }
        }
        // Barre espace : valide le mot en cours (autocorrection) puis repart sur un nouveau mot.
        device.click((width * 0.5f).toInt(), (height * 0.94f).toInt())
    }
    device.waitForIdle()
}
