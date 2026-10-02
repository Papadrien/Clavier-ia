package fr.junade.taipo.emoji

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Barre horizontale des emojis récents, au-dessus de la barre du haut du clavier dans les champs de
 * messagerie. Elle défile si les emojis dépassent la largeur ; toucher un emoji appelle le
 * listener (l'insertion au curseur est faite par `ClavierIme`). L'ordre affiché n'est mis à jour
 * que par [setEmojis] : toucher un emoji ne réorganise pas la barre sous le doigt.
 */
class RecentEmojiBarView(context: Context) : HorizontalScrollView(context) {

    fun interface OnEmojiClickListener {
        fun onEmojiClick(emoji: String)
    }

    private var listener: OnEmojiClickListener? = null
    private var shown: List<String> = emptyList()
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        // Fond transparent : la bande laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(BAR_HEIGHT_DP)))
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener?) {
        this.listener = listener
    }

    /** Remplace les emojis affichés (du plus récent au plus ancien) et revient au début de la barre. */
    fun setEmojis(emojis: List<String>) {
        if (emojis == shown) return
        shown = emojis
        row.removeAllViews()
        emojis.forEach { emoji ->
            row.addView(
                TextView(context).apply {
                    text = emoji
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, EMOJI_SIZE_SP)
                    gravity = Gravity.CENTER
                    setOnClickListener { listener?.onEmojiClick(emoji) }
                },
                LinearLayout.LayoutParams(dp(CELL_WIDTH_DP), dp(BAR_HEIGHT_DP)),
            )
        }
        scrollTo(0, 0)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val BAR_HEIGHT_DP = 40
        const val CELL_WIDTH_DP = 44
        const val EMOJI_SIZE_SP = 22f
    }
}
