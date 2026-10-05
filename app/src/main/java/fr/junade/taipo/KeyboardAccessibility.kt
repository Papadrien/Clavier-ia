package fr.junade.taipo

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.StringRes
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * Noms que TalkBack prononce pour les caractères de ponctuation et les symboles (lot 20). Les lettres et les chiffres
 * n'en ont pas besoin : TalkBack les lit tels quels. Logique pure (aucun accès aux ressources : [nameRes] ne renvoie qu'un
 * identifiant), pour rester testable en JVM.
 */
internal object KeyChars {

    /** Identifiant de la chaîne qui nomme [char], ou null si le caractère se lit tel quel (lettres, chiffres, autres). */
    @StringRes
    fun nameRes(char: Char): Int? = when (char) {
        ',' -> R.string.a11y_char_comma
        '.' -> R.string.a11y_char_period
        '\'', '\u2019' -> R.string.a11y_char_apostrophe
        '"' -> R.string.a11y_char_quote
        '@' -> R.string.a11y_char_at
        '/' -> R.string.a11y_char_slash
        '+' -> R.string.a11y_char_plus
        '-' -> R.string.a11y_char_minus
        '=' -> R.string.a11y_char_equal
        '_' -> R.string.a11y_char_underscore
        '*' -> R.string.a11y_char_asterisk
        '#' -> R.string.a11y_char_hash
        '$' -> R.string.a11y_char_dollar
        '\u20AC' -> R.string.a11y_char_euro
        '%' -> R.string.a11y_char_percent
        '&' -> R.string.a11y_char_ampersand
        '!' -> R.string.a11y_char_exclamation
        '?' -> R.string.a11y_char_question
        ':' -> R.string.a11y_char_colon
        ';' -> R.string.a11y_char_semicolon
        '(' -> R.string.a11y_char_left_paren
        ')' -> R.string.a11y_char_right_paren
        '\u00B7' -> R.string.a11y_char_middle_dot
        else -> null
    }

    /** Vrai si TalkBack lit correctement [char] sans nom explicite : une lettre ou un chiffre. */
    fun isSelfSpoken(char: Char): Boolean = char.isLetterOrDigit()
}

/** Une touche et sa zone (coordonnées de la vue, en pixels), pour l'arbre d'accessibilité. */
internal class KeySlot(val key: Key, val bounds: android.graphics.Rect)

/**
 * Noms parlés des touches (lot 20). Le libellé affiché ne suffit pas : les icônes (Maj, Effacer, Entrée, Emoji) n'ont pas de
 * texte, la barre espace non plus, et la ponctuation se lit mal.
 */
internal class KeySpeech(private val context: Context) {

    fun describe(key: Key, layoutId: LayoutId, shifted: Boolean, capsLock: Boolean): String = when (val action = key.action) {
        is KeyAction.TypeChar -> charName(if (shifted && action.char.isLetter()) action.char.uppercaseChar() else action.char)
        KeyAction.Shift -> context.getString(if (capsLock) R.string.a11y_key_caps_lock else R.string.a11y_key_shift)
        KeyAction.Backspace -> context.getString(R.string.a11y_key_backspace)
        KeyAction.Enter -> context.getString(R.string.a11y_key_enter)
        KeyAction.Space -> context.getString(R.string.a11y_key_space)
        KeyAction.Emoji -> context.getString(R.string.a11y_key_emoji)
        KeyAction.ToggleLayout ->
            context.getString(if (layoutId == LayoutId.LETTERS) R.string.a11y_key_to_symbols else R.string.a11y_key_to_letters)
    }

    fun charName(char: Char): String = KeyChars.nameRes(char)?.let { context.getString(it) } ?: char.toString()
}

