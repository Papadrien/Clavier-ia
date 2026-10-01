package fr.junade.taipo.clipboard

import android.content.Context
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import fr.junade.taipo.AppLog

/**
 * Instances uniques (par processus) des dépôts du presse-papiers : [PinnedClipRepository]
 * (éléments épinglés) et [ClipHistoryRepository] (historique, story 2.9). Ils partagent la même
 * base chiffrée, ouverte une seule fois.
 */
object ClipboardProvider {

    private const val TAG = "ClipboardProvider"

    private val lock = Any()

    @Volatile
    private var pinnedInstance: PinnedClipRepository? = null

    @Volatile
    private var historyInstance: ClipHistoryRepository? = null

    @Volatile
    private var sharedDatabase: ClipboardDatabase? = null

    fun repository(context: Context): PinnedClipRepository =
        pinnedInstance ?: synchronized(lock) {
            pinnedInstance ?: createPinned(context.applicationContext).also { pinnedInstance = it }
        }

    fun historyRepository(context: Context): ClipHistoryRepository =
        historyInstance ?: synchronized(lock) {
            historyInstance ?: createHistory(context.applicationContext).also { historyInstance = it }
        }

    private fun createPinned(appContext: Context) = PinnedClipRepository(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openDao = { database(appContext).pinnedClipDao() },
        onError = { AppLog.e(TAG, "Échec d'accès aux éléments épinglés", it) },
    )

    private fun createHistory(appContext: Context) = ClipHistoryRepository(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openDao = { database(appContext).clipHistoryDao() },
        onError = { AppLog.e(TAG, "Échec d'accès à l'historique du presse-papiers", it) },
    )

    /** La base partagée, ouverte au premier appel (hors thread principal : appelé depuis `openDao`). */
    private fun database(appContext: Context): ClipboardDatabase =
        sharedDatabase ?: synchronized(lock) {
            sharedDatabase ?: openDatabase(appContext).also { sharedDatabase = it }
        }

    private fun passphraseProvider(appContext: Context) = DatabasePassphraseProvider(
        appContext,
        prefsName = ClipboardDatabase.KEY_PREFS_NAME,
        keyAlias = ClipboardDatabase.KEY_ALIAS,
    )

    /**
     * Ouvre la base chiffrée (migrations comprises). Si elle est illisible (clé Keystore perdue ou
     * invalidée, fichier corrompu, migration qui échoue), elle est supprimée puis recréée vide : les
     * éléments épinglés et l'historique sont alors perdus, mais une base inutilisable bloquerait
     * durablement la fonction.
     */
    private fun openDatabase(appContext: Context): ClipboardDatabase {
        val provider = passphraseProvider(appContext)
        return try {
            openAndVerify(appContext, provider)
        } catch (e: Exception) {
            AppLog.e(TAG, "Base illisible : réinitialisation des éléments épinglés et de l'historique", e)
            appContext.deleteDatabase(ClipboardDatabase.NAME)
            provider.reset()
            openAndVerify(appContext, provider)
        }
    }

    /** Force l'ouverture (Room est paresseux) pour détecter tout de suite une clé invalide. */
    private fun openAndVerify(appContext: Context, provider: DatabasePassphraseProvider): ClipboardDatabase {
        val database = ClipboardDatabase.create(appContext, provider.getOrCreate())
        try {
            database.openHelper.writableDatabase
        } catch (e: Exception) {
            database.close()
            throw e
        }
        return database
    }
}
