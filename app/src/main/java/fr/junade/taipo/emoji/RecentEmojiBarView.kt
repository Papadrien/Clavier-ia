package fr.junade.taipo.emoji

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.R
import fr.junade.taipo.announceAsButton
import fr.junade.taipo.dimen
import fr.junade.taipo.fixedHeightTextDimen

/**
 * Barre horizontale des emojis récents, au-dessus de la barre du haut du clavier dans les champs de
 * messagerie. Elle défile si les emojis dépassent la largeur ; toucher un emoji appelle le
 * listener (l'insertion au curseur est faite par `TaipoIme`). L'ordre affiché n'est mis à jour
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
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.taipo_emoji_recent_bar_height).toInt()))
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener?) {
        this.listener = listener
    }

    /** Remplace les emojis affichés (du plus récent au plus ancien) et revient au début de la barre. */
    fun setEmojis(emojis: List<String>) {
        if (emojis == shown) return
        shown = emojis
        row.removeAllViews()
        // Lot 21 : dimensions lues une fois pour toute la barre, pas une fois par emoji.
        val textSize = fixedHeightTextDimen(R.dimen.taipo_emoji_recent_text_size)
        val cellWidth = dimen(R.dimen.taipo_emoji_recent_cell_width).toInt()
        val barHeight = dimen(R.dimen.taipo_emoji_recent_bar_height).toInt()
        emojis.forEach { emoji ->
            row.addView(
                TextView(context).apply {
                    text = emoji
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                    gravity = Gravity.CENTER
                    setOnClickListener { listener?.onEmojiClick(emoji) }
                    announceAsButton()
                },
                LinearLayout.LayoutParams(cellWidth, barHeight),
            )
        }
        scrollTo(0, 0)
    }
}
