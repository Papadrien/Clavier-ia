package fr.junade.taipo

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/*
 * Aides communes d'accessibilité (refonte graphique, lot 20). Le détail de l'audit, des écarts constatés et des
 * corrections est dans docs/accessibilite.md.
 */

/**
 * Fait annoncer cette vue comme un **bouton** par TalkBack : les `TextView` et `ImageView` cliquables (emplacements de
 * suggestion, puce de collage, touches du panneau emoji, cartes) n'ont pas ce rôle par défaut, contrairement à `Button`
 * et `ImageButton`.
 */
internal fun View.announceAsButton() {
    ViewCompat.setAccessibilityDelegate(
        this,
        object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        },
    )
}

/**
 * Étend la zone tactile des boutons enfants d'une barre au-delà de leur dessin (lot 20) : les boutons des barres du haut
 * font 36 dp de haut et 40 dp de large, sous le minimum de 48 dp recommandé par Android et Material. Le rendu ne change pas :
 * le toucher qui tombe dans la marge de la barre (6 dp au-dessus et au-dessous) ou dans l'espace entre deux boutons est
 * transmis au bouton **le plus proche**, dans la limite de [slopPx].
 *
 * Mécanisme : un [TouchDelegate] sur la barre. Il ne reçoit que les gestes qu'aucun enfant n'a pris (la marge, l'espace entre
 * les boutons), comme le fait le `TouchDelegate` du système, mais pour plusieurs enfants à la fois. Les coordonnées sont
 * ramenées dans le bouton choisi ; un doigt qui s'éloigne au-delà de [slopPx] annule l'appui (comme sur un bouton ordinaire).
 */
internal class ExpandedTouchDelegate(private val host: ViewGroup, private val slopPx: Int) : TouchDelegate(Rect(), host) {

    private var target: View? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) target = nearestChild(event.x, event.y)
        val view = target ?: return false
        val x = event.x - view.left
        val y = event.y - view.top
        val inReach = distanceToView(view, event.x, event.y) <= slopPx
        val forwarded = MotionEvent.obtain(event)
        if (inReach) {
            // Dans la zone étendue : le point est ramené sur le bord du bouton, qui le reçoit comme un toucher intérieur.
            forwarded.setLocation(x.coerceIn(0f, (view.width - 1).coerceAtLeast(0).toFloat()), y.coerceIn(0f, (view.height - 1).coerceAtLeast(0).toFloat()))
        } else {
            // Trop loin : hors de la vue, le bouton annule l'appui.
            forwarded.setLocation(-OUTSIDE, -OUTSIDE)
        }
        val handled = view.dispatchTouchEvent(forwarded)
        forwarded.recycle()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) target = null
        return handled
    }

    /** Enfant visible, activé et cliquable (ou conteneur) le plus proche du point (x, y) de la barre, à moins de [slopPx]. */
    private fun nearestChild(x: Float, y: Float): View? {
        var best: View? = null
        var bestDistance = Float.MAX_VALUE
        for (index in 0 until host.childCount) {
            val child = host.getChildAt(index)
            if (child.visibility != View.VISIBLE || !child.isEnabled) continue
            if (!child.isClickable && child !is ViewGroup) continue
            val distance = distanceToView(child, x, y)
            if (distance <= slopPx && distance < bestDistance) {
                best = child
                bestDistance = distance
            }
        }
        return best
    }

    /** Distance (en pixels) entre le point (x, y), en coordonnées de la barre, et le rectangle de [view] ; 0 s'il est dedans. */
    private fun distanceToView(view: View, x: Float, y: Float): Float {
        val dx = maxOf(view.left - x, 0f, x - view.right)
        val dy = maxOf(view.top - y, 0f, y - view.bottom)
        return maxOf(dx, dy)
    }

    private companion object {
        const val OUTSIDE = 10_000f
    }
}

/** Étend la zone tactile des boutons enfants de cette barre à 48 dp (voir [ExpandedTouchDelegate]). */
internal fun ViewGroup.expandChildTouchTargets() {
    touchDelegate = ExpandedTouchDelegate(this, resources.getDimension(R.dimen.taipo_touch_slop).toInt())
}
