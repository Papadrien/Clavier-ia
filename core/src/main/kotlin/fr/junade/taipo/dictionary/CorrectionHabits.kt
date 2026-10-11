package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import java.util.Locale

/**
 * Habitudes de correction de l'utilisateur : ce qu'il fait des corrections automatiques, par langue.
 * Trois compteurs, tous insensibles à la casse :
 * - **annulations** : combien de fois la correction « tapé -> corrigé » a été annulée ;
 * - **acceptations** : combien de fois cette même paire est restée en place ;
 * - **usage** : combien de fois un mot a été tapé et validé.
 *
 * Aucun texte n'est conservé : seulement des comptes de mots isolés et de paires de mots, comme pour
 * [fr.junade.taipo.suggestion.NextWordModel]. Un mot qui contient un chiffre ou qui est trop long (numéro,
 * code, identifiant) n'est jamais enregistré. Les compteurs sont plafonnés ([MAX_COUNT]) et le nombre total
 * d'entrées est borné ([maxEntries]) : au-delà, les entrées rares sont élaguées.
 *
 * Alimentée par [HabitSignals]. La correction consulte les annulations ([isRefused], phase 3) ; départage à venir (phase 4). Logique pure (aucune dépendance Android), testée en JVM. Les méthodes publiques sont
 * synchronisées : lecture et écriture depuis le thread principal, sauvegarde depuis un autre.
 */
