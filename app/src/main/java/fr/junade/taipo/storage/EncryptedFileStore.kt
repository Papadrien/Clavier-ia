package fr.junade.taipo.storage

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import fr.junade.taipo.suggestion.NextWordCrypto
import fr.junade.taipo.suggestion.writeAtomically
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sauvegarde chiffrée d'un modèle appris (mot suivant, habitudes de correction) : audit QUAL-01, code commun aux deux dépôts
 * qui étaient identiques à 95 %.
 *
 * Fichier [fileName] chiffré en AES-256-GCM ([NextWordCrypto] : clé dérivée par HKDF, format versionné, ancien format relu puis
 * migré), secret dans l'Android Keystore ([DatabasePassphraseProvider], clé propre à chaque modèle). Le chargement se fait hors du
 * thread principal dès la construction ; ce qui est appris pendant ce temps est conservé ([merge] fusionne le disque dans le
 * modèle). Les écritures sont regroupées : réécriture [SAVE_DELAY_MS] après la dernière modification, ou tout de suite avec
 * [flush] (en arrière-plan) ou [flushBlocking] (terminé au retour, pour l'arrêt du service).
 *
 * Audit BUG-02 : le fichier n'est effacé (avec sa clé) que si [StoredDataFailure.isUnrecoverable] le juge définitivement
 * illisible. Une erreur passagère (Keystore indisponible, disque) laisse le fichier intact et bloque toute écriture jusqu'à la
 * fin du processus : le modèle vide en mémoire ne remplace jamais les données de l'utilisateur. Le chargement suivant réessaie.
 *
 * Audit BUG-06 : [clear] attend la fin du chargement et prend le verrou d'écriture : ni un chargement ni une sauvegarde en
 * cours ne font revenir les données effacées.
 */
class EncryptedFileStore(
    context: Context,
    private val scope: CoroutineScope,
    fileName: String,
    keyPrefsName: String,
    keyAlias: String,
    private val tag: String,
    private val serialize: () -> String,
    private val merge: (String) -> Unit,
    private val clearModel: () -> Unit,
) {

    private val file = File(context.applicationContext.filesDir, fileName)
    private val keyProvider = DatabasePassphraseProvider(context.applicationContext, keyPrefsName, keyAlias)

    private val lock = Any()

    // Sérialise les écritures (sauvegarde groupée, flush, flushBlocking, effacement) : fichier temporaire commun.
    private val writeLock = Any()
    private var saveJob: Job? = null
    private var dirty = false

    /** Vrai si le fichier existe mais n'a pas pu être lu pour une raison passagère : il ne doit pas être écrasé. */
    @Volatile
    private var readOnly = false

    // Une sauvegarde n'écrit qu'après le chargement : sinon elle écraserait le fichier pas encore relu.
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
                delay(SAVE_DELAY_MS)
                save()
            }
        }
    }

    /** Écrit tout de suite les modifications en attente, en arrière-plan (fin de saisie). */
    fun flush() {
        val pending = synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty
        }
        if (pending) scope.launch(Dispatchers.IO) { save() }
    }

    /** Écrit tout de suite, de façon synchrone (arrêt du service). Ne fait rien, sans attendre, s'il n'y a rien à écrire. */
    fun flushBlocking() {
        val pending = synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty
        }
        if (pending) save() // audit PERF-03 : pas d'attente du chargement quand rien n'est à écrire
    }

    /** Efface le modèle en mémoire et sur le disque (réglage « Effacer les données apprises »). */
    fun clear() {
        cancelPendingSave()
        clearModel()
        scope.launch(Dispatchers.IO) {
            loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)
            cancelPendingSave() // une modification arrivée pendant l'attente ne doit pas réécrire l'ancien contenu
            clearModel() // un chargement terminé entre-temps a pu refusionner l'ancien contenu
            synchronized(writeLock) {
                runCatching { file.delete() }
                readOnly = false // le fichier illisible a été effacé à la demande de l'utilisateur : on peut réécrire
            }
        }
    }

    private fun cancelPendingSave() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty = false
        }
    }

    // Lecture : Keystore, déchiffrement et analyse peuvent lever bien des exceptions, toutes classées par StoredDataFailure.
    @Suppress("TooGenericExceptionCaught")
    private fun load() {
        var rewriteAsCurrentFormat = false
        try {
            if (!file.exists()) return
            try {
                val decrypted = withSecret { NextWordCrypto.decrypt(it, file.readBytes()) }
                val text = String(decrypted.plain, Charsets.UTF_8)
                try {
                    merge(text)
                } catch (e: IllegalArgumentException) {
                    throw StoredDataFailure.CorruptedDataException("contenu illisible", e)
                }
                if (decrypted.legacyFormat) {
                    // Ancien format (clé SHA-256) : réécrit tout de suite au format actuel, avec le même secret.
                    synchronized(lock) { dirty = true }
                    rewriteAsCurrentFormat = true
                }
            } catch (e: Exception) {
                if (StoredDataFailure.isUnrecoverable(e)) {
                    AppLog.e(tag, "Données apprises définitivement illisibles : réinitialisation", e)
                    runCatching { file.delete() }
                    runCatching { keyProvider.reset() }
                } else {
                    // Erreur passagère : le fichier est gardé tel quel et ne sera pas écrasé par le modèle vide.
                    AppLog.e(tag, "Données apprises momentanément illisibles : conservées, nouvel essai au prochain démarrage", e)
                    readOnly = true
                }
            }
        } finally {
            loaded.countDown()
        }
        if (rewriteAsCurrentFormat) flush()
    }

    // Écriture : toute erreur (Keystore, disque) remet la sauvegarde à plus tard, sans perdre les données en mémoire.
    @Suppress("TooGenericExceptionCaught")
    private fun save() {
        if (!loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            AppLog.w(tag, "Chargement pas terminé : sauvegarde reportée")
            synchronized(lock) { dirty = true }
            return
        }
        synchronized(writeLock) {
            if (readOnly) return
            val snapshot = synchronized(lock) {
                if (!dirty) return
                dirty = false
                serialize()
            }
            try {
                writeAtomically(file, withSecret { NextWordCrypto.encrypt(it, snapshot.toByteArray(Charsets.UTF_8)) })
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                AppLog.e(tag, "Échec de la sauvegarde", e)
            }
        }
    }

    /** Fournit le secret à [block] puis l'efface de la mémoire. */
    private inline fun <T> withSecret(block: (ByteArray) -> T): T {
        val secret = keyProvider.getOrCreate()
        try {
            return block(secret)
        } finally {
            secret.fill(0)
        }
    }

    private companion object {
        const val SAVE_DELAY_MS = 20_000L
        const val LOAD_WAIT_MS = 2_000L
    }
}
