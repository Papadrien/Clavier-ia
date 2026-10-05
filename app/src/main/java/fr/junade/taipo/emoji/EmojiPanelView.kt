package fr.junade.taipo.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import fr.junade.taipo.KeyboardWidth
import fr.junade.taipo.PillKeyDrawable
import fr.junade.taipo.R
import fr.junade.taipo.dimen
import fr.junade.taipo.announceAsButton
import fr.junade.taipo.setFixedTextSizeRes
import fr.junade.taipo.themeColor
import fr.junade.taipo.useTaipoFont

/**
 * Story 1.15 : panneau emoji, qui remplace les touches quand on appuie sur le bouton emoji (à
 * gauche de la barre espace, à droite de la virgule, comme sur Gboard). De haut en bas : onglets des
 * catégories, grille défilante, puis une rangée avec « ABC » (retour au clavier) et retour arrière.
 * Sans GIF, sans stickers, sans emoji ASCII (décision du projet).
 *
 * La hauteur totale est fixée par l'appelant (celle du clavier remplacé) : le clavier ne change donc
 * pas de taille en basculant. La marge basse (zone système) est conservée sous la rangée du bas.
 *
 * Lot 14 (refonte graphique) : le fond est celui de tout le clavier (#0E0E0E, KeyboardBackgroundDrawable) ;
 * « ABC » et retour arrière sont des touches secondaires (#2B2B2B) à ombre, comme celles du clavier (même
 * rayon, mêmes marges, même enfoncement à l'appui) ; l'onglet de la catégorie courante est violet
 * ([EmojiTabsView]). Les emojis restent des glyphes de texte, aucune image. Comportement inchangé.
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
    private val abcKey = makeTextKey("ABC")
    private val backspaceKey = makeIconKey(R.drawable.ic_key_backspace)

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
        // Fond transparent : le panneau laisse voir le fond commun du clavier (KeyboardBackgroundDrawable, #0E0E0E).

        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, dimen(R.dimen.taipo_emoji_tabs_height).toInt()))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        bottomBar.orientation = HORIZONTAL
        bottomBar.addView(abcKey, keyParams(1.4f))
        bottomBar.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 7f))
        bottomBar.addView(backspaceKey, keyParams(1.6f))
        addView(bottomBar, LayoutParams(LayoutParams.MATCH_PARENT, dimen(R.dimen.taipo_emoji_bottom_bar_height).toInt()))
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, 0))

        tabs.setOnTabSelectedListener { index ->
            tabs.selectedIndex = index
            grid.showSection(index, animate = true)
        }
        grid.setOnSectionChangedListener { index -> tabs.selectedIndex = index }

        setupTapKey(abcKey) { closeListener?.onClose() }
        setupBackspaceKey()
        setupAccessibility()
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
        tabs.setTabs(sectionIds.map { it.tabIcon }, sections.map { it.title })
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

    /** Touche secondaire à libellé (Open Sans, blanc), sur une face #2B2B2B à ombre. */
    private fun makeTextKey(label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(context.themeColor(R.color.text_primary))
        setFixedTextSizeRes(R.dimen.taipo_emoji_key_text_size)
        useTaipoFont()
        gravity = Gravity.CENTER
        styleKey(this)
    }

    /** Touche secondaire à icône blanche (l'icône du clavier, lot 09), sur une face #2B2B2B à ombre. */
    private fun makeIconKey(iconRes: Int): ImageView = ImageView(context).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(context.themeColor(R.color.text_primary))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        styleKey(this)
    }

    /**
     * Fond « face + ombre » des touches secondaires (même géométrie que le clavier) ; le contenu se centre sur
     * la face, au-dessus de l'épaisseur d'ombre (marge basse = ombre au repos). L'enfoncement à l'appui suit
     * l'état `pressed` de la vue ([setKeyPressed]).
     */
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

    private fun setKeyPressed(key: View, pressed: Boolean) {
        key.isPressed = pressed
    }

    private fun keyParams(weight: Float) = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).apply {
        val margin = dimen(R.dimen.taipo_key_inset).toInt()
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

    /**
     * Lot 20 : les deux touches du bas gèrent leurs gestes par un écouteur tactile, qui consomme tout : sans écouteur de clic,
     * TalkBack ne peut pas les activer (double appui). Le clic n'est jamais déclenché par un toucher ordinaire (l'écouteur
     * tactile le consomme avant), seulement par l'accessibilité, sans double saisie.
     */
    private fun setupAccessibility() {
        abcKey.contentDescription = context.getString(R.string.a11y_emoji_back_to_keyboard)
        abcKey.announceAsButton()
        abcKey.setOnClickListener { closeListener?.onClose() }
        backspaceKey.contentDescription = context.getString(R.string.a11y_key_backspace)
        backspaceKey.announceAsButton()
        backspaceKey.setOnClickListener { backspaceListener?.onBackspace() }
    }

    private fun setHeight(view: View, heightPx: Int) {
        val params = view.layoutParams ?: return
        if (params.height != heightPx) {
            params.height = heightPx
            view.layoutParams = params
        }
    }


    companion object {
        /** Hauteur de la rangée d'onglets et de la rangée du bas, par rapport à une rangée de touches. */
        private const val TABS_ROW_RATIO = 0.7f
        private const val BOTTOM_ROW_RATIO = 0.85f

        // Mêmes délais que la répétition du retour arrière du clavier (KeyboardView).
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 60L
    }
}
