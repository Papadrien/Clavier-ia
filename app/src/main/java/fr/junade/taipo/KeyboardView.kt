package fr.junade.taipo

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import fr.junade.taipo.dictionary.NextLetterModel
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

    // Revue de code (performance) : l'IME repose la disposition à chaque touche et à chaque déplacement du curseur
    // (applyState). Les dispositions sont des instances en cache (Keyboards) : la même instance ne relance ni le calcul des
    // zones, ni l'arbre d'accessibilité, ni surtout une mise en page complète de la vue.
    var layout: KeyboardLayout = Keyboards.letters
        set(value) {
            if (field === value) return
            field = value
            slotCache = null
            zones = computeZones()
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

    /**
     * Mode une main : zone des touches serrée contre un bord (voir [KeyboardWidth]). Les panneaux (emoji, Smart Clipboard)
     * suivent cette zone via [KeyboardStackLayout] ; la barre du haut garde toute la largeur. La zone libre est inerte, sauf
     * ses deux boutons (changer de côté, quitter le mode : voir [OneHandedControls]).
     */
    var oneHandedMode: OneHandedMode = OneHandedMode.DEFAULT
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            if (value.isActive) controlsMode = value
            onKeyZoneChanged()
            moveOneHanded(previous)
        }

    /** Largeur de la zone des touches en mode une main, en % de la largeur normale ([OneHandedWidth]). */
    var oneHandedWidthPercent: Int = OneHandedWidth.DEFAULT_PERCENT
        set(value) {
            val clamped = OneHandedWidth.clamp(value)
            if (field == clamped) return
            field = clamped
            oneHandedTransition.stop()
            onKeyZoneChanged()
        }

    /**
     * Mode une main : la zone des touches et les boutons ronds passent d'un état à l'autre en ressort (voir [OneHandedTransition]).
     * Pas d'animation si les animations sont coupées dans les réglages système, ni si la vue n'est pas affichée : changement direct.
     */
    private fun moveOneHanded(previous: OneHandedMode) {
        if (width <= 0 || !isShown || !ValueAnimator.areAnimatorsEnabled()) {
            oneHandedTransition.stop()
            return
        }
        val density = resources.displayMetrics.density
        val available = width.toFloat()
        val from = KeyboardWidth.forAvailableWidth(available, density, previous, oneHandedWidthPercent)
        val to = KeyboardWidth.forAvailableWidth(available, density, oneHandedMode, oneHandedWidthPercent)
        oneHandedTransition.moveTo(previous, oneHandedMode, from, to, controlsCenterX(previous, from), controlsCenterX(oneHandedMode, to))
        invalidate()
    }

    /** Centre horizontal des boutons ronds dans la zone libre d'un mode actif ([zone] : zone des touches de ce mode). */
    private fun controlsCenterX(mode: OneHandedMode, zone: KeyboardWidth): Float = when (mode) {
        OneHandedMode.LEFT -> (zone.rightPx + width) / 2f
        OneHandedMode.RIGHT -> zone.leftPx / 2f
        OneHandedMode.OFF -> 0f
    }

    /** La zone des touches a changé : géométrie recalculée, bulle ouverte fermée (elle serait mal placée). */
    private fun onKeyZoneChanged() {
        contentCache = null
        slotCache = null
        keyPreview.key = null
        dismissPopup()
        accessibility.invalidateRoot()
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
            if (field == value) return
            field = value
            accessibility.invalidateRoot()
            invalidate()
        }

    /** Verrouillage des majuscules (double appui sur Maj) : une barre est dessinée sous la flèche. */
    var isCapsLock: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            accessibility.invalidateRoot()
            invalidate()
        }

    // Dessin des touches (faces, ombres, icônes, libellés) : voir KeyRenderer (audit 2.1).
    private val keyRenderer = KeyRenderer(context)

    // Forme des touches : les bulles (agrandissement, accents) reprennent son rayon. Lue à la création, comme les couleurs.
    private val bubbleRadiusScale = KeyboardTheme.shape.radiusScale

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

    // Icône globe (bulle d'Entrée, changement de langue) : les autres icônes de touche sont chargées par KeyRenderer.
    private val globeIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_key_globe)) { "Icône globe introuvable" }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }

    // Icône du mode une main (bulle d'Entrée), teintée comme celle du globe.
    private val oneHandedIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_key_one_hand)) { "Icône mode une main introuvable" }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }

    // Icône du clavier flottant (bulle d'Entrée), teintée comme celle du globe.
    private val floatingIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_key_floating)) { "Icône clavier flottant introuvable" }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }

    /** Icônes de la bulle d'Entrée, par symbole fictif ([EnterBubbleGrid]). */
    private val bubbleIcons = mapOf(
        KeyPopup.GLOBE_SYMBOL to globeIcon,
        KeyPopup.ONE_HANDED_SYMBOL to oneHandedIcon,
        KeyPopup.FLOATING_SYMBOL to floatingIcon,
    )

    // Mode une main : boutons de la zone libre (changer de côté, quitter le mode), voir OneHandedControls.
    private val oneHandedSwitchIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_one_hand_switch)) { "Icône changement de côté introuvable" }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }
    private val oneHandedExitIcon = checkNotNull(ContextCompat.getDrawable(context, R.drawable.ic_one_hand_exit)) {
        "Icône sortie du mode une main introuvable"
    }
        .mutate().apply { setTint(context.themeColor(R.color.text_primary)) }
    private val controlFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_functional) }
    private val controlShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_functional_shadow) }
    private val controlPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_pressed) }

    // Animation du mode une main (zone des touches, boutons ronds) ; controlsMode : dernier côté actif, pour dessiner les boutons qui rétrécissent à la sortie.
    private val oneHandedTransition = OneHandedTransition()
    private var controlsMode: OneHandedMode = OneHandedMode.RIGHT
    private var pressedControl: OneHandedControls.Button? = null
        set(value) {
            if (field == value) return
            val animate = ValueAnimator.areAnimatorsEnabled()
            field?.let { oneHandedTransition.setPressed(it, false, animate) }
            value?.let { oneHandedTransition.setPressed(it, true, animate) }
            field = value
            invalidate()
        }

    // Appui long : bulle de symboles (grille) affichée au-dessus de la touche : voir KeyPopup (audit 2.1).
    private val keyPopup = KeyPopup(this)

    // Story 1.6 : vrai quand l'appui long a déjà saisi le chiffre de la touche (le relâchement ne
    // doit alors pas saisir aussi la lettre).
    private var longPressCommitted = false

    // Position de l'appui en cours : sert au glissement vers le bas qui saisit le chiffre d'une touche du haut (DigitSwipe).
    private var downX = 0f
    private var downY = 0f

    // Instant de l'appui en cours (MotionEvent.eventTime, ms) : un chiffre n'est pas saisi par un glissement trop bref (DigitSwipe).
    private var downTime = 0L

    // Touche du haut dont le glissement vers le bas peut encore saisir le chiffre (null : chiffre déjà saisi, autre touche, aucune).
    private var digitSwipeKey: Key? = null

    // Story 1.10 : bulle d'agrandissement de la touche pressée (retour visuel de frappe), affichée
    // au-dessus de la touche tant qu'elle est maintenue. Réservée aux touches qui saisissent un
    // caractère (lettres/chiffres/symboles) : les touches de fonction (espace, maj, retour arrière,
    // entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Disparaît dès qu'une
    // bulle d'accents (1.8) s'affiche par-dessus, ou au relâchement/annulation.
    private val keyPreview = KeyPreview(this)

    private fun showsPreview(key: Key?): Boolean = key?.action is KeyAction.TypeChar

    /**
     * L'appui long sur Entrée ouvre toujours une bulle (sélection par glissement vers le haut, comme les accents) avec la
     * touche du mode une main. Clavier multilingue (activé par l'IME) : le globe s'y ajoute et passe aussi en exposant sur Entrée.
     */
    /** Doigt posé en (x, y) (coordonnées de cette vue) : le fond animé du clavier réagit (voir KeyboardBackgroundDrawable). */
    var touchDownListener: ((Float, Float) -> Unit)? = null

    /** Fond à motif : touches translucides (voir [KeyRenderer.translucentKeys]). */
    var translucentKeys: Boolean
        get() = keyRenderer.translucentKeys
        set(value) {
            if (keyRenderer.translucentKeys == value) return
            keyRenderer.translucentKeys = value
            invalidate()
        }

    var showsGlobeOnEnter = false
        set(value) {
            if (field == value) return
            field = value
            keyRenderer.showsGlobeOnEnter = value
            invalidate()
        }

    /** Action du champ sur la touche Entrée : icône dessinée et nom lu par TalkBack (audit UX-10). */
    var enterActionIcon = EnterAction.Icon.ENTER
        set(value) {
            if (field == value) return
            field = value
            keyRenderer.enterActionIcon = value
            accessibility.invalidateRoot()
            invalidate()
        }

    /** Nom de la langue active, écrit sur la barre d'espace à la place du trait (null : le trait). Voir [KeyRenderer.spaceLabel]. */
    var spaceLabel: String? = null
        set(value) {
            if (field == value) return
            field = value
            keyRenderer.spaceLabel = value
            invalidate()
        }
    private var globeListener: Runnable? = null
    private var oneHandedToggleListener: Runnable? = null
    private var floatingToggleListener: Runnable? = null

    /** Appelé quand la touche clavier flottant de la bulle d'Entrée est relâchée (détache ou recolle le clavier : voir TaipoIme). */
    fun setOnFloatingToggleListener(listener: Runnable) {
        floatingToggleListener = listener
    }
    private var oneHandedSwitchSideListener: Runnable? = null

    /** Appelé quand le bouton « changer de côté » de la zone libre est relâché (mode une main actif : voir TaipoIme). */
    fun setOnOneHandedSwitchSideListener(listener: Runnable) {
        oneHandedSwitchSideListener = listener
    }

    /** Appelé quand la touche mode une main de la bulle d'Entrée est relâchée (active ou quitte le mode : voir TaipoIme). */
    fun setOnOneHandedToggleListener(listener: Runnable) {
        oneHandedToggleListener = listener
    }

    /** Appelé quand la touche globe de la bulle d'Entrée est relâchée (choix de la langue : voir TaipoIme). */
    fun setOnGlobeSelectedListener(listener: Runnable) {
        globeListener = listener
    }

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        digitSwipeAnimation.cancel()
        val digit = key.longPressChar
        if (key.action is KeyAction.Enter) {
            // Bulle du globe (multilingue) et du mode une main : relâcher sur une cellule l'active, relâcher ailleurs ferme la
            // bulle sans envoyer de retour à la ligne (le relâchement avec bulle ouverte ne saisit jamais la touche d'origine).
            showPopup(key, EnterBubbleGrid.grid(multilingual = showsGlobeOnEnter))
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

    /** Chiffre d'une touche du haut saisi par appui long (bulle) ou par glissement vers le bas (rangée de chiffres désactivée). */
    interface OnDigitHintListener {
        fun onLongPressDigit()
        fun onSwipeDigit()
    }

    private var digitHintListener: OnDigitHintListener? = null

    // --- Zones de frappe adaptatives : les zones de toucher des lettres probables grandissent, les touches dessinées non. ---

    /** Bords des zones de toucher de chaque rangée, en unités de poids depuis son début ; null : zones nominales. */
    private class TouchZones(val rows: Array<FloatArray?>)

    private var nextLetters: NextLetterModel.Probabilities? = null
    private var zones: TouchZones? = null

    // Zones figées à l'appui : le glissement et le relâchement de ce toucher se jugent sur les mêmes zones que son appui.
    private var pressZones: TouchZones? = null

    /** Debug seulement : dessine les bords des zones de toucher adaptatives (réglage des paramètres). */
    var showTouchZones = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * Ce qui peut suivre le mot en cours de frappe (null : zones nominales). Appelé après chaque changement du texte, avant
     * la frappe suivante ; les zones sont calculées ici (quelques sommes sur une rangée), pas au moment du toucher.
     */
    fun setNextLetterPrediction(prediction: NextLetterModel.Probabilities?) {
        if (prediction == null && nextLetters == null) return
        nextLetters = prediction
        zones = computeZones()
        if (showTouchZones) invalidate()
    }

    private fun computeZones(): TouchZones? {
        val prediction = nextLetters ?: return null
        val rows = layout.rows
        if (rows.none { TouchTargets.hasAdaptiveKey(it) }) return null
        return TouchZones(
            Array(rows.size) { i ->
                // Une rangée sans lettre (bas du clavier, symboles) garde ses zones nominales.
                rows[i].takeIf { TouchTargets.hasAdaptiveKey(it) }?.let { TouchTargets.rowEdges(it, prediction) }
            },
        )
    }

    fun setOnDigitHintListener(listener: OnDigitHintListener) {
        digitHintListener = listener
    }

    fun setOnCursorMoveListener(listener: OnCursorMoveListener) {
        cursorMoveListener = listener
    }

    fun setOnDeleteSwipeListener(listener: OnDeleteSwipeListener) {
        deleteSwipeListener = listener
    }

    // Story 1.7 : glissement horizontal sur la barre espace = déplacement du curseur. Un caractère tous les 24 dp
    // (12 dp jusqu'au 13/11/2026 : deux fois moins sensible, le curseur filait trop vite).
    private val spaceSwipe = SpaceSwipeTracker(activationPx = dp(16f), stepPx = dp(24f))

    // Story 1.9 : glissement horizontal vers la gauche sur la touche retour arrière = suppression
    // de mots entiers. Seuils plus larges que ceux de la barre espace : le geste doit rester
    // distinct d'un simple appui long sur la touche. Un mot de plus tous les 80 dp (40 dp jusqu'au 13/11/2026 : deux fois
    // moins sensible, trop de mots partaient d'un coup).
    private val backspaceSwipe = BackspaceSwipeTracker(activationPx = dp(24f), stepPx = dp(80f))

    // Animation de l'icône retour arrière (balancier, inclinaison) : seule l'icône bouge, pas la touche.
    private val backspaceAnimation = BackspaceAnimation(
        density = resources.displayMetrics.density,
        animationsEnabled = { ValueAnimator.areAnimatorsEnabled() },
    )
    private val sparkPainter = SparkPainter(
        intArrayOf(
            context.themeColor(R.color.accent_on_key), // lot T6 : violet clair en sombre, violet plein en clair
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

    // Animation « Élastique » du glissement vers le bas qui saisit le chiffre d'une touche du haut : la touche s'étire, la lettre
    // s'enfonce, le chiffre éclot puis, au relâchement, sort par le haut de la touche et revient en petit (voir DigitSwipeAnimation).
    private val digitSwipeAnimation = DigitSwipeAnimation(animationsEnabled = { ValueAnimator.areAnimatorsEnabled() })

    init {
        keyRenderer.shiftAnimation = shiftAnimation
        keyRenderer.spaceBarAnimation = spaceBarAnimation
        keyRenderer.digitSwipeAnimation = digitSwipeAnimation
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        backspaceAnimation.cancel()
        shiftAnimation.cancel()
        spaceBarAnimation.cancel()
        digitSwipeAnimation.cancel()
        oneHandedTransition.stop()
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
        oneHandedTransition.stop()
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
        // Mode une main : le ressort avance avant que la zone des touches soit lue ; les zones de toucher mises en cache suivent.
        if (oneHandedTransition.isRunning) {
            oneHandedTransition.advance(System.nanoTime())
            slotCache = null
        }
        val inset = dims.keyInset
        val usableHeight = usableHeightPx()
        val rowHeight = usableHeight / layout.rows.size.toFloat()
        val content = keyboardWidth()

        val now = System.nanoTime()
        syncShiftAnimation(now)
        if (backspaceAnimation.isRunning) backspaceAnimation.advance(now)
        if (shiftAnimation.isRunning) shiftAnimation.advance(now)
        if (spaceBarAnimation.isRunning) spaceBarAnimation.advance(now)
        if (digitSwipeAnimation.isRunning) digitSwipeAnimation.advance(now)
        keyRenderer.backspaceIconAngle = backspaceAnimation.iconAngle

        val unit = content.widthPx / layout.referenceUnits
        // La touche dont le chiffre s'anime s'étire vers le bas : elle est dessinée en dernier, par-dessus la rangée du dessous.
        var swipingKey: Key? = null
        var swipingLeft = 0f
        var swipingTop = 0f
        var swipingWidth = 0f
        layout.rows.forEachIndexed { rowIndex, row ->
            val top = rowIndex * rowHeight
            var left = content.leftPx + layout.rowStartUnits(row) * unit

            row.forEach { key ->
                val keyWidth = unit * key.weight
                if (digitSwipeAnimation.isAnimating(key)) {
                    swipingKey = key
                    swipingLeft = left
                    swipingTop = top
                    swipingWidth = keyWidth
                } else {
                    keyRenderer.draw(
                        canvas, key, left, top, keyWidth, rowHeight, inset,
                        dims, layout, isShifted, pressedKey,
                    )
                }
                left += keyWidth
            }
        }
        swipingKey?.let { key ->
            keyRenderer.draw(canvas, key, swipingLeft, swipingTop, swipingWidth, rowHeight, inset, dims, layout, isShifted, pressedKey)
        }

        sparkPainter.draw(canvas, shiftAnimation.sparks)
        drawOneHandedControls(canvas)
        drawTouchZones(canvas)
        drawPopup(canvas)
        drawPreview(canvas)
        if (backspaceAnimation.isRunning ||
            shiftAnimation.isRunning ||
            spaceBarAnimation.isRunning ||
            digitSwipeAnimation.isRunning ||
            oneHandedTransition.isRunning
        ) {
            postInvalidateOnAnimation()
        }
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
                pressStart(event.getX(0), event.getY(0), event.eventTime)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Le doigt précédent est considéré comme relâché à sa dernière position connue :
                // sa touche est saisie avant celle du nouveau doigt, sans être perdue.
                val activeIndex = event.findPointerIndex(activePointerId)
                // Frappe rapide : jamais de chiffre par glissement pour ce doigt (il ne s'est pas levé, le suivant est déjà posé).
                if (activeIndex >= 0) releaseTouch(event.getX(activeIndex), event.getY(activeIndex), event.eventTime, digitAllowed = false)
                val index = event.actionIndex
                activePointerId = event.getPointerId(index)
                pressStart(event.getX(index), event.getY(index), event.eventTime)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointerId)
                if (index >= 0) moveTouch(event.getX(index), event.getY(index), event.eventTime)
                return true
            }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                // Le relâchement d'un doigt déjà validé (voir ACTION_POINTER_DOWN) est ignoré.
                if (event.getPointerId(index) == activePointerId) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    releaseTouch(event.getX(index), event.getY(index), event.eventTime)
                    pressZones = null
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

    private fun pressStart(x: Float, y: Float, time: Long) {
        touchDownListener?.invoke(x, y)
        longPressCommitted = false
        pressedControl = controlAt(x, y)
        downX = x
        downY = y
        downTime = time
        pressZones = zones
        spaceSwipe.reset()
        backspaceSwipe.reset()
        deleteSwipeWords = 0
        pressedKey = keyAt(x, y)
        digitSwipeKey = pressedKey?.takeIf { it.longPressChar != null }
        digitSwipeAnimation.cancel()
        pressedKey?.takeIf { it.longPressChar != null }?.let { digitSwipeAnimation.onPress(it) }
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

    private fun moveTouch(x: Float, y: Float, time: Long) {
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
        trackDigitSwipe(x, y)
        if (commitDigitSwipe(x, y, time)) return
        // Le doigt descend depuis une touche du haut : c'est encore elle qui est visée, pas la touche du dessous (DigitSwipe).
        if (keptByDigitSwipe(x, y)) return
        val current = keyAt(x, y)
        if (current != pressedKey) {
            cancelKeyLongPress()
            digitSwipeKey = null
            digitSwipeAnimation.cancel()
            pressedKey = current
            keyPreview.key = current.takeIf { showsPreview(it) }
            startRepeat(current)
            invalidate()
        }
    }

    /**
     * Suit le glissement vers le bas d'une touche du haut pour l'animation « Élastique » du chiffre : la touche s'étire à mesure
     * que le doigt approche du seuil. La bulle d'agrandissement de la lettre disparaît dès que le glissement s'engage.
     */
    private fun trackDigitSwipe(x: Float, y: Float) {
        val key = pressedKey ?: return
        if (!digitSwipeAnimation.isTracking(key)) return
        val keyHeight = keyBounds(key)?.height() ?: return
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        val progress = DigitSwipe.progress(x - downX, y - downY, DigitSwipe.threshold(keyHeight, slop))
        digitSwipeAnimation.onDrag(progress)
        if (progress > PREVIEW_HIDE_PROGRESS) keyPreview.key = null
        invalidate()
    }

    /**
     * Glissement vers le bas sur une touche du haut (rangée de chiffres désactivée : seules ces touches ont un
     * [Key.longPressChar]) : saisit son chiffre dès que le seuil est franchi, le relâchement ne saisit plus la lettre.
     */
    private fun commitDigitSwipe(x: Float, y: Float, time: Long): Boolean {
        val key = pressedKey ?: return false
        val digit = key.longPressChar ?: return false
        if (key != digitSwipeKey) return false
        val keyHeight = keyBounds(key)?.height() ?: return false
        val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        if (!DigitSwipe.isTriggered(x - downX, y - downY, DigitSwipe.threshold(keyHeight, slop), time - downTime)) return false
        cancelKeyLongPress()
        longPressCommitted = true
        digitSwipeKey = null
        keyPreview.key = null
        digitSwipeAnimation.onCommit()
        keyListener?.onKey(Key("longpress_$digit", digit.toString(), KeyAction.TypeChar(digit)))
        digitHintListener?.onSwipeDigit()
        invalidate()
        return true
    }

    /** Vrai si le doigt descend depuis la touche du haut appuyée, sans avoir saisi son chiffre : elle reste la touche visée. */
    private fun keptByDigitSwipe(x: Float, y: Float): Boolean {
        val key = pressedKey ?: return false
        if (key != digitSwipeKey) return false
        return DigitSwipe.keepsKey(x - downX, y - downY, ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    }

    /** Symbole choisi dans une bulle d'appui long : une icône de la bulle d'Entrée (globe, une main, flottant) ou un caractère. */
    private fun onBubbleSymbol(symbol: Char, popupKey: Key?) {
        when (symbol) {
            KeyPopup.GLOBE_SYMBOL -> globeListener?.run()
            KeyPopup.ONE_HANDED_SYMBOL -> oneHandedToggleListener?.run()
            KeyPopup.FLOATING_SYMBOL -> floatingToggleListener?.run()
            else -> {
                keyListener?.onKey(Key("popup_$symbol", symbol.toString(), KeyAction.TypeChar(symbol)))
                // Le chiffre de la touche du haut a été choisi dans la bulle : l'IME peut suggérer le glissement vers le bas.
                if (symbol == popupKey?.longPressChar) digitHintListener?.onLongPressDigit()
            }
        }
    }

    /**
     * Relâchement du doigt actif en (x, y) à l'instant [time] : saisit la touche, le symbole de la bulle, ou rien.
     * [digitAllowed] faux : le glissement vers le bas ne peut plus saisir de chiffre à ce relâchement.
     */
    private fun releaseTouch(x: Float, y: Float, time: Long, digitAllowed: Boolean = true) {
        pressedControl?.let { button ->
            // Bouton de la zone libre : validé seulement si le doigt est relâché encore dessus.
            pressedControl = null
            if (controlAt(x, y) == button) {
                if (button == OneHandedControls.Button.SWITCH_SIDE) oneHandedSwitchSideListener?.run() else oneHandedToggleListener?.run()
            }
            invalidate()
            return
        }
        spaceBarAnimation.onRelease()
        // Doigt levé sans nouveau déplacement depuis que le délai minimal est passé : le glissement saisit le chiffre maintenant.
        val swipeOpen = !keyPopup.isShown && !longPressCommitted
        val keptKey = swipeOpen && keptByDigitSwipe(x, y)
        if (swipeOpen && digitAllowed) commitDigitSwipe(x, y, time)
        digitSwipeAnimation.onRelease()
        stopRepeat()
        cancelKeyLongPress()
        keyPreview.key = null
        if (keyPopup.isShown) {
            releaseOnPopup(x, y)
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
        // Un glissement vers le bas trop court sur une touche du haut saisit sa lettre, même levé sur la rangée du dessous.
        if (key != null && (keyAt(x, y) == key || keptKey)) {
            deliverKey(key, repeat = false)
        }
        invalidate()
    }

    /**
     * Relâchement pendant qu'une bulle est ouverte : relâcher sur un symbole le saisit ; relâcher sur la touche d'une bulle
     * sans choix présélectionné (le point) saisit la touche elle-même ; relâcher ailleurs ferme la bulle sans rien saisir.
     */
    private fun releaseOnPopup(x: Float, y: Float) {
        keyPopup.updateSelection(x, y)
        val symbol = keyPopup.selectedSymbol()
        val popupKey = keyPopup.key
        val typesOwnChar = symbol == null && popupKey?.action is KeyAction.TypeChar && keyPopup.isOnHomeWithoutDefault(x, y)
        dismissPopup()
        pressedKey = null
        if (symbol != null) onBubbleSymbol(symbol, popupKey)
        if (typesOwnChar && popupKey != null) deliverKey(popupKey, repeat = false)
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
        pressedControl = null
        if (backspaceSwipe.isActive) deleteSwipeListener?.onDeleteSwipeCancel()
        backspaceAnimation.onSwipeCancel()
        spaceBarAnimation.onRelease()
        digitSwipeAnimation.onRelease()
        deleteSwipeWords = 0
        stopRepeat()
        cancelKeyLongPress()
        dismissPopup()
        keyPreview.key = null
        longPressCommitted = false
        spaceSwipe.reset()
        backspaceSwipe.reset()
        pressedKey = null
        pressZones = null
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

    private fun isEnterLongPress(key: Key): Boolean = key.action is KeyAction.Enter

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

    private val touchZonePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(TOUCH_ZONE_STROKE_DP)
            color = TOUCH_ZONE_COLOR
        }
    }

    /**
     * Mode une main : disposition des boutons dans la zone libre du mode actif (ou du dernier côté actif pendant que les boutons
     * rétrécissent à la sortie), ou null (boutons absents, zone trop étroite ou trop basse). Taille et hauteur sont celles de la zone
     * finale ; seul le centre horizontal est animé (voir [controlsCenter]).
     */
    private fun oneHandedControlsLayout(): OneHandedControls.Layout? {
        val mode = if (oneHandedMode.isActive) oneHandedMode else controlsMode
        if (!oneHandedMode.isActive && oneHandedTransition.controlsAppear <= CONTROL_HIDDEN_BELOW) return null
        val zone = KeyboardWidth.forAvailableWidth(width.toFloat(), resources.displayMetrics.density, mode, oneHandedWidthPercent)
        val freeLeft = if (mode == OneHandedMode.LEFT) zone.rightPx else 0f
        val freeRight = if (mode == OneHandedMode.LEFT) width.toFloat() else zone.leftPx
        return OneHandedControls.layout(
            freeLeft,
            freeRight,
            usableHeightPx(),
            maxRadius = dp(CONTROL_MAX_RADIUS_DP),
            margin = dp(CONTROL_MARGIN_DP),
            minRadius = dp(CONTROL_MIN_RADIUS_DP),
        )
    }

    /** Centre horizontal des boutons ronds : celui du ressort pendant un mouvement, sinon celui de la zone libre. */
    private fun controlsCenter(layout: OneHandedControls.Layout): Float =
        if (oneHandedTransition.isMoving) oneHandedTransition.controlsCx else layout.cx

    private fun controlAt(x: Float, y: Float): OneHandedControls.Button? {
        if (!oneHandedMode.isActive) return null
        if (oneHandedTransition.isMoving && oneHandedTransition.controlsAppear < CONTROL_TOUCHABLE_FROM) return null
        val layout = oneHandedControlsLayout() ?: return null
        return OneHandedControls.hit(layout.copy(cx = controlsCenter(layout)), x, y)
    }

    /** Mode une main : deux pastilles rondes (face + ombre, comme les touches) dans la zone libre, en ressort (apparition, déplacement, appui). */
    private fun drawOneHandedControls(canvas: Canvas) {
        val layout = oneHandedControlsLayout() ?: return
        val moving = oneHandedTransition.isMoving
        val appear = if (moving) oneHandedTransition.controlsAppear else 1f
        // La flèche de changement de côté pointe vers la zone libre : à droite en mode gauche, retournée en mode droit.
        val flipX = if (moving) {
            oneHandedTransition.flipX
        } else if (oneHandedMode == OneHandedMode.LEFT) {
            1f
        } else {
            -1f
        }
        val cx = controlsCenter(layout)
        val shadow = resources.getDimension(R.dimen.taipo_key_shadow_height)
        OneHandedControls.Button.entries.forEach { button ->
            val scale = appear * oneHandedTransition.pressScale(button)
            if (scale <= CONTROL_HIDDEN_BELOW) return@forEach
            val pressed = pressedControl == button
            val centerY = layout.centerY(button)
            val faceY = centerY + if (pressed) shadow else 0f
            val saved = canvas.save()
            canvas.scale(scale, scale, cx, centerY)
            canvas.drawCircle(cx, centerY + shadow, layout.radius, controlShadowPaint)
            canvas.drawCircle(cx, faceY, layout.radius, if (pressed) controlPressedPaint else controlFacePaint)
            val icon = if (button == OneHandedControls.Button.SWITCH_SIDE) oneHandedSwitchIcon else oneHandedExitIcon
            val half = minOf(dims.iconSize, layout.radius * CONTROL_ICON_RATIO) / 2f
            icon.setBounds((cx - half).roundToInt(), (faceY - half).roundToInt(), (cx + half).roundToInt(), (faceY + half).roundToInt())
            if (button == OneHandedControls.Button.SWITCH_SIDE) canvas.scale(flipX, 1f, cx, faceY)
            icon.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    /** Debug : un trait à chaque bord de zone de toucher adaptatif (entre deux touches voisines d'une rangée adaptative). */
    private fun drawTouchZones(canvas: Canvas) {
        if (!BuildConfig.DEBUG || !showTouchZones) return
        val rows = (pressZones ?: zones)?.rows ?: return
        val rowHeight = usableHeightPx() / layout.rows.size.toFloat()
        val content = keyboardWidth()
        val unit = content.widthPx / layout.referenceUnits
        layout.rows.forEachIndexed { rowIndex, row ->
            val edges = rows.getOrNull(rowIndex) ?: return@forEachIndexed
            val left = content.leftPx + layout.rowStartUnits(row) * unit
            for (i in 1 until edges.size - 1) {
                val x = left + edges[i] * unit
                canvas.drawLine(x, rowIndex * rowHeight, x, (rowIndex + 1) * rowHeight, touchZonePaint)
            }
        }
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
        cornerRadius = dims.popupCornerRadius * bubbleRadiusScale,
        textSize = dims.popupTextSize,
        selectionInset = dims.popupSelectionInset,
        iconSize = dims.iconSize,
        iconMaxRatio = KeyRenderer.KEY_ICON_MAX_RATIO,
        shifted = isShifted,
        icons = bubbleIcons,
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
            cornerRadius = dims.keyCornerRadius * bubbleRadiusScale,
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
        // Story 1.14 : en classe large (et en mode une main), les marges de part et d'autre de la zone des touches sont inertes.
        if ((content.leftPx > 0f || content.rightPx < width.toFloat()) && (x < content.leftPx || x >= content.rightPx)) return null
        // Rangée plus courte que la référence (centrée) : un appui dans la marge revient à la touche la plus proche.
        val unit = content.widthPx / layout.referenceUnits
        val rowStart = content.leftPx + layout.rowStartUnits(row) * unit
        // Zones adaptatives : mêmes règles aux extrémités (au-delà du premier ou du dernier bord, la touche la plus proche).
        val edges = (pressZones ?: zones)?.rows?.getOrNull(rowIndex)
        if (edges != null) return row[TouchTargets.indexAt(edges, (x - rowStart) / unit)]
        var acc = rowStart
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

    /** Story 1.14 : zone horizontale des touches (plein écran en Compact, plafonnée et centrée en large, serrée contre un bord en mode une main). */
    private fun keyboardWidth(): KeyboardWidth =
        if (oneHandedTransition.isMoving) {
            oneHandedTransition.zone // mode une main en cours de ressort : la zone bouge à chaque image, rien n'est mis en cache
        } else {
            contentCache ?: KeyboardWidth.forAvailableWidth(
                width.toFloat(),
                resources.displayMetrics.density,
                oneHandedMode,
                oneHandedWidthPercent,
            ).also { contentCache = it }
        }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        /** Mode une main : rayon maximal, marge et rayon minimal des boutons de la zone libre, et part du diamètre prise par l'icône. */
        const val CONTROL_MAX_RADIUS_DP = 22f
        const val CONTROL_MARGIN_DP = 6f
        const val CONTROL_MIN_RADIUS_DP = 12f
        const val CONTROL_ICON_RATIO = 1.2f

        /** Sous cette échelle un bouton rond n'est ni dessiné ni touchable (il est encore en train d'apparaître ou de disparaître). */
        const val CONTROL_HIDDEN_BELOW = 0.01f
        const val CONTROL_TOUCHABLE_FROM = 0.5f

        /** Avancement du glissement du chiffre à partir duquel la bulle d'agrandissement de la lettre est masquée. */
        const val PREVIEW_HIDE_PROGRESS = 0.15f

        /** Lot 21 : « pas de caractère » (voir [KeyRenderer.shiftedChar]). */
        const val NO_CHAR = KeyRenderer.NO_CHAR

        /** Marge entre le bout du trait et le bord de la barre espace quand le trait est au bout de sa course. */
        private const val SPACE_BAR_EDGE_MARGIN_DP = 8f

        // Calque de debug des zones de toucher adaptatives.
        private const val TOUCH_ZONE_STROKE_DP = 1.5f
        private const val TOUCH_ZONE_COLOR = 0xCCFF00AA.toInt()

        /** Part du délai système d'appui long utilisée pour les touches à pop-up ou à chiffre (−20 %). */
        const val LONG_PRESS_DELAY_RATIO = 0.8f

        /** Un changement de Maj dans les 400 ms qui suivent un appui sur la touche vient de cet appui ; au-delà, c'est la majuscule automatique. */
        const val SHIFT_TAP_WINDOW_NANOS = 400_000_000L
    }
}
