package fr.junade.taipo.voice

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Nombres de la dictée dans les treize langues du clavier autres que le français et l'anglais ([NumberLexicons]). */
class SpokenNumbersOtherLanguagesTest {

    private val nbsp = " "

    private fun check(language: KeyboardLanguage, cases: Map<String, String>) {
        for ((spoken, digits) in cases) assertEquals(digits, SpokenNumbers.toDigits(spoken, language), "$language : $spoken")
    }

    @Test
    fun allemand() = check(
        KeyboardLanguage.DE,
        mapOf(
            "drei" to "3", "zwölf" to "12", "siebzehn" to "17", "dreiundzwanzig" to "23", "einundzwanzig" to "21",
            "hundert" to "100", "einhundertfünf" to "105", "zweihundertdreiundvierzig" to "243", "zweitausendsechsundzwanzig" to "2026",
            "neunzehnhundertsechsundachtzig" to "1986", "fünfzigtausend" to "50.000", "eine Million" to "1.000.000",
            "drei Komma fünf" to "3,5", "zwanzig Prozent" to "20$nbsp%", "ein Haus" to "ein Haus", "eins" to "1",
            "Ich habe drei Katzen und zwei Hunde" to "Ich habe 3 Katzen und 2 Hunde",
        ),
    )

    @Test
    fun espagnol() = check(
        KeyboardLanguage.ES,
        mapOf(
            "tres" to "3", "dieciséis" to "16", "veintitrés" to "23", "treinta y tres" to "33", "ciento veinte" to "120",
            "doscientos cincuenta y cinco" to "255", "dos mil veintiséis" to "2026", "un millón" to "1.000.000",
            "dos millones" to "2.000.000", "cien mil" to "100.000", "tres coma cinco" to "3,5", "veinte por ciento" to "20$nbsp%",
            "dos y tres" to "2 y 3", "un perro" to "un perro", "y tres" to "y 3", "veintitrés tres" to "23 3",
        ),
    )

    @Test
    fun italien() = check(
        KeyboardLanguage.IT,
        mapOf(
            "ventitré" to "23", "ventuno" to "21", "trentotto" to "38", "centottanta" to "180", "duecentoventi" to "220",
            "duemilaventisei" to "2026", "mille" to "1000", "un milione" to "1.000.000", "tre virgola cinque" to "3,5",
            "venti per cento" to "20%", "sei bravo" to "sei bravo", "sei mesi" to "sei mesi",
            "un gatto" to "un gatto",
        ),
    )

    @Test
    fun portugais() = check(
        KeyboardLanguage.PT,
        mapOf(
            "vinte e três" to "23", "cento e vinte" to "120", "duzentos e cinquenta" to "250", "dois mil e vinte e seis" to "2026",
            "dezesseis" to "16", "dezasseis" to "16", "um milhão" to "1.000.000", "três vírgula cinco" to "3,5",
            "vinte por cento" to "20%", "um gato" to "um gato", "dois e três" to "2 e 3",
        ),
    )

    @Test
    fun neerlandais() = check(
        KeyboardLanguage.NL,
        mapOf(
            "drieëntwintig" to "23", "eenentwintig" to "21", "honderdvijf" to "105", "tweeduizend zesentwintig" to "2026",
            "tweeduizendzesentwintig" to "2026", "negentienhonderd" to "1900", "één" to "1", "een huis" to "een huis",
            "drie komma vijf" to "3,5", "twintig procent" to "20%",
        ),
    )

    @Test
    fun polonais() = check(
        KeyboardLanguage.PL,
        mapOf(
            "dwadzieścia trzy" to "23", "dwieście pięćdziesiąt" to "250", "dwa tysiące dwadzieścia sześć" to "2026",
            "tysiąc" to "1000", "pięćdziesiąt tysięcy" to "50${nbsp}000", "dwa miliony" to "2${nbsp}000${nbsp}000",
            "trzy przecinek pięć" to "3,5", "dwadzieścia procent" to "20%", "jeden kot" to "jeden kot", "dziewiętnaście" to "19",
        ),
    )

