package fr.junade.taipo

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.annotation.ColorRes

/**
 * Thème du clavier (lot 3.1 de la revue de code ; thèmes clair et sombre : lots T1 à T3).
 *
 * Deux palettes de mêmes noms : `res/values-night/colors.xml` (sombre) et `res/values/colors.xml` (claire). Le clavier lit la
 * sienne en résolvant ses couleurs avec une configuration où le mode nuit est forcé selon [mode], au lieu de suivre celui de
 * l'appareil. [mode] vient du réglage ([choice], de type [ThemeChoice]) ; en « Système, automatique », il suit le thème de l'appareil.
 *
 * **Les écrans de l'application restent toujours sombres** : [wrap] force le mode nuit, quel que soit le réglage.
 *
 * Repeindre le clavier (lot T4) : ses vues lisent leurs couleurs à leur création. [TaipoIme] les recrée quand le thème effectif
 * ([mode]) a changé depuis leur création : à l'ouverture d'un champ (réglage modifié pendant que le clavier était masqué) et,
 * quand l'appareil passe de clair à sombre, par la recréation de la vue qu'Android fait à chaque changement de configuration.
 */
object KeyboardTheme {

    enum class Mode { DARK, LIGHT }

    /**
     * Thème choisi. Posé au démarrage du processus ([TaipoApplication]) et à chaque changement du réglage
     * ([KeyboardPreferences.themeChoice]).
     */
    @Volatile
    var choice: ThemeChoice = ThemeChoice.DEFAULT

    /**
     * Dernier état clair / sombre de l'appareil connu du service du clavier (null : pas encore reçu, on lit la configuration
     * globale du système). Android recrée la vue du clavier dans `onConfigurationChanged`, parfois avant que la configuration
     * globale ne soit à jour : le service donne donc la sienne par [updateSystemConfiguration], avant de laisser Android recréer la vue.
     */
    @Volatile
    private var systemNightOverride: Boolean? = null

    /**
     * Couleur d'accent choisie (section « Couleur » de la page « Thème »), déjà ramenée au violet si elle n'est pas utilisable
     * ([AccentPalette.effective]). Posée comme [choice] : au démarrage du processus et à chaque changement du réglage.
     */
    @Volatile
    var palette: AccentPalette = AccentPalette.DEFAULT

    /**
     * Forme des touches choisie (section « Forme des touches » de la page « Apparence »), déjà ramenée à Arrondi si elle n'est
     * pas utilisable ([KeyShape.effective]). Posée comme [palette] : au démarrage du processus et à chaque changement du réglage.
     */
    @Volatile
    var shape: KeyShape = KeyShape.DEFAULT

    /** Palette du clavier en ce moment : le réglage, ou le thème de l'appareil en mode automatique. */
    val mode: Mode get() = choice.resolve(isSystemNight())

    /** Vrai quand la palette vient du fond d'écran (lot T7) : « Couleur dynamique » choisie, sur Android 12 ou plus. */
    val dynamic: Boolean get() = choice.usesDynamicColors(Build.VERSION.SDK_INT)

    /**
     * Apparence effective du clavier : palette claire ou sombre ([mode]), couleurs du fond d'écran ou non ([dynamic]) et couleur
     * d'accent ([accent] ; toujours le violet en couleur dynamique, dont l'accent vient du fond d'écran).
     * et forme des touches ([shape]). [TaipoIme] la compare à celle de la création des vues pour savoir s'il faut les recréer.
     */
    data class Look(
        val mode: Mode,
        val dynamic: Boolean,
        val accent: AccentPalette = AccentPalette.DEFAULT,
        val shape: KeyShape = KeyShape.DEFAULT,
    )

    val look: Look
        get() = dynamic.let { isDynamic -> Look(mode, isDynamic, if (isDynamic) AccentPalette.DEFAULT else palette, shape) }

    /** Ressources de l'apparence effective, gardées tant qu'elle ne change pas (et jusqu'au prochain changement de configuration). */
    @Volatile
    private var cache: Pair<Look, Resources>? = null

    /** Couleur [id] dans la palette du thème courant du clavier, avec sa couleur d'accent ([AccentPalette.recolor]). */
    fun color(context: Context, @ColorRes id: Int): Int = look.accent.recolor(resources(context).getColor(id, null))

    /**
     * Couleur [id] dans la palette [mode] (celle du fond d'écran si [dynamic], Android 12+), quel que soit le thème en cours. Sert à
     * la page « Thème » (lots T5 et T7), dont les cartes d'aperçu montrent les palettes alors que l'écran, lui, reste sombre.
     * Couleur de la charte, sans la couleur d'accent : l'aperçu l'applique lui-même ([AccentPalette.recolor]).
     */
    fun colorIn(context: Context, mode: Mode, @ColorRes id: Int, dynamic: Boolean = false): Int {
        val app = context.applicationContext ?: context
        return app.createConfigurationContext(configuration(app, mode, dynamic)).resources.getColor(id, null)
    }

    /**
     * Contexte du clavier : ses ressources (`@color/…`, icônes vectorielles, styles) suivent [mode]. À utiliser pour charger
     * une ressource XML qui contient une couleur (icône non teintée, thème d'une boîte de dialogue) depuis le service du clavier,
     * dont la configuration est celle de l'appareil.
     */
    fun themed(context: Context): Context = context.createConfigurationContext(configuration(context, mode, dynamic))

    /**
     * Contexte à donner aux écrans (activités) : leurs ressources suivent toujours la palette sombre. À utiliser dans
     * `attachBaseContext`.
     */
    fun wrap(base: Context): Context = base.createConfigurationContext(configuration(base, Mode.DARK))

    private fun resources(context: Context): Resources {
        val current = look
        cache?.let { (cachedLook, resources) -> if (cachedLook == current) return resources }
        val app = context.applicationContext ?: context
        return app.createConfigurationContext(configuration(app, current.mode, current.dynamic)).resources.also { cache = current to it }
    }

    /**
     * Configuration qui sélectionne la palette : mode nuit forcé selon [mode] et, pour les couleurs du fond d'écran, type d'interface
     * « desk » (qualificatif des dossiers `values-desk-v31` et `values-desk-night-v31`). Le type est toujours posé (« normal » sinon),
     * pour qu'un appareil réellement en station d'accueil ne fasse pas passer les écrans de l'application en couleur dynamique.
     */
    private fun configuration(base: Context, mode: Mode, dynamic: Boolean = false): Configuration =
        Configuration(base.resources.configuration).apply {
            val night = if (mode == Mode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            val type = if (dynamic) Configuration.UI_MODE_TYPE_DESK else Configuration.UI_MODE_TYPE_NORMAL
            uiMode = (uiMode and (Configuration.UI_MODE_NIGHT_MASK or Configuration.UI_MODE_TYPE_MASK).inv()) or night or type
        }

    /** À appeler par le service du clavier, à sa création et en tête de chaque changement de configuration (avant `super`). */
    fun updateSystemConfiguration(configuration: Configuration) {
        systemNightOverride = isNight(configuration)
        cache = null // un changement de fond d'écran arrive aussi par ici : les tons dynamiques sont à relire
    }

    /** Vrai quand l'appareil est en mode sombre. */
    private fun isSystemNight(): Boolean = systemNightOverride ?: isNight(Resources.getSystem().configuration)

    private fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}

/** Couleur [id] du thème courant du clavier (voir [KeyboardTheme]). */
internal fun Context.themeColor(@ColorRes id: Int): Int = KeyboardTheme.color(this, id)
