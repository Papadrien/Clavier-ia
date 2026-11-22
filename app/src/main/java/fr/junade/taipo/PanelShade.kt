package fr.junade.taipo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.ceil

/**
 * Animation « store » des panneaux qui remplacent les touches (panneau emoji, panneau IA ; 21/11/2026) : à l'ouverture, le
 * panneau descend du haut de la zone des touches comme un store qu'on tire et se pose en ressort (celui du style d'animation,
 * [MotionStyle]) ; les touches s'assombrissent derrière lui. À la fermeture, il remonte et les touches s'éclairent. Le panneau
 * est coupé au bord haut de la zone des touches : il ne passe jamais sur la barre du haut. Animations Android coupées :
 * changement direct.
 */
internal object PanelShade {

    /** Ouvre [panel] par-dessus [keys] (même zone) ; [keys] finit masqué (INVISIBLE : il garde sa taille). */
    fun show(panel: View, keys: View) {
        cancel(panel)
        panel.visibility = View.VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled() || keys.height == 0) {
            settleShown(panel, keys)
            return
        }
        val travel = keys.height.toFloat()
        val style = MotionStyle.current
        start(
            panel,
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = style.revealDurationMs
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val p = BarReveal.springProgress(it.animatedValue as Float, style)
                    place(panel, -travel * (1f - p))
                    keys.alpha = 1f - DIMMED * p.coerceIn(0f, 1f)
                }
            },
        ) { settleShown(panel, keys) }
    }

    /** Referme [panel] (il remonte) ; [keys] revient. [onHidden] : une fois le panneau masqué. */
    fun hide(panel: View, keys: View, onHidden: () -> Unit = {}) {
        cancel(panel)
        keys.visibility = View.VISIBLE
        if (!ValueAnimator.areAnimatorsEnabled() || panel.visibility != View.VISIBLE || panel.height == 0) {
            settleHidden(panel, keys)
            onHidden()
            return
        }
        val travel = panel.height.toFloat()
        keys.alpha = 1f - DIMMED
        start(
            panel,
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = HIDE_MS
                interpolator = AccelerateInterpolator()
                addUpdateListener {
                    val q = it.animatedValue as Float
                    place(panel, -travel * q)
                    keys.alpha = 1f - DIMMED * (1f - q)
                }
            },
        ) {
            settleHidden(panel, keys)
            onHidden()
        }
    }

    /** Referme [panel] sans animation (changement de champ, rotation, autre panneau qui s'ouvre). */
    fun hideNow(panel: View, keys: View) {
        cancel(panel)
        settleHidden(panel, keys)
    }

    /** Arrête une animation en cours sur [panel] (elle ne termine pas son effet). */
    fun cancel(panel: View) {
        (panel.getTag(R.id.tag_panel_shade) as? Animator)?.let {
            panel.setTag(R.id.tag_panel_shade, null)
            it.removeAllListeners()
            it.cancel()
        }
    }

    private fun start(panel: View, animator: ValueAnimator, onEnd: () -> Unit) {
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    panel.setTag(R.id.tag_panel_shade, null)
                    onEnd()
                }
            },
        )
        panel.setTag(R.id.tag_panel_shade, animator)
        animator.start()
    }

    /**
     * Découpe réutilisée d'une image à l'autre (pas d'allocation pendant l'animation) : [View.setClipBounds] en garde une copie.
     * Thread principal seulement, comme toutes les animations de vues.
     */
    private val clip = Rect()

    /** Décale [panel] de [offsetY] (négatif : vers le haut) et coupe ce qui dépasse au-dessus de la zone des touches. */
    private fun place(panel: View, offsetY: Float) {
        panel.translationY = offsetY
        val hiddenTop = ceil(-offsetY).toInt().coerceAtLeast(0)
        clip.set(0, hiddenTop, CLIP_WIDTH, CLIP_WIDTH)
        panel.clipBounds = clip
    }

    private fun settleShown(panel: View, keys: View) {
        panel.translationY = 0f
        panel.clipBounds = null
        keys.visibility = View.INVISIBLE
        keys.alpha = 1f
    }

    private fun settleHidden(panel: View, keys: View) {
        panel.visibility = View.GONE
        panel.translationY = 0f
        panel.clipBounds = null
        keys.visibility = View.VISIBLE
        keys.alpha = 1f
    }

    /** Assombrissement des touches derrière le panneau ouvert (part d'opacité retirée). */
    private const val DIMMED = 0.7f

    /** Fermeture : plus rapide que l'ouverture, sans rebond. */
    private const val HIDE_MS = 240L

    /** Largeur et hauteur de la découpe : assez pour ne jamais couper sur les côtés ni en bas. */
    private const val CLIP_WIDTH = 100_000
}
