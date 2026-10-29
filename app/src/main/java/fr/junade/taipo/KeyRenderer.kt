package fr.junade.taipo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Lot 21 : dimensions de la charte lues **une fois** (construction, changement de configuration ou de taille), puis
 * réutilisées au dessin. Avant, chaque touche relisait 4 à 7 dimensions dans les ressources à chaque dessin
 * (de l'ordre de 150 accès par dessin du clavier). Seul le contenu change avec la configuration (orientation, classe de largeur,
 * échelle de police) : [KeyboardView] les relit à ces moments. Extraite de [KeyboardView] (audit 2.1, étape 9).
 */
internal class DrawDimens(context: Context) {
    val keyInset = context.dimen(R.dimen.taipo_key_inset)
    val keyCornerRadius = context.dimen(R.dimen.taipo_key_corner_radius)
    val keyShadowHeight = context.dimen(R.dimen.taipo_key_shadow_height)
    val keyShadowPressedHeight = context.dimen(R.dimen.taipo_key_shadow_pressed_height)
    val spaceBarLength = context.dimen(R.dimen.taipo_space_bar_length)
    val iconSize = context.dimen(R.dimen.taipo_icon_size)
    val keyTextSize = context.dimen(R.dimen.taipo_key_text_size)
    val keyTextSizeLarge = context.dimen(R.dimen.taipo_key_text_size_large)
    val hintTextSize = context.dimen(R.dimen.taipo_key_hint_text_size)
    val hintMarginEnd = context.dimen(R.dimen.taipo_key_hint_margin_end)
    val hintMarginTop = context.dimen(R.dimen.taipo_key_hint_margin_top)
    val popupCornerRadius = context.dimen(R.dimen.taipo_popup_corner_radius)
    val popupTextSize = context.dimen(R.dimen.taipo_popup_text_size)
    val popupSelectionInset = context.dimen(R.dimen.taipo_popup_selection_inset)
    val previewMaxWidth = context.dimen(R.dimen.taipo_preview_max_width)
    val previewGap = context.dimen(R.dimen.taipo_preview_gap)
    val previewTextSize = context.dimen(R.dimen.taipo_preview_text_size)
}

/**
 * Dessin d'une touche (face, ombre, icône, libellé, indice d'appui long), extrait de [KeyboardView] (audit 2.1, étape 9).
 * Le code est celui d'avant l'extraction. La vue garde la disposition, les gestes et les mesures ; elle passe à [draw]
 * l'état utile au dessin (dimensions, disposition, Maj, verrouillage, touche pressée).
 *
 * Lot 21 : aucune allocation au dessin (peintures, icônes, rectangle et tampon de caractère sont réutilisés).
 */
internal class KeyRenderer(private val context: Context) {

    // Charte Taipo (lot 04) : chaque touche = une face + une ombre (épaisseur inférieure), voir draw.
    // Lettres, chiffres, symboles et espace : gris ; virgule, point, emoji, Maj, retour arrière et ABC/123 : violet-gris ;
    // Entrée : violet d'accent (voir keyStyle).
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

