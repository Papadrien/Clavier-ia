package fr.junade.taipo

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.ColorRes

/**
 * Thème du clavier et des écrans de l'application (lot 3.1 de la revue de code).
 *
 * Décision du 04/10/2026 : **en V1, le thème est toujours sombre**, quel que soit le thème du système. Le
 * thème clair existe déjà (palette de `res/values/colors.xml`, rapide à retravailler) mais n'est pas proposé :
 * il est prévu pour la V2. La palette sombre est dans `res/values-night/colors.xml` (c'est ce qui dit au système
 * que ces valeurs sont celles d'un thème sombre).
 *
 * Mécanisme : la couleur choisie est résolue avec une configuration où le mode nuit est forcé ([mode]), au lieu
 * de suivre celui de l'appareil. Pour passer à la V2 (suivre le système, ou un réglage), il suffit de changer
 * [mode] (et de lui faire lire le réglage) : le reste du code ne change pas.
 */
object KeyboardTheme {

    enum class Mode { DARK, LIGHT }

    /** V1 : toujours sombre. V2 : suivre le système ou un réglage de l'utilisateur. */
    val mode: Mode = Mode.DARK

    @Volatile
    private var themedResources: Resources? = null

    /** Couleur [id] dans la palette du thème courant. */
    fun color(context: Context, @ColorRes id: Int): Int = resources(context).getColor(id, null)

    /**
     * Contexte à donner aux écrans (activités) pour que leurs ressources (`@color/…`, thème) suivent [mode] :
     * à utiliser dans `attachBaseContext`.
     */
    fun wrap(base: Context): Context = base.createConfigurationContext(configuration(base))

    private fun resources(context: Context): Resources {
        themedResources?.let { return it }
        val app = context.applicationContext ?: context
        return app.createConfigurationContext(configuration(app)).resources.also { themedResources = it }
    }

    private fun configuration(base: Context): Configuration = Configuration(base.resources.configuration).apply {
        val night = if (mode == Mode.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }
}

/** Couleur [id] du thème courant du clavier (voir [KeyboardTheme]). */
internal fun Context.themeColor(@ColorRes id: Int): Int = KeyboardTheme.color(this, id)
