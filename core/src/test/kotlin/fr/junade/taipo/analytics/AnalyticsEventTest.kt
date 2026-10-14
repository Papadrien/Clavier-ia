package fr.junade.taipo.analytics

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnalyticsEventTest {

    @Test
    fun `les mots-cles courts passent, le texte libre est ecarte`() {
        val props = AnalyticsProps.of(
            "type" to "correct",
            "model" to "ultra_leger",
            "phrase" to "Bonjour tout le monde",
            "mot" to "Salut",
            "vide" to "",
            "long" to "a".repeat(AnalyticsProps.MAX_KEYWORD_LENGTH + 1),
            "ok" to true,
            "n" to 3,
            "d" to 12L,
            "x" to 1.5,
            "nul" to null,
        )
        assertEquals(setOf("type", "model", "ok", "n", "d"), props.values.keys)
        assertEquals("correct", props.values["type"])
    }

    @Test
    fun `les cles invalides et l exces de proprietes sont ecartes`() {
        val many = (1..20).map { "k$it" to it }.toTypedArray()
        assertEquals(AnalyticsProps.MAX_PROPS, AnalyticsProps.of(*many).values.size)
        assertTrue(AnalyticsProps.of("Cle Avec Espace" to 1, "" to 2).values.isEmpty())
    }

    @Test
    fun `un mot de passe ou un nom de fichier ne passent pas pour un mot-cle`() {
        assertFalse(AnalyticsProps.isKeyword("Mot2Passe!"))
        assertFalse(AnalyticsProps.isKeyword("mon fichier.txt"))
        assertFalse(AnalyticsProps.isKeyword("/storage/emulated/0/a"))
        assertTrue(AnalyticsProps.isKeyword("gemma3-1b-it-int4"))
    }

    @Test
    fun `tranches de duree`() {
        assertEquals("lt_1s", AnalyticsEvents.durationBucket(999))
        assertEquals("1_3s", AnalyticsEvents.durationBucket(1_000))
        assertEquals("3_10s", AnalyticsEvents.durationBucket(9_999))
        assertEquals("10_30s", AnalyticsEvents.durationBucket(10_000))
        assertEquals("gt_30s", AnalyticsEvents.durationBucket(30_000))
        assertEquals("lt_5s", AnalyticsEvents.listenBucket(4))
        assertEquals("5_15s", AnalyticsEvents.listenBucket(5))
        assertEquals("15_60s", AnalyticsEvents.listenBucket(59))
        assertEquals("gt_60s", AnalyticsEvents.listenBucket(60))
    }

    @Test
    fun `disposition selon la langue`() {
        assertEquals("azerty", AnalyticsEvents.layoutOf(KeyboardLanguage.FR))
        assertEquals("qwertz", AnalyticsEvents.layoutOf(KeyboardLanguage.DE))
        assertEquals("qwerty", AnalyticsEvents.layoutOf(KeyboardLanguage.EN))
        KeyboardLanguage.entries.forEach { assertTrue(AnalyticsProps.isKeyword(AnalyticsEvents.layoutOf(it))) }
    }

    @Test
    fun `pour le SDK, les booleens deviennent des mots-cles et les Long des Int`() {
        val sdk = AnalyticsProps.of("success" to true, "auto" to false, "n" to 3, "d" to 12L, "big" to Long.MAX_VALUE, "type" to "correct").forSdk()
        assertEquals(mapOf<String, Any>("success" to "true", "auto" to "false", "n" to 3, "d" to 12, "type" to "correct"), sdk)
        assertTrue(AnalyticsProps.EMPTY.forSdk().isEmpty())
        // Seuls String et Int arrivent au SDK.
        sdk.values.forEach { assertTrue(it is String || it is Int) }
    }
}
