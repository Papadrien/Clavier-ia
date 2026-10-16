package fr.junade.taipo

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import fr.junade.taipo.model.download.DownloadDecision
import fr.junade.taipo.model.download.DownloadFailure

/** Vues d'une ligne de modèle (layout `item_model_download`), partagées par l'écran « Modèle IA » et l'écran « Modèle vocal ». */
internal class ModelRowViews(
    val name: TextView,
    val badge: TextView,
    val subtitle: TextView,
    val technical: TextView,
    val license: TextView,
    val status: TextView,
    val progress: ProgressBar,
    val primary: Button,
    val secondary: Button,
    val tertiary: Button,
) {
    companion object {
        fun from(item: View) = ModelRowViews(
            name = item.findViewById(R.id.model_name),
            badge = item.findViewById(R.id.model_badge),
            subtitle = item.findViewById(R.id.model_subtitle),
            technical = item.findViewById(R.id.model_technical),
            license = item.findViewById(R.id.model_license),
            status = item.findViewById(R.id.model_status),
            progress = item.findViewById(R.id.model_progress),
            primary = item.findViewById(R.id.model_primary),
            secondary = item.findViewById(R.id.model_secondary),
            tertiary = item.findViewById(R.id.model_tertiary),
        )
    }
}

/**
 * Socle des deux écrans de téléchargement de modèle : « Modèle IA » ([ModelDownloadActivity]) et « Modèle vocal »
 * ([VoiceModelDownloadActivity]). Il porte ce qu'ils partagent : thème, boutons d'une ligne, messages d'échec, réponse à la
 * décision de [fr.junade.taipo.model.download.DownloadPolicy], confirmation en données mobiles et demande de la permission
 * de notification (Android 13+).
 */
abstract class ModelScreenActivity : ComponentActivity() {

    // Thème de la V1 : toujours sombre (voir KeyboardTheme).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(KeyboardTheme.wrap(newBase))
    }

    /** Démarrage en attente de la réponse à la demande de notification (Android 13+). */
    private var pendingStart: (() -> Unit)? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Accordée ou non, le téléchargement démarre : sans permission il tourne sans notification visible.
            pendingStart?.invoke()
            pendingStart = null
        }

    internal fun showPrimary(views: ModelRowViews, label: Int, onClick: () -> Unit) {
        views.primary.setText(label)
        views.primary.setOnClickListener { onClick() }
        views.primary.visibility = View.VISIBLE
    }

    internal fun showTertiary(views: ModelRowViews, label: Int, onClick: () -> Unit) {
        views.tertiary.setText(label)
        views.tertiary.setOnClickListener { onClick() }
        views.tertiary.visibility = View.VISIBLE
    }

    internal fun showSecondary(views: ModelRowViews, label: Int, onClick: () -> Unit) {
        views.secondary.setText(label)
        views.secondary.setOnClickListener { onClick() }
        views.secondary.visibility = View.VISIBLE
    }

    internal fun failureMessage(reason: DownloadFailure): Int = when (reason) {
        DownloadFailure.NETWORK -> R.string.model_download_failed_network
        DownloadFailure.SERVER -> R.string.model_download_failed_server
        DownloadFailure.NO_SPACE -> R.string.model_download_failed_no_space
        DownloadFailure.CORRUPTED -> R.string.model_download_failed_corrupted
        DownloadFailure.SYSTEM_STOP -> R.string.model_download_failed_system_stop
        DownloadFailure.OTHER -> R.string.model_download_failed_other
    }

    /** Réponse à la décision de [DownloadPolicy] : démarrage, confirmation données mobiles, ou message clair (8.3, 8.4). */
    internal fun applyDecision(decision: DownloadDecision, start: () -> Unit, confirmMobile: () -> Unit) {
        when (decision) {
            DownloadDecision.Start -> start()
            DownloadDecision.ConfirmMobileData -> confirmMobile()
            DownloadDecision.NoConnection -> toast(R.string.model_download_toast_no_connection)
            DownloadDecision.NotAvailable -> toast(R.string.model_download_toast_not_available)
            is DownloadDecision.InsufficientSpace -> Toast.makeText(
                this,
                getString(
                    R.string.model_download_toast_no_space,
                    Formatter.formatFileSize(this, decision.requiredBytes),
                    Formatter.formatFileSize(this, decision.freeBytes),
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    internal fun confirmMobileData(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.MessageDialogTheme))
            .setTitle(R.string.model_download_mobile_title)
            .setMessage(message)
            .setPositiveButton(R.string.model_download_mobile_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.model_download_mobile_cancel, null)
            .show()
    }

    /** Lance [launch] ; sur Android 13+, la permission de notification est d'abord demandée, une seule fois. */
    internal fun launchWithNotificationPermission(launch: () -> Unit) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !hasAskedNotificationPermission()
        if (needsPermission) {
            markNotificationPermissionAsked()
            pendingStart = launch
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launch()
        }
    }

    private fun hasAskedNotificationPermission(): Boolean =
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).getBoolean(KEY_NOTIFICATION_ASKED, false)

    private fun markNotificationPermissionAsked() {
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
    }

    internal fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private companion object {
        private const val UI_PREFS = "model_download_ui"
        private const val KEY_NOTIFICATION_ASKED = "notification_permission_asked"
    }
}
