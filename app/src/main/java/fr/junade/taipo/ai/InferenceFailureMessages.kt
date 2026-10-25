package fr.junade.taipo.ai

import androidx.annotation.StringRes
import fr.junade.taipo.R

/** Phrase affichée à l'utilisateur pour chaque cause d'échec d'inférence (story 8.6). */
@StringRes
fun InferenceFailureCause.messageRes(): Int = when (this) {
    InferenceFailureCause.MODEL_FILE -> R.string.inference_error_model_file
    InferenceFailureCause.OUT_OF_MEMORY -> R.string.inference_error_memory
    InferenceFailureCause.MODEL_INCOMPATIBLE -> R.string.inference_error_incompatible
    InferenceFailureCause.TEXT_TOO_LONG -> R.string.inference_error_text_too_long
    InferenceFailureCause.UNEXPECTED -> R.string.inference_error_unexpected
}