class CorrectionHabits(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    // (langue, clé) -> compte. Clé d'une paire : « tapé<TAB>corrigé » ; clé d'un mot : le mot.
    private val undone = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private val accepted = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private val usage = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private var entryCount = 0

    @Synchronized
    fun isEmpty(): Boolean = entryCount == 0

    @Synchronized
    fun size(): Int = entryCount

    @Synchronized
    fun clear() {
        undone.clear()
        accepted.clear()
        usage.clear()
        entryCount = 0
    }

    /** Retient que la correction [original] -> [corrected] a été annulée. Faux si la paire n'est pas enregistrable. */
    @Synchronized
    fun recordUndo(language: KeyboardLanguage, original: String, corrected: String): Boolean =
        pairKey(original, corrected)?.let { increment(undone, language, it) } ?: false

    /** Retient que la correction [original] -> [corrected] est restée en place. Faux si la paire n'est pas enregistrable. */
    @Synchronized
    fun recordAccepted(language: KeyboardLanguage, original: String, corrected: String): Boolean =
        pairKey(original, corrected)?.let { increment(accepted, language, it) } ?: false

    /** Retient que [word] a été tapé puis validé. Faux si le mot n'est pas enregistrable. */
    @Synchronized
    fun recordUsage(language: KeyboardLanguage, word: String): Boolean =
        normalize(word)?.let { increment(usage, language, it) } ?: false

    @Synchronized
    fun undoCount(language: KeyboardLanguage, original: String, corrected: String): Int =
        pairKey(original, corrected)?.let { count(undone, language, it) } ?: 0

    /** Vrai si l'utilisateur a annulé au moins [REFUSAL_THRESHOLD] fois cette correction : elle n'est plus appliquée. */
    @Synchronized
    fun isRefused(language: KeyboardLanguage, original: String, corrected: String): Boolean =
        undoCount(language, original, corrected) >= REFUSAL_THRESHOLD

    @Synchronized
    fun acceptedCount(language: KeyboardLanguage, original: String, corrected: String): Int =
        pairKey(original, corrected)?.let { count(accepted, language, it) } ?: 0

    @Synchronized
    fun usageCount(language: KeyboardLanguage, word: String): Int =
        normalize(word)?.let { count(usage, language, it) } ?: 0

    /** Ajoute les comptes de [other] à ceux de cet objet (chargement du disque après quelques saisies). */
    fun mergeFrom(other: CorrectionHabits) {
        if (other === this) return
        val snapshot = synchronized(other) { other.entries() }
        synchronized(this) {
            for (entry in snapshot) add(mapFor(entry.kind), entry.language, entry.key, entry.count)
            if (entryCount > maxEntries) prune()
        }
    }

    /** Texte de sauvegarde : une ligne `U|A|W <tab> langue <tab> clé <tab> compte` (la clé d'une paire contient une tabulation). */
    @Synchronized
    fun serialize(): String = buildString {
        for (entry in entries()) {
            append(entry.kind).append('\t').append(entry.language.name).append('\t')
                .append(entry.key).append('\t').append(entry.count).append('\n')
        }
    }

    // ------------------------------------------------------------------

    private class Entry(val kind: Char, val language: KeyboardLanguage, val key: String, val count: Int)

    private fun entries(): List<Entry> {
        val result = ArrayList<Entry>(entryCount)
        for ((kind, map) in listOf(KIND_UNDO to undone, KIND_ACCEPTED to accepted, KIND_USAGE to usage)) {
            for ((language, counts) in map) for ((key, count) in counts) result.add(Entry(kind, language, key, count))
        }
        return result
    }

    private fun mapFor(kind: Char): HashMap<KeyboardLanguage, HashMap<String, Int>> = when (kind) {
        KIND_UNDO -> undone
        KIND_ACCEPTED -> accepted
        else -> usage
    }

    private fun count(map: HashMap<KeyboardLanguage, HashMap<String, Int>>, language: KeyboardLanguage, key: String): Int =
        map[language]?.get(key) ?: 0

    private fun increment(map: HashMap<KeyboardLanguage, HashMap<String, Int>>, language: KeyboardLanguage, key: String): Boolean {
        add(map, language, key, 1)
        if (entryCount > maxEntries) prune()
        return true
    }

    private fun add(map: HashMap<KeyboardLanguage, HashMap<String, Int>>, language: KeyboardLanguage, key: String, amount: Int) {
        val counts = map.getOrPut(language) { HashMap() }
        val existing = counts[key]
        if (existing == null) entryCount++
        counts[key] = minOf(MAX_COUNT, (existing ?: 0) + amount)
    }

    /** Supprime les entrées vues une seule fois, puis, si ce n'est pas assez, divise tous les comptes par deux. */
    private fun prune() {
        dropBelow(2)
        if (entryCount > maxEntries) {
            for (map in listOf(undone, accepted, usage)) for (counts in map.values) counts.replaceAll { _, count -> count / 2 }
            dropBelow(1)
        }
    }

    private fun dropBelow(minCount: Int) {
        for (map in listOf(undone, accepted, usage)) {
            val languages = map.entries.iterator()
            while (languages.hasNext()) {
                val counts = languages.next().value
                val inner = counts.entries.iterator()
                while (inner.hasNext()) {
                    if (inner.next().value < minCount) {
                        inner.remove()
                        entryCount--
                    }
                }
                if (counts.isEmpty()) languages.remove()
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000

        /** Plafond d'un compteur : un compteur plus haut n'apporterait rien et pourrait déborder. */
        const val MAX_COUNT = 1_000

        /** Nombre d'annulations de la même correction à partir duquel elle n'est plus appliquée (à ajuster à l'usage). */
        const val REFUSAL_THRESHOLD = 2

        private const val MAX_WORD_LENGTH = 30
        private const val KIND_UNDO = 'U'
        private const val KIND_ACCEPTED = 'A'
        private const val KIND_USAGE = 'W'

        /**
         * Mot enregistrable : en minuscules, sans espace ni tabulation, ni chiffre, d'au plus [MAX_WORD_LENGTH]
         * caractères (même règle que `NextWordModel` : un numéro ou un code n'est jamais retenu). Null sinon.
         */
        private fun normalize(word: String): String? {
            val trimmed = word.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_WORD_LENGTH) return null
            if (trimmed.any { it.isWhitespace() || it.isDigit() }) return null
            return trimmed.lowercase(Locale.ROOT)
        }

        /** Clé d'une paire « tapé -> corrigé » ; null si l'un des deux n'est pas enregistrable ou s'ils ne diffèrent que par la casse. */
        private fun pairKey(original: String, corrected: String): String? {
            val was = normalize(original) ?: return null
            val now = normalize(corrected) ?: return null
            if (was == now) return null
            return "$was\t$now"
        }

        /** Relit un texte produit par [serialize] ; les lignes illisibles ou hors limites sont ignorées. */
        fun parse(text: String, maxEntries: Int = DEFAULT_MAX_ENTRIES): CorrectionHabits {
            val habits = CorrectionHabits(maxEntries)
            for (line in text.lineSequence()) {
                val parts = line.split('\t')
                // Mot : 4 champs ; paire : 5 (la clé « tapé<TAB>corrigé » contient une tabulation).
                val kind = parts.firstOrNull()?.singleOrNull() ?: continue
                val keyParts = when (kind) {
                    KIND_USAGE -> if (parts.size == 4) parts.subList(2, 3) else continue
                    KIND_UNDO, KIND_ACCEPTED -> if (parts.size == 5) parts.subList(2, 4) else continue
                    else -> continue
                }
                val language = KeyboardLanguage.entries.firstOrNull { it.name == parts[1] } ?: continue
                val count = parts.last().toIntOrNull()?.takeIf { it > 0 } ?: continue
                val key = keyParts.joinToString("\t")
                if (keyParts.any { normalize(it) != it } || keyParts.size == 2 && keyParts[0] == keyParts[1]) continue
                habits.add(habits.mapFor(kind), language, key, count)
            }
            if (habits.entryCount > maxEntries) habits.prune()
            return habits
        }
    }
}
