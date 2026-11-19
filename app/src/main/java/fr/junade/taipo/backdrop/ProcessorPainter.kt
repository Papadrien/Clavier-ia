package fr.junade.taipo.backdrop

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import java.util.IdentityHashMap
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Processeur (maquette « Circuit » retenue le 16/11/2026) : circuit imprimé dans la couleur d'accent, un processeur au centre et
 * des bus de pistes qui en partent vers les bords (plan : [CircuitLayout]). Chaque touche envoie trois paquets de données le
 * long des bus les plus proches d'elle, vers le processeur ; à leur arrivée, la broche s'allume et le cœur scintille. Le cœur
 * chauffe aussi avec l'élan de frappe ([BackdropMotion.energy]). Comme les autres fonds, plus rien ne bouge une fois tout éteint.
 */
internal class ProcessorPainter : BackdropPainter() {
    /** Paquet : part de la position [start] (le long du bus [trace]) et file vers le processeur (position 0). */
    private class Packet(val trace: Int, val start: Float)

    private var layout: CircuitLayout? = null
    private val paths = ArrayList<Path>()
    private val measures = ArrayList<PathMeasure>()
    private val packets = IdentityHashMap<BackdropMotion.Hit, List<Packet>>()
    private var pinGlow = FloatArray(0)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val segment = Path()
    private val rect = RectF()
    private val position = FloatArray(2)

    override fun prepare(w: Float, h: Float, random: Random) {
        val plan = CircuitLayout.generate(w, h, random)
        layout = plan
        paths.clear()
        measures.clear()
        plan.traces.forEach { t ->
            val path = Path()
            path.moveTo(t.xs[0], t.ys[0])
            for (i in 1 until t.xs.size) path.lineTo(t.xs[i], t.ys[i])
            paths += path
            measures += PathMeasure(path, false)
        }
        pinGlow = FloatArray(plan.traces.size)
        packets.clear()
    }

    override fun draw(canvas: Canvas, w: Float, h: Float, colors: BackdropColors, motion: BackdropMotion) {
        val plan = layout ?: return
        drawBoard(canvas, plan, colors)
        val heat = drawPackets(canvas, plan, colors, motion)
        drawCore(canvas, plan, colors, motion, heat)
    }

    // ------------------------------------------------------------------ Circuit au repos

