package fr.junade.taipo.analytics

import fr.junade.taipo.analytics.FirstOpenPolicy.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FirstOpenPolicyTest {

    @Test
    fun `installation neuve jamais comptee : on envoie`() {
        assertEquals(Decision.SEND, FirstOpenPolicy.decide(alreadyHandled = false, firstInstallTime = 1_000, lastUpdateTime = 1_000))
    }

    @Test
    fun `quelques secondes entre installation et mise a jour restent une installation neuve`() {
        assertEquals(
            Decision.SEND,
            FirstOpenPolicy.decide(false, 1_000, 1_000 + FirstOpenPolicy.UPDATE_THRESHOLD_MILLIS),
        )
    }

    @Test
    fun `application mise a jour : rien n est envoye mais on memorise`() {
        assertEquals(
            Decision.SKIP_AND_REMEMBER,
            FirstOpenPolicy.decide(false, 1_000, 1_000 + FirstOpenPolicy.UPDATE_THRESHOLD_MILLIS + 1),
        )
    }

    @Test
    fun `deja traitee : rien a faire`() {
        assertEquals(Decision.NOTHING, FirstOpenPolicy.decide(true, 1_000, 1_000))
        assertEquals(Decision.NOTHING, FirstOpenPolicy.decide(true, 1_000, 999_999_999))
    }

    @Test
    fun `le nom de l evenement est un mot-cle valide`() {
        assertEquals(true, AnalyticsProps.isKeyword(AnalyticsEvents.FIRST_OPEN))
    }
}
