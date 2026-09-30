package fr.junade.taipo.clipboard

/**
 * Story 2.2 : aperçu, sur une ligne, du texte copié, affiché dans la puce de collage de la barre.
 *
 * - Les retours à la ligne, tabulations et suites d'espaces (insécables comprises) deviennent une
 *   seule espace ; les espaces de début et de fin disparaissent.
 * - Au-delà de [DEFAULT_MAX_CODE_POINTS] points de code, l'aperçu est coupé et terminé par « … ».
 *   La coupe se fait sur un point de code (jamais au milieu d'une paire de substitution, donc
 *   un emoji n'est pas tronqué en deux moitiés invalides).
 * - Un contenu sensible est remplacé par [MASK] : le collage reste possible, mais le texte n'est
 *   jamais montré à l'écran.
 *
 * Logique pure (sans Android), testée en JVM.
 */
object ClipboardPreview {

    const val MASK = "\u2022\u2022\u2022\u2022\u2022\u2022"
    const val ELLIPSIS = "\u2026"
    const val DEFAULT_MAX_CODE_POINTS = 40

    /** Story 2.5 : longueur maximale du texte d'une carte du panneau (qui l'affiche sur 3 lignes). */
    const val CARD_MAX_CODE_POINTS = 200

    /** Texte [text] ramené à une ligne, coupé à [maxCodePoints] points de code (suivis de « … » si coupé). */
    fun oneLine(text: String, maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS): String {
        val collapsed = collapseWhitespace(text, stopAfterCodePoints = maxCodePoints + 1)
        if (collapsed.codePointCount(0, collapsed.length) <= maxCodePoints) return collapsed
        val end = collapsed.offsetByCodePoints(0, maxCodePoints)
        return collapsed.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** Ce que montre la puce : l'aperçu sur une ligne, ou [MASK] pour un contenu sensible. */
    fun forDisplay(text: String, sensitive: Boolean, maxCodePoints: Int = DEFAULT_MAX_CODE_POINTS): String =
        if (sensitive) MASK else oneLine(text, maxCodePoints)

    /** Les espaces regroupées ; s'arrête dès [stopAfterCodePoints] points de code (inutile de parcourir un très gros texte). */
    /**
     * Texte d'une carte du panneau : retours à la ligne conservés (la carte les affiche), coupé à
     * [maxCodePoints] points de code et terminé par « … » ; [MASK] si le contenu est sensible.
     */
    fun forCard(text: String, sensitive: Boolean, maxCodePoints: Int = CARD_MAX_CODE_POINTS): String {
        if (sensitive) return MASK
        val trimmed = text.trim()
        if (trimmed.length <= maxCodePoints || trimmed.codePointCount(0, trimmed.length) <= maxCodePoints) return trimmed
        val end = trimmed.offsetByCodePoints(0, maxCodePoints)
        return trimmed.substring(0, end).trimEnd() + ELLIPSIS
    }

    private fun collapseWhitespace(text: String, stopAfterCodePoints: Int): String {
        val out = StringBuilder(minOf(text.length, 256))
        var pendingSpace = false
        var count = 0
        var index = 0
        while (index < text.length && count < stopAfterCodePoints) {
            val cp = text.codePointAt(index)
            index += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) {
                    out.append(' ')
                    count++
                }
                pendingSpace = false
                out.appendCodePoint(cp)
                count++
            }
        }
        return out.toString()
    }
}
