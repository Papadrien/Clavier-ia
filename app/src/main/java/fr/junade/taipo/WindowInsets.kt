package fr.junade.taipo

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Depuis targetSdk 35, Android impose l'affichage bord à bord : le contenu de l'activité
 * s'étend sous la barre d'état, la barre de navigation et les encoches. Sans marge,
 * le haut des pages (titre, texte) passe sous la barre d'état.
 *
 * À appeler juste après `setContentView` : ajoute aux paddings existants du contenu
 * les insets des barres système, des encoches et du clavier à l'écran (IME).
 */
fun Activity.applySystemBarInsets() {
    val content = findViewById<View>(android.R.id.content)
    val left = content.paddingLeft
    val top = content.paddingTop
    val right = content.paddingRight
    val bottom = content.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(content)
}
