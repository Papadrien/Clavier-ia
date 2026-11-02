package fr.junade.taipo

import kotlin.math.hypot

/**
 * Mode une main : deux boutons ronds dans la zone libre (à côté des touches) pour changer de côté ou quitter le mode.
 * Géométrie pure (sans dépendance Android) pour rester testable en JVM.
 */
object OneHandedControls {

    enum class Button { SWITCH_SIDE, EXIT }

    /** Centre horizontal commun, rayon, et centres verticaux des deux boutons (en pixels dans la vue). */
    data class Layout(val cx: Float, val radius: Float, val switchCy: Float, val exitCy: Float) {
        fun centerY(button: Button): Float = if (button == Button.SWITCH_SIDE) switchCy else exitCy
    }

    /** La zone de toucher d'un bouton est plus grande que son dessin (le pouce est plus gros qu'une petite pastille). */
    const val TOUCH_SCALE = 1.4f

    /** Écart entre les deux boutons, en rayons. */
    private const val GAP_RATIO = 0.5f

    /**
     * Disposition des boutons dans la zone libre [freeLeft, freeRight] de hauteur [usableHeight] : centrés dans la zone, l'un au-dessus de
     * l'autre autour du milieu. Rayon plafonné à [maxRadius], marge [margin] de chaque côté ; null si la zone est trop étroite
     * (rayon sous [minRadius]) ou trop basse : les boutons ne sont alors ni dessinés ni actifs.
     */
    fun layout(freeLeft: Float, freeRight: Float, usableHeight: Float, maxRadius: Float, margin: Float, minRadius: Float): Layout? {
        val zoneWidth = freeRight - freeLeft
        if (zoneWidth <= 0f || usableHeight <= 0f) return null
        val radius = minOf(maxRadius, zoneWidth / 2f - margin)
        if (radius < minRadius) return null
        val gap = radius * GAP_RATIO
        if (4f * radius + gap > usableHeight) return null
        val middle = usableHeight / 2f
        val offset = radius + gap / 2f
        return Layout((freeLeft + freeRight) / 2f, radius, middle - offset, middle + offset)
    }

    /** Bouton touché en ([x], [y]) : le plus proche dont la zone de toucher contient le point, ou null. */
    fun hit(layout: Layout, x: Float, y: Float): Button? {
        val reach = layout.radius * TOUCH_SCALE
        return Button.entries
            .map { it to hypot(x - layout.cx, y - layout.centerY(it)) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }
            ?.first
    }
}
