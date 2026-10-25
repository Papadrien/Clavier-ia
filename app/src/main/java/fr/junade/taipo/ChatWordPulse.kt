package fr.junade.taipo

/**
 * Mode chat : détecte l'arrivée d'un mot dans la réponse en cours de génération, pour le retour haptique. Le moteur
 * envoie des morceaux (parfois un demi-mot, parfois un espace en tête de mot) : un mot est terminé quand une espace
 * suit un caractère qui n'en est pas une, y compris à la jointure entre deux morceaux.
 */
internal object ChatWordPulse {

    /** Vrai si l'ajout de [chunk] à [previous] termine au moins un mot (une seule vibration par morceau, même pour plusieurs mots). */
    fun completesWord(previous: String, chunk: String): Boolean {
        var lastIsWordChar = previous.isNotEmpty() && !previous.last().isWhitespace()
        for (c in chunk) {
            if (c.isWhitespace()) {
                if (lastIsWordChar) return true
                lastIsWordChar = false
            } else {
                lastIsWordChar = true
            }
        }
        return false
    }
}
