package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * Page « Nouveautés » ([WhatsNew]) : ouverte par la pilule de la barre du clavier, ou depuis l'accueil (À propos). Une carte par
 * nouveauté, comme les sections de la page Confidentialité. L'ouvrir arrête la pilule pour cette version.
 */
class WhatsNewActivity : Activity() {

    // Écrans de l'application : même thème que le clavier (page « Apparence »), voir KeyboardTheme.wrap.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whats_new)
        WhatsNewPreferences(this).markOpened()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.whats_new_heading), true)

        val container = findViewById<LinearLayout>(R.id.whats_new_container)
        val inflater = LayoutInflater.from(this)
        WhatsNew.items.forEach { news ->
            // Même carte que les sections des conditions d'utilisation et de la confidentialité.
            val item = inflater.inflate(R.layout.item_terms_section, container, false)
            val title = item.findViewById<TextView>(R.id.terms_section_title)
            title.setText(news.title)
            ViewCompat.setAccessibilityHeading(title, true)
            item.findViewById<TextView>(R.id.terms_section_body).apply {
                val body = news.body
                if (body == null) {
                    visibility = View.GONE
                } else {
                    setText(body)
                    setTextIsSelectable(false)
                }
            }
            container.addView(item)
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }
}
