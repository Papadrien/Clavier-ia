package fr.junade.taipo.clipboard

import android.content.Context
import android.util.Log
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Instance unique (par processus) du [PinnedClipRepository] : le clavier en est le seul utilisateur. */
object ClipboardProvider {

    private const val TAG = "ClipboardProvider"

    @Volatile
    private var instance: PinnedClipRepository? = null

    fun repository(context: Context): PinnedClipRepository =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(appContext: Context) = PinnedClipRepository(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openDao = { openDatabase(appContext).pinnedClipDao() },
        onError = { Log.e(TAG, "Échec d'accès aux éléments épinglés", it) },
    )

    private fun passphraseProvider(appContext: Context) = DatabasePassphraseProvider(
        appContext,
        prefsName = ClipboardDatabase.KEY_PREFS_NAME,
        keyAlias = ClipboardDatabase.KEY_ALIAS,
    )

    /**
     * Ouvre la base chiffrée. Si elle est illisible (clé Keystore perdue ou invalidée, fichier
     * corrompu), elle est supprimée puis recréée vide : les éléments épinglés sont alors perdus,
     * mais une base inutilisable bloquerait durablement la fonction.
     */
    private fun openDatabase(appContext: Context): ClipboardDatabase {
        val provider = passphraseProvider(appContext)
        return try {
            openAndVerify(appContext, provider)
        } catch (e: Exception) {
            Log.e(TAG, "Base illisible : réinitialisation des éléments épinglés", e)
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