    // Animation « Amibe » du trait : forme pleine (capsule à deux rayons) dessinée à la place du trait pendant le glissement.
    var spaceBarAnimation: SpaceBarAnimation? = null
    private val spaceBarBlobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spaceBarBlobPath = Path()
    private val spaceBarTint = context.themeColor(R.color.taipo_purple_light)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.key_hint)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    private val keyRect = RectF()

    // Animation « Élastique » du chiffre (glissement vers le bas sur une touche du haut) : voir DigitSwipeAnimation.
    var digitSwipeAnimation: DigitSwipeAnimation? = null
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.taipo_purple_light)
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    // Icône Maj dessinée au Canvas (flèche creuse, pleine ou verrouillée, animation « Bond ») : l'état vient de la vue.
    var shiftAnimation: ShiftAnimation? = null
    private val shiftPainter = ShiftIconPainter(context.themeColor(R.color.key_label))

    /** Inclinaison de l'icône retour arrière en degrés (balancier et glissement : voir [BackspaceAnimation]), posée par la vue avant chaque dessin. */
    var backspaceIconAngle = 0f

    // Lot 21 : un caractère à dessiner sans passer par String.
    private val pillRect = RectF()
    private val charBuffer = CharArray(1)
    private var lastKeyTextSize = -1f

    // Icônes des touches (lot 09) : VectorDrawable de la charte, teintés une fois, redimensionnés au dessin (aucune
    // allocation dans onDraw). Remplacent les glyphes de police (Maj, Effacer) et les tracés Canvas (Entrée, Emoji).
    private val backspaceIcon = loadKeyIcon(R.drawable.ic_key_backspace)
    private val enterIcon = loadKeyIcon(R.drawable.ic_key_enter)
    private val emojiIcon = loadKeyIcon(R.drawable.ic_key_emoji)
    private val globeIcon = loadKeyIcon(R.drawable.ic_key_globe)

    /** Clavier multilingue actif : la pilule Entrée affiche, à côté du symbole d'entrée, un petit globe (appui long = langue). */
    var showsGlobeOnEnter = false

    // État de la vue pour le dessin en cours, fixé au début de chaque [draw] (aucune allocation).
    private var dims: DrawDimens = DrawDimens(context)
    private var layout: KeyboardLayout = Keyboards.letters
    private var isShifted = false
    private var pressedKey: Key? = null

    /** Relit l'épaisseur du trait de la barre d'espace (changement de configuration). */
    fun refreshDimens() {
        spaceBarPaint.strokeWidth = context.dimen(R.dimen.taipo_space_bar_thickness)
    }

    /**
     * Trait de la barre espace pendant le glissement (animation « Amibe ») : une capsule entre deux cercles. Le bout avant
     * gonfle et le bout arrière s'amincit quand le trait s'étire ; la teinte vire au violet clair. Sans allocation.
     */
    private fun drawSpaceBarBlob(canvas: Canvas, blob: SpaceBarAnimation, cx: Float, cy: Float) {
        val half = dims.spaceBarLength
        val rest = spaceBarPaint.strokeWidth / 2f
        val rightX = cx + half + blob.rightOffset
        val leftX = cx - half + blob.leftOffset
        val stretch = SpaceBarPose.stretch(rightX - leftX, 2f * half)
        val headR = SpaceBarPose.headRadius(stretch, rest)
        val tailR = SpaceBarPose.tailRadius(stretch, rest)
        val rightR = if (blob.headIsRight) headR else tailR
        val leftR = if (blob.headIsRight) tailR else headR
        spaceBarBlobPaint.color = SpaceBarPose.blend(spaceBarPaint.color, spaceBarTint, SpaceBarPose.tint(stretch))
        canvas.drawCircle(rightX, cy, rightR, spaceBarBlobPaint)
        canvas.drawCircle(leftX, cy, leftR, spaceBarBlobPaint)
        spaceBarBlobPath.rewind()
        spaceBarBlobPath.moveTo(leftX, cy - leftR)
        spaceBarBlobPath.lineTo(rightX, cy - rightR)
        spaceBarBlobPath.lineTo(rightX, cy + rightR)
        spaceBarBlobPath.lineTo(leftX, cy + leftR)
        spaceBarBlobPath.close()
        canvas.drawPath(spaceBarBlobPath, spaceBarBlobPaint)
    }

    /** Lot 21 : la majuscule à dessiner à la place du libellé (Maj actif sur une lettre), ou [NO_CHAR]. Sans allocation. */
    fun shiftedChar(key: Key, layout: KeyboardLayout, shifted: Boolean): Char {
        val action = key.action
        return if (action is KeyAction.TypeChar && shifted && layout.id == LayoutId.LETTERS && action.char.isLetter()) {
            action.char.uppercaseChar()
        } else {
            NO_CHAR
        }
    }

    fun displayLabel(key: Key, layout: KeyboardLayout, shifted: Boolean): String = when (val action = key.action) {
        is KeyAction.TypeChar ->
            if (shifted && layout.id == LayoutId.LETTERS && action.char.isLetter()) action.char.uppercaseChar().toString() else key.label

        // Icônes VectorDrawable (lot 09), dessinées par drawKeyIcon.
        KeyAction.Shift, KeyAction.Backspace, KeyAction.Enter -> ""
        KeyAction.Space -> ""
        KeyAction.ToggleLayout -> if (layout.id == LayoutId.LETTERS) "?123" else "ABC"
        KeyAction.SymbolPage -> key.label // « ?123 » (page 1) ou « =\< » (page 2)
        KeyAction.Emoji -> "" // icône dessinée par drawEmojiIcon
    }

    /** Dessine [key] dans la case (left, top, keyWidth, rowHeight), réduite de [inset] de chaque côté. */
    fun draw(
        canvas: Canvas,
        key: Key,
        left: Float,
        top: Float,
        keyWidth: Float,
        rowHeight: Float,
        inset: Float,
        dims: DrawDimens,
        layout: KeyboardLayout,
        isShifted: Boolean,
        pressedKey: Key?,
    ) {
        this.dims = dims
        this.layout = layout
        this.isShifted = isShifted
        this.pressedKey = pressedKey
        val swipe = digitSwipeAnimation?.takeIf { it.isAnimating(key) }
        if (swipe == null) {
            drawKey(canvas, key, left, top, keyWidth, rowHeight, inset)
            return
        }
        // Touche étirée vers le bas sous le doigt : l'échelle se fait depuis le haut de la touche.
        val p = swipe.letterProgress
        canvas.save()
        canvas.scale(DigitSwipePose.faceScaleX(p), DigitSwipePose.faceScaleY(p), left + keyWidth / 2f, top + inset)
        drawKey(canvas, key, left, top, keyWidth, rowHeight, inset)
        canvas.restore()
    }

    private fun drawKey(canvas: Canvas, key: Key, left: Float, top: Float, keyWidth: Float, rowHeight: Float, inset: Float) {
        keyRect.set(left + inset, top + inset, left + keyWidth - inset, top + rowHeight - inset)

        val style = keyStyle(key)
        // Face : la couleur de la charte ; Maj verrouillée / clavier de symboles affiché = état actif (touche à bascule).
        // L'état pressé ne change pas la couleur : il enfonce la touche (voir ci-dessous).
        val face = when {
            key.action is KeyAction.Shift && isShifted -> activePaint
            key.action is KeyAction.ToggleLayout && layout.id.isSymbols -> activePaint
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
        val radius = dims.keyCornerRadius
        val restShadow = minOf(dims.keyShadowHeight, keyRect.height() * MAX_SHADOW_RATIO)
        val shownShadow = if (key == pressedKey) minOf(dims.keyShadowPressedHeight, restShadow) else restShadow
        keyRect.top += restShadow - shownShadow
        if (style == KeyStyle.ACCENT) {
            // Entrée est une pilule : toute la largeur de la touche, extrémités en demi-cercles.
            drawPillKey(canvas, shownShadow, shadow, face)
        } else {
            canvas.drawRoundRect(keyRect, radius, radius, shadow)
            keyRect.bottom -= shownShadow
            canvas.drawRoundRect(keyRect, radius, radius, face)
        }

        when (key.action) {
            KeyAction.Space -> {
                val cx = keyRect.centerX()
                val cy = keyRect.centerY()
                val blob = spaceBarAnimation
                if (blob != null && blob.isRunning) {
                    drawSpaceBarBlob(canvas, blob, cx, cy)
                } else {
                    canvas.drawLine(
                        cx - dims.spaceBarLength, cy, cx + dims.spaceBarLength, cy, spaceBarPaint,
                    )
                }
            }

            KeyAction.Emoji -> drawKeyIcon(canvas, emojiIcon)

            KeyAction.Enter -> drawEnterContent(canvas)

            KeyAction.Shift -> drawShiftIcon(canvas)

            KeyAction.Backspace -> drawKeyIcon(canvas, backspaceIcon, backspaceIconAngle)

            else -> drawKeyLabel(canvas, key)
        }
    }

    /**
     * Libellé de la touche et indice d'appui long. Lot 21 : une lettre en majuscule (Maj actif) est dessinée depuis un
     * tampon de caractère, sans créer de String.
     */
    private fun drawKeyLabel(canvas: Canvas, key: Key) {
        digitSwipeAnimation?.let { swipe ->
            if (swipe.isAnimating(key)) {
                drawSwipingLabel(canvas, key, swipe)
                return
            }
        }
        val upper = shiftedChar(key, layout, isShifted)
        val label = if (upper == NO_CHAR) displayLabel(key, layout, isShifted) else ""
        if (upper != NO_CHAR || label.isNotEmpty()) {
            autoSizeTextPaint(key)
            val baseline = keyRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
            if (upper != NO_CHAR) {
                drawChar(canvas, upper, keyRect.centerX(), baseline, textPaint)
            } else {
                canvas.drawText(label, keyRect.centerX(), baseline, textPaint)
            }
        }
        key.longPressChar?.let { drawLongPressHint(canvas, it) }
    }

    /**
     * Touche pilule (Entrée) : face en capsule sur toute la largeur de la touche (rayon = la moitié de la hauteur de la
     * face, ou de la largeur si la touche est plus haute que large), sur une capsule d'ombre décalée vers le bas. Laisse
     * [keyRect] réduit à la face, pour centrer le contenu.
     */
    private fun drawPillKey(canvas: Canvas, shownShadow: Float, shadow: Paint, face: Paint) {
        val faceHeight = keyRect.height() - shownShadow
        val radius = minOf(keyRect.width(), faceHeight) / 2f
        pillRect.set(keyRect.left, keyRect.top, keyRect.right, keyRect.top + faceHeight)
        pillRect.offset(0f, shownShadow)
        canvas.drawRoundRect(pillRect, radius, radius, shadow)
        pillRect.offset(0f, -shownShadow)
        canvas.drawRoundRect(pillRect, radius, radius, face)
        keyRect.bottom -= shownShadow
    }

    /**
     * Contenu de la pilule Entrée : le symbole d'entrée, seul et centré ; en mode multilingue, suivi d'un globe plus petit
     * (GLOBE_SCALE), l'ensemble étant centré.
     */
    private fun drawEnterContent(canvas: Canvas) {
        if (!showsGlobeOnEnter) {
            drawKeyIcon(canvas, enterIcon)
            return
        }
        val enterSize = minOf(dims.iconSize, keyRect.height() * KEY_ICON_MAX_RATIO)
        val globeSize = enterSize * GLOBE_SCALE
        val gap = enterSize * GLOBE_GAP_RATIO
        val startX = keyRect.centerX() - (enterSize + gap + globeSize) / 2f
        val cy = keyRect.centerY()
        placeIcon(enterIcon, startX + enterSize / 2f, cy, enterSize / 2f)
        enterIcon.draw(canvas)
        placeIcon(globeIcon, startX + enterSize + gap + globeSize / 2f, cy, globeSize / 2f)
        globeIcon.draw(canvas)
    }

    private fun placeIcon(icon: Drawable, cx: Float, cy: Float, half: Float) {
        icon.setBounds(
            (cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(),
        )
    }

    private fun loadKeyIcon(@DrawableRes id: Int): Drawable {
        val icon = checkNotNull(ContextCompat.getDrawable(context, id)) { "Icône de touche introuvable" }
        return icon.mutate().apply { setTint(context.themeColor(R.color.key_label)) }
    }

    /** Lot 09 : icône centrée sur la face de la touche, à [R.dimen.taipo_icon_size] (réduite sur les touches basses). */
    private fun drawKeyIcon(canvas: Canvas, icon: Drawable, angleDegrees: Float = 0f) {
        val half = minOf(dims.iconSize, keyRect.height() * KEY_ICON_MAX_RATIO) / 2f
        val cx = keyRect.centerX()
        val cy = keyRect.centerY()
        icon.setBounds(
            (cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(),
        )
        if (angleDegrees == 0f) {
            icon.draw(canvas)
            return
        }
        // Icône animée (retour arrière) : elle pivote autour de son centre, la touche ne bouge pas.
        canvas.save()
        canvas.rotate(angleDegrees, cx, cy)
        icon.draw(canvas)
        canvas.restore()
    }

    /** Icône Maj centrée sur la face de la touche, à la même taille que les autres icônes de touche. */
    private fun drawShiftIcon(canvas: Canvas) {
        val animation = shiftAnimation ?: return
        val size = minOf(dims.iconSize, keyRect.height() * KEY_ICON_MAX_RATIO)
        shiftPainter.draw(canvas, animation, keyRect.centerX(), keyRect.centerY(), size)
    }

    /**
     * Libellé d'une touche du haut pendant l'animation « Élastique » : la lettre s'enfonce en rétrécissant, le chiffre éclot
     * depuis l'indice jusqu'au centre, puis, au relâchement, sort par le haut de la touche (rogné sur la face) pendant que
     * l'indice revient tout petit à sa place. Sans allocation.
     */
    private fun drawSwipingLabel(canvas: Canvas, key: Key, swipe: DigitSwipeAnimation) {
        val digit = key.longPressChar ?: return
        autoSizeTextPaint(key)
        if (hintPaint.textSize != dims.hintTextSize) hintPaint.textSize = dims.hintTextSize
        val height = keyRect.height()
        val centerX = keyRect.centerX()
        val centerY = keyRect.centerY()
        val hintX = keyRect.right - dims.hintMarginEnd
        val hintCenterY = keyRect.top + dims.hintMarginTop - hintPaint.ascent() + (hintPaint.ascent() + hintPaint.descent()) / 2f

        // Lettre : descend, rétrécit, s'efface ; revient avec un petit rebond au relâchement.
        val p = swipe.letterProgress
        val letterAlpha = DigitSwipePose.letterAlpha(p)
        if (letterAlpha > 0f) {
            val upper = shiftedChar(key, layout, isShifted)
            val baseAlpha = textPaint.alpha
            val baseline = centerY - (textPaint.ascent() + textPaint.descent()) / 2f
            val scale = DigitSwipePose.letterScale(p)
            canvas.save()
            canvas.translate(0f, p * height * DigitSwipePose.LETTER_SHIFT_RATIO)
            canvas.scale(scale, scale, centerX, centerY)
            textPaint.alpha = (baseAlpha * letterAlpha).toInt()
            if (upper != NO_CHAR) {
                drawChar(canvas, upper, centerX, baseline, textPaint)
            } else {
                canvas.drawText(displayLabel(key, layout, isShifted), centerX, baseline, textPaint)
            }
            textPaint.alpha = baseAlpha
            canvas.restore()
        }

        // Chiffre : éclot de l'indice vers le centre, fait un « pop » à la validation, puis sort par le haut au relâchement.
        val exit = swipe.digitExit
        val digitAlpha = DigitSwipePose.digitAlpha(swipe.digitBloom, exit)
        if (digitAlpha > 0f) {
            val bloom = swipe.digitBloom.coerceIn(0f, DigitSwipePose.MAX_BLOOM)
            digitPaint.textSize =
                (dims.hintTextSize + (textPaint.textSize - dims.hintTextSize) * bloom) * (1f + DigitSwipePose.POP_SCALE * swipe.digitPop)
            digitPaint.alpha = (255f * digitAlpha).toInt()
            val x = hintX + (centerX - hintX) * bloom
            val y = hintCenterY + (centerY - hintCenterY) * bloom - exit * height * DigitSwipePose.EXIT_RISE_RATIO
            val clipped = exit > 0f
            if (clipped) {
                canvas.save()
                canvas.clipRect(keyRect)
            }
            drawChar(canvas, digit, x, y - (digitPaint.ascent() + digitPaint.descent()) / 2f, digitPaint)
            if (clipped) canvas.restore()
        }

        // Indice : disparaît quand le chiffre éclot ; revient tout petit une fois le chiffre sorti.
        val hintScale = swipe.hintScale
        if (hintScale > 0f) {
            val baseAlpha = hintPaint.alpha
            hintPaint.textSize = dims.hintTextSize * hintScale.coerceAtMost(DigitSwipePose.MAX_HINT_SCALE)
            hintPaint.alpha = (baseAlpha * DigitSwipePose.hintAlpha(hintScale)).toInt()
            drawChar(canvas, digit, hintX, hintCenterY - (hintPaint.ascent() + hintPaint.descent()) / 2f, hintPaint)
            hintPaint.alpha = baseAlpha
            hintPaint.textSize = dims.hintTextSize
        }
    }

    /** Petit indice du chiffre accessible par appui long, en haut à droite de la touche. */
    private fun drawLongPressHint(canvas: Canvas, char: Char) {
        if (hintPaint.textSize != dims.hintTextSize) hintPaint.textSize = dims.hintTextSize
        drawChar(
            canvas,
            char,
            keyRect.right - dims.hintMarginEnd,
            keyRect.top + dims.hintMarginTop - hintPaint.ascent(),
            hintPaint,
        )
    }

    private fun autoSizeTextPaint(key: Key) {
        val base = when (key.id) {
            // « ?123 » (page des lettres) : police réduite de 30 % ; « ABC » garde la taille large.
            "toggle" -> if (layout.id == LayoutId.LETTERS) dims.keyTextSizeLarge * TOGGLE_123_TEXT_RATIO else dims.keyTextSizeLarge
            "symbol_page" -> dims.keyTextSize * SYMBOL_PAGE_TEXT_RATIO // « ?123 » et « =\< » : 4 signes sur une touche étroite
            else -> dims.keyTextSize
        }
        // Rangées basses (paysage, hauteur réduite) : le texte ne doit pas déborder de la touche.
        // Lot 21 : la taille n'est reposée sur le Paint que si elle change (la plupart des touches ont la même).
        val size = base.coerceAtMost(keyRect.height() * 0.62f)
        if (size != lastKeyTextSize) {
            lastKeyTextSize = size
            textPaint.textSize = size
        }
    }

    /** Lot 21 : dessine un caractère par le tampon [charBuffer] (aucune String créée, contrairement à `Char.toString()`). */
    private fun drawChar(canvas: Canvas, char: Char, x: Float, y: Float, paint: Paint) {
        charBuffer[0] = char
        canvas.drawText(charBuffer, 0, 1, x, y, paint)
    }

    /**
     * Famille de couleurs d'une touche (charte Taipo). Un seul endroit à modifier pour reclasser une touche.
     * Demande du 05/10/2026 : lettres, chiffres, symboles et espace en gris (SECONDARY) ; virgule, point, « @ », « / » et
     * emoji en violet-gris (NORMAL) ; Maj, retour arrière et 123 / ABC restent violet-gris ; Entrée reste violette.
     */
    private enum class KeyStyle { NORMAL, SECONDARY, ACCENT }

    private fun keyStyle(key: Key): KeyStyle = when (key.action) {
        KeyAction.Enter -> KeyStyle.ACCENT
        KeyAction.Shift, KeyAction.Backspace, KeyAction.ToggleLayout, KeyAction.SymbolPage, KeyAction.Emoji -> KeyStyle.NORMAL
        KeyAction.Space -> KeyStyle.SECONDARY
        is KeyAction.TypeChar -> if (key.secondary) KeyStyle.NORMAL else KeyStyle.SECONDARY
    }

    companion object {
        /** Lot 21 : « pas de caractère » (voir [shiftedChar]). */
        const val NO_CHAR = '\u0000'

        /** Taille du libellé des touches de bascule entre les pages de symboles, par rapport à une touche normale. */
        private const val SYMBOL_PAGE_TEXT_RATIO = 0.75f
        private const val TOGGLE_123_TEXT_RATIO = 0.7f

        // Garde-fou : l'ombre ne dépasse jamais cette part de la hauteur de la touche (rangées basses en paysage).
        private const val MAX_SHADOW_RATIO = 0.15f

        /** Icônes des touches : jamais plus de cette part de la hauteur de la face (rangées basses en paysage). */
        const val KEY_ICON_MAX_RATIO = 0.62f

        /** Pilule Entrée en mode multilingue : taille du globe par rapport au symbole d'entrée, et espace entre les deux. */
        private const val GLOBE_SCALE = 0.7f
        private const val GLOBE_GAP_RATIO = 0.3f
    }
}
