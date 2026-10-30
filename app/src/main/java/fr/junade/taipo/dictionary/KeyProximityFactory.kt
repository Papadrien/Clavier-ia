package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyAction
import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.KeyboardLayout
import fr.junade.taipo.Keyboards
import fr.junade.taipo.LetterArrangement

/**
 * Construit la [KeyProximity] d'une langue à partir de la disposition réelle du clavier (celle choisie pour la
 * langue dans les paramètres : AZERTY en français, QWERTZ en allemand, QWERTY sinon, tant qu'elle n'est pas changée) : la proximité suit donc toujours ce que l'utilisateur voit à l'écran
 * (largeurs de touches comprises, apostrophe française entre N et Effacer).
 *
 * Page des lettres avec la rangée de chiffres (toujours prise en compte, même si elle est masquée à l'écran), les
 * symboles de cette page (virgule, point, apostrophe) et la barre d'espace : une faute peut tomber sur n'importe
 * laquelle de ces touches voisines.
 */
object KeyProximityFactory {

    /** [arrangement] : disposition choisie pour la langue (par défaut son origine) ; la proximité suit la disposition affichée. */
    fun forLanguage(
        language: KeyboardLanguage,
        arrangement: LetterArrangement = LetterArrangement.defaultFor(language),
    ): KeyProximity = fromLayout(Keyboards.lettersOf(language, numberRow = true, arrangement = arrangement))

    internal fun fromLayout(layout: KeyboardLayout): KeyProximity = KeyProximity.fromRows(
        layout.rows.map { row ->
            val boxes = row.map { key ->
                val char = when (val action = key.action) {
                    is KeyAction.TypeChar -> action.char.takeIf { !it.isWhitespace() }
                    KeyAction.Space -> KeyProximity.SPACE
                    else -> null
                }
                KeyProximity.KeyBox(char, key.weight)
            }
            // Une rangée plus courte que la référence est centrée à l'écran (voir KeyboardLayout.rowStartUnits) :
            // des marges sans touche reproduisent ce décalage.
            val margin = layout.rowStartUnits(row)
            if (margin > 0f) listOf(KeyProximity.KeyBox(null, margin)) + boxes + listOf(KeyProximity.KeyBox(null, margin)) else boxes
        },
    )
}
