package fr.junade.taipo

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tampon d'étincelles de taille fixe, partagé par les animations d'icônes du clavier ([BackspaceAnimation], [ShiftAnimation]).
 * Chaque étincelle part d'un point et parcourt un déplacement donné en [lifeS] secondes (voir [BackspaceSparkPose] pour la
 * trajectoire, l'opacité et la taille). Aucune allocation : quand le tampon est plein, la plus ancienne est remplacée.
 * Sans dépendance à Android pour la logique : testé en JVM.
 */
internal class SparkField(private val density: Float, private val random: Random = Random.Default) {

    private val originX = FloatArray(CAPACITY)
    private val originY = FloatArray(CAPACITY)
    private val travelX = FloatArray(CAPACITY)
    private val travelY = FloatArray(CAPACITY)
    private val age = FloatArray(CAPACITY)
    private val life = FloatArray(CAPACITY)
    private val colorIndex = IntArray(CAPACITY)
    private var cursor = 0

    var aliveCount = 0
        private set

    val capacity: Int get() = CAPACITY

    fun isAlive(i: Int): Boolean = life[i] > 0f

    fun x(i: Int): Float = originX[i] + travelX[i] * BackspaceSparkPose.ease(age[i] / life[i])

    fun y(i: Int): Float = originY[i] + travelY[i] * BackspaceSparkPose.ease(age[i] / life[i])

    /** Rayon en pixels. */
    fun radius(i: Int): Float = BackspaceSparkPose.RADIUS_DP * density * BackspaceSparkPose.scale(age[i] / life[i])

    fun alpha(i: Int): Float = BackspaceSparkPose.alpha(age[i] / life[i])

    fun color(i: Int): Int = colorIndex[i]

    /** Une étincelle en (x, y) (pixels) qui se déplace de (dx, dy) pixels en [lifeS] secondes. */
    fun spawn(x: Float, y: Float, dx: Float, dy: Float, lifeS: Float) {
        val i = cursor
        cursor = (cursor + 1) % CAPACITY
        if (life[i] <= 0f) aliveCount++
        originX[i] = x
        originY[i] = y
        travelX[i] = dx
        travelY[i] = dy
        age[i] = 0f
        life[i] = lifeS
        colorIndex[i] = random.nextInt(COLOR_COUNT)
    }

    /** Gerbe de [count] étincelles autour de (x, y), de portée [radiusDp] (dp), décalée de [leftBiasDp] dp vers la gauche. */
    fun burst(x: Float, y: Float, count: Int, radiusDp: Float, lifeS: Float, leftBiasDp: Float = 0f) {
        repeat(count) {
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val distance = between(radiusDp * BURST_MIN_RATIO, radiusDp) * density
            spawn(x, y, cos(angle) * distance - leftBiasDp * density, sin(angle) * distance, lifeS)
        }
    }

    fun step(dt: Float) {
        var alive = 0
        for (i in 0 until CAPACITY) {
            if (life[i] <= 0f) continue
            age[i] += dt
            if (age[i] >= life[i]) life[i] = 0f else alive++
        }
        aliveCount = alive
    }

    fun clear() {
        life.fill(0f)
        aliveCount = 0
    }

    fun between(min: Float, max: Float): Float = min + random.nextFloat() * (max - min)

    fun signed(amplitude: Float): Float = (random.nextFloat() * 2f - 1f) * amplitude

    companion object {
        const val CAPACITY = 40
        const val COLOR_COUNT = 3
        private const val BURST_MIN_RATIO = 0.6f
    }
}

/** Dessin d'un [SparkField] : des points pleins violet clair, violet et couleur du libellé des touches (4 dp au départ). Aucune allocation. */
internal class SparkPainter(private val colors: IntArray) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun draw(canvas: Canvas, field: SparkField) {
        if (field.aliveCount == 0) return
        for (i in 0 until field.capacity) {
            if (!field.isAlive(i)) continue
            paint.color = colors[field.color(i) % colors.size]
            paint.alpha = (ALPHA_FULL * field.alpha(i)).toInt()
            canvas.drawCircle(field.x(i), field.y(i), field.radius(i), paint)
        }
    }

    private companion object {
        const val ALPHA_FULL = 255f
    }
}
