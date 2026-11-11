package fr.junade.taipo.dictionary

import android.content.Context
import fr.junade.taipo.storage.EncryptedFileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Point d'accès unique aux habitudes de correction ([CorrectionHabits]) et à leur sauvegarde.
 *
 * Même stockage que `NextWordRepository` ([EncryptedFileStore]) : fichier séparé (`correction_habits.enc`), secret Keystore
 * propre (clé et fichier exclus des sauvegardes). Un fichier définitivement illisible est effacé et les habitudes repartent
 * de zéro ; une erreur passagère le laisse intact (audit BUG-02).
 *
 * Alimenté par `SuggestionController` (via `HabitSignals`) ; effacé depuis l'écran des paramètres du clavier.
 */
class CorrectionHabitsRepository(
    context: Context,
    scope: CoroutineScope,
) {

    /** Habitudes courantes, utilisables tout de suite (vides tant que le chargement n'est pas terminé). */
    val habits = CorrectionHabits()

    private val store = EncryptedFileStore(
        context = context,
        scope = scope,
        fileName = FILE_NAME,
        keyPrefsName = KEY_PREFS_NAME,
        keyAlias = KEY_ALIAS,
        tag = TAG,
        serialize = { habits.serialize() },
        merge = { text -> habits.mergeFrom(CorrectionHabits.parse(text)) },
        clearModel = { habits.clear() },
    )

    /** À appeler après chaque modification : planifie une sauvegarde groupée. */
    fun markDirty() = store.markDirty()

    /** Écrit tout de suite les modifications en attente (fin de saisie). */
    fun flush() = store.flush()

    /** Écrit tout de suite, de façon synchrone (arrêt du service). */
    fun flushBlocking() = store.flushBlocking()

    /** Efface les habitudes (mémoire et fichier). */
    fun clear() = store.clear()

    companion object {
        private const val TAG = "CorrectionHabitsRepository"
        const val FILE_NAME = "correction_habits.enc"
        const val KEY_PREFS_NAME = "correction_habits_key"
        const val KEY_ALIAS = "taipo_correction_habits_key"
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
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
