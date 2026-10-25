package fr.junade.taipo.clipboard

/**
 * Story 2.10 : un texte copié ressemble-t-il à un numéro de carte bancaire ?
 *
 * Règle volontairement stricte (peu de faux positifs) : le texte **entier** (espaces autour
 * ignorés) est un numéro de 13 à 19 chiffres, éventuellement groupés par des espaces ou des
 * tirets simples entre deux chiffres, dont le premier est 2 à 6 (réseaux Visa, Mastercard, Amex,
 * Discover, CB, JCB, UnionPay) et dont la clé de Luhn est valide. Un nombre de 13 chiffres commençant
 * par 1 (horodatage en millisecondes, par exemple) n'est donc jamais détecté. Un numéro noyé dans
 * une phrase ne l'est pas non plus.
 *
 * Pas de détection de mot de passe par le contenu : seul le drapeau posé par l'application source
 * (`EXTRA_IS_SENSITIVE`, Android 13+) le signale. IBAN, téléphone et numéros de sécurité sociale ne
 * sont pas traités comme sensibles (ils s'épinglent).
 *
 * Logique pure (sans Android), testée en JVM. Coût borné : un texte de plus de [MAX_CHARS]
 * caractères est écarté sans être parcouru.
 */
object SensitiveContentDetector {

    private const val MIN_DIGITS = 13
    private const val MAX_DIGITS = 19

    /** 19 chiffres et 4 séparateurs, avec une marge : au-delà, ce n'est pas un numéro de carte. */
    private const val MAX_CHARS = 32

    /** Le texte [text] est-il un numéro de carte bancaire plausible ? */
    fun looksLikeCardNumber(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in MIN_DIGITS..MAX_CHARS) return false
        val digits = StringBuilder(MAX_DIGITS)
        var previousWasSeparator = false
        for (c in trimmed) {
            when {
                c in '0'..'9' -> {
                    digits.append(c)
                    previousWasSeparator = false
                }
                (c == ' ' || c == '-') && digits.isNotEmpty() && !previousWasSeparator -> previousWasSeparator = true
                else -> return false
            }
            if (digits.length > MAX_DIGITS) return false
        }
        if (previousWasSeparator) return false // séparateur final
        if (digits.length < MIN_DIGITS) return false
        if (digits[0] !in '2'..'6') return false
        return passesLuhn(digits)
    }

    /** Vrai si [text] est détecté comme sensible par son contenu (aujourd'hui : numéro de carte). */
    fun looksSensitive(text: String): Boolean = looksLikeCardNumber(text)

    private fun passesLuhn(digits: CharSequence): Boolean {
        var sum = 0
        var doubleIt = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (doubleIt) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            doubleIt = !doubleIt
        }
        return sum % 10 == 0
    }
}
