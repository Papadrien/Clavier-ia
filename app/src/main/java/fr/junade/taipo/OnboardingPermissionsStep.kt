package fr.junade.taipo

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.clipboard.ScreenshotFinder

/**
 * Étape 5 de l'accueil : autorisations des fonctions de Taipo ([AppPermission]), demandées d'un coup depuis l'écran
 * d'accueil (une IME ne peut pas afficher de demande d'autorisation, l'accueil est une Activity). La liste [list] montre
 * ce qui est accordé ; [action] est le bouton « Autoriser ». [prefs] retient que l'utilisateur a répondu.
 */
class OnboardingPermissionsStep(
    private val activity: Activity,
    private val prefs: SharedPreferences,
    private val list: LinearLayout,
    private val action: View,
) {

    private val sdk: Int get() = Build.VERSION.SDK_INT

    /** Autorisations déjà accordées parmi celles qui ont un sens sur ce téléphone. */
    fun granted(): Set<AppPermission> = AppPermission.applicable(sdk).filterTo(mutableSetOf()) { permission ->
        val name = permission.grantingName(sdk) ?: return@filterTo false
        activity.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
    }

    fun isDone(): Boolean = AppPermission.stepDone(granted(), sdk, prefs.getBoolean(KEY_ANSWERED, false))

    /**
     * Demande d'un coup les autorisations manquantes (Android affiche une fenêtre par autorisation). Si Android ne les
     * affiche plus (refus définitif après une première réponse), ouvre les réglages de l'application à la place.
     */
    fun request() {
        val missing = AppPermission.applicable(sdk) - granted()
        if (missing.isEmpty()) return
        val answered = prefs.getBoolean(KEY_ANSWERED, false)
        val blocked = answered && missing.none { permission ->
            permission.manifestNames(sdk).any { activity.shouldShowRequestPermissionRationale(it) }
        }
        if (blocked) {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
            }
        } else {
            activity.requestPermissions(AppPermission.toRequest(missing, sdk), REQUEST_CODE)
        }
    }

    /** Réponse d'Android à [request] : vrai si elle concernait cette étape (l'accueil est alors à redessiner). */
    fun onRequestResult(requestCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        prefs.edit().putBoolean(KEY_ANSWERED, true).apply()
        // Captures autorisées ici : la suggestion du Smart Clipboard est allumée, comme depuis les paramètres du clavier.
        if (ScreenshotFinder.hasPermission(activity)) {
            val preferences = KeyboardPreferences(activity)
            if (!preferences.isScreenshotSuggestionEnabled) {
                preferences.isScreenshotSuggestionEnabled = true
                Analytics.setting(activity, "screenshot_suggestions", true)
            }
        }
        return true
    }

    /** Une ligne par autorisation, cochée quand elle est accordée. À appeler après le rendu commun de l'étape. */
    fun render(status: StepStatus) {
        val granted = granted()
        val applicable = AppPermission.applicable(sdk)
        val missing = applicable.any { it !in granted }
        // Étape faite avec des refus : la liste et le bouton restent, pour accorder le reste plus tard.
        val showList = status == StepStatus.CURRENT || (status == StepStatus.DONE && missing)
        list.visibility = if (showList) View.VISIBLE else View.GONE
        action.visibility = if (showList && missing) View.VISIBLE else View.GONE
        if (!showList) return
        list.removeAllViews()
        val gap = activity.resources.getDimensionPixelSize(R.dimen.taipo_settings_gap_xs)
        for (permission in applicable) {
            val row = TextView(activity, null, 0, R.style.TaipoText_Caption)
            row.text = activity.getString(
                if (permission in granted) R.string.onboarding_permission_granted else R.string.onboarding_permission_missing,
                activity.getString(permission.labelRes()),
            )
            row.setPadding(0, gap, 0, 0)
            list.addView(row)
        }
        list.applyTaipoFontToTree()
    }

    @StringRes
    private fun AppPermission.labelRes(): Int = when (this) {
        AppPermission.MICROPHONE -> R.string.onboarding_permission_microphone
        AppPermission.NOTIFICATIONS -> R.string.onboarding_permission_notifications
        AppPermission.SCREENSHOTS -> R.string.onboarding_permission_screenshots
        AppPermission.USER_DICTIONARY -> R.string.onboarding_permission_dictionary
    }

    private companion object {
        const val KEY_ANSWERED = "onboarding_permissions_answered"
        const val REQUEST_CODE = 42
    }
}
