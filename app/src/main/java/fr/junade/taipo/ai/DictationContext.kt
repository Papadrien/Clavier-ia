package fr.junade.taipo.ai

import java.text.Normalizer

/**
 * Contexte donné à la reformulation de la Transcription IA (23/11/2026, d'après Typeless) : ce que le modèle ne peut pas deviner
 * à partir du seul texte dicté. Logique pure, testée en JVM (`DictationContextTest`).
 *
 * - [textBefore] : la fin du texte déjà écrit avant le curseur (et ce qui a déjà été reformulé dans cette dictée), pour la
 *   continuité : majuscule ou non, suite d'une phrase, même registre. Le modèle ne doit ni le répéter ni le réécrire.
 * - [kind] : le type de champ (message, e-mail, autre), pour le ton et la mise en forme attendus.
 * - [names] : mots personnels de l'utilisateur proches de ce qui a été dicté (prénoms, noms, jargon) : leur orthographe exacte.
 */
data class DictationContext(
    val textBefore: String = "",
    val kind: FieldKind = FieldKind.OTHER,
    val names: List<String> = emptyList(),
) {

    /** Type de champ, pour le ton et la mise en forme. */
    enum class FieldKind { MESSAGE, EMAIL, OTHER }

    /** Le même contexte, avec [previous] (déjà reformulé dans cette dictée) ajouté à la fin du texte précédent. */
    fun continuedBy(previous: String): DictationContext =
        if (previous.isBlank()) this else copy(textBefore = tail("$textBefore $previous".trim()))

    companion object {
        /** Longueur gardée du texte avant le curseur : assez pour la continuité, court pour un petit modèle. */
        const val MAX_TEXT_BEFORE = 300

        /** Mots personnels donnés au modèle, au plus : une longue liste égare un petit modèle. */
        const val MAX_NAMES = 20

        /** Messageries d'e-mail connues (le champ n'annonce pas toujours qu'il attend un e-mail). */
        private val EMAIL_APPS = setOf(
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "ch.protonmail.android",
            "me.proton.android.mail",
            "com.samsung.android.email.provider",
            "com.yahoo.mobile.client.android.mail",
            "com.fsck.k9",
            "eu.faircode.email",
        )

        /** Variation de texte « sujet d'e-mail » d'Android (`InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT`). */
        private const val VARIATION_EMAIL_SUBJECT = 0x00000030
        private const val VARIATION_MASK = 0x00000ff0

        fun fieldKind(packageName: String?, inputType: Int, messagingField: Boolean): FieldKind = when {
            messagingField -> FieldKind.MESSAGE
            packageName in EMAIL_APPS || inputType and VARIATION_MASK == VARIATION_EMAIL_SUBJECT -> FieldKind.EMAIL
            else -> FieldKind.OTHER
        }

        /** Fin de [text] sur [MAX_TEXT_BEFORE] caractères au plus, coupée à un début de mot. */
        fun tail(text: String, max: Int = MAX_TEXT_BEFORE): String {
            val trimmed = text.trim()
            if (trimmed.length <= max) return trimmed
            val cut = trimmed.substring(trimmed.length - max)
            val space = cut.indexOf(' ')
            return if (space in 0 until cut.length - 1) cut.substring(space + 1) else cut
        }

        /**
         * Mots personnels ([personal]) qui ressemblent à un mot (ou à deux mots collés) de [raw], le texte dicté : la
         * reconnaissance vocale écrit souvent un nom propre de travers (« Ritsanko » entendu « rit sanko »). Comparaison sans
         * majuscules ni accents, distance d'édition d'au plus un quart du mot (1 au moins). Mots de moins de 3 lettres ignorés.
         */
        fun relevantNames(personal: Collection<String>, raw: String, max: Int = MAX_NAMES): List<String> {
            val tokens = WORD.findAll(raw).map { fold(it.value) }.toList()
            if (tokens.isEmpty()) return emptyList()
            val candidates = (tokens + tokens.zipWithNext { a, b -> a + b }).toSet()
            return personal.asSequence()
                .filter { it.length >= MIN_NAME_LENGTH }
                .distinct()
                .filter { word ->
                    val folded = fold(word).replace(" ", "")
                    val allowed = (folded.length / NAME_DISTANCE_DIVISOR).coerceAtLeast(1)
                    candidates.any { kotlin.math.abs(it.length - folded.length) <= allowed && distance(it, folded) <= allowed }
                }
                .take(max)
                .toList()
        }

        private const val MIN_NAME_LENGTH = 3
        private const val NAME_DISTANCE_DIVISOR = 4
        private val WORD = Regex("""[\p{L}\p{N}]+""")
        private val MARKS = Regex("""\p{M}+""")

        private fun fold(text: String): String = MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")

        /** Distance d'édition (insertion, suppression, substitution). */
        private fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}

/**
 * Transcription IA en direct (23/11/2026) : la dictée écrit le texte pendant l'enregistrement, et chaque bloc terminé part déjà
 * en reformulation pendant que l'utilisateur continue de parler ; à l'arrêt, il ne reste que la fin. Logique pure, testée en JVM
 * (`LiveRewriteTest`).
 *
 * Le texte d'un décodeur en continu (transducteur, recherche gloutonne) ne fait que s'allonger : un mot suivi d'un autre ne change
 * plus. Un bloc est donc pris dès qu'un autre mot le suit.
 */
object LiveRewrite {

    /**
     * Mots par bloc reformulé pendant l'écoute, au plus : assez pour garder le sens d'un passage, assez court pour que la
     * reformulation suive le débit de la parole. La coupe se fait en fin de phrase quand il y en a une ([DictationRewrite.chunks]).
     */
    const val LIVE_WORDS = 60

    /** Bloc à reformuler : son texte et son nombre de mots. */
    data class Block(val text: String, val words: Int)

    private val WHITESPACE = Regex("""\s+""")

    private fun words(raw: String): List<String> = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Prochain bloc complet de [raw] après ses [consumed] premiers mots, ou null si le passage en cours n'est pas terminé. */
    fun nextBlock(raw: String, consumed: Int, maxWords: Int = LIVE_WORDS): Block? {
        val remaining = words(raw).drop(consumed)
        if (remaining.size <= 1) return null
        val parts = DictationRewrite.chunks(remaining.joinToString(" "), maxWords)
        if (parts.size < 2) return null
        val first = parts.first()
        return Block(first, words(first).size)
    }

    /** Ce qui reste de [raw] après ses [consumed] premiers mots (la fin, à reformuler à l'arrêt). */
    fun rest(raw: String, consumed: Int): String = words(raw).drop(consumed).joinToString(" ")

    /** Recolle les blocs reformulés : une espace, ou un retour à la ligne autour d'une liste. */
    fun join(parts: List<String>): String {
        val out = StringBuilder()
        for (part in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            if (out.isNotEmpty()) out.append(if (isListEnd(out) || part.startsWith("- ") || part.contains('\n')) "\n" else " ")
            out.append(part)
        }
        return out.toString()
    }

    /** La dernière ligne de [text] est un élément de liste. */
    private fun isListEnd(text: CharSequence): Boolean = text.substring(text.lastIndexOf('\n') + 1).startsWith("- ")
}
