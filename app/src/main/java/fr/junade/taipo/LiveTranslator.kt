package fr.junade.taipo

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import fr.junade.taipo.ai.InferenceFailureClassifier
import fr.junade.taipo.ai.RewriteCancelledException
import fr.junade.taipo.ai.RewriteEngine
import fr.junade.taipo.ai.RewriteSafeguard
import fr.junade.taipo.ai.TranslationLanguage
import fr.junade.taipo.ai.TranslationLanguages
import fr.junade.taipo.ai.TranslationPrompt
import fr.junade.taipo.ai.TranslationPromptPreferences
import fr.junade.taipo.ai.messageRes
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Traduction en direct (20/11/2026), comme Gboard et SwiftKey : l'utilisateur tape dans la zone de saisie du clavier (celle du
 * mode Chat, [PromptModeController] en mode traduction) et la traduction s'écrit dans le champ de l'application, remplacée à
 * chaque pause par une version à jour. Envoyer (ou Entrée) fige le segment : le texte tapé s'efface, la traduction reste, et la
 * suite s'écrit après elle. Tout se fait sur l'appareil, avec le modèle IA actif ([RewriteEngine]).
 *
 * Rythme adapté à un modèle local (1 à 3 s par phrase) : traduction après [PAUSE_MS] sans frappe, jamais deux à la fois ; une
 * frappe pendant une traduction en relance une dès qu'elle finit, sur le texte le plus récent.
 *
 * Aussi : « Traduire le texte du champ » ([translateField]), qui traduit sur place la sélection, ou tout le texte du champ.
 *
 * Toutes les méthodes sont à appeler depuis le thread principal.
 */
