package fr.junade.taipo

import fr.junade.taipo.emoji.EmojiText
import kotlin.math.abs
import kotlin.math.min

/**
 * Glissement sur la barre espace dans le champ de l'application : nombre de pas que le curseur peut réellement faire.
 *
 * Le curseur est déplacé par des touches directionnelles. Arrivée au début ou à la fin du texte, une touche gauche ou
 * droite que le champ ne peut plus absorber n'est pas perdue : Android la traite comme un déplacement du focus, et le
 * curseur quitte le champ (champ voisin, défilement de l'écran) sans que le clavier puisse le reprendre. On ne
 * envoie donc que les pas qui restent dans le texte.
 *
 * Logique pure (sans Android), testée en JVM. Un « pas » est un caractère affiché (un emoji composé compte pour un).
 */
internal object CursorSteps {

    /** Longueur maximale, en caractères UTF-16, supposée pour un caractère affiché (emoji composé compris). */
    private const val MAX_CLUSTER_CHARS = 32

    /** Combien de caractères demander au champ avant ou après le curseur pour juger de [requested] pas. */
    fun fetchLimit(requested: Int): Int = abs(requested) * MAX_CLUSTER_CHARS

    /**
     * Pas autorisés (de même signe que [requested], jamais plus grands en valeur absolue).
     *
     * @param before texte avant le curseur, au moins [fetchLimit] caractères s'il y en a autant
     * @param after texte après le curseur, idem
     * @param hasSelection une sélection existe : le premier pas la replie sans déplacer le curseur, il est toujours sûr
     */
    fun allowed(requested: Int, before: CharSequence, after: CharSequence, hasSelection: Boolean): Int {
        if (requested == 0) return 0
        val wanted = abs(requested)
        val reachable = if (requested < 0) clustersBack(before, wanted) else clustersBack(after, Int.MAX_VALUE)
        val steps = min(wanted, reachable + if (hasSelection) 1 else 0)
        return if (requested < 0) -steps else steps
    }

    /** Nombre de caractères affichés dans [text], comptés depuis la fin, jusqu'à [upTo]. */
    private fun clustersBack(text: CharSequence, upTo: Int): Int {
        var length = text.length
        var count = 0
        while (length > 0 && count < upTo) {
            length -= EmojiText.lastClusterLength(Head(text, length)).coerceAtLeast(1)
            count++
        }
        return count
    }

    /** Début d'un texte sans copie. */
    private class Head(private val source: CharSequence, override val length: Int) : CharSequence {
        override fun get(index: Int): Char = source[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = source.subSequence(startIndex, endIndex)
        override fun toString(): String = source.subSequence(0, length).toString()
    }
}
