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

    @Test
    fun `autorisations verrouillees tant que Taipo n'est pas choisi`() {
        val state = OnboardingState.from(listOf(pkg), null, pkg)
        assertEquals(StepStatus.LOCKED, state.permissions)
    }

    @Test
    fun `autorisations a faire puis faites`() {
        assertEquals(StepStatus.CURRENT, OnboardingState.from(listOf(pkg), "$pkg/.TaipoIme", pkg).permissions)
        val done = OnboardingState.from(listOf(pkg), "$pkg/.TaipoIme", pkg, tried = true, aiReady = true, permissionsDone = true)
        assertEquals(StepStatus.DONE, done.permissions)
        assertEquals(true, done.complete)
    }

    @Test
    fun `l'accueil n'est pas complet sans l'etape des autorisations`() {
        val state = OnboardingState.from(listOf(pkg), "$pkg/.TaipoIme", pkg, tried = true, aiReady = true)
        assertEquals(false, state.complete)
    }
}
