package fr.junade.taipo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    val spaceLabelTextSize = context.dimen(R.dimen.taipo_space_label_text_size)
    val spaceLabelMargin = context.dimen(R.dimen.taipo_space_label_margin)
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
    private val spaceBarTint = context.themeColor(R.color.accent_on_key) // lot T6 : violet clair en sombre, violet plein en clair

    /**
     * Nom de la langue active (« Français »), écrit sur la barre d'espace à la place du trait ; null : le trait. Pendant le
     * glissement qui déplace le curseur, le mot suit l'animation « Amibe » : il se décale, s'étire et vire au violet comme le trait.
     */
    var spaceLabel: String? = null
    private val spaceLabelColor = context.themeColor(R.color.key_hint)
    private val spaceLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = spaceLabelColor
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

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

    // Forme des touches (page « Apparence ») : lue à la création, les vues sont recréées quand elle change (KeyboardTheme.Look).
    private val shape = KeyboardTheme.shape
    private val facePath = Path()
    private val plateauRect = RectF()
    private val plateauInset = context.dimen(R.dimen.taipo_key_plateau_inset)
    private val plateauPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.themeColor(R.color.key_plateau) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.themeColor(R.color.key_outline)
        strokeWidth = context.dimen(R.dimen.taipo_key_outline_width)
    }

    // Animation « Élastique » du chiffre (glissement vers le bas sur une touche du haut) : voir DigitSwipeAnimation.
    var digitSwipeAnimation: DigitSwipeAnimation? = null
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.themeColor(R.color.accent_on_key) // lot T6
        textAlign = Paint.Align.CENTER
        useTaipoFont(context)
    }

    // Icône Maj dessinée au Canvas (flèche creuse, pleine ou verrouillée, animation « Bond ») : l'état vient de la vue.
    var shiftAnimation: ShiftAnimation? = null
    private val shiftPainter = ShiftIconPainter(context.themeColor(R.color.key_label))

    /** Inclinaison de l'icône retour arrière en degrés (balancier et glissement : voir [BackspaceAnimation]), posée par la vue avant chaque dessin. */
    var backspaceIconAngle = 0f

    // Lot 21 : un caractère à dessiner sans passer par String.
    private val charBuffer = CharArray(1)
    private var lastKeyTextSize = -1f

    // Icônes des touches (lot 09) : VectorDrawable de la charte, teintés une fois, redimensionnés au dessin (aucune
    // allocation dans onDraw). Remplacent les glyphes de police (Maj, Effacer) et les tracés Canvas (Entrée, Emoji).
    private val backspaceIcon = loadKeyIcon(R.drawable.ic_key_backspace)
    // Lot T6 : Entrée est violette dans les deux thèmes, son icône est donc blanche (text_on_accent), pas la couleur des libellés.
    private val enterIcon = loadKeyIcon(R.drawable.ic_key_enter, R.color.text_on_accent)
    private val emojiIcon = loadKeyIcon(R.drawable.ic_key_emoji)
    // Petit globe en exposant sur la pilule Entrée violette : blanc, légèrement atténué comme l'indice des chiffres (lot T6).
    private val globeIcon = loadKeyIcon(R.drawable.ic_key_globe, R.color.text_on_accent).apply { alpha = GLOBE_ALPHA }

    // Sans clavier multilingue, l'appui long sur Entrée n'ouvre que le mode une main : son icône (celle de la bulle) prend
    // la place du globe, pour signaler qu'il y a quelque chose derrière l'appui long.
    private val oneHandedHintIcon = loadKeyIcon(R.drawable.ic_key_one_hand, R.color.text_on_accent).apply { alpha = GLOBE_ALPHA }

    /** Clavier multilingue actif : la pilule Entrée affiche un petit globe en exposant (appui long = langue). */
    /**
     * Fond du clavier à motif (page « Thème ») : les touches (sauf Entrée) deviennent translucides pour laisser voir le motif.
     * Les lettres gardent leur couleur, donc leur contraste.
     */
    var translucentKeys = false
        set(value) {
            if (field == value) return
            field = value
            val face = if (value) TRANSLUCENT_FACE_ALPHA else OPAQUE
            val shadow = if (value) TRANSLUCENT_SHADOW_ALPHA else OPAQUE
            normalPaint.alpha = face
            secondaryPaint.alpha = face
            activePaint.alpha = face
            normalShadowPaint.alpha = shadow
            secondaryShadowPaint.alpha = shadow
        }

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

    /**
     * Nom de la langue sur la barre d'espace (voir [spaceLabel]). Au repos, centré, réduit s'il dépasse la touche. Pendant le
     * glissement, il reprend les deux bouts du trait animé : décalé vers leur milieu, étiré (en largeur) quand le trait s'étire,
     * teinté comme lui, sans sortir de la touche. Sans allocation.
     */
    private fun drawSpaceLabel(canvas: Canvas, label: String, cx: Float, cy: Float) {
        val paint = spaceLabelPaint
        paint.textSize = dims.spaceLabelTextSize
        val room = keyRect.width() - 2f * dims.spaceLabelMargin
        val textWidth = paint.measureText(label)
        val fit = if (textWidth > room && textWidth > 0f) room / textWidth else 1f
        var shift = 0f
        var scaleX = 1f
        paint.color = spaceLabelColor
        val blob = spaceBarAnimation
        if (blob != null && blob.isRunning) {
            val half = dims.spaceBarLength
            val rightX = half + blob.rightOffset
            val leftX = -half + blob.leftOffset
            val stretch = SpaceBarPose.stretch(rightX - leftX, 2f * half)
            scaleX = (1f + stretch * LABEL_STRETCH).coerceIn(LABEL_MIN_SCALE, LABEL_MAX_SCALE)
            paint.color = SpaceBarPose.blend(spaceLabelColor, spaceBarTint, SpaceBarPose.tint(stretch))
            val maxShift = ((room - textWidth * fit * scaleX) / 2f).coerceAtLeast(0f)
            shift = ((rightX + leftX) / 2f).coerceIn(-maxShift, maxShift)
        }
        val save = canvas.save()
        canvas.translate(cx + shift, cy)
        canvas.scale(fit * scaleX, fit)
        canvas.drawText(label, 0f, -(paint.ascent() + paint.descent()) / 2f, paint)
        canvas.restoreToCount(save)
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
        // L'état pressé enfonce la touche ; sans ombre (formes Plat, Contour, Sans fond), il change la couleur (voir drawFace).
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
        drawFace(canvas, key, style, face, shadow)

        when (key.action) {
            KeyAction.Space -> {
                val cx = keyRect.centerX()
                val cy = keyRect.centerY()
                val blob = spaceBarAnimation
                val label = spaceLabel
                if (label != null) {
                    drawSpaceLabel(canvas, label, cx, cy)
                } else if (blob != null && blob.isRunning) {
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
     * Face et ombre de la touche, selon la forme choisie ([shape], voir [KeyShape]). Ombre = la face décalée vers le bas ; au
     * repos, l'ombre visible fait [taipo_key_shadow_height] (multipliée par [KeyShape.shadowFactor]). Pressée, la touche s'enfonce :
     * l'ombre visible se réduit à [taipo_key_shadow_pressed_height] et la face descend de la différence (sa taille ne change pas).
     * Sans ombre (Plat, Contour, Sans fond), l'appui se voit à la couleur de la face. L'emprise de référence (zones tactiles,
     * dimensions) ne change pas. Laisse [keyRect] réduit à la face, pour centrer le contenu. Sans allocation.
     */
    private fun drawFace(canvas: Canvas, key: Key, style: KeyStyle, face: Paint, shadow: Paint) {
        val accent = style == KeyStyle.ACCENT
        val pressed = key == pressedKey
        val toggled = face === activePaint
        val maxShadow = keyRect.height() * (if (shape.hasPlateau) MAX_MECHANICAL_SHADOW_RATIO else MAX_SHADOW_RATIO)
        val restShadow = minOf(dims.keyShadowHeight * shape.shadowFactor, maxShadow)
        val shownShadow = if (pressed) minOf(dims.keyShadowPressedHeight * shape.shadowFactor, restShadow) else restShadow
        keyRect.top += restShadow - shownShadow
        keyRect.bottom -= shownShadow
        // Face dessinée : le cercle de Rond est centré dans la touche, les autres formes en occupent toute la place.
        val width = keyRect.width()
        val height = keyRect.height()
        val faceWidth = shape.faceWidth(accent, width, height)
        val faceHeight = shape.faceHeight(accent, width, height)
        keyRect.inset((width - faceWidth) / 2f, (height - faceHeight) / 2f)
        val corner = shape.cornerSize(accent, faceWidth, faceHeight, dims.keyCornerRadius)
        when (shape.body(accent)) {
            KeyShape.FaceBody.FILLED -> {
                if (shownShadow > 0f) {
                    keyRect.offset(0f, shownShadow)
                    drawShape(canvas, corner, shadow)
                    keyRect.offset(0f, -shownShadow)
                }
                drawShape(canvas, corner, if (pressed && shape.pressTintsFace && !accent) activePaint else face)
                if (shape.hasPlateau) drawPlateau(canvas, corner)
            }
            // Touche creuse : un trait ; pleine sous le doigt et quand elle est active (Maj, symboles).
            KeyShape.FaceBody.OUTLINED -> {
                if (pressed || toggled) drawShape(canvas, corner, if (toggled) activePaint else face)
                val half = outlinePaint.strokeWidth / 2f
                keyRect.inset(half, half)
                drawShape(canvas, maxOf(corner - half, 0f), outlinePaint)
                keyRect.inset(-half, -half)
            }
            // Sans fond : rien au repos, la face sous le doigt et quand la touche est active.
            KeyShape.FaceBody.BARE -> if (pressed || toggled) drawShape(canvas, corner, if (toggled) activePaint else face)
        }
    }

    /** Silhouette de la forme dans [keyRect] : rectangle arrondi de rayon [corner], ou coins coupés de [corner] (Biseau). */
    private fun drawShape(canvas: Canvas, corner: Float, paint: Paint) {
        if (shape.outline() == KeyShape.Outline.ROUNDED_RECT) {
            canvas.drawRoundRect(keyRect, corner, corner, paint)
            return
        }
        val r = keyRect
        facePath.rewind()
        facePath.moveTo(r.left + corner, r.top)
        facePath.lineTo(r.right - corner, r.top)
        facePath.lineTo(r.right, r.top + corner)
        facePath.lineTo(r.right, r.bottom - corner)
        facePath.lineTo(r.right - corner, r.bottom)
        facePath.lineTo(r.left + corner, r.bottom)
        facePath.lineTo(r.left, r.bottom - corner)
        facePath.lineTo(r.left, r.top + corner)
        facePath.close()
        canvas.drawPath(facePath, paint)
    }

    /**
     * Mécanique : plateau éclairé sur le haut de la face (keycap). Le contenu remonte au centre du plateau ([keyRect] réduit d'autant).
     */
    private fun drawPlateau(canvas: Canvas, corner: Float) {
        val inset = minOf(plateauInset, keyRect.width() / 4f, keyRect.height() / 4f)
        plateauRect.set(keyRect.left + inset, keyRect.top + inset * PLATEAU_TOP_RATIO, keyRect.right - inset, keyRect.bottom - inset * 2f)
        val radius = maxOf(corner - inset / 2f, 0f)
        canvas.drawRoundRect(plateauRect, radius, radius, plateauPaint)
        keyRect.bottom -= inset
    }

    /**
     * Contenu de la pilule Entrée : le symbole d'entrée, centré, et en exposant, en haut à droite comme l'indice des chiffres
     * (replié vers l'intérieur pour rester dans la courbe de la pilule), ce qu'ouvre l'appui long : un petit globe en mode
     * multilingue, sinon l'icône du mode une main.
     */
    private fun drawEnterContent(canvas: Canvas) {
        drawKeyIcon(canvas, enterIcon)
        val hintIcon = if (showsGlobeOnEnter) globeIcon else oneHandedHintIcon
        val size = dims.hintTextSize * GLOBE_HINT_SCALE
        val half = size / 2f
        val radius = minOf(keyRect.width(), keyRect.height()) / 2f
        val cy = keyRect.top + dims.hintMarginTop + half
        // Coin haut-droit de l'icône (cx + half, cy - half) à l'intérieur du cercle de l'extrémité droite de la pilule.
        val reach = radius * GLOBE_EDGE_RATIO
        val dy = (cy - half) - (keyRect.top + radius)
        val dx = if (reach > abs(dy)) sqrt(reach * reach - dy * dy) else 0f
        val cx = keyRect.right - radius + dx - half
        placeIcon(hintIcon, cx, cy, half)
        hintIcon.draw(canvas)
    }

    private fun placeIcon(icon: Drawable, cx: Float, cy: Float, half: Float) {
        icon.setBounds(
            (cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt(),
        )
    }

    private fun loadKeyIcon(@DrawableRes id: Int, @ColorRes tint: Int = R.color.key_label): Drawable {
        val icon = checkNotNull(ContextCompat.getDrawable(context, id)) { "Icône de touche introuvable" }
        return icon.mutate().apply { setTint(context.themeColor(tint)) }
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
        /**
         * Touches translucides sur un fond à motif : face à 73 %, ombre à 60 %. Revue du 10/11/2026 : transparence réduite de 30 %
         * (maquettes : face à 62 %, ombre à 43 %, soit 38 % et 57 % de transparence, ramenées à 27 % et 40 %).
         */
        const val TRANSLUCENT_FACE_ALPHA = 187
        const val TRANSLUCENT_SHADOW_ALPHA = 153
        private const val OPAQUE = 255

        /** Lot 21 : « pas de caractère » (voir [shiftedChar]). */
        const val NO_CHAR = '\u0000'

        /** Taille du libellé des touches de bascule entre les pages de symboles, par rapport à une touche normale. */
        private const val SYMBOL_PAGE_TEXT_RATIO = 0.75f
        private const val TOGGLE_123_TEXT_RATIO = 0.7f

        /**
         * Nom de la langue sur l'espace pendant le glissement : part de l'allongement du trait reprise en largeur par le mot
         * (le trait est court, le mot bien plus long : l'étirer autant le rendrait illisible), bornée.
         */
        private const val LABEL_STRETCH = 0.3f
        private const val LABEL_MIN_SCALE = 0.85f
        private const val LABEL_MAX_SCALE = 1.3f

        // Garde-fou : l'ombre ne dépasse jamais cette part de la hauteur de la touche (rangées basses en paysage).
        private const val MAX_SHADOW_RATIO = 0.15f

        /** Mécanique : le flanc, plus épais, peut aller jusqu'à 22 % de la hauteur de la touche. */
        private const val MAX_MECHANICAL_SHADOW_RATIO = 0.22f

        /** Mécanique : marge du plateau en haut, en part de sa marge sur les côtés. */
        private const val PLATEAU_TOP_RATIO = 0.6f

        /** Icônes des touches : jamais plus de cette part de la hauteur de la face (rangées basses en paysage). */
        const val KEY_ICON_MAX_RATIO = 0.62f

        /** Pilule Entrée en mode multilingue : taille du globe par rapport à la taille de l'indice des chiffres, et marge au bord courbe. */
        private const val GLOBE_HINT_SCALE = 1.3f
        private const val GLOBE_EDGE_RATIO = 0.88f

        /** Opacité (sur 255) du petit globe blanc de la pilule Entrée : l'indice des chiffres est à 70 % dans la charte. */
        private const val GLOBE_ALPHA = 179
    }
}
