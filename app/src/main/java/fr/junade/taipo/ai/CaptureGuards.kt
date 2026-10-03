package fr.junade.taipo.ai

/** Verdict sur le résultat d'un `AudioRecord.read()` (voir [ReadMonitor]). */
internal enum class ReadVerdict {
    /** Des échantillons ont été lus : à traiter. */
    DATA,

    /** Lecture vide : attendre un court instant avant de réessayer (pas de boucle à vide). */
    WAIT,

    /** Le micro ne fournit plus rien : arrêter la capture proprement. */
    FAILED,
}

/**
 * Interprète les valeurs de retour de `AudioRecord.read()` (lot 2.5 de la revue de code).
 *
 * - une valeur négative est un code d'erreur (`ERROR`, `ERROR_BAD_VALUE`, `ERROR_INVALID_OPERATION`,
 *   `ERROR_DEAD_OBJECT`) : échec immédiat ;
 * - zéro n'est pas attendu en lecture bloquante : on tolère quelques lectures vides consécutives, puis
 *   on considère le micro perdu (au lieu de tourner à vide indéfiniment) ;
 * - toute lecture non vide remet le compteur à zéro.
 */
internal class ReadMonitor(private val maxEmptyReads: Int = MAX_EMPTY_READS) {

    private var emptyReads = 0

    fun onRead(read: Int): ReadVerdict = when {
        read < 0 -> ReadVerdict.FAILED
        read == 0 -> {
            emptyReads++
            if (emptyReads >= maxEmptyReads) ReadVerdict.FAILED else ReadVerdict.WAIT
        }
        else -> {
            emptyReads = 0
            ReadVerdict.DATA
        }
    }

    companion object {
        /** 100 lectures vides × 10 ms d'attente = environ 1 s sans aucune donnée avant d'abandonner. */
        const val MAX_EMPTY_READS = 100
        const val EMPTY_READ_DELAY_MS = 10L
    }
}

/**
 * Taille de la file entre la capture micro et le décodage.
 *
 * La file est volontairement très large (retour du 26/09/2026 : un décodage initial lent ne doit jamais
 * faire perdre le début de la phrase), mais **bornée** : si le décodeur prend plus de [MAX_SECONDS]
 * secondes de retard sur la voix, la capture s'arrête avec un message plutôt que de grossir sans limite
 * en mémoire ou de supprimer de l'audio en silence (ce qui laisserait des trous dans la transcription).
 */
internal object CaptureBacklog {

    const val MAX_SECONDS = 60

    /** Nombre de blocs de [chunkSamples] échantillons correspondant à [seconds] secondes d'audio (arrondi au-dessus). */
    fun capacityFor(chunkSamples: Int, sampleRate: Int, seconds: Int = MAX_SECONDS): Int {
        require(chunkSamples > 0) { "chunkSamples doit être positif" }
        require(sampleRate > 0) { "sampleRate doit être positif" }
        require(seconds > 0) { "seconds doit être positif" }
        val totalSamples = sampleRate.toLong() * seconds
        return ((totalSamples + chunkSamples - 1) / chunkSamples).toInt().coerceAtLeast(1)
    }
}
