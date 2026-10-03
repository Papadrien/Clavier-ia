package fr.junade.taipo

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.view.View
import android.view.inputmethod.InputConnection
import fr.junade.taipo.clipboard.ClipboardEditBridge
import fr.junade.taipo.clipboard.ClipboardItems
import fr.junade.taipo.clipboard.ClipboardPanelView
import fr.junade.taipo.clipboard.ClipboardPreview
import fr.junade.taipo.clipboard.ClipboardProvider
import fr.junade.taipo.clipboard.ClipboardReader
import fr.junade.taipo.clipboard.ClipboardSuggestionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Smart Clipboard (épopée 2) : puce de collage après une copie récente (stories 2.2 et 2.3), panneau
 * des copies récentes et des éléments épinglés (2.1, 2.5 à 2.9). Extrait de `TaipoIme` au lot 2.3
 * de la revue de code, sans changement de comportement.
 *
 * Tout ce qui touche à l'IME (champ de saisie, barre, clavier, autres panneaux) passe par [Host].
 * Toutes les méthodes sont à appeler depuis le thread principal.
 */
class ClipboardController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mainHandler: Handler,
    private val host: Host,
) {

    /** Ce que le presse-papiers demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        fun inputConnection(): InputConnection?

        /** Retour haptique d'un appui. */
        fun haptic()

        fun clearHighlight()

        /** Oublie l'autocorrection en attente (le texte collé la rend caduque). */
        fun clearPendingAutocorrection()

        /** La barre du haut est créée (sinon la puce n'est pas mise à jour). */
        fun barReady(): Boolean

        fun collapseMenu()

        fun setClipboardPanelOpen(open: Boolean)

        fun setPasteSuggestion(preview: String?, sensitive: Boolean)

        fun isRecording(): Boolean

        fun isCorrectionInProgress(): Boolean

        fun isEmojiPanelVisible(): Boolean

        /** Referme le panneau emoji sans resynchroniser (le panneau presse-papiers prend sa place). */
        fun hideEmojiPanel()

        fun keyboardBottomInsetPx(): Int

        fun setKeyboardVisible(visible: Boolean)

        fun refreshRecentEmojiBar()

        fun clearSuggestions()

        /** Majuscule automatique, état des touches et barre : à resynchroniser après un collage ou la fermeture du panneau. */
        fun resyncKeyboard()
    }

    private var panel: ClipboardPanelView? = null

    // Story 2.2 : puce de collage après une copie récente. Seule la dernière copie est gardée, en
    // mémoire vive ; l'expiration à 10 minutes (story 2.3) est planifiée avec [pasteExpiryRunnable].
    private val state = ClipboardSuggestionState(clock = { System.currentTimeMillis() })
    private val reader by lazy { ClipboardReader(context.applicationContext) { onClipboardChanged() } }
    private var currentPasteSuggestion: ClipboardSuggestionState.Suggestion? = null

    // Story 2.5 : éléments épinglés (base chiffrée séparée de celle du dictionnaire personnel).
    // Le dépôt est instancié dès la création du service : l'ouverture de la base (Keystore) est
    // asynchrone et doit être terminée avant la première ouverture du panneau.
    private val pinnedRepository by lazy { ClipboardProvider.repository(context.applicationContext) }

    // Story 2.9 : historique des copies (1 h, chiffré), dans la même base que les éléments épinglés.
    private val historyRepository by lazy { ClipboardProvider.historyRepository(context.applicationContext) }
    private var panelJob: Job? = null
    private val pasteExpiryRunnable = Runnable { refreshPasteSuggestion() }

    /** Faux dans les champs de mot de passe : la puce de collage y est masquée (story 10.1), relu à chaque champ. */
    var pasteAllowedInField = true

    val isPanelVisible: Boolean
        get() = panel?.visibility == View.VISIBLE

    /** Une copie a été lue (diagnostic de la puce : aucun texte n'est journalisé). */
    val hasLastClip: Boolean get() = state.lastClip() != null

    val hasSuggestion: Boolean get() = state.suggestion() != null

    /**
     * Création du service : ouvre les bases chiffrées dès maintenant (asynchrone, doit être prête
     * avant la première ouverture du panneau) et branche le retour de l'écran de modification (2.6).
     */
    fun start() {
        pinnedRepository
        historyRepository
        ClipboardEditBridge.onLastClipEdited = { text -> onLastClipEdited(text) } // story 2.6
    }

    /** Destruction du service. */
    fun release() {
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        reader.stop()
        state.clear()
        ClipboardEditBridge.onLastClipEdited = null
        ClipboardEditBridge.lastClipText = null
    }

    /** Crée le panneau (masqué) : à ajouter à la hiérarchie de vues par l'IME. */
    fun createPanel(): ClipboardPanelView {
        val view = ClipboardPanelView(context)
        view.visibility = View.GONE
        view.setOnPasteListener { item -> onItemTapped(item) }
        view.setOnPinListener { item -> onItemPin(item) }
        view.setOnEditListener { item -> onItemEdit(item) }
        view.setOnLabelListener { item -> onItemLabel(item) }
        view.setOnDeleteLabelListener { item -> onItemDeleteLabel(item) }
        view.setOnDeleteListener { item -> onItemDelete(item) }
        panel = view
        return view
    }

    /** Écoute des copies tant que le clavier est actif, et relecture à l'ouverture du champ (le processus a pu être tué). */
    fun startListening() {
        reader.start()
        readClipboard()
    }

    fun stopListening() {
        reader.stop()
        mainHandler.removeCallbacks(pasteExpiryRunnable)
    }

    /** Le même bouton de la barre ouvre le panneau et, tant qu'il est ouvert (bouton coloré), le referme. */
    fun onPanelButtonClicked() {
        if (isPanelVisible) {
            host.haptic()
            hidePanel()
        } else {
            showPanel()
        }
    }

    /** Story 2.4 : l'utilisateur a saisi ou supprimé du texte : la puce est écartée pour cette copie (story 2.2). */
    fun onTyping() {
        state.onTyping()
    }

    // ------------------------------------------------------------------
    // Panneau Smart Clipboard (stories 2.1 et 2.5)
    // ------------------------------------------------------------------

    fun showPanel() {
        if (host.isRecording() || host.isCorrectionInProgress()) return
        val view = panel ?: return
        host.haptic()
        host.collapseMenu() // story 2.4 : au retour des touches, mots ou puce, pas le menu ouvert
        host.hideEmojiPanel()
        view.configure(host.keyboardBottomInsetPx())
        host.setKeyboardVisible(false)
        view.visibility = View.VISIBLE
        host.setClipboardPanelOpen(true)
        host.refreshRecentEmojiBar()
        host.clearSuggestions()
        // Stories 2.5 et 2.9 : les cartes suivent les éléments épinglés et l'historique tant que le
        // panneau est ouvert ; les copies expirées sont purgées à l'ouverture.
        refreshPanel()
        panelJob?.cancel()
        panelJob = scope.launch {
            launch { pinnedRepository.pinned.collect { refreshPanel() } }
            launch { historyRepository.history.collect { refreshPanel() } }
            launch {
                try {
                    historyRepository.purgeExpired()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Échec de la purge de l'historique du presse-papiers", t)
                }
            }
        }
    }

    /** Retour aux touches (croix de la barre, nouveau champ, rotation) ; [resync] resynchronise majuscule et barre. */
    fun hidePanel(resync: Boolean = true) {
        if (!isPanelVisible) return
        val view = panel ?: return
        panelJob?.cancel()
        panelJob = null
        view.closeMenu()
        view.visibility = View.GONE
        host.setClipboardPanelOpen(false)
        host.setKeyboardVisible(true)
        host.refreshRecentEmojiBar()
        if (resync) host.resyncKeyboard()
    }

    /**
     * Cartes du panneau : dernière copie (même écartée ou collée), copies récentes de l'historique
     * (moins d'1 h), puis éléments épinglés.
     */
    private fun refreshPanel() {
        if (!isPanelVisible) return
        panel?.setItems(
            ClipboardItems.build(
                lastClip = state.lastClip(),
                pinned = pinnedRepository.snapshot(),
                history = historyRepository.snapshot(),
                nowMillis = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Appui sur une carte : colle son texte au curseur (`commitText` remplace une éventuelle
     * sélection) et revient aux touches. Coller la dernière copie compte comme collage de la puce.
     */
    private fun onItemTapped(item: ClipboardItems.Item) {
        val ic = host.inputConnection() ?: return
        host.haptic()
        host.clearHighlight()
        host.clearPendingAutocorrection()
        ic.commitText(item.text, 1)
        if (item.isLastClip) state.onPasted()
        host.collapseMenu()
        hidePanel() // resynchronise majuscule, suggestions et barre
    }

    /** Menu d'appui long, « Épingler » : refus expliqués par un message (sensible, vide, trop long, doublon, plafond). */
    private fun onItemPin(item: ClipboardItems.Item) {
        ClipboardItems.pinRefusal(item.text, item.sensitive)?.let {
            host.showMessage(pinMessage(it))
            return
        }
        scope.launch {
            try {
                val result = pinnedRepository.pin(item.text)
                host.showMessage(pinMessage(result))
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de l'épinglage", t)
                host.showMessage(context.getString(R.string.clipboard_pin_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    private fun pinMessage(result: ClipboardItems.PinResult): String = context.getString(
        when (result) {
            ClipboardItems.PinResult.PINNED -> R.string.clipboard_pin_done
            ClipboardItems.PinResult.ALREADY_PINNED -> R.string.clipboard_pin_already
            ClipboardItems.PinResult.FULL -> R.string.clipboard_pin_full
            ClipboardItems.PinResult.TOO_LONG -> R.string.clipboard_pin_too_long
            ClipboardItems.PinResult.SENSITIVE -> R.string.clipboard_pin_sensitive
            ClipboardItems.PinResult.EMPTY -> R.string.clipboard_pin_empty
        },
    )

    /**
     * Story 2.6, menu d'appui long, « Modifier » : ouvre l'écran de modification. Un élément épinglé
     * ou une ligne d'historique (story 2.9) y est identifié par son id ; la dernière copie, qui
     * existe en mémoire ici, lui est passée par [ClipboardEditBridge] et revient par [onLastClipEdited].
     */
    private fun onItemEdit(item: ClipboardItems.Item) {
        if (!ClipboardItems.canEdit(item)) return
        val intent = Intent(context, ClipboardEditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pinnedId = item.pinnedId
        val historyId = item.historyId
        if (pinnedId != null) {
            intent.putExtra(ClipboardEditActivity.EXTRA_PINNED_ID, pinnedId)
        } else if (!item.isLastClip && historyId != null) {
            // Story 2.9 : ligne d'historique (pas la dernière copie) : relue et réécrite par son id.
            intent.putExtra(ClipboardEditActivity.EXTRA_HISTORY_ID, historyId)
        } else {
            ClipboardEditBridge.lastClipText = item.text
        }
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Échec de l'ouverture de l'écran de modification", t)
            ClipboardEditBridge.lastClipText = null
            host.showMessage(context.getString(R.string.clipboard_edit_error, t.message ?: t.javaClass.simpleName))
        }
    }

    /**
     * Stories 2.7 et 2.8, menu d'appui long, « Ajouter une étiquette » / « Modifier l'étiquette » :
     * ouvre le pop-up de saisie de l'étiquette de l'élément épinglé (identifié par son id).
     */
    private fun onItemLabel(item: ClipboardItems.Item) {
        if (!ClipboardItems.canLabel(item)) return
        val id = item.pinnedId ?: return
        val intent = Intent(context, ClipboardLabelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ClipboardLabelActivity.EXTRA_PINNED_ID, id)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Échec de l'ouverture du pop-up d'étiquette", t)
            host.showMessage(context.getString(R.string.clipboard_label_error, t.message ?: t.javaClass.simpleName))
        }
    }

    /** Story 2.8, « Supprimer l'étiquette » (après confirmation dans le panneau) : le panneau suit le flux des éléments épinglés. */
    private fun onItemDeleteLabel(item: ClipboardItems.Item) {
        val id = item.pinnedId ?: return
        scope.launch {
            try {
                pinnedRepository.clearLabel(id)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de la suppression d'une étiquette", t)
                host.showMessage(context.getString(R.string.clipboard_label_delete_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /** Retour de l'écran de modification pour la dernière copie : le panneau et la puce montrent le nouveau texte. */
    private fun onLastClipEdited(text: String) {
        val previous = state.lastClip()?.text
        state.onEdited(text)
        if (previous != null && previous != text) {
            // Story 2.9 : sa ligne d'historique suit, sinon l'ancien texte réapparaîtrait en doublon.
            scope.launch {
                try {
                    historyRepository.replaceText(previous, text)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Échec de la mise à jour de l'historique après modification", t)
                }
            }
        }
        refreshPanel()
        refreshPasteSuggestion()
    }

    /**
     * Menu d'appui long, « Supprimer » (après confirmation dans le panneau) : un élément épinglé est
     * retiré de la base ; la dernière copie est retirée du panneau (et de la puce) jusqu'à la
     * prochaine copie ; la ligne d'historique qui porte le même texte (story 2.9) est supprimée
     * aussi, sinon la carte reviendrait aussitôt comme copie récente.
     */
    private fun onItemDelete(item: ClipboardItems.Item) {
        if (item.isLastClip) {
            state.onDeleted()
            refreshPanel()
        }
        val pinnedId = item.pinnedId
        val historyId = item.historyId
        if (pinnedId == null && historyId == null) return
        scope.launch {
            try {
                // Le panneau se met à jour via les flux des éléments épinglés et de l'historique.
                if (pinnedId != null) pinnedRepository.remove(pinnedId)
                if (historyId != null) historyRepository.remove(historyId)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de la suppression d'un élément du presse-papiers", t)
                host.showMessage(context.getString(R.string.clipboard_delete_error, t.message ?: t.javaClass.simpleName))
            }
        }
    }

    // ------------------------------------------------------------------
    // Puce de collage après une copie récente (stories 2.2 et 2.3)
    // ------------------------------------------------------------------

    /** Une copie vient d'être faite (écoute active tant que le clavier est affiché). */
    private fun onClipboardChanged() {
        readClipboard()
        refreshPasteSuggestion()
        refreshPanel() // story 2.5 : une copie faite pendant que le panneau est ouvert s'y ajoute
    }

    private fun readClipboard() {
        val snapshot = reader.read()
        val isNewCopy = state.onClipRead(snapshot?.text, snapshot?.copiedAtMillis ?: 0L, snapshot?.sensitive ?: false)
        if (isNewCopy && snapshot != null) recordInHistory(snapshot)
    }

    /**
     * Story 2.9 : une nouvelle copie entre dans l'historique chiffré (1 h). Une copie vide ou de
     * plus de 10 000 caractères reste en mémoire du clavier seulement ; une copie sensible est
     * enregistrée comme les autres, avec son drapeau (le panneau en masque l'aperçu). Le texte
     * copié n'est jamais journalisé.
     */
    private fun recordInHistory(snapshot: ClipboardReader.Snapshot) {
        if (ClipboardItems.historyRefusal(snapshot.text)) return
        scope.launch {
            try {
                historyRepository.record(snapshot.text, snapshot.copiedAtMillis, snapshot.sensitive)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Échec de l'enregistrement dans l'historique du presse-papiers", t)
            }
        }
    }

    /**
     * Rien n'est proposé pendant une dictée ou une correction, sous un panneau (emoji, presse-papiers),
     * ni dans les champs sans suggestions (mot de passe, e-mail, URL…).
     */
    private fun pasteSuggestionAllowed(): Boolean =
        pasteAllowedInField && !host.isRecording() && !host.isCorrectionInProgress() &&
            !host.isEmojiPanelVisible() && !isPanelVisible

    /** Met la puce de la barre d'accord avec l'état de la copie, et planifie son expiration (story 2.3). */
    fun refreshPasteSuggestion() {
        if (!host.barReady()) return
        val suggestion = if (pasteSuggestionAllowed()) state.suggestion() else null
        if (suggestion != currentPasteSuggestion) {
            currentPasteSuggestion = suggestion
            if (suggestion == null) {
                host.setPasteSuggestion(null, sensitive = false)
            } else {
                val preview = ClipboardPreview.forDisplay(suggestion.text, suggestion.sensitive)
                host.setPasteSuggestion(preview, suggestion.sensitive)
            }
        }
        mainHandler.removeCallbacks(pasteExpiryRunnable)
        state.expiresInMillis()?.let { mainHandler.postDelayed(pasteExpiryRunnable, it + PASTE_EXPIRY_MARGIN_MS) }
    }

    /**
     * Appui sur la puce : colle le texte copié au curseur (`commitText` remplace une éventuelle
     * sélection), réinitialise l'autocorrection en attente et resynchronise la barre.
     */
    fun onPasteTapped() {
        val suggestion = currentPasteSuggestion ?: return
        if (host.isRecording() || host.isCorrectionInProgress()) return
        val ic = host.inputConnection() ?: return
        host.haptic()
        host.clearHighlight()
        host.clearPendingAutocorrection()
        ic.commitText(suggestion.text, 1)
        state.onPasted()
        host.collapseMenu()
        host.resyncKeyboard()
    }

    private companion object {
        private const val TAG = "ClipboardController"

        /** Marge ajoutée au délai d'expiration de la puce, pour que l'horloge ait bien dépassé l'échéance. */
        private const val PASTE_EXPIRY_MARGIN_MS = 50L
    }
}