/**
 * Arbre d'accessibilité virtuel du clavier (lot 20). [KeyboardView] est une seule vue dessinée au Canvas : sans cela,
 * TalkBack voit un bloc muet et le clavier est inutilisable. Chaque touche devient un « bouton » virtuel, dans l'ordre de
 * lecture (rangée par rangée, de gauche à droite) :
 *
 * - l'exploration au doigt annonce la touche sous le doigt, un double appui la saisit ([KeyboardView.activateKey]) ;
 * - Maj annonce son état (activée / désactivée) ;
 * - les touches à appui long (accents, symboles du point, chiffres) proposent chaque caractère comme **action** du menu
 *   d'actions de TalkBack (« Saisir é », etc.), l'équivalent du glissement de la bulle d'appui long, qui est impossible à
 *   faire en exploration.
 *
 * Les gestes tactiles de [KeyboardView] (frappe, appui long, glissements) ne changent pas : ils restent utilisés quand
 * TalkBack est éteint, ou par le geste « double appui prolongé » de TalkBack.
 */
internal class KeyboardAccessibility(private val view: KeyboardView) : ExploreByTouchHelper(view) {

    private val speech = KeySpeech(view.context)

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val xi = x.toInt()
        val yi = y.toInt()
        val index = view.keySlots().indexOfFirst { it.bounds.contains(xi, yi) }
        return if (index >= 0) index else ExploreByTouchHelper.INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        for (index in view.keySlots().indices) virtualViewIds.add(index)
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val slot = view.keySlots().getOrNull(virtualViewId)
        if (slot == null) {
            // Identifiant périmé (disposition changée entre-temps) : un nœud vide mais valide, pour ne pas planter.
            node.contentDescription = ""
            node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
            return
        }
        node.contentDescription = describe(slot.key)
        node.setBoundsInParent(slot.bounds)
        node.className = android.widget.Button::class.java.name
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        if (slot.key.action is KeyAction.Shift) {
            node.stateDescription = view.context.getString(
                if (view.isShifted || view.isCapsLock) R.string.a11y_state_on else R.string.a11y_state_off,
            )
        }
        alternates(slot.key).forEachIndexed { index, char ->
            val label = view.context.getString(R.string.a11y_action_insert, speech.charName(spoken(char)))
            node.addAction(AccessibilityActionCompat(CUSTOM_ACTION_BASE + index, label))
        }
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        val slot = view.keySlots().getOrNull(virtualViewId) ?: return false
        if (action == AccessibilityNodeInfoCompat.ACTION_CLICK) {
            view.activateKey(slot.key)
            invalidateVirtualView(virtualViewId)
            return true
        }
        val char = alternates(slot.key).getOrNull(action - CUSTOM_ACTION_BASE) ?: return false
        view.typeSymbol(char)
        return true
    }

    override fun onPopulateEventForVirtualView(virtualViewId: Int, event: AccessibilityEvent) {
        val slot = view.keySlots().getOrNull(virtualViewId)
        event.contentDescription = if (slot != null) describe(slot.key) else ""
    }

    private fun describe(key: Key): String = speech.describe(key, view.layout.id, view.isShifted, view.isCapsLock)

    /** Caractères de l'appui long d'une touche : la grille de la bulle, ou à défaut le chiffre d'indice. */
    private fun alternates(key: Key): List<Char> =
        if (key.popup.isNotEmpty()) key.popup.flatten() else listOfNotNull(key.longPressChar)

    /** Comme sur la bulle d'appui long, les lettres sont annoncées en majuscule quand Maj est actif. */
    private fun spoken(char: Char): Char = if (view.isShifted && char.isLetter()) char.uppercaseChar() else char

    private companion object {
        /**
         * Premier identifiant des actions personnalisées (une par caractère de l'appui long). Un identifiant d'action
         * personnalisée doit avoir un octet de poids fort non nul (les actions standard sont plus petites) ; 0x7E est un
         * identifiant de paquet inutilisé, qui ne peut pas entrer en collision avec les ressources de l'application (0x7F)
         * ni celles du système (0x01).
         */
        const val CUSTOM_ACTION_BASE = 0x7E000000
    }
}
