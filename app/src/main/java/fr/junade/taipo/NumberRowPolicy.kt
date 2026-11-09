package fr.junade.taipo

/**
 * Quand afficher la rangée de chiffres : toujours (réglage « Rangée de chiffres »), ou seulement dans les champs mot de passe
 * (réglage « Rangée de chiffres uniquement dans les champs mot de passe », sans effet quand la rangée est déjà toujours affichée).
 */
internal object NumberRowPolicy {
    fun shown(always: Boolean, passwordFieldsOnly: Boolean, fieldType: FieldType): Boolean =
        always || (passwordFieldsOnly && fieldType == FieldType.PASSWORD)
}
