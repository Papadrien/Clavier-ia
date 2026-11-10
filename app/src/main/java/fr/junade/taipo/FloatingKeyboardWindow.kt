package fr.junade.taipo

import android.content.res.Configuration
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout

/**
 * Clavier flottant, côté fenêtre (sortie de [TaipoIme]) :
 * - [apply] relit le réglage et le pose sur la racine ([FloatingKeyboardFrame]) ;
 * - [applyWindowLayout] agrandit la fenêtre du clavier à tout l'écran en flottant (sinon : hauteur du contenu, comme avant).
 *   À rappeler après `InputMethodService.updateFullscreenMode`, qui remet ses propres hauteurs ;
 * - [computeInsets] : en flottant, l'application n'est pas réduite et seule la carte reçoit les touchers, le reste de
 *   l'écran les laisse passer à l'application.
 */
internal class FloatingKeyboardWindow(
    private val service: InputMethodService,
    private val window: () -> Window?,
    private val composer: ImeViewComposer,
    private val preferences: () -> KeyboardPreferences,
) {
    private val cardBounds = Rect()

    val isFloating: Boolean get() = composer.isComposed && composer.floatingFrame.floating

    fun apply() {
        if (!composer.isComposed) return
        val prefs = preferences()
        val frame = composer.floatingFrame
        val landscape = service.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        frame.widthPercent = prefs.floatingWidthPercent
        frame.position = prefs.floatingPosition(landscape)
        frame.onPositionChanged = { position -> preferences().setFloatingPosition(landscape, position) }
        frame.floating = prefs.isFloatingKeyboard
        applyWindowLayout()
    }

    fun applyWindowLayout() {
        val window = window() ?: return
        val height = if (isFloating) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (window.attributes.height != height) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, height)
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
        if (!isFloating || !service.isInputViewShown) return
        val decor = window()?.decorView ?: return
        outInsets.contentTopInsets = decor.height
        outInsets.visibleTopInsets = decor.height
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        composer.floatingFrame.cardBoundsInWindow(cardBounds)
        outInsets.touchableRegion.set(cardBounds)
    }
}
