package fr.junade.taipo

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
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

    /** Story 2.2 : puce de collage (aperçu du texte copié récemment). */
    private val pasteChip = TextView(context)

    /**
     * Suggestions d'auto-remplissage en ligne (gestionnaire de mots de passe) : des vues fournies par
     * le service d'auto-remplissage, rangées en ligne et défilables, à la place de la bande de mots.
     */
    private val inlineRow = LinearLayout(context)
    private val inlineScroll = HorizontalScrollView(context)

    private var clipboardListener: CorrectionBarView.OnClipboardClickListener? = null
    private var pasteListener: CorrectionBarView.OnPasteClickListener? = null
    private var clipboardButtonActiveBackground: Boolean? = null

    init {
        // Bouton Smart Clipboard : icône « coller » à la place du texte.
        style.styleBarIconButton(clipboardButton, R.drawable.ic_paste)
        clipboardButton.contentDescription = context.getString(R.string.clipboard_button)
        clipboardButton.setOnClickListener { clipboardListener?.onClipboardClick() }

        pasteChip.setTextColor(context.themeColor(R.color.text_primary))
        pasteChip.setTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        pasteChip.useTaipoFont()
        pasteChip.gravity = Gravity.CENTER_VERTICAL
        pasteChip.maxLines = 1
        pasteChip.ellipsize = TextUtils.TruncateAt.END
        val chipPadding = dimen(R.dimen.taipo_suggestion_chip_padding).toInt()
        // Le texte se centre sur la face du fond : marge basse = épaisseur d'ombre.
        pasteChip.setPadding(chipPadding, 0, chipPadding, dimen(R.dimen.taipo_key_shadow_height).toInt())
        pasteChip.background = style.pillBackground(R.color.surface_button)
        pasteChip.setOnClickListener { pasteListener?.onPasteClick() }
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
            pasteChip.text = ""
            return
        }
        pasteChip.text = preview
        pasteChip.contentDescription = if (sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else {
            context.getString(R.string.clipboard_paste_description, preview)
        }
    }

    /** Remplace les suggestions d'auto-remplissage en ligne par [views] (liste vide : les retire). */
    fun setInlineViews(views: List<View>) {
        inlineRow.removeAllViews()
        views.forEachIndexed { index, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            inlineRow.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ).apply {
                    if (index > 0) marginStart = dimen(R.dimen.taipo_suggestion_chip_gap).toInt()
                },
            )
        }
        inlineScroll.scrollTo(0, 0)
    }

    /** Montre le contenu décidé par [visibility] ; le bouton Smart Clipboard est coloré tant que le panneau est ouvert. */
    fun render(visibility: ZoneContentVisibility, clipboardPanelOpen: Boolean) {
        suggestionStrip.visibility = if (visibility.words) View.VISIBLE else View.GONE
        clipboardButton.visibility = if (visibility.clipboardButton) View.VISIBLE else View.GONE
        pasteChip.visibility = if (visibility.pasteChip) View.VISIBLE else View.GONE
        inlineScroll.visibility = if (visibility.inline) View.VISIBLE else View.GONE
        if (clipboardButtonActiveBackground != clipboardPanelOpen) {
            clipboardButtonActiveBackground = clipboardPanelOpen
            clipboardButton.background =
                style.pillBackground(if (clipboardPanelOpen) R.color.accent else R.color.surface_button)
        }
    }
}
