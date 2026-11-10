package fr.junade.taipo

import android.content.Context

/**
 * Revue UX : astuces de gestes, montrées **une seule fois chacune**, au moment où elles servent, dans la barre du haut
 * ([MessagePopup.showTip]). Une astuce dont le geste a déjà été utilisé ([learned]) n'est jamais montrée.
 *
 * - [Tip.DELETE_WORDS] : plusieurs appuis de suite sur ⌫ → glisser sur ⌫ efface des mots entiers.
 * - [Tip.MOVE_CURSOR] : le curseur déplacé à la main plusieurs fois dans le champ → glisser sur l'espace le déplace.
 * - [Tip.SWITCH_LANGUAGE] : clavier multilingue → appui long sur Entrée pour changer de langue.
 * - [Tip.DIGITS] : un chiffre tapé depuis la page des symboles, sans rangée de chiffres → appui long sur la rangée du haut.
 *
 * Les compteurs sont dans [Counter] (logique pure, testée en JVM) ; les astuces déjà montrées ou apprises sont gardées dans les
 * préférences.
 */
class GestureTips(context: Context) {

    enum class Tip(val key: String) {
        DELETE_WORDS("delete_words"),
        MOVE_CURSOR("move_cursor"),
        SWITCH_LANGUAGE("switch_language"),
        DIGITS("digits"),
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val counter = Counter()

    /** Le geste de [tip] a été utilisé : son astuce ne sera jamais montrée. */
    fun learned(tip: Tip) {
        if (!done(tip)) prefs.edit().putBoolean(tip.key, true).apply()
    }

    /** Touche appuyée ; renvoie l'astuce à montrer maintenant, ou null. */
    fun onKey(backspace: Boolean, digitFromSymbols: Boolean): Tip? {
        if (digitFromSymbols) return claim(Tip.DIGITS)
        return if (counter.onKey(backspace)) claim(Tip.DELETE_WORDS) else null
    }

    /** Le curseur a été déplacé à la main dans le champ (pas par la frappe). */
    fun onManualCursorMove(): Tip? = if (counter.onManualCursorMove()) claim(Tip.MOVE_CURSOR) else null

    /** Ouverture d'un champ avec le clavier multilingue. */
    fun onMultilingualField(): Tip? = claim(Tip.SWITCH_LANGUAGE)

    private fun done(tip: Tip) = prefs.getBoolean(tip.key, false)

    /** L'astuce n'a jamais été montrée ni apprise : elle peut l'être ([shown] la retire une fois affichée). */
    private fun claim(tip: Tip): Tip? = if (done(tip)) null else tip

    /** L'astuce vient d'être affichée : elle ne reviendra pas. */
    fun shown(tip: Tip) = learned(tip)

    /** Compteurs des déclencheurs : appuis consécutifs sur ⌫, déplacements du curseur à la main. Logique pure. */
    class Counter {
        private var backspaces = 0
        private var cursorMoves = 0

        /** Vrai quand [BACKSPACES_FOR_TIP] appuis consécutifs sur ⌫ viennent d'être faits. */
        fun onKey(backspace: Boolean): Boolean {
            backspaces = if (backspace) backspaces + 1 else 0
            return backspaces == BACKSPACES_FOR_TIP
        }

        /** Vrai au [CURSOR_MOVES_FOR_TIP]-ième déplacement à la main. */
        fun onManualCursorMove(): Boolean {
            cursorMoves++
            return cursorMoves == CURSOR_MOVES_FOR_TIP
        }
    }

    companion object {
        const val BACKSPACES_FOR_TIP = 5
        const val CURSOR_MOVES_FOR_TIP = 3
        private const val PREFS_NAME = "gesture_tips"

        /**
         * Debug : toutes les astuces redeviennent « jamais montrées » (astuces de gestes et tip « Swipe vers le bas » des
         * chiffres). Les compteurs en mémoire du clavier ouvert ne sont pas remis à zéro.
         */
        fun resetAll(context: Context) {
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
            KeyboardPreferences(context).hasUsedDigitSwipe = false
        }
    }
}
