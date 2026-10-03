package fr.junade.taipo.ai

/**
 * Accès minimal au champ de saisie dont a besoin la dictée. Implémenté sur `InputConnection` dans
 * l'app (voir `InputConnectionVoiceField`), et par un faux champ dans les tests JVM.
 */
interface VoiceField {
    /** Jusqu'à [length] caractères avant le curseur, ou null si le champ ne les fournit pas. */
    fun textBeforeCursor(length: Int): String?

    fun deleteBeforeCursor(length: Int)

    fun commit(text: String)

    fun beginBatchEdit()

    fun endBatchEdit()
}

/**
 * Logique pure d'insertion progressive de la dictée (lot 2.3 de la revue : extraite de `TaipoIme`
 * sans changement de comportement).
 *
 * L'hypothèse du décodeur couvre toute la session depuis son début, alors que le texte du champ peut
 * avoir été modifié ou le curseur déplacé par l'utilisateur pendant la dictée :
 * - [insertedText] = ce que la dictée a actuellement inséré, juste avant le curseur ;
 * - [frozenLength] = nombre de caractères de l'hypothèse déjà « remis » à l'utilisateur (ils font
 *   désormais partie de son texte et ne sont plus jamais touchés ni réinsérés) ;
 * - [lastHypothesis] = dernière hypothèse effectivement appliquée au champ.
 *
 * @param hasSelection vrai tant qu'une sélection existe dans le champ (la mise à jour attend).
 * @param onTextInserted appelé quand du texte vient d'être inséré (story 2.4 : l'insertion compte
 *   comme une saisie de l'utilisateur).
 */
class VoiceTextSync(
    private val hasSelection: () -> Boolean,
    private val onTextInserted: () -> Unit,
) {
    var insertedText = ""
        private set
    var frozenLength = 0
        private set
    var lastHypothesis = ""
        private set

    /** Début d'une session de dictée, ou fin après retrait/remplacement du texte inséré. */
    fun reset() {
        insertedText = ""
        frozenLength = 0
        lastHypothesis = ""
    }

    /**
     * Remplace, pendant l'écoute, l'insertion précédente par [partial]. Le décodeur en streaming peut
     * réviser des mots déjà « affichés » : on remplace donc en bloc plutôt que de concaténer.
     */
    fun applyPartial(field: VoiceField, partial: String) {
        if (partial == lastHypothesis) return
        sync(field, partial, isFinal = false)
    }

    /**
     * Fin de dictée : remplace la dernière hypothèse insérée par [finalText] (null = simple retrait,
     * cas de l'annulation). Si l'utilisateur a modifié le texte entre-temps, ce qu'il a fait n'est pas
     * écrasé.
     */
    fun finish(field: VoiceField, finalText: String?) {
        sync(field, finalText, isFinal = true)
    }

    /**
     * Met le champ en accord avec l'hypothèse [hypothesis] (null = retrait seul, annulation).
     *
     * Le texte inséré par la dictée n'est supprimé que s'il se trouve toujours exactement avant le
     * curseur. Sinon (curseur déplacé, frappe, suppression ou correction faite par l'utilisateur), ce
     * qui a déjà été inséré lui appartient : on le « fige » et seule la suite de l'hypothèse, au-delà
     * de la partie figée, est insérée à l'endroit où se trouve maintenant le curseur. Ainsi la reprise
     * de la dictée n'efface rien d'imprévu et ne réinsère pas ce que l'utilisateur a supprimé.
     * Tant qu'une sélection existe (l'utilisateur copie ou remplace du texte), la mise à jour attend.
     */
    private fun sync(field: VoiceField, hypothesis: String?, isFinal: Boolean) {
        if (!isFinal && hasSelection()) return

        val inserted = insertedText
        var intact = true
        if (inserted.isNotEmpty()) {
            val before = field.textBeforeCursor(inserted.length) ?: return
            intact = before == inserted
        }
        if (!intact) {
            frozenLength = lastHypothesis.length
            insertedText = ""
        }
        if (hypothesis == null) {
            if (intact && inserted.isNotEmpty()) field.deleteBeforeCursor(inserted.length)
            return
        }

        field.beginBatchEdit()
        try {
            if (intact && inserted.isNotEmpty()) field.deleteBeforeCursor(inserted.length)
            var display = if (hypothesis.length > frozenLength) hypothesis.substring(frozenLength) else ""
            if (frozenLength > 0 && display.startsWith(" ")) {
                // Reprise après une modification : pas d'espace en trop si le texte avant le curseur finit déjà par un blanc.
                val last = field.textBeforeCursor(1)?.lastOrNull()
                if (last == null || last.isWhitespace()) display = display.trimStart(' ')
            }
            if (display.isNotEmpty()) field.commit(display)
            insertedText = display
            lastHypothesis = hypothesis
            if (display.isNotEmpty()) onTextInserted()
        } finally {
            field.endBatchEdit()
        }
    }
}
