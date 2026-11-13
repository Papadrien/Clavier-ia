package fr.junade.taipo

import android.content.Context
import androidx.annotation.StringRes

/**
 * Annonce des nouveautés d'une version (13/11/2026) : une pilule « Nouveautés » dans la barre du haut, à l'ouverture du clavier,
 * les [MAX_SHOWS] premières fois après la mise à jour (même pilule que les astuces, [GestureTipPillView]). Un toucher ouvre la page
 * [WhatsNewActivity] dans l'application ; la pilule ne revient plus ensuite. La page reste accessible depuis l'accueil (À propos).
 *
 * **Annoncer une nouvelle version** : changer [RELEASE] (les compteurs repartent de zéro pour elle), [items] et les textes
 * `whats_new_*` (titre de la page et de la pilule).
 *
 * Une installation neuve n'est pas une mise à jour : on n'y annonce pas les nouveautés de la version installée, seulement celles
 * des versions suivantes ([WhatsNewPreferences.ensureBaseline]).
 */
object WhatsNew {

    /** Version annoncée. */
    const val RELEASE = "3.0"

    /** Nombre d'ouvertures du clavier où la pilule est montrée, au plus. */
    const val MAX_SHOWS = 3

    /** Une nouveauté : titre, et texte facultatif (sans texte, la carte ne montre que le titre). */
    data class Item(@StringRes val title: Int, @StringRes val body: Int? = null)

    /** Nouveautés de [RELEASE], dans l'ordre de la page. */
    val items: List<Item> = listOf(
        Item(R.string.whats_new_floating_title),
        Item(R.string.whats_new_languages_title, R.string.whats_new_languages_body),
        Item(R.string.whats_new_appearance_title, R.string.whats_new_appearance_body),
        Item(R.string.whats_new_dictation_title),
        Item(R.string.whats_new_privacy_title, R.string.whats_new_privacy_body),
    )

    /** La pilule peut être montrée : version pas encore ouverte, et montrée moins de [MAX_SHOWS] fois. Logique pure. */
    fun shouldShow(shownCount: Int, opened: Boolean): Boolean = !opened && shownCount < MAX_SHOWS
}

/** Compteurs de [WhatsNew] : nombre d'affichages de la pilule et page ouverte, pour la version [WhatsNew.RELEASE]. */
class WhatsNewPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val shownKey = "${WhatsNew.RELEASE}_shown"
    private val openedKey = "${WhatsNew.RELEASE}_opened"

    /** La pilule doit être montrée à cette ouverture du clavier. */
    fun shouldShowNow(): Boolean {
        ensureBaseline()
        return WhatsNew.shouldShow(prefs.getInt(shownKey, 0), prefs.getBoolean(openedKey, false))
    }

    /** La pilule vient d'être affichée. */
    fun markShown() {
        prefs.edit().putInt(shownKey, prefs.getInt(shownKey, 0) + 1).apply()
    }

    /** La page des nouveautés a été ouverte : la pilule ne revient plus pour cette version. */
    fun markOpened() {
        prefs.edit().putBoolean(openedKey, true).apply()
    }

    /**
     * Premier passage : sur une installation neuve, la version installée est considérée comme déjà vue (rien à annoncer) ; après
     * une mise à jour depuis une version plus ancienne, ses nouveautés sont annoncées.
     */
    private fun ensureBaseline() {
        if (prefs.getBoolean(KEY_BASELINE, false)) return
        val editor = prefs.edit().putBoolean(KEY_BASELINE, true)
        if (isFreshInstall()) editor.putBoolean(openedKey, true)
        editor.apply()
    }

    private fun isFreshInstall(): Boolean = try {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.firstInstallTime == info.lastUpdateTime
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        private const val PREFS_NAME = "whats_new"
        private const val KEY_BASELINE = "baseline"

        /** Debug : la pilule de la version en cours revient, comme après une mise à jour (bouton « astuces » de l'accueil). */
        fun reset(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .clear()
                .putBoolean(KEY_BASELINE, true)
                .apply()
        }
    }
}
