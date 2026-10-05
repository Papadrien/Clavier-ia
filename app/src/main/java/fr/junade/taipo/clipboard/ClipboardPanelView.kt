package fr.junade.taipo.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import fr.junade.taipo.KeyboardWidth
import fr.junade.taipo.PillKeyDrawable
import fr.junade.taipo.R
import fr.junade.taipo.TaipoType
import fr.junade.taipo.announceAsButton
import fr.junade.taipo.dimen
import fr.junade.taipo.setTextSizeRes
import fr.junade.taipo.themeColor
import fr.junade.taipo.useTaipoFont

/**
 * Stories 2.1 et 2.5 à 2.8 : panneau Smart Clipboard, qui remplace les touches quand on appuie sur le
 * bouton « Presse-papiers » de la barre (même principe que le panneau emoji de la story 1.15). Il n'a pas de bouton « ABC » : on le ferme par
 * la croix de la barre du haut.
 *
 * Il affiche les éléments en cartes (2 par rangée, aperçu sur 3 lignes, masqué si le contenu est
 * sensible) : les copies récentes (la dernière copie puis l'historique d'1 h, story 2.9), puis les
 * éléments épinglés, marqués d'une épingle. Une copie récente a le même menu qu'une carte non
 * épinglée. Un appui sur une
 * carte la colle ; un appui long ouvre un petit menu dessiné par-dessus le panneau (et non une
 * fenêtre popup, capricieuse dans la fenêtre d'un clavier) : « Épingler », « Modifier » et
 * « Supprimer » pour une carte non épinglée ; « Modifier », « Ajouter une étiquette » et « Supprimer »
 * pour une carte épinglée sans étiquette ; « Modifier », « Modifier l'étiquette », « Supprimer
 * l'étiquette » et « Supprimer » pour une carte épinglée étiquetée (« Modifier » est absent pour un
 * contenu sensible ou trop long, voir [ClipboardItems.canEdit]). Les deux suppressions demandent
 * une confirmation, dessinée de la même façon dans le même voile. L'étiquette d'une carte
 * épinglée s'affiche en pill à l'intérieur de la carte, au-dessus du texte. Sans élément, un
 * message indique qu'il n'y a rien à coller.
 *
 * La hauteur est fixée par l'appelant (celle du clavier remplacé) : le clavier ne change pas de
 * taille en basculant.
 *
 * Lot 15 (refonte graphique) : le fond est celui de tout le clavier (#0E0E0E) ; les cartes sont des
 * « face + ombre » (#2B2B2B, ou #43384C pour une carte épinglée) qui s'enfoncent à l'appui, comme les
 * touches ; l'action principale du menu (« Épingler ») est un bouton violet (#8C00FF) ; l'étiquette est
 * une pastille violette. Open Sans partout. Aucune logique modifiée (base, historique, chiffrement,
 * contenu sensible, sélection).
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(context: Context) : FrameLayout(context) {

    /** Une action sur une carte (collage, épinglage, suppression). */
    fun interface OnItemListener {
        fun onItem(item: ClipboardItems.Item)
    }

    private val content = LinearLayout(context)
    private val list = LinearLayout(context)
    private val emptyMessage = TextView(context)
    private val bottomSpacer = View(context)
    private val scrim = FrameLayout(context)
    private val menuBox = LinearLayout(context)
    private val pinEntry = TextView(context)
    private val editEntry = TextView(context)
    private val labelEntry = TextView(context)
    private val deleteLabelEntry = TextView(context)
    private val deleteEntry = TextView(context)
    private val confirmQuestion = TextView(context)
    private val confirmBox = LinearLayout(context)

    private var menuItem: ClipboardItems.Item? = null
    private var pasteListener: OnItemListener? = null
    private var pinListener: OnItemListener? = null
    private var editListener: OnItemListener? = null
    private var labelListener: OnItemListener? = null
    private var deleteLabelListener: OnItemListener? = null
    private var confirmingLabelDeletion = false
    private var deleteListener: OnItemListener? = null

    init {
        // Fond transparent : le panneau laisse voir le fond commun du clavier (KeyboardBackgroundDrawable, #0E0E0E).

        content.orientation = LinearLayout.VERTICAL
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val listArea = FrameLayout(context)
        val scroll = ScrollView(context)
        list.orientation = LinearLayout.VERTICAL
        val listPadding = dimen(R.dimen.taipo_clip_list_padding).toInt()
        list.setPadding(listPadding, listPadding, listPadding, listPadding)
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listArea.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        emptyMessage.text = context.getString(R.string.clipboard_empty)
        emptyMessage.setTextColor(context.themeColor(R.color.clip_text_muted))
        emptyMessage.setTextSizeRes(R.dimen.taipo_clip_empty_text_size)
        emptyMessage.useTaipoFont()
        emptyMessage.gravity = Gravity.CENTER
        listArea.addView(emptyMessage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(listArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        content.addView(bottomSpacer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))

        buildMenu()
    }

    /** Appui sur une carte : coller son texte. */
    fun setOnPasteListener(listener: OnItemListener) {
        pasteListener = listener
    }

    /** Menu d'appui long, « Épingler ». */
    fun setOnPinListener(listener: OnItemListener) {
        pinListener = listener
    }

    /** Menu d'appui long, « Modifier » (story 2.6). */
    fun setOnEditListener(listener: OnItemListener) {
        editListener = listener
    }

    /** Menu d'appui long, « Ajouter une étiquette » ou « Modifier l'étiquette » (stories 2.7 et 2.8). */
    fun setOnLabelListener(listener: OnItemListener) {
        labelListener = listener
    }

    /** Menu d'appui long, « Supprimer l'étiquette », une fois la suppression confirmée (story 2.8). */
    fun setOnDeleteLabelListener(listener: OnItemListener) {
        deleteLabelListener = listener
    }

    /** Menu d'appui long, « Supprimer », une fois la suppression confirmée. */
    fun setOnDeleteListener(listener: OnItemListener) {
        deleteListener = listener
    }

    /** [bottomMarginPx] = zone système en bas. La fermeture se fait par la croix de la barre du haut. */
    fun configure(bottomMarginPx: Int) {
        setHeight(bottomSpacer, bottomMarginPx)
    }

    /** Remplace les cartes affichées (referme le menu d'appui long, dont la carte a pu disparaître). */
    fun setItems(items: List<ClipboardItems.Item>) {
        closeMenu()
        list.removeAllViews()
        emptyMessage.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.chunked(COLUMNS).forEach { rowItems ->
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            repeat(COLUMNS) { column ->
                val item = rowItems.getOrNull(column)
                val view = if (item != null) card(item) else View(context)
                row.addView(
                    view,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                        .apply {
                            val margin = dimen(R.dimen.taipo_clip_card_margin).toInt()
                            setMargins(margin, margin, margin, margin)
                        },
                )
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Referme le menu d'appui long et la confirmation (changement de carte, retour aux touches). */
    fun closeMenu() {
        menuItem = null
        scrim.visibility = View.GONE
        confirmBox.visibility = View.GONE
    }

    private fun card(item: ClipboardItems.Item): View {
        val frame = FrameLayout(context)
        frame.minimumHeight = dimen(R.dimen.taipo_clip_card_min_height).toInt()
        // Face + ombre, comme les touches : l'ombre est sous la face (marge basse de la carte), la carte
        // s'enfonce à l'appui (la vue cliquable relaie l'état « pressed » au fond).
        val shadow = dimen(R.dimen.taipo_key_shadow_height)
        frame.background = PillKeyDrawable(
            faceColor = context.themeColor(if (item.pinned) R.color.clip_item_pinned else R.color.clip_item),
            shadowColor = context.themeColor(if (item.pinned) R.color.clip_item_pinned_shadow else R.color.clip_item_shadow),
            cornerRadius = dimen(R.dimen.taipo_clip_card_corner_radius),
            shadowHeight = shadow,
            pressedShadowHeight = dimen(R.dimen.taipo_key_shadow_pressed_height),
        )
        // Story 2.7 : l'étiquette (pill) est au-dessus du texte, dans la carte, sans le chevaucher ;
        // la marge de droite laisse la place de l'épingle.
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val paddingHorizontal = dimen(R.dimen.taipo_clip_card_padding_horizontal).toInt()
        val paddingVertical = dimen(R.dimen.taipo_clip_card_padding_vertical).toInt()
        val paddingEnd = if (item.pinned) dimen(R.dimen.taipo_clip_card_padding_end_pinned).toInt() else paddingHorizontal
        column.setPadding(paddingHorizontal, paddingVertical, paddingEnd, paddingVertical + shadow.toInt())
        val label = item.label
        if (label != null) {
            val pill = TextView(context)
            pill.text = label
            pill.setTextColor(context.themeColor(R.color.text_primary))
            pill.setTextSizeRes(R.dimen.taipo_clip_label_text_size)
            pill.useTaipoFont(TaipoType.Weight.BOLD)
            pill.maxLines = 1
            pill.ellipsize = TextUtils.TruncateAt.END
            val pillHorizontal = dimen(R.dimen.taipo_clip_label_padding_horizontal).toInt()
            val pillVertical = dimen(R.dimen.taipo_clip_label_padding_vertical).toInt()
            pill.setPadding(pillHorizontal, pillVertical, pillHorizontal, pillVertical)
            pill.background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.taipo_clip_label_corner_radius)
                setColor(context.themeColor(R.color.clip_pin_badge))
            }
            column.addView(
                pill,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dimen(R.dimen.taipo_clip_label_margin_bottom).toInt() },
            )
        }
        val text = TextView(context)
        text.text = ClipboardPreview.forCard(item.text, item.sensitive)
        text.setTextColor(context.themeColor(R.color.text_primary))
        text.setTextSizeRes(R.dimen.taipo_clip_card_text_size)
        text.useTaipoFont()
        text.maxLines = CARD_MAX_LINES
        text.ellipsize = TextUtils.TruncateAt.END
        column.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (item.pinned) {
            val pin = TextView(context)
            pin.text = "\uD83D\uDCCC"
            pin.setTextSizeRes(R.dimen.taipo_clip_pin_text_size)
            pin.contentDescription = context.getString(R.string.clipboard_pinned_description)
            frame.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dimen(R.dimen.taipo_clip_pin_margin_top).toInt(), dimen(R.dimen.taipo_clip_pin_margin_end).toInt(), 0)
            })
        }
        frame.contentDescription = if (item.sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else if (label != null) {
            context.getString(R.string.clipboard_paste_description_labeled, label, ClipboardPreview.oneLine(item.text))
        } else {
            context.getString(R.string.clipboard_paste_description, ClipboardPreview.oneLine(item.text))
        }
        frame.setOnClickListener { pasteListener?.onItem(item) }
        frame.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openMenu(item)
            true
        }
        // Lot 20 : TalkBack annonce un bouton et nomme ses deux actions (coller ; menu de l'appui long), au lieu de « activer ».
        frame.announceAsButton()
        ViewCompat.replaceAccessibilityAction(
            frame, AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.a11y_clipboard_action_paste), null,
        )
        ViewCompat.replaceAccessibilityAction(
            frame, AccessibilityActionCompat.ACTION_LONG_CLICK, context.getString(R.string.a11y_clipboard_action_menu), null,
        )
        return frame
    }

    private fun openMenu(item: ClipboardItems.Item) {
        menuItem = item
        pinEntry.visibility = if (item.pinned) View.GONE else View.VISIBLE
        editEntry.visibility = if (ClipboardItems.canEdit(item)) View.VISIBLE else View.GONE
        val canLabel = ClipboardItems.canLabel(item)
        val hasLabel = item.label != null
        labelEntry.text = context.getString(
            if (hasLabel) R.string.clipboard_menu_label_edit else R.string.clipboard_menu_label_add,
        )
        labelEntry.visibility = if (canLabel) View.VISIBLE else View.GONE
        deleteLabelEntry.visibility = if (canLabel && hasLabel) View.VISIBLE else View.GONE
        confirmBox.visibility = View.GONE
        menuBox.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    /**
     * Stories 2.6 et 2.8 : « Supprimer » et « Supprimer l'étiquette » remplacent les entrées du menu
     * par la demande de confirmation ([forLabel] : celle de l'étiquette).
     */
    private fun askDeleteConfirmation(forLabel: Boolean) {
        confirmingLabelDeletion = forLabel
        confirmQuestion.text = context.getString(
            if (forLabel) R.string.clipboard_delete_label_confirm_message else R.string.clipboard_delete_confirm_message,
        )
        menuBox.visibility = View.GONE
        confirmBox.visibility = View.VISIBLE
    }

    /**
     * Menu d'appui long : un voile qui le referme au toucher, et une boîte centrée avec les entrées ;
     * la boîte de confirmation de suppression (story 2.6) occupe la même place, l'une ou l'autre
     * visible.
     */
    private fun buildMenu() {
        scrim.setBackgroundColor(context.themeColor(R.color.scrim))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeMenu() }

        styleBox(menuBox)
        styleBox(confirmBox)
        styleEntry(pinEntry, context.getString(R.string.clipboard_menu_pin))
        pinEntry.setOnClickListener {
            val item = menuItem
            closeMenu()
            if (item != null) pinListener?.onItem(item)
        }
        styleEntry(editEntry, context.getString(R.string.clipboard_menu_edit))
        editEntry.setOnClickListener {
            val item = menuItem
            closeMenu()
            if (item != null) editListener?.onItem(item)
        }
        styleEntry(deleteEntry, context.getString(R.string.clipboard_menu_delete))
        deleteEntry.setOnClickListener { askDeleteConfirmation(forLabel = false) }
        styleEntry(labelEntry, context.getString(R.string.clipboard_menu_label_add))
        labelEntry.setOnClickListener {
            val item = menuItem
            closeMenu()
            if (item != null) labelListener?.onItem(item)
        }
        styleEntry(deleteLabelEntry, context.getString(R.string.clipboard_menu_label_delete))
        deleteLabelEntry.setOnClickListener { askDeleteConfirmation(forLabel = true) }
        stylePrimaryEntry(pinEntry)
        val entryHeight = dimen(R.dimen.taipo_clip_entry_height).toInt()
        val entryParams = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, entryHeight) }
        // L'action principale (« Épingler ») est un bouton violet : sa boîte de 48 dp garde des marges autour de la face.
        val primaryParams = entryParams().apply {
            setMargins(
                dimen(R.dimen.taipo_clip_primary_margin_horizontal).toInt(),
                dimen(R.dimen.taipo_clip_primary_margin_vertical).toInt(),
                dimen(R.dimen.taipo_clip_primary_margin_horizontal).toInt(),
                dimen(R.dimen.taipo_clip_primary_margin_vertical).toInt(),
            )
        }
        menuBox.addView(pinEntry, primaryParams)
        menuBox.addView(editEntry, entryParams())
        menuBox.addView(labelEntry, entryParams())
        menuBox.addView(deleteLabelEntry, entryParams())
        menuBox.addView(deleteEntry, entryParams())

        confirmQuestion.text = context.getString(R.string.clipboard_delete_confirm_message)
        confirmQuestion.setTextColor(context.themeColor(R.color.text_primary))
        confirmQuestion.setTextSizeRes(R.dimen.taipo_clip_entry_text_size)
        confirmQuestion.useTaipoFont()
        val entryPadding = dimen(R.dimen.taipo_clip_entry_padding_horizontal).toInt()
        confirmQuestion.setPadding(
            entryPadding,
            dimen(R.dimen.taipo_clip_confirm_padding_top).toInt(),
            entryPadding,
            dimen(R.dimen.taipo_clip_confirm_padding_bottom).toInt(),
        )
        val cancelEntry = TextView(context)
        styleEntry(cancelEntry, context.getString(R.string.clipboard_delete_confirm_cancel))
        cancelEntry.gravity = Gravity.CENTER
        cancelEntry.setOnClickListener { closeMenu() }
        val confirmEntry = TextView(context)
        styleEntry(confirmEntry, context.getString(R.string.clipboard_delete_confirm_ok))
        confirmEntry.gravity = Gravity.CENTER
        confirmEntry.setTextColor(context.themeColor(R.color.clip_danger_text))
        confirmEntry.setOnClickListener {
            val item = menuItem
            val forLabel = confirmingLabelDeletion
            closeMenu()
            if (item != null) (if (forLabel) deleteLabelListener else deleteListener)?.onItem(item)
        }
        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.addView(cancelEntry, LinearLayout.LayoutParams(0, entryHeight, 1f))
        buttons.addView(confirmEntry, LinearLayout.LayoutParams(0, entryHeight, 1f))
        confirmBox.addView(confirmQuestion, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        confirmBox.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        confirmBox.visibility = View.GONE

        // Une seule boîte est visible à la fois : elles partagent la même place au centre du voile.
        scrim.addView(menuBox, LayoutParams(dimen(R.dimen.taipo_clip_menu_width).toInt(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        scrim.addView(confirmBox, LayoutParams(dimen(R.dimen.taipo_clip_confirm_width).toInt(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun styleBox(box: LinearLayout) {
        box.orientation = LinearLayout.VERTICAL
        box.isClickable = true // un toucher dans la boîte ne referme pas le menu
        box.background = GradientDrawable().apply {
            cornerRadius = dimen(R.dimen.taipo_clip_dialog_corner_radius)
            setColor(context.themeColor(R.color.clip_dialog))
        }
    }

    private fun styleEntry(entry: TextView, label: String) {
        entry.text = label
        entry.setTextColor(context.themeColor(R.color.text_primary))
        entry.setTextSizeRes(R.dimen.taipo_clip_entry_text_size)
        entry.useTaipoFont(TaipoType.Weight.BOLD)
        entry.gravity = Gravity.CENTER_VERTICAL
        val padding = dimen(R.dimen.taipo_clip_entry_padding_horizontal).toInt()
        entry.setPadding(padding, 0, padding, 0)
    }

    /**
     * Action principale du menu : bouton violet « face + ombre » (même géométrie que les touches, enfoncé à
     * l'appui), texte blanc centré sur la face, au-dessus de l'épaisseur d'ombre.
     */
    private fun stylePrimaryEntry(entry: TextView) {
        val shadow = dimen(R.dimen.taipo_key_shadow_height)
        entry.gravity = Gravity.CENTER
        entry.setPadding(0, 0, 0, shadow.toInt())
        entry.background = PillKeyDrawable(
            faceColor = context.themeColor(R.color.clip_action_primary),
            shadowColor = context.themeColor(R.color.clip_action_primary_shadow),
            cornerRadius = dimen(R.dimen.taipo_key_corner_radius),
            shadowHeight = shadow,
            pressedShadowHeight = dimen(R.dimen.taipo_key_shadow_pressed_height),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Story 1.14 : en classe de largeur large, le contenu est plafonné et centré comme les touches.
        val widthContent = KeyboardWidth.forAvailableWidth(w.toFloat(), resources.displayMetrics.density)
        val pad = widthContent.leftPx.toInt()
        if (content.paddingLeft != pad || content.paddingRight != pad) content.setPadding(pad, 0, pad, 0)
    }

    private fun setHeight(view: View, heightPx: Int) {
        val params = view.layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            view.layoutParams = params
        }
    }

    companion object {
        private const val COLUMNS = 2
        private const val CARD_MAX_LINES = 3
    }
}
