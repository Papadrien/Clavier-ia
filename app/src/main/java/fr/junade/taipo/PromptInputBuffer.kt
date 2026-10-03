package fr.junade.taipo

import fr.junade.taipo.emoji.EmojiText

/**
 * Story 5.1, phase 5.1-3 : tampon de texte de la zone de saisie du prompt, avec son curseur. En mode
 * prompt, les frappes du clavier y sont redirigées au lieu d'aller dans le champ de l'application
 * (phase 5.1-5). Le retour arrière et les déplacements du curseur respectent les emojis composés
 * (paire de substitution, teinte, drapeau, séquence ZWJ), comme dans le champ de l'application
 * (story 1.15).
 *
 * Logique pure (sans Android), testée en JVM.
 */
class PromptInputBuffer {

    var text: String = ""
        private set

    /** Position du curseur, en caractères UTF-16, entre 0 et `text.length`. */
    var cursor: Int = 0
        private set

    val textBeforeCursor: String
        get() = text.substring(0, cursor)

    val textAfterCursor: String
        get() = text.substring(cursor)

    val isBlank: Boolean
        get() = text.isBlank()

    /** Insère [value] au curseur, qui se place juste après. */
    fun insert(value: String) {
        if (value.isEmpty()) return
        text = text.substring(0, cursor) + value + text.substring(cursor)
        cursor += value.length
    }

    /** Supprime le « caractère » (emoji composé compris) avant le curseur. Faux s'il n'y avait rien à supprimer. */
    fun backspace(): Boolean {
        val length = EmojiText.lastClusterLength(textBeforeCursor)
        if (length == 0) return false
        text = text.substring(0, cursor - length) + text.substring(cursor)
        cursor -= length
        return true
    }

    /**
     * Supprime jusqu'à [count] caractères UTF-16 avant le curseur (glissement de suppression par
     * mots : la longueur vient de mots entiers du texte, donc de limites valides). Renvoie le nombre
     * de caractères réellement supprimés.
     */
    fun deleteBefore(count: Int): Int {
        val length = count.coerceIn(0, cursor)
        if (length == 0) return 0
        text = text.substring(0, cursor - length) + text.substring(cursor)
        cursor -= length
        return length
    }

    /** Déplace le curseur de [steps] « caractères » (négatif : vers la gauche), borné au texte. */
    fun moveCursor(steps: Int) {
        var remaining = steps
        while (remaining < 0 && cursor > 0) {
            cursor -= EmojiText.lastClusterLength(textBeforeCursor)
            remaining++
        }
        while (remaining > 0 && cursor < text.length) {
            cursor = nextBoundary(cursor)
            remaining--
        }
    }

    /** Remplace tout le texte (par exemple pour restaurer un prompt après une erreur) ; curseur à la fin. */
    fun replaceAll(value: String) {
        text = value
        cursor = value.length
    }

    fun clear() = replaceAll("")

    /**
     * Plus petite limite de « caractère » strictement après [from] : on découpe le texte à rebours
     * depuis la fin, comme le fait le retour arrière, ce qui reste cohérent avec lui (drapeaux,
     * séquences ZWJ). Même si [from] tombe au milieu d'un emoji composé, on avance jusqu'à sa fin.
     */
    private fun nextBoundary(from: Int): Int {
        var boundary = text.length
        var position = text.length
        while (position > from) {
            boundary = position
            position -= EmojiText.lastClusterLength(Prefix(text, position))
        }
        return boundary
    }

    /** Début du texte sans copie : évite de recopier le texte à chaque pas du découpage. */
    private class Prefix(private val source: String, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = source[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            source.subSequence(startIndex, endIndex)
        override fun toString(): String = source.substring(0, length)
    }
}
