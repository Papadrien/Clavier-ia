package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.ai.Tone

/**
 * Panneau IA (20/11/2026) : remplace les touches, comme le panneau emoji, et regroupe toutes les actions IA du clavier (Corriger,
 * Ton, Traduire, Chat, Transcription IA ; Vocal, la dictée, reste dans la barre). Un appui lance l'action, un appui long
 * l'épingle dans la barre du haut (ou l'en retire) ; une punaise marque les actions épinglées. Une bulle d'astuce le rappelle
 * tant que rien n'est épinglé (et les premières fois).
 *
 * Deuxième page, « Changer le ton » : les tons ([Tone]) en puces ; un appui réécrit le texte du champ dans ce ton (le contrôleur
 * s'en charge), « Rétablir » remet le texte d'origine. La rangée du bas porte « ABC » (retour aux touches), comme le panneau emoji.
 *
 * Vue sans logique d'action : tout passe par [Listener] ([AiPanelController]).
 */
@SuppressLint("ViewConstructor")
internal class AiPanelView(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onActionTapped(action: AiAction)

        fun onActionLongPressed(action: AiAction)

        fun onToneTapped(tone: Tone)

        fun onRestoreOriginal()

        fun onBackToActions()

        fun onClose()
    }

    var listener: Listener? = null

    private val tip = TextView(context)
    private val actionsPage = LinearLayout(context)
    private val tonePage = LinearLayout(context)
    private val toneStatus = TextView(context)
    private val restoreButton = TextView(context)
    private val toneChips = HashMap<Tone, TextView>()
    private val tiles = HashMap<AiAction, Tile>()
    private val bottomBar = LinearLayout(context)
    private val bottomSpacer = View(context)
    private val abcKey = TextView(context)

    private val keyInset = dimen(R.dimen.taipo_key_inset).toInt()
    private val gap = dimen(R.dimen.taipo_bar_gap).toInt()

    /** Une case du panneau : icône, libellé et punaise (action épinglée). */
    private class Tile(val root: FrameLayout, val icon: ImageView, val label: TextView, val pin: ImageView)

    init {
        orientation = VERTICAL
        // Fond transparent : le panneau laisse voir le fond commun du clavier.
        tip.apply {
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont()
            gravity = Gravity.CENTER
            val pad = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                cornerRadius = dimen(R.dimen.taipo_key_corner_radius)
                setColor(context.themeColor(R.color.accent) and TIP_ALPHA_MASK or TIP_ALPHA)
            }
            text = context.getString(R.string.ai_panel_pin_tip, AiPins.MAX)
            visibility = View.GONE
        }
        addView(tip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(gap, gap, gap, 0) })

        actionsPage.orientation = VERTICAL
        val rows = AiAction.entries.chunked(COLUMNS)
        for (row in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (action in row) {
                val tile = makeTile(action)
                tiles[action] = tile
                line.addView(tile.root, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(keyInset, keyInset, keyInset, keyInset) })
            }
            // Dernière rangée incomplète : des cases vides gardent la même largeur aux autres.
            repeat(COLUMNS - row.size) { line.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
            actionsPage.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
        addView(actionsPage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        buildTonePage()
        addView(tonePage, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        tonePage.visibility = View.GONE

        bottomBar.orientation = HORIZONTAL
        abcKey.apply {
            text = "ABC"
            setTextColor(context.themeColor(R.color.text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, KeyRenderer.toggleLabelTextSize(dimen(R.dimen.taipo_key_text_size_large)))
            useTaipoFont()
            gravity = Gravity.CENTER
            maxLines = 1
            styleKey(this)
            contentDescription = context.getString(R.string.a11y_emoji_back_to_keyboard)
            announceAsButton()
            setOnClickListener { listener?.onClose() }
        }
        bottomBar.addView(abcKey, keyParams(ABC_WEIGHT))
        bottomBar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, BOTTOM_SPACER_WEIGHT))
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, dimen(R.dimen.taipo_emoji_bottom_bar_height).toInt()))
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))
    }

    private fun makeTile(action: AiAction): Tile {
        val root = FrameLayout(context)
        styleKey(root)
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(context).apply {
            setImageResource(iconOf(action))
            imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        }
        val size = dimen(R.dimen.taipo_icon_size).toInt()
        column.addView(icon, LayoutParams(size, size))
        val label = TextView(context).apply {
            setText(labelOf(action))
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont()
            gravity = Gravity.CENTER
            maxLines = 1
        }
        column.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap / 2 })
        root.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val pin = ImageView(context).apply {
            setImageResource(R.drawable.ic_pin)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.color.taipo_purple_light))
            visibility = View.GONE
        }
        val pinSize = dimen(R.dimen.taipo_ai_panel_pin_size).toInt()
        root.addView(pin, FrameLayout.LayoutParams(pinSize, pinSize, Gravity.TOP or Gravity.END).apply { setMargins(gap, gap, gap, gap) })
        root.setOnClickListener { listener?.onActionTapped(action) }
        root.setOnLongClickListener {
            listener?.onActionLongPressed(action)
            true
        }
        root.announceAsButton()
        return Tile(root, icon, label, pin)
    }

    private fun buildTonePage() {
        tonePage.orientation = VERTICAL
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ImageView(context).apply {
            setImageResource(R.drawable.ic_arrow_back)
            imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.ai_panel_back)
            announceAsButton()
            setOnClickListener { listener?.onBackToActions() }
        }
        header.addView(back, LayoutParams(dimen(R.dimen.taipo_bar_icon_button_width).toInt(), LayoutParams.MATCH_PARENT))
        toneStatus.apply {
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont(TaipoType.Weight.MEDIUM)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(toneStatus, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        restoreButton.apply {
            setText(R.string.ai_tone_restore)
            setTextColor(context.themeColor(R.color.text_primary))
            setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
            useTaipoFont()
            gravity = Gravity.CENTER
            val pad = dimen(R.dimen.taipo_bar_padding_horizontal).toInt()
            setPadding(pad, 0, pad, 0)
            background = chipBackground(selected = false)
            announceAsButton()
            setOnClickListener { listener?.onRestoreOriginal() }
            visibility = View.GONE
        }
        header.addView(restoreButton, LayoutParams(LayoutParams.WRAP_CONTENT, dimen(R.dimen.taipo_ai_panel_chip_height).toInt()).apply { marginEnd = gap })
        tonePage.addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dimen(R.dimen.taipo_emoji_tabs_height).toInt()))

        // Tons en grille de deux colonnes : chaque puce garde un libellé lisible, même en grande police.
        for (row in Tone.entries.chunked(TONE_COLUMNS)) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (tone in row) {
                val chip = TextView(context).apply {
                    setText(toneLabel(tone))
                    setTextColor(context.themeColor(R.color.text_primary))
                    setFixedTextSizeRes(R.dimen.taipo_suggestion_chip_text_size)
                    useTaipoFont()
                    gravity = Gravity.CENTER
                    maxLines = 1
                    background = chipBackground(selected = false)
                    announceAsButton()
                    setOnClickListener { listener?.onToneTapped(tone) }
                }
                toneChips[tone] = chip
                line.addView(chip, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { setMargins(keyInset, keyInset, keyInset, keyInset) })
            }
            tonePage.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun chipBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dimen(R.dimen.taipo_ai_panel_chip_height) / 2f
        setColor(context.themeColor(if (selected) R.color.accent else R.color.key_functional))
    }

    /** Hauteurs calées sur le clavier remplacé : rangée du bas et marge basse (zone système), comme le panneau emoji. */
    fun configure(rowHeightPx: Int, bottomMarginPx: Int) {
        setHeight(bottomBar, (rowHeightPx * BOTTOM_ROW_RATIO).toInt())
        setHeight(bottomSpacer, bottomMarginPx)
    }

    /**
     * Met à jour la page des actions : actions épinglées ([pins], punaise), indisponibles ([unavailable] : grisées, l'appui
     * explique pourquoi) et bulle d'astuce ([showTip]).
     */
    fun renderActions(pins: List<AiAction>, unavailable: Set<AiAction>, showTip: Boolean) {
        for ((action, tile) in tiles) {
            val pinned = action in pins
            tile.pin.visibility = if (pinned) View.VISIBLE else View.GONE
            tile.root.alpha = if (action in unavailable) RESTRICTED_ALPHA else 1f
            val label = context.getString(labelOf(action))
            tile.root.contentDescription = if (pinned) context.getString(R.string.ai_panel_tile_pinned, label) else label
        }
        tip.visibility = if (showTip) View.VISIBLE else View.GONE
    }

    /** Page affichée : les actions, ou les tons. */
    fun showTonePage(shown: Boolean) {
        actionsPage.visibility = if (shown) View.GONE else View.VISIBLE
        tonePage.visibility = if (shown) View.VISIBLE else View.GONE
        if (shown) tip.visibility = View.GONE
    }

    val tonePageShown: Boolean get() = tonePage.visibility == View.VISIBLE

    /**
     * Page des tons : [selected] (ton appliqué en dernier, violet), [status] (ligne d'état : consigne, réécriture en cours, ton
     * appliqué) et bouton « Rétablir » ([canRestore]). [busy] : réécriture en cours, les puces sont grisées.
     */
    fun renderTones(selected: Tone?, status: String, canRestore: Boolean, busy: Boolean) {
        for ((tone, chip) in toneChips) {
            chip.background = chipBackground(tone == selected)
            chip.setTextColor(context.themeColor(if (tone == selected) R.color.text_on_accent else R.color.text_primary))
            chip.alpha = if (busy && tone != selected) RESTRICTED_ALPHA else 1f
            chip.isEnabled = !busy
        }
        toneStatus.text = status
        restoreButton.visibility = if (canRestore && !busy) View.VISIBLE else View.GONE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // En classe de largeur large, le contenu est plafonné et centré comme les touches.
        val content = KeyboardWidth.forAvailableWidth(w.toFloat(), resources.displayMetrics.density)
        val pad = content.leftPx.toInt()
        if (paddingLeft != pad || paddingRight != pad) setPadding(pad, 0, pad, 0)
    }

    private fun styleKey(key: View) {
        val shadow = dimen(R.dimen.taipo_key_shadow_height)
        key.background = PillKeyDrawable(
            faceColor = context.themeColor(R.color.key_functional),
            shadowColor = context.themeColor(R.color.key_functional_shadow),
            cornerRadius = dimen(R.dimen.taipo_key_corner_radius),
            shadowHeight = shadow,
            pressedShadowHeight = dimen(R.dimen.taipo_key_shadow_pressed_height),
        )
        key.setPadding(0, 0, 0, shadow.toInt())
    }

    private fun keyParams(weight: Float) = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
        setMargins(keyInset, keyInset, keyInset, keyInset)
    }

    private fun setHeight(view: View, heightPx: Int) {
        val params = view.layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            view.layoutParams = params
        }
    }

    companion object {
        private const val COLUMNS = 3
        private const val TONE_COLUMNS = 2
        private const val BOTTOM_ROW_RATIO = 0.85f
        private const val ABC_WEIGHT = 1.4f
        private const val BOTTOM_SPACER_WEIGHT = 8.6f
        private const val TIP_ALPHA_MASK = 0x00FFFFFF
        private const val TIP_ALPHA = 0x40000000

        fun iconOf(action: AiAction): Int = when (action) {
            AiAction.CORRECT -> R.drawable.ic_spellcheck
            AiAction.TONE -> R.drawable.ic_tone
            AiAction.TRANSLATE -> R.drawable.ic_translate
            AiAction.CHAT -> R.drawable.ic_rate_review
            AiAction.TRANSCRIPTION -> R.drawable.ic_speech_to_text
        }

        fun labelOf(action: AiAction): Int = when (action) {
            AiAction.CORRECT -> R.string.action_pill_correction
            AiAction.TONE -> R.string.ai_action_tone
            AiAction.TRANSLATE -> R.string.ai_action_translate
            AiAction.CHAT -> R.string.action_pill_chat
            AiAction.TRANSCRIPTION -> R.string.action_pill_transcription
        }

        fun toneLabel(tone: Tone): Int = when (tone) {
            Tone.PROFESSIONAL -> R.string.tone_professional
            Tone.FRIENDLY -> R.string.tone_friendly
            Tone.CASUAL -> R.string.tone_casual
            Tone.POLITE -> R.string.tone_polite
            Tone.CONCISE -> R.string.tone_concise
            Tone.CONFIDENT -> R.string.tone_confident
            Tone.EMPATHETIC -> R.string.tone_empathetic
            Tone.SOCIAL -> R.string.tone_social
        }
    }
}
