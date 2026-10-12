package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lot 4.2 : règles d'affichage de la barre du haut, extraites de CorrectionBarView sans changement de comportement. */
class BarVisibilityTest {

    private fun zoneWithText(): SuggestionZoneState = SuggestionZoneState().apply { onFieldTextChanged(true) }

    @Test
    fun `occupe pendant une ecoute une transcription ou une correction, pas au repos`() {
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.IDLE))
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.HIDDEN))
        assertFalse(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.UNDO))
        assertTrue(BarVisibility.isBusy(VoiceBarState.RECORDING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.LOADING, CorrectionBarState.IDLE))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.LOADING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CORRECTING))
        assertTrue(BarVisibility.isBusy(VoiceBarState.IDLE, CorrectionBarState.CANCELLING))
    }

    @Test
    fun `apres une correction le bouton Annuler prend la place de Corriger et reste visible`() {
        val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.UNDO, zoneWithText(), false)
        assertTrue(visible.correct)
        assertFalse(visible.generate)
        assertTrue(visible.voice)
    }

    @Test
    fun `pendant les suggestions de mots Transcription IA et Generer sont ranges derriere le menu, Vocal et Corriger restent visibles`() {
        val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, zoneWithText(), false)
        assertTrue(visible.voice)
        assertFalse(visible.aiTranscription)
        assertFalse(visible.generate)
        assertTrue(visible.correct)
        assertTrue(visible.actionsMenu)
    }

    @Test
    fun `pendant les suggestions de mots Corriger reste cache tant que son etat est HIDDEN`() {
        val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.HIDDEN, zoneWithText(), false)
        assertFalse(visible.correct)
    }

    @Test
    fun `champ vide Vocal et Generer sont visibles, Corriger seulement s il y a du texte`() {
        val empty = SuggestionZoneState()
        val hidden = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.HIDDEN, empty, false)
        assertTrue(hidden.voice)
        assertTrue(hidden.aiTranscription)
        assertTrue(hidden.generate)
        assertFalse(hidden.correct)
        assertFalse(hidden.actionsMenu)

        val idle = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, empty, false)
        assertTrue(idle.correct)
    }

    @Test
    fun `pendant une ecoute Vocal reste visible mais Generer disparait et le menu de droite aussi`() {
        val visible = BarVisibility.actionButtons(VoiceBarState.RECORDING, CorrectionBarState.IDLE, zoneWithText(), false)
        assertTrue(visible.voice)
        assertFalse(visible.generate)
        assertTrue(visible.correct)
        assertFalse(visible.actionsMenu)
    }

    @Test
    fun `Corriger se cache tant qu une suggestion de mots est affichee et revient sans suggestion`() {
        val withSuggestion = BarVisibility.actionButtons(
            VoiceBarState.IDLE, CorrectionBarState.IDLE, zoneWithText(), false, wordSuggestionsShown = true,
        )
        assertFalse(withSuggestion.correct)
        assertTrue(withSuggestion.actionsMenu)
        assertTrue(withSuggestion.voice)

        val without = BarVisibility.actionButtons(
            VoiceBarState.IDLE, CorrectionBarState.IDLE, zoneWithText(), false, wordSuggestionsShown = false,
        )
        assertTrue(without.correct)
    }

    @Test
    fun `Corriger reste visible hors du repos meme avec une suggestion affichee`() {
        for (state in listOf(
            CorrectionBarState.UNDO,
            CorrectionBarState.LOADING,
            CorrectionBarState.CORRECTING,
            CorrectionBarState.CANCELLING,
        )) {
            val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, state, zoneWithText(), false, wordSuggestionsShown = true)
            assertTrue("état $state", visible.correct)
        }
    }

    @Test
    fun `menu robot ouvert la bande est effacee donc Corriger est visible`() {
        val zone = zoneWithText().apply { toggleActionsMenu() }
        val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, zone, false, wordSuggestionsShown = true)
        assertTrue(visible.correct)
    }

    @Test
    fun `les suggestions d auto-remplissage en ligne cachent Corriger, la puce de collage non`() {
        val inline = zoneWithText().apply { setInlineAvailable(true) }
        assertFalse(BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, inline, false).correct)

        val paste = SuggestionZoneState().apply { setPasteAvailable(true) } // champ vide : la puce est affichée
        assertTrue(BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, paste, false).correct)
    }

    @Test
    fun `suggestionsDisplayed ne compte ni le panneau Smart Clipboard ni le champ vide`() {
        assertTrue(BarVisibility.suggestionsDisplayed(zoneWithText(), clipboardPanelOpen = false, wordSuggestionsShown = true))
        assertFalse(BarVisibility.suggestionsDisplayed(zoneWithText(), clipboardPanelOpen = true, wordSuggestionsShown = true))
        assertFalse(BarVisibility.suggestionsDisplayed(SuggestionZoneState(), clipboardPanelOpen = false, wordSuggestionsShown = true))
        assertFalse(BarVisibility.suggestionsDisplayed(zoneWithText(), clipboardPanelOpen = false, wordSuggestionsShown = false))
    }

    @Test
    fun `les actions IA passent en pilules seulement quand le menu robot est ouvert`() {
        val typing = zoneWithText()
        assertFalse(BarVisibility.labeledActions(typing, false)) // suggestions de mots : icônes seules
        typing.toggleActionsMenu()
        assertTrue(BarVisibility.labeledActions(typing, false)) // menu robot ouvert : pilules
        assertFalse(BarVisibility.labeledActions(typing, true)) // panneau Smart Clipboard ouvert
        assertFalse(BarVisibility.labeledActions(SuggestionZoneState(), false)) // champ vide : icônes seules
    }

    @Test
    fun `panneau Smart Clipboard ouvert cache le menu de droite, Vocal reste visible`() {
        val visible = BarVisibility.actionButtons(VoiceBarState.IDLE, CorrectionBarState.IDLE, zoneWithText(), true)
        assertFalse(visible.actionsMenu)
        assertTrue(visible.voice)
    }

    @Test
    fun `zone en saisie montre les mots, le menu de gauche et pas la roue crantee`() {
        val visible = BarVisibility.zoneContent(zoneWithText(), false)
        assertTrue(visible.words)
        assertFalse(visible.clipboardButton)
        assertFalse(visible.pasteChip)
        assertFalse(visible.inline)
        assertTrue(visible.menuButton)
        assertFalse(visible.settingsButton)
    }

    @Test
    fun `menu de gauche ouvert pendant la saisie montre le bouton Smart Clipboard et la roue crantee`() {
        val visible = BarVisibility.zoneContent(zoneWithText().apply { toggleMenu() }, false)
        assertTrue(visible.clipboardButton)
        assertFalse(visible.words)
        assertTrue(visible.menuButton)
        assertTrue(visible.settingsButton)
    }

    @Test
    fun `zone avant saisie montre le bouton Smart Clipboard et la roue crantee`() {
        val visible = BarVisibility.zoneContent(SuggestionZoneState(), false)
        assertTrue(visible.clipboardButton)
        assertFalse(visible.words)
        assertFalse(visible.menuButton)
        assertTrue(visible.settingsButton)
    }

    @Test
    fun `puce de collage sur champ vide et suggestions en ligne prennent la place de la bande de mots`() {
        val paste = SuggestionZoneState().apply { setPasteAvailable(true) }
        assertTrue(BarVisibility.zoneContent(paste, false).pasteChip)
        val typing = zoneWithText().apply { setPasteAvailable(true) }
        assertFalse(BarVisibility.zoneContent(typing, false).pasteChip) // pendant la saisie, les mots gardent la place
        assertTrue(BarVisibility.zoneContent(typing, false).words)

        val inline = zoneWithText().apply { setInlineAvailable(true) }
        val inlineVisible = BarVisibility.zoneContent(inline, false)
        assertTrue(inlineVisible.inline)
        assertFalse(inlineVisible.words)
    }

    @Test
    fun `menu de droite ouvert vide la zone`() {
        val state = zoneWithText().apply { toggleActionsMenu() }
        val visible = BarVisibility.zoneContent(state, false)
        assertFalse(visible.words)
        assertFalse(visible.clipboardButton)
        assertFalse(visible.pasteChip)
        assertFalse(visible.inline)
    }

    @Test
    fun `panneau Smart Clipboard ouvert montre seulement le bouton actif et cache menus et roue crantee`() {
        val visible = BarVisibility.zoneContent(zoneWithText().apply { setPasteAvailable(true) }, true)
        assertTrue(visible.clipboardButton)
        assertFalse(visible.words)
        assertFalse(visible.pasteChip)
        assertFalse(visible.menuButton)
        assertFalse(visible.settingsButton)
    }

    @Test
    fun `apparence de Corriger selon l etat`() {
        val undo = BarVisibility.correctAppearance(CorrectionBarState.UNDO)!!
        assertEquals(R.string.correction_button_undo, undo.label)
        assertTrue(undo.enabled)
        assertEquals(1f, undo.alpha)
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
            val busy = BarVisibility.correctAppearance(state)!!
            assertFalse(busy.enabled)
            assertEquals(0.6f, busy.alpha)
        }
        for (state in listOf(CorrectionBarState.LOADING, CorrectionBarState.CORRECTING, CorrectionBarState.CANCELLING)) {
            assertTrue(BarVisibility.correctAppearance(state)!!.loading, "une roue remplace l'icône pendant $state")
        }
        for (state in listOf(CorrectionBarState.IDLE, CorrectionBarState.UNDO)) {
            assertFalse(BarVisibility.correctAppearance(state)!!.loading)
        }
    }

    @Test
    fun `apparence de Vocal selon l etat, rouge seulement pendant l ecoute`() {
        val recording = BarVisibility.voiceAppearance(VoiceBarState.RECORDING)
        assertEquals(R.color.action_danger, recording.background)
        assertEquals(1f, recording.alpha)
        assertEquals(R.string.voice_button_recording, recording.label)
        for (state in listOf(VoiceBarState.IDLE, VoiceBarState.LOADING, VoiceBarState.TRANSCRIBING)) {
            assertEquals(R.color.surface_button, BarVisibility.voiceAppearance(state).background)
        }
        // Seul le chargement du modèle remplace l'icône micro par une roue animée.
        assertEquals(true, BarVisibility.voiceAppearance(VoiceBarState.LOADING).loading)
        listOf(VoiceBarState.IDLE, VoiceBarState.RECORDING, VoiceBarState.TRANSCRIBING).forEach { state ->
            assertEquals(false, BarVisibility.voiceAppearance(state).loading)
        }
        assertEquals(1f, BarVisibility.voiceAppearance(VoiceBarState.IDLE).alpha)
        assertEquals(0.6f, BarVisibility.voiceAppearance(VoiceBarState.LOADING).alpha)
        assertEquals(0.6f, BarVisibility.voiceAppearance(VoiceBarState.TRANSCRIBING).alpha)
    }
}
