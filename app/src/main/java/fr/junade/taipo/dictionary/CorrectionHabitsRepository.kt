package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.suggestion.NextWordCrypto
import fr.junade.taipo.suggestion.writeAtomically
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Point d'accès unique aux habitudes de correction ([CorrectionHabits]) et à leur sauvegarde.
 *
 * Même principe que `NextWordRepository` : fichier séparé (`correction_habits.enc`), chiffré avec le même
 * format ([NextWordCrypto] : AES-256-GCM, clé dérivée par HKDF), mais avec un secret Keystore propre (clé
 * et fichier exclus des sauvegardes, voir les règles de sauvegarde). Si le fichier est illisible (clé
 * perdue, corruption), il est supprimé et les habitudes repartent de zéro : ce ne sont que des compteurs.
 *
 * Le chargement se fait hors du thread principal dès la construction ; ce qui est compté pendant ce temps
 * est conservé (le contenu du disque est fusionné). Les écritures sont regroupées ([SAVE_DELAY_MS] après la
 * dernière modification), ou immédiates avec [flush] / [flushBlocking] (arrêt du service).
 *
 * Alimenté par `SuggestionController` (via `HabitSignals`) ; effacé depuis l'écran des paramètres du clavier.
 */
class CorrectionHabitsRepository(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val keyProvider = DatabasePassphraseProvider(appContext, KEY_PREFS_NAME, KEY_ALIAS)

    /** Habitudes courantes, utilisables tout de suite (vides tant que le chargement n'est pas terminé). */
    val habits = CorrectionHabits()

    private val lock = Any()

    // Sérialise les écritures du fichier. Ordre des verrous : writeLock puis lock, jamais l'inverse.
    private val writeLock = Any()
    private var saveJob: Job? = null
    private var dirty = false

    // Levé quand le chargement initial est terminé (réussi ou non) : un objet encore vide écraserait le
    // fichier avant que son contenu soit fusionné.
    private val loaded = CountDownLatch(1)

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    /** À appeler après chaque modification : planifie une sauvegarde groupée. */
    fun markDirty() {
        synchronized(lock) {
            dirty = true
            if (saveJob?.isActive == true) return
            saveJob = scope.launch(Dispatchers.IO) {
                try {
                    delay(SAVE_DELAY_MS)
                    save()
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
    }

    /** Écrit tout de suite les modifications en attente (fin de saisie, arrêt du clavier). */
    fun flush() {
        val pending = synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty
        }
        if (pending) scope.launch(Dispatchers.IO) { save() }
    }

    /** Comme [flush], mais l'écriture est terminée quand la fonction rend la main (pour `onDestroy` du service). */
    fun flushBlocking() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
        }
        save()
    }

    /**
     * Efface tout ce qui a été compté, en mémoire et sur le disque. Si le chargement initial n'est pas fini,
     * l'effacement attend sa fin : sinon le contenu du disque serait fusionné après coup.
     */
    fun clear() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty = false
        }
        habits.clear()
        scope.launch(Dispatchers.IO) {
            loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                saveJob?.cancel()
                saveJob = null
                dirty = false
            }
            habits.clear()
            synchronized(writeLock) { runCatching { file.delete() } }
        }
    }

    private fun load() {
        var rewriteAsCurrentFormat = false
        try {
            if (!file.exists()) return
            try {
                val decrypted = withSecret { NextWordCrypto.decrypt(it, file.readBytes()) }
                habits.mergeFrom(CorrectionHabits.parse(String(decrypted.plain, Charsets.UTF_8)))
                if (decrypted.legacyFormat) {
                    // Ce fichier n'a jamais été écrit au format historique ; si cela arrivait, on le réécrit au format actuel.
                    synchronized(lock) { dirty = true }
                    rewriteAsCurrentFormat = true
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Habitudes de correction illisibles : réinitialisation", e)
                runCatching { file.delete() }
                runCatching { keyProvider.reset() }
            }
        } finally {
            loaded.countDown()
        }
        if (rewriteAsCurrentFormat) flush()
    }

    private fun save() {
        if (!loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            AppLog.w(TAG, "Chargement des habitudes de correction pas terminé : sauvegarde reportée")
            return
        }
        synchronized(writeLock) {
            val snapshot = synchronized(lock) {
                if (!dirty) return
                dirty = false
                habits.serialize()
            }
            try {
                // Fichier temporaire puis renommage : jamais de fichier à moitié écrit.
                writeAtomically(file, withSecret { NextWordCrypto.encrypt(it, snapshot.toByteArray(Charsets.UTF_8)) })
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                AppLog.e(TAG, "Échec de la sauvegarde des habitudes de correction", e)
            }
        }
    }

    /** Donne le secret du Keystore à [block] et l'efface ensuite (le fournisseur rend toujours une copie). */
    private inline fun <T> withSecret(block: (ByteArray) -> T): T {
        val secret = keyProvider.getOrCreate()
        try {
            return block(secret)
        } finally {
            secret.fill(0)
        }
    }

    companion object {
        private const val TAG = "CorrectionHabitsRepository"
        const val FILE_NAME = "correction_habits.enc"
        const val KEY_PREFS_NAME = "correction_habits_key"
        const val KEY_ALIAS = "taipo_correction_habits_key"
        private const val SAVE_DELAY_MS = 20_000L
        private const val LOAD_WAIT_MS = 2_000L
    }
}

/** Instance unique (par processus) du [CorrectionHabitsRepository], partagée par le clavier et l'écran des paramètres. */
object CorrectionHabitsProvider {

    @Volatile
    private var instance: CorrectionHabitsRepository? = null

    fun repository(context: Context): CorrectionHabitsRepository =
        instance ?: synchronized(this) {
            instance ?: CorrectionHabitsRepository(
                context.applicationContext,
                // Portée liée au processus : le dépôt vit aussi longtemps que l'application.
                CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
