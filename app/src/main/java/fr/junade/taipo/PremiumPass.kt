package fr.junade.taipo

import android.content.Context

/**
 * Pass premium (achat unique, à vie) : couleurs d'accent du clavier, prompts personnalisés, presse-papiers étendu (copies gardées
 * 7 jours au lieu de 12 h, 50 éléments épinglés au lieu de 5). Le reste de Taipo (frappe, langues, IA, sauvegarde) reste gratuit.
 *
 * L'état vient de Google Play ([fr.junade.taipo.premium.PremiumBilling], appelé par les écrans de l'application) et il est gardé
 * dans les préférences : le clavier le lit ici sans jamais parler à Google Play, hors ligne comme en ligne. Clavier et écrans
 * partagent le même processus : un changement est vu tout de suite par le clavier.
 *
 * Builds debug : un forçage ([debugOverride], bouton en bas de l'accueil) remplace l'état de Google Play, pour tester les deux cas.
 * Il est ignoré, et jamais relu, dans un build de publication.
 */
object PremiumPass {

    /** Identifiant du produit « achat unique » déclaré dans la Play Console. */
    const val PRODUCT_ID = "pass_premium"

    /** Forçage du build debug : réel (Google Play), forcé actif, forcé inactif. */
    enum class DebugOverride(val storageKey: String) { NONE("none"), ACTIVE("active"), INACTIVE("inactive") }

    @Volatile
    private var purchased = false

    @Volatile
    var debugOverride: DebugOverride = DebugOverride.NONE
        private set

    @Volatile
    private var loaded = false

    /** Le pass est actif : acheté (ou forcé en debug). Faux tant que [load] n'a pas été appelé. */
    val isActive: Boolean get() = resolve(purchased, debugOverride, BuildConfig.DEBUG)

    /** Acheté d'après Google Play (dernier état connu), sans le forçage du debug. */
    val isPurchased: Boolean get() = purchased

    /** Relit l'état enregistré. Appelé au démarrage du processus ([TaipoApplication]), avant toute vue du clavier. */
    fun load(context: Context) {
        val prefs = prefs(context)
        purchased = prefs.getBoolean(KEY_PURCHASED, false)
        debugOverride = if (BuildConfig.DEBUG) {
            DebugOverride.entries.firstOrNull { it.storageKey == prefs.getString(KEY_DEBUG_OVERRIDE, null) } ?: DebugOverride.NONE
        } else {
            DebugOverride.NONE
        }
        loaded = true
        applyToKeyboard(context)
    }

    /** État lu chez Google Play : achat trouvé (ou confirmé), ou plus aucun achat (remboursement). */
    fun setPurchased(context: Context, value: Boolean) {
        if (loaded && purchased == value) return
        purchased = value
        prefs(context).edit().putBoolean(KEY_PURCHASED, value).apply()
        applyToKeyboard(context)
    }

    /** Bouton de debug de l'accueil : sans effet dans un build de publication. */
    fun setDebugOverride(context: Context, value: DebugOverride) {
        if (!BuildConfig.DEBUG) return
        debugOverride = value
        prefs(context).edit().putString(KEY_DEBUG_OVERRIDE, value.storageKey).apply()
        applyToKeyboard(context)
    }

    /**
     * Le clavier suit le nouvel état : la couleur et la police choisies s'appliquent (ou retombent sur le violet et Open Sans).
     * Ses vues sont recréées à la prochaine ouverture d'un champ (palette et police sont comparées à celles de leur création).
     */
    private fun applyToKeyboard(context: Context) {
        val preferences = KeyboardPreferences(context)
        KeyboardTheme.palette = AccentPalette.effective(preferences.accentPalette, isActive)
        TaipoType.keyboardFont = KeyboardFont.effective(preferences.keyboardFont, isActive)
        // Le fond du clavier est relu à chaque ouverture de champ (KeyboardBackdrop.effective), rien à poser ici.
    }

    /** État effectif : le forçage du debug l'emporte, dans un build debug seulement. Logique pure, testée en JVM. */
    fun resolve(purchased: Boolean, override: DebugOverride, debugBuild: Boolean): Boolean = when {
        !debugBuild || override == DebugOverride.NONE -> purchased
        else -> override == DebugOverride.ACTIVE
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "premium_prefs"
    private const val KEY_PURCHASED = "purchased"
    private const val KEY_DEBUG_OVERRIDE = "debug_override"
}
