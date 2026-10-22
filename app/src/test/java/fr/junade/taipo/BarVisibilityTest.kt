package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Règles d'affichage de la barre du haut à deux états (lot 4.2, refonte « normale / saisie »). */
class BarVisibilityTest {

    private fun typing(): SuggestionZoneState = SuggestionZoneState().apply { onFieldTextChanged(true) }

    private fun typingMenuOpen(): SuggestionZoneState = typing().apply { toggleMenu() }

    private fun actions(
        zone: SuggestionZoneState,
        correction: CorrectionBarState = CorrectionBarState.IDLE,
        voice: VoiceBarState = VoiceBarState.IDLE,
        wordsShown: Boolean = false,
        aiTranscription: AiTranscriptionBarState = AiTranscriptionBarState.IDLE,
    ): ActionButtonsVisibility {
        val mode = BarVisibility.mode(zone, correction, aiTranscription)
        return BarVisibility.actionButtons(voice, correction, zone, mode, wordsShown, aiTranscription)
    }

    private fun content(zone: SuggestionZoneState, correction: CorrectionBarState = CorrectionBarState.IDLE): BarContentVisibility =
        BarVisibility.content(zone, BarVisibility.mode(zone, correction, AiTranscriptionBarState.IDLE))

    @Test
    fun `occupe pendant une ecoute une transcription ou une correction, pas au repos`() {
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.IDLE))
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.HIDDEN))
        assertTrue(BarVisibility.isBusy(VoiceBarState.RECORDING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.LOADING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.LOADING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CORRECTING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CANCELLING))
    }

    @Test
    fun `barre normale montre seulement sa rangee avec Parametres Smart Clipboard Annuler et Vocal`() {
        val zone = SuggestionZoneState()
        val shown = content(zone, CorrectionBarState.HIDDEN)
        assertTrue(shown.normalRow)
        assertFalse(shown.typingRow)
        assertFalse(shown.robot)
        assertFalse(shown.words)
        assertFalse(shown.menuUtilityButtons)
        val buttons = actions(zone, CorrectionBarState.HIDDEN)
        assertTrue(buttons.voice)
        assertFalse(buttons.correct)
        assertFalse(buttons.generate)
        assertFalse(buttons.aiTranscription)
    }

    @Test
    fun `la puce de collage est dans la rangee normale a cote du bouton Smart Clipboard`() {
        val zone = SuggestionZoneState().apply { setPasteAvailable(true) }
        assertTrue(content(zone, CorrectionBarState.HIDDEN).pasteChip)
        // Pendant la saisie, la puce n'est plus affichée (elle revient quand le champ est vide).
        zone.onFieldTextChanged(true)
        assertFalse(content(zone).pasteChip)
    }

    @Test
    fun `saisie menu ferme montre le robot et les mots, Vocal et Corriger`() {
        val zone = typing()
        val shown = content(zone)
        assertTrue(shown.typingRow)
        assertFalse(shown.normalRow)
        assertTrue(shown.robot)
        assertTrue(shown.words)
        assertFalse(shown.inline)
        assertFalse(shown.menuUtilityButtons)
        val buttons = actions(zone)
        assertTrue(buttons.voice)
        assertTrue(buttons.correct)
        assertFalse(buttons.generate)
        assertFalse(buttons.aiTranscription)
    }

    @Test
    fun `menu ouvert la bande est effacee et la rangee devient Robot Parametres Clipboard Annuler Chat Corriger Transcription`() {
        val zone = typingMenuOpen()
        val shown = content(zone)
        assertTrue(shown.robot)
        assertFalse(shown.words)
        assertTrue(shown.menuUtilityButtons)
        val buttons = actions(zone)
        assertTrue(buttons.voice)
        assertTrue(buttons.generate)
        assertTrue(buttons.correct)
        assertTrue(buttons.aiTranscription)
    }

    @Test
    fun `Corriger reste cache tant que son etat est HIDDEN`() {
        assertFalse(actions(typing(), CorrectionBarState.HIDDEN).correct)
    }

    @Test
    fun `Corriger se cache tant qu une suggestion de mots est affichee et revient sans suggestion`() {
        val zone = typing()
        assertFalse(actions(zone, wordsShown = true).correct)
        assertTrue(actions(zone, wordsShown = false).correct)
    }

    @Test
    fun `Corriger reste visible hors du repos meme avec une suggestion affichee`() {
        val zone = typing()
        for (state in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(actions(zone, state, wordsShown = true).correct, "état $state")
        }
    }

    @Test
    fun `menu ouvert Corriger est visible meme avec des mots proposes`() {
        assertTrue(actions(typingMenuOpen(), wordsShown = true).correct)
    }

    @Test
    fun `les suggestions d auto-remplissage cachent Corriger et remplacent la bande`() {
        val zone = typing().apply { setInlineAvailable(true) }
        val shown = content(zone)
        assertTrue(shown.inline)
        assertFalse(shown.words)
        assertFalse(actions(zone).correct)
    }

    @Test
    fun `l auto-remplissage sur un champ vide donne la rangee de saisie`() {
        val zone = SuggestionZoneState().apply { setInlineAvailable(true) }
        val shown = content(zone, CorrectionBarState.HIDDEN)
        assertTrue(shown.typingRow)
        assertTrue(shown.inline)
        assertTrue(shown.robot)
    }

    @Test
    fun `suggestionsDisplayed ne compte ni la barre normale ni le menu ouvert`() {
        val normal = SuggestionZoneState()
        assertFalse(BarVisibility.suggestionsDisplayed(normal, BarMode.NORMAL, wordSuggestionsShown = true))
        val menu = typingMenuOpen()
        assertFalse(BarVisibility.suggestionsDisplayed(menu, BarMode.TYPING, wordSuggestionsShown = true))
        assertTrue(BarVisibility.suggestionsDisplayed(typing(), BarMode.TYPING, wordSuggestionsShown = true))
        assertFalse(BarVisibility.suggestionsDisplayed(typing(), BarMode.TYPING, wordSuggestionsShown = false))
    }

    @Test
    fun `pendant le traitement d une transcription IA le bouton reste visible menu ferme`() {
        val zone = typing()
        val processing = actions(zone, aiTranscription = AiTranscriptionBarState.PROCESSING)
        assertTrue(processing.aiTranscription)
        assertFalse(processing.generate) // seul Transcription IA reste hors du menu : Chat reste derrière
        assertFalse(actions(zone, aiTranscription = AiTranscriptionBarState.RECORDING).aiTranscription)
        assertFalse(actions(zone).aiTranscription)
    }

    @Test
    fun `pendant une ecoute Chat disparait et le menu reste`() {
        val zone = typingMenuOpen()
        val buttons = actions(zone, voice = VoiceBarState.RECORDING)
        assertTrue(buttons.voice)
        assertFalse(buttons.generate)
        assertTrue(buttons.aiTranscription)
        assertTrue(content(zone).robot)
    }

    @Test
    fun `une correction en cours rend la barre de saisie meme sur un champ vide`() {
        val zone = SuggestionZoneState()
        assertEquals(BarMode.TYPING, BarVisibility.mode(zone, CorrectionBarState.CORRECTING, AiTranscriptionBarState.IDLE))
        assertEquals(BarMode.TYPING, BarVisibility.mode(zone, CorrectionBarState.IDLE, AiTranscriptionBarState.PROCESSING))
        assertEquals(BarMode.NORMAL, BarVisibility.mode(zone, CorrectionBarState.IDLE, AiTranscriptionBarState.IDLE))
    }

    @Test
    fun `panneau Smart Clipboard ouvert la barre ne change pas meme pendant une correction`() {
        val zone = SuggestionZoneState().apply { setClipboardPanelOpen(true) }
        assertEquals(BarMode.NORMAL, BarVisibility.mode(zone, CorrectionBarState.CORRECTING, AiTranscriptionBarState.IDLE))
    }

    @Test
    fun `les actions IA passent en pilules seulement quand le menu est ouvert`() {
        assertTrue(BarVisibility.labeledActions(typingMenuOpen(), BarMode.TYPING))
        assertFalse(BarVisibility.labeledActions(typing(), BarMode.TYPING))
        assertFalse(BarVisibility.labeledActions(SuggestionZoneState(), BarMode.NORMAL))
    }

    @Test
    fun `apparence de Corriger selon l etat`() {
        assertNull(BarVisibility.correctAppearance(CorrectionBarState.HIDDEN))
        val idle = BarVisibility.correctAppearance(CorrectionBarState.IDLE)!!
        assertEquals(R.string.correction_button_idle, idle.label)
        assertTrue(idle.enabled)
        assertEquals(1f, idle.alpha)
        // Chargement / correction en cours : le bouton reste actif, un nouvel appui annule.
        val loading = BarVisibility.correctAppearance(CorrectionBarState.LOADING)!!
        assertTrue(loading.enabled)
        assertEquals(1f, loading.alpha)
        for (state in listOf(CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertFalse(BarVisibility.correctAppearance(state)!!.enabled)
        }
        assertEquals(1f, BarVisibility.correctAppearance(CorrectionBarState.CORRECTING)!!.alpha)
        assertEquals(0.6f, BarVisibility.correctAppearance(CorrectionBarState.CANCELLING)!!.alpha)
        for (state in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(BarVisibility.correctAppearance(state)!!.loading, "une roue remplace l'icône pendant $state")
        }
        assertFalse(BarVisibility.correctAppearance(CorrectionBarState.IDLE)!!.loading)
    }

    @Test
    fun `Corriger a le fond violet pendant le traitement de la correction seulement`() {
        assertEquals(R.color.accent, BarVisibility.correctAppearance(CorrectionBarState.CORRECTING)!!.face)
        for (state in listOf(CorrectionBarState.IDLE, CorrectionBarState.LOADING, CorrectionBarState.CANCELLING)) {
            assertEquals(R.color.surface_button, BarVisibility.correctAppearance(state)!!.face, "fond gris pendant $state")
        }
    }

    @Test
    fun `Transcription IA a le fond violet pendant le traitement seulement`() {
        val processing = BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.PROCESSING)
        assertEquals(R.color.accent, processing.face)
        assertEquals(1f, processing.alpha)
        assertEquals(R.color.action_danger, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.RECORDING).face)
        assertEquals(R.color.surface_button, BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.IDLE).face)
        val cancelling = BarVisibility.aiTranscriptionAppearance(AiTranscriptionBarState.CANCELLING)
        assertEquals(R.color.surface_button, cancelling.face)
        assertEquals(0.6f, cancelling.alpha)
    }

    @Test
    fun `apparence de Vocal selon l etat, rouge a l ecoute et violet a la transcription`() {
        val recording = BarVisibility.voiceAppearance(VoiceBarState.RECORDING)
        assertEquals(R.color.action_danger, recording.background)
        assertEquals(1f, recording.alpha)
        assertEquals(R.string.voice_button_recording, recording.label)
        for (state in listOf(VoiceBarState.IDLE, VoiceBarState.LOADING)) {
            assertEquals(R.color.surface_button, BarVisibility.voiceAppearance(state).background)
        }
        // Transcription en cours de traitement : fond violet.
        assertEquals(R.color.accent, BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).background)
        // Seul le chargement du modèle remplace l'icône micro par une roue animée.
        assertEquals(true, BarVisibility.voiceAppearance(VoiceBarState.LOADING).loading)
        listOf(VoiceBarState.IDLE, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING).forEach { state ->
            assertEquals(false, BarVisibility.voiceAppearance(state).loading)
        }
        assertEquals(1f, BarVisibility.voiceAppearance(VoiceBarState.IDLE).alpha)
        assertEquals(0.6f, BarVisibility.voiceAppearance(VoiceBarState.LOADING).alpha)
        assertEquals(1f, BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).alpha)
    }
}
