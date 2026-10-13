package fr.junade.taipo

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.dictionary.WordSuggestion

/**
 * Zone de gauche de la barre du haut (lot 4.2, extraite de [CorrectionBarView] sans changement de
 * comportement) : superpose la bande de suggestions de mots, le bouton Smart Clipboard, la puce de collage
 * et les suggestions d'auto-remplissage en ligne ; [render] n'en montre qu'une à la fois, selon
 * [BarVisibility.zoneContent] (décidé par [SuggestionZoneState]).
 */
internal class SuggestionZoneView(context: Context, private val style: BarStyle) : FrameLayout(context) {

    /** Stories 1.16/1.17 : bande de suggestions (3 mots + 1 emoji). */
    private val suggestionStrip = SuggestionStripView(context)

    /** Story 2.1 : bouton Smart Clipboard (icône « coller »). */
    private val clipboardButton = ImageButton(context)

    /** Story 2.2 : puce de collage : icône « coller » (celle du Smart Clipboard) puis aperçu du texte copié récemment. */
    private val pasteChip = LinearLayout(context)
    private val pasteIcon = ImageView(context)
    private val pasteText = TextView(context)

    /**
     * Suggestions d'auto-remplissage en ligne (gestionnaire de mots de passe) : des vues fournies par
     * le service d'auto-remplissage, rangées en ligne et défilables, à la place de la bande de mots.
     */
    private val inlineRow = LinearLayout(context)
    private val inlineScroll = HorizontalScrollView(context)

    private var clipboardListener: CorrectionBarView.OnClipboardClickListener? = null
    private var pasteListener: CorrectionBarView.OnPasteClickListener? = null
    private var clipboardButtonActiveBackground: Boolean? = null
    private val clipboardButtonBackgrounds = style.toggleBackgrounds() // lot 21 : un Drawable par état, jamais recréé

