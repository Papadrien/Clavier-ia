package fr.papadrien.clavier

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

/** États du bouton Corriger. */
enum class CorrectionBarState { HIDDEN, IDLE, LOADING, CORRECTING }

/** États du bouton Vocal. */
enum class VoiceBarState { IDLE, RECORDING, TRANSCRIBING }

/**
 * Barre au-dessus du clavier contenant les boutons Corriger et Vocal.
 *
 * Décision 3.3 : le bouton Corriger n'est visible que si du texte est
 * présent dans le champ. Décision 7.1/7.2 : le bouton Vocal, lui, est
 * toujours visible. Décision 8.7 : un état "chargement" distinct est affiché
 * si le modèle de correction doit être rechargé.
 *
 * Simplification assumée pour ce prototype : l'animation "labyrinthe" prévue
 * dans les décisions de design n'est pas implémentée ici (l'asset de motif
 * vectoriel de référence n'est pas disponible dans cet environnement) ; les
 * états "en cours" sont indiqués simplement par un texte de bouton différent
 * et un bouton désactivé (ou une couleur différente pour l'écoute vocale).
 */
@SuppressLint("ViewConstructor")
class CorrectionBarView(context: Context) : LinearLayout(context) {

    fun interface OnCorrectListener {
        fun onCorrectClicked()
    }

    private val correctButton = Button(context)
    val voiceButton = Button(context)
    private var listener: OnCorrectListener? = null

    var state: CorrectionBarState = CorrectionBarState.HIDDEN
        set(value) {
            field = value
            renderCorrect()
        }

    var voiceState: VoiceBarState = VoiceBarState.IDLE
        set(value) {
            field = value
            renderVoice()
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#17181B"))
        val paddingH = dp(12f).toInt()
        val paddingV = dp(6f).toInt()
        setPadding(paddingH, paddingV, paddingH, paddingV)

        styleButton(voiceButton, "#3A3F47")
        voiceButton.setOnClickListener { /* geste réel géré via setOnTouchListener côté appelant */ }
        addView(
            voiceButton,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()).apply { marginEnd = dp(8f).toInt() },
        )

        styleButton(correctButton, "#5A7FD4")
        correctButton.setOnClickListener {
            if (state == CorrectionBarState.IDLE) {
                listener?.onCorrectClicked()
            }
        }
        addView(correctButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f).toInt()))

        renderCorrect()
        renderVoice()
    }

    fun setOnCorrectListener(listener: OnCorrectListener) {
        this.listener = listener
    }

    private fun styleButton(button: Button, backgroundColor: String) {
        button.setTextColor(Color.WHITE)
        button.setTypeface(button.typeface, Typeface.BOLD)
        button.isAllCaps = false
        button.background = roundedBackground(backgroundColor)
        button.setPadding(dp(16f).toInt(), 0, dp(16f).toInt(), 0)
    }

    private fun renderCorrect() {
        when (state) {
            CorrectionBarState.HIDDEN -> {
                correctButton.visibility = View.GONE
            }

            CorrectionBarState.IDLE -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = true
                correctButton.alpha = 1f
                correctButton.text = context.getString(R.string.correction_button_idle)
            }

            CorrectionBarState.LOADING -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_loading)
            }

            CorrectionBarState.CORRECTING -> {
                correctButton.visibility = View.VISIBLE
                correctButton.isEnabled = false
                correctButton.alpha = 0.6f
                correctButton.text = context.getString(R.string.correction_button_correcting)
            }
        }
    }

    private fun renderVoice() {
        when (voiceState) {
            VoiceBarState.IDLE -> {
                voiceButton.text = context.getString(R.string.voice_button_idle)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 1f
            }

            VoiceBarState.RECORDING -> {
                voiceButton.text = context.getString(R.string.voice_button_recording)
                voiceButton.background = roundedBackground("#C0392B")
                voiceButton.alpha = 1f
            }

            VoiceBarState.TRANSCRIBING -> {
                voiceButton.text = context.getString(R.string.voice_button_transcribing)
                voiceButton.background = roundedBackground("#3A3F47")
                voiceButton.alpha = 0.6f
            }
        }
    }

    private fun roundedBackground(colorHex: String): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18f)
        setColor(Color.parseColor(colorHex))
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )
}
