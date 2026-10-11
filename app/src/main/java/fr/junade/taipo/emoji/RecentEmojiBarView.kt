package fr.junade.taipo.emoji

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.R
import fr.junade.taipo.announceAsButton
import fr.junade.taipo.dimen
import fr.junade.taipo.fixedHeightTextDimen
import kotlin.math.max

/**
 * Barre horizontale des emojis récents, au-dessus de la barre du haut du clavier dans les champs de
 * messagerie. Elle se remplit depuis la droite (côté du pouce) : l'emoji le plus récent est le plus à
 * droite, les plus anciens vont vers la gauche. Si les emojis dépassent la largeur, la barre défile et
 * s'ouvre calée à droite (les plus récents visibles). Toucher un emoji appelle le listener (l'insertion
 * au curseur est faite par `TaipoIme`). L'ordre affiché n'est mis à jour que par [setEmojis] : toucher
 * un emoji ne réorganise pas la barre sous le doigt.
 */
class RecentEmojiBarView(context: Context) : HorizontalScrollView(context) {

    fun interface OnEmojiClickListener {
        fun onEmojiClick(emoji: String)
    }

    private var listener: OnEmojiClickListener? = null
    private var shown: List<String> = emptyList()
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        // Contenu plus étroit que la barre : les emojis restent collés à droite.
        gravity = Gravity.END
    }

    /** Vrai tant que la barre doit être recalée sur son extrémité droite à la prochaine mise en page. */
    private var pinToEnd = false

    init {
        // « Droite » doit rester la droite physique (côté du pouce) quelle que soit la langue du système.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        // Le contenu occupe au moins toute la largeur : c'est ce qui permet l'alignement à droite.
        isFillViewport = true
        // Fond transparent : la bande laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dimen(R.dimen.taipo_emoji_recent_bar_height).toInt()))
    }

    fun setOnEmojiClickListener(listener: OnEmojiClickListener?) {
        this.listener = listener
    }

    /**
     * Remplace les emojis affichés. [emojis] est du plus récent au plus ancien ; la barre les affiche
     * en miroir (le plus récent à droite) et se cale à droite.
     */
    fun setEmojis(emojis: List<String>) {
        if (emojis == shown) return
        shown = emojis
        row.removeAllViews()
        // Lot 21 : dimensions lues une fois pour toute la barre, pas une fois par emoji.
        val textSize = fixedHeightTextDimen(R.dimen.taipo_emoji_recent_text_size)
        val cellWidth = dimen(R.dimen.taipo_emoji_recent_cell_width).toInt()
        val barHeight = dimen(R.dimen.taipo_emoji_recent_bar_height).toInt()
        emojis.asReversed().forEach { emoji ->
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
        // Le calage se fait à la mise en page, quand la largeur du contenu est connue.
        pinToEnd = true
        requestLayout()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (pinToEnd && width > 0) {
            pinToEnd = false
            scrollTo(max(0, row.width - (width - paddingLeft - paddingRight)), 0)
        }
    }
}
