package fr.junade.taipo.dictionary

/**
 * Compteurs de l'autocorrection pour ajuster les seuils à l'usage (phase 6 de la refonte). Ils répondent à trois questions :
 *
 * 1. **Qui corrige, et combien ?** Par source (dictionnaire direct, recherche par distance, contexte français, tournure
 *    habituelle) et, pour la recherche, par [FaultKind].
 * 2. **Où passe la frontière de confiance ?** Pour chaque type de faute, la marge du meilleur candidat est rangée dans un
 *    seau (< 0,25, < 0,5, < 1, < 2, < 4, < 8, >= 8), séparément pour les corrections appliquées et pour celles que le seuil a
 *    refusées. Beaucoup de refus juste sous le seuil : le seuil est peut-être trop haut ; beaucoup d'annulations dans les
 *    seaux appliqués les plus bas : il est trop bas.
 * 3. **Combien ça coûte ?** Un histogramme de latence ([LatencyHistogram]) pour l'autocorrection, séparée quand la recherche
 *    à trois erreurs a tourné (le chemin le plus cher), et pour la bande de suggestions.
 *
 * **Jamais de texte** : seulement des nombres et des noms de types (aucun mot tapé, corrigé ni contexte, pas même en
 * mémoire). Le clavier ne crée cet objet que dans un build debug ; en release il n'existe pas.
 *
 * Les méthodes sont synchronisées : la bande de suggestions calcule hors du thread principal, l'autocorrection sur celui-ci.
 * Logique pure, testée en JVM par `AutocorrectStatsTest`.
 */
class AutocorrectStats {

    /** Origine d'une correction appliquée. */
    enum class Source(val label: String) {
        /** Règle directe du dictionnaire : accent, apostrophe, ligature, élision, nom propre. */
        DICTIONARY_DIRECT("dico-direct"),

        /** Recherche par distance d'édition, après décision de confiance. */
        DICTIONARY_SEARCH("dico-recherche"),

        /** Correction de contexte français (a / à, homophones, participes, traits d'union). */
        CONTEXT("contexte"),

        /** Tournure habituelle de l'utilisateur (voir [PhraseHabits]). */
        PHRASE_HABIT("tournure"),
    }

    /** Ce dont on mesure la durée. */
    enum class Section(val label: String) {
        AUTOCORRECTION("autocorrection"),
        AUTOCORRECTION_THREE_EDITS("autocorrection avec recherche à 3 erreurs"),
        SUGGESTIONS("bande de suggestions"),
    }

    /** Dernière correction appliquée : source, et type de faute si elle vient de la recherche. */
    private class Applied(val source: Source, val kind: FaultKind?)

    private val kinds = FaultKind.values()
    private val sources = Source.values()
    private val sections = Section.values()

    private var words = 0L
    private var refusals = 0L
    private var threeEditsSearches = 0L
    private var undoneUnattributed = 0L
    private val applied = LongArray(sources.size)
    private val undoneBySource = LongArray(sources.size)
    private val appliedByKind = LongArray(kinds.size)
    private val rejectedByKind = LongArray(kinds.size)
    private val undoneByKind = LongArray(kinds.size)
    private val acceptedMargins = Array(kinds.size) { LongArray(MARGIN_BUCKETS) }
    private val rejectedMargins = Array(kinds.size) { LongArray(MARGIN_BUCKETS) }
    private val latencies = Array(sections.size) { LatencyHistogram() }

    // Correction du dernier mot examiné (pas encore confirmée appliquée), puis dernière correction appliquée : pour
    // attribuer une annulation. Seulement des types, jamais de texte.
    private var pending: Applied? = null
    private var lastApplied: Applied? = null

