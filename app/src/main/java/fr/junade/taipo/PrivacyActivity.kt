package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/** Écran « Confidentialité » (accueil > À propos, en dernier) : texte seul construit à partir de [PrivacyContent], sans bascule. */
class PrivacyActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.privacy_heading), true)

        val container = findViewById<LinearLayout>(R.id.privacy_container)
        val inflater = LayoutInflater.from(this)
        PrivacyContent.sections.forEach { section ->
            // La vue d'une section est la même que celle des conditions d'utilisation.
            val item = inflater.inflate(R.layout.item_terms_section, container, false)
            val title = item.findViewById<TextView>(R.id.terms_section_title)
            title.setText(section.title)
            ViewCompat.setAccessibilityHeading(title, true)
            item.findViewById<TextView>(R.id.terms_section_body).setText(section.body)
            container.addView(item)
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
    }
}
