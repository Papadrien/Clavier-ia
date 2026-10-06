package fr.junade.taipo

import fr.junade.taipo.ai.VoiceField

/**
 * Dictée dans le prompt : [VoiceField] posé sur [PromptInputBuffer], pour que la transcription (partielle
 * puis finale) s'insère dans la zone de saisie du prompt et non dans le champ de l'application.
 *
 * - [isActive] : le mode prompt est actif. Une fois quitté (croix, changement de champ), le tampon est
 *   vidé et plus rien ne doit y être écrit : les lectures renvoient null (la synchronisation attend) et
 *   les écritures sont ignorées, même si une hypothèse du décodeur arrive encore en retard.
 * - [onChanged] : le texte ou le curseur a changé, la pilule de saisie doit être redessinée. Appelé une
 *   seule fois par lot (voir [beginBatchEdit]), pas à chaque suppression ou insertion.
 *
 * Logique pure (sans Android), testée en JVM.
 */
class PromptBufferVoiceField(
    private val buffer: PromptInputBuffer,
    private val isActive: () -> Boolean,
    private val onChanged: () -> Unit,
) : VoiceField {

    private var batchDepth = 0
    private var dirty = false

    override fun textBeforeCursor(length: Int): String? {
        if (!isActive()) return null
        return buffer.textBeforeCursor.takeLast(length.coerceAtLeast(0))
    }

    override fun deleteBeforeCursor(length: Int) {
        if (!isActive()) return
        if (buffer.deleteBefore(length) > 0) changed()
    }

    override fun commit(text: String) {
        if (!isActive() || text.isEmpty()) return
        buffer.insert(text)
        changed()
    }

    override fun beginBatchEdit() {
        batchDepth++
    }

    override fun endBatchEdit() {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0 && dirty) {
            dirty = false
            onChanged()
        }
    }

    private fun changed() {
        if (batchDepth > 0) dirty = true else onChanged()
    }
}
