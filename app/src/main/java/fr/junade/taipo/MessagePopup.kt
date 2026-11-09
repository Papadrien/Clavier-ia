package fr.junade.taipo

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Pop-ups du clavier (messages d'information, liste des langues), extraits de [TaipoIme] (audit 2.1).
 *
 * Un pop-up est rattaché à la fenêtre du clavier, qui garde ainsi la saisie en cours. Un seul pop-up est affiché à la fois :
 * en ouvrir un nouveau ferme le précédent.
 *
 * Revue UX : un simple message d'information n'ouvre plus de fenêtre à fermer. Il s'affiche en **bandeau** posé sur la barre du
 * haut, qui disparaît seul (2,5 s, plus pour un long message, 6 s au plus), au toucher ou à la frappe suivante ([dismissBanner]).
 * Les vrais choix gardent une fenêtre : message avec un bouton d'action, liste des langues.
 *
 * Les astuces de gestes ([showTip]) ne sont pas un bandeau : elles s'affichent **dans** la barre du haut, à sa hauteur
 * ([CorrectionBarView.showGestureTip]).
 */
class MessagePopup(private val context: Context, private val host: Host) {

    interface Host {
        /** Jeton de la fenêtre du clavier où accrocher le pop-up, ou null si le clavier n'est pas affiché. */
        fun windowToken(): IBinder?

        /** Vue sur laquelle poser le bandeau (barre du haut), ou null si le clavier n'est pas affiché. */
        fun bannerAnchor(): View?

        /** Affiche l'astuce [message] dans la barre du haut pendant [durationMs] ; faux si la barre n'est pas affichée. */
        fun showBarTip(message: String, durationMs: Long): Boolean

        /** Retire l'astuce de la barre du haut, s'il y en a une. */
        fun hideBarTip()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var banner: PopupWindow? = null
    private val hideBanner = Runnable { dismissBanner() }

    /** Pop-up en cours, s'il y en a un. */
    private var dialog: AlertDialog? = null

    /**
     * Affiche [message] dans un pop-up avec un bouton OK (les toasts passaient inaperçus) et, si [action] est donné, un second
     * bouton qui ferme le pop-up puis appelle [onAction]. Si le clavier n'est plus affiché (pas de fenêtre pour accrocher le
     * pop-up), repli sur un toast (sans le bouton d'action).
     */
    fun show(message: String, action: String? = null, onAction: () -> Unit = {}) {
        if (action == null && showBanner(message)) return
        val token = host.windowToken()
        if (token == null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        dismiss()
        val created = AlertDialog.Builder(ContextThemeWrapper(KeyboardTheme.themed(context), R.style.MessageDialogTheme))
            .setMessage(message)
            .setPositiveButton(R.string.message_dialog_ok, null)
            .apply { if (action != null) setNegativeButton(action) { _, _ -> onAction() } }
            .create()
        attach(created, token)
        dialog = created
        try {
            created.show()
            created.window?.decorView?.applyTaipoFontToTree()
        } catch (t: Throwable) {
            dialog = null
            AppLog.e(TAG, "Impossible d'afficher le pop-up", t)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Liste de choix [items] (au-dessus du clavier), [checked] étant l'entrée cochée : un appui sur une entrée ferme la liste
     * puis appelle [onChosen] avec son rang. Sans fenêtre où l'accrocher, ou si elle ne peut pas s'afficher, appelle [onUnavailable].
     */
    fun showChoice(
        @StringRes title: Int,
        items: Array<String>,
        checked: Int,
        onChosen: (Int) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        val token = host.windowToken()
        if (token == null) {
            onUnavailable()
            return
        }
        dismiss()
        val created = AlertDialog.Builder(ContextThemeWrapper(KeyboardTheme.themed(context), R.style.MessageDialogTheme))
            .setTitle(title)
            .setSingleChoiceItems(items, checked) { picker, which ->
                picker.dismiss()
                onChosen(which)
            }
            .create()
        attach(created, token)
        dialog = created
        try {
            created.show()
            created.window?.decorView?.applyTaipoFontToTree()
        } catch (e: WindowManager.BadTokenException) {
            dialog = null
            onUnavailable()
        }
    }

    /** Ferme le pop-up, le bandeau et l'astuce en cours, s'il y en a. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
        dismissBanner()
        host.hideBarTip()
    }

    /**
     * Astuce de geste ([GestureTips]) dans la barre du haut, à sa hauteur : elle reste [TIP_DURATION_MS], même si la frappe
     * continue (elle se poursuit souvent juste après), et se ferme au toucher.
     */
    fun showTip(message: String): Boolean {
        dismiss()
        return host.showBarTip(message, TIP_DURATION_MS)
    }

    /** La frappe continue : un message d'information s'efface (une astuce, dans la barre, reste). */
    fun dismissBannerOnKey() = dismissBanner()

    /** Ferme le bandeau d'information (frappe suivante, toucher, délai écoulé). */
    fun dismissBanner() {
        handler.removeCallbacks(hideBanner)
        banner?.let { if (it.isShowing) runCatching { it.dismiss() } }
        banner = null
    }

    /** Bandeau sur la barre du haut, à la place d'une fenêtre ; faux s'il ne peut pas s'afficher (repli sur la fenêtre). */
    private fun showBanner(message: String): Boolean {
        val anchor = host.bannerAnchor() ?: return false
        if (!anchor.isAttachedToWindow || anchor.width == 0) return false
        dismiss()
        val themed = KeyboardTheme.themed(context)
        val density = context.resources.displayMetrics.density
        val text = TextView(themed).apply {
            this.text = message
            setTextColor(themed.themeColor(R.color.text_primary))
            textSize = BANNER_TEXT_SP
            gravity = Gravity.CENTER_VERTICAL
            val horizontal = (BANNER_PADDING_H_DP * density).toInt()
            val vertical = (BANNER_PADDING_V_DP * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            background = GradientDrawable().apply {
                setColor(themed.themeColor(R.color.key_popup))
                cornerRadius = BANNER_RADIUS_DP * density
            }
            elevation = BANNER_ELEVATION_DP * density
            applyTaipoFontToTree()
            setOnClickListener { dismissBanner() }
        }
        val margin = (BANNER_MARGIN_DP * density).toInt()
        val popup = PopupWindow(text, anchor.width - 2 * margin, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true
            isOutsideTouchable = false
            overlapAnchor = true // posé sur la barre, pas en dessous
            elevation = BANNER_ELEVATION_DP * density
        }
        return try {
            popup.showAsDropDown(anchor, margin, margin)
            banner = popup
            handler.postDelayed(hideBanner, bannerDuration(message))
            true
        } catch (t: Throwable) {
            AppLog.w(TAG, "Bandeau impossible à afficher, repli sur la fenêtre", t)
            false
        }
    }

    private fun attach(popup: AlertDialog, token: IBinder) {
        popup.window?.let { window ->
            val params = window.attributes
            params.token = token
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            window.attributes = params
            window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
    }

    companion object {
        /** Même étiquette que [TaipoIme] : le filtre logcat habituel continue de fonctionner. */
        private const val TAG = "TaipoIme"
        private const val BANNER_TEXT_SP = 14f
        private const val BANNER_PADDING_H_DP = 14f
        private const val BANNER_PADDING_V_DP = 10f
        private const val BANNER_RADIUS_DP = 14f
        private const val BANNER_ELEVATION_DP = 6f
        private const val BANNER_MARGIN_DP = 4f
        private const val TIP_DURATION_MS = 7_000L

        /** Durée d'affichage du bandeau : 2,5 s, plus 40 ms par caractère au-delà de 40, 6 s au plus. Logique pure, testée en JVM. */
        fun bannerDuration(message: String): Long = (2_500L + (message.length - 40).coerceAtLeast(0) * 40L).coerceAtMost(6_000L)
    }
}
