package fr.junade.taipo

import android.app.AlertDialog
import android.content.Context
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Pop-ups du clavier (messages d'information, liste des langues), extraits de [TaipoIme] (audit 2.1).
 *
 * Un pop-up est rattaché à la fenêtre du clavier, qui garde ainsi la saisie en cours. Un seul pop-up est affiché à la fois :
 * en ouvrir un nouveau ferme le précédent. Le comportement est celui d'avant l'extraction.
 */
class MessagePopup(private val context: Context, private val host: Host) {

    interface Host {
        /** Jeton de la fenêtre du clavier où accrocher le pop-up, ou null si le clavier n'est pas affiché. */
        fun windowToken(): IBinder?
    }

    /** Pop-up en cours, s'il y en a un. */
    private var dialog: AlertDialog? = null

    /**
     * Affiche [message] dans un pop-up avec un bouton OK (les toasts passaient inaperçus). Si le clavier n'est plus
     * affiché (pas de fenêtre pour accrocher le pop-up), repli sur un toast.
     */
    fun show(message: String) {
        val token = host.windowToken()
        if (token == null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        dismiss()
        val created = AlertDialog.Builder(ContextThemeWrapper(context, R.style.MessageDialogTheme))
            .setMessage(message)
            .setPositiveButton(R.string.message_dialog_ok, null)
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
        val created = AlertDialog.Builder(ContextThemeWrapper(context, R.style.MessageDialogTheme))
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

    /** Ferme le pop-up en cours, s'il y en a un. */
    fun dismiss() {
        dialog?.dismiss()
        dialog = null
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

    private companion object {
        /** Même étiquette que [TaipoIme] : le filtre logcat habituel continue de fonctionner. */
        const val TAG = "TaipoIme"
    }
}
