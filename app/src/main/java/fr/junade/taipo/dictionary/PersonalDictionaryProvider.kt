package fr.junade.taipo.dictionary

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * Instance unique (par processus) du [PersonalDictionaryRepository]. L'écran
 * de gestion et le clavier tournent dans le même processus et partagent donc
 * la même base : une modification faite dans l'écran est vue par le clavier
 * immédiatement, via le flux Room.
 */
object PersonalDictionaryProvider {

    private const val TAG = "PersonalDictionary"

    @Volatile
    private var instance: PersonalDictionaryRepository? = null

    fun repository(context: Context): PersonalDictionaryRepository =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(appContext: Context) = PersonalDictionaryRepository(
        // Portée liée au processus : le dépôt vit aussi longtemps que l'IME.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        openDao = { openDatabase(appContext).personalWordDao() },
        onError = { Log.e(TAG, "Échec d'accès au dictionnaire personnel", it) },
    )

    /**
     * Ouvre la base chiffrée. Si elle est illisible (clé Keystore perdue ou
     * invalidée, fichier corrompu), elle est supprimée puis recréée vide :
     * le dictionnaire personnel n'est qu'une liste de mots que l'utilisateur
     * peut ressaisir, alors qu'une base inutilisable bloquerait durablement
     * la fonction.
     */
    private fun openDatabase(appContext: Context): PersonalDictionaryDatabase {
        val passphraseProvider = DatabasePassphraseProvider(appContext)
        return try {
            openAndVerify(appContext, passphraseProvider)
        } catch (e: Exception) {
            Log.e(TAG, "Base illisible : réinitialisation du dictionnaire personnel", e)
            appContext.deleteDatabase(PersonalDictionaryDatabase.NAME)
            passphraseProvider.reset()
            openAndVerify(appContext, passphraseProvider)
        }
    }

    /** Force l'ouverture (Room est paresseux) pour détecter tout de suite une clé invalide. */
    private fun openAndVerify(
        appContext: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): PersonalDictionaryDatabase {
        val database = PersonalDictionaryDatabase.create(appContext, passphraseProvider.getOrCreate())
        try {
            database.openHelper.writableDatabase
        } catch (e: Exception) {
            database.close()
            throw e
        }
        return database
    }
}
