package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyAction
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.KeyboardLayout
import fr.junade.taipo.Keyboards

/**
 * Construit la [KeyProximity] d'une langue à partir de la disposition réelle du clavier (AZERTY en
 * français, QWERTY en anglais) : la proximité suit donc toujours ce que l'utilisateur voit à l'écran
 * (largeurs de touches comprises, apostrophe française entre N et Effacer).
 *
 * Uniquement la page des lettres, sans la rangée de chiffres : la proximité ne sert qu'aux lettres.
 */
object KeyProximityFactory {

    fun forLanguage(
        language: KeyboardLanguage,
        missingLetterCost: Int = KeyProximity.DEFAULT_MISSING_LETTER_COST,
    ): KeyProximity = fromLayout(
        when (language) {
            KeyboardLanguage.FR -> Keyboards.letters
            KeyboardLanguage.EN -> Keyboards.lettersEn
        },
        missingLetterCost,
    )

    internal fun fromLayout(
        layout: KeyboardLayout,
        missingLetterCost: Int = KeyProximity.DEFAULT_MISSING_LETTER_COST,
    ): KeyProximity = KeyProximity.fromRows(
        layout.rows.map { row ->
            row.map { key ->
                val char = (key.action as? KeyAction.TypeChar)?.char?.takeIf { it.isLetter() || it == '\'' }
                KeyProximity.KeyBox(char, key.weight)
            }
        },
        missingLetterCost,
    )
}
