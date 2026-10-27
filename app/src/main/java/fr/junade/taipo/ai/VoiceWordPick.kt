package fr.junade.taipo.ai

/**
 * Mot touché dans le champ pendant une dictée, pour le corriger : logique pure (JVM), sans accès au champ.
 *
 * Pendant l'écoute, la dictée écrit au curseur. Un toucher de l'utilisateur dans le texte déplace ce curseur : tant qu'il y
 * reste ([active]), la bande de suggestions propose des alternatives au mot touché. Le point où la dictée en était est retenu
 * ([resumePoint]) : une fois le mot remplacé, le curseur y revient (décalé de la correction), pour que la suite de la dictée
 * s'écrive au bout du texte et pas au milieu.
 *
 * Le post-traitement de la dictée n'est pas concerné : ce suivi ne lit ni ne modifie le texte dicté.
 *
 * @param now horloge en millisecondes (injectée pour les tests).
 */
class VoiceWordPick(private val now: () -> Long = System::currentTimeMillis) {

    /** Vrai tant qu'un mot touché attend sa correction : la bande de suggestions montre ses alternatives. */
    var active = false
        private set

    private var resumePoint = -1
    private var ignoreUntil = 0L

    /** Début ou fin d'une écoute : plus rien à suivre. */
    fun reset() {
        active = false
        resumePoint = -1
        ignoreUntil = 0L
    }

    /**
     * Le curseur du champ vient de bouger pendant l'écoute (de [oldEnd] à [newEnd]).
     *
     * @param collapsed pas de sélection (les sélections ne sont pas des mots touchés).
     * @param atDictationEnd le texte dicté se trouve juste avant le curseur : c'est l'écho d'une insertion de la dictée, ou
     *   l'utilisateur est revenu à la fin du texte dicté ; la dictée a la main.
     * @param typedRecently une touche du clavier vient d'être pressée : le curseur suit la frappe, il n'a pas été posé.
     */
    fun onCursorMoved(oldEnd: Int, newEnd: Int, collapsed: Boolean, atDictationEnd: Boolean, typedRecently: Boolean) {
        if (now() < ignoreUntil) return
        if (!collapsed || newEnd == oldEnd) return
        if (atDictationEnd) {
            active = false
            resumePoint = -1
            return
        }
        if (typedRecently) return
        // Premier toucher : le curseur d'avant est le point où la dictée écrivait. Les touchers suivants le gardent.
        if (!active) resumePoint = oldEnd
        active = true
    }

    /**
     * Le mot touché vient d'être remplacé : [cursorBefore] est la position du curseur avant le remplacement, [lengthDelta] la
     * variation de longueur du texte. Renvoie la position où remettre le curseur pour que la dictée reprenne où elle en était,
     * ou null s'il n'y a rien à remettre. Les mises à jour de sélection qui suivent (écho du remplacement) sont ignorées un instant.
     */
    fun onWordReplaced(cursorBefore: Int, lengthDelta: Int): Int? {
        val resume = resumePoint
        active = false
        resumePoint = -1
        ignoreUntil = now() + IGNORE_AFTER_REPLACE_MS
        if (resume < 0) return null
        // Un mot placé avant le point de reprise le décale ; un mot situé après (texte déjà présent) ne change rien.
        return if (cursorBefore in 0..resume) resume + lengthDelta else resume
    }

    private companion object {
        /** Durée pendant laquelle l'écho de nos propres modifications du champ n'est pas pris pour un toucher. */
        const val IGNORE_AFTER_REPLACE_MS = 400L
    }
}
