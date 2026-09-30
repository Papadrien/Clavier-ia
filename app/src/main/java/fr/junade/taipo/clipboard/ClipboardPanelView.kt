package fr.junade.taipo.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import fr.junade.taipo.KeyboardWidth
import fr.junade.taipo.R

/**
 * Stories 2.1 et 2.5 : panneau Smart Clipboard, qui remplace les touches quand on appuie sur le
 * bouton « Presse-papiers » de la barre (même principe que le panneau emoji de la story 1.15). Il n'a pas de bouton « ABC » : on le ferme par
 * la croix de la barre du haut.
 *
 * Il affiche les éléments en cartes (2 par rangée, aperçu sur 3 lignes, masqué si le contenu est
 * sensible) : la dernière copie puis les éléments épinglés, marqués d'une épingle. Un appui sur une
 * carte la colle ; un appui long ouvre un petit menu dessiné par-dessus le panneau (et non une
 * fenêtre popup, capricieuse dans la fenêtre d'un clavier) : « Épingler » et « Supprimer » pour
 * une carte non épinglée, « Supprimer » seul pour une carte épinglée. Sans élément, un message
 * indique qu'il n'y a rien à coller.
 *
 * La hauteur est fixée par l'appelant (celle du clavier remplacé) : le clavier ne change pas de
 * taille en basculant.
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
    private val pinEntry = TextView(context)
    private val deleteEntry = TextView(context)

    private var menuItem: ClipboardItems.Item? = null
    private var pasteListener: OnItemListener? = null
    private var pinListener: OnItemListener? = null
    private var deleteListener: OnItemListener? = null

    init {
        setBackgroundColor(Color.BLACK)

        content.orientation = LinearLayout.VERTICAL
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val listArea = FrameLayout(context)
        val scroll = ScrollView(context)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        listArea.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        emptyMessage.text = context.getString(R.string.clipboard_empty)
        emptyMessage.setTextColor(Color.parseColor("#9AA0A6"))
        emptyMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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

    /** Menu d'appui long, « Supprimer ». */
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
                        .apply { setMargins(dp(4f), dp(4f), dp(4f), dp(4f)) },
                )
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** Referme le menu d'appui long (changement de carte, retour aux touches). */
    fun closeMenu() {
        menuItem = null
        scrim.visibility = View.GONE
    }

    private fun card(item: ClipboardItems.Item): View {
        val frame = FrameLayout(context)
        frame.minimumHeight = dp(64f)
        frame.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(Color.parseColor(if (item.pinned) "#22304A" else "#1E2025"))
        }
        val text = TextView(context)
        text.text = ClipboardPreview.forCard(item.text, item.sensitive)
        text.setTextColor(Color.WHITE)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        text.maxLines = CARD_MAX_LINES
        text.ellipsize = TextUtils.TruncateAt.END
        text.setPadding(dp(12f), dp(10f), dp(if (item.pinned) 28f else 12f), dp(10f))
        frame.addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (item.pinned) {
            val pin = TextView(context)
            pin.text = "\uD83D\uDCCC"
            pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            pin.contentDescription = context.getString(R.string.clipboard_pinned_description)
            frame.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6f), dp(8f), 0)
            })
        }
        frame.contentDescription = if (item.sensitive) {
            context.getString(R.string.clipboard_paste_description_masked)
        } else {
            context.getString(R.string.clipboard_paste_description, ClipboardPreview.oneLine(item.text))
        }
        frame.setOnClickListener { pasteListener?.onItem(item) }
        frame.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            openMenu(item)
            true
        }
        return frame
    }

    private fun openMenu(item: ClipboardItems.Item) {
        menuItem = item
        pinEntry.visibility = if (item.pinned) View.GONE else View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    /** Menu d'appui long : un voile qui le referme au toucher, et une boîte centrée avec les entrées. */
    private fun buildMenu() {
        scrim.setBackgroundColor(Color.parseColor("#99000000"))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeMenu() }

        val box = LinearLayout(context)
        box.orientation = LinearLayout.VERTICAL
        box.isClickable = true // un toucher dans la boîte ne referme pas le menu
        box.background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(Color.parseColor("#2B2F36"))
        }
        styleEntry(pinEntry, context.getString(R.string.clipboard_menu_pin))
        pinEntry.setOnClickListener {
            val item = menuItem
            closeMenu()
            if (item != null) pinListener?.onItem(item)
        }
        styleEntry(deleteEntry, context.getString(R.string.clipboard_menu_delete))
        deleteEntry.setOnClickListener {
            val item = menuItem
            closeMenu()
            if (item != null) deleteListener?.onItem(item)
        }
        box.addView(pinEntry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)))
        box.addView(deleteEntry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48f)))
        scrim.addView(box, LayoutParams(dp(220f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun styleEntry(entry: TextView, label: String) {
        entry.text = label
        entry.setTextColor(Color.WHITE)
        entry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        entry.setTypeface(entry.typeface, Typeface.BOLD)
        entry.gravity = Gravity.CENTER_VERTICAL
        entry.setPadding(dp(18f), 0, dp(18f), 0)
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

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLUMNS = 2
        private const val CARD_MAX_LINES = 3
    }
}
