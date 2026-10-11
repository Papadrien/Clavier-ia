package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import fr.junade.taipo.suggestion.EmailText
import java.util.Locale

/**
 * Habitudes de correction de l'utilisateur : ce qu'il fait des corrections automatiques, par langue.
 * Trois compteurs, tous insensibles à la casse :
 * - **annulations** : combien de fois la correction « tapé -> corrigé » a été annulée ;
 * - **acceptations** : combien de fois cette même paire est restée en place ;
 * - **usage** : combien de fois un mot a été tapé et validé.
 *
 * S'y ajoutent les **adresses e-mail** tapées (en minuscules, avec le nombre de fois où elles l'ont été), pour les
 * proposer dans la bande de suggestions dès que l'utilisateur en commence une ([suggestEmails]). Elles sont à part
 * des trois compteurs : elles ne servent pas à départager les corrections, sont bornées à [MAX_EMAILS] (la moins
 * utilisée, la plus ancienne d'abord, laisse la place) et suivent le même stockage chiffré et le même effacement.
 *
 * Aucun texte n'est conservé : seulement des comptes de mots isolés, de paires de mots et d'adresses, comme pour
 * [fr.junade.taipo.suggestion.NextWordModel]. Un mot qui contient un chiffre ou qui est trop long (numéro,
 * code, identifiant) n'est jamais enregistré. Les compteurs sont plafonnés ([MAX_COUNT]) et le nombre total
 * d'entrées est borné ([maxEntries]) : au-delà, les entrées rares sont élaguées.
 *
 * Alimentée par [HabitSignals]. La correction consulte les annulations ([isRefused], phase 3) et, pour départager des
 * candidats à distance égale, les acceptations, l'usage et le contexte (phase 4, voir [HabitContext]).
 * Logique pure (aucune dépendance Android), testée en JVM. Les méthodes publiques sont
 * synchronisées : lecture et écriture depuis le thread principal, sauvegarde depuis un autre.
 */
