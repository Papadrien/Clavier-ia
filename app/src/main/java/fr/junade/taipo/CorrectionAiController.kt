package fr.junade.taipo

import android.content.Context
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import fr.junade.taipo.ai.ChangedRange
import fr.junade.taipo.ai.CorrectedSentenceMemory
import fr.junade.taipo.ai.CorrectionDiff
import fr.junade.taipo.ai.CorrectionEngine
import fr.junade.taipo.ai.CorrectionPlanner
import fr.junade.taipo.ai.CorrectionSafeguard
import fr.junade.taipo.ai.InferenceFailureClassifier
import fr.junade.taipo.ai.messageRes
import fr.junade.taipo.ai.SpanLocator
import fr.junade.taipo.ai.TextBlock
import fr.junade.taipo.model.AiModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Correction IA (épopée 3) : bouton Corriger, correction du texte sélectionné ou des phrases pas encore
 * corrigées du champ, remplacement dans le champ, surlignage temporaire des mots modifiés et retrait de ce
 * surlignage. Extrait de `TaipoIme` au lot 2.3 de la revue de code, sans changement de comportement.
 *
 * Possède aussi la sélection connue du champ ([lastSelectionStart] / [lastSelectionEnd], mise à jour par
 * [onSelectionUpdated]) : elle sert à retirer le surlignage et à restaurer la sélection de l'utilisateur.
 *
 * Tout ce qui touche à l'IME (champ de saisie, barre, messages, suggestions, autocorrection du dictionnaire)
 * passe par [Host]. Toutes les méthodes sont à appeler depuis le thread principal.
 */
