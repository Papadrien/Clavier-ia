package fr.junade.taipo.backdrop

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import fr.junade.taipo.AppLog
import fr.junade.taipo.KeyboardPreferences
import fr.junade.taipo.R
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.showScreenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fond « Image » de la page « Apparence » (19/11/2026), extrait de `ThemeActivity` : choix de la source (galerie : sélecteur de
 * photos d'Android ; fichiers : sélecteur de documents), import de la copie réduite ([PhotoBackdrop.store]), suppression avec
 * confirmation ([PhotoBackdrop.delete]) et lecture de la vignette. L'écran est prévenu par [onPreview] (nouvelle vignette, ou
 * null) et [onImported] (une image vient d'être choisie : le fond « Image » est posé).
 *
 * À créer pendant la construction de l'écran (les sélecteurs sont enregistrés avant son démarrage, comme l'exige
 * `registerForActivityResult`). [scope] est annulé avec l'écran.
 */
internal class PhotoBackdropSection(
    private val activity: ComponentActivity,
    private val scope: CoroutineScope,
    private val preferences: () -> KeyboardPreferences,
    private val onPreview: (Bitmap?) -> Unit,
    private val onImported: () -> Unit,
) {

    /** Galerie : sélecteur de photos d'Android (repli sur le sélecteur de fichiers sur les anciennes versions). */
    private val pickPhoto = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::import) }

    /** Sélecteur de fichiers (Fichiers, Drive…), images seulement. */
    private val openPhoto = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::import) }

    /** Choix de la source de l'image du fond : galerie ou sélecteur de fichiers. */
    fun choose() {
        val sources = arrayOf(
            activity.getString(R.string.theme_backdrop_photo_source_gallery),
            activity.getString(R.string.theme_backdrop_photo_source_files),
        )
        AlertDialog.Builder(ContextThemeWrapper(activity, R.style.MessageDialogTheme))
            .setTitle(R.string.theme_backdrop_photo_source_title)
            .setItems(sources) { _, which -> launchPicker(gallery = which == 0) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** « Supprimer l'image » : confirmation, puis copie supprimée et fond Uni. */
    fun confirmDelete() {
        AlertDialog.Builder(ContextThemeWrapper(activity, R.style.MessageDialogTheme))
            .setTitle(R.string.theme_backdrop_photo_delete_title)
            .setMessage(R.string.theme_backdrop_photo_delete_message)
            .setPositiveButton(R.string.theme_backdrop_photo_delete_confirm) { _, _ -> delete() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Vignette du fond « Image » : l'image enregistrée, lue en taille réduite hors du thread principal. */
    fun loadPreview() {
        val appContext = activity.applicationContext
        scope.launch {
            onPreview(withContext(Dispatchers.IO) { PhotoBackdrop.load(appContext, PREVIEW_SIDE_PX) })
        }
    }

    private fun launchPicker(gallery: Boolean) {
        try {
            if (gallery) {
                pickPhoto.launch(PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
            } else {
                openPhoto.launch(arrayOf("image/*"))
            }
        } catch (e: ActivityNotFoundException) {
            AppLog.w(TAG, "aucun sélecteur d'image", e)
            activity.showScreenMessage(R.string.theme_backdrop_photo_no_picker)
        }
    }

    /**
     * Image choisie : copie réduite enregistrée hors du thread principal, puis fond « Image » posé et nouvelle version annoncée
     * au clavier (réglages écrits avec l'image, même si l'écran se ferme entre-temps).
     */
    private fun import(uri: Uri) {
        val appContext = activity.applicationContext
        val prefs = preferences()
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                PhotoBackdrop.store(appContext, uri).also { ok ->
                    if (ok) {
                        prefs.backdropPhotoVersion = System.currentTimeMillis()
                        prefs.keyboardBackdrop = KeyboardBackdrop.PHOTO
                    }
                }
            }
            if (!imported) {
                activity.showScreenMessage(R.string.theme_backdrop_photo_error)
                return@launch
            }
            Analytics.setting(activity, "keyboard_backdrop", KeyboardBackdrop.PHOTO.storageKey)
            onImported()
            loadPreview()
        }
    }

    /** Supprime la copie de l'image hors du thread principal ; le clavier repasse à Uni au prochain champ (plus d'image). */
    private fun delete() {
        val appContext = activity.applicationContext
        val prefs = preferences()
        scope.launch {
            val deleted = withContext(Dispatchers.IO) {
                PhotoBackdrop.delete(appContext).also { ok ->
                    if (ok) {
                        prefs.backdropPhotoVersion = System.currentTimeMillis()
                        if (prefs.keyboardBackdrop == KeyboardBackdrop.PHOTO) prefs.keyboardBackdrop = KeyboardBackdrop.PLAIN
                    }
                }
            }
            if (!deleted) {
                activity.showScreenMessage(R.string.theme_backdrop_photo_delete_error)
                return@launch
            }
            Analytics.setting(activity, "keyboard_backdrop", KeyboardBackdrop.PLAIN.storageKey)
            onPreview(null)
        }
    }

    private companion object {
        /** Grand côté de l'image lue pour la vignette (la vignette fait la moitié de la largeur de l'écran). */
        const val PREVIEW_SIDE_PX = 720
        const val TAG = "PhotoBackdropSection"
    }
}
