package fr.junade.taipo

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Les trois boutons communs à la rangée normale et au menu ouvert de la rangée de saisie : Paramètres, Smart Clipboard
 * et Annuler. Une vue ne peut avoir qu'un parent : la barre en crée deux jeux (un par rangée) et applique chaque état aux
 * deux. Les icônes animées et les fonds de bascule sont propres à chaque jeu (un Drawable n'appartient qu'à une seule vue).
 */
internal class UtilityButtons(private val context: Context, style: BarStyle) {

    val settingsButton = ImageButton(context)
    val clipboardButton = ImageButton(context)
    val undoButton = ImageButton(context)

    /** Roue crantée qui tourne en ressort quand on touche le bouton, le temps que l'application s'ouvre. */
    private val settingsIcon = SpinIconDrawable(context, R.drawable.ic_settings, context.dimen(R.dimen.taipo_icon_size).toInt())

    /** Icône « coller » animée : panneau ouvert, le presse-papiers s'incline et des étincelles jaillissent. */
    private val clipboardIcon = ClipboardIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    /** Icône « annuler » animée (« Rembobinage ») : un tour à l'envers, en ressort, à chaque appui. */
    private val undoIcon = UndoIconDrawable(context.dimen(R.dimen.taipo_icon_size).toInt())

    private val clipboardBackgrounds = style.toggleBackgrounds() // lot 21 : un Drawable par état, jamais recréé
    private var clipboardActive: Boolean? = null
    private var clipboardRestricted = false
    private var undoEnabled = false
    private var settingsListener: (() -> Unit)? = null
    private var clipboardListener: (() -> Unit)? = null
    private var undoListener: (() -> Unit)? = null

    val views: List<View> get() = listOf(settingsButton, clipboardButton, undoButton)

    init {
        style.styleRoundIconButton(settingsButton, R.drawable.ic_settings)
        settingsButton.contentDescription = context.getString(R.string.settings_button_description)
        settingsButton.setImageDrawable(settingsIcon) // même tracé que ic_settings au repos
        settingsButton.setOnClickListener {
            settingsIcon.spin()
            settingsListener?.invoke()
        }

        style.styleRoundIconButton(clipboardButton, R.drawable.ic_paste)
        clipboardButton.setImageDrawable(clipboardIcon) // même tracé que ic_paste au repos
        clipboardButton.contentDescription = context.getString(R.string.clipboard_button)
        clipboardButton.setOnClickListener { clipboardListener?.invoke() }

        style.styleRoundIconButton(undoButton, R.drawable.ic_undo)
        undoButton.contentDescription = context.getString(R.string.undo_button)
        undoButton.setImageDrawable(undoIcon) // même tracé que ic_undo au repos
        undoButton.setOnClickListener {
            undoIcon.play()
            undoListener?.invoke()
        }
        undoButton.isEnabled = false
        undoButton.alpha = undoRestingAlpha()
    }

    fun setOnSettingsClickListener(listener: () -> Unit) {
        settingsListener = listener
    }

    fun setOnClipboardClickListener(listener: () -> Unit) {
        clipboardListener = listener
    }

    fun setOnUndoClickListener(listener: () -> Unit) {
        undoListener = listener
    }

    /** Opacité de repos du bouton Smart Clipboard : l'animation d'apparition la relit au lieu de mémoriser celle du départ. */
    fun clipboardRestingAlpha(): Float = if (clipboardRestricted) RESTRICTED_ALPHA else 1f

    /** Opacité de repos du bouton Annuler. */
    fun undoRestingAlpha(): Float = if (undoEnabled) 1f else RESTRICTED_ALPHA

    /** Story 10.1 : champ sensible, le bouton Smart Clipboard est grisé (il reste touchable pour afficher la raison). */
    fun setClipboardRestricted(restricted: Boolean) {
        clipboardRestricted = restricted
        clipboardButton.alpha = clipboardRestingAlpha()
    }

    /** Le bouton Smart Clipboard est coloré (actif) tant que le panneau est ouvert. */
    fun setClipboardActive(active: Boolean, animate: Boolean) {
        if (clipboardActive == active) return
        clipboardActive = active
        clipboardButton.background = clipboardBackgrounds.get(active)
        clipboardIcon.setActive(active, animate = animate)
    }

    /** Une action est annulable ([enabled]) ou non : le bouton Annuler est actif ou grisé (et non touchable). */
    fun setUndoEnabled(enabled: Boolean, animate: Boolean) {
        if (enabled && !undoEnabled && animate) undoIcon.pop()
        undoEnabled = enabled
        undoButton.isEnabled = enabled
        undoButton.alpha = undoRestingAlpha()
    }
}

/** Story 2.2 : puce de collage : aperçu du texte copié récemment (sans icône), à côté du bouton Smart Clipboard. */
internal class PasteChipView(context: Context, style: BarStyle) : LinearLayout(context) {

    private val text = TextView(context)
    private var listener: (() -> Unit)? = null

    init {
        text.setTextColor(context.themeColor(R.color.text_primary))
        text.setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
        text.useTaipoFont()
        text.gravity = Gravity.CENTER_VERTICAL
        text.maxLines = 1
        text.ellipsize = TextUtils.TruncateAt.END
        text.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val chipPadding = dimen(R.dimen.taipo_suggestion_chip_padding).toInt()
        // Le contenu se centre sur la face du fond : marge basse = épaisseur d'ombre.
        addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setPadding(chipPadding, 0, chipPadding, dimen(R.dimen.taipo_key_shadow_height).toInt())
        background = style.pillBackground(R.color.surface_button)
        setOnClickListener { listener?.invoke() }
        announceAsButton() // lot 20
    }

    fun setOnPasteClickListener(listener: () -> Unit) {
        this.listener = listener
    }

    /**
     * Texte de la puce : [preview] (déjà mis en forme, éventuellement masqué) ; [sensitive] : l'aperçu est masqué, la
     * description d'accessibilité ne révèle donc pas non plus le texte. null vide la puce.
     */
    fun setPasteText(preview: String?, sensitive: Boolean) {
        if (preview == null) {
            text.text = ""
            return
        }
        text.text = preview
        contentDescription = if (sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else {
            context.getString(R.string.clipboard_paste_description, preview)
        }
    }
}
