package fr.junade.taipo

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/**
 * Règles d'affichage de la barre du haut (lot 4.2) : quels boutons et quelle zone sont visibles, et
 * l'apparence des boutons Corriger et Vocal selon leur état. Logique pure (sans vue), extraite de
 * [CorrectionBarView] sans changement de comportement, testée en JVM.
 */
/** Story 10.1 : opacité d'un bouton grisé parce que le champ est sensible (il reste touchable pour afficher la raison). */
internal const val RESTRICTED_ALPHA = 0.4f

internal data class ActionButtonsVisibility(
    val voice: Boolean,
    /** Transcription IA : visible avec les actions IA (rangée derrière le menu « robot » pendant les suggestions de mots). */
    val aiTranscription: Boolean,
    val generate: Boolean,
    val correct: Boolean,
    val actionsMenu: Boolean,
)

internal data class ZoneContentVisibility(
    val words: Boolean,
    val clipboardButton: Boolean,
    /** Bouton Annuler : juste à droite du bouton Smart Clipboard, visible avec lui. */
    val undoButton: Boolean,
    val pasteChip: Boolean,
    val inline: Boolean,
    val menuButton: Boolean,
    val settingsButton: Boolean,
)

/** Apparence du bouton Corriger (état [CorrectionBarState.HIDDEN] : aucun changement d'apparence). */
internal data class CorrectAppearance(
    @StringRes val label: Int,
    val enabled: Boolean,
    val alpha: Float,
    /** Chargement du modèle ou correction en cours : une roue animée remplace l'icône du bouton. */
    val loading: Boolean = false,
)

/** Apparence du bouton Vocal. */
internal data class VoiceAppearance(
    @StringRes val label: Int,
    @ColorRes val background: Int,
    val alpha: Float,
    /** Le modèle se charge (le contour de la capsule du micro se dessine alors en boucle ; voir [MicIconMode]). */
    val loading: Boolean = false,
)

internal object BarVisibility {

    /** Écoute, transcription ou correction en cours : les boutons concernés servent d'arrêt ou d'indicateur. */
    fun isBusy(voice: VoiceBarState, correction: CorrectionBarState): Boolean =
        voice != VoiceBarState.IDLE ||
            correction == CorrectionBarState.LOADING || correction == CorrectionBarState.CORRECTING ||
                correction == CorrectionBarState.CANCELLING

    /**
     * Vocal est toujours visible, à droite du menu « robot ». Transcription IA et « Générer » sont rangés derrière ce
     * menu tant que la bande de mots est affichée, sauf pendant une écoute, une transcription ou une correction. Corriger, lui, est visible (à gauche du
     * menu « robot ») dès que du texte permet de corriger, sauf tant qu'une suggestion est affichée. « Générer »
     * disparaît quand [isBusy] (le moteur est partagé : pas de génération en parallèle).
     *
     * Corriger se cache tant qu'une suggestion est affichée dans la barre ([suggestionsDisplayed]) et réapparaît
     * quand il n'y en a aucune. Il reste visible dès que son état n'est plus au repos (chargement, correction en
     * cours) : le bouton sert alors d'annulation ou d'indicateur et ne doit pas disparaître.
     * [wordSuggestionsShown] : la bande de mots affiche au moins un mot ou un emoji.
     */
    fun actionButtons(
        voice: VoiceBarState,
        correction: CorrectionBarState,
        zoneState: SuggestionZoneState,
        clipboardPanelOpen: Boolean,
        wordSuggestionsShown: Boolean = false,
    ): ActionButtonsVisibility {
        val busy = isBusy(voice, correction)
        val actionsVisible = zoneState.actionButtonsVisible(busy)
        val hiddenBySuggestion = correction == CorrectionBarState.IDLE &&
            suggestionsDisplayed(zoneState, clipboardPanelOpen, wordSuggestionsShown)
        return ActionButtonsVisibility(
            voice = true,
            aiTranscription = actionsVisible,
            generate = actionsVisible && !busy,
            correct = (actionsVisible || zoneState.zone == SuggestionZoneState.Zone.WORDS) &&
                correction != CorrectionBarState.HIDDEN && !hiddenBySuggestion,
            actionsMenu = !clipboardPanelOpen && zoneState.actionsMenuButtonVisible(busy),
        )
    }

