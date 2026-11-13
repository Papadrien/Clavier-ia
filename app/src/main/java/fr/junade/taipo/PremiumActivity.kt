package fr.junade.taipo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.premium.PremiumBilling

/**
 * Écran « Pass premium » : le seul qui vend. Prix lu chez Google Play (promotion comprise), les trois avantages, ce qui reste
 * gratuit, achat et restauration. Ouvert depuis l'accueil, la page Thème, les prompts et le clavier (plafond des épinglés), avec
 * l'origine en extra ([EXTRA_SOURCE]) pour la mesure d'usage. Achat réussi : remerciement, puis `RESULT_OK` pour l'écran appelant
 * (la page Thème applique alors la couleur choisie).
 */
class PremiumActivity : Activity(), PremiumBilling.Listener {

    private lateinit var billing: PremiumBilling
    private lateinit var price: TextView
    private lateinit var status: TextView
    private lateinit var buy: Button
    private lateinit var restore: Button
    private var priceKnown = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_premium)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.premium_heading), true)
        price = findViewById(R.id.premium_price)
        status = findViewById(R.id.premium_status)
        buy = findViewById(R.id.button_premium_buy)
        restore = findViewById(R.id.button_premium_restore)
        addSwatches(findViewById(R.id.premium_swatches))

        val source = intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_HOME
        billing = PremiumBilling(this, this).also { it.source = source }
        if (savedInstanceState == null) Analytics.track(this, AnalyticsEvents.PREMIUM_PAYWALL_SHOWN, "source" to source)

        buy.setOnClickListener {
            if (!priceKnown) {
                showStatus(R.string.premium_status_unavailable)
                return@setOnClickListener
            }
            billing.purchase(this)
        }
        restore.setOnClickListener {
            showStatus(R.string.premium_status_restoring)
            billing.refresh()
        }
        // Page de remerciement (pack actif) : retour à l'accueil, quelle que soit la page d'où l'écran du pack a été ouvert.
        findViewById<View>(R.id.button_premium_home).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }

        findViewById<View>(android.R.id.content).applyTaipoFontToTree()
        applySystemBarInsets()
        render()
        billing.loadPrice()
        billing.refresh()
    }

    override fun onDestroy() {
        billing.close()
        super.onDestroy()
    }

    /** Les neuf couleurs, la première (violet, gratuite) comprise : un aperçu de ce que la page Thème propose. */
    private fun addSwatches(row: LinearLayout) {
        val size = resources.getDimensionPixelSize(R.dimen.taipo_premium_swatch_size)
        for (palette in AccentPalette.entries) {
            val swatch = AccentSwatchView(this).apply { this.palette = palette }
            row.addView(swatch, LinearLayout.LayoutParams(0, size, 1f))
        }
        row.contentDescription = getString(R.string.premium_colors_title)
    }

    /** Pass actif : remerciement, boutons d'achat masqués. Sinon, l'offre. */
    private fun render() {
        val active = PremiumPass.isActive
        buy.visibility = if (active) View.GONE else View.VISIBLE
        restore.visibility = if (active) View.GONE else View.VISIBLE
        findViewById<View>(R.id.button_premium_home).visibility = if (active) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.premium_tagline).setText(if (active) R.string.premium_active_thanks else R.string.premium_tagline)
        if (active) price.setText(R.string.premium_active_title)
    }

    private fun showStatus(@StringRes message: Int) {
        status.setText(message)
        status.visibility = View.VISIBLE
    }

    override fun onPriceLoaded(formattedPrice: String) {
        priceKnown = true
        if (!PremiumPass.isActive) {
            price.text = formattedPrice
            buy.text = getString(R.string.premium_buy_with_price, formattedPrice)
        }
    }

    override fun onStateChanged(state: PremiumBilling.State) {
        when (state) {
            PremiumBilling.State.PURCHASED -> {
                status.visibility = View.GONE
                setResult(RESULT_OK)
            }
            PremiumBilling.State.PENDING -> showStatus(R.string.premium_status_pending)
            PremiumBilling.State.CANCELLED -> status.visibility = View.GONE
            PremiumBilling.State.UNAVAILABLE -> showStatus(R.string.premium_status_unavailable)
            PremiumBilling.State.ERROR -> showStatus(R.string.premium_status_error)
            PremiumBilling.State.IDLE -> if (status.text == getString(R.string.premium_status_restoring)) {
                showStatus(R.string.premium_status_nothing_to_restore)
            }
        }
        render()
    }

    companion object {
        const val EXTRA_SOURCE = "fr.junade.taipo.extra.PREMIUM_SOURCE"
        const val SOURCE_HOME = "home"
        const val SOURCE_ABOUT = "about"
        const val SOURCE_THEME = "theme"
        const val SOURCE_PROMPTS = "prompts"
        const val SOURCE_CLIPBOARD_PIN = "clipboard_pin"

        fun intent(context: Context, source: String): Intent =
            Intent(context, PremiumActivity::class.java).putExtra(EXTRA_SOURCE, source)
    }
}
