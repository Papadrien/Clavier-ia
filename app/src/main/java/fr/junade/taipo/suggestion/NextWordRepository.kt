package fr.junade.taipo.suggestion

import android.content.Context
import fr.junade.taipo.AppLog
import fr.junade.taipo.dictionary.DatabasePassphraseProvider
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Point d'accès unique au modèle de prédiction du mot suivant ([NextWordModel]) et à sa sauvegarde.
 *
 * Ce que le clavier apprend des habitudes d'écriture reste sur l'appareil, dans un fichier chiffré
 * en AES-256-GCM. La clé vient de l'Android Keystore (même mécanisme que le dictionnaire personnel
 * et l'historique du presse-papiers, avec une clé propre) ; le fichier et la clé sont exclus des
 * sauvegardes. Si le fichier est illisible (clé perdue, corruption), il est supprimé et le modèle
 * repart de zéro : ce ne sont que des statistiques de frappe.
 *
 * Le chargement se fait hors du thread principal dès la construction : les mots appris pendant ce
 * temps sont conservés (le contenu du disque est fusionné dans le modèle). Les écritures sont
 * regroupées : le fichier est réécrit [SAVE_DELAY_MS] après la dernière modification, ou tout de suite
 * avec [flush] (en arrière-plan) ou [flushBlocking] (terminé au retour, pour l'arrêt du service : un
 * `launch` asynchrone pourrait ne pas aboutir si le processus est tué juste après).
 */
class NextWordRepository(
    context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val keyProvider = DatabasePassphraseProvider(appContext, KEY_PREFS_NAME, KEY_ALIAS)

    /** Modèle courant, utilisable tout de suite (vide tant que le chargement n'est pas terminé). */
    val model = NextWordModel()

    private val lock = Any()

    // Sérialise les écritures du fichier (sauvegarde d'arrière-plan et flushBlocking) : le snapshot est
    // pris sous ce verrou, donc l'écriture qui passe en dernier porte toujours l'état le plus récent.
    // Ordre des verrous : writeLock puis lock, jamais l'inverse.
    private val writeLock = Any()
    private var saveJob: Job? = null
    private var dirty = false

    // Levé quand le chargement initial est terminé (réussi ou non). Tant qu'il ne l'est pas, on
    // n'écrit pas : un modèle encore vide écraserait le fichier avant que son contenu soit fusionné.
    private val loaded = CountDownLatch(1)

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    /** À appeler après chaque apprentissage : planifie une sauvegarde groupée. */
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

    /**
     * Comme [flush], mais l'écriture est terminée quand la fonction rend la main (fichier de quelques
     * Ko : écriture directe). Pour `onDestroy` du service, où un `launch` risquerait d'être perdu.
     * Si une sauvegarde d'arrière-plan est déjà en cours d'écriture, on attend sa fin.
     */
    fun flushBlocking() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
        }
        save()
    }

    /** Efface tout ce qui a été appris, en mémoire et sur le disque. */
    fun clear() {
        synchronized(lock) {
            saveJob?.cancel()
            saveJob = null
            dirty = false
        }
        model.clear()
        scope.launch(Dispatchers.IO) { runCatching { file.delete() } }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            try {
                val text = String(decrypt(file.readBytes()), Charsets.UTF_8)
                model.mergeFrom(NextWordModel.parse(text))
            } catch (e: Exception) {
                AppLog.e(TAG, "Modèle de prédiction illisible : réinitialisation", e)
                runCatching { file.delete() }
                runCatching { keyProvider.reset() }
            }
        } finally {
            loaded.countDown()
        }
    }

    private fun save() {
        // Chargement pas fini (quelques ms en pratique) : on attend un peu, sinon on renonce et les
        // modifications restent en attente (dirty) pour la prochaine sauvegarde.
        if (!loaded.await(LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            AppLog.w(TAG, "Chargement du modèle de prédiction pas terminé : sauvegarde reportée")
            return
        }
        synchronized(writeLock) {
            val snapshot = synchronized(lock) {
                if (!dirty) return
                dirty = false
                model.serialize()
            }
            try {
                // Fichier temporaire puis renommage : jamais de fichier à moitié écrit.
                writeAtomically(file, encrypt(snapshot.toByteArray(Charsets.UTF_8)))
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                AppLog.e(TAG, "Échec de la sauvegarde du modèle de prédiction", e)
            }
        }
    }

    /** Clé AES-256 dérivée de la clé protégée par le Keystore. */
    private fun aesKey(): SecretKeySpec {
        val secret = keyProvider.getOrCreate()
        try {
            return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret), "AES")
        } finally {
            secret.fill(0)
        }
    }

    /** Format du fichier : IV (12 octets) suivi du texte chiffré et de son étiquette d'authentification. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        require(stored.size > IV_BYTES) { "Fichier du modèle trop court" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            aesKey(),
            GCMParameterSpec(GCM_TAG_BITS, stored.copyOfRange(0, IV_BYTES)),
        )
        return cipher.doFinal(stored, IV_BYTES, stored.size - IV_BYTES)
    }

    companion object {
        private const val TAG = "NextWordRepository"
        const val FILE_NAME = "next_word_model.enc"
        const val KEY_PREFS_NAME = "next_word_key"
        const val KEY_ALIAS = "taipo_next_word_key"
        private const val SAVE_DELAY_MS = 20_000L
        private const val LOAD_WAIT_MS = 2_000L
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/**
 * Instance unique (par processus) du [NextWordRepository], comme pour le dictionnaire personnel : le
 * clavier et, plus tard, un écran de réglages partagent le même modèle.
 */
object NextWordProvider {

    @Volatile
    private var instance: NextWordRepository? = null

    fun repository(context: Context): NextWordRepository =
        instance ?: synchronized(this) {
            instance ?: NextWordRepository(
                context.applicationContext,
                // Portée liée au processus : le dépôt vit aussi longtemps que l'IME.
                CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default),
            ).also { instance = it }
        }
}
