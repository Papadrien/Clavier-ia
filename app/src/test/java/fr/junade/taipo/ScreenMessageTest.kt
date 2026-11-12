package fr.junade.taipo

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Audit A11Y-05 : les écrans affichent leurs messages dans la page ([ScreenMessage]) au lieu d'un Toast : en bas de la page,
 * annoncé par TalkBack (titre de volet), remplacé par le suivant, fermé au toucher ou après [ScreenMessage.DURATION_MS].
 */
@RunWith(RobolectricTestRunner::class)
class ScreenMessageTest {

    private val activity = Robolectric.buildActivity(SystemPromptsActivity::class.java).setup().get()
    private val content: FrameLayout = activity.findViewById(android.R.id.content)

    private fun messages(): List<TextView> =
        (0 until content.childCount).map { content.getChildAt(it) }.filterIsInstance<TextView>()

    @Test
    fun `le message est affiche dans la page et annonce`() {
        activity.showScreenMessage("Enregistré")
        val shown = messages().single()
        assertEquals("Enregistré", shown.text.toString())
        assertEquals("Enregistré", shown.accessibilityPaneTitle?.toString())
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, shown.accessibilityLiveRegion)
    }

    @Test
    fun `un nouveau message remplace le precedent`() {
        activity.showScreenMessage("Premier")
        activity.showScreenMessage("Second")
        assertEquals(listOf("Second"), messages().map { it.text.toString() })
    }

    @Test
    fun `le message se ferme au toucher`() {
        activity.showScreenMessage("Enregistré")
        messages().single().performClick()
        assertNull(messages().firstOrNull())
    }

    @Test
    fun `le message se ferme apres sa duree`() {
        activity.showScreenMessage("Enregistré")
        ShadowLooper.idleMainLooper(ScreenMessage.DURATION_MS - 1, TimeUnit.MILLISECONDS)
        assertEquals(1, messages().size)
        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertNull(messages().firstOrNull())
    }
}
