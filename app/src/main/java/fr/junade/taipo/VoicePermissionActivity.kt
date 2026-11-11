package fr.junade.taipo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ContextThemeWrapper
import androidx.core.app.ActivityCompat

/**
 * Activity transparente dont le seul rôle est de déclencher la demande de
 * permission RECORD_AUDIO, puis de se fermer.
 *
 * Une IME (InputMethodService) ne peut pas afficher elle-même une boîte de
 * dialogue de permission runtime : celle-ci doit être rattachée à une
 * Activity. Décision permission micro refusée (23/09/2026) : bouton d'action
 * grisé, un nouveau clic relance cette demande — c'est TaipoIme qui lance
 * cette Activity au clic sur le bouton Vocal si la permission n'est pas
 * encore accordée, et qui revérifie l'état au prochain affichage du clavier.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        // Audit UX-03 : refus définitif (Android n'affiche plus la demande) : on explique et on propose les réglages de l'app.
        if (!granted && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            showOpenSettings()
        } else {
            finish()
        }
    }

    private fun showOpenSettings() {
        AlertDialog.Builder(ContextThemeWrapper(KeyboardTheme.themed(this), R.style.MessageDialogTheme))
            .setTitle(R.string.voice_permission_settings_title)
            .setMessage(R.string.voice_permission_settings_message)
            .setPositiveButton(R.string.voice_permission_settings_open) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNegativeButton(R.string.voice_permission_settings_cancel, null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        private const val REQUEST_CODE = 1
    }
}
