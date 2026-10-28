package fr.junade.taipo

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Zone de suggestions de la rangée de saisie (entre le robot et Corriger) : superpose la bande de suggestions de mots et les
 * suggestions d'auto-remplissage en ligne ; [render] n'en montre qu'une à la fois, selon [BarContentVisibility]. La bande
 * partage elle-même sa largeur à parts égales entre ses trois mots ([SuggestionStripView]).
 */
internal class SuggestionZoneView(context: Context) : FrameLayout(context) {

    /** Stories 1.16/1.17 : bande de suggestions (3 mots + 1 emoji). */
    private val suggestionStrip = SuggestionStripView(context)

    /**
     * Suggestions d'auto-remplissage en ligne (gestionnaire de mots de passe) : des vues fournies par
     * le service d'auto-remplissage, rangées en ligne et défilables, à la place de la bande de mots.
     */
    private val inlineRow = LinearLayout(context)
    private val inlineScroll = HorizontalScrollView(context)

    /** Tip (ampoule + texte) affiché à la place de la bande de mots : voir [SuggestionZoneState.showTip]. */
    private val tipView = LinearLayout(context)

    init {
        inlineRow.orientation = LinearLayout.HORIZONTAL
        inlineRow.gravity = Gravity.CENTER_VERTICAL
        inlineScroll.isHorizontalScrollBarEnabled = false
        inlineScroll.overScrollMode = View.OVER_SCROLL_NEVER
        inlineScroll.addView(
            inlineRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        inlineScroll.visibility = View.GONE

        tipView.orientation = LinearLayout.HORIZONTAL
        tipView.gravity = Gravity.CENTER
        tipView.visibility = View.GONE
        val iconSize = dimen(R.dimen.taipo_icon_size).toInt()
        val tipIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_tip_bulb)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val tipText = TextView(context).apply {
            setText(R.string.digit_swipe_tip)
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_text_size)
            useTaipoFont()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tipView.addView(tipIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        tipView.addView(
            tipText,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dimen(R.dimen.taipo_suggestion_slot_gap).toInt() },
        )

        addView(suggestionStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            inlineScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        addView(tipView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Story 1.16 : propose [emoji] dans le 4e emplacement de la bande de suggestions (null = aucun). */
    fun setEmojiSuggestion(emoji: String?) {
        suggestionStrip.setEmoji(emoji)
    }

    fun setOnEmojiSuggestionClickListener(listener: SuggestionStripView.OnEmojiClickListener) {
        suggestionStrip.setOnEmojiClickListener(listener)
    }

    /** Pilule « coller » dans la bande (place du 3e mot, ou toute la largeur sans mot) : aperçu de la copie récente ([preview], null = aucune pilule). */
    fun setPasteSuggestion(preview: String?, sensitive: Boolean) {
        suggestionStrip.setPaste(preview, sensitive)
    }

    fun setOnPasteClickListener(listener: () -> Unit) {
        suggestionStrip.setOnPasteClickListener(listener)
    }

    /** Story 1.17 : remplit les 3 emplacements de mots (liste vide ou null = emplacement vide). */
    fun setWordSuggestions(words: List<WordSuggestion?>) {
        suggestionStrip.setWords(words)
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        suggestionStrip.setOnWordClickListener(listener)
    }

    /**
     * Remplace les suggestions d'auto-remplissage en ligne par [views] (liste vide : les retire).
     *
     * Les vues déjà affichées ne sont ni détachées ni redimensionnées : le système fixe leur taille
     * (LayoutParams) quand le service d'auto-remplissage a rendu leur contenu, et une vue détachée puis
     * rattachée, ou dotée de nouveaux LayoutParams « WRAP_CONTENT », se retrouve sans largeur (invisible).
     * Chaque nouvelle vue est insérée à son rang ; une largeur déjà fixée par le système est conservée.
     */
    fun setInlineViews(views: List<View>) {
        val gap = dimen(R.dimen.taipo_suggestion_chip_gap).toInt()
        for (i in inlineRow.childCount - 1 downTo 0) {
            if (inlineRow.getChildAt(i) !in views) inlineRow.removeViewAt(i)
        }
        views.forEachIndexed { index, view ->
            val margin = if (index > 0) gap else 0
            if (view.parent === inlineRow) {
                (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                    if (params.marginStart != margin) {
                        params.marginStart = margin
                        view.layoutParams = params
                    }
                }
            } else {
                (view.parent as? ViewGroup)?.removeView(view)
                val width = view.layoutParams?.width?.takeIf { it > 0 } ?: LinearLayout.LayoutParams.WRAP_CONTENT
                inlineRow.addView(
                    view,
                    minOf(index, inlineRow.childCount),
                    LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = margin },
                )
            }
        }
        if (views.isEmpty()) inlineScroll.scrollTo(0, 0)
    }

    /**
     * Montre la bande de mots ([words]), les suggestions d'auto-remplissage ([inline]) ou le tip ([tip]). Ce qui apparaît
     * se fond ([BarReveal]) ; rien tant que la barre n'est pas affichée.
     */
    fun render(words: Boolean, inline: Boolean, tip: Boolean = false) {
        show(suggestionStrip, words)
        show(inlineScroll, inline)
        show(tipView, tip)
    }

    private fun show(view: View, visible: Boolean) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !wasVisible && isLaidOut) {
            BarReveal.enter(view, fromEnd = false, full = false)
        } else if (!visible && wasVisible) {
            BarReveal.cancel(view)
        }
    }
}