class CorrectionHabits(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    // (langue, clé) -> compte. Clé d'une paire : « tapé<TAB>corrigé » ; clé d'un mot : le mot.
    private val undone = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private val accepted = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private val usage = HashMap<KeyboardLanguage, HashMap<String, Int>>()
    private var entryCount = 0

    // Adresse e-mail (minuscules) -> nombre de fois tapée. L'ordre est celui de la dernière frappe : la plus récente est en dernier.
    // Hors de [entryCount] : [isEmpty] et [size] ne parlent que des mots et des paires, seuls à jouer sur la correction.
    private val emails = LinkedHashMap<String, Int>()

    @Synchronized
    fun isEmpty(): Boolean = entryCount == 0

    @Synchronized
    fun size(): Int = entryCount

    @Synchronized
    fun clear() {
        undone.clear()
        accepted.clear()
        usage.clear()
        emails.clear()
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

    /** Retient que l'adresse [email] vient d'être tapée. Faux si ce n'est pas une adresse valide (voir [EmailText.normalize]). */
    @Synchronized
    fun recordEmail(email: String): Boolean {
        val key = EmailText.normalize(email) ?: return false
        // Retirée puis remise : l'adresse passe en dernier, c'est la plus récente.
        emails[key] = minOf(MAX_COUNT, (emails.remove(key) ?: 0) + 1)
        while (emails.size > MAX_EMAILS) evictEmail(keep = key)
        return true
    }

    /** Combien de fois l'adresse [email] a été tapée (casse sans effet). */
    @Synchronized
    fun emailUsage(email: String): Int = EmailText.normalize(email)?.let { emails[it] } ?: 0

    /** Nombre d'adresses différentes retenues. */
    @Synchronized
    fun emailsSize(): Int = emails.size

    /**
     * Au plus [limit] adresses retenues qui commencent par [prefix] (casse sans effet) et ne s'y réduisent pas : les
     * plus tapées d'abord, à égalité la plus récente d'abord. Liste vide si [prefix] est vide.
     */
    @Synchronized
    fun suggestEmails(prefix: String, limit: Int): List<String> {
        val wanted = prefix.trim().lowercase(Locale.ROOT)
        if (wanted.isEmpty() || limit <= 0) return emptyList()
        // (adresse, compte, rang de récence) ; le rang croît avec la récence.
        val matches = ArrayList<Triple<String, Int, Int>>()
        var rank = 0
        for ((email, count) in emails) {
            if (email.length > wanted.length && email.startsWith(wanted)) matches.add(Triple(email, count, rank))
            rank++
        }
        return matches
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenByDescending { it.third })
            .take(limit)
            .map { it.first }
    }

    /** Ajoute les comptes de [other] à ceux de cet objet (chargement du disque après quelques saisies). */
    fun mergeFrom(other: CorrectionHabits) {
        if (other === this) return
        val snapshot: List<Entry>
        val emailSnapshot: List<Pair<String, Int>>
        synchronized(other) {
            snapshot = other.entries()
            emailSnapshot = other.emails.map { it.key to it.value }
        }
        synchronized(this) {
            for (entry in snapshot) add(mapFor(entry.kind), entry.language, entry.key, entry.count)
            if (entryCount > maxEntries) prune()
            mergeEmails(emailSnapshot)
        }
    }

    /** Texte de sauvegarde : une ligne `U|A|W <tab> langue <tab> clé <tab> compte` (la clé d'une paire contient une tabulation). */
    @Synchronized
    fun serialize(): String = buildString {
        for (entry in entries()) {
            append(entry.kind).append('\t').append(entry.language.name).append('\t')
                .append(entry.key).append('\t').append(entry.count).append('\n')
        }
        // Adresses : une ligne `E <tab> adresse <tab> compte`, de la moins récente à la plus récente (l'ordre est conservé).
        for ((email, count) in emails) append(KIND_EMAIL).append('\t').append(email).append('\t').append(count).append('\n')
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

    /**
     * Ajoute à celles-ci les adresses [older], lues sur le disque donc plus anciennes que ce qui vient d'être tapé :
     * elles passent avant, et les comptes des adresses présentes des deux côtés s'additionnent.
     */
    private fun mergeEmails(older: List<Pair<String, Int>>) {
        if (older.isEmpty()) return
        val olderCounts = older.toMap()
        val merged = LinkedHashMap<String, Int>()
        for ((email, count) in older) if (email !in emails) merged[email] = count
        for ((email, count) in emails) merged[email] = minOf(MAX_COUNT, count + (olderCounts[email] ?: 0))
        emails.clear()
        emails.putAll(merged)
        while (emails.size > MAX_EMAILS) evictEmail(keep = null)
    }

    /** Retire l'adresse la moins tapée (la plus ancienne à égalité), sauf [keep] (celle qui vient d'être tapée). */
    private fun evictEmail(keep: String?) {
        var victim: String? = null
        var lowest = Int.MAX_VALUE
        for ((email, count) in emails) {
            if (email == keep) continue
            if (count < lowest) {
                lowest = count
                victim = email
            }
        }
        if (victim == null) return
        emails.remove(victim)
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

        /** Nombre d'acceptations de la même correction à partir duquel la paire compte pour départager (phase 4, à ajuster à l'usage). */
        const val ACCEPTANCE_THRESHOLD = 2

        /** Nombre maximal d'adresses e-mail retenues. */
        const val MAX_EMAILS = 200

        private const val MAX_WORD_LENGTH = 30
        private const val KIND_EMAIL = 'E'
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
                if (kind == KIND_EMAIL) {
                    // Adresse : 3 champs (`E`, adresse, compte), déjà en minuscules et valide ; sinon la ligne est ignorée.
                    val email = parts.getOrNull(1)?.takeIf { EmailText.normalize(it) == it }
                    val emailCount = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
                    if (parts.size == 3 && email != null && emailCount != null) {
                        habits.emails[email] = minOf(MAX_COUNT, (habits.emails.remove(email) ?: 0) + emailCount)
                    }
                    continue
                }
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
            while (habits.emails.size > MAX_EMAILS) habits.evictEmail(keep = null)
            return habits
        }
    }
}
