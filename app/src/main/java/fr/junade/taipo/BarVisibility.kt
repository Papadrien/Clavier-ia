package fr.junade.taipo

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/**
 * Règles d'affichage de la barre du haut (lot 4.2) : quels boutons et quelle zone sont visibles, et
 * l'apparence des boutons Corriger et Vocal selon leur état. Logique pure (sans vue), extraite de
 * [CorrectionBarView] sans changement de comportement, testée en JVM.
 */
internal data class ActionButtonsVisibility(
    val voice: Boolean,
    val generate: Boolean,
    val correct: Boolean,
    val actionsMenu: Boolean,
)

internal data class ZoneContentVisibility(
    val words: Boolean,
    val clipboardButton: Boolean,
    val pasteChip: Boolean,
    val inline: Boolean,
    val menuButton: Boolean,
    val settingsButton: Boolean,
)

/** Apparence du bouton Corriger (état [CorrectionBarState.HIDDEN] : aucun changement d'apparence). */
internal data class CorrectAppearance(@StringRes val label: Int, val enabled: Boolean, val alpha: Float)

/** Apparence du bouton Vocal. */
internal data class VoiceAppearance(@StringRes val label: Int, @ColorRes val background: Int, val alpha: Float)

internal object BarVisibility {

    /** Écoute, transcription ou correction en cours : les boutons concernés servent d'arrêt ou d'indicateur. */
    fun isBusy(voice: VoiceBarState, correction: CorrectionBarState): Boolean =
        voice != VoiceBarState.IDLE ||
            correction == CorrectionBarState.LOADING || correction == CorrectionBarState.CORRECTING

    /**
     * Vocal et Corriger sont rangés derrière le menu de droite tant que la bande de mots est affichée,
     * sauf pendant une écoute, une transcription ou une correction. « Générer » se range avec eux mais
     * disparaît quand [isBusy] (le moteur est partagé : pas de génération en parallèle).
     */
    fun actionButtons(
        voice: VoiceBarState,
        correction: CorrectionBarState,
        zoneState: SuggestionZoneState,
        clipboardPanelOpen: Boolean,
    ): ActionButtonsVisibility {
        val busy = isBusy(voice, correction)
        val actionsVisible = zoneState.actionButtonsVisible(busy)
        return ActionButtonsVisibility(
            voice = actionsVisible,
            generate = actionsVisible && !busy,
            correct = actionsVisible && correction != CorrectionBarState.HIDDEN,
            actionsMenu = !clipboardPanelOpen && zoneState.actionsMenuButtonVisible(busy),
        )
    }

    /**
     * Panneau Smart Clipboard ouvert : la zone ne montre que le bouton Smart Clipboard (actif), quel que
     * soit l'état de saisie, et les menus « ··· » de gauche ainsi que la roue crantée se cachent.
     */
    fun zoneContent(zoneState: SuggestionZoneState, clipboardPanelOpen: Boolean): ZoneContentVisibility {
        val zone = if (clipboardPanelOpen) SuggestionZoneState.Zone.CLIPBOARD else zoneState.zone
        return ZoneContentVisibility(
            words = zone == SuggestionZoneState.Zone.WORDS,
            clipboardButton = zone == SuggestionZoneState.Zone.CLIPBOARD,
            pasteChip = zone == SuggestionZoneState.Zone.PASTE,
            inline = zone == SuggestionZoneState.Zone.INLINE,
            menuButton = zoneState.menuButtonVisible && !clipboardPanelOpen,
            settingsButton = zoneState.settingsButtonVisible && !clipboardPanelOpen,
        )
    }

    fun correctAppearance(state: CorrectionBarState): CorrectAppearance? = when (state) {
        CorrectionBarState.HIDDEN -> null
        CorrectionBarState.IDLE -> CorrectAppearance(R.string.correction_button_idle, enabled = true, alpha = 1f)
        CorrectionBarState.LOADING -> CorrectAppearance(R.string.correction_button_loading, enabled = false, alpha = 0.6f)
        CorrectionBarState.CORRECTING -> CorrectAppearance(R.string.correction_button_correcting, enabled = false, alpha = 0.6f)
    }

    fun voiceAppearance(state: VoiceBarState): VoiceAppearance = when (state) {
        VoiceBarState.IDLE -> VoiceAppearance(R.string.voice_button_idle, R.color.surface_button, 1f)
        VoiceBarState.LOADING -> VoiceAppearance(R.string.voice_button_loading, R.color.surface_button, 0.6f)
        VoiceBarState.RECORDING -> VoiceAppearance(R.string.voice_button_recording, R.color.action_danger, 1f)
        VoiceBarState.TRANSCRIBING -> VoiceAppearance(R.string.voice_button_transcribing, R.color.surface_button, 0.6f)
    }
}
