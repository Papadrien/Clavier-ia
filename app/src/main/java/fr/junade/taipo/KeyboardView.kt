package fr.junade.taipo

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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

    // Lot 20 : arbre d'accessibilité virtuel (TalkBack), voir KeyboardAccessibility. Les zones des touches sont mises en cache
    // et recalculées après un changement de disposition, de hauteur, de taille ou d'orientation.
    private var slotCache: List<KeySlot>? = null
    private val accessibility = KeyboardAccessibility(this)

    init {
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        contentDescription = context.getString(R.string.a11y_keyboard_description)
        // Rendu : pas de surlignage de focus par défaut (vue focalisable pour l'accessibilité, jamais pour la saisie).
        defaultFocusHighlightEnabled = false
    }

    var layout: KeyboardLayout = Keyboards.letters
        set(value) {
            field = value
            slotCache = null
            accessibility.invalidateRoot()
            invalidate()
            requestLayout()
        }

    /**
     * Nombre de rangées sur lequel se règle la hauteur de la vue : elle ne descend jamais sous ce nombre de rangées de
     * référence, même si la disposition affichée en compte moins (symboles à 4 rangées sous des lettres à 5 avec la
     * rangée de chiffres). Les rangées s'étirent alors : la hauteur du clavier ne change pas d'une disposition à l'autre.
     */
    var minRowCount: Int = 0
        set(value) {
            if (field == value) return
            field = value
            slotCache = null
            requestLayout()
            invalidate()
        }

    /** Story 1.12 : coefficient appliqué à la hauteur des rangées (1 = hauteur de référence). */
    var heightScale: Float = KeyboardHeight.DEFAULT.scale
        set(value) {
            if (field == value) return
            field = value
            slotCache = null
            requestLayout()
            invalidate()
        }

    // Story 1.13 : orientation courante, qui détermine les dimensions verticales (KeyboardMetrics).
    private var isLandscape: Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun metrics(): KeyboardMetrics = KeyboardMetrics.forOrientation(isLandscape)

    /** Marge sous les touches en portrait, en dp (réglage « Marge inférieure », [KeyboardBottomMargin]). */
    var bottomMarginDp: Int = KeyboardBottomMargin.DEFAULT_DP
        set(value) {
            val clamped = KeyboardBottomMargin.clamp(value)
            if (field == clamped) return
            field = clamped
            requestLayout()
            invalidate()
        }

    var isShifted: Boolean = false
        set(value) {
            field = value
            accessibility.invalidateRoot()
            invalidate()
        }

    /** Verrouillage des majuscules (double appui sur Maj) : une barre est dessinée sous la flèche. */
    var isCapsLock: Boolean = false
        set(value) {
            field = value
            accessibility.invalidateRoot()
            invalidate()
        }

    // Dessin des touches (faces, ombres, icônes, libellés) : voir KeyRenderer (audit 2.1).
    private val keyRenderer = KeyRenderer(context)

    private var dims = DrawDimens(context)

    private fun refreshDimens() {
        dims = DrawDimens(context)
        keyRenderer.refreshDimens()
        contentCache = null
    }

    // Lot 21 : zone des touches (un objet par appel avant) mise en cache ; invalidée quand la largeur ou la densité change.
    private var contentCache: KeyboardWidth? = null

    // Lot 21 : tampon réutilisé (aucun RectF créé par frame) : l'emprise d'une touche pour les bulles.
    private val boundsScratch = RectF()

    // Icône du globe (bulle d'Entrée) : les autres icônes de touche sont chargées par KeyRenderer.
    private val globeIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_key_globe)) { "Icône globe introuvable" }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }

    // Appui long : bulle de symboles (grille) affichée au-dessus de la touche : voir KeyPopup (audit 2.1).
    private val keyPopup = KeyPopup(this)

    // Story 1.6 : vrai quand l'appui long a déjà saisi le chiffre de la touche (le relâchement ne
    // doit alors pas saisir aussi la lettre).
    private var longPressCommitted = false

    // Story 1.10 : bulle d'agrandissement de la touche pressée (retour visuel de frappe), affichée
    // au-dessus de la touche tant qu'elle est maintenue. Réservée aux touches qui saisissent un
    // caractère (lettres/chiffres/symboles) : les touches de fonction (espace, maj, retour arrière,
    // entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Disparaît dès qu'une
    // bulle d'accents (1.8) s'affiche par-dessus, ou au relâchement/annulation.
    private val keyPreview = KeyPreview(this)

    private fun showsPreview(key: Key?): Boolean = key?.action is KeyAction.TypeChar

    /**
     * Clavier multilingue : l'appui long sur Entrée ouvre une bulle contenant la touche globe (sélection par
     * glissement vers le haut, comme les accents). L'IME active ce comportement.
     */
    var isEnterLongPressEnabled = false
    private var globeListener: Runnable? = null

    /** Appelé quand la touche globe de la bulle d'Entrée est relâchée (choix de la langue : voir TaipoIme). */
    fun setOnGlobeSelectedListener(listener: Runnable) {
        globeListener = listener
    }

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        val digit = key.longPressChar
        if (key.action is KeyAction.Enter) {
            // Bulle à une cellule (globe) : relâcher dessus change de langue, relâcher ailleurs ferme la bulle sans
            // envoyer de retour à la ligne (le relâchement avec bulle ouverte ne saisit jamais la touche d'origine).
            showPopup(key, KeyPopup.ENTER_GRID)
        } else if (key.popup.isNotEmpty()) {
            // Story 1.8 : bulle d'accents / symboles, sélection par glissement du doigt.
            showPopup(key)
        } else if (digit != null) {
            longPressCommitted = true
            keyPreview.key = null
            keyListener?.onKey(Key("longpress_$digit", digit.toString(), KeyAction.TypeChar(digit)))
            invalidate()
        }
    }

    private val repeatHandler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        override fun run() {
            pressedKey?.let { key ->
                if (key.action is KeyAction.Backspace) {
                    deliverKey(key, repeat = true)
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

    // Animation de l'icône retour arrière (balancier, inclinaison) : seule l'icône bouge, pas la touche.
    private val backspaceAnimation = BackspaceAnimation(
        density = resources.displayMetrics.density,
        animationsEnabled = { ValueAnimator.areAnimatorsEnabled() },
    )
    private val sparkPainter = SparkPainter(
        intArrayOf(
            context.themeColor(R.color.taipo_purple_light),
            context.themeColor(R.color.taipo_purple),
            context.themeColor(R.color.key_label),
        ),
    )

    // Animation de l'icône Maj (« Bond ») : seule l'icône bouge, la face de la touche garde son rendu. L'état vient de
    // isShifted / isCapsLock, relu à chaque dessin (voir syncShiftAnimation) ; un appui sur Maj est horodaté pour
    // distinguer l'activation au doigt de la majuscule automatique (début de phrase).
    private val shiftAnimation = ShiftAnimation(
        density = resources.displayMetrics.density,
        animationsEnabled = { ValueAnimator.areAnimatorsEnabled() },
    )
    private var shiftSeeded = false
    private var lastShiftTapNanos = 0L

    // Animation « Amibe » du trait de la barre espace pendant le glissement du curseur : deux ressorts, l'avant colle au doigt,
    // l'arrière traîne. Le trait seul bouge, pas la touche.
    private val spaceBarAnimation = SpaceBarAnimation(
        density = resources.displayMetrics.density,
        animationsEnabled = { ValueAnimator.areAnimatorsEnabled() },
        halfLengthPx = { resources.getDimension(R.dimen.taipo_space_bar_length) },
    )

    init {
        keyRenderer.shiftAnimation = shiftAnimation
        keyRenderer.spaceBarAnimation = spaceBarAnimation
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceAnimation.cancel()
        shiftAnimation.cancel()
        spaceBarAnimation.cancel()
        shiftSeeded = false
        keyRenderer.backspaceIconAngle = 0f
        repeatHandler.removeCallbacksAndMessages(null)
        keyPopup.forget()
        activePointerId = MotionEvent.INVALID_POINTER_ID
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDimens()
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape == isLandscape) return
        isLandscape = landscape
        slotCache = null
        // Une bulle ouverte pendant la rotation serait mal positionnée : on la ferme.
        keyPreview.key = null
        dismissPopup()
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        slotCache = null
        refreshDimens() // lot 21 : dépliage d'un pliable, multi-fenêtre : les dimensions et la zone des touches sont relues
        // Story 1.14 : largeur modifiée (dépliage d'un pliable, multi-fenêtre) ; une bulle ouverte
        // serait mal positionnée, on la ferme.
        if (oldw != 0 && w != oldw) {
            keyPreview.key = null
            dismissPopup()
        }
    }

    /** Lot 20 : l'exploration au doigt de TalkBack arrive en événements de survol, relayés à l'arbre virtuel. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Lot 20 : les touches de la disposition courante et leur zone tactile (coordonnées de la vue, mêmes que [keyAt]), dans
     * l'ordre de lecture. Mis en cache ; l'identifiant virtuel d'une touche pour TalkBack est son rang dans cette liste.
     */
    internal fun keySlots(): List<KeySlot> {
        slotCache?.let { return it }
        val rows = layout.rows
        if (rows.isEmpty()) return emptyList()
        val rowHeight = usableHeightPx() / rows.size.toFloat()
        val content = keyboardWidth()
        val slots = ArrayList<KeySlot>()
        val unit = content.widthPx / layout.referenceUnits
        rows.forEachIndexed { rowIndex, row ->
            var left = content.leftPx + layout.rowStartUnits(row) * unit
            row.forEach { key ->
                val keyWidth = unit * key.weight
                slots += KeySlot(
                    key,
                    Rect(
                        left.roundToInt(),
                        (rowIndex * rowHeight).roundToInt(),
                        (left + keyWidth).roundToInt(),
                        ((rowIndex + 1) * rowHeight).roundToInt(),
                    ),
                )
                left += keyWidth
            }
        }
        slotCache = slots
        return slots
    }

    /** Lot 20 : saisie d'une touche demandée par TalkBack (double appui), sans geste tactile. */
    internal fun activateKey(key: Key) {
        deliverKey(key, repeat = false)
    }

    /** Lot 20 : saisie d'un caractère de l'appui long demandée par TalkBack (action « Saisir é »), comme le choix dans la bulle. */
    internal fun typeSymbol(symbol: Char) {
        keyListener?.onKey(Key("popup_$symbol", symbol.toString(), KeyAction.TypeChar(symbol)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (rowsHeightPx() + bottomMarginPx()).toInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        // Pas de fond propre : le clavier est transparent et laisse voir le fond commun posé sur la
        // racine de l'IME (KeyboardBackgroundDrawable), partagé avec la barre du haut.
        val inset = dims.keyInset
        val usableHeight = usableHeightPx()
        val rowHeight = usableHeight / layout.rows.size.toFloat()
        val content = keyboardWidth()

        val now = System.nanoTime()
        syncShiftAnimation(now)
        if (backspaceAnimation.isRunning) backspaceAnimation.advance(now)
        if (shiftAnimation.isRunning) shiftAnimation.advance(now)
        if (spaceBarAnimation.isRunning) spaceBarAnimation.advance(now)
        keyRenderer.backspaceIconAngle = backspaceAnimation.iconAngle

        val unit = content.widthPx / layout.referenceUnits
        layout.rows.forEachIndexed { rowIndex, row ->
            val top = rowIndex * rowHeight
            var left = content.leftPx + layout.rowStartUnits(row) * unit

            row.forEach { key ->
                val keyWidth = unit * key.weight
                keyRenderer.draw(
                    canvas, key, left, top, keyWidth, rowHeight, inset,
                    dims, layout, isShifted, pressedKey,
                )
                left += keyWidth
            }
        }

        sparkPainter.draw(canvas, shiftAnimation.sparks)
        drawPopup(canvas)
        drawPreview(canvas)
        if (backspaceAnimation.isRunning || shiftAnimation.isRunning || spaceBarAnimation.isRunning) postInvalidateOnAnimation()
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
        keyPreview.key = pressedKey.takeIf { showsPreview(it) }
        if (pressedKey?.action is KeyAction.Space) spaceSwipe.onDown(x)
        if (pressedKey?.action is KeyAction.Backspace) {
            backspaceSwipe.onDown(x)
            backspaceAnimation.onPress()
        }
        startRepeat(pressedKey)
        scheduleLongPress(pressedKey)
        invalidate()
    }

    private fun moveTouch(x: Float, y: Float) {
        if (keyPopup.isShown) {
            keyPopup.updateSelection(x, y)
            return
        }
        if (longPressCommitted) return
        if (pressedKey?.action is KeyAction.Space) {
            val steps = spaceSwipe.onMove(x)
            if (steps != 0) cursorMoveListener?.onCursorMove(steps)
            // Une fois le glissement engagé, le doigt peut sortir de la barre espace sans changer de touche.
            if (spaceSwipe.isActive) {
                spaceBarAnimation.onDrag(spaceSwipe.offsetFromDown(x), spaceBarMaxTravelPx())
                invalidate()
                return
            }
        }
        if (pressedKey?.action is KeyAction.Backspace) {
            val words = backspaceSwipe.onMove(x)
            if (backspaceSwipe.isActive) {
                // Le glissement remplace la suppression caractère par caractère de l'appui maintenu (repeatRunnable).
                stopRepeat()
                backspaceAnimation.onDrag(backspaceSwipe.leftwardDistance(x), words)
                invalidate()
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
            keyPreview.key = current.takeIf { showsPreview(it) }
            startRepeat(current)
            invalidate()
        }
    }

    /** Relâchement du doigt actif en (x, y) : saisit la touche, le symbole de la bulle, ou rien. */
    private fun releaseTouch(x: Float, y: Float) {
        spaceBarAnimation.onRelease()
        stopRepeat()
        cancelKeyLongPress()
        keyPreview.key = null
        if (keyPopup.isShown) {
            // Relâcher sur un symbole le saisit ; relâcher ailleurs ferme la bulle sans rien saisir.
            keyPopup.updateSelection(x, y)
            val symbol = keyPopup.selectedSymbol()
            dismissPopup()
            pressedKey = null
            if (symbol == KeyPopup.GLOBE_SYMBOL) {
                globeListener?.run()
            } else if (symbol != null) {
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
            backspaceAnimation.onSwipeRelease()
            invalidate()
            return
        }
        if (longPressCommitted) {
            longPressCommitted = false
            invalidate()
            return
        }
        if (key != null && keyAt(x, y) == key) {
            deliverKey(key, repeat = false)
        }
        invalidate()
    }

    /** Saisit [key] ; pour le retour arrière, lance aussi l'animation de l'icône (balancier). */
    private fun deliverKey(key: Key, repeat: Boolean) {
        if (key.action is KeyAction.Shift) lastShiftTapNanos = System.nanoTime()
        keyListener?.onKey(key)
        if (key.action !is KeyAction.Backspace) return
        backspaceAnimation.onKey(repeat)
        invalidate()
    }

    /**
     * Compare l'état de Maj (minuscule, majuscule, verrouillée) à celui de l'animation et lance la transition. Fait à chaque
     * dessin : plusieurs changements dans la même image (majuscule puis verrouillage) n'en font qu'une. Premier dessin ou
     * touche absente de la disposition : pose directe, sans animation. Majuscule « automatique » = aucun appui sur Maj
     * juste avant (début de phrase).
     */
    private fun syncShiftAnimation(nowNanos: Long) {
        val target = ShiftState.of(isShifted, isCapsLock)
        if (!shiftSeeded) {
            shiftSeeded = true
            shiftAnimation.snap(target)
            return
        }
        if (target == shiftAnimation.state) return
        val bounds = shiftKeyBounds()
        if (bounds == null) {
            shiftAnimation.snap(target)
            return
        }
        val auto = nowNanos - lastShiftTapNanos > SHIFT_TAP_WINDOW_NANOS
        shiftAnimation.onState(target, auto, bounds.centerX(), bounds.centerY() - dims.keyShadowHeight / 2f, dims.iconSize)
    }

    private fun shiftKeyBounds(): RectF? {
        val key = layout.rows.firstNotNullOfOrNull { row -> row.firstOrNull { it.action is KeyAction.Shift } } ?: return null
        return keyBounds(key)
    }

    private fun cancelTouch() {
        if (backspaceSwipe.isActive) deleteSwipeListener?.onDeleteSwipeCancel()
        backspaceAnimation.onSwipeCancel()
        spaceBarAnimation.onRelease()
        deleteSwipeWords = 0
        stopRepeat()
        cancelKeyLongPress()
        dismissPopup()
        keyPreview.key = null
        longPressCommitted = false
        spaceSwipe.reset()
        backspaceSwipe.reset()
        pressedKey = null
        invalidate()
    }

    private fun scheduleLongPress(key: Key?) {
        cancelKeyLongPress()
        if (key != null && (key.popup.isNotEmpty() || key.longPressChar != null || isEnterLongPress(key))) {
            repeatHandler.postDelayed(longPressRunnable, longPressDelayMs())
        }
    }

    /** Délai système d'appui long (réglable dans l'accessibilité), réduit de 20 % pour que les accents et chiffres s'affichent plus vite. */
    private fun longPressDelayMs(): Long =
        (ViewConfiguration.getLongPressTimeout() * LONG_PRESS_DELAY_RATIO).toLong()

    private fun isEnterLongPress(key: Key): Boolean = isEnterLongPressEnabled && key.action is KeyAction.Enter

    private fun cancelKeyLongPress() {
        repeatHandler.removeCallbacks(longPressRunnable)
    }

    /** Position (gauche, haut, largeur) de la touche dans la vue, ou null si elle n'est pas affichée. */
    /** Distance maximale du trait de la barre espace au centre de la touche : la touche moins le demi-trait et une marge. */
    private fun spaceBarMaxTravelPx(): Float {
        val bounds = pressedKey?.let { keyBounds(it) } ?: return 0f
        val half = resources.getDimension(R.dimen.taipo_space_bar_length)
        return (bounds.width() / 2f - half - dp(SPACE_BAR_EDGE_MARGIN_DP)).coerceAtLeast(dp(SPACE_BAR_EDGE_MARGIN_DP))
    }

    private fun keyBounds(target: Key): RectF? {
        val rowHeight = usableHeightPx() / layout.rows.size.toFloat()
        val content = keyboardWidth()
        val unit = content.widthPx / layout.referenceUnits
        layout.rows.forEachIndexed { rowIndex, row ->
            var left = content.leftPx + layout.rowStartUnits(row) * unit
            row.forEach { key ->
                val keyWidth = unit * key.weight
                if (key == target) {
                    // Lot 21 : RectF réutilisé (appelé à chaque dessin de la bulle d'agrandissement) ; les appelants ne le gardent pas.
                    boundsScratch.set(left, rowIndex * rowHeight, left + keyWidth, (rowIndex + 1) * rowHeight)
                    return boundsScratch
                }
                left += keyWidth
            }
        }
        return null
    }

    private fun showPopup(key: Key, grid: List<List<Char>> = key.popup) {
        keyPreview.key = null
        val bounds = keyBounds(key) ?: return
        // Place disponible au-dessus de la vue : la bulle peut recouvrir la barre d'actions qui la
        // surmonte (le parent ne clippe pas ses enfants, voir TaipoIme), pas au-delà de la fenêtre.
        keyPopup.show(key, grid, bounds, headroomPx(), keyboardWidth())
    }

    private fun dismissPopup() = keyPopup.dismiss()

    private fun drawPopup(canvas: Canvas) = keyPopup.draw(
        canvas,
        cornerRadius = dims.popupCornerRadius,
        textSize = dims.popupTextSize,
        selectionInset = dims.popupSelectionInset,
        iconSize = dims.iconSize,
        iconMaxRatio = KeyRenderer.KEY_ICON_MAX_RATIO,
        shifted = isShifted,
        globeIcon = globeIcon,
    )

    /** Story 1.10 : bulle d'agrandissement affichée au-dessus de la touche de caractère pressée. */
    private fun drawPreview(canvas: Canvas) {
        val key = keyPreview.key ?: return
        val bounds = keyBounds(key) ?: return
        val upper = keyRenderer.shiftedChar(key, layout, isShifted)
        val label = if (upper == NO_CHAR) keyRenderer.displayLabel(key, layout, isShifted) else ""
        if (upper == NO_CHAR && label.isEmpty()) return
        keyPreview.draw(
            canvas,
            bounds = bounds,
            upper = upper.takeIf { it != NO_CHAR },
            label = label,
            headroom = headroomPx(),
            content = keyboardWidth(),
            maxWidth = dims.previewMaxWidth,
            cornerRadius = dims.keyCornerRadius,
            gap = dims.previewGap,
            textSize = dims.previewTextSize,
        )
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
        // Rangée plus courte que la référence (centrée) : un appui dans la marge revient à la touche la plus proche.
        val unit = content.widthPx / layout.referenceUnits
        var acc = content.leftPx + layout.rowStartUnits(row) * unit
        for (key in row) {
            acc += unit * key.weight
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
        dp(metrics().rowsHeightDp(maxOf(layout.rows.size, minRowCount), heightScale))
    private fun bottomMarginPx(): Float =
        dp(if (isLandscape) metrics().bottomMarginDp else bottomMarginDp.toFloat())
    private fun usableHeightPx(): Float = (height - bottomMarginPx()).coerceAtLeast(0f)

    /** Story 1.14 : zone horizontale des touches (plein écran en Compact, plafonnée et centrée en large). */
    private fun keyboardWidth(): KeyboardWidth =
        contentCache ?: KeyboardWidth.forAvailableWidth(width.toFloat(), resources.displayMetrics.density)
            .also { contentCache = it }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Lot 21 : « pas de caractère » (voir [KeyRenderer.shiftedChar]). */
        const val NO_CHAR = KeyRenderer.NO_CHAR

        /** Marge entre le bout du trait et le bord de la barre espace quand le trait est au bout de sa course. */
        private const val SPACE_BAR_EDGE_MARGIN_DP = 8f

        /** Part du délai système d'appui long utilisée pour les touches à pop-up ou à chiffre (−20 %). */
        const val LONG_PRESS_DELAY_RATIO = 0.8f

        /** Un changement de Maj dans les 400 ms qui suivent un appui sur la touche vient de cet appui ; au-delà, c'est la majuscule automatique. */
        const val SHIFT_TAP_WINDOW_NANOS = 400_000_000L
    }
}
