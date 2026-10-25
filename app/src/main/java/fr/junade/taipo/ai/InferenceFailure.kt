package fr.junade.taipo.ai

import fr.junade.taipo.model.ModelFileException
import fr.junade.taipo.model.ModelLoadException
import java.io.IOException

/** Cause d'un échec d'inférence, telle que l'utilisateur la comprend (story 8.6). */
enum class InferenceFailureCause {
    /** Fichier modèle introuvable, incomplet ou illisible. */
    MODEL_FILE,

    /** Mémoire insuffisante pour charger ou exécuter le modèle. */
    OUT_OF_MEMORY,

    /** Le moteur n'arrive pas à charger le fichier : modèle incompatible ou corrompu. */
    MODEL_INCOMPATIBLE,

    /** Le texte envoyé dépasse ce que le modèle accepte. */
    TEXT_TOO_LONG,

    /** Toute autre erreur. */
    UNEXPECTED,
}

/**
 * Classe une exception en [InferenceFailureCause] (story 8.6), pour ne jamais montrer à l'utilisateur un nom de
 * classe ou le message brut d'une exception. Pur Kotlin, testable en JVM.
 *
 * Les exceptions de l'application ([ModelFileException], [ModelLoadException]) sont typées. Pour les erreurs qui
 * viennent du moteur LiteRT-LM (mémoire, texte trop long), seuls des mots-clés du message permettent de les
 * reconnaître : heuristique, à confirmer sur appareil avec les vrais messages du moteur.
 */
object InferenceFailureClassifier {

    private const val MAX_DEPTH = 8

    private val memoryKeywords = listOf(
        "out of memory", "outofmemory", "bad_alloc", "enomem", "failed to allocate", "cannot allocate",
        "memory allocation", "mmap",
    )
    private val tooLongKeywords = listOf(
        "too long", "context length", "context window", "max_num_tokens", "max num tokens", "sequence length",
        "token limit", "exceeds the maximum",
    )

    fun classify(failure: Throwable): InferenceFailureCause {
        val chain = generateSequence(failure) { it.cause }.take(MAX_DEPTH).toList()
        fun anyMessage(keywords: List<String>) = chain.any { error ->
            val message = error.message?.lowercase() ?: return@any false
            keywords.any { message.contains(it) }
        }
        return when {
            chain.any { it is OutOfMemoryError } || anyMessage(memoryKeywords) -> InferenceFailureCause.OUT_OF_MEMORY
            chain.any { it is ModelFileException || it is IOException } -> InferenceFailureCause.MODEL_FILE
            chain.any { it is ModelLoadException } -> InferenceFailureCause.MODEL_INCOMPATIBLE
            anyMessage(tooLongKeywords) -> InferenceFailureCause.TEXT_TOO_LONG
            else -> InferenceFailureCause.UNEXPECTED
        }
    }
}
