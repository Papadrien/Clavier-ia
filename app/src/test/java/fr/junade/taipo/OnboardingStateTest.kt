package fr.junade.taipo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Lot UX 2 : état des étapes de l'accueil. */
class OnboardingStateTest {

    private val pkg = "fr.junade.taipo"

    @Test
    fun `clavier non active seule l'etape 1 est a faire`() {
        val state = OnboardingState.from(listOf("com.google.android.inputmethod.latin"), "com.google.android.inputmethod.latin/.LatinIME", pkg)
        assertEquals(StepStatus.CURRENT, state.activate)
        assertEquals(StepStatus.LOCKED, state.choose)
        assertEquals(StepStatus.LOCKED, state.tryIt)
    }

    @Test
    fun `clavier active mais pas choisi, l'etape 2 est a faire`() {
        val state = OnboardingState.from(listOf("com.google.android.inputmethod.latin", pkg), "com.google.android.inputmethod.latin/.LatinIME", pkg)
        assertEquals(StepStatus.DONE, state.activate)
        assertEquals(StepStatus.CURRENT, state.choose)
        assertEquals(StepStatus.LOCKED, state.tryIt)
    }

    @Test
    fun `clavier active et choisi, l'essai est accessible`() {
        val state = OnboardingState.from(listOf(pkg), "$pkg/.TaipoIme", pkg)
        assertEquals(StepStatus.DONE, state.activate)
        assertEquals(StepStatus.DONE, state.choose)
        assertEquals(StepStatus.CURRENT, state.tryIt)
    }

    @Test
    fun `methode par defaut inconnue compte comme non choisie`() {
        val state = OnboardingState.from(listOf(pkg), null, pkg)
        assertEquals(StepStatus.CURRENT, state.choose)
    }

    @Test
    fun `un autre paquet au nom proche n'est pas Taipo`() {
        val state = OnboardingState.from(listOf("$pkg.debug"), "$pkg.debug/.TaipoIme", pkg)
        assertEquals(StepStatus.CURRENT, state.activate)
    }

    @Test
    fun `choisi sans etre active est ignore`() {
        val state = OnboardingState.from(emptyList(), "$pkg/.TaipoIme", pkg)
        assertEquals(StepStatus.CURRENT, state.activate)
        assertEquals(StepStatus.LOCKED, state.choose)
    }
}
