package fr.junade.taipo.clipboard

/**
 * Smart Clipboard : la capture d'écran récente proposée au collage (puce de la barre).
 *
 * Seule la capture la plus récente est gardée, en mémoire vive. Elle est proposée tant qu'elle date de moins de
 * [expirationMillis] (10 minutes, comme une copie de texte) et qu'elle n'a pas déjà été collée. Elle est retrouvée par
 * [ScreenshotFinder] ; ici, logique pure (sans Android), testée en JVM. L'horloge est injectable.
 */
class ScreenshotSuggestionState(
    private val clock: () -> Long,
    private val expirationMillis: Long = EXPIRATION_MILLIS,
) {

    /** Une capture : son identifiant MediaStore, le moment de la prise de vue et son type MIME (image/png, image/jpeg…). */
    data class Shot(val id: Long, val takenAtMillis: Long, val mimeType: String)

    private var shot: Shot? = null
    private val pastedIds = ArrayDeque<Long>()

    /** Résultat de la dernière recherche (null = aucune capture récente, ou accès impossible). */
    fun onFound(found: Shot?) {
        shot = found
    }

    /** La capture à proposer maintenant, ou null (aucune, déjà collée ou expirée). */
    fun suggestion(): Shot? {
        val current = shot ?: return null
        if (current.id in pastedIds) return null
        if (remainingMillis(current) <= 0) return null
        return current
    }

    /** Durée avant l'expiration de la capture proposée, ou null s'il n'y en a pas (rien à planifier). */
    fun expiresInMillis(): Long? = suggestion()?.let(::remainingMillis)

    /** La capture proposée vient d'être collée : elle n'est plus proposée. */
    fun onPasted() {
        val current = shot ?: return
        if (current.id in pastedIds) return
        pastedIds.addLast(current.id)
        while (pastedIds.size > MAX_REMEMBERED) pastedIds.removeFirst()
    }

    fun clear() {
        shot = null
    }

    private fun remainingMillis(current: Shot): Long = current.takenAtMillis + expirationMillis - clock()

    companion object {
        /** La capture est proposée pendant 10 minutes après sa prise, comme une copie de texte. */
        const val EXPIRATION_MILLIS = 10 * 60 * 1000L

        /** Nombre de captures collées dont on se souvient (pour ne pas les reproposer). */
        private const val MAX_REMEMBERED = 20
    }
}

/** Quelle puce de la barre : la copie de texte ou la capture d'écran, selon la plus récente. */
object PasteChipChoice {
    enum class Kind { NONE, TEXT, SCREENSHOT }

    /**
     * [textCopiedAtMillis] / [screenshotTakenAtMillis] : moment de la copie ou de la capture, null si rien n'est proposé.
     * Une seule puce est affichée : la plus récente (à égalité, le texte).
     */
    fun pick(textCopiedAtMillis: Long?, screenshotTakenAtMillis: Long?): Kind = when {
        textCopiedAtMillis == null && screenshotTakenAtMillis == null -> Kind.NONE
        screenshotTakenAtMillis == null -> Kind.TEXT
        textCopiedAtMillis == null -> Kind.SCREENSHOT
        screenshotTakenAtMillis > textCopiedAtMillis -> Kind.SCREENSHOT
        else -> Kind.TEXT
    }
}

/** Le champ de saisie accepte-t-il une image de ce type ? (types déclarés par l'application dans `EditorInfo.contentMimeTypes`). */
object ImageAcceptance {
    fun accepts(declaredMimeTypes: List<String>, mimeType: String): Boolean {
        val wanted = mimeType.lowercase()
        return declaredMimeTypes.any { declared ->
            val d = declared.lowercase()
            d == wanted || d == "*/*" || (d.endsWith("/*") && wanted.startsWith(d.dropLast(1)))
        }
    }
}
