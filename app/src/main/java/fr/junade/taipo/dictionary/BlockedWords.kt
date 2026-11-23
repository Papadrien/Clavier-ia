package fr.junade.taipo.dictionary

import android.content.Context
import java.util.Locale

/**
 * Mots bloqués (23/11/2026, comme « Supprimer la suggestion » de Gboard) : mots que l'utilisateur a retirés depuis la bande de
 * suggestions (appui long, « Retirer du dictionnaire »). Un mot bloqué n'est plus proposé (bande, mot suivant, saisie par
 * glissement) ni utilisé pour corriger un autre mot ; tapé tel quel, il reste écrit. Le dictionnaire de la langue (fichier
 * téléchargé, commun à tous) n'est jamais modifié : la liste est à part, dans les données de l'utilisateur.
 *
 * Gardée dans les préférences (`blocked_words`), en mémoire pour les tests à chaque frappe ; clavier et écrans partagent le
 * processus, une modification est vue tout de suite. Sauvegardée avec le dictionnaire personnel ([fr.junade.taipo.backup]).
 */
object BlockedWords {

    /** Au plus autant de mots bloqués (les plus anciens sont oubliés au-delà). */
    const val MAX_WORDS = 1000

    private const val PREFS_NAME = "blocked_words"
    private const val KEY_WORDS = "words"

    @Volatile private var cached: Set<String>? = null

    /** Clé de comparaison d'un mot : sans espaces en bordure, en minuscules (les accents comptent). Logique pure. */
    fun keyOf(word: String): String = word.trim().lowercase(Locale.ROOT)

    /** Mots bloqués (clés), lus une fois. */
    fun keys(context: Context): Set<String> =
        cached ?: synchronized(this) {
            cached ?: prefs(context).getStringSet(KEY_WORDS, emptySet()).orEmpty().toSet().also { cached = it }
        }

    fun isBlocked(context: Context, word: String): Boolean = keyOf(word) in keys(context)

    /** Mots bloqués, triés (écran du dictionnaire personnel). */
    fun all(context: Context): List<String> = keys(context).sorted()

    /** Bloque [word] ; faux s'il l'était déjà. */
    fun block(context: Context, word: String): Boolean = update(context) { current ->
        val key = keyOf(word)
        if (key.isEmpty() || key in current) null else (current + key).toList().takeLast(MAX_WORDS).toSet()
    }

    /** Débloque [words] ; faux si aucun n'était bloqué. */
    fun unblock(context: Context, words: Collection<String>): Boolean = update(context) { current ->
        val keys = words.map(::keyOf).toSet()
        if (current.none { it in keys }) null else current - keys
    }

    /** Ajoute [words] (restauration d'une sauvegarde) ; renvoie le nombre de mots nouvellement bloqués. */
    fun addAll(context: Context, words: Collection<String>): Int {
        var added = 0
        update(context) { current ->
            val incoming = words.map(::keyOf).filter { it.isNotEmpty() && it !in current }.toSet()
            added = incoming.size
            if (incoming.isEmpty()) null else (current + incoming).toList().takeLast(MAX_WORDS).toSet()
        }
        return added
    }

    /** Applique [change] (null : rien à changer) et l'enregistre ; vrai si la liste a changé. */
    @Synchronized
    private fun update(context: Context, change: (Set<String>) -> Set<String>?): Boolean {
        val next = change(keys(context)) ?: return false
        prefs(context).edit().putStringSet(KEY_WORDS, next).apply()
        cached = next
        return true
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Filtrage des mots bloqués dans la bande et l'autocorrection. Logique pure, testée en JVM (`BlockedWordFilterTest`). */
object BlockedWordFilter {

    private val WORD = Regex("""[\p{L}\p{N}'’-]+""")

    /** Emplacements de la bande sans les mots bloqués ([blocked] : clés de [BlockedWords.keyOf]) ; le mot tapé reste toujours. */
    fun slots(words: List<WordSuggestion?>, blocked: Set<String>): List<WordSuggestion?> {
        if (blocked.isEmpty()) return words
        return words.map { suggestion ->
            suggestion?.takeUnless { it.kind != WordSuggestion.Kind.TYPED && BlockedWords.keyOf(it.text) in blocked }
        }
    }

    /**
     * Vrai si une autocorrection qui remplace [removed] par [inserted] introduirait un mot bloqué (un mot de [inserted] absent de
     * [removed]) : la correction n'est alors pas faite.
     */
    fun blocksCorrection(removed: String, inserted: String, blocked: Set<String>): Boolean {
        if (blocked.isEmpty()) return false
        val before = WORD.findAll(removed).map { BlockedWords.keyOf(it.value) }.toSet()
        return WORD.findAll(inserted).map { BlockedWords.keyOf(it.value) }.any { it in blocked && it !in before }
    }
}
