package fr.junade.taipo.premium

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import fr.junade.taipo.AppLog
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.PremiumPass
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents

/**
 * Achat du pass premium avec Google Play Billing. Utilisé par les écrans de l'application seulement (accueil, écran du pass) :
 * le clavier ne lit que [PremiumPass], jamais Google Play.
 *
 * - [refresh] relit les achats (démarrage de l'accueil, retour dans l'application) : un achat trouvé active le pass, aucun achat
 *   (remboursement) le retire. Une erreur de Google Play ne change rien à l'état connu.
 * - [loadPrice] lit le prix affiché dans la Play Console (promotion comprise), dans la monnaie de l'utilisateur.
 * - [purchase] ouvre la fenêtre de paiement de Google Play ; le résultat arrive dans [Listener.onStateChanged].
 * - Tout achat reçu est confirmé ([acknowledge]) : sans confirmation, Google Play le rembourse au bout de 3 jours.
 * - Un achat n'active le pack que si sa signature est vérifiée ([PurchaseVerifier], clé publique de la Play Console dans
 *   `BuildConfig.PLAY_LICENSE_KEY`) : un achat inventé par une fausse bibliothèque Google Play est refusé (21/11/2026).
 *
 * Toutes les réponses arrivent sur le fil principal (BillingClient les y renvoie).
 */
class PremiumBilling(context: Context, private val listener: Listener) {

    /** État de l'achat, pour l'écran du pass. */
    enum class State { IDLE, PENDING, PURCHASED, CANCELLED, UNAVAILABLE, ERROR }

    interface Listener {
        fun onStateChanged(state: State) {}
        fun onPriceLoaded(formattedPrice: String) {}
    }

    private val appContext = context.applicationContext
    private var productDetails: ProductDetails? = null

    /** Origine de l'ouverture de l'écran du pass, reprise par l'événement d'achat. */
    var source: String = "home"

    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener { result, purchases -> onPurchasesUpdated(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private var connecting = false
    private val waiting = ArrayList<() -> Unit>()

    /** Exécute [action] une fois connecté à Google Play ; sans Google Play (appareil sans Play Store), signale UNAVAILABLE. */
    private fun whenReady(action: () -> Unit) {
        if (client.isReady) {
            action()
            return
        }
        waiting += action
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val pending = waiting.toList()
                    waiting.clear()
                    pending.forEach { it() }
                } else {
                    AppLog.w(TAG, "Google Play Billing indisponible : ${result.responseCode} ${result.debugMessage}")
                    waiting.clear()
                    listener.onStateChanged(State.UNAVAILABLE)
                }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false // reconnexion à la prochaine demande
            }
        })
    }

    /** Relit les achats chez Google Play et met [PremiumPass] à jour. */
    fun refresh() = whenReady {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLog.w(TAG, "Lecture des achats impossible : ${result.responseCode} ${result.debugMessage}")
                return@queryPurchasesAsync
            }
            val pass = purchases.filter { PremiumPass.PRODUCT_ID in it.products }
            val bought = pass.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
            val decisions = bought.map { it to trust(it) }
            val owned = decisions.firstOrNull { it.second == PurchaseTrust.Decision.TRUSTED }?.first
            if (owned == null && decisions.any { it.second == PurchaseTrust.Decision.UNKNOWN }) {
                // Vérification impossible (clé illisible) : l'état connu reste tel quel, le pack n'est pas retiré aux vrais acheteurs.
                listener.onStateChanged(State.ERROR)
                return@queryPurchasesAsync
            }
            // Un achat refusé (signature fausse) ne compte pas : s'il n'y en a pas d'autre, le pack est retiré.
            PremiumPass.setPurchased(appContext, owned != null)
            owned?.let(::acknowledge)
            listener.onStateChanged(
                when {
                    owned != null -> State.PURCHASED
                    pass.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> State.PENDING
                    decisions.isNotEmpty() -> State.ERROR // seulement des achats refusés
                    else -> State.IDLE
                },
            )
        }
    }

    /** Achat authentique de ce pack, pour cette application ? Voir [PurchaseTrust]. */
    private fun trust(purchase: Purchase): PurchaseTrust.Decision {
        val verification = PurchaseVerifier.verify(BuildConfig.PLAY_LICENSE_KEY, purchase.originalJson, purchase.signature)
        val decision = PurchaseTrust.decide(verification, purchase.packageName == appContext.packageName, BuildConfig.DEBUG)
        if (decision != PurchaseTrust.Decision.TRUSTED) AppLog.w(TAG, "Achat non retenu : vérification $verification, décision $decision")
        return decision
    }

    /** Prix du pass tel que Google Play l'affiche (ex. « 2,99 € »). */
    fun loadPrice() = whenReady {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PremiumPass.PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLog.w(TAG, "Prix du pass introuvable : ${result.responseCode} ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            val found = details.productDetailsList.firstOrNull { it.productId == PremiumPass.PRODUCT_ID } ?: return@queryProductDetailsAsync
            productDetails = found
            found.oneTimePurchaseOfferDetails?.formattedPrice?.let(listener::onPriceLoaded)
        }
    }

    /** Ouvre la fenêtre de paiement de Google Play (le produit doit avoir été lu par [loadPrice]). */
    fun purchase(activity: Activity) = whenReady {
        val details = productDetails
        if (details == null) {
            listener.onStateChanged(State.UNAVAILABLE)
            return@whenReady
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            AppLog.w(TAG, "Fenêtre de paiement non ouverte : ${result.responseCode} ${result.debugMessage}")
            listener.onStateChanged(State.ERROR)
        }
    }

    private fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val pass = purchases.orEmpty().filter { PremiumPass.PRODUCT_ID in it.products }
                val bought = pass.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                val owned = bought.firstOrNull { trust(it) == PurchaseTrust.Decision.TRUSTED }
                when {
                    owned != null -> {
                        PremiumPass.setPurchased(appContext, true)
                        acknowledge(owned)
                        Analytics.track(appContext, AnalyticsEvents.PREMIUM_PURCHASED, "source" to source)
                        listener.onStateChanged(State.PURCHASED)
                    }
                    bought.isNotEmpty() -> listener.onStateChanged(State.ERROR) // achat refusé (signature) ou invérifiable
                    pass.isNotEmpty() -> listener.onStateChanged(State.PENDING) // paiement en attente (espèces, virement…)
                    else -> listener.onStateChanged(State.IDLE)
                }
            }
            // Déjà acheté : l'achat est relu chez Google Play et vérifié comme les autres, pas accepté sur parole.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> listener.onStateChanged(State.CANCELLED)
            else -> {
                AppLog.w(TAG, "Achat échoué : ${result.responseCode} ${result.debugMessage}")
                listener.onStateChanged(State.ERROR)
            }
        }
    }

    private fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLog.w(TAG, "Confirmation de l'achat impossible (relancée au prochain démarrage) : ${result.responseCode}")
            }
        }
    }

    /** À appeler quand l'écran se ferme. */
    fun close() {
        waiting.clear()
        client.endConnection()
    }

    private companion object {
        const val TAG = "PremiumBilling"
    }
}
