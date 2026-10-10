package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import fr.junade.taipo.model.ModelLicense

/**
 * Écran « Licences » (story 8.11), accessible depuis l'accueil : mentions des modèles Gemma 3 (conditions
 * d'utilisation Gemma, politique d'usage interdit) et texte complet de la licence Apache 2.0 de Gemma 4.
 *
 * Le texte Apache est lu dans `assets/licenses/apache-2.0.txt` et affiché à la demande.
 */
class LicensesActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()

        listOf(R.id.licenses_heading, R.id.licenses_gemma3_heading, R.id.licenses_gemma4_heading).forEach {
            ViewCompat.setAccessibilityHeading(findViewById(it), true)
        }

        findViewById<View>(R.id.licenses_link_gemma_terms).setOnClickListener {
            openWebLink(ModelLicense.GEMMA_TERMS.termsUrl)
        }
        findViewById<View>(R.id.licenses_link_gemma_policy).setOnClickListener {
            ModelLicense.GEMMA_TERMS.policyUrl?.let(::openWebLink)
        }
        findViewById<View>(R.id.licenses_link_apache).setOnClickListener {
            openWebLink(ModelLicense.APACHE_2.termsUrl)
        }

        val text = findViewById<TextView>(R.id.licenses_apache_text)
        val toggle = findViewById<Button>(R.id.licenses_apache_toggle)
        toggle.setOnClickListener {
            val show = text.visibility != View.VISIBLE
            if (show && text.text.isNullOrEmpty()) text.text = readApacheText()
            text.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.licenses_apache_hide else R.string.licenses_apache_show)
        }
    }

    private fun readApacheText(): String = try {
        assets.open(APACHE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: java.io.IOException) {
        getString(R.string.licenses_apache_unreadable)
    }

    private companion object {
        const val APACHE_ASSET = "licenses/apache-2.0.txt"
    }
}
