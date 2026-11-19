package fr.junade.taipo.backdrop

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chargement de l'image du fond « Image » pour le clavier : décodée hors du thread principal, gardée en mémoire tant que la
 * même version est demandée ([version] : `KeyboardPreferences.backdropPhotoVersion`, changée à chaque import), relue après un
 * nouvel import. [scope] doit tourner sur le thread principal : [load] et son rappel y sont appelés.
 */
class PhotoBackdropLoader(private val context: Context, private val scope: CoroutineScope) {

    private var loadedVersion: Long? = null
    private var pendingVersion: Long? = null
    private var bitmap: Bitmap? = null
    private var job: Job? = null

    /** Image de la version [version] : rendue tout de suite si elle est déjà chargée, sinon à [onReady] une fois décodée. */
    fun load(version: Long, onReady: (Bitmap?) -> Unit) {
        if (version == loadedVersion) {
            onReady(bitmap)
            return
        }
        if (version == pendingVersion && job?.isActive == true) return
        job?.cancel()
        pendingVersion = version
        job = scope.launch {
            val decoded = withContext(Dispatchers.IO) { PhotoBackdrop.load(context) }
            loadedVersion = version
            pendingVersion = null
            bitmap = decoded
            onReady(decoded)
        }
    }

    /** Autre fond choisi, ou clavier détruit : l'image n'est plus gardée en mémoire (le GC récupère le bitmap, plus dessiné). */
    fun release() {
        job?.cancel()
        job = null
        pendingVersion = null
        loadedVersion = null
        bitmap = null
    }
}
