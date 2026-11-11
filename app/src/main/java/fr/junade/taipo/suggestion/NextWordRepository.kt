package fr.junade.taipo.suggestion

import android.content.Context
import fr.junade.taipo.storage.EncryptedFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Point d'accès unique au modèle de prédiction du mot suivant ([NextWordModel]) et à sa sauvegarde.
 *
 * Ce que le clavier apprend des habitudes d'écriture reste sur l'appareil, dans un fichier chiffré (voir [EncryptedFileStore] :
 * AES-256-GCM, secret dans l'Android Keystore avec une clé propre, fichier et clé exclus des sauvegardes). Un fichier
 * définitivement illisible est effacé et le modèle repart de zéro ; une erreur passagère le laisse intact (audit BUG-02).
 */
class NextWordRepository(
    context: Context,
    scope: CoroutineScope,
) {

    /** Modèle courant, utilisable tout de suite (vide tant que le chargement n'est pas terminé). */
    val model = NextWordModel()

    // Le modèle protège lui-même ses accès concurrents (frappe sur le fil principal, sauvegarde sur IO).
    private val store = EncryptedFileStore(
        context = context,
        scope = scope,
        fileName = FILE_NAME,
        keyPrefsName = KEY_PREFS_NAME,
        keyAlias = KEY_ALIAS,
        tag = TAG,
        serialize = { model.serialize() },
        merge = { text -> model.mergeFrom(NextWordModel.parse(text)) },
        clearModel = { model.clear() },
    )

    /** À appeler après chaque apprentissage : planifie une sauvegarde groupée. */
    fun markDirty() = store.markDirty()

    /** Écrit tout de suite les modifications en attente, en arrière-plan (fin de saisie). */
    fun flush() = store.flush()

    /** Écrit tout de suite, de façon synchrone (arrêt du service). */
    fun flushBlocking() = store.flushBlocking()

    /** Efface tout ce qui a été appris (mémoire et fichier). */
    fun clear() = store.clear()

    companion object {
        private const val TAG = "NextWordRepository"
        const val FILE_NAME = "next_word_model.enc"
        const val KEY_PREFS_NAME = "next_word_key"
        const val KEY_ALIAS = "taipo_next_word_key"
    }
}

/** Instance unique (par processus) du [NextWordRepository], partagée par le clavier et l'écran des paramètres. */
object NextWordProvider {

    @Volatile
    private var instance: NextWordRepository? = null

    fun repository(context: Context): NextWordRepository =
        instance ?: synchronized(this) {
            instance ?: NextWordRepository(
                context.applicationContext,
                // Portée liée au processus : le dépôt vit aussi longtemps que l'application.
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
