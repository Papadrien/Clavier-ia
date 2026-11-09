package fr.junade.taipo.dictionary

import kotlin.math.ceil

/**
 * Histogramme de durées (phase 6 de la refonte de l'autocorrecteur) : on mesure la latence du moteur à chaque frappe sans
 * rien garder d'autre que des nombres. Les durées sont rangées par puissances de deux, en microsecondes : le seau `b`
 * (b >= 1) contient les durées de [2^(b-1), 2^b) µs, le seau 0 celles de moins d'une microseconde. Un centile est donc
 * une **borne haute** à un facteur deux près (« p95 <= 512 µs »), jamais au-dessus du maximum réellement mesuré.
 *
 * Pas de verrou : l'appelant se synchronise ([AutocorrectStats] le fait). Logique pure, testée en JVM.
 */
class LatencyHistogram(private val slowMicros: Long = SLOW_MICROS) {

    private val buckets = LongArray(BUCKETS)

    /** Nombre de durées enregistrées. */
    var count: Long = 0L
        private set

    /** Plus longue durée enregistrée, en microsecondes. */
    var maxMicros: Long = 0L
        private set

    /** Nombre de durées d'au moins [slowMicros] (par défaut une image à 60 Hz : 16 ms). */
    var slowCount: Long = 0L
        private set

    /** Enregistre une durée en nanosecondes (une durée négative compte pour zéro). */
    fun record(nanos: Long) {
        val micros = if (nanos <= 0L) 0L else nanos / 1_000L
        buckets[bucketOf(micros)]++
        count++
        if (micros > maxMicros) maxMicros = micros
        if (micros >= slowMicros) slowCount++
    }

    /**
     * Borne haute, en microsecondes, du centile [p] (0 < p <= 1) : au moins la part [p] des durées est en dessous.
     * 0 s'il n'y a aucune mesure.
     */
    fun percentileMicros(p: Double): Long {
        if (count == 0L) return 0L
        val target = maxOf(1L, ceil(count * p).toLong())
        var cumulative = 0L
        for (bucket in buckets.indices) {
            cumulative += buckets[bucket]
            if (cumulative >= target) return minOf(upperBoundMicros(bucket), maxMicros)
        }
        return maxMicros
    }

    /** Efface toutes les mesures. */
    fun reset() {
        buckets.fill(0L)
        count = 0L
        maxMicros = 0L
        slowCount = 0L
    }

    companion object {
        /** Nombre de seaux : le dernier couvre tout ce qui dépasse 2^30 µs (plus de 17 minutes). */
        const val BUCKETS = 32

        /** Seuil des durées « lentes » : une image à 60 Hz. */
        const val SLOW_MICROS = 16_000L

        /** Seau d'une durée de [micros] microsecondes. */
        internal fun bucketOf(micros: Long): Int =
            if (micros < 1L) 0 else minOf(BUCKETS - 1, 64 - java.lang.Long.numberOfLeadingZeros(micros))

        /** Borne haute (exclue) du seau [bucket], en microsecondes. */
        internal fun upperBoundMicros(bucket: Int): Long = 1L shl bucket
    }
}
