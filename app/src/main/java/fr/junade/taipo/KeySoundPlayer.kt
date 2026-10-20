package fr.junade.taipo

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Son des touches : joue le « tic » synthétisé de [KeySound] à chaque frappe. Les sons sont générés une fois
 * ([KeyClickSynth]), écrits en petits fichiers WAV dans le cache de l'application, puis chargés dans un `SoundPool`
 * (latence minimale, pas d'allocation à la frappe). Quelques variantes par son (graines différentes) et un léger
 * décalage de vitesse aléatoire évitent l'effet « mitraillette ». Sans chargement terminé, [play] ne fait rien.
 */
class KeySoundPlayer(private val context: Context) {

    private var pool: SoundPool? = null
    private val sampleIds = HashMap<KeySound, IntArray>()
    private val ready = ConcurrentHashMap.newKeySet<Int>()

    /** Prépare les sons (une seule fois) ; à appeler quand le son des touches est activé. */
    @Synchronized
    fun prepare() {
        if (pool != null) return
        val soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) ready.add(sampleId) }
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        for (sound in KeySound.entries) {
            sampleIds[sound] = IntArray(VARIANTS) { variant ->
                val file = File(dir, "${sound.name.lowercase()}_$variant.wav")
                val samples = sound.render(seed = (variant + 1).toLong(), variation = VARIATION)
                file.writeBytes(KeyClickSynth.toWav(samples))
                soundPool.load(file.absolutePath, 1)
            }
        }
        pool = soundPool
    }

    fun play(sound: KeySound) {
        val soundPool = pool ?: return
        val ids = sampleIds[sound] ?: return
        val id = ids[Random.nextInt(ids.size)]
        if (id !in ready) return
        val rate = 1f + (Random.nextFloat() * 2f - 1f) * RATE_JITTER
        soundPool.play(id, VOLUME, VOLUME, 1, 0, rate)
    }

    @Synchronized
    fun release() {
        pool?.release()
        pool = null
        sampleIds.clear()
        ready.clear()
    }

    private companion object {
        const val CACHE_DIR = "key_sounds"
        const val VARIANTS = 3
        const val MAX_STREAMS = 4
        const val VARIATION = 0.04
        const val RATE_JITTER = 0.03f

        /** Volume de lecture (0 à 1) : le son d'origine baissé de 80 %. */
        const val VOLUME = 0.2f
    }
}