    /**
     * Un mot terminé a été examiné par [Autocorrection.correctedText] ; [trace] est celle de la correction du dictionnaire.
     * [dictionaryApplied], [contextApplied] et [phraseApplied] : l'étape a changé le texte. [refusedAtEnd] : le dernier
     * garde-fou (correction annulée deux fois) a tout refusé. [nanos] : durée de l'ensemble.
     */
    @Synchronized
    fun recordWord(
        trace: DecisionTrace,
        dictionaryApplied: Boolean,
        contextApplied: Boolean,
        phraseApplied: Boolean,
        refusedAtEnd: Boolean,
        nanos: Long,
    ) {
        words++
        if (trace.refusedByHabits || refusedAtEnd) refusals++
        val kind = trace.kind
        if (trace.path == DecisionTrace.Path.SEARCH && kind != null) {
            val bucket = marginBucket(trace.margin)
            if (trace.accepted) {
                acceptedMargins[kind.ordinal][bucket]++
            } else {
                rejectedByKind[kind.ordinal]++
                rejectedMargins[kind.ordinal][bucket]++
            }
        }
        if (trace.threeEditsSearched) threeEditsSearches++
        val section = if (trace.threeEditsSearched) Section.AUTOCORRECTION_THREE_EDITS else Section.AUTOCORRECTION
        latencies[section.ordinal].record(nanos)

        pending = null
        if (refusedAtEnd) return
        var last: Applied? = null
        if (dictionaryApplied) {
            val search = trace.path == DecisionTrace.Path.SEARCH && kind != null
            val source = if (search) Source.DICTIONARY_SEARCH else Source.DICTIONARY_DIRECT
            applied[source.ordinal]++
            if (search && kind != null) appliedByKind[kind.ordinal]++
            last = Applied(source, if (search) kind else null)
        }
        if (contextApplied) {
            applied[Source.CONTEXT.ordinal]++
            last = Applied(Source.CONTEXT, null)
        }
        if (phraseApplied) {
            applied[Source.PHRASE_HABIT.ordinal]++
            last = Applied(Source.PHRASE_HABIT, null)
        }
        pending = last
    }

    /** Durée d'un calcul hors autocorrection (la bande de suggestions), en nanosecondes. */
    @Synchronized
    fun recordLatency(section: Section, nanos: Long) {
        latencies[section.ordinal].record(nanos)
    }

    /** Le clavier a réellement appliqué la correction du dernier mot examiné : elle devient celle qu'une annulation défait. */
    @Synchronized
    fun confirmApplied() {
        lastApplied = pending
        pending = null
    }

    /**
     * L'utilisateur a annulé une autocorrection. L'annulation est attribuée à la dernière correction appliquée (une
     * annulation tardive d'une correction plus ancienne est donc attribuée à tort : compteur indicatif).
     */
    @Synchronized
    fun recordUndone() {
        val last = lastApplied
        lastApplied = null
        if (last == null) {
            undoneUnattributed++
            return
        }
        undoneBySource[last.source.ordinal]++
        last.kind?.let { undoneByKind[it.ordinal]++ }
    }

    /** Nombre de mots examinés. */
    @Synchronized
    fun wordCount(): Long = words

    /** Nombre d'étapes de correction appliquées par [source] (un mot corrigé deux fois compte deux fois). */
    @Synchronized
    fun appliedCount(source: Source): Long = applied[source.ordinal]

    /** Nombre de corrections de la recherche de type [kind] appliquées. */
    @Synchronized
    fun appliedCount(kind: FaultKind): Long = appliedByKind[kind.ordinal]

    /** Nombre de meilleurs candidats de type [kind] refusés parce que leur marge n'atteignait pas le seuil. */
    @Synchronized
    fun rejectedByMargin(kind: FaultKind): Long = rejectedByKind[kind.ordinal]

    /** Nombre de corrections écartées parce que l'utilisateur les avait annulées deux fois. */
    @Synchronized
    fun refusalCount(): Long = refusals

    /** Nombre d'annulations attribuées à [source]. */
    @Synchronized
    fun undoneCount(source: Source): Long = undoneBySource[source.ordinal]

    /** Nombre d'annulations attribuées à la recherche de type [kind]. */
    @Synchronized
    fun undoneCount(kind: FaultKind): Long = undoneByKind[kind.ordinal]

    /** Nombre d'annulations sans correction appliquée connue. */
    @Synchronized
    fun undoneUnattributedCount(): Long = undoneUnattributed

