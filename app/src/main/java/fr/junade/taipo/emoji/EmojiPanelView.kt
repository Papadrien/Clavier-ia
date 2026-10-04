package fr.junade.taipo.emoji

import fr.junade.taipo.useTaipoFont
import fr.junade.taipo.themeColor
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.KeyboardWidth
import fr.junade.taipo.R

/**
 * Story 1.15 : panneau emoji, qui remplace les touches quand on appuie sur le bouton emoji (à
 * gauche de la barre espace, à droite de la virgule, comme sur Gboard). De haut en bas : onglets des
 * catégories, grille défilante, puis une rangée avec « ABC » (retour au clavier) et retour arrière.
 * Sans GIF, sans stickers, sans emoji ASCII (décision du projet).
 *
 * La hauteur totale est fixée par l'appelant (celle du clavier remplacé) : le clavier ne change donc
 * pas de taille en basculant. La marge basse (zone système) est conservée sous la rangée du bas.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(context: Context) : LinearLayout(context) {

    fun interface OnBackspaceListener {
        fun onBackspace()
    }

    fun interface OnCloseListener {
        fun onClose()
    }

    private val tabs = EmojiTabsView(context)
    private val grid = EmojiGridView(context)
    private val bottomBar = LinearLayout(context)
    private val bottomSpacer = View(context)
    private val abcKey = makeKey("ABC", 18f)
    private val backspaceKey = makeKey("\u232B", 24f)

    private var closeListener: OnCloseListener? = null
    private var backspaceListener: OnBackspaceListener? = null
    private var sectionIds: List<EmojiCategoryId> = emptyList()

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeating = false
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeating = true
            backspaceListener?.onBackspace()
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        orientation = VERTICAL
        // Fond transparent : le panneau laisse voir le fond commun du clavier (KeyboardBackgroundDrawable).

        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, dp(36f)))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        bottomBar.orientation = HORIZONTAL
        bottomBar.addView(abcKey, keyParams(1.4f))
        bottomBar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 7f))
        bottomBar.addView(backspaceKey, keyParams(1.6f))
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))

        tabs.setOnTabSelectedListener { index ->
            tabs.selectedIndex = index
            grid.showSection(index, animate = true)
        }
        grid.setOnSectionChangedListener { index -> tabs.selectedIndex = index }

        setupTapKey(abcKey) { closeListener?.onClose() }
        setupBackspaceKey()
    }

    fun setOnEmojiSelectedListener(listener: EmojiGridView.OnEmojiSelectedListener) {
        grid.setOnEmojiSelectedListener(listener)
    }

    fun setOnBackspaceListener(listener: OnBackspaceListener) {
        backspaceListener = listener
    }

    fun setOnCloseListener(listener: OnCloseListener) {
        closeListener = listener
    }

    /**
     * Adapte la rangée du bas et la marge basse à celles du clavier : [rowHeightPx] est la hauteur
     * d'une rangée de touches, [bottomMarginPx] la marge basse réservée à la zone système.
     */
    fun configure(rowHeightPx: Int, bottomMarginPx: Int) {
        setHeight(tabs, (rowHeightPx * TABS_ROW_RATIO).toInt())
        setHeight(bottomBar, (rowHeightPx * BOTTOM_ROW_RATIO).toInt())
        setHeight(bottomSpacer, bottomMarginPx)
    }

    /**
     * Affiche le panneau avec [recents] (du plus récent au plus ancien). Il s'ouvre sur « Récents »
     * s'il y en a, sinon sur « Smileys et émotions ».
     */
    fun show(recents: List<String>) {
        val catalog = EmojiCatalog.load(context)
        val sections = mutableListOf(
            EmojiSection(
                id = EmojiCategoryId.RECENT,
                title = titleFor(EmojiCategoryId.RECENT),
                emojis = recents,
                emptyMessage = context.getString(R.string.emoji_no_recent),
            ),
        )
        catalog.forEach { category ->
            sections += EmojiSection(category.id, titleFor(category.id), category.emojis)
        }
        sectionIds = sections.map { it.id }
        tabs.setTabs(sectionIds.map { it.tabIcon })
        grid.setSections(sections)
        val start = if (recents.isEmpty()) 1 else 0
        val index = start.coerceAtMost(sections.lastIndex)
        tabs.selectedIndex = index
        grid.showSection(index, animate = false)
    }

    private fun titleFor(id: EmojiCategoryId): String = context.getString(
        when (id) {
            EmojiCategoryId.RECENT -> R.string.emoji_category_recent
            EmojiCategoryId.SMILEYS -> R.string.emoji_category_smileys
            EmojiCategoryId.PEOPLE -> R.string.emoji_category_people
            EmojiCategoryId.ANIMALS -> R.string.emoji_category_animals
            EmojiCategoryId.FOOD -> R.string.emoji_category_food
            EmojiCategoryId.ACTIVITIES -> R.string.emoji_category_activities
            EmojiCategoryId.TRAVEL -> R.string.emoji_category_travel
            EmojiCategoryId.OBJECTS -> R.string.emoji_category_objects
            EmojiCategoryId.SYMBOLS -> R.string.emoji_category_symbols
            EmojiCategoryId.FLAGS -> R.string.emoji_category_flags
        },
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Story 1.14 : en classe de largeur large, le contenu est plafonné et centré comme les touches.
        val content = KeyboardWidth.forAvailableWidth(w.toFloat(), resources.displayMetrics.density)
        val pad = content.leftPx.toInt()
        if (paddingLeft != pad || paddingRight != pad) setPadding(pad, 0, pad, 0)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    // --- Touches de la rangée du bas ------------------------------------------------------------

    private fun makeKey(label: String, textSizeSp: Float): TextView = TextView(context).apply {
        text = label
        setTextColor(context.themeColor(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        useTaipoFont()
        gravity = Gravity.CENTER
        background = keyBackground(pressed = false)
    }

    private fun keyBackground(pressed: Boolean) = GradientDrawable().apply {
        setColor(context.themeColor(if (pressed) R.color.key_pressed else R.color.key_functional))
        cornerRadius = dp(8f).toFloat()
    }

    private fun setKeyPressed(key: View, pressed: Boolean) {
        key.background = keyBackground(pressed)
    }

    private fun keyParams(weight: Float) = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
        val margin = dp(3f)
        setMargins(margin, margin, margin, margin)
    }

    private fun isInside(view: View, event: MotionEvent): Boolean =
        event.x in 0f..view.width.toFloat() && event.y in 0f..view.height.toFloat()

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapKey(key: View, onTap: () -> Unit) {
        key.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> setKeyPressed(view, true)
                MotionEvent.ACTION_UP -> {
                    setKeyPressed(view, false)
                    if (isInside(view, event)) onTap()
                }
                MotionEvent.ACTION_CANCEL -> setKeyPressed(view, false)
            }
            true
        }
    }

    /** Retour arrière : un appui bref supprime un emoji ; maintenu, il répète comme la touche du clavier. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupBackspaceKey() {
        backspaceKey.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    repeating = false
                    setKeyPressed(view, true)
                    repeatHandler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP -> {
                    repeatHandler.removeCallbacks(repeatRunnable)
                    setKeyPressed(view, false)
                    if (!repeating && isInside(view, event)) backspaceListener?.onBackspace()
                    repeating = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    repeatHandler.removeCallbacks(repeatRunnable)
                    setKeyPressed(view, false)
                    repeating = false
                }
            }
            true
        }
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
        /** Hauteur de la rangée d'onglets et de la rangée du bas, par rapport à une rangée de touches. */
        private const val TABS_ROW_RATIO = 0.7f
        private const val BOTTOM_ROW_RATIO = 0.85f

        // Mêmes délais que la répétition du retour arrière du clavier (KeyboardView).
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
    }
}
