package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import fr.junade.taipo.model.ModelLicense

/** Écran « Conditions d'utilisation » (accueil > À propos), construit à partir de [TermsContent]. */
class TermsActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terms)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.terms_heading), true)

        val container = findViewById<LinearLayout>(R.id.terms_container)
        val inflater = LayoutInflater.from(this)
        TermsContent.sections.forEach { section ->
            val item = inflater.inflate(R.layout.item_terms_section, container, false)
            val title = item.findViewById<TextView>(R.id.terms_section_title)
            title.setText(section.title)
            ViewCompat.setAccessibilityHeading(title, true)
            item.findViewById<TextView>(R.id.terms_section_body).setText(section.body)
            container.addView(item)
        }
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        findViewById<View>(R.id.terms_link_policy).setOnClickListener {
            ModelLicense.GEMMA_TERMS.policyUrl?.let(::openWebLink)
        }
        findViewById<View>(R.id.terms_link_licenses).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
    }
}
