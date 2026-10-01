package fr.junade.taipo

import android.text.InputType

/**
 * Story 1.18 : type du champ de saisie courant, d'après `EditorInfo.inputType`, et comportement du
 * clavier qui en découle (comme sur Gboard : le clavier s'adapte au champ).
 *
 * - [numericPad] : le clavier de lettres est remplacé par un pavé numérique ([Keyboards.layoutOf]).
 * - [autoCapitalizes] : majuscule automatique de début de phrase (story 1.2).
 * - [autoCorrects] : autocorrection du dictionnaire à la fin d'un mot (story 1.3). S'y ajoute, dans
 *   `ClavierIme`, l'absence de suggestions demandée par l'application (`NO_SUGGESTIONS`).
 * - [doubleSpacePeriod] : un double espace devient « . ».
 *
 * Logique pure : elle ne lit que des constantes, testée en JVM.
 */
enum class FieldType(
    val numericPad: Boolean = false,
    val autoCapitalizes: Boolean = true,
    val autoCorrects: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
) {
    /** Texte libre (et tout champ non reconnu) : comportement de base. */
    TEXT,

    /** Adresse e-mail : « @ » à la place de la virgule, ni autocorrection ni majuscule automatique. */
    EMAIL(autoCapitalizes = false, autoCorrects = false, doubleSpacePeriod = false),

    /** Adresse web : « / » à la place de la virgule, ni autocorrection ni majuscule automatique. */
    URL(autoCapitalizes = false, autoCorrects = false, doubleSpacePeriod = false),

    /** Mot de passe (texte masqué ou visible) : clavier de lettres, mais saisie littérale. */
    PASSWORD(autoCapitalizes = false, autoCorrects = false, doubleSpacePeriod = false),

    /** Champ numérique : pavé numérique (chiffres, séparateur décimal, signe moins). */
    NUMBER(numericPad = true, autoCapitalizes = false, autoCorrects = false, doubleSpacePeriod = false),

    /** Champ téléphone : pavé numérique avec « + », « * » et « # ». */
    PHONE(numericPad = true, autoCapitalizes = false, autoCorrects = false, doubleSpacePeriod = false),
    ;

    companion object {

        /**
         * Type du champ pour [inputType]. Un champ numérique de type mot de passe (code PIN) reste un
         * champ numérique : il a besoin du pavé. Les champs date/heure et les types inconnus
         * gardent le clavier de texte.
         */
        fun of(inputType: Int): FieldType = when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> NUMBER
            InputType.TYPE_CLASS_PHONE -> PHONE
            InputType.TYPE_CLASS_TEXT -> when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> EMAIL

                InputType.TYPE_TEXT_VARIATION_URI -> URL

                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                -> PASSWORD

                else -> TEXT
            }

            else -> TEXT
        }
    }
}