    init {
        // Bouton Smart Clipboard : icône « coller » à la place du texte.
        style.styleBarIconButton(clipboardButton, R.drawable.ic_paste)
        clipboardButton.contentDescription = context.getString(R.string.clipboard_button)
        clipboardButton.setOnClickListener { clipboardListener?.onClipboardClick() }

        pasteIcon.setImageResource(R.drawable.ic_paste)
        pasteIcon.imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        pasteIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        pasteIcon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        pasteText.setTextColor(context.themeColor(R.color.text_primary))
        pasteText.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        pasteText.useTaipoFont()
        pasteText.gravity = Gravity.CENTER_VERTICAL
        pasteText.maxLines = 1
        pasteText.ellipsize = TextUtils.TruncateAt.END
        pasteText.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        pasteChip.orientation = LinearLayout.HORIZONTAL
        pasteChip.gravity = Gravity.CENTER_VERTICAL
        val chipPadding = dimen(R.dimen.taipo_suggestion_chip_padding).toInt()
        val iconSize = dimen(R.dimen.taipo_icon_size).toInt()
        val iconGap = dimen(R.dimen.taipo_suggestion_chip_gap).toInt()
        // Icône à gauche du texte ; le contenu se centre sur la face du fond : marge basse = épaisseur d'ombre.
        pasteChip.addView(pasteIcon, LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = iconGap })
        pasteChip.addView(pasteText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        pasteChip.setPadding(chipPadding, 0, chipPadding, dimen(R.dimen.taipo_key_shadow_height).toInt())
        pasteChip.background = style.pillBackground(R.color.surface_button)
        pasteChip.setOnClickListener { pasteListener?.onPasteClick() }
        pasteChip.announceAsButton() // lot 20
        pasteChip.visibility = View.GONE

        inlineRow.orientation = LinearLayout.HORIZONTAL
        inlineRow.gravity = Gravity.CENTER_VERTICAL
        inlineScroll.isHorizontalScrollBarEnabled = false
        inlineScroll.overScrollMode = View.OVER_SCROLL_NEVER
        inlineScroll.addView(
            inlineRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        inlineScroll.visibility = View.GONE

        addView(suggestionStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val iconButtonWidth = dimen(R.dimen.taipo_suggestion_icon_button_width).toInt()
        addView(
            clipboardButton,
            LayoutParams(iconButtonWidth, LayoutParams.MATCH_PARENT, Gravity.START),
        )
        addView(
            pasteChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START),
        )
        addView(
            inlineScroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    /** Story 1.16 : propose [emoji] dans le 4e emplacement de la bande de suggestions (null = aucun). */
    fun setEmojiSuggestion(emoji: String?) {
        suggestionStrip.setEmoji(emoji)
    }

    fun setOnEmojiSuggestionClickListener(listener: SuggestionStripView.OnEmojiClickListener) {
        suggestionStrip.setOnEmojiClickListener(listener)
    }

    /** Story 1.17 : remplit les 3 emplacements de mots (liste vide ou null = emplacement vide). */
    fun setWordSuggestions(words: List<WordSuggestion?>) {
        suggestionStrip.setWords(words)
    }

    fun setOnWordSuggestionClickListener(listener: SuggestionStripView.OnWordClickListener) {
        suggestionStrip.setOnWordClickListener(listener)
    }

    /** Story 10.1 : champ sensible, le bouton Smart Clipboard est grisé (il reste touchable pour afficher la raison). */
    fun setClipboardButtonRestricted(restricted: Boolean) {
        clipboardRestricted = restricted
        clipboardButton.alpha = clipboardRestingAlpha()
    }

    private var clipboardRestricted = false

    /** Opacité de repos du bouton Smart Clipboard : l'animation d'apparition la relit au lieu de mémoriser celle du départ. */
    private fun clipboardRestingAlpha(): Float = if (clipboardRestricted) RESTRICTED_ALPHA else 1f

    fun setOnClipboardClickListener(listener: CorrectionBarView.OnClipboardClickListener) {
        clipboardListener = listener
    }

    fun setOnPasteClickListener(listener: CorrectionBarView.OnPasteClickListener) {
        pasteListener = listener
    }

    /**
     * Texte de la puce de collage : [preview] (déjà mis en forme, éventuellement masqué) ; [sensitive] :
     * l'aperçu est masqué, la description d'accessibilité ne révèle donc pas non plus le texte.
     * null vide la puce.
     */
    fun setPasteText(preview: String?, sensitive: Boolean) {
        if (preview == null) {
            pasteText.text = ""
            return
        }
        pasteText.text = preview
        pasteChip.contentDescription = if (sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else {
            context.getString(R.string.clipboard_paste_description, preview)
        }
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
     * Montre le contenu décidé par [visibility] ; le bouton Smart Clipboard est coloré tant que le panneau est ouvert.
     * Ce qui apparaît s'anime depuis la gauche ([BarReveal]) : dévoilement complet si [reveal] (appui sur le menu de
     * gauche), sinon fondu discret (frappe, nouvelle copie) ; rien tant que la barre n'est pas affichée.
     */
    fun render(visibility: ZoneContentVisibility, clipboardPanelOpen: Boolean, reveal: Boolean = false) {
        show(suggestionStrip, visibility.words, reveal)
        show(clipboardButton, visibility.clipboardButton, reveal, ::clipboardRestingAlpha)
        show(pasteChip, visibility.pasteChip, reveal)
        show(inlineScroll, visibility.inline, reveal)
        if (clipboardButtonActiveBackground != clipboardPanelOpen) {
            clipboardButtonActiveBackground = clipboardPanelOpen
            clipboardButton.background = clipboardButtonBackgrounds.get(clipboardPanelOpen)
        }
    }

    private fun show(view: View, visible: Boolean, reveal: Boolean, restingAlpha: (() -> Float)? = null) {
        val wasVisible = view.visibility == View.VISIBLE
        view.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible && !wasVisible && isLaidOut) {
            BarReveal.enter(view, fromEnd = false, full = reveal, restingAlpha = restingAlpha)
        } else if (!visible && wasVisible) {
            BarReveal.cancel(view)
        }
    }
}
