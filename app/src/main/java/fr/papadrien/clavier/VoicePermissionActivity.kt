package fr.papadrien.clavier

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat

/**
 * Activity transparente dont le seul rôle est de déclencher la demande de
 * permission RECORD_AUDIO, puis de se fermer.
 *
 * Une IME (InputMethodService) ne peut pas afficher elle-même une boîte de
 * dialogue de permission runtime : celle-ci doit être rattachée à une
 * Activity. Décision permission micro refusée (23/09/2026) : bouton d'action
 * grisé, un nouveau clic relance cette demande — c'est ClavierIme qui lance
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
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 1
    }
}
