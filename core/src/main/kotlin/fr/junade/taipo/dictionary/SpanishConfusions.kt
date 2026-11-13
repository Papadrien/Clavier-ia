package fr.junade.taipo.dictionary

import fr.junade.taipo.dictionary.FrenchConfusions.noneOf
import fr.junade.taipo.dictionary.FrenchConfusions.oneOf

/**
 * Confusions courantes de l'espagnol, par table déclarative : même moteur que [FrenchConfusions] ([FrenchConfusions.fixWith]),
 * autres règles. Le dictionnaire ne voit qu'un mot à la fois : « tu » et « tú », « el » et « él », « si » et « sí » sont tous
 * justes. Seul le voisin peut trancher, et seulement quand il rend l'autre lecture impossible (« tu eres » : aucun « tu » possessif
 * ne précède « eres »).
 *
 * Familles couvertes :
 * - interrogatifs après « ¿ » (« ¿como estas? » -> « ¿cómo estás? ») : que, como, donde, cuando, quien, cual, cuanto, adonde,
 *   « porque » -> « por qué » ; après une préposition qui suit le « ¿ » (« ¿a donde vas? ») ; en exclamation devant un mot
 *   d'exclamation (« ¡que bonito! ») ; « estas » / « esta » après un interrogatif ;
 * - interrogatifs après un verbe de savoir (« no sé como », « dime donde ») ;
 * - tu / tú (et « tú estas » -> « tú estás »), el / él, mi / mí, si / sí, se / sé, de / dé, aun / aún, a / ha, haber / a ver, echo / hecho ;
 * - mots soudés par erreur (« aveces », « porfavor », « osea », « enserio »).
 *
 * Principe (comme en français et en anglais) : sans contexte sûr, on ne corrige pas. Une règle dont le contexte reste possible
 * dans une phrase juste est retirée. Testé contre les phrases justes du banc (`tools/autocorrect-bench/es/sentences-ok.txt`) et
 * `SpanishConfusionsTest`. Les règles à mot suivant attendent ce mot (correction « à rebours ») et ne s'appliquent pas juste après
 * une annulation.
 *
 * Volontairement non couvert : « el vino » (le vin : « vino » n'est donc pas un verbe de [THIRD_PERSON_VERBS]), « el habla » (« el habla de la région » est juste), « solo » / « sólo » (la RAE ne demande plus l'accent), « que » / « qué » sans « ¿ » ni « ¡ » (« no sé que
 * hacer » est juste avec les deux lectures), « de el » -> « del » et « a el » -> « al » (la règle ne remplace qu'un mot), « sino » /
 * « si no », « porque » / « por qué » hors question, « sé » -> « se », « te » / « té » (« tu te vas » est juste), « la de » (« que la de
 * Pedro » est juste, donc pas de « dé » après « la » ni « lo »).
 */
internal object SpanishConfusions {