    /** Nombre de fois où la recherche à trois erreurs a tourné. */
    @Synchronized
    fun threeEditsSearchCount(): Long = threeEditsSearches

    /** Nombre de mesures de latence de [section]. */
    @Synchronized
    fun latencyCount(section: Section): Long = latencies[section.ordinal].count

    /** Borne haute du centile [p] de la latence de [section], en microsecondes (voir [LatencyHistogram.percentileMicros]). */
    @Synchronized
    fun latencyPercentileMicros(section: Section, p: Double): Long = latencies[section.ordinal].percentileMicros(p)

    /** Seau de marge de [kind] pour les corrections appliquées (true) ou refusées (false) : copie. */
    @Synchronized
    fun marginBuckets(kind: FaultKind, accepted: Boolean): LongArray =
        (if (accepted) acceptedMargins else rejectedMargins)[kind.ordinal].copyOf()

    /** Efface tous les compteurs. */
    @Synchronized
    fun reset() {
        words = 0L
        refusals = 0L
        threeEditsSearches = 0L
        undoneUnattributed = 0L
        applied.fill(0L)
        undoneBySource.fill(0L)
        appliedByKind.fill(0L)
        rejectedByKind.fill(0L)
        undoneByKind.fill(0L)
        acceptedMargins.forEach { it.fill(0L) }
        rejectedMargins.forEach { it.fill(0L) }
        latencies.forEach { it.reset() }
        pending = null
        lastApplied = null
    }

    /**
     * Le rapport, en lignes courtes pour logcat : que des nombres et des noms de types. Les seaux de marge sont dans l'ordre
     * [MARGIN_LABELS]. Les types sans aucune décision et les sections sans mesure sont omis.
     */
    @Synchronized
    fun report(): List<String> {
        val lines = ArrayList<String>()
        lines += "Autocorrection : mots=$words etapes-appliquees=${applied.sum()} (" +
            sources.joinToString(" ") { "${it.label}=${applied[it.ordinal]}" } +
            ") refus-habitudes=$refusals annulees=${undoneBySource.sum() + undoneUnattributed} recherche-3-erreurs=$threeEditsSearches"
        lines += "Par type de faute (appliquees/refusees-marge/annulees) : " +
            kinds.joinToString(" ") { "${it.name}=${appliedByKind[it.ordinal]}/${rejectedByKind[it.ordinal]}/${undoneByKind[it.ordinal]}" }
        for (kind in kinds) {
            val accepted = acceptedMargins[kind.ordinal]
            val rejected = rejectedMargins[kind.ordinal]
            if (accepted.all { it == 0L } && rejected.all { it == 0L }) continue
            lines += "Marges ${kind.name} [$MARGIN_LABELS] appliquees=${accepted.joinToString(",")} refusees=${rejected.joinToString(",")}"
        }
        for (section in sections) {
            val histogram = latencies[section.ordinal]
            if (histogram.count == 0L) continue
            lines += "Latence ${section.label} : n=${histogram.count} p50<=${histogram.percentileMicros(0.50)}us " +
                "p95<=${histogram.percentileMicros(0.95)}us p99<=${histogram.percentileMicros(0.99)}us " +
                "max=${histogram.maxMicros}us lentes(>=16ms)=${histogram.slowCount}"
        }
        return lines
    }

    companion object {
        /** Bornes hautes (exclues) des seaux de marge, sauf le dernier qui prend tout le reste (l'infini compris). */
        private val MARGIN_BOUNDS = doubleArrayOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0)

        /** Nombre de seaux de marge : un par borne, plus celui du reste. */
        const val MARGIN_BUCKETS = 7

        /** Libellés des seaux, dans l'ordre. */
        const val MARGIN_LABELS = "<0.25|<0.5|<1|<2|<4|<8|>=8"

        /** Seau d'une marge : 0 pour moins de 0,25 (l'égalité stricte comprise), 6 pour 8 et plus (l'infini compris). */
        internal fun marginBucket(margin: Double): Int {
            for (i in MARGIN_BOUNDS.indices) if (margin < MARGIN_BOUNDS[i]) return i
            return MARGIN_BOUNDS.size
        }
    }
}
