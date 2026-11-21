package fr.junade.taipo

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Story 1.10 : bulle d'agrandissement affichée au-dessus de la touche de caractère pressée, extraite de [KeyboardView]
 * (audit 2.1, étape 8). La vue décide quelle touche est agrandie et fournit sa position, son libellé et les mesures dont la bulle
 * dépend ([Geometry]).
 *
 * Seules les touches de caractère (lettres, chiffres, symboles) ont une bulle : les touches de fonction (espace, maj,
 * retour arrière, entrée, bascule) ont déjà leur propre retour visuel (fond éclairci). Elle disparaît aussitôt ([dismiss]) dès
 * qu'une bulle d'accents (1.8) s'affiche par-dessus, ou à l'annulation.
 *
 * Animée depuis le 16/11/2026 (forme donnée par le style d'animation, [MotionStyle.bubble] ; pose calculée par
 * [KeyPreviewPose]) : la bulle entre au lieu de surgir, et sort au relâchement ([release]), jamais moins de
 * [KeyPreviewPose.MIN_SHOW_MS] après l'appui. Les bulles sont indépendantes : en frappe rapide, plusieurs s'affichent en même
 * temps (sauf Glisse, où la même bulle passe d'une touche à la suivante).
 *
 * Styles du 19/11/2026 (Étincelles, Pixels, Bulle cœur, Éclosion) : la bulle a sa forme (cœur, fleur) et, au relâchement,
 * laisse des particules ([KeyParticles]) qui continuent après elle, dessinées par-dessus les touches et pouvant sortir de la
 * bulle. Animations Android coupées : bulle simple, sans particules.
 */
internal class KeyPreview(private val view: View) {

    /** Mesures et libellés fournis par la vue qui dessine la bulle. */
    interface Geometry {
        /** Emplacement de la touche [key] dans la vue (le RectF peut être réutilisé : il est copié aussitôt), ou null. */
        fun keyBounds(key: Key): RectF?

        /** Texte à afficher dans la bulle de [key] (majuscule comprise), ou null pour ne rien dessiner. */
        fun text(key: Key): String?
        val headroom: Float
        val content: KeyboardWidth
        val maxWidth: Float
        val cornerRadius: Float
        val gap: Float
        val textSize: Float

        /** Retrait de la face de la touche dans son emplacement (Goutte : la bulle part de la face). */
        val keyInset: Float
    }

    /** Style imposé (aperçus de la page « Apparence ») ; null : celui du clavier ([MotionStyle.current]). */
    var styleOverride: MotionStyle? = null

    private val style: MotionStyle get() = styleOverride ?: MotionStyle.current
    private val animation: KeyPreviewAnimation get() = style.bubble

    private class Bubble(var key: Key, val downAt: Long) {
        var exitAt = NOT_EXITING

        /** Particules déjà lancées : celles de l'appui (Pixels) et celles du relâchement. */
        var started = false
        var burst = false

        /** Dernier appui montré par cette bulle (Glisse : celui de la touche qui l'a reprise) : point de départ de la durée minimale. */
        var shownAt = downAt
        var fresh = true

        /**
         * Lettre affichée, lue au premier dessin puis gardée : après le relâchement, la majuscule automatique peut se couper
         * pendant le maintien, la bulle ne doit pas passer de « B » à « b ».
         */
        var text: String? = null
        var previousText: String? = null

        /** Glisse : départ du glissement depuis la dernière position dessinée, et fondu croisé avec [previousText]. */
        var swapAt = 0L
        var moveAt = Long.MIN_VALUE / 2
        var fromLeft = 0f
        var fromTop = 0f
        var lastLeft = Float.NaN
        var lastTop = Float.NaN
    }

    private val bubbles = ArrayList<Bubble>(MAX_BUBBLES)
    private var active: Bubble? = null

    /** Touche dont la bulle est affichée et encore appuyée, ou null. */
    val key: Key? get() = active?.key

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = view.context.themeColor(R.color.key_popup) }
    private val previewTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = view.context.themeColor(R.color.text_primary)
        textAlign = Paint.Align.CENTER
        useTaipoFont(view.context)
    }
    private val popupAlpha = previewPaint.alpha
    private val textAlpha = previewTextPaint.alpha
    private val keyRect = RectF()
    private val previewRect = RectF()
    private val faceRect = RectF()
    private val dropPath = Path()
    private val shapePath = Path()
    private val particles = KeyParticles()
    private val accent = view.context.themeColor(R.color.accent)
    private val accentLight = view.context.themeColor(R.color.accent_on_key)
    private val onAccent = view.context.themeColor(R.color.text_on_accent)
    private val face = previewPaint.color
    private val effectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val ovalRect = RectF()

    /**
     * Appui sur [key] à l'instant [now] (ms, `SystemClock.uptimeMillis`). Null : appui sur une touche sans bulle, la bulle
     * affichée est relâchée. [sliding] : le doigt a glissé d'une touche à l'autre, l'ancienne bulle disparaît aussitôt (sauf
     * Glisse, qui la déplace).
     */
    fun press(key: Key?, now: Long, sliding: Boolean = false) {
        if (key == null) {
            release(now)
            return
        }
        if (active?.key == key) return
        if (animation == KeyPreviewAnimation.GLIDE) {
            val last = bubbles.lastOrNull()
            if (last != null && !last.lastLeft.isNaN()) {
                last.previousText = last.text
                last.text = null
                last.key = key
                last.swapAt = now
                last.moveAt = now
                last.fromLeft = last.lastLeft
                last.fromTop = last.lastTop
                last.exitAt = NOT_EXITING
                last.shownAt = now
                last.fresh = false
                bubbles.retainAll { it === last }
                active = last
                return
            }
        }
        if (sliding) bubbles.clear()
        // Les bulles précédentes poursuivent leur affichage minimal et leur sortie, en parallèle.
        if (bubbles.size >= MAX_BUBBLES) bubbles.removeAt(0)
        Bubble(key, now).also {
            bubbles += it
            active = it
        }
    }

    /** Relâchement de la touche affichée : la bulle sort, une fois affichée au moins [KeyPreviewPose.MIN_SHOW_MS] depuis l'appui. */
    fun release(now: Long) {
        active?.let { it.exitAt = maxOf(now, it.shownAt + KeyPreviewPose.MIN_SHOW_MS) }
        active = null
    }

    /** Fait disparaître toute bulle sans animation (bulle d'accents, rotation, glissement, annulation). */
    fun dismiss() {
        bubbles.clear()
        particles.clear()
        active = null
    }

    /** Vrai tant qu'une bulle bouge (entrée, glissement, maintien ou sortie) : la vue doit demander l'image suivante. */
    fun isRunning(now: Long): Boolean {
        val enter = KeyPreviewPose.enterDurationMs(style)
        return particles.isRunning(now) ||
            bubbles.any { it.exitAt != NOT_EXITING || now - it.downAt < enter || now - it.moveAt < GLIDE_MOVE_MS }
    }

    /** Dessine les bulles à l'instant [now] ; celles dont la sortie est terminée sont oubliées. */
    fun draw(canvas: Canvas, now: Long, g: Geometry) {
        particles.prune(now)
        if (bubbles.isEmpty() && particles.particles.isEmpty()) return
        val animated = ValueAnimator.areAnimatorsEnabled()
        val current = style
        val iterator = bubbles.iterator()
        while (iterator.hasNext()) {
            val b = iterator.next()
            val sinceExit = if (b.exitAt == NOT_EXITING) -1L else now - b.exitAt
            if (animated && current.bubble.hasParticles) launchParticles(b, sinceExit, now, g)
            val pose = KeyPreviewPose.pose(current.bubble, current, now - b.downAt, sinceExit, animated, b.fresh)
            if (pose == null) {
                iterator.remove()
                if (active === b) active = null
            } else {
                drawOne(canvas, b, pose, now, animated, g)
            }
        }
        if (animated) drawParticles(canvas, now)
    }

    /** Lance les particules de [b] : à son premier dessin (Pixels) et au début de sa sortie (tous les styles à particules). */
    private fun launchParticles(b: Bubble, sinceExit: Long, now: Long, g: Geometry) {
        val wantStart = !b.started && style.bubble == KeyPreviewAnimation.PIXELS
        val wantBurst = !b.burst && sinceExit >= 0
        if (!wantStart && !wantBurst) return
        b.started = true
        val bounds = g.keyBounds(b.key) ?: return
        keyRect.set(bounds)
        placeBubble(g)
        val unit = keyRect.width()
        val keyBox = KeyParticles.Box(keyRect.left, keyRect.top, keyRect.width(), keyRect.height())
        if (wantStart) particles.blink(now, keyBox, unit, accentLight)
        if (!wantBurst) return
        b.burst = true
        val start = b.exitAt
        when (style.bubble) {
            KeyPreviewAnimation.SPARKS -> particles.sparks(start, previewRect.centerX(), previewRect.centerY(), unit, accent, accentLight, keyBox)
            KeyPreviewAnimation.PIXELS -> particles.shatter(
                start,
                KeyParticles.Box(previewRect.left, previewRect.top, previewRect.width(), previewRect.height()),
                unit,
                face,
                accent,
            )
            KeyPreviewAnimation.HEART -> particles.heartTrail(
                start,
                previewRect.centerX(),
                previewRect.centerY() + previewRect.height() * HEART_TRAIL_DROP,
                previewRect.width(),
                unit,
                accentLight,
            )
            KeyPreviewAnimation.BLOOM -> {
                val r = bloomRadius()
                val flower = KeyParticles.Flower(
                    previewRect.centerX(),
                    previewRect.centerY(),
                    r * BLOOM_PETAL_BASE,
                    r * BLOOM_PETAL_LENGTH,
                    r * BLOOM_PETAL_WIDTH,
                )
                val colors = IntArray(BLOOM_PETALS) { if (it % 2 == 0) accent else accentLight }
                particles.petalFall(start, flower, unit, colors, keyBox, BLOOM_CORE_COLOR)
            }
            else -> Unit
        }
    }

    private fun drawParticles(canvas: Canvas, now: Long) {
        for (p in particles.particles) {
            if (particles.progress(p, now) < 0f) continue
            val alpha = particles.alphaAt(p, now)
            val x = particles.xAt(p, now)
            val y = particles.yAt(p, now)
            val t = particles.progress(p, now).coerceIn(0f, 1f)
            when (p.kind) {
                KeyParticles.Kind.SPARK -> {
                    sparkPaint.color = p.color
                    sparkPaint.alpha = (alpha * OPAQUE).roundToInt()
                    sparkPaint.strokeWidth = p.size
                    // Traînée : de la position actuelle vers l'arrière, le long de la vitesse du moment.
                    val seconds = (now - p.startAt) / MS_PER_S
                    val vy = p.vy + p.gravity * seconds
                    canvas.drawLine(x, y, x - p.vx * SPARK_TRAIL_S, y - vy * SPARK_TRAIL_S, sparkPaint)
                }
                KeyParticles.Kind.SQUARE, KeyParticles.Kind.BLINK -> {
                    val size = if (p.kind == KeyParticles.Kind.SQUARE) p.size * (1f - SQUARE_SHRINK * t) else p.size
                    fillPaint(p.color, alpha)
                    canvas.drawRect(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f, effectPaint)
                }
                KeyParticles.Kind.HEART -> {
                    fillPaint(p.color, alpha)
                    heartPath(x, y, p.size * (HEART_GROW_FROM + HEART_GROW * KeyPreviewPose.easeOut(t * 2f)))
                    canvas.drawPath(shapePath, effectPaint)
                }
                KeyParticles.Kind.PETAL -> {
                    fillPaint(p.color, alpha)
                    val k = 1f - PETAL_SHRINK * t
                    drawPetal(canvas, x, y, p.size * k, p.size2 * k, particles.angleAt(p, now))
                }
                KeyParticles.Kind.FLASH -> {
                    fillPaint(p.color, alpha)
                    ovalRect.set(x, y, x + p.size, y + p.size2)
                    canvas.drawRoundRect(ovalRect, p.size2 * FLASH_CORNER, p.size2 * FLASH_CORNER, effectPaint)
                }
                KeyParticles.Kind.DOT -> {
                    fillPaint(p.color, alpha)
                    canvas.drawCircle(x, y, p.size * (1f - DOT_SHRINK * t), effectPaint)
                }
            }
        }
    }

    private fun fillPaint(color: Int, alpha: Float) {
        effectPaint.color = color
        effectPaint.alpha = (alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt()
    }

    /** Cœur de demi-largeur ≈ [s] centré en ([cx], [cy]), pointe en bas, dans [shapePath]. */
    private fun heartPath(cx: Float, cy: Float, s: Float) {
        shapePath.rewind()
        shapePath.moveTo(cx, cy + s * 0.95f)
        shapePath.cubicTo(cx - s * 1.25f, cy + s * 0.1f, cx - s * 1.05f, cy - s * 0.95f, cx, cy - s * 0.4f)
        shapePath.cubicTo(cx + s * 1.05f, cy - s * 0.95f, cx + s * 1.25f, cy + s * 0.1f, cx, cy + s * 0.95f)
        shapePath.close()
    }

    /** Pétale (ovale) de base ([x], [y]), de longueur [length] et largeur [width], tourné de [angle] radians (0 : vers le haut). */
    private fun drawPetal(canvas: Canvas, x: Float, y: Float, length: Float, width: Float, angle: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        ovalRect.set(-width / 2f, -length, width / 2f, 0f)
        canvas.drawOval(ovalRect, effectPaint)
        canvas.restore()
    }

    /** Éclosion : rayon de la fleur, d'après la bulle placée ([previewRect]). */
    private fun bloomRadius(): Float = min(previewRect.width(), previewRect.height()) / 2f

    private fun drawOne(canvas: Canvas, b: Bubble, pose: KeyPreviewPose.Pose, now: Long, animated: Boolean, g: Geometry) {
        val bounds = g.keyBounds(b.key) ?: return
        keyRect.set(bounds)
        val text = b.text ?: g.text(b.key)?.also { b.text = it } ?: return
        placeBubble(g)
        if (animation == KeyPreviewAnimation.GLIDE) glideTo(b, now, animated)
        when (animation) {
            KeyPreviewAnimation.DROP -> drawDrop(canvas, pose, text, g)
            KeyPreviewAnimation.HEART -> drawHeart(canvas, pose, text, g)
            KeyPreviewAnimation.BLOOM -> drawBloom(canvas, b, pose, text, now, animated, g)
            else -> drawBubble(canvas, b, pose, text, now, g)
        }
    }

    /** Bulle cœur : un cœur de la couleur d'accent, la lettre au centre, à la place de la bulle arrondie. */
    private fun drawHeart(canvas: Canvas, pose: KeyPreviewPose.Pose, text: String, g: Geometry) {
        val cx = previewRect.centerX()
        val cy = previewRect.centerY()
        val s = previewRect.width() * HEART_SIZE
        canvas.save()
        canvas.translate(pose.offsetX * keyRect.width(), pose.offsetY * keyRect.height())
        canvas.scale(pose.scaleX, pose.scaleY, cx, cy)
        fillPaint(accent, pose.alpha)
        heartPath(cx, cy, s)
        canvas.drawPath(shapePath, effectPaint)
        setTextSize(g.textSize * PREVIEW_SCALE * HEART_TEXT)
        val color = previewTextPaint.color
        previewTextPaint.color = onAccent
        val baseline = cy - s * HEART_TEXT_RAISE - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        drawText(canvas, text, baseline, pose.alpha)
        previewTextPaint.color = color
        canvas.restore()
    }

    /** Éclosion : cinq pétales qui s'ouvrent l'un après l'autre ([KeyPreviewPose.petalOpen]) autour d'un disque portant la lettre. */
    private fun drawBloom(canvas: Canvas, b: Bubble, pose: KeyPreviewPose.Pose, text: String, now: Long, animated: Boolean, g: Geometry) {
        if (pose.alpha <= 0f) return
        val cx = previewRect.centerX()
        val cy = previewRect.centerY()
        val r = bloomRadius()
        val since = now - b.downAt
        for (i in 0 until BLOOM_PETALS) {
            val open = KeyPreviewPose.petalOpen(since, i, animated)
            if (open <= 0f) continue
            val a = (-Math.PI / 2 + i * 2 * Math.PI / BLOOM_PETALS).toFloat()
            fillPaint(if (i % 2 == 0) accent else accentLight, pose.alpha)
            val d = r * BLOOM_PETAL_BASE * open
            // Les pétales tournent un peu en s'ouvrant, comme une corolle qui se déplie.
            drawPetal(
                canvas,
                cx + cos(a) * d,
                cy + sin(a) * d,
                r * BLOOM_PETAL_LENGTH * open,
                r * BLOOM_PETAL_WIDTH * open,
                a + (Math.PI / 2).toFloat() - (1f - open) * BLOOM_TWIST,
            )
        }
        val core = r * BLOOM_CORE * BLOOM_CORE_SCALE * pose.scaleX
        if (core > 0f) {
            fillPaint(face, pose.alpha)
            canvas.drawCircle(cx, cy, core, effectPaint)
        }
        setTextSize(g.textSize * PREVIEW_SCALE * BLOOM_TEXT * BLOOM_CORE_SCALE)
        val baseline = cy - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        drawText(canvas, text, baseline, pose.alpha * pose.scaleX.coerceIn(0f, 1f))
    }

    /** Pose dans [previewRect] la bulle de la touche [keyRect], à sa place finale (calcul d'origine de la story 1.10). */
    private fun placeBubble(g: Geometry) {
        val previewWidth = (keyRect.width() * 1.6f).coerceAtMost(g.maxWidth) * PREVIEW_SCALE
        val previewHeight = keyRect.height() * 1.8f * PREVIEW_SCALE
        // Place disponible au-dessus de la vue : comme la bulle d'accents (1.8), la bulle peut
        // recouvrir la barre d'actions qui la surmonte, pas au-delà de la fenêtre.
        val left = (keyRect.centerX() - previewWidth / 2f)
            .coerceIn(g.content.leftPx, (g.content.rightPx - previewWidth).coerceAtLeast(g.content.leftPx))
        val previewBottom = keyRect.top - g.gap
        val previewTop = (previewBottom - previewHeight).coerceAtLeast(-g.headroom)
        previewRect.set(left, previewTop, left + previewWidth, previewBottom)
    }

    /** Glisse : la bulle va de sa dernière position à celle de la nouvelle touche. */
    private fun glideTo(b: Bubble, now: Long, animated: Boolean) {
        val m = if (animated) KeyPreviewPose.easeOut((now - b.moveAt).toFloat() / GLIDE_MOVE_MS) else 1f
        if (m < 1f) {
            val left = b.fromLeft + (previewRect.left - b.fromLeft) * m
            val top = b.fromTop + (previewRect.top - b.fromTop) * m
            previewRect.offsetTo(left, top)
        }
        b.lastLeft = previewRect.left
        b.lastTop = previewRect.top
    }

    private fun drawBubble(canvas: Canvas, b: Bubble, pose: KeyPreviewPose.Pose, text: String, now: Long, g: Geometry) {
        val pivotX = previewRect.centerX()
        val pivotY = previewRect.bottom
        canvas.save()
        canvas.translate(pose.offsetX * keyRect.width(), pose.offsetY * keyRect.height())
        canvas.scale(pose.scaleX, pose.scaleY, pivotX, pivotY)
        previewPaint.alpha = (popupAlpha * pose.alpha).roundToInt()
        canvas.drawRoundRect(previewRect, g.cornerRadius, g.cornerRadius, previewPaint)
        previewPaint.alpha = popupAlpha
        setTextSize(g.textSize * PREVIEW_SCALE)
        val baseline = previewRect.centerY() - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f +
            pose.letterOffsetY * keyRect.height()
        val previous = b.previousText
        val swap = if (previous == null) 1f else ((now - b.swapAt).toFloat() / GLIDE_SWAP_MS).coerceIn(0f, 1f)
        drawText(canvas, text, baseline, pose.alpha * swap)
        if (previous != null && swap < 1f) drawText(canvas, previous, baseline, pose.alpha * (1f - swap))
        canvas.restore()
    }

    private fun drawText(canvas: Canvas, text: String, baseline: Float, alpha: Float) {
        previewTextPaint.alpha = (textAlpha * alpha).roundToInt()
        canvas.drawText(text, previewRect.centerX(), baseline, previewTextPaint)
        previewTextPaint.alpha = textAlpha
    }

    /**
     * Goutte : une seule forme, de la face de la touche ([faceRect]) à la bulle, reliée par un col qui se pince. [grow] 0 : la
     * forme est la touche ; 1 : la bulle est à sa place.
     */
    private fun drawDrop(canvas: Canvas, pose: KeyPreviewPose.Pose, text: String, g: Geometry) {
        val k = pose.grow
        faceRect.set(keyRect)
        faceRect.inset(g.keyInset, g.keyInset)
        val top = lerp(faceRect.top, previewRect.top, k)
        val left = lerp(faceRect.left, previewRect.left, k)
        val right = lerp(faceRect.right, previewRect.right, k)
        val bottom = lerp(faceRect.top + faceRect.height() * DROP_START_HEIGHT, previewRect.bottom, k.coerceAtMost(1f))
        val r = g.cornerRadius
        val mid = (bottom + faceRect.top) / 2f
        val pinch = k.coerceIn(0f, 1f) * faceRect.width() * DROP_PINCH
        dropPath.rewind()
        dropPath.moveTo(left + r, top)
        dropPath.lineTo(right - r, top)
        dropPath.quadTo(right, top, right, top + r)
        dropPath.lineTo(right, bottom - r)
        dropPath.cubicTo(right, bottom, faceRect.right - pinch, mid, faceRect.right, faceRect.top + r)
        dropPath.lineTo(faceRect.right, faceRect.bottom - r)
        dropPath.quadTo(faceRect.right, faceRect.bottom, faceRect.right - r, faceRect.bottom)
        dropPath.lineTo(faceRect.left + r, faceRect.bottom)
        dropPath.quadTo(faceRect.left, faceRect.bottom, faceRect.left, faceRect.bottom - r)
        dropPath.lineTo(faceRect.left, faceRect.top + r)
        dropPath.cubicTo(faceRect.left + pinch, mid, left, bottom, left, bottom - r)
        dropPath.lineTo(left, top + r)
        dropPath.quadTo(left, top, left + r, top)
        dropPath.close()
        canvas.drawPath(dropPath, previewPaint)
        setTextSize(lerp(g.textSize * PREVIEW_SCALE * DROP_START_TEXT, g.textSize * PREVIEW_SCALE, k.coerceIn(0f, 1f)))
        val baseline = (top + bottom) / 2f - (previewTextPaint.ascent() + previewTextPaint.descent()) / 2f
        canvas.drawText(text, (left + right) / 2f, baseline, previewTextPaint)
    }

    private fun setTextSize(size: Float) {
        if (previewTextPaint.textSize != size) previewTextPaint.textSize = size
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        // Taille de la bulle d'agrandissement (largeur, hauteur et texte) : 60 % de la taille d'origine (0,5 + 20 %).
        const val PREVIEW_SCALE = 0.6f
        const val NOT_EXITING = Long.MAX_VALUE
        const val MAX_BUBBLES = 6
        const val GLIDE_MOVE_MS = 80L
        const val GLIDE_SWAP_MS = 70f
        const val DROP_START_HEIGHT = 0.9f
        const val DROP_PINCH = 0.14f
        const val DROP_START_TEXT = 0.8f

        // Styles du 19/11/2026 (Étincelles, Pixels, Bulle cœur, Éclosion).
        const val OPAQUE = 255
        const val MS_PER_S = 1000f
        const val SPARK_TRAIL_S = 0.035f
        const val SQUARE_SHRINK = 0.4f
        const val HEART_GROW_FROM = 0.6f
        const val HEART_GROW = 0.6f
        const val PETAL_SHRINK = 0.3f
        const val DOT_SHRINK = 0.5f
        const val FLASH_CORNER = 0.18f

        /** Bulle cœur : demi-largeur du cœur en part de la largeur de la bulle, lettre un peu plus petite et remontée. */
        const val HEART_SIZE = 0.62f
        const val HEART_TEXT = 0.9f
        const val HEART_TEXT_RAISE = 0.08f
        const val HEART_TRAIL_DROP = 0.2f

        /** Éclosion : en part du rayon de la fleur. */
        const val BLOOM_PETALS = 5
        const val BLOOM_PETAL_BASE = 0.55f
        const val BLOOM_PETAL_LENGTH = 0.95f
        const val BLOOM_PETAL_WIDTH = 0.55f
        const val BLOOM_CORE = 0.62f
        const val BLOOM_TWIST = 1.2f
        const val BLOOM_TEXT = 0.85f

        /** Rond central de la fleur (et sa lettre) : 40 % plus grand (21/11/2026) ; les pétales gardent leur taille. */
        const val BLOOM_CORE_SCALE = 1.4f

        /** Cœur de la fleur laissé sur la touche (jaune pollen, identique dans tous les thèmes). */
        val BLOOM_CORE_COLOR = 0xFFFFD54A.toInt()
    }
}
