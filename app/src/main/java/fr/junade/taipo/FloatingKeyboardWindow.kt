package fr.junade.taipo

import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import fr.junade.taipo.analytics.Analytics
import fr.junade.taipo.analytics.AnalyticsEvents
import kotlin.math.roundToInt

/**
 * Clavier flottant, côté fenêtre (sortie de [TaipoIme]) :
 * - [apply] relit le réglage et le pose sur la racine ([FloatingKeyboardFrame]), avec envol ou atterrissage si [apply] suit un
 *   geste de l'utilisateur ; la marge du bas des touches suit la forme image par image ;
 * - [applyWindowLayout] agrandit la fenêtre du clavier à tout l'écran tant qu'il flotte ou se recolle (sinon : hauteur du
 *   contenu, comme avant). À rappeler après `InputMethodService.updateFullscreenMode`, qui remet ses propres hauteurs. En
 *   flottant, l'animation de fenêtre d'Android (montée depuis le bas) est remplacée : la carte apparaît sur place
 *   ([onWindowShown]) et la fenêtre s'efface en fondu à la fermeture ;
 * - [computeInsets] : en plein écran, l'application n'est pas réduite et seule la carte reçoit les touchers, le reste de l'écran
 *   les laisse passer à l'application.
 */
internal class FloatingKeyboardWindow(
    private val service: InputMethodService,
    private val window: () -> Window?,
    private val composer: ImeViewComposer,
    private val preferences: () -> KeyboardPreferences,
    private val onDockRequested: () -> Unit,
) {
    private val cardBounds = Rect()

    /** Animations de fenêtre d'origine d'Android, rendues au retour du clavier ancré. */
    private var systemWindowAnimations: Int? = null

    /** Forme visée : flottant (la carte peut encore être en train d'y aller). */
    val isFloating: Boolean get() = composer.isComposed && composer.floatingFrame.floating

    private val needsFullWindow: Boolean get() = composer.isComposed && composer.floatingFrame.needsFullWindow

    /** [via] : geste de l'utilisateur qui a changé le mode (mesure d'usage, voir docs/analytics.md) ; null = simple relecture. */
    fun apply(animate: Boolean = false, via: String? = null) {
        if (!composer.isComposed) return
        val prefs = preferences()
        val frame = composer.floatingFrame
        val landscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val dockedMarginDp = prefs.bottomMarginDp
        frame.widthPercent = prefs.floatingWidthPercent
        if (!frame.floating || !animate) frame.position = prefs.floatingPosition(landscape)
        frame.onPositionChanged = { position -> preferences().setFloatingPosition(landscape, position) }
        frame.onDockRequested = onDockRequested
        // Marge sous les touches : celle du réglage une fois ancré, aucune en flottant (la poignée en tient lieu).
        frame.onMorphProgress = { progress ->
            composer.keyboardView.bottomMarginDp = (dockedMarginDp * (1f - progress)).roundToInt().coerceAtLeast(0)
        }
        frame.onDocked = { applyWindowLayout() }
        // Détacher : la fenêtre couvre l'écran avant que la carte ne quitte le bas.
        if (prefs.isFloatingKeyboard) applyWindowLayout(forceFull = true)
        val wasFloating = frame.floating
        frame.setFloating(prefs.isFloatingKeyboard, animate)
        if (via != null && wasFloating != frame.floating) {
            Analytics.track(service, AnalyticsEvents.KEYBOARD_MODE, "mode" to if (frame.floating) "floating" else "docked", "via" to via)
        }
        if (!animate) composer.keyboardView.bottomMarginDp = if (frame.floating) 0 else dockedMarginDp
        applyWindowLayout()
    }

    /** La fenêtre vient d'apparaître (ouverture d'un champ) : en flottant, la carte apparaît sur place. */
    fun onWindowShown() {
        if (isFloating) composer.floatingFrame.playAppear()
    }

    fun applyWindowLayout(forceFull: Boolean = false) {
        val window = window() ?: return
        val full = forceFull || needsFullWindow
        val height = if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (window.attributes.height != height) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
        val system = systemWindowAnimations ?: window.attributes.windowAnimations.also { systemWindowAnimations = it }
        val animations = if (full) R.style.FloatingKeyboardWindowAnimation else system
        if (window.attributes.windowAnimations != animations) window.setWindowAnimations(animations)
        window.findViewById<View>(android.R.id.inputArea)?.let { area ->
            val params = area.layoutParams ?: return@let
            if (params.height != height) {
                params.height = height
                (params as? FrameLayout.LayoutParams)?.gravity = Gravity.BOTTOM
                area.layoutParams = params
            }
        }
        if (composer.isComposed) {
            val frame = composer.floatingFrame
            frame.layoutParams?.let { params ->
                if (params.height != height) {
                    params.height = height
                    frame.layoutParams = params
                }
            }
        }
    }

    fun computeInsets(outInsets: InputMethodService.Insets) {
        if (!needsFullWindow || !service.isInputViewShown) return
        val decor = window()?.decorView ?: return
        outInsets.contentTopInsets = decor.height
        outInsets.visibleTopInsets = decor.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        composer.floatingFrame.cardBoundsInWindow(cardBounds)
        outInsets.touchableRegion.set(cardBounds)
    }
}