    @Test
    fun roumain() = check(
        KeyboardLanguage.RO,
        mapOf(
            "douăzeci și trei" to "23", "o sută" to "100", "două sute cinci" to "205", "două mii douăzeci și șase" to "2026",
            "douăzeci de mii" to "20.000", "o mie nouă sute nouăzeci și nouă" to "1999", "un milion" to "1.000.000",
            "şapte" to "7", "trei virgulă cinci" to "3,5", "douăzeci la sută" to "20%", "o casă nouă" to "o casă nouă",
            "unsprezece" to "11", "sute" to "sute",
        ),
    )

    @Test
    fun tcheque() = check(
        KeyboardLanguage.CS,
        mapOf(
            "dvacet tři" to "23", "dvaadvacet" to "22", "sto" to "100", "dvě stě" to "200", "tři sta" to "300", "pět set" to "500",
            "dva tisíce dvacet šest" to "2026", "pět tisíc" to "5000", "tři celá pět" to "3,5", "dvacet procent" to "20$nbsp%",
            "jeden pes" to "jeden pes", "set" to "set",
        ),
    )

    @Test
    fun hongrois() = check(
        KeyboardLanguage.HU,
        mapOf(
            "tizenhárom" to "13", "huszonhárom" to "23", "harmincöt" to "35", "kétszáz" to "200", "kétezer-huszonhat" to "2026",
            "kétezerhuszonhat" to "2026", "ezerkilencszázkilencvenkilenc" to "1999", "három egész öt" to "3,5",
            "húsz százalék" to "20%", "egy ház" to "egy ház", "hét nap" to "hét nap", "hétszáz" to "700",
        ),
    )

    @Test
    fun suedois() = check(
        KeyboardLanguage.SV,
        mapOf(
            "tjugotre" to "23", "tjugoett" to "21", "sextiotre" to "63", "hundra" to "100", "tvåhundrafem" to "205",
            "tvåtusentjugosex" to "2026", "femtiotusen" to "50${nbsp}000", "tre komma fem" to "3,5", "tjugo procent" to "20$nbsp%",
            "en katt" to "en katt", "sexton" to "16", "miljonen" to "miljonen", "tusen" to "1000",
        ),
    )

    @Test
    fun danois() = check(
        KeyboardLanguage.DA,
        mapOf(
            "treogtyve" to "23", "enogtyve" to "21", "halvtreds" to "50", "femogfirs" to "85", "halvfems" to "90",
            "to tusind seksogtyve" to "2026", "et hundrede og fem" to "105", "nitten hundrede" to "1900", "tre komma fem" to "3,5",
            "tyve procent" to "20$nbsp%", "en hund" to "en hund", "et hus" to "et hus",
        ),
    )

    @Test
    fun norvegien() = check(
        KeyboardLanguage.NB,
        mapOf(
            "tjuetre" to "23",
            "tre og tjue" to "23",
            "hundre og fem" to "105",
            "to tusen og tjueseks" to "2026",
            "tre komma fem" to "3,5",
            "tjue prosent" to "20$nbsp%",
            "en bil" to "en bil", "millionen" to "millionen",
            "femti tusen" to "50${nbsp}000",
        ),
    )

    @Test
    fun finnois() = check(
        KeyboardLanguage.FI,
        mapOf(
            "kolme" to "3", "yksitoista" to "11", "kaksitoista" to "12", "kaksikymmentäkolme" to "23", "sata" to "100",
            "kaksisataa" to "200", "kaksituhatta kaksikymmentäkuusi" to "2026", "tuhat" to "1000", "kolme pilkku viisi" to "3,5",
            "kaksikymmentä prosenttia" to "20$nbsp%", "toista kertaa" to "toista kertaa", "sataa" to "sataa", "kuusi" to "kuusi",
        ),
    )
}
