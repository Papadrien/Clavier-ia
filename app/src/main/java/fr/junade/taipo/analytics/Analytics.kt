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
 * propriétés filtrées par [AnalyticsProps].
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
        if (!initialized || sensitiveFieldActive) return
        if (!AnalyticsPreferences(context.applicationContext).isEnabled) return
        val values = props.forSdk()
        if (values.isEmpty()) Aptabase.instance.trackEvent(name) else Aptabase.instance.trackEvent(name, values)
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
