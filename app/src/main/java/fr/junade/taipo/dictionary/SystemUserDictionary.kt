package fr.junade.taipo.dictionary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.UserDictionary
import fr.junade.taipo.AppLog
import fr.junade.taipo.KeyboardLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * Lecture du dictionnaire utilisateur du système Android (mots enregistrés via « Ajouter au dictionnaire » sur un mot
 * souligné en rouge), pour que l'autocorrection de Taipo ne les « corrige » pas et les propose comme candidats.
 *
 * Lecture seule, jamais d'écriture. Nécessite la permission runtime READ_USER_DICTIONARY, demandée depuis l'écran du
 * dictionnaire personnel ([fr.junade.taipo.PersonalDictionaryActivity]). Sans elle, [words] est vide : rien ne change.
 *
 * Les mots restent en mémoire du processus : jamais copiés dans la base de Taipo, la sauvegarde, l'export ou la mesure
 * d'usage (voir [SystemDictionaryWords]).
 *
 * [refresh] lit le `ContentProvider` : bloquant, à appeler hors du thread principal. [words] ne bloque jamais (liste en
 * cache), c'est elle que l'autocorrection appelle à chaque fin de mot.
 */
class SystemUserDictionary private constructor(private val appContext: Context) {

    @Volatile
    private var entries: List<SystemDictionaryWords.Entry> = emptyList()

    private val byLanguage = ConcurrentHashMap<KeyboardLanguage, List<String>>()

    @Volatile
    private var observerRegistered = false

    /** Vrai si la permission de lecture est accordée. */
    fun isAccessGranted(): Boolean = isAccessGranted(appContext)

    /** Mots du dictionnaire système à utiliser pour [language] (liste vide sans permission). Ne bloque jamais. */
    fun words(language: KeyboardLanguage): List<String> =
        byLanguage.getOrPut(language) { SystemDictionaryWords.select(entries, language) }

    /** Nombre de mots lus (toutes langues), pour l'affichage dans l'écran du dictionnaire. */
    fun count(): Int = entries.size

    /**
     * Relit le dictionnaire système. Sans permission, vide la liste. Toute erreur (provider absent ou en échec) laisse
     * simplement le dictionnaire vide : le clavier continue de fonctionner. Appeler hors du thread principal.
     */
    fun refresh() {
        if (!isAccessGranted()) {
            replace(emptyList())
            return
        }
        registerObserver()
        replace(
            try {
                read()
            } catch (e: Exception) { // SecurityException (permission retirée entre-temps), provider indisponible...
                AppLog.w(TAG, "dictionnaire système illisible : ${e.javaClass.simpleName}")
                emptyList()
            },
        )
    }

    private fun replace(newEntries: List<SystemDictionaryWords.Entry>) {
        entries = newEntries
        byLanguage.clear()
    }

    private fun read(): List<SystemDictionaryWords.Entry> {
        val result = ArrayList<SystemDictionaryWords.Entry>()
        val projection = arrayOf(
            UserDictionary.Words.WORD,
            UserDictionary.Words.FREQUENCY,
            UserDictionary.Words.LOCALE,
        )
        appContext.contentResolver.query(UserDictionary.Words.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val wordIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
            val frequencyIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
            val localeIndex = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
            while (cursor.moveToNext()) {
                val word = cursor.getString(wordIndex) ?: continue
                result += SystemDictionaryWords.Entry(
                    word = word,
                    frequency = cursor.getInt(frequencyIndex),
                    locale = cursor.getString(localeIndex),
                )
            }
        }
        return result
    }

    /** Relit la liste quand la personne ajoute ou retire un mot (sans redémarrer le clavier). */
    private fun registerObserver() {
        if (observerRegistered) return
        synchronized(this) {
            if (observerRegistered) return
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    // Lecture hors du thread principal.
                    Thread({ refresh() }, "system-dictionary-refresh").start()
                }
            }
            appContext.contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, observer)
            observerRegistered = true
        }
    }

    companion object {
        private const val TAG = "SystemUserDictionary"

        @Volatile
        private var instance: SystemUserDictionary? = null

        fun get(context: Context): SystemUserDictionary =
            instance ?: synchronized(this) {
                instance ?: SystemUserDictionary(context.applicationContext).also { instance = it }
            }

        fun isAccessGranted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_USER_DICTIONARY) == PackageManager.PERMISSION_GRANTED
    }
}
