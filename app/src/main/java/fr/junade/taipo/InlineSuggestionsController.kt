package fr.junade.taipo

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions

/**
 * Auto-remplissage en ligne (gestionnaire de mots de passe : Bitwarden, etc.), extrait de [TaipoIme] (audit 2.1).
 *
 * Le service d'auto-remplissage actif fournit ses suggestions sous forme de vues, affichées dans la barre du clavier à la
 * place des suggestions de frappe. Le service doit avoir l'auto-remplissage « en ligne » activé dans ses propres réglages.
 *
 * Comportement inchangé : une réponse reçue avant la construction de la vue du clavier est gardée puis appliquée à
 * l'ouverture du champ ; les vues d'une réponse périmée sont ignorées.
 */
class InlineSuggestionsController(
    private val context: Context,
    private val mainHandler: Handler,
    private val host: Host,
) {

    /** Ce que le contrôleur demande au clavier : état de la vue et affichage des vues dans la barre. */
    interface Host {
        /** Vrai quand les vues du clavier existent. */
        fun isComposed(): Boolean

        /** Affiche [views] dans la barre à la place des suggestions de frappe (liste vide : retour aux suggestions). */
        fun setInlineSuggestions(views: List<View>)
    }

    /** Numéro de la dernière réponse d'auto-remplissage en ligne : les vues d'une réponse périmée sont ignorées. */
    private var generation = 0

    // Réponse reçue avant la construction de la vue (InlineSuggestionsResponse, API 30+ : typée Any pour rester chargeable sous l'API 26).
    private var pendingResponse: Any? = null

    /** Demande au service d'auto-remplissage actif de fournir ses suggestions sous forme de vues à afficher dans la barre. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createRequest(@Suppress("UNUSED_PARAMETER") uiExtras: Bundle): InlineSuggestionsRequest {
        val density = context.resources.displayMetrics.density
        val height = (INLINE_SUGGESTION_HEIGHT_DP * density).toInt()
        val styles = UiVersions.newStylesBuilder()
            .addStyle(buildInlineSuggestionStyle(context))
            .build()
        val spec = InlinePresentationSpec.Builder(
            Size((INLINE_SUGGESTION_MIN_WIDTH_DP * density).toInt(), height),
            Size((INLINE_SUGGESTION_MAX_WIDTH_DP * density).toInt(), height),
        ).setStyle(styles).build()
        AppLog.d(TAG, "autofill en ligne : demande envoyée au système (hauteur ${height}px)")
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(INLINE_SUGGESTION_MAX_COUNT)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun onResponse(response: InlineSuggestionsResponse): Boolean {
        AppLog.d(
            TAG,
            "autofill en ligne : réponse de ${response.inlineSuggestions.size} suggestion(s), vue du clavier prête=${host.isComposed()}",
        )
        if (!host.isComposed()) {
            // Première ouverture : la réponse peut arriver avant la construction de la vue du clavier. Elle est
            // gardée et appliquée à l'ouverture du champ (le système ne la renvoie pas de lui-même).
            pendingResponse = response
            return response.inlineSuggestions.isNotEmpty()
        }
        pendingResponse = null
        return show(response)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun show(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        val current = ++generation
        if (suggestions.isEmpty()) {
            clear()
            return false
        }
        val slots = arrayOfNulls<View>(suggestions.size)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        suggestions.forEachIndexed { index, suggestion ->
            try {
                suggestion.inflate(context, Size(wrap, wrap), context.mainExecutor) { view ->
                    // Réponse périodiquement remplacée par une plus récente : on ignore les vues en retard.
                    if (current != generation || !host.isComposed()) return@inflate
                    if (view == null) {
                        AppLog.w(TAG, "autofill en ligne : vue $index non rendue par le service d'auto-remplissage")
                        return@inflate
                    }
                    slots[index] = view
                    host.setInlineSuggestions(slots.filterNotNull())
                    if (BuildConfig.DEBUG) {
                        mainHandler.postDelayed(
                            { AppLog.d(TAG, "autofill en ligne : vue $index ${view.width}x${view.height} px, affichée=${view.isShown}") },
                            INLINE_LAYOUT_LOG_DELAY_MS,
                        )
                    }
                }
            } catch (e: IllegalArgumentException) {
                AppLog.e(TAG, "autofill en ligne : taille refusée pour la suggestion $index", e)
            }
        }
        return true
    }

    /** Applique la réponse arrivée avant la construction de la vue du clavier, s'il y en a une. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun applyPending() {
        val pending = pendingResponse as? InlineSuggestionsResponse ?: return
        pendingResponse = null
        AppLog.d(TAG, "autofill en ligne : application de la réponse reçue avant la vue du clavier")
        show(pending)
    }

    /** Retire les suggestions d'auto-remplissage : la barre retrouve ses suggestions habituelles. */
    fun clear() {
        generation++
        pendingResponse = null
        if (host.isComposed()) host.setInlineSuggestions(emptyList())
    }

    private companion object {
        /** Même étiquette que [TaipoIme] : le filtre logcat habituel continue de fonctionner. */
        const val TAG = "TaipoIme"

        // Hauteur alignée sur la zone de la barre (36 dp, moins la marge).
        const val INLINE_SUGGESTION_HEIGHT_DP = 32f
        const val INLINE_SUGGESTION_MIN_WIDTH_DP = 48f
        const val INLINE_SUGGESTION_MAX_WIDTH_DP = 320f
        const val INLINE_SUGGESTION_MAX_COUNT = 5
        const val INLINE_LAYOUT_LOG_DELAY_MS = 500L
    }
}
