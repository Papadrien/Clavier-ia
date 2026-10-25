package fr.junade.taipo.emoji

/**
 * Story 1.15 : un emoji peut s'écrire sur plusieurs caractères UTF-16 et plusieurs points de code
 * (paire de substitution, sélecteur de variation, teinte de peau, séquence ZWJ, drapeau...). La
 * touche retour arrière doit le supprimer en entier, sans laisser une moitié de caractère.
 * Logique pure (sans Android), testée en JVM.
 */
object EmojiText {

    private const val ZWJ = 0x200D
    private const val VS15 = 0xFE0E
    private const val VS16 = 0xFE0F
    private const val KEYCAP = 0x20E3

    private fun isSuffix(cp: Int): Boolean =
        cp == VS15 || cp == VS16 || cp == KEYCAP || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    /**
     * Vrai pour un point de code qui peut être la base d'un emoji (plans emoji, flèches, symboles
     * divers, dingbats, et quelques symboles isolés). Sert à ne joindre par ZWJ que des emojis :
     * le ZWJ sert aussi dans des écritures non emoji (devanagari, etc.), où chaque lettre doit
     * rester supprimable une par une.
     */
    private fun isEmojiBase(cp: Int): Boolean =
        cp >= 0x1F000 || cp in 0x2190..0x2BFF || cp == 0xA9 || cp == 0xAE || cp == 0x203C || cp == 0x2049 ||
            cp == 0x2122 || cp == 0x2139 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    /** Début du point de code qui se termine juste avant [endExclusive] (> 0). */
    private fun previousStart(text: CharSequence, endExclusive: Int): Int =
        if (endExclusive >= 2 &&
            Character.isLowSurrogate(text[endExclusive - 1]) &&
            Character.isHighSurrogate(text[endExclusive - 2])
        ) {
            endExclusive - 2
        } else {
            endExclusive - 1
        }

    /**
     * Longueur, en caractères UTF-16, du dernier « caractère » de [text] : 1 pour une lettre, 2 pour
     * un emoji simple, davantage pour une séquence (drapeau, famille, touche...). 0 si [text] est vide.
     */
    fun lastClusterLength(text: CharSequence): Int {
        if (text.isEmpty()) return 0
        return text.length - clusterStart(text, text.length)
    }

    private fun clusterStart(text: CharSequence, endExclusive: Int): Int {
        var start = elementStart(text, endExclusive)
        // Séquence ZWJ : « élément ZWJ élément ZWJ ... », uniquement entre éléments emoji.
        while (start > 0) {
            val zwj = previousStart(text, start)
            if (Character.codePointAt(text, zwj) != ZWJ || zwj == 0) break
            val previous = elementStart(text, zwj)
            if (!isEmojiBase(Character.codePointAt(text, previous)) ||
                !isEmojiBase(Character.codePointAt(text, start))
            ) {
                break
            }
            start = previous
        }
        return start
    }

    /** Début de l'élément (base + suffixes) qui se termine en [endExclusive]. */
    private fun elementStart(text: CharSequence, endExclusive: Int): Int {
        var pos = endExclusive
        while (pos > 0) {
            val s = previousStart(text, pos)
            if (isSuffix(Character.codePointAt(text, s))) pos = s else break
        }
        if (pos == 0) return 0
        var start = previousStart(text, pos)
        if (isRegionalIndicator(Character.codePointAt(text, start))) {
            // Drapeau : les indicateurs régionaux vont par paires, comptées depuis le début de la suite.
            var count = 1
            var p = start
            while (p > 0) {
                val s = previousStart(text, p)
                if (isRegionalIndicator(Character.codePointAt(text, s))) {
                    count++
                    p = s
                } else {
                    break
                }
            }
            if (count % 2 == 0) start = previousStart(text, start)
        }
        return start
    }
}