    private fun drawBoard(canvas: Canvas, plan: CircuitLayout, colors: BackdropColors) {
        val cell = plan.cell
        line.strokeWidth = cell * TRACE_WIDTH
        line.color = BackdropColors.withAlpha(colors.accent, if (colors.dark) 0.32f else 0.3f)
        paths.forEach { canvas.drawPath(it, line) }
        val via = BackdropColors.withAlpha(colors.accent, if (colors.dark) 0.45f else 0.42f)
        plan.traces.forEach { t ->
            drawVia(canvas, t.xs.last(), t.ys.last(), cell, via, colors.base)
            if (t.startsAtVia) drawVia(canvas, t.xs[0], t.ys[0], cell, via, colors.base)
        }
        // Pastilles des broches, puis corps des composants par-dessus.
        fill.color = BackdropColors.withAlpha(colors.accent, 0.5f)
        plan.pads.forEach { p ->
            val horizontal = p.direction == 0 || p.direction == 4
            val pw = cell * if (horizontal) 0.4f else 0.32f
            val ph = cell * if (horizontal) 0.32f else 0.4f
            val left = when (p.direction) {
                0 -> p.x
                4 -> p.x - pw
                else -> p.x - pw / 2f
            }
            val top = when (p.direction) {
                2 -> p.y
                6 -> p.y - ph
                else -> p.y - ph / 2f
            }
            canvas.drawRect(left, top, left + pw, top + ph, fill)
        }
        val body = BackdropColors.mix(colors.base, colors.accent, if (colors.dark) 0.14f else 0.1f)
        val edge = BackdropColors.withAlpha(colors.accent, 0.45f)
        val mark = BackdropColors.withAlpha(colors.accent, 0.6f)
        line.strokeWidth = cell * 0.1f
        plan.parts.forEach { part -> drawPart(canvas, part, cell, body, edge, mark) }
        val cpu = plan.cpu
        rect.set(cpu.left + cell * 0.15f, cpu.top + cell * 0.15f, cpu.right - cell * 0.15f, cpu.bottom - cell * 0.15f)
        fill.color = body
        canvas.drawRoundRect(rect, cell * 0.25f, cell * 0.25f, fill)
        line.color = edge
        canvas.drawRoundRect(rect, cell * 0.25f, cell * 0.25f, line)
        dieRect(cpu)
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, line)
        fill.color = mark
        canvas.drawCircle(cpu.left + cell * 0.5f, cpu.top + cell * 0.5f, cell * 0.14f, fill)
        label.textSize = cell * 0.8f
        label.color = BackdropColors.withAlpha(colors.accent, 0.55f)
        canvas.drawText(CPU_LABEL, cpu.centerX, cpu.bottom - cell * 0.5f, label)
    }

    private fun drawPart(canvas: Canvas, part: CircuitLayout.Part, cell: Float, body: Int, edge: Int, mark: Int) {
        val b = part.box
        val inset = if (part.kind == CircuitLayout.Kind.RESISTOR) 0.22f else 0.15f
        rect.set(b.left + cell * 0.1f, b.top + b.height * inset, b.right - cell * 0.1f, b.bottom - b.height * inset)
        fill.color = body
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, fill)
        line.color = edge
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, line)
        fill.color = mark
        val band = cell * 0.16f
        if (part.kind == CircuitLayout.Kind.RESISTOR) {
            for (k in 0 until 3) {
                val x = b.left + b.width * (0.3f + k * 0.17f)
                canvas.drawRect(x, rect.top, x + band, rect.bottom, fill)
            }
        } else {
            canvas.drawRect(rect.left + cell * 0.2f, rect.top, rect.left + cell * 0.2f + band, rect.bottom, fill)
        }
    }

    private fun drawVia(canvas: Canvas, x: Float, y: Float, cell: Float, color: Int, base: Int) {
        fill.color = color
        canvas.drawCircle(x, y, cell * VIA_RADIUS, fill)
        fill.color = base
        canvas.drawCircle(x, y, cell * VIA_RADIUS * 0.45f, fill)
    }

    // ------------------------------------------------------------------ Paquets

    /** Dessine les paquets en route et les broches atteintes ; renvoie la chaleur apportée au cœur par les arrivées. */
    private fun drawPackets(canvas: Canvas, plan: CircuitLayout, colors: BackdropColors, motion: BackdropMotion): Float {
        if (motion.hits.isEmpty()) {
            packets.clear()
            return 0f
        }
        packets.keys.retainAll(motion.hits.toSet())
        pinGlow.fill(0f)
        val cell = plan.cell
        val speed = cell * PACKET_SPEED_CELLS
        var heat = 0f
        for (hit in motion.hits) {
            val fade = max(0f, 1f - hit.age / BackdropMotion.HIT_LIFETIME_S)
            for (packet in packets.getOrPut(hit) { route(plan, hit) }) {
                val arrival = drawPacket(canvas, packet, packet.start - hit.age * speed, fade, plan.cell, colors)
                heat += arrival * HEAT_PER_ARRIVAL
                pinGlow[packet.trace] = max(pinGlow[packet.trace], arrival)
            }
        }
        plan.traces.forEachIndexed { i, t ->
            val v = pinGlow[i]
            if (v < MIN_GLOW) return@forEachIndexed
            glowSegment(canvas, i, 0f, cell * 1.8f, v, cell, colors)
            fill.color = BackdropColors.withAlpha(colors.glow, v)
            canvas.drawCircle(t.xs[0], t.ys[0], cell * 0.24f, fill)
        }
        return heat
    }

    /**
     * Paquet à la position [s] de son bus : en route ([s] > 0), dessine sa tête brillante et son sillage et renvoie 0 ; arrivé,
     * renvoie l'éclat de son arrivée (1 à l'instant de l'arrivée, puis décroissant), qui allume la broche et chauffe le cœur.
     */
    private fun drawPacket(canvas: Canvas, packet: Packet, s: Float, fade: Float, cell: Float, colors: BackdropColors): Float {
        if (s <= 0f) return exp(-(-s / (cell * PACKET_SPEED_CELLS)) * ARRIVAL_DECAY) * fade
        val head = cell * PACKET_LENGTH_CELLS
        // Sillage pâle jusqu'au point de départ, tête brillante, puis la lueur du paquet lui-même.
        glowSegment(canvas, packet.trace, s + head, packet.start, TRAIL_GLOW * fade, cell, colors)
        glowSegment(canvas, packet.trace, s, min(s + head, packet.start), 1f, cell, colors)
        measures[packet.trace].getPosTan(s, position, null)
        fill.color = BackdropColors.withAlpha(colors.glow, 0.4f)
        canvas.drawCircle(position[0], position[1], cell * 0.5f, fill)
        fill.color = if (colors.dark) Color.WHITE else colors.glow
        canvas.drawCircle(position[0], position[1], cell * 0.22f, fill)
        return 0f
    }

    /** Les [PACKETS] bus qui passent le plus près de la touche ; chaque paquet part du point du bus le plus proche d'elle. */
    private fun route(plan: CircuitLayout, hit: BackdropMotion.Hit): List<Packet> =
        plan.traces.withIndex()
            .filter { it.value.fromCpu }
            .map { (i, t) -> i to t.nearest(hit.x, hit.y) }
            .sortedBy { it.second.distance }
            .take(PACKETS)
            .map { (i, n) -> Packet(i, max(n.position, plan.cell)) }

    /** Trait lumineux d'intensité [v] sur la portion [from]–[to] du bus [trace] : halo large et pâle sous un trait net. */
    private fun glowSegment(canvas: Canvas, trace: Int, from: Float, to: Float, v: Float, cell: Float, colors: BackdropColors) {
        if (v < MIN_GLOW || to <= from) return
        val k = min(1f, v)
        segment.rewind()
        if (!measures[trace].getSegment(from, to, segment, true)) return
        line.strokeWidth = cell * (0.3f + k * 0.5f)
        line.color = BackdropColors.withAlpha(colors.accent, k * if (colors.dark) 0.38f else 0.26f)
        canvas.drawPath(segment, line)
        line.strokeWidth = cell * (0.14f + k * 0.14f)
        line.color = BackdropColors.withAlpha(colors.glow, min(1f, 0.35f + k))
        canvas.drawPath(segment, line)
    }

    // ------------------------------------------------------------------ Cœur du processeur

    private fun drawCore(canvas: Canvas, plan: CircuitLayout, colors: BackdropColors, motion: BackdropMotion, arrivals: Float) {
        val heat = min(1f, motion.energy * ENERGY_HEAT + arrivals)
        if (heat < MIN_GLOW) return
        val cell = plan.cell
        val warm = BackdropColors.secondTone(colors.accent, CORE_HUE_SHIFT)
        dieRect(plan.cpu)
        line.strokeWidth = cell * (0.3f + heat * 0.6f)
        line.color = BackdropColors.withAlpha(warm, heat * 0.3f)
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, line)
        fill.color = BackdropColors.withAlpha(warm, 0.2f + heat * 0.45f)
        canvas.drawRoundRect(rect, cell * 0.2f, cell * 0.2f, fill)
        // Cellules du cœur qui scintillent : tirage pseudo-aléatoire renouvelé trois fois par unité de phase.
        val left = rect.left
        val top = rect.top
        val cw = rect.width() / CORE_COLS
        val ch = rect.height() / CORE_ROWS
        val tick = floor(motion.phase * 3f)
        fill.color = BackdropColors.withAlpha(Color.WHITE, 0.55f * heat)
        for (a in 0 until CORE_COLS) {
            for (b in 0 until CORE_ROWS) {
                val r = sin((a * 7 + b * 13 + tick) * 12.9898f) * 43758.547f
                if (abs(r - r.toInt()) < heat * 0.6f) {
                    canvas.drawRect(left + a * cw + 1f, top + b * ch + 1f, left + (a + 1) * cw - 1f, top + (b + 1) * ch - 1f, fill)
                }
            }
        }
    }

    /** Pose dans [rect] le cœur du processeur : la moitié centrale de son boîtier. */
    private fun dieRect(cpu: CircuitLayout.Box) {
        rect.set(cpu.left + cpu.width * 0.25f, cpu.top + cpu.height * 0.25f, cpu.right - cpu.width * 0.25f, cpu.bottom - cpu.height * 0.25f)
    }

    private companion object {
        const val TRACE_WIDTH = 0.15f
        const val VIA_RADIUS = 0.27f
        const val CPU_LABEL = "TP-8"

        /** Trois paquets par touche, à 21 cellules par seconde : un bus entier est parcouru en moins d'une seconde et demie. */
        const val PACKETS = 3
        const val PACKET_SPEED_CELLS = 21f
        const val PACKET_LENGTH_CELLS = 1.6f
        const val TRAIL_GLOW = 0.18f
        const val ARRIVAL_DECAY = 4f
        const val HEAT_PER_ARRIVAL = 0.35f
        const val ENERGY_HEAT = 0.55f
        const val CORE_HUE_SHIFT = 32f
        const val CORE_COLS = 6
        const val CORE_ROWS = 4
        const val MIN_GLOW = 0.03f
    }
}
