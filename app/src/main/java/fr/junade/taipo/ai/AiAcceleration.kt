package fr.junade.taipo.ai

import android.annotation.SuppressLint
import android.content.Context
import fr.junade.taipo.BuildConfig

/**
 * Accélération du modèle IA (revue de code, performance) : processeur central (par défaut) ou processeur graphique (GPU),
 * souvent bien plus rapide pour générer du texte. **Essai réservé aux builds debug** (bouton en bas de l'accueil) : un échec
 * d'initialisation sur GPU peut planter le clavier au niveau natif, sans exception à attraper.
 *
 * Garde-fous :
 * - échec d'initialisation (exception) : repli immédiat sur le processeur central, GPU marqué en échec ([gpuFailed]) ;
 * - échec pendant une réponse alors que l'initialisation avait réussi (bibliothèque OpenCL absente, GPU perdu) : GPU marqué
 *   en échec ([markGpuFailed]), moteur rechargé sur le processeur central et réponse relancée (voir [LlmEngineHost.abandonGpuAfter]) ;
 * - plantage pendant l'initialisation : le drapeau [KEY_IN_PROGRESS], écrit de façon synchrone avant l'essai, est encore là
 *   au démarrage suivant ([checkPreviousCrash]) : le GPU est alors marqué en échec, le clavier repart sur le processeur.
 * Le GPU marqué en échec n'est plus essayé tant que l'essai n'est pas relancé ([setGpuRequested]).
 */
class AiAcceleration(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val gpuRequested: Boolean get() = prefs.getBoolean(KEY_GPU_REQUESTED, false)

    val gpuFailed: Boolean get() = prefs.getBoolean(KEY_GPU_FAILED, false)

    /** Le GPU est à essayer pour le prochain chargement. */
    val useGpu: Boolean get() = BuildConfig.DEBUG && gpuRequested && !gpuFailed

    /** Active ou coupe l'essai du GPU ; l'activer efface un échec précédent (nouvel essai). */
    fun setGpuRequested(requested: Boolean) {
        prefs.edit().putBoolean(KEY_GPU_REQUESTED, requested).putBoolean(KEY_GPU_FAILED, false).apply()
    }

    /** Juste avant d'initialiser le moteur sur GPU (écriture synchrone : elle doit survivre à un plantage natif). */
    @SuppressLint("ApplySharedPref")
    fun beginGpuInit() {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, true).commit()
    }

    /** Initialisation sur GPU terminée ; [success] faux : repli sur le processeur, GPU marqué en échec. */
    fun endGpuInit(success: Boolean) {
        prefs.edit().putBoolean(KEY_IN_PROGRESS, false).apply { if (!success) putBoolean(KEY_GPU_FAILED, true) }.apply()
    }

    /** Le GPU a échoué pendant une réponse : il n'est plus essayé, jusqu'à un nouvel essai ([setGpuRequested]). */
    fun markGpuFailed() {
        prefs.edit().putBoolean(KEY_GPU_FAILED, true).apply()
    }

    /** Au démarrage du clavier : un essai sur GPU resté « en cours » a planté le processus. */
    fun checkPreviousCrash() {
        if (prefs.getBoolean(KEY_IN_PROGRESS, false)) endGpuInit(success = false)
    }

    private companion object {
        const val PREFS_NAME = "ai_acceleration"
        const val KEY_GPU_REQUESTED = "gpu_requested"
        const val KEY_GPU_FAILED = "gpu_failed"
        const val KEY_IN_PROGRESS = "gpu_init_in_progress"
    }
}
