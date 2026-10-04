package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : View(context) {

    fun interface OnKeyListener {
        fun onKey(key: Key)
    }

    /** Story 1.7 : déplacement du curseur par glissement sur la barre espace (négatif = gauche). */
    fun interface OnCursorMoveListener {
        fun onCursorMove(steps: Int)
    }

    /**
     * Story 1.9 : glissement vers la gauche depuis la touche retour arrière. Le texte n'est pas
     * supprimé pendant le glissement : il est surligné, et la suppression n'a lieu qu'au relâchement.
     */
    interface OnDeleteSwipeListener {
        /** Nombre total de mots à surligner avant le curseur de départ (0 = rien ; peut diminuer). */
        fun onDeleteSwipeUpdate(words: Int)

        /** Doigt relâché : supprime la zone surlignée. */
        fun onDeleteSwipeRelease()

        /** Geste interrompu par le système : retire le surlignage sans rien supprimer. */
        fun onDeleteSwipeCancel()
    }

    private var keyListener: OnKeyListener? = null
    private var cursorMoveListener: OnCursorMoveListener? = null
    private var deleteSwipeListener: OnDeleteSwipeListener? = null
    private var deleteSwipeWords = 0
    private var pressedKey: Key? = null

    var layout: KeyboardLayout = Keyboards.letters
        set(value) {
            field = value
            invalidate()
            requestLayout()
        }

    /** Story 1.12 : coefficient appliqué à la hauteur des rangées (1 = hauteur de référence). */
    var heightScale: Float = KeyboardHeight.DEFAULT.scale
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    // Story 1.13 : orientation courante, qui détermine les dimensions verticales (KeyboardMetrics).
    private var isLandscape: Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun metrics(): KeyboardMetrics = KeyboardMetrics.forOrientation(isLandscape)

    var isShifted: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** Verrouillage des majuscules (double appui sur Maj) : une barre est dessinée sous la flèche. */
    var isCapsLock: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Charte Taipo (lot 04) : chaque touche = une face + une ombre (épaisseur inférieure), voir drawKey.
    // Touches normales et spéciales (lettres, chiffres, espace, Maj, retour arrière, ABC/123) : violet-gris ;
    // touches secondaires (virgule, point, emoji, pavé numérique) : gris ; Entrée : violet d'accent.
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_normal) }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_functional) }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_pressed) }
    private val enterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_enter) }
    private val normalShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_normal_shadow) }
    private val secondaryShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_functional_shadow) }
    private val enterShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_enter_shadow) }
    private val spaceBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.key_space_bar)
        strokeWidth = context.dimen(R.dimen.taipo_space_bar_thickness)
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    private val keyRect = RectF()

    // Icônes des touches (lot 09) : VectorDrawable de la charte, teintés une fois, redimensionnés au dessin (aucune
    // allocation dans onDraw). Remplacent les glyphes de police (Maj, Effacer) et les tracés Canvas (Entrée, Emoji).
    private val shiftIcon = loadKeyIcon(R.drawable.ic_key_shift)
    private val shiftCapsIcon = loadKeyIcon(R.drawable.ic_key_shift_caps)
    private val backspaceIcon = loadKeyIcon(R.drawable.ic_key_backspace)
    private val enterIcon = loadKeyIcon(R.drawable.ic_key_enter)
    private val emojiIcon = loadKeyIcon(R.drawable.ic_key_emoji)

    // Appui long : bulle de symboles (grille) affichée au-dessus de la touche.
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_popup) }
    private val popupSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_popup_selection) }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context, TaipoType.Weight.BOLD)
    }
    private val popupRect = RectF()
    private var popupKey: Key? = null
    private var popupSelectedRow = -1
    private var popupSelectedCol = -1
    private var popupCellWidth = 0f
    private var popupCellHeight = 0f
    private var popupPadding = 0f
    private var popupPaddingV = 0f

    // Cellule sélectionnée d'office à l'ouverture (Key.defaultPopupChar) et zone « de départ » (la
    // touche pressée jusqu'à la bulle) où le doigt la conserve : relâcher sans bouger la saisit.
    private var popupDefaultRow = -1
    private var popupDefaultCol = -1
    private val popupHomeRect = RectF()

    // Story 1.6 : vrai quand l'appui long a déjà saisi le chiffre de la touche (le relâchement ne
    // doit alors pas saisir aussi la lettre).
    private var longPressCommitted = false

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.key_hint)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    // Story 1.10 : bulle d'agrandissement de la touche pressée (retour visuel de frappe), affichée
    // au-dessus de la touche tant qu'elle est maintenue. Réservée aux touches qui saisissent un
    // caractère (lettres/chiffres/symboles) : les touches de fonction (espace, maj, retour arrière,
    // entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Disparaît dès qu'une
    // bulle d'accents (1.8) s'affiche par-dessus, ou au relâchement/annulation.
    private var previewKey: Key? = null
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_popup) }
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }
    private val previewRect = RectF()

    // Taille de la bulle d'agrandissement (largeur, hauteur et texte) : 50 % de la taille d'origine.
    private val PREVIEW_SCALE = 0.5f

    // Garde-fou : l'ombre ne dépasse jamais cette part de la hauteur de la touche (rangées basses en paysage).
    private val MAX_SHADOW_RATIO = 0.15f

    // Icônes des touches : jamais plus de cette part de la hauteur de la face (rangées basses en paysage).
    private val KEY_ICON_MAX_RATIO = 0.62f

    private fun showsPreview(key: Key?): Boolean = key?.action is KeyAction.TypeChar

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        val digit = key.longPressChar
        if (key.popup.isNotEmpty()) {
            // Story 1.8 : bulle d'accents / symboles, sélection par glissement du doigt.
            showPopup(key)
        } else if (digit != null) {
            longPressCommitted = true
            previewKey = null
            keyListener?.onKey(Key("longpress_$digit", digit.toString(), KeyAction.TypeChar(digit)))
            invalidate()
        }
    }

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            pressedKey?.let { key ->
                if (key.action is KeyAction.Backspace) {
                    keyListener?.onKey(key)
                    repeatHandler.postDelayed(this, 60L)
                }
            }
        }
    }

    fun setOnKeyListener(listener: OnKeyListener) {
        keyListener = listener
    }

    fun setOnCursorMoveListener(listener: OnCursorMoveListener) {
        cursorMoveListener = listener
    }

    fun setOnDeleteSwipeListener(listener: OnDeleteSwipeListener) {
        deleteSwipeListener = listener
    }

    // Story 1.7 : glissement horizontal sur la barre espace = déplacement du curseur.
    private val spaceSwipe = SpaceSwipeTracker(activationPx = dp(16f), stepPx = dp(12f))

    // Story 1.9 : glissement horizontal vers la gauche sur la touche retour arrière = suppression
    // de mots entiers. Seuils plus larges que ceux de la barre espace : le geste doit rester
    // distinct d'un simple appui long sur la touche.
    private val backspaceSwipe = BackspaceSwipeTracker(activationPx = dp(24f), stepPx = dp(40f))

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        repeatHandler.removeCallbacksAndMessages(null)
        popupKey = null
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape == isLandscape) return
        isLandscape = landscape
        // Une bulle ouverte pendant la rotation serait mal positionnée : on la ferme.
        previewKey = null
        dismissPopup()
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Story 1.14 : largeur modifiée (dépliage d'un pliable, multi-fenêtre) ; une bulle ouverte
        // serait mal positionnée, on la ferme.
        if (oldw != 0 && w != oldw) {
            previewKey = null
            dismissPopup()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (rowsHeightPx() + bottomMarginPx()).toInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // Pas de fond propre : le clavier est transparent et laisse voir le fond commun posé sur la
        // racine de l'IME (KeyboardBackgroundDrawable), partagé avec la barre du haut.
        val inset = insetPx()
        val usableHeight = usableHeightPx()
        val rowHeight = usableHeight / layout.rows.size.toFloat()
        val content = keyboardWidth()

        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.fold(0f) { acc, key -> acc + key.weight }
            val top = rowIndex * rowHeight
            var left = content.leftPx

            row.forEach { key ->
                val keyWidth = content.widthPx * key.weight / totalWeight
                drawKey(canvas, key, left, top, keyWidth, rowHeight, inset)
                left += keyWidth
            }
        }

        drawPopup(canvas)
        drawPreview(canvas)
    }

    private fun drawKey(canvas: Canvas, key: Key, left: Float, top: Float, keyWidth: Float, rowHeight: Float, inset: Float) {
        keyRect.set(left + inset, top + inset, left + keyWidth - inset, top + rowHeight - inset)

        val style = keyStyle(key)
        // Face : la couleur de la charte ; Maj verrouillée / clavier de symboles affiché = état actif (touche à bascule).
        // L'état pressé ne change pas la couleur : il enfonce la touche (voir ci-dessous).
        val face = when {
            key.action is KeyAction.Shift && isShifted -> activePaint
            key.action is KeyAction.ToggleLayout && layout.id == LayoutId.SYMBOLS -> activePaint
            style == KeyStyle.ACCENT -> enterPaint
            style == KeyStyle.SECONDARY -> secondaryPaint
            else -> normalPaint
        }
        val shadow = when (style) {
            KeyStyle.ACCENT -> enterShadowPaint
            KeyStyle.SECONDARY -> secondaryShadowPaint
            KeyStyle.NORMAL -> normalShadowPaint
        }
        // Ombre = la touche entière ; la face la recouvre en laissant voir l'épaisseur en bas. Au repos, l'ombre
        // visible fait [taipo_key_shadow_height]. Pressée, la touche s'enfonce : l'ombre visible se réduit à
        // [taipo_key_shadow_pressed_height] et la face descend de la différence (sa taille ne change pas).
        // L'emprise de référence (zones tactiles, dimensions) ne change pas ; le contenu suit la face.
        val radius = cornerRadiusPx()
        val restShadow = minOf(dimen(R.dimen.taipo_key_shadow_height), keyRect.height() * MAX_SHADOW_RATIO)
        val shownShadow = if (key == pressedKey) minOf(dimen(R.dimen.taipo_key_shadow_pressed_height), restShadow) else restShadow
        keyRect.top += restShadow - shownShadow
        if (style == KeyStyle.ACCENT) {
            // Lot 07 : Entrée est une touche ronde (la zone tactile reste le rectangle de la touche).
            drawRoundKey(canvas, shownShadow, shadow, face)
        } else {
            canvas.drawRoundRect(keyRect, radius, radius, shadow)
            keyRect.bottom -= shownShadow
            canvas.drawRoundRect(keyRect, radius, radius, face)
        }

        when (key.action) {
            KeyAction.Space -> {
                val cx = keyRect.centerX()
                val cy = keyRect.centerY()
                canvas.drawLine(
                    cx - spaceBarLengthPx(), cy, cx + spaceBarLengthPx(), cy, spaceBarPaint,
                )
            }

            KeyAction.Emoji -> drawKeyIcon(canvas, emojiIcon)

            KeyAction.Enter -> drawKeyIcon(canvas, enterIcon)

            KeyAction.Shift -> drawKeyIcon(canvas, if (isCapsLock) shiftCapsIcon else shiftIcon)

            KeyAction.Backspace -> drawKeyIcon(canvas, backspaceIcon)

            else -> {
                val label = displayLabel(key)
                if (label.isNotEmpty()) {
                    autoSizeTextPaint(key)
                    val baseline = keyRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(label, keyRect.centerX(), baseline, textPaint)
                }
                key.longPressChar?.let { drawLongPressHint(canvas, it) }
            }
        }
    }

    /**
     * Touche ronde (Entrée) : cercle de la face, de diamètre la plus petite dimension de la face, centré dans la touche,
     * sur un cercle d'ombre décalé vers le bas. Laisse [keyRect] réduit à la face, pour centrer le contenu.
     */
    private fun drawRoundKey(canvas: Canvas, shownShadow: Float, shadow: Paint, face: Paint) {
        val faceHeight = keyRect.height() - shownShadow
        val radius = minOf(keyRect.width(), faceHeight) / 2f
        val cx = keyRect.centerX()
        val faceCy = keyRect.top + faceHeight / 2f
        canvas.drawCircle(cx, faceCy + shownShadow, radius, shadow)
        canvas.drawCircle(cx, faceCy, radius, face)
        keyRect.bottom -= shownShadow
    }

    private fun loadKeyIcon(@DrawableRes id: Int): Drawable {
        val icon = checkNotNull(ContextCompat.getDrawable(context, id)) { "Icône de touche introuvable" }
        return icon.mutate().apply { setTint(context.themeColor(R.color.key_label)) }
    }

    /** Lot 09 : icône centrée sur la face de la touche, à [R.dimen.taipo_icon_size] (réduite sur les touches basses). */
    private fun drawKeyIcon(canvas: Canvas, icon: Drawable) {
        val half = minOf(dimen(R.dimen.taipo_icon_size), keyRect.height() * KEY_ICON_MAX_RATIO) / 2f
        val cx = keyRect.centerX()
        val cy = keyRect.centerY()
        icon.setBounds(
            (cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(),
        )
        icon.draw(canvas)
    }

    /** Petit indice du chiffre accessible par appui long, en haut à droite de la touche. */
    private fun drawLongPressHint(canvas: Canvas, char: Char) {
        hintPaint.textSize = dimen(R.dimen.taipo_key_hint_text_size)
        canvas.drawText(
            char.toString(),
            keyRect.right - dimen(R.dimen.taipo_key_hint_margin_end),
            keyRect.top + dimen(R.dimen.taipo_key_hint_margin_top) - hintPaint.ascent(),
            hintPaint,
        )
    }

    private fun autoSizeTextPaint(key: Key) {
        val defaultSize = dimen(R.dimen.taipo_key_text_size)
        val bigSize = dimen(R.dimen.taipo_key_text_size_large)
        if (key.id == "toggle") {
            textPaint.textSize = bigSize
        } else {
            textPaint.textSize = defaultSize
        }
        // Rangées basses (paysage, hauteur réduite) : le texte ne doit pas déborder de la touche.
        textPaint.textSize = textPaint.textSize.coerceAtMost(keyRect.height() * 0.62f)
    }

    private fun displayLabel(key: Key): String = when (val action = key.action) {
        is KeyAction.TypeChar ->
            if (isShifted && action.char.isLetter()) action.char.uppercaseChar().toString() else key.label

        // Icônes VectorDrawable (lot 09), dessinées par drawKeyIcon.
        KeyAction.Shift, KeyAction.Backspace, KeyAction.Enter -> ""
        KeyAction.Space -> ""
        KeyAction.ToggleLayout -> if (layout.id == LayoutId.LETTERS) "123" else "ABC"
        KeyAction.Emoji -> "" // icône dessinée par drawEmojiIcon
    }

    /** Famille de couleurs d'une touche (charte Taipo). Un seul endroit à modifier pour reclasser une touche. */
    private enum class KeyStyle { NORMAL, SECONDARY, ACCENT }

    private fun keyStyle(key: Key): KeyStyle = when {
        key.action is KeyAction.Enter -> KeyStyle.ACCENT
        // Lot 06 : l'espace a toujours la couleur des touches normales, même sur le pavé numérique (où il est « secondary »).
        key.action is KeyAction.Space -> KeyStyle.NORMAL
        key.secondary || key.action is KeyAction.Emoji -> KeyStyle.SECONDARY
        else -> KeyStyle.NORMAL
    }

    // Multi-touch : en frappe rapide, le doigt suivant se pose avant que le précédent soit levé.
    // Un seul doigt est « actif » à la fois (celui qui alimente appui long, bulles et glissements) ;
    // quand un nouveau doigt se pose, la touche de l'ancien est validée immédiatement, dans l'ordre.
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                pressStart(event.getX(0), event.getY(0))
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Le doigt précédent est considéré comme relâché à sa dernière position connue :
                // sa touche est saisie avant celle du nouveau doigt, sans être perdue.
                val activeIndex = event.findPointerIndex(activePointerId)
                if (activeIndex >= 0) releaseTouch(event.getX(activeIndex), event.getY(activeIndex))
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                pressStart(event.getX(index), event.getY(index))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) moveTouch(event.getX(index), event.getY(index))
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                // Le relâchement d'un doigt déjà validé (voir ACTION_POINTER_DOWN) est ignoré.
                if (event.getPointerId(index) == activePointerId) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    releaseTouch(event.getX(index), event.getY(index))
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                cancelTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun pressStart(x: Float, y: Float) {
        longPressCommitted = false
        spaceSwipe.reset()
        backspaceSwipe.reset()
        deleteSwipeWords = 0
        pressedKey = keyAt(x, y)
        previewKey = pressedKey.takeIf { showsPreview(it) }
        if (pressedKey?.action is KeyAction.Space) spaceSwipe.onDown(x)
        if (pressedKey?.action is KeyAction.Backspace) backspaceSwipe.onDown(x)
        startRepeat(pressedKey)
        scheduleLongPress(pressedKey)
        invalidate()
    }

    private fun moveTouch(x: Float, y: Float) {
        if (popupKey != null) {
            updatePopupSelection(x, y)
            return
        }
        if (longPressCommitted) return
        if (pressedKey?.action is KeyAction.Space) {
            val steps = spaceSwipe.onMove(x)
            if (steps != 0) cursorMoveListener?.onCursorMove(steps)
            // Une fois le glissement engagé, le doigt peut sortir de la barre espace sans changer de touche.
            if (spaceSwipe.isActive) return
        }
        if (pressedKey?.action is KeyAction.Backspace) {
            val words = backspaceSwipe.onMove(x)
            if (backspaceSwipe.isActive) {
                // Le glissement remplace la suppression caractère par caractère de l'appui maintenu (repeatRunnable).
                stopRepeat()
                if (words != deleteSwipeWords) {
                    deleteSwipeWords = words
                    deleteSwipeListener?.onDeleteSwipeUpdate(words)
                }
                // Une fois le glissement engagé, le doigt peut sortir de la touche sans changer de touche.
                return
            }
        }
        val current = keyAt(x, y)
        if (current != pressedKey) {
            cancelKeyLongPress()
            pressedKey = current
            previewKey = current.takeIf { showsPreview(it) }
            startRepeat(current)
            invalidate()
        }
    }

    /** Relâchement du doigt actif en (x, y) : saisit la touche, le symbole de la bulle, ou rien. */
    private fun releaseTouch(x: Float, y: Float) {
        stopRepeat()
        cancelKeyLongPress()
        previewKey = null
        val popup = popupKey
        if (popup != null) {
            // Relâcher sur un symbole le saisit ; relâcher ailleurs ferme la bulle sans rien saisir.
            updatePopupSelection(x, y)
            val symbol = popup.popup.getOrNull(popupSelectedRow)?.getOrNull(popupSelectedCol)
            dismissPopup()
            pressedKey = null
            if (symbol != null) {
                keyListener?.onKey(Key("popup_$symbol", symbol.toString(), KeyAction.TypeChar(symbol)))
            }
            invalidate()
            return
        }
        val key = pressedKey
        pressedKey = null
        if (spaceSwipe.isActive) {
            // Le glissement a déplacé le curseur : le relâchement ne saisit pas d'espace.
            spaceSwipe.reset()
            invalidate()
            return
        }
        if (backspaceSwipe.isActive) {
            // Le glissement a surligné le(s) mot(s) : on les supprime maintenant, sans effacer un caractère de plus.
            backspaceSwipe.reset()
            deleteSwipeWords = 0
            deleteSwipeListener?.onDeleteSwipeRelease()
            invalidate()
            return
        }
        if (longPressCommitted) {
            longPressCommitted = false
            invalidate()
            return
        }
        if (key != null && keyAt(x, y) == key) {
            keyListener?.onKey(key)
        }
        invalidate()
    }

    private fun cancelTouch() {
        if (backspaceSwipe.isActive) deleteSwipeListener?.onDeleteSwipeCancel()
        deleteSwipeWords = 0
        stopRepeat()
        cancelKeyLongPress()
        dismissPopup()
        previewKey = null
        longPressCommitted = false
        spaceSwipe.reset()
        backspaceSwipe.reset()
        pressedKey = null
        invalidate()
    }

    private fun scheduleLongPress(key: Key?) {
        cancelKeyLongPress()
        if (key != null && (key.popup.isNotEmpty() || key.longPressChar != null)) {
            repeatHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
        }
    }

    private fun cancelKeyLongPress() {
        repeatHandler.removeCallbacks(longPressRunnable)
    }

    /** Position (gauche, haut, largeur) de la touche dans la vue, ou null si elle n'est pas affichée. */
    private fun keyBounds(target: Key): RectF? {
        val rowHeight = usableHeightPx() / layout.rows.size.toFloat()
        val content = keyboardWidth()
        layout.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.fold(0f) { acc, key -> acc + key.weight }
            var left = content.leftPx
            row.forEach { key ->
                val keyWidth = content.widthPx * key.weight / totalWeight
                if (key == target) {
                    return RectF(left, rowIndex * rowHeight, left + keyWidth, (rowIndex + 1) * rowHeight)
                }
                left += keyWidth
            }
        }
        return null
    }

    private fun showPopup(key: Key) {
        previewKey = null
        val bounds = keyBounds(key) ?: return
        val rows = key.popup.size
        val cols = key.popup.maxOf { it.size }
        val margin = dimen(R.dimen.taipo_popup_margin)
        val gap = dimen(R.dimen.taipo_popup_gap)
        val padding = dimen(R.dimen.taipo_popup_padding)
        val clipMargin = dimen(R.dimen.taipo_popup_clip_margin)
        // Marge verticale un peu plus grande que la marge horizontale : la bulle est plus haute
        // de quelques pixels sans grossir les symboles ni les cellules.
        val paddingV = padding + dimen(R.dimen.taipo_popup_padding_extra_vertical)

        // Place disponible au-dessus de la vue : la bulle peut recouvrir la barre d'actions qui la
        // surmonte (le parent ne clippe pas ses enfants, voir TaipoIme), pas au-delà de la fenêtre.
        val headroom = headroomPx()

        // La bulle doit tenir dans cet espace : la hauteur des cellules s'adapte à la place
        // disponible au-dessus de la rangée de la touche.
        val content = keyboardWidth()
        val cellMax = dimen(R.dimen.taipo_popup_cell_max)
        val cellWidth = minOf(cellMax, (content.widthPx - 2 * margin - 2 * padding) / cols)
        val cellHeight = minOf(cellMax, (bounds.top + headroom - gap - 2 * paddingV) / rows)
            .coerceAtLeast(dimen(R.dimen.taipo_popup_cell_min_height))
        val popupWidth = cols * cellWidth + 2 * padding
        val popupHeight = rows * cellHeight + 2 * paddingV

        // Choix présélectionné (Key.defaultPopupChar) : il doit se trouver centré juste au-dessus
        // de la touche pressée, pour que relâcher sans bouger le doigt le saisisse et que le geste
        // vers le haut soit naturel. Sans choix par défaut, c'est la bulle entière qui est centrée.
        var defaultRow = -1
        var defaultCol = -1
        key.defaultPopupChar?.let { default ->
            key.popup.forEachIndexed { r, row ->
                val c = row.indexOf(default)
                if (c >= 0) {
                    defaultRow = r
                    defaultCol = c
                }
            }
        }
        val anchorInPopup = if (defaultCol >= 0) padding + (defaultCol + 0.5f) * cellWidth else popupWidth / 2f
        // Une bulle trop proche d'un bord de l'écran est décalée pour rester visible : le choix
        // présélectionné ne peut alors plus être centré exactement sur la touche.
        val left = PopupPlacement.left(
            keyCenterX = bounds.centerX(),
            anchorInPopup = anchorInPopup,
            popupWidth = popupWidth,
            minLeft = content.leftPx + margin,
            maxRight = content.rightPx - margin,
        )
        val popupTop = (bounds.top - gap - popupHeight).coerceAtLeast(-headroom)

        popupRect.set(left, popupTop, left + popupWidth, popupTop + popupHeight)
        popupCellWidth = cellWidth
        popupCellHeight = cellHeight
        popupPadding = padding
        popupPaddingV = paddingV
        // Zone de départ : la touche et l'espace jusqu'à la bulle, avec une petite tolérance.
        popupHomeRect.set(
            bounds.left - clipMargin, popupRect.bottom, bounds.right + clipMargin, bounds.bottom + clipMargin,
        )
        popupDefaultRow = defaultRow
        popupDefaultCol = defaultCol
        popupSelectedRow = popupDefaultRow
        popupSelectedCol = popupDefaultCol
        popupKey = key
        invalidate()
    }

    private fun dismissPopup() {
        popupKey = null
        popupSelectedRow = -1
        popupSelectedCol = -1
        popupDefaultRow = -1
        popupDefaultCol = -1
        invalidate()
    }

    private fun updatePopupSelection(x: Float, y: Float) {
        val key = popupKey ?: return
        var row = -1
        var col = -1
        if (popupRect.contains(x, y)) {
            val r = ((y - popupRect.top - popupPaddingV) / popupCellHeight).toInt()
                .coerceIn(0, key.popup.lastIndex)
            val c = ((x - popupRect.left - popupPadding) / popupCellWidth).toInt()
                .coerceIn(0, key.popup[r].lastIndex)
            row = r
            col = c
        } else if (popupDefaultRow >= 0 && popupHomeRect.contains(x, y)) {
            // Doigt resté sur la touche d'origine (ou juste au-dessus) : le choix par défaut reste
            // sélectionné, un léger tremblement du doigt ne l'annule donc pas.
            row = popupDefaultRow
            col = popupDefaultCol
        }
        if (row != popupSelectedRow || col != popupSelectedCol) {
            popupSelectedRow = row
            popupSelectedCol = col
            invalidate()
        }
    }

    private fun drawPopup(canvas: Canvas) {
        val key = popupKey ?: return
        val radius = dimen(R.dimen.taipo_popup_corner_radius)
        canvas.drawRoundRect(popupRect, radius, radius, popupPaint)

        popupTextPaint.textSize = dimen(R.dimen.taipo_popup_text_size)
        val baselineOffset = -(popupTextPaint.ascent() + popupTextPaint.descent()) / 2f
        val selectionInset = dimen(R.dimen.taipo_popup_selection_inset)

        key.popup.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, symbol ->
                val cx = popupRect.left + popupPadding + (colIndex + 0.5f) * popupCellWidth
                val cy = popupRect.top + popupPaddingV + (rowIndex + 0.5f) * popupCellHeight
                if (rowIndex == popupSelectedRow && colIndex == popupSelectedCol) {
                    val selectionRadius = minOf(popupCellWidth, popupCellHeight) / 2f - selectionInset
                    canvas.drawCircle(cx, cy, selectionRadius, popupSelectionPaint)
                }
                canvas.drawText(popupLabel(symbol), cx, cy + baselineOffset, popupTextPaint)
            }
        }
    }

    /** Comme sur les touches, les lettres de la bulle passent en majuscule quand Maj est actif. */
    private fun popupLabel(symbol: Char): String =
        if (isShifted && symbol.isLetter()) symbol.uppercaseChar().toString() else symbol.toString()

    /** Story 1.10 : bulle d'agrandissement affichée au-dessus de la touche de caractère pressée. */
    private fun drawPreview(canvas: Canvas) {
        val key = previewKey ?: return
        val bounds = keyBounds(key) ?: return
        val label = displayLabel(key)
        if (label.isEmpty()) return

        val previewWidth = (bounds.width() * 1.6f).coerceAtMost(dimen(R.dimen.taipo_preview_max_width)) * PREVIEW_SCALE
        val previewHeight = bounds.height() * 1.8f * PREVIEW_SCALE
        val gap = dimen(R.dimen.taipo_preview_gap)
        // Place disponible au-dessus de la vue : comme la bulle d'accents (1.8), la bulle peut
        // recouvrir la barre d'actions qui la surmonte, pas au-delà de la fenêtre.
        val headroom = headroomPx()

        val content = keyboardWidth()
        val left = (bounds.centerX() - previewWidth / 2f)
            .coerceIn(content.leftPx, (content.rightPx - previewWidth).coerceAtLeast(content.leftPx))
        val previewBottom = bounds.top - gap
        val previewTop = (previewBottom - previewHeight).coerceAtLeast(-headroom)

        previewRect.set(left, previewTop, left + previewWidth, previewBottom)
        canvas.drawRoundRect(previewRect, cornerRadiusPx(), cornerRadiusPx(), previewPaint)

        previewTextPaint.textSize = dimen(R.dimen.taipo_preview_text_size) * PREVIEW_SCALE
        val baseline = previewRect.centerY() - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        canvas.drawText(label, previewRect.centerX(), baseline, previewTextPaint)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val rows = layout.rows
        if (rows.isEmpty()) return null
        val usableHeight = usableHeightPx()
        if (y >= usableHeight) return null
        val rowHeight = usableHeight / rows.size.toFloat()
        val rowIndex = (y / rowHeight).toInt().coerceIn(0, rows.lastIndex)
        val row = rows[rowIndex]
        val content = keyboardWidth()
        // Story 1.14 : en classe large, les marges de part et d'autre de la zone des touches sont inertes.
        if (content.leftPx > 0f && (x < content.leftPx || x >= content.rightPx)) return null
        val totalWeight = row.fold(0f) { acc, key -> acc + key.weight }
        var acc = content.leftPx
        for (key in row) {
            acc += content.widthPx * key.weight / totalWeight
            if (x < acc) return key
        }
        return row.last()
    }

    private fun startRepeat(key: Key?) {
        stopRepeat()
        if (key?.action is KeyAction.Backspace) {
            repeatHandler.postDelayed(repeatRunnable, 400L)
        }
    }

    private fun stopRepeat() {
        repeatHandler.removeCallbacks(repeatRunnable)
    }

    // Dimensions calées sur la mesure du clavier système de référence (Gboard,
    // thème sombre) : hauteur de touche ~37dp + marge d'insertion ~3dp de
    // chaque côté ⇒ ~43dp par rangée, agrandie de 20 % (51,6dp) à la demande puis multipliée par le réglage de hauteur (story 1.12), et ~60dp de marge basse pour ne pas
    // chevaucher la zone système (bouton de changement de clavier, geste de
    // navigation) qui se superpose sinon aux dernières touches.
    // Story 1.13 : en paysage, rangées et marge basse sont raccourcies (KeyboardMetrics).
    /**
     * Place disponible au-dessus de la vue pour les bulles (accents, agrandissement). Le clavier est
     * dans un conteneur (avec le panneau emoji, story 1.15) placé sous la barre d'actions : la
     * position à prendre en compte est celle du conteneur plus celle de la vue dans ce conteneur.
     */
    private fun headroomPx(): Float {
        val parentTop = (parent as? View)?.top ?: 0
        return (top + parentTop).toFloat().coerceAtLeast(0f)
    }

    /** Story 1.15 : hauteur d'une rangée de touches, pour caler le panneau emoji sur le clavier. */
    fun rowHeightPx(): Int = (rowsHeightPx() / layout.rows.size.coerceAtLeast(1)).toInt()

    /** Story 1.15 : marge basse (zone système) du clavier, reprise par le panneau emoji. */
    fun bottomInsetPx(): Int = bottomMarginPx().toInt()

    private fun rowsHeightPx(): Float =
        dp(metrics().rowsHeightDp(layout.rows.size, heightScale))
    private fun bottomMarginPx(): Float = dp(metrics().bottomMarginDp)
    private fun usableHeightPx(): Float = (height - bottomMarginPx()).coerceAtLeast(0f)

    private fun insetPx(): Float = dimen(R.dimen.taipo_key_inset)
    private fun cornerRadiusPx(): Float = dimen(R.dimen.taipo_key_corner_radius)
    private fun spaceBarLengthPx(): Float = dimen(R.dimen.taipo_space_bar_length)

    /** Story 1.14 : zone horizontale des touches (plein écran en Compact, plafonnée et centrée en large). */
    private fun keyboardWidth(): KeyboardWidth =
        KeyboardWidth.forAvailableWidth(width.toFloat(), resources.displayMetrics.density)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}