    /**
     * Une suggestion est affichée dans la zone de gauche : mots ou emoji de la bande (zone des mots, au moins un
     * emplacement rempli, [wordSuggestionsShown]) ou suggestions d'auto-remplissage en ligne. Ni la puce de collage, ni le
     * bouton Smart Clipboard, ni le menu robot ouvert (la bande est alors effacée), ni le panneau Smart Clipboard
     * ouvert ne comptent.
     */
    fun suggestionsDisplayed(
        zoneState: SuggestionZoneState,
        clipboardPanelOpen: Boolean,
        wordSuggestionsShown: Boolean,
    ): Boolean = !clipboardPanelOpen && when (zoneState.zone) {
        SuggestionZoneState.Zone.WORDS -> wordSuggestionsShown
        SuggestionZoneState.Zone.INLINE -> true
        else -> false
    }

    /**
     * Menu « robot » ouvert (zone [SuggestionZoneState.Zone.ACTIONS]) : les actions IA s'affichent en pilules icône + texte.
     * Dans toutes les autres zones (suggestions de mots, Smart Clipboard, puce de collage, auto-remplissage) ou panneau
     * Smart Clipboard ouvert, elles restent des boutons ronds à icône seule. Vocal n'a jamais de texte.
     */
    fun labeledActions(zoneState: SuggestionZoneState, clipboardPanelOpen: Boolean): Boolean =
        !clipboardPanelOpen && zoneState.zone == SuggestionZoneState.Zone.ACTIONS

    /**
     * Panneau Smart Clipboard ouvert : la zone ne montre que le bouton Smart Clipboard (actif), quel que
     * soit l'état de saisie, et les menus « ··· » de gauche ainsi que la roue crantée se cachent.
     */
    fun zoneContent(zoneState: SuggestionZoneState, clipboardPanelOpen: Boolean): ZoneContentVisibility {
        val zone = if (clipboardPanelOpen) SuggestionZoneState.Zone.CLIPBOARD else zoneState.zone
        return ZoneContentVisibility(
            words = zone == SuggestionZoneState.Zone.WORDS,
            clipboardButton = zone == SuggestionZoneState.Zone.CLIPBOARD,
            undoButton = zone == SuggestionZoneState.Zone.CLIPBOARD,
            pasteChip = zone == SuggestionZoneState.Zone.PASTE,
            inline = zone == SuggestionZoneState.Zone.INLINE,
            menuButton = zoneState.menuButtonVisible && !clipboardPanelOpen,
            settingsButton = zoneState.settingsButtonVisible && !clipboardPanelOpen,
        )
    }

    fun correctAppearance(state: CorrectionBarState): CorrectAppearance? = when (state) {
        CorrectionBarState.HIDDEN -> null
        CorrectionBarState.IDLE -> CorrectAppearance(R.string.correction_button_idle, enabled = true, alpha = 1f)
        // Chargement ou correction en cours : un nouvel appui sur le bouton annule la correction.
        CorrectionBarState.LOADING -> CorrectAppearance(R.string.correction_button_loading, enabled = true, alpha = 1f, loading = true)
        CorrectionBarState.CORRECTING -> CorrectAppearance(R.string.correction_button_correcting, enabled = false, alpha = 0.6f, loading = true)
        CorrectionBarState.CANCELLING -> CorrectAppearance(R.string.correction_button_cancelling, enabled = false, alpha = 0.6f, loading = true)
    }

    fun voiceAppearance(state: VoiceBarState): VoiceAppearance = when (state) {
        VoiceBarState.IDLE -> VoiceAppearance(R.string.voice_button_idle, R.color.surface_button, 1f)
        VoiceBarState.LOADING -> VoiceAppearance(R.string.voice_button_loading, R.color.surface_button, 0.6f, loading = true)
        VoiceBarState.RECORDING -> VoiceAppearance(R.string.voice_button_recording, R.color.action_danger, 1f)
        VoiceBarState.TRANSCRIBING -> VoiceAppearance(R.string.voice_button_transcribing, R.color.surface_button, 0.6f)
    }
}
