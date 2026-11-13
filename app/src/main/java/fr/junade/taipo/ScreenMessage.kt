package fr.junade.taipo

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat

/**
 * Audit A11Y-05 : message d'information dans la page, à la place d'un `Toast`. Posé en bas de l'écran, au-dessus des barres
 * système et du clavier (dans les marges de [applySystemBarInsets]), il reste [DURATION_MS], ou plus longtemps si le réglage
 * d'accessibilité « Temps de réaction » le demande, et se ferme au toucher. TalkBack l'annonce (titre de volet) et le relit
 * s'il est remplacé. Un nouveau message remplace le précédent.
 */
object ScreenMessage {

    /** Durée d'affichage sans réglage d'accessibilité. */
    const val DURATION_MS = 6_000L

    private const val TAG_VIEW = "taipo_screen_message"

    fun show(activity: Activity, message: CharSequence) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        (content.findViewWithTag<View>(TAG_VIEW) as? TextView)?.let { existing ->
            content.removeView(existing)
        }
        val view = TextView(activity, null, 0, R.style.TaipoText_Info).apply {
            tag = TAG_VIEW
            text = message
            background = activity.getDrawable(R.drawable.bg_screen_message)
            val padding = activity.dimen(R.dimen.taipo_settings_card_padding).toInt()
            setPadding(padding, padding, padding, padding)
            elevation = activity.dimen(R.dimen.taipo_screen_message_elevation)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            ViewCompat.setAccessibilityPaneTitle(this, message)
        }
        view.setOnClickListener { content.removeView(view) }
        val margin = activity.dimen(R.dimen.taipo_settings_gap_md).toInt()
        content.addView(
            view,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(margin, margin, margin, margin) },
        )
        view.postDelayed({ content.removeView(view) }, durationMs(activity))
    }

    /** Durée recommandée par le système pour un texte à lire (réglage « Temps de réaction », Android 10 et plus). */
    private fun durationMs(activity: Activity): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return DURATION_MS
        val manager = activity.getSystemService<AccessibilityManager>() ?: return DURATION_MS
        return manager.getRecommendedTimeoutMillis(DURATION_MS.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
    }
}

/** Message dans la page (voir [ScreenMessage]). */
fun Activity.showScreenMessage(message: CharSequence) = ScreenMessage.show(this, message)

fun Activity.showScreenMessage(@StringRes message: Int) = ScreenMessage.show(this, getText(message))

/**
 * Message affiché au moment où l'écran se ferme ([Activity.finish] juste après) : il n'y a plus de page pour le porter, et
 * l'écran suivant est souvent une autre application (écrans du presse-papiers ouverts depuis le clavier). Seul cas où un
 * `Toast` reste utilisé.
 */
fun Activity.finishWithMessage(message: CharSequence) {
    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    finish()
}
