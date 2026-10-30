package fr.junade.taipo.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import fr.junade.taipo.AppLog

/**
 * Prend le focus audio pendant un enregistrement : les lecteurs audio et vidéo qui respectent le focus (musique,
 * podcasts, vidéos) se mettent en pause, et ne reprennent pas le son dans le micro. [release] rend le focus, ce qui
 * leur permet de reprendre. Le focus est « transitoire exclusif » : les autres applications se mettent en pause au
 * lieu de baisser simplement leur volume.
 *
 * Limite : une application qui ignore le focus audio continue de jouer.
 */
class RecordingAudioFocus(context: Context) {

    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var request: AudioFocusRequest? = null

    /** Demande le focus. Sans effet s'il est déjà tenu. Un refus n'empêche pas l'enregistrement. */
    fun acquire() {
        val manager = audioManager ?: return
        if (request != null) return
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        val result = try {
            manager.requestAudioFocus(focusRequest)
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Focus audio indisponible", e)
            return
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = focusRequest
        } else {
            AppLog.w(TAG, "Focus audio refusé (code $result)")
        }
    }

    /** Rend le focus. Sans effet s'il n'est pas tenu. */
    fun release() {
        val focusRequest = request ?: return
        request = null
        try {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Focus audio non rendu", e)
        }
    }

    private companion object {
        private const val TAG = "RecordingAudioFocus"
    }
}
