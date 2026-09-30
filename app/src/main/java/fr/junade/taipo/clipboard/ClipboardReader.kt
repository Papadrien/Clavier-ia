package fr.junade.taipo.clipboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Story 2.2 : lecture du presse-papiers système pour la puce de collage.
 *
 * - [start] / [stop] : écoute des changements pendant que le clavier est actif (entre l'ouverture
 *   et la fermeture d'un champ) ; [onChanged] est appelé à chaque nouvelle copie.
 * - [read] : lecture ponctuelle, utilisée aussi à l'ouverture d'un champ au cas où le processus du
 *   clavier aurait été tué entre-temps (copie manquée). Elle ne renvoie que du texte, avec
 *   l'horodatage de la copie ([ClipDescription.getTimestamp], API 26) et, sur Android 13+, le
 *   drapeau « contenu sensible » posé par l'application source ([ClipDescription.EXTRA_IS_SENSITIVE]).
 *
 * Le clavier actif peut lire le presse-papiers sans notification système (à valider sur appareil :
 * voir la checklist de la story 2.2).
 */
class ClipboardReader(context: Context, private val onChanged: () -> Unit) {

    /** Texte copié, moment de la copie (0 si inconnu) et indicateur « sensible ». */
    data class Snapshot(val text: String, val copiedAtMillis: Long, val sensitive: Boolean)

    private val manager = context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onChanged() }
    private var listening = false

    fun start() {
        if (listening) return
        manager?.addPrimaryClipChangedListener(listener)
        listening = manager != null
    }

    fun stop() {
        if (!listening) return
        manager?.removePrimaryClipChangedListener(listener)
        listening = false
    }

    /** Le texte actuellement copié, ou null (presse-papiers vide, contenu qui n'est pas du texte, lecture refusée). */
    fun read(): Snapshot? {
        val cm = manager ?: return null
        return try {
            val description = cm.primaryClipDescription ?: return null
            if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) &&
                !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                return null
            }
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            val text = clip.getItemAt(0).text?.toString() ?: return null
            Snapshot(text, clip.description.timestamp, isSensitive(clip.description))
        } catch (e: RuntimeException) {
            // SecurityException (lecture refusée par le système) ou échec de transfert d'un très gros
            // contenu : pas de suggestion, sans faire planter le clavier.
            Log.w(TAG, "Lecture du presse-papiers impossible", e)
            null
        }
    }

    private fun isSensitive(description: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) ?: false
    }

    private companion object {
        private const val TAG = "ClipboardReader"
    }
}