    fun fix(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String =
        FrenchConfusions.fixWith(RULES, text, textMayBeTruncated, allowRetro, frequencyOf)

    /** Le mot suit directement un « ¿ » : il ouvre la question. */
    private val OPENS_QUESTION: WordTest = { w, _ -> '¿' in w.leading }

    /** Le mot suit directement un « ¡ » : il ouvre l'exclamation. */
    private val OPENS_EXCLAMATION: WordTest = { w, _ -> '¡' in w.leading }

    /** Mots qui peuvent suivre le « ¿ » avant l'interrogatif : « ¿y como... », « ¿a donde... », « ¿por que... ». */
    private val QUESTION_OPENERS = arrayOf(
        "y", "e", "pero", "entonces", "a", "de", "por", "para", "en", "con", "desde", "hasta", "hacia", "sobre", "sin", "según",
        "tras", "contra", "entre", "ante",
    )

    private val AFTER_OPENING_QUESTION: WordTest = { w, _ -> '¿' in w.leading && w.core in QUESTION_OPENERS }

    /** Verbes de savoir et de demande après lesquels « como », « donde »... sont des interrogatifs (« no sé como »). */
    private val KNOWING_VERBS = arrayOf(
        "sé", "sabes", "sabe", "saber", "sabemos", "saben", "dime", "dígame", "digame", "explícame", "explicame", "explica", "imagina",
        "imagínate", "imaginate", "entiendo", "entiendes", "importa", "pregunto", "preguntar", "pregúntale", "preguntale",
    )

    private val INTERROGATIVES = mapOf(
        "que" to "qué", "como" to "cómo", "donde" to "dónde", "cuando" to "cuándo", "quien" to "quién", "quienes" to "quiénes",
        "cual" to "cuál", "cuales" to "cuáles", "cuanto" to "cuánto", "cuanta" to "cuánta", "cuantos" to "cuántos",
        "cuantas" to "cuántas", "adonde" to "adónde",
    )

    /** Interrogatifs qu'un verbe de savoir précède : sans « que », ambigu (« no sé que hacer »). */
    private val INDIRECT_INTERROGATIVES = arrayOf("como", "donde", "cuando", "quien", "cual", "cuanto", "cuantos", "cuantas", "cuanta")

    /** Mots d'exclamation après « ¡qué » : « ¡qué bonito! », « ¡qué pena! ». « ¡que te vaya bien! » reste sans accent. */
    private val EXCLAMATION_WORDS = arrayOf(
        "bonito", "bonita", "lindo", "linda", "bueno", "buena", "bien", "mal", "malo", "mala", "pena", "lástima", "lastima", "suerte",
        "horror", "asco", "miedo", "rico", "rica", "genial", "guapo", "guapa", "hermoso", "hermosa", "grande", "fuerte", "tarde",
        "pronto", "tonto", "tonta", "locura", "casualidad", "calor", "frío", "frio", "barbaridad", "vergüenza", "verguenza",
        "maravilla", "sorpresa", "alegría", "alegria", "fantástico", "fantastico", "increíble", "increible", "emoción", "emocion",
        "desastre", "idiota",
    )

    private val SECOND_PERSON_VERBS = arrayOf(
        "eres", "estás", "estas", "tienes", "quieres", "puedes", "sabes", "vas", "vienes", "haces", "dices", "ves", "crees", "piensas",
        "fuiste", "hiciste", "dijiste", "tuviste", "pareces", "necesitas", "conoces", "mereces", "debes",
    )

    private val THIRD_PERSON_VERBS = arrayOf(
        "es", "está", "esta", "estaba", "era", "fue", "tiene", "tenía", "tenia", "dijo", "dice", "quiere", "puede", "sabe", "sabía",
        "sabia", "va", "hizo", "hace", "ha", "había", "habia", "estuvo", "quiso", "pudo", "vive", "trabaja", "hablaba",
        "iba", "venía", "venia", "llegó", "llego", "salió", "salio",
    )

    /** Noms qui suivent un possessif (« tu casa », « mi madre ») et jamais un pronom tonique. */
    private val POSSESSED_NOUNS = arrayOf(
        "casa", "madre", "padre", "familia", "nombre", "trabajo", "vida", "coche", "carro", "teléfono", "telefono", "hermano", "hermana",
        "hijo", "hija", "mamá", "mama", "papá", "papa", "esposa", "esposo", "novia", "novio", "perro", "gato", "amigo", "amiga",
        "amigos", "amigas", "opinión", "opinion", "ayuda", "culpa", "turno", "país", "pais", "idea", "cara", "cabeza", "cuerpo",
        "mente", "dinero", "tiempo", "cuenta", "correo", "número", "numero", "dirección", "direccion",
    )

    /** Prépositions après lesquelles un pronom tonique (« él », « mí ») peut finir le groupe : « con él, », « para mí. ». */
    private val PREPOSITIONS = arrayOf(
        "con", "para", "por", "sin", "sobre", "de", "a", "ante", "contra", "hacia", "desde", "hasta", "según", "entre", "tras",
    )

    /** Auxiliaire « haber » : il est suivi d'un participe, jamais de « echo » (je jette). */
    private val HABER_FORMS = arrayOf(
        "he", "has", "ha", "hemos", "han", "había", "habías", "habíamos", "habían", "habia", "habias", "habiamos", "habian", "hube",
        "habré", "habrá", "haya", "hayas", "hayan", "habiendo",
    )

    private val IRREGULAR_PARTICIPLES = setOf(
        "hecho", "dicho", "visto", "puesto", "escrito", "vuelto", "roto", "muerto", "abierto", "cubierto", "resuelto", "satisfecho",
        "devuelto", "frito", "impreso",
    )

    /** Participe passé probable : en -ado / -ido (5 lettres ou plus) ou participe irrégulier courant. */
    private val PARTICIPLE_LIKE: WordTest = { w, _ ->
        val c = w.core
        c in IRREGULAR_PARTICIPLES || (c.length >= 5 && '\'' !in c && (c.endsWith("ado") || c.endsWith("ido")))
    }

    /** Infinitif probable : en -ar, -er ou -ir (3 lettres ou plus). */
    private val INFINITIVE_LIKE: WordTest = { w, _ ->
        val c = w.core
        c.length >= 3 && '\'' !in c && (c.endsWith("ar") || c.endsWith("er") || c.endsWith("ir"))
    }

    /** « osea » est « o sea », sauf dans « médula ósea » : ces noms précèdent l'adjectif. */
    private val BONE_NOUNS = arrayOf("médula", "medula", "tejido", "masa", "densidad", "estructura", "ceniza")

    internal val RULES: List<ConfusionRule> = buildList {
        // --- Interrogatifs après « ¿ » (« ¿como estas? », « ¿a donde vas? », « ¿por que? ») ---
        for ((plain, accented) in INTERROGATIVES) {
            add(ConfusionRule(plain, accented, "$plain > $accented juste après « ¿ »", self = OPENS_QUESTION))
            add(ConfusionRule(plain, accented, "$plain > $accented après « ¿ » et une préposition", previous = AFTER_OPENING_QUESTION))
            add(ConfusionRule(plain, accented, "$plain > $accented juste après « ¿ », en fin de question", self = OPENS_QUESTION, punctAfter = true))
            add(
                ConfusionRule(
                    plain,
                    accented,
                    "$plain > $accented après « ¿ » et une préposition, en fin de question",
                    previous = AFTER_OPENING_QUESTION,
                    punctAfter = true,
                ),
            )
        }
        add(ConfusionRule("porque", "por qué", "porque > por qué juste après « ¿ »", self = OPENS_QUESTION))
        add(ConfusionRule("porque", "por qué", "porque > por qué après « ¿ » et une conjonction", previous = AFTER_OPENING_QUESTION))
        add(ConfusionRule("porque", "por qué", "porque > por qué juste après « ¿ », en fin de question", self = OPENS_QUESTION, punctAfter = true))

        // --- « ¡que bonito! » : seulement devant un mot d'exclamation ---
        add(ConfusionRule("que", "qué", "que > qué après « ¡ » devant un mot d'exclamation", self = OPENS_EXCLAMATION, next = oneOf(*EXCLAMATION_WORDS)))

        // --- estás / está dans une question (« ¿como estas? », « ¿donde esta mi casa? », « ¿que esta pasando? ») ---
        add(ConfusionRule("estas", "estás", "estas > estás après « ¿cómo »", previous = anyAfterQuestion("como", "cómo", "que", "qué", "donde", "dónde")))
        add(
            ConfusionRule(
                "estas",
                "estás",
                "estas > estás après « ¿cómo », en fin de question",
                previous = anyAfterQuestion("como", "cómo", "que", "qué", "donde", "dónde"),
                punctAfter = true,
            ),
        )
        add(ConfusionRule("estas", "estás", "estas > estás après « cómo » ou « dónde »", previous = oneOf("cómo", "dónde")))
        add(
            ConfusionRule(
                "estas",
                "estás",
                "estas > estás après « cómo » ou « dónde », en fin de question",
                previous = oneOf("cómo", "dónde"),
                punctAfter = true,
            ),
        )
        add(ConfusionRule("esta", "está", "esta > está après « dónde » ou « cómo »", previous = oneOf("dónde", "cómo")))
        add(ConfusionRule("esta", "está", "esta > está après « dónde » ou « cómo », en fin de question", previous = oneOf("dónde", "cómo"), punctAfter = true))
        add(ConfusionRule("esta", "está", "esta > está après « ¿dónde » ou « ¿cómo »", previous = anyAfterQuestion("donde", "dónde", "como", "cómo")))
        add(
            ConfusionRule(
                "esta",
                "está",
                "esta > está après « ¿dónde » ou « ¿cómo », en fin de question",
                previous = anyAfterQuestion("donde", "dónde", "como", "cómo"),
                punctAfter = true,
            ),
        )
        add(
            ConfusionRule(
                "esta",
                "está",
                "esta > está après « ¿qué » devant un gérondif",
                previous = anyAfterQuestion("que", "qué"),
                next = oneOf("pasando", "haciendo", "diciendo", "buscando", "mirando"),
            ),
        )

        // --- Interrogatifs indirects après un verbe de savoir (« no sé como », « dime donde ») ---
        for (plain in INDIRECT_INTERROGATIVES) {
            val accented = INTERROGATIVES.getValue(plain)
            add(ConfusionRule(plain, accented, "$plain > $accented après un verbe de savoir", previous = oneOf(*KNOWING_VERBS)))
        }

        // --- estás après « tú » ---
        add(ConfusionRule("estas", "estás", "estas > estás après « tú »", previous = oneOf("tú")))

        // --- tu / tú ---
        add(ConfusionRule("tu", "tú", "tu > tú devant un verbe à la 2e personne", next = oneOf(*SECOND_PERSON_VERBS)))
        add(ConfusionRule("tú", "tu", "tú > tu devant un nom de possession", next = oneOf(*POSSESSED_NOUNS)))

        // --- el / él ---
        add(ConfusionRule("el", "él", "el > él devant un verbe conjugué", next = oneOf(*THIRD_PERSON_VERBS)))
        add(ConfusionRule("el", "él", "el > él en fin de groupe après une préposition", previous = oneOf(*PREPOSITIONS), punctAfter = true))

        // --- mi / mí ---
        add(ConfusionRule("mi", "mí", "mi > mí en fin de groupe après une préposition", previous = oneOf(*PREPOSITIONS), punctAfter = true))
        add(
            ConfusionRule(
                "mi",
                "mí",
                "mi > mí après « a » ou « para » devant un mot qui ne suit pas un possessif",
                previous = oneOf("a", "para"),
                next = oneOf("me", "no", "también", "tambien", "tampoco", "nada", "siempre"),
            ),
        )
        add(ConfusionRule("mí", "mi", "mí > mi devant un nom de possession", next = oneOf(*POSSESSED_NOUNS)))

        // --- si / sí ---
        add(ConfusionRule("si", "sí", "si > sí en début de phrase, suivi d'une ponctuation", sentenceStart = true, punctAfter = true))
        add(ConfusionRule("si", "sí", "si > sí après « que », en fin de groupe", previous = oneOf("que"), punctAfter = true))
        add(
            ConfusionRule(
                "si",
                "sí",
                "si > sí devant « mismo »",
                previous = oneOf("a", "por", "en", "de", "para", "entre", "sobre"),
                next = oneOf("mismo", "misma", "mismos", "mismas"),
            ),
        )

        // --- se / sé ---
        add(
            ConfusionRule(
                "se",
                "sé",
                "se > sé après « no » ou « yo » devant un interrogatif, « que » ou « nada »",
                previous = oneOf("no", "yo"),
                next = oneOf(
                    "si", "qué", "que", "nada", "mucho", "cómo", "como", "cuándo", "cuando", "dónde", "donde", "quién", "quien",
                    "por", "cuál", "cual",
                ),
            ),
        )

        // --- de / dé ---
        add(
            ConfusionRule(
                "de",
                "dé",
                "de > dé après « que » et un pronom complément",
                previous = oneOf("me", "te", "le", "les", "nos"),
                previous2 = oneOf("que"),
            ),
        )

        // --- aun / aún ---
        add(
            ConfusionRule(
                "aun",
                "aún",
                "aun > aún devant « no » ou un verbe d'état",
                next = oneOf(
                    "no", "está", "estoy", "estás", "estamos", "están", "hay", "tengo", "tiene", "sigue", "queda", "quedan", "falta",
                    "faltan", "existe", "es", "son",
                ),
            ),
        )

        // --- a / ha ---
        add(
            ConfusionRule(
                "a",
                "ha",
                "a > ha entre un sujet ou une négation et un participe",
                previous = oneOf(
                    "él", "ella", "usted", "ya", "no", "nunca", "siempre", "también", "tambien", "que", "se", "lo", "le", "me", "te", "nos",
                    "quien",
                ),
                next = PARTICIPLE_LIKE,
            ),
        )
        add(
            ConfusionRule(
                "ha",
                "a",
                "ha > a après « ir » devant un infinitif",
                previous = oneOf("voy", "vas", "va", "vamos", "vais", "van", "iba", "ibas", "íbamos", "ibamos", "iban"),
                next = INFINITIVE_LIKE,
            ),
        )

        // --- haber / a ver, echo / hecho ---
        add(ConfusionRule("haber", "a ver", "haber > a ver devant « si »", next = oneOf("si")))
        add(
            ConfusionRule(
                "aver",
                "a ver",
                "aver > a ver devant un interrogatif ou « si »",
                next = oneOf("si", "que", "qué", "como", "cómo", "quien", "quién", "cuando", "cuándo", "donde", "dónde", "cual", "cuál"),
            ),
        )
        add(ConfusionRule("echo", "hecho", "echo > hecho après l'auxiliaire haber", previous = oneOf(*HABER_FORMS)))
        add(ConfusionRule("hecho", "echo", "hecho > echo dans « te echo de menos »", previous = oneOf("te"), next = oneOf("de")))

        // --- mots soudés ---
        add(ConfusionRule("aveces", "a veces", "aveces > a veces", always = true))
        add(ConfusionRule("porfavor", "por favor", "porfavor > por favor", always = true))
        add(ConfusionRule("enserio", "en serio", "enserio > en serio", always = true))
        add(ConfusionRule("sinembargo", "sin embargo", "sinembargo > sin embargo", always = true))
        add(ConfusionRule("alomejor", "a lo mejor", "alomejor > a lo mejor", always = true))
        add(ConfusionRule("atraves", "a través", "atraves > a través", always = true))
        add(ConfusionRule("apesar", "a pesar", "apesar > a pesar", always = true))
        add(ConfusionRule("osea", "o sea", "osea > o sea en début de phrase", sentenceStart = true))
        add(ConfusionRule("osea", "o sea", "osea > o sea hors « médula ósea »", previous = noneOf(*BONE_NOUNS)))
    }

    /** Le mot précédent suit directement un « ¿ » (ou porte lui-même le « ¿ ») et est l'un de [words]. */
    private fun anyAfterQuestion(vararg words: String): WordTest {
        val set = words.toSet()
        return { w, _ -> '¿' in w.leading && w.core in set }
    }
}

/** Correction de contexte de l'espagnol ([SpanishConfusions]) : le pendant de [EnglishContextCorrector] pour l'espagnol. */
object SpanishContextCorrector {

    /**
     * Corrige la fin de [text] (le texte avant le curseur, sans la touche qui termine le mot). Renvoie le texte corrigé, ou
     * null s'il n'y a rien à changer. Mêmes paramètres que [FrenchContextCorrector.correct].
     */
    fun correct(text: String, textMayBeTruncated: Boolean, allowRetro: Boolean, frequencyOf: (String) -> Long): String? {
        if (text.isEmpty() || text.last().isWhitespace()) return null
        val fixed = SpanishConfusions.fix(text, textMayBeTruncated, allowRetro, frequencyOf)
        return if (fixed == text) null else fixed
    }
}
