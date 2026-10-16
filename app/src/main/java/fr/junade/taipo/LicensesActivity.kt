package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import fr.junade.taipo.licenses.LicenseNotice
import fr.junade.taipo.licenses.LicenseNotices
import fr.junade.taipo.licenses.LicenseSection
import fr.junade.taipo.model.ModelLicense

/**
 * Écran « Licences » (story 8.11, étendu par l'audit 1.9), accessible depuis l'accueil :
 * - modèles Gemma 3 (conditions d'utilisation Gemma, politique d'usage interdit) et texte complet de la licence
 *   Apache 2.0 de Gemma 4 (cartes écrites dans le layout) ;
 * - puis les autres mentions décrites dans [LicenseNotices] : modèle vocal Nemotron, listes de mots FrequencyWords
 *   (CC BY-SA 4.0), bibliothèques logicielles et police, une carte chacune.
 *
 * Les textes complets sont lus dans `assets/licenses/` et affichés à la demande.
 */
class LicensesActivity : Activity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)
        applySystemBarInsets()

        bindGemmaCards()
        bindNotices(R.id.licenses_models_container, LicenseSection.MODELS)
        bindNotices(R.id.licenses_dictionaries_container, LicenseSection.DICTIONARIES)
        bindNotices(R.id.licenses_software_container, LicenseSection.SOFTWARE)

        // Après le remplissage des conteneurs : la police s'applique aux cartes ajoutées par le code.
        findViewById<View>(android.R.id.content).applyTaipoFontToTree()

        listOf(
            R.id.licenses_heading,
            R.id.licenses_section_models,
            R.id.licenses_gemma3_heading,
            R.id.licenses_gemma4_heading,
            R.id.licenses_section_dictionaries,
            R.id.licenses_section_software,
        ).forEach { ViewCompat.setAccessibilityHeading(findViewById(it), true) }
    }

    private fun bindGemmaCards() {
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
            if (show && text.text.isNullOrEmpty()) text.text = readAsset(APACHE_ASSET, R.string.licenses_apache_unreadable)
            text.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.licenses_apache_hide else R.string.licenses_apache_show)
        }
    }

    /** Ajoute une carte par mention de la rubrique, dans l'ordre de [LicenseNotices.all]. */
    private fun bindNotices(containerId: Int, section: LicenseSection) {
        val container = findViewById<ViewGroup>(containerId)
        val inflater = LayoutInflater.from(this)
        for (notice in LicenseNotices.inSection(section)) {
            val card = inflater.inflate(R.layout.item_license_notice, container, false)
            bindNotice(card, notice)
            container.addView(card)
        }
    }

    private fun bindNotice(card: View, notice: LicenseNotice) {
        card.findViewById<TextView>(R.id.license_item_name).also {
            it.text = notice.name
            ViewCompat.setAccessibilityHeading(it, true)
        }
        val license = getString(R.string.licenses_meta_license, notice.licenseName)
        card.findViewById<TextView>(R.id.license_item_meta).text =
            listOfNotNull(license, notice.copyright).joinToString("\n")
        card.findViewById<TextView>(R.id.license_item_usage).setText(notice.usageRes)
        notice.noticeRes?.let { res ->
            card.findViewById<TextView>(R.id.license_item_notice).apply {
                setText(res)
                visibility = View.VISIBLE
            }
        }
        card.findViewById<View>(R.id.license_item_project_link).setOnClickListener { openWebLink(notice.projectUrl) }
        card.findViewById<View>(R.id.license_item_license_link).setOnClickListener { openWebLink(notice.licenseUrl) }

        // Texte complet : seulement si le fichier est réellement présent dans l'APK.
        val asset = notice.fullTextAsset?.takeIf(::assetExists) ?: return
        val toggle = card.findViewById<Button>(R.id.license_item_fulltext_toggle)
        val text = card.findViewById<TextView>(R.id.license_item_fulltext)
        toggle.visibility = View.VISIBLE
        toggle.setOnClickListener {
            val show = text.visibility != View.VISIBLE
            if (show && text.text.isNullOrEmpty()) text.text = readAsset(asset, R.string.licenses_fulltext_unreadable)
            text.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.licenses_fulltext_hide else R.string.licenses_fulltext_show)
        }
    }

    private fun assetExists(path: String): Boolean = try {
        assets.open(path).close()
        true
    } catch (_: java.io.IOException) {
        false
    }

    private fun readAsset(path: String, fallbackRes: Int): String = try {
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (_: java.io.IOException) {
        getString(fallbackRes)
    }

    private companion object {
        const val APACHE_ASSET = "licenses/apache-2.0.txt"
    }
}