class CorrectionAiController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val correctionEngine: () -> CorrectionEngine,
    private val host: Host,
) {

    /** Ce que la correction demande à l'IME. */
    interface Host {
        fun showMessage(message: String)

        fun inputConnection(): InputConnection?

        /** Une génération (mode prompt) est en cours : le moteur est pris, la correction est refusée. */
        fun generationBusy(): Boolean

        /** Modèle de correction actif, ou null si aucun n'est choisi. */
        fun activeModel(): AiModel?

        /** Mots du dictionnaire personnel présents dans [text] : signalés à l'IA pour qu'elle ne les corrige pas. */
        fun protectedWords(text: String): List<String>

        fun setCorrectionBarState(state: CorrectionBarState)

        fun clearSuggestions()

        /** Met à jour la barre (bouton Corriger visible ou non, puce de collage). */
        fun updateCorrectionBarVisibility()

        /** Relance la majuscule automatique et la suggestion d'emoji, coupée pendant la correction. */
        fun syncAutoCapitalization()

        /** L'autocorrection du dictionnaire ne peut plus être annulée à la suppression. */
        fun clearPendingAutocorrection()
    }

    /**
     * Annulation de la dernière correction : [currentText] est ce que le champ contient maintenant à la place de
     * [previousText] (texte d'avant la correction). [absStart] : offset absolu probable de [currentText], -1 si inconnu.
     */
    private class UndoRecord(val currentText: String, val previousText: String, val absStart: Int)

    private var undoRecord: UndoRecord? = null

    /** Vrai juste après une correction appliquée : le bouton Corriger devient Annuler. */
    val canUndo: Boolean get() = undoRecord != null

    /**
     * L'annulation n'est plus proposée (saisie, action de la barre du haut, autre champ). [notify] : met la barre à jour
     * (le bouton redevient Corriger) ; faux quand l'appelant le fait lui-même ou que le champ est quitté.
     */
    fun dropUndo(notify: Boolean = true) {
        if (undoRecord == null) return
        undoRecord = null
        if (notify) host.updateCorrectionBarVisibility()
    }

    /** Une correction est en cours (modèle au travail) : lu par l'IME pour suspendre suggestions, collage, etc. */
    var inProgress = false
        private set

    // Phrases déjà corrigées (mémoire vive uniquement) : une phrase identique n'est pas renvoyée à l'IA.
    private val correctedSentences = CorrectedSentenceMemory()

    // État du surlignage temporaire après correction (décision 3.2). Seuls les mots corrigés
    // sont surlignés. Le surlignage disparaît dès la première action utilisateur ailleurs :
    // touche de ce clavier, bouton Corriger/Vocal, ou déplacement du curseur dans le champ.
    // Pour le retirer, on remplace la zone [premier mot corrigé → dernier mot corrigé] par le
    // même texte sans surlignage (voir removeCorrectionHighlight).
    private var correctionHighlightActive = false

    /** Texte brut (sans surlignage) de la zone à re-saisir : du premier au dernier mot corrigé. */
    private var correctionHighlightText = ""

    /**
     * Offset absolu, dans le champ, de la fin de la zone surlignée ; -1 si l'app ne permet pas
     * de le connaître (dans ce cas la zone re-saisie est tout le texte, le curseur est à la fin,
     * et le surlignage n'est retiré qu'à la frappe d'une touche).
     */
    private var correctionZoneEnd = -1

    /** Sélection (offsets absolus) laissée par la correction : si elle bouge, l'utilisateur a agi ailleurs. */
    private var correctionSelectionStart = -1
    private var correctionSelectionEnd = -1

    /** Dernière sélection (offsets absolus) connue, mise à jour par [onSelectionUpdated]. */
    var lastSelectionStart = -1
        private set
    var lastSelectionEnd = -1
        private set

    /** Les mises à jour de sélection reçues avant cet instant sont l'écho de nos propres modifications. */
    private var ignoreSelectionUpdatesUntil = 0L

    /** À appeler après une modification du champ par le clavier lui-même : sa mise à jour de sélection n'est pas une action de l'utilisateur. */
    fun markSelfEdit() {
        ignoreSelectionUpdatesUntil = SystemClock.uptimeMillis() + SELF_EDIT_GRACE_MS
    }

    /** Vrai si une mise à jour de sélection reçue maintenant est l'écho d'une modification faite par le clavier lui-même. */
    fun isSelfEditEcho(): Boolean = SystemClock.uptimeMillis() < ignoreSelectionUpdatesUntil

    /**
     * Le champ a signalé une nouvelle sélection. Si le curseur a bougé ailleurs que là où la correction
     * l'a laissé (tap, texte ajouté par une autre fonction), le surlignage disparaît.
     */
    fun onSelectionUpdated(newSelStart: Int, newSelEnd: Int) {
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
        if (correctionHighlightActive && SystemClock.uptimeMillis() >= ignoreSelectionUpdatesUntil) {
            val selectionUnchanged = correctionZoneEnd >= 0 &&
                newSelStart == correctionSelectionStart && newSelEnd == correctionSelectionEnd
            if (!selectionUnchanged) {
                // Le curseur a bougé (tap ailleurs, texte ajouté par une autre fonction) : le surlignage disparaît.
                if (correctionZoneEnd >= 0) removeCorrectionHighlight() else correctionHighlightActive = false
            }
        }
    }

    fun onCorrectClicked() {
        if (inProgress) return
        if (host.generationBusy()) { // le moteur est pris par la génération
            host.showMessage(context.getString(R.string.generation_busy))
            return
        }
        clearHighlightIfNeeded()
        val ic = host.inputConnection() ?: return

        // Texte sélectionné : seul ce texte est corrigé (sans le principe des phrases déjà corrigées).
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) {
            correctSelection(ic, selected)
            return
        }

        val captured = captureFieldText(ic) ?: return
        if (captured.text.isBlank()) return

        val model = host.activeModel()
        if (model == null) {
            ModelScreenRedirect.open(context)
            return
        }

        // On ne renvoie à l'IA que les phrases pas encore corrigées (nouvelles ou modifiées).
        val blocks = CorrectionPlanner.blocksToCorrect(captured.text, correctedSentences::contains)
        if (blocks.isEmpty()) {
            host.showMessage(context.getString(R.string.correction_nothing_new))
            return
        }

        launchCorrection {
            val correctedBlocks = blocks.map { block ->
                val original = captured.text.substring(block.start, block.endExclusive)
                val corrected = correctionEngine().correct(model, original, host.protectedWords(original)) {
                    host.setCorrectionBarState(CorrectionBarState.LOADING)
                }
                // null : réponse vide ou tronquée, le texte d'origine est alors conservé.
                CorrectionSafeguard.accept(original, corrected)
            }
            host.setCorrectionBarState(CorrectionBarState.CORRECTING)
            applyCorrection(ic, captured, blocks, correctedBlocks)
            if (correctedBlocks.any { it == null }) {
                host.showMessage(context.getString(R.string.correction_partial))
            }
        }
    }

    /** Corrige uniquement le texte sélectionné ; les espaces en bordure de sélection sont conservés. */
    private fun correctSelection(ic: InputConnection, selected: String) {
        if (selected.isBlank()) return
        val model = host.activeModel()
        if (model == null) {
            ModelScreenRedirect.open(context)
            return
        }
        val leading = selected.length - selected.trimStart().length
        val trailing = selected.length - selected.trimEnd().length
        val core = selected.substring(leading, selected.length - trailing)

        // Offset absolu du début de la sélection, s'il est connu (pour pouvoir retirer le surlignage plus tard).
        val captured = captureFieldText(ic)
        val absSelectionStart = if (captured != null && captured.startOffset >= 0) {
            captured.startOffset + captured.beforeCursor
        } else {
            -1
        }

        launchCorrection {
            val corrected = correctByParagraph(model, core)
            host.setCorrectionBarState(CorrectionBarState.CORRECTING)
            applySelectionCorrection(ic, selected, leading, trailing, core, corrected, absSelectionStart)
        }
    }

    /**
     * Corrige [text] paragraphe par paragraphe (un appel au modèle par ligne non vide) en gardant
     * les retours à la ligne d'origine. Les petits modèles tronquent souvent leur réponse au
     * premier retour à la ligne : corrigé d'un bloc, un texte de deux paragraphes perdait le second.
     */
    private suspend fun correctByParagraph(model: AiModel, text: String): String {
        val result = StringBuilder()
        var cursor = 0
        for (separator in Regex("\n+").findAll(text)) {
            result.append(correctParagraph(model, text.substring(cursor, separator.range.first)))
            result.append(separator.value)
            cursor = separator.range.last + 1
        }
        result.append(correctParagraph(model, text.substring(cursor)))
        return result.toString()
    }

    /** Corrige un paragraphe (espaces en bordure conservés) ; réponse vide ou tronquée : paragraphe inchangé. */
    private suspend fun correctParagraph(model: AiModel, paragraph: String): String {
        val core = paragraph.trim()
        if (core.isEmpty()) return paragraph
        val leading = paragraph.length - paragraph.trimStart().length
        val trailing = paragraph.length - paragraph.trimEnd().length
        val corrected = correctionEngine().correct(model, core, host.protectedWords(core)) {
            host.setCorrectionBarState(CorrectionBarState.LOADING)
        }
        val accepted = CorrectionSafeguard.accept(core, corrected)
        if (accepted == null) AppLog.w(TAG, "correction refusée par le garde-fou (réponse vide ou tronquée) : texte conservé")
        return paragraph.substring(0, leading) + (accepted ?: core) + paragraph.substring(paragraph.length - trailing)
    }

    private fun applySelectionCorrection(
        ic: InputConnection,
        selected: String,
        leading: Int,
        trailing: Int,
        core: String,
        corrected: String,
        absSelectionStart: Int,
    ) {
        if (corrected == core) {
            clearHighlightState()
            host.showMessage(context.getString(R.string.correction_no_change))
            return
        }
        // La sélection a pu changer pendant que le modèle travaillait : on ne remplace que si elle est identique. La
        // saisie a pu être redémarrée entre-temps : on lit la connexion courante, pas celle captée au clic.
        val liveIc = host.inputConnection() ?: ic
        if (liveIc.getSelectedText(0)?.toString() != selected) {
            AppLog.w(TAG, "correction annulée : la sélection a changé")
            host.showMessage(context.getString(R.string.correction_text_changed))
            return
        }

        val ranges = CorrectionDiff.changedRanges(core, corrected).map {
            ChangedRange(it.start + leading, it.endExclusive + leading)
        }
        val newText = selected.substring(0, leading) + corrected + selected.substring(selected.length - trailing)

        clearHighlightState()
        markSelfEdit()
        liveIc.commitText(highlighted(newText, ranges), 1) // remplace la sélection ; le curseur se place après le texte corrigé
        undoRecord = UndoRecord(currentText = newText, previousText = selected, absStart = absSelectionStart)
        if (ranges.isEmpty()) return // uniquement des suppressions : rien à surligner

        correctionHighlightActive = true
        if (absSelectionStart >= 0) {
            correctionHighlightText = newText.substring(ranges.first().start, ranges.last().endExclusive)
            correctionZoneEnd = absSelectionStart + ranges.last().endExclusive
            val cursor = absSelectionStart + newText.length
            correctionSelectionStart = cursor
            correctionSelectionEnd = cursor
            lastSelectionStart = cursor
            lastSelectionEnd = cursor
        } else {
            // Position inconnue : le curseur est juste après le texte corrigé, qu'on re-saisira en entier.
            correctionHighlightText = newText
            correctionZoneEnd = -1
        }
    }

    /** Lance une correction en arrière-plan avec indicateur de chargement et gestion d'erreur communs. */
    private fun launchCorrection(work: suspend () -> Unit) {
        inProgress = true
        host.clearSuggestions()
        host.setCorrectionBarState(CorrectionBarState.LOADING)
        scope.launch {
            try {
                work()
            } catch (t: Throwable) {
                val cause = InferenceFailureClassifier.classify(t)
                AppLog.e(TAG, "Échec de la correction IA ($cause)", t) // détail technique : debug uniquement
                host.showMessage(context.getString(cause.messageRes()))
            } finally {
                inProgress = false
                host.updateCorrectionBarVisibility()
                host.syncAutoCapitalization() // relance aussi la suggestion d'emoji, coupée pendant la correction
            }
        }
    }

    /** Texte capturé et sa longueur avant/après le curseur, pour pouvoir le remplacer précisément. */
    data class CapturedText(
        val text: String,
        val beforeCursor: Int,
        val afterCursor: Int,
        /** Offset absolu du début du texte capturé dans le champ, -1 si inconnu. */
        val startOffset: Int = -1,
    )

    /**
     * Capture le texte accessible du champ (décision 3.1 : tout le texte du
     * champ accessible via InputConnection, ou le maximum accessible si
     * l'app ne donne pas accès à la totalité).
     */
    fun captureFieldText(ic: InputConnection): CapturedText? {
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val extractedText = extracted?.text
        if (extractedText != null) {
            val text = extractedText.toString()
            val selStart = extracted.selectionStart.coerceIn(0, text.length)
            val selEnd = extracted.selectionEnd.coerceIn(0, text.length)
            return CapturedText(text, beforeCursor = selStart, afterCursor = text.length - selEnd, startOffset = extracted.startOffset)
        }
        // Repli si l'app ne fournit pas d'ExtractedText.
        val before = ic.getTextBeforeCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_ACCESSIBLE_CHARS, 0)?.toString().orEmpty()
        if (before.isEmpty() && after.isEmpty()) return null
        return CapturedText(before + after, beforeCursor = before.length, afterCursor = after.length)
    }

    /**
     * Remplace dans le champ uniquement la zone allant de la première à la dernière phrase
     * corrigée ; les phrases déjà corrigées situées avant, après ou entre les zones ne sont pas
     * touchées. Seuls les mots modifiés par l'IA sont surlignés.
     */
    private fun applyCorrection(
        ic: InputConnection,
        captured: CapturedText,
        blocks: List<TextBlock>,
        correctedBlocks: List<String?>,
    ) {
        val spanStart = blocks.first().start
        val spanEnd = blocks.last().endExclusive
        val oldSpan = captured.text.substring(spanStart, spanEnd)

        // Reconstitue la zone : phrases corrigées + phrases déjà corrigées intercalées.
        val replacement = StringBuilder()
        val ranges = mutableListOf<ChangedRange>()
        var cursor = spanStart
        blocks.forEachIndexed { index, block ->
            replacement.append(captured.text, cursor, block.start)
            val original = captured.text.substring(block.start, block.endExclusive)
            val corrected = correctedBlocks[index] ?: original
            val offset = replacement.length
            CorrectionDiff.changedRanges(original, corrected).forEach {
                ranges += ChangedRange(it.start + offset, it.endExclusive + offset)
            }
            replacement.append(corrected)
            cursor = block.endExclusive
        }
        val newSpan = replacement.toString()

        // Une zone rejetée par le garde-fou n'est pas mémorisée : elle sera renvoyée au modèle à la prochaine correction.
        fun rememberCorrected() = correctedBlocks.filterNotNull().forEach { correctedSentences.remember(it) }

        if (newSpan == oldSpan) {
            rememberCorrected() // phrases désormais corrigées, même sans changement
            clearHighlightState()
            return
        }

        clearHighlightState()

        // Pendant que le modèle travaillait (parfois plus de 10 s), l'application a pu redémarrer la saisie : la connexion
        // captée au clic est alors périmée, et la comparer au texte donnait un faux « le texte a changé ». On repart de la
        // connexion courante et d'une nouvelle capture, où l'on retrouve la zone par son texte (même si l'utilisateur a tapé
        // avant ou après, ou déplacé le curseur). Seul un texte réellement modifié annule la correction.
        val liveIc = host.inputConnection() ?: ic
        val fresh = captureFieldText(liveIc)
        val expectedIndex = if (fresh != null && captured.startOffset >= 0 && fresh.startOffset >= 0) {
            captured.startOffset + spanStart - fresh.startOffset
        } else {
            spanStart
        }
        val index = if (fresh != null) SpanLocator.find(fresh.text, oldSpan, expectedIndex) else -1
        if (fresh == null || index < 0) {
            AppLog.w(TAG, "correction annulée : zone introuvable dans le champ (capture=${fresh != null})")
            host.showMessage(context.getString(R.string.correction_text_changed))
            return
        }

        markSelfEdit()
        val applied = if (fresh.startOffset >= 0) {
            replaceKnownSpan(liveIc, fresh, index, index + oldSpan.length, oldSpan, newSpan, ranges)
        } else {
            replaceWholeText(liveIc, fresh, index, index + oldSpan.length, newSpan, ranges)
        }
        if (applied) {
            rememberCorrected()
            undoRecord = UndoRecord(
                currentText = newSpan,
                previousText = oldSpan,
                absStart = if (fresh.startOffset >= 0) fresh.startOffset + index else -1,
            )
        }
    }

    /** Couleur d'accent du clavier avec transparence. */
    private fun highlightColor(): Int = (context.themeColor(R.color.accent) and 0x00FFFFFF) or HIGHLIGHT_ALPHA

    private fun highlighted(text: String, ranges: List<ChangedRange>, shift: Int = 0) = SpannableString(text).apply {
        ranges.forEach {
            setSpan(
                BackgroundColorSpan(highlightColor()),
                it.start + shift,
                it.endExclusive + shift,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** Position exacte connue : remplace uniquement la zone, puis restaure la sélection de l'utilisateur. */
    private fun replaceKnownSpan(
        ic: InputConnection,
        captured: CapturedText,
        spanStart: Int,
        spanEnd: Int,
        oldSpan: String,
        newSpan: String,
        ranges: List<ChangedRange>,
        @androidx.annotation.StringRes failureMessage: Int = R.string.correction_text_changed,
    ): Boolean {
        val absStart = captured.startOffset + spanStart
        val absEnd = captured.startOffset + spanEnd
        val selStart = captured.startOffset + captured.beforeCursor
        val selEnd = captured.startOffset + captured.text.length - captured.afterCursor
        val delta = newSpan.length - oldSpan.length
        fun mapOffset(offset: Int) = when {
            offset >= absEnd -> offset + delta
            offset <= absStart -> offset
            else -> absStart + newSpan.length
        }

        var applied = false
        ic.beginBatchEdit()
        try {
            ic.setSelection(absEnd, absEnd)
            // Le texte a pu changer pendant que le modèle travaillait : on ne remplace que s'il est identique.
            if (ic.getTextBeforeCursor(oldSpan.length, 0)?.toString() == oldSpan) {
                ic.deleteSurroundingText(oldSpan.length, 0)
                ic.commitText(highlighted(newSpan, ranges), 1)
                applied = true
            }
            if (applied) {
                ic.setSelection(mapOffset(selStart), mapOffset(selEnd))
            } else {
                ic.setSelection(selStart, selEnd)
            }
        } finally {
            ic.endBatchEdit()
        }

        if (!applied) {
            AppLog.w(TAG, "correction annulée : le texte de la zone ne correspond plus au curseur")
            host.showMessage(context.getString(failureMessage))
            return false
        }
        if (ranges.isEmpty()) return true // uniquement des suppressions : rien à surligner

        correctionHighlightText = newSpan.substring(ranges.first().start, ranges.last().endExclusive)
        correctionZoneEnd = absStart + ranges.last().endExclusive
        correctionSelectionStart = mapOffset(selStart)
        correctionSelectionEnd = mapOffset(selEnd)
        lastSelectionStart = correctionSelectionStart
        lastSelectionEnd = correctionSelectionEnd
        correctionHighlightActive = true
        return true
    }

    /** Position inconnue (l'app ne fournit pas ExtractedText) : remplace tout le texte accessible autour du curseur. */
    private fun replaceWholeText(
        ic: InputConnection,
        captured: CapturedText,
        spanStart: Int,
        spanEnd: Int,
        newSpan: String,
        ranges: List<ChangedRange>,
        @androidx.annotation.StringRes failureMessage: Int = R.string.correction_text_changed,
    ): Boolean {
        val newFull = captured.text.substring(0, spanStart) + newSpan + captured.text.substring(spanEnd)
        val beforeExpected = captured.text.substring(0, captured.beforeCursor)
        val afterExpected = captured.text.substring(captured.text.length - captured.afterCursor)

        var applied = false
        ic.beginBatchEdit()
        try {
            val before = ic.getTextBeforeCursor(captured.beforeCursor, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(captured.afterCursor, 0)?.toString().orEmpty()
            if (before == beforeExpected && after == afterExpected) {
                ic.deleteSurroundingText(captured.beforeCursor, captured.afterCursor)
                ic.commitText(highlighted(newFull, ranges, shift = spanStart), 1)
                applied = true
            }
        } finally {
            ic.endBatchEdit()
        }

        if (!applied) {
            AppLog.w(TAG, "correction annulée : le texte autour du curseur ne correspond plus")
            host.showMessage(context.getString(failureMessage))
            return false
        }
        if (ranges.isEmpty()) return true

        correctionHighlightText = newFull
        correctionZoneEnd = -1
        correctionHighlightActive = true
        return true
    }

    /**
     * Retire le surlignage de correction : la zone surlignée est remplacée par le même texte
     * sans surlignage, puis la sélection de l'utilisateur est restaurée telle quelle. Si le texte
     * de la zone n'est plus celui attendu (modifié entre-temps), on ne touche à rien pour ne
     * pas risquer de modifier le mauvais texte.
     */
    private fun removeCorrectionHighlight() {
        if (!correctionHighlightActive) return
        val plainText = correctionHighlightText
        val zoneEnd = correctionZoneEnd
        val restoreStart = lastSelectionStart
        val restoreEnd = lastSelectionEnd
        clearHighlightState()

        val ic = host.inputConnection() ?: return
        if (plainText.isEmpty()) return
        markSelfEdit()
        ic.beginBatchEdit()
        try {
            if (zoneEnd >= 0) ic.setSelection(zoneEnd, zoneEnd)
            val beforeCursor = ic.getTextBeforeCursor(plainText.length, 0)?.toString()
            if (beforeCursor == plainText) {
                ic.deleteSurroundingText(plainText.length, 0)
                ic.commitText(plainText, 1)
            }
            if (zoneEnd >= 0 && restoreStart >= 0 && restoreEnd >= 0) {
                ic.setSelection(restoreStart, restoreEnd)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /** Appelée avant toute action de l'utilisateur (touche, Corriger, Vocal) : le surlignage disparaît, et l'annulation aussi. */
    fun clearHighlightIfNeeded() {
        removeCorrectionHighlight()
        dropUndo()
    }

    /** Bouton Annuler : remet, à la place du texte corrigé, le texte d'avant la correction IA. */
    fun onUndoClicked() {
        val record = undoRecord ?: return
        if (inProgress) return
        undoRecord = null
        clearHighlightState()
        val ic = host.inputConnection()
        val fresh = ic?.let { captureFieldText(it) }
        val expectedIndex = if (fresh != null && record.absStart >= 0 && fresh.startOffset >= 0) {
            record.absStart - fresh.startOffset
        } else {
            0
        }
        val index = if (fresh != null) SpanLocator.find(fresh.text, record.currentText, expectedIndex) else -1
        if (ic == null || fresh == null || index < 0) {
            AppLog.w(TAG, "annulation impossible : texte corrigé introuvable dans le champ (capture=${fresh != null})")
            host.showMessage(context.getString(R.string.correction_undo_failed))
            host.updateCorrectionBarVisibility()
            return
        }
        markSelfEdit()
        val end = index + record.currentText.length
        if (fresh.startOffset >= 0) {
            replaceKnownSpan(ic, fresh, index, end, record.currentText, record.previousText, emptyList(), R.string.correction_undo_failed)
        } else {
            replaceWholeText(ic, fresh, index, end, record.previousText, emptyList(), R.string.correction_undo_failed)
        }
        host.updateCorrectionBarVisibility()
    }

    fun clearHighlightState() {
        host.clearPendingAutocorrection()
        correctionHighlightActive = false
        correctionHighlightText = ""
        correctionZoneEnd = -1
        correctionSelectionStart = -1
        correctionSelectionEnd = -1
    }

    private companion object {
        private const val TAG = "CorrectionAiController"
        private const val MAX_ACCESSIBLE_CHARS = 10_000

        /** Transparence (alpha 0x55) appliquée à la couleur d'accent pour le surlignage des mots corrigés. */
        private const val HIGHLIGHT_ALPHA = 0x55000000

        /** Délai pendant lequel les mises à jour de sélection sont considérées comme l'écho de nos propres modifications. */
        private const val SELF_EDIT_GRACE_MS = 500L
    }
}
