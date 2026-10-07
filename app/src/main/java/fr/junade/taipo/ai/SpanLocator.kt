package fr.junade.taipo.ai

/**
 * Retrouve, dans le texte actuel du champ, la zone envoyée au modèle de correction. Pendant que le modèle travaille
 * (parfois plus de 10 s), l'utilisateur peut taper avant ou après la zone, ou la saisie peut être redémarrée par
 * l'application : la position d'origine n'est alors plus fiable, mais le texte de la zone, lui, n'a peut-être pas changé.
 * Logique pure, testable en JVM.
 */
internal object SpanLocator {

    /**
     * Index de [span] dans [text], ou -1 si la zone n'y figure plus (texte réellement modifié). Si [span] est encore à
     * [expectedIndex], c'est lui ; sinon l'occurrence la plus proche de [expectedIndex] (la plus à gauche en cas d'égalité).
     */
    fun find(text: String, span: String, expectedIndex: Int): Int {
        if (span.isEmpty()) return -1
        if (expectedIndex in 0..(text.length - span.length) && text.startsWith(span, expectedIndex)) return expectedIndex
        var best = -1
        var from = 0
        while (true) {
            val index = text.indexOf(span, from)
            if (index < 0) break
            if (best < 0 || kotlin.math.abs(index - expectedIndex) < kotlin.math.abs(best - expectedIndex)) best = index
            from = index + 1
        }
        return best
    }
}