class LiveTranslator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val engine: () -> RewriteEngine,
    private val host: Host,
) {

    interface Host {
        fun activeModel(): AiModel?

        fun showMessage(message: String)

        /** Remplace [previous] (juste avant le curseur) par [text] ; texte écrit, ou null si [previous] n'y est plus. */
        fun replaceTextBeforeCursor(previous: String, text: String): String?

        /** Remplace la sélection du champ, ou tout son texte, par [text] (une action Annuler) ; faux si le champ n'est pas accessible. */
        fun replaceFieldText(text: String): Boolean

        /** Sélection du champ, ou à défaut tout son texte (null : champ inaccessible). */
        fun fieldTextToTranslate(): String?

        fun recordUndoEdit(inserted: String, original: String)

        fun hapticAi(cue: AiHapticCue)

        /** L'état affiché (langue, traduction en cours) a changé. */
        fun onStateChanged()

        /** La correction ou une transcription occupe le moteur. */
        fun engineBusyElsewhere(): Boolean
    }

    private val preferences = KeyboardPreferences(context)
    private val promptPreferences = TranslationPromptPreferences(context)

    /** Langues proposées pour le modèle actif. */
    var languages: List<TranslationLanguage> = emptyList()
        private set

    /** Langue cible choisie (null : pas de traduction possible avec ce modèle). */
    var target: TranslationLanguage? = null
        private set

    /** Une traduction tourne (en direct ou du champ). */
    val busy: Boolean get() = job?.isActive == true

    /** Texte écrit dans le champ par le segment en cours (espaces comprises), vide avant la première traduction. */
    private var written = ""

    /** Texte source dont [written] est la traduction. */
    private var writtenSource = ""

    /** Dernier texte tapé (zone de saisie). */
    private var pendingSource = ""
    private var job: Job? = null
    private var active = false

    private val pauseRunnable = Runnable { translateNow() }

    /**
     * Prépare une session pour le modèle actif : faux (avec le message) si la traduction n'est pas possible (aucun modèle : renvoi
     * vers l'écran des modèles ; Gemma 3 270M : trop petit).
     */
    fun start(keyboard: KeyboardLanguage): Boolean {
        val model = host.activeModel()
        if (model == null) {
            ModelScreenRedirect.open(context)
            return false
        }
        val available = TranslationLanguages.forModel(model)
        if (available.isEmpty()) {
            host.showMessage(context.getString(R.string.translation_model_too_small, context.getString(R.string.model_name_leger)))
            return false
        }
        languages = TranslationLanguages.ordered(available, preferences.translationRecent)
        target = TranslationLanguages.defaultTarget(available, preferences.translationTarget, keyboard)
        written = ""
        writtenSource = ""
        pendingSource = ""
        active = true
        return true
    }

    /** Langue cible touchée : retenue, et le segment en cours est retraduit dans cette langue. */
    fun selectTarget(language: TranslationLanguage) {
        if (target == language) return
        target = language
        preferences.translationTarget = language.tag
        preferences.translationRecent = TranslationLanguages.pushRecent(preferences.translationRecent, language.tag)
        host.onStateChanged()
        if (pendingSource.isNotBlank()) schedule(immediate = true)
    }

    /** Le texte tapé a changé : nouvelle traduction après une pause. */
    fun onInputChanged(text: String) {
        if (!active) return
        pendingSource = text
        schedule(immediate = false)
    }

    private fun schedule(immediate: Boolean) {
        mainHandler.removeCallbacks(pauseRunnable)
        if (immediate) mainHandler.post(pauseRunnable) else mainHandler.postDelayed(pauseRunnable, PAUSE_MS)
    }

    /** Traduit tout de suite le texte tapé (sans attendre la pause). */
    private fun translateNow() {
        if (!active || busy) return // la traduction en cours relance la suivante à sa fin (voir launch)
        val source = pendingSource
        if (source == writtenSource) return
        if (source.isBlank()) {
            // Texte tapé effacé : la traduction écrite disparaît aussi.
            if (written.isNotEmpty()) write("")
            writtenSource = source
            return
        }
        val language = target ?: return
        val model = host.activeModel() ?: return
        if (host.engineBusyElsewhere()) {
            host.showMessage(context.getString(R.string.ai_action_busy))
            return
        }
        launch(model) {
            val response = engine().rewrite(model, promptPreferences.get(), TranslationPrompt.userTurn(language, source.trim()), "traduction")
            val translated = RewriteSafeguard.clean(response) ?: return@launch
            // Session refermée ou autre langue choisie entre-temps : résultat périmé.
            if (!active || target != language) return@launch
            write(translated)
            writtenSource = source
        }
    }

    private fun write(text: String) {
        val result = host.replaceTextBeforeCursor(written, text)
        if (result == null) {
            // Le curseur a bougé, ou le texte a été modifié à la main : le segment repart de zéro, à la nouvelle position.
            written = ""
            host.showMessage(context.getString(R.string.translation_field_changed))
            return
        }
        written = result
    }

    /**
     * Envoyer ou Entrée : fige le segment (dernière traduction lancée tout de suite si le texte tapé a changé). Renvoie vrai si la
     * zone de saisie peut être vidée (sinon, une traduction est encore en route : on attend sa fin).
     */
    fun commit(): Boolean {
        if (!active) return true
        mainHandler.removeCallbacks(pauseRunnable)
        if (pendingSource.isBlank() && !busy) {
            if (written.isNotEmpty()) write("") // texte tapé effacé avant l'envoi : rien ne reste
            closeSegment()
            return true
        }
        if (pendingSource != writtenSource || busy) {
            commitAfterTranslation = true
            translateNow()
            return false
        }
        closeSegment()
        return true
    }

    /** Envoyer touché pendant une traduction : le segment se fige dès qu'elle est écrite. */
    private var commitAfterTranslation = false

    /** Appelé par le contrôleur du mode quand le texte tapé doit être vidé (segment figé). */
    var onSegmentCommitted: (() -> Unit)? = null

    private fun closeSegment() {
        commitAfterTranslation = false
        if (written.isNotBlank()) host.recordUndoEdit(inserted = written, original = "")
        written = ""
        writtenSource = ""
        pendingSource = ""
        onSegmentCommitted?.invoke()
        host.onStateChanged()
    }

    /** Sortie du mode : la traduction écrite reste dans le champ (une action Annuler) ; une traduction en cours est abandonnée. */
    fun finish() {
        if (!active) return
        mainHandler.removeCallbacks(pauseRunnable)
        active = false
        cancelRunning()
        if (written.isNotBlank()) host.recordUndoEdit(inserted = written, original = "")
        written = ""
        writtenSource = ""
        pendingSource = ""
        commitAfterTranslation = false
    }

    /** « Traduire le texte du champ » : la sélection, ou tout le texte du champ, est remplacé par sa traduction. */
    fun translateField() {
        val language = target ?: return
        if (busy) return
        val model = host.activeModel() ?: return
        if (host.engineBusyElsewhere()) {
            host.showMessage(context.getString(R.string.ai_action_busy))
            return
        }
        val source = host.fieldTextToTranslate()
        if (source.isNullOrBlank()) {
            host.showMessage(context.getString(R.string.translation_field_empty))
            return
        }
        launch(model) {
            val response = engine().rewrite(model, promptPreferences.get(), TranslationPrompt.userTurn(language, source.trim()), "traduction du champ")
            val translated = RewriteSafeguard.clean(response) ?: return@launch
            if (!active) return@launch
            if (host.fieldTextToTranslate() != source) {
                host.showMessage(context.getString(R.string.correction_text_changed))
                return@launch
            }
            val leading = source.takeWhile { it.isWhitespace() }
            val trailing = source.takeLastWhile { it.isWhitespace() }
            host.replaceFieldText(leading + translated + trailing)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // échec du moteur : message à l'utilisateur, pas de plantage
    private fun launch(model: AiModel, work: suspend () -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        cancelled = false
        val started = scope.launch {
            try {
                work()
                host.hapticAi(AiHapticCue.PROCESSING_DONE)
                Analytics.aiAction(context, AnalyticsEvents.AiType.TRANSLATE, model.id, startedAt)
            } catch (e: RewriteCancelledException) {
                AppLog.i(TAG, "traduction abandonnée")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (!cancelled) {
                    val cause = InferenceFailureClassifier.classify(t)
                    AppLog.e(TAG, "Échec de la traduction ($cause)", t)
                    Analytics.aiAction(context, AnalyticsEvents.AiType.TRANSLATE, model.id, startedAt, failure = cause)
                    host.showMessage(context.getString(cause.messageRes()))
                }
            }
        }
        job = started
        host.onStateChanged()
        started.invokeOnCompletion {
            mainHandler.post {
                if (job === started) job = null
                host.onStateChanged()
                if (!active) return@post
                // Texte tapé pendant la traduction : on traduit la version la plus récente.
                if (pendingSource != writtenSource) {
                    translateNow()
                } else if (commitAfterTranslation) {
                    closeSegment()
                }
            }
        }
    }

    @Volatile private var cancelled = false

    private fun cancelRunning() {
        val running = job ?: return
        cancelled = true
        // L'arrêt natif peut bloquer un instant : hors du thread principal.
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { engine().cancel() }
        running.cancel()
        job = null
    }

    private companion object {
        const val TAG = "LiveTranslator"

        /** Pause de frappe avant de traduire (un peu plus longue que chez Gboard : le modèle local est plus lent). */
        const val PAUSE_MS = 700L
    }
}
