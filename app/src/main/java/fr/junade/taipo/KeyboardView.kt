package fr.junade.taipo

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

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

    // Palette alignée sur le clavier système de référence (Gboard, thème sombre) :
    // fond quasi noir, touches "principales" (lettres/chiffres/espace) gris moyen,
    // touches "accessoires" (fonction + ponctuation rapide) gris très foncé,
    // et un accent turquoise réservé à la touche Entrée.
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E2E2E") }
    private val functionalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141414") }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4A4A") }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4A4A") }
    private val enterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80CBC4") }
    private val spaceBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4A")
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val keyRect = RectF()

    // Icône de la touche emoji (story 1.15).
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E0E0E0") }
    private val iconRect = RectF()

    // Appui long : bulle de symboles (grille) affichée au-dessus de la touche.
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A") }
    private val popupSelectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#26A69A") }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
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
        color = Color.parseColor("#9E9E9E")
        textAlign = Paint.Align.CENTER
    }

    // Story 1.10 : bulle d'agrandissement de la touche pressée (retour visuel de frappe), affichée
    // au-dessus de la touche tant qu'elle est maintenue. Réservée aux touches qui saisissent un
    // caractère (lettres/chiffres/symboles) : les touches de fonction (espace, maj, retour arrière,
    // entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Disparaît dès qu'une
    // bulle d'accents (1.8) s'affiche par-dessus, ou au relâchement/annulation.
    private var previewKey: Key? = null
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A3A3A") }
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val previewRect = RectF()

    // Taille de la bulle d'agrandissement (largeur, hauteur et texte) : 50 % de la taille d'origine.
    private val PREVIEW_SCALE = 0.5f

    // Hauteur supplémentaire (dp, en haut et en bas) de la bulle d'appui long, sans toucher aux symboles.
    private val POPUP_EXTRA_VERTICAL_PADDING_DP = 4f

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

        val fill = when {
            key == pressedKey -> pressedPaint
            key.action is KeyAction.Enter -> enterPaint
            key.action is KeyAction.Shift && isShifted -> activePaint
            key.action is KeyAction.ToggleLayout && layout.id == LayoutId.SYMBOLS -> activePaint
            isFunctional(key) || key.secondary -> functionalPaint
            else -> normalPaint
        }
        canvas.drawRoundRect(keyRect, cornerRadiusPx(), cornerRadiusPx(), fill)

        when (key.action) {
            KeyAction.Space -> {
                val cx = keyRect.centerX()
                val cy = keyRect.centerY()
                canvas.drawLine(
                    cx - spaceBarLengthPx(), cy, cx + spaceBarLengthPx(), cy, spaceBarPaint,
                )
            }

            KeyAction.Emoji -> drawEmojiIcon(canvas)

            else -> {
                val label = displayLabel(key)
                if (label.isNotEmpty()) {
                    autoSizeTextPaint(key)
                    val baseline = keyRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                    canvas.drawText(label, keyRect.centerX(), baseline, textPaint)
                    if (key.action is KeyAction.Shift && isCapsLock) drawCapsLockBar(canvas, baseline)
                }
                key.longPressChar?.let { drawLongPressHint(canvas, it) }
            }
        }
    }

    /** Barre sous la flèche de la touche Maj quand les majuscules sont verrouillées. */
    private fun drawCapsLockBar(canvas: Canvas, textBaseline: Float) {
        val halfWidth = dp(7f)
        val top = (textBaseline + dp(3f)).coerceAtMost(keyRect.bottom - dp(4f))
        canvas.drawRoundRect(
            keyRect.centerX() - halfWidth, top, keyRect.centerX() + halfWidth, top + dp(2.5f),
            dp(1.25f), dp(1.25f), iconFillPaint,
        )
    }

    /** Story 1.15 : icône « smiley » de la touche emoji (dessinée, donc indépendante des polices). */
    private fun drawEmojiIcon(canvas: Canvas) {
        val cx = keyRect.centerX()
        val cy = keyRect.centerY()
        val radius = minOf(keyRect.height() * 0.3f, dp(11f))
        iconStrokePaint.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, radius, iconStrokePaint)
        val eyeRadius = radius * 0.13f
        canvas.drawCircle(cx - radius * 0.36f, cy - radius * 0.28f, eyeRadius, iconFillPaint)
        canvas.drawCircle(cx + radius * 0.36f, cy - radius * 0.28f, eyeRadius, iconFillPaint)
        val smile = radius * 0.55f
        iconRect.set(cx - smile, cy - smile, cx + smile, cy + smile)
        canvas.drawArc(iconRect, 25f, 130f, false, iconStrokePaint)
    }

    /** Petit indice du chiffre accessible par appui long, en haut à droite de la touche. */
    private fun drawLongPressHint(canvas: Canvas, char: Char) {
        hintPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics,
        )
        canvas.drawText(
            char.toString(),
            keyRect.right - dp(7f),
            keyRect.top + dp(3f) - hintPaint.ascent(),
            hintPaint,
        )
    }

    private fun autoSizeTextPaint(key: Key) {
        val defaultSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics,
        )
        val bigSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 26f, resources.displayMetrics,
        )
        if (key.id == "backspace" || key.id == "enter" || key.id == "shift" || key.id == "toggle") {
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

        KeyAction.Shift -> "⇧"
        KeyAction.Backspace -> "⌫"
        KeyAction.Enter -> "⏎"
        KeyAction.Space -> ""
        KeyAction.ToggleLayout -> if (layout.id == LayoutId.LETTERS) "123" else "ABC"
        KeyAction.Emoji -> "" // icône dessinée par drawEmojiIcon
    }

    private fun isFunctional(key: Key): Boolean = when (key.action) {
        is KeyAction.TypeChar, KeyAction.Space -> false
        KeyAction.Shift, KeyAction.Backspace, KeyAction.Enter, KeyAction.ToggleLayout, KeyAction.Emoji -> true
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
        val margin = dp(4f)
        val gap = dp(4f)
        val padding = dp(8f)
        // Marge verticale un peu plus grande que la marge horizontale : la bulle est plus haute
        // de quelques pixels sans grossir les symboles ni les cellules.
        val paddingV = padding + dp(POPUP_EXTRA_VERTICAL_PADDING_DP)

        // Place disponible au-dessus de la vue : la bulle peut recouvrir la barre d'actions qui la
        // surmonte (le parent ne clippe pas ses enfants, voir ClavierIme), pas au-delà de la fenêtre.
        val headroom = headroomPx()

        // La bulle doit tenir dans cet espace : la hauteur des cellules s'adapte à la place
        // disponible au-dessus de la rangée de la touche.
        val content = keyboardWidth()
        val cellWidth = minOf(dp(44f), (content.widthPx - 2 * margin - 2 * padding) / cols)
        val cellHeight = minOf(dp(44f), (bounds.top + headroom - gap - 2 * paddingV) / rows).coerceAtLeast(dp(24f))
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
            bounds.left - dp(4f), popupRect.bottom, bounds.right + dp(4f), bounds.bottom + dp(4f),
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
        val radius = dp(20f)
        canvas.drawRoundRect(popupRect, radius, radius, popupPaint)

        popupTextPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 22f, resources.displayMetrics,
        )
        val baselineOffset = -(popupTextPaint.ascent() + popupTextPaint.descent()) / 2f

        key.popup.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { colIndex, symbol ->
                val cx = popupRect.left + popupPadding + (colIndex + 0.5f) * popupCellWidth
                val cy = popupRect.top + popupPaddingV + (rowIndex + 0.5f) * popupCellHeight
                if (rowIndex == popupSelectedRow && colIndex == popupSelectedCol) {
                    canvas.drawCircle(cx, cy, minOf(popupCellWidth, popupCellHeight) / 2f - dp(1f), popupSelectionPaint)
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

        val previewWidth = (bounds.width() * 1.6f).coerceAtMost(dp(72f)) * PREVIEW_SCALE
        val previewHeight = bounds.height() * 1.8f * PREVIEW_SCALE
        val gap = dp(4f)
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

        previewTextPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 30f * PREVIEW_SCALE, resources.displayMetrics,
        )
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

    private fun insetPx(): Float = dp(3f)
    private fun cornerRadiusPx(): Float = dp(8f)
    private fun spaceBarLengthPx(): Float = dp(22f)

    /** Story 1.14 : zone horizontale des touches (plein écran en Compact, plafonnée et centrée en large). */
    private fun keyboardWidth(): KeyboardWidth =
        KeyboardWidth.forAvailableWidth(width.toFloat(), resources.displayMetrics.density)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}