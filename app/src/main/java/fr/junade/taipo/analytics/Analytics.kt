package fr.junade.taipo.analytics

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.aptabase.Aptabase
import com.aptabase.InitOptions
import com.aptabase.TrackingMode
import fr.junade.taipo.BuildConfig
import fr.junade.taipo.ai.InferenceFailureCause

/**
 * Mesure d'usage anonyme via le SDK Kotlin d'Aptabase (https://aptabase.com, serveur de l'UE d'après la clé `A-EU-…`).
 * Aucun identifiant d'appareil, aucun texte : seulement des événements du catalogue [AnalyticsEvents] avec des
 * propriétés filtrées par [AnalyticsProps], plus deux tranches de profil sur chaque événement ([UsageBuckets]).
 *
 * - Le SDK est initialisé une fois, dans [fr.junade.taipo.TaipoApplication] ([initialize]) : le clavier (service IME), les
 *   écrans et les téléchargements partagent le même processus.
 * - Les événements sont ignorés si la mesure est désactivée ([AnalyticsPreferences]), si le SDK n'est pas initialisé (clé
 *   vide), ou tant qu'un champ sensible (mot de passe) est affiché ([sensitiveFieldActive], tenu par l'IME).
 * - L'envoi, la session (renouvelée après 1 h d'inactivité) et le mode debug/release relèvent du SDK ; les propriétés lui
 *   sont données par [AnalyticsProps.forSdk] (String et Int seulement).
 */
object Analytics {

    /** Vrai pendant qu'un champ de mot de passe est affiché : aucun événement n'est alors enregistré. */
    @Volatile
    var sensitiveFieldActive: Boolean = false

    @Volatile
    private var initialized = false

    /** À appeler une fois, depuis `Application.onCreate`. Sans clé d'application, la mesure reste inactive. */
    fun initialize(application: Application) {
        val appKey = BuildConfig.APTABASE_APP_KEY
        if (appKey.isBlank()) return
        // Mode explicite plutôt que lu dans l'environnement : les builds debug vont au tableau de bord de test d'Aptabase,
        // release et benchmark (non débogables) à la production.
        Aptabase.instance.initialize(
            application,
            appKey,
            InitOptions(trackingMode = if (BuildConfig.DEBUG) TrackingMode.asDebug else TrackingMode.asRelease),
        )
        initialized = true
    }

    fun track(context: Context, name: String, props: AnalyticsProps) {
        send(context, name, props)
    }

    /** Envoie l'événement au SDK ; renvoie false s'il a été ignoré (SDK absent, champ sensible, mesure désactivée). */
    private fun send(context: Context, name: String, props: AnalyticsProps): Boolean {
        if (!initialized || sensitiveFieldActive) return false
        val appContext = context.applicationContext
        val preferences = AnalyticsPreferences(appContext)
        if (!preferences.isEnabled) return false
        val values = props.plus(*usageProps(appContext, preferences)).forSdk()
        if (values.isEmpty()) Aptabase.instance.trackEvent(name) else Aptabase.instance.trackEvent(name, values)
        return true
    }

    /**
     * Tranches ajoutées à chaque événement pour estimer la rétention sans identifiant : ancienneté de l'installation et
     * nombre de jours d'utilisation (voir [UsageBuckets]). Le compteur de jours reste local ; seule la tranche part.
     * Appelé uniquement quand la mesure est activée : désactivée, rien n'est compté.
     */
    @Synchronized
    private fun usageProps(context: Context, preferences: AnalyticsPreferences): Array<Pair<String, Any?>> {
        val now = System.currentTimeMillis()
        val state = UsageBuckets.recordActiveDay(
            UsageBuckets.ActiveDaysState(preferences.activeDays, preferences.lastActiveDay),
            UsageBuckets.epochDay(now),
        )
        if (state.lastDay != preferences.lastActiveDay) {
            preferences.activeDays = state.count
            preferences.lastActiveDay = state.lastDay
        }
        val installTime = installTimeMillis(context)
        val props = ArrayList<Pair<String, Any?>>(2)
        if (installTime != null) {
            props.add(UsageBuckets.PROP_INSTALL_AGE to UsageBuckets.installAgeBucket(now - installTime))
        }
        props.add(UsageBuckets.PROP_ACTIVE_DAYS to UsageBuckets.activeDaysBucket(state.count))
        return props.toTypedArray()
    }

    /** Date de première installation (conservée par Android lors des mises à jour) ; null si illisible. Lue une fois. */
    private fun installTimeMillis(context: Context): Long? {
        cachedInstallTime?.let { return it }
        val time = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime }.getOrNull()
        cachedInstallTime = time
        return time
    }

    @Volatile
    private var cachedInstallTime: Long? = null

    /**
     * Première ouverture de l'application : envoie [AnalyticsEvents.FIRST_OPEN] une seule fois par installation (nombre de
     * nouveaux utilisateurs). À appeler à l'ouverture de l'écran d'accueil. Règles dans [FirstOpenPolicy] : une application
     * mise à jour (déjà installée avant cette version) n'est pas comptée. Si l'envoi est ignoré (mesure désactivée, SDK
     * absent), rien n'est mémorisé et la prochaine ouverture réessaie.
     */
    fun firstOpen(context: Context) {
        val appContext = context.applicationContext
        val preferences = AnalyticsPreferences(appContext)
        if (preferences.firstOpenHandled) return
        val info = try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            return
        }
        when (FirstOpenPolicy.decide(preferences.firstOpenHandled, info.firstInstallTime, info.lastUpdateTime)) {
            FirstOpenPolicy.Decision.NOTHING -> Unit
            FirstOpenPolicy.Decision.SKIP_AND_REMEMBER -> preferences.firstOpenHandled = true
            FirstOpenPolicy.Decision.SEND ->
                if (send(appContext, AnalyticsEvents.FIRST_OPEN, AnalyticsProps.EMPTY)) preferences.firstOpenHandled = true
        }
    }

    /** Raccourci : `track(context, name, "cle" to valeur, ...)`. */
    fun track(context: Context, name: String, vararg props: Pair<String, Any?>) =
        track(context, name, AnalyticsProps.of(*props))

    /** Réglage modifié : nom du réglage et sa nouvelle valeur (booléen ou mot-clé), jamais un texte saisi. */
    fun setting(context: Context, key: String, value: Any) =
        track(context, AnalyticsEvents.SETTING_CHANGED, "key" to key, "value" to value)

    /**
     * Fin d'une action IA ([type] : [AnalyticsEvents.AiType]) : `ai_action` avec le modèle, la durée en tranche et le
     * succès ; sur un échec, [failure] ajoute `ai_action_failed` avec la cause. [startedAt] : `SystemClock.elapsedRealtime()`.
     */
    fun aiAction(context: Context, type: String, modelId: String?, startedAt: Long, failure: InferenceFailureCause? = null) {
        track(
            context,
            AnalyticsEvents.AI_ACTION,
            "type" to type,
            "model" to modelId,
            "duration" to AnalyticsEvents.durationBucket(SystemClock.elapsedRealtime() - startedAt),
            "success" to (failure == null),
        )
        if (failure != null) {
            track(
                context,
                AnalyticsEvents.AI_ACTION_FAILED,
                "type" to type,
                "model" to modelId,
                "cause" to failure.name.lowercase(),
            )
        }
    }
}
