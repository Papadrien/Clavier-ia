package fr.junade.taipo.analytics

import fr.junade.taipo.analytics.UsageBuckets.ActiveDaysState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageBucketsTest {

    private fun days(n: Long) = n * UsageBuckets.DAY_MILLIS

    @Test
    fun `anciennete de l installation par tranche`() {
        assertEquals("0_1d", UsageBuckets.installAgeBucket(0))
        assertEquals("0_1d", UsageBuckets.installAgeBucket(days(2) - 1))
        assertEquals("2_7d", UsageBuckets.installAgeBucket(days(2)))
        assertEquals("2_7d", UsageBuckets.installAgeBucket(days(8) - 1))
        assertEquals("8_30d", UsageBuckets.installAgeBucket(days(8)))
        assertEquals("8_30d", UsageBuckets.installAgeBucket(days(31) - 1))
        assertEquals("gt_30d", UsageBuckets.installAgeBucket(days(31)))
    }

    @Test
    fun `une date d installation dans le futur compte comme aujourd hui`() {
        assertEquals("0_1d", UsageBuckets.installAgeBucket(-days(5)))
    }

    @Test
    fun `jours d utilisation par tranche`() {
        assertEquals("1d", UsageBuckets.activeDaysBucket(0))
        assertEquals("1d", UsageBuckets.activeDaysBucket(1))
        assertEquals("2_3d", UsageBuckets.activeDaysBucket(2))
        assertEquals("2_3d", UsageBuckets.activeDaysBucket(3))
        assertEquals("4_7d", UsageBuckets.activeDaysBucket(4))
        assertEquals("4_7d", UsageBuckets.activeDaysBucket(7))
        assertEquals("8_30d", UsageBuckets.activeDaysBucket(8))
        assertEquals("8_30d", UsageBuckets.activeDaysBucket(30))
        assertEquals("gt_30d", UsageBuckets.activeDaysBucket(31))
    }

    @Test
    fun `un jour n est compte qu une fois`() {
        val start = ActiveDaysState(0, -1)
        val first = UsageBuckets.recordActiveDay(start, 20_000)
        assertEquals(ActiveDaysState(1, 20_000), first)
        assertEquals(first, UsageBuckets.recordActiveDay(first, 20_000))
        assertEquals(ActiveDaysState(2, 20_001), UsageBuckets.recordActiveDay(first, 20_001))
    }

    @Test
    fun `le compteur est plafonne`() {
        val full = ActiveDaysState(UsageBuckets.ACTIVE_DAYS_CAP, 20_000)
        assertEquals(UsageBuckets.ACTIVE_DAYS_CAP, UsageBuckets.recordActiveDay(full, 20_001).count)
    }

    @Test
    fun `jour epoque UTC`() {
        assertEquals(0L, UsageBuckets.epochDay(UsageBuckets.DAY_MILLIS - 1))
        assertEquals(1L, UsageBuckets.epochDay(UsageBuckets.DAY_MILLIS))
    }

    @Test
    fun `cles et tranches sont des mots-cles acceptes par le filtre`() {
        assertTrue(AnalyticsProps.isKeyword(UsageBuckets.PROP_INSTALL_AGE))
        assertTrue(AnalyticsProps.isKeyword(UsageBuckets.PROP_ACTIVE_DAYS))
        val all = listOf(0L, days(3), days(10), days(40)).map(UsageBuckets::installAgeBucket) +
            listOf(1, 2, 5, 20, 99).map(UsageBuckets::activeDaysBucket)
        all.forEach { assertTrue(AnalyticsProps.isKeyword(it), it) }
    }

    @Test
    fun `plus ajoute les proprietes sans depasser le filtre`() {
        val props = AnalyticsProps.of("type" to "correct")
            .plus(UsageBuckets.PROP_INSTALL_AGE to "2_7d", UsageBuckets.PROP_ACTIVE_DAYS to "4_7d")
        assertEquals(mapOf<String, Any>("type" to "correct", "install_age" to "2_7d", "active_days" to "4_7d"), props.values)
    }
}
