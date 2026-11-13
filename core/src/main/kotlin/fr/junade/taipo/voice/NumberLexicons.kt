package fr.junade.taipo.voice

import fr.junade.taipo.KeyboardLanguage
import java.util.concurrent.ConcurrentHashMap

/**
 * Lexiques des nombres dits en lettres des treize langues du clavier autres que le français et l'anglais (voir
 * [CompoundNumberRules]). Chaque liste est écrite sous la forme « mot=valeur » ; K, M et G valent mille, un million et un
 * milliard. Les formes « liées » (bound) ne s'emploient qu'après un nombre (« sute », « tysięcy », « tuhatta »).
 *
 * Mise en forme, selon l'usage de chaque langue : virgule décimale partout ; séparateur des milliers point (allemand, espagnol,
 * italien, portugais, néerlandais, roumain, danois) ou espace insécable (polonais, tchèque, hongrois, suédois, norvégien,
 * finnois), à partir de 10 000 ; espace insécable avant « % » là où la norme typographique la demande.
 */
internal object NumberLexicons {

    private val built = ConcurrentHashMap<KeyboardLanguage, SpokenNumbers.Rules>()

    fun rules(language: KeyboardLanguage): SpokenNumbers.Rules = built.getOrPut(language) { CompoundNumberRules(lexicon(language)) }

    private fun lexicon(language: KeyboardLanguage): NumberLexicon = when (language) {
        KeyboardLanguage.FR, KeyboardLanguage.EN -> error("$language a ses propres règles")
        KeyboardLanguage.DE -> german()
        KeyboardLanguage.ES -> spanish()
        KeyboardLanguage.IT -> italian()
        KeyboardLanguage.PT -> portuguese()
        KeyboardLanguage.NL -> dutch()
        KeyboardLanguage.PL -> polish()
        KeyboardLanguage.RO -> romanian()
        KeyboardLanguage.CS -> czech()
        KeyboardLanguage.HU -> hungarian()
        KeyboardLanguage.SV -> swedish()
        KeyboardLanguage.DA -> danish()
        KeyboardLanguage.NB -> norwegian()
        KeyboardLanguage.FI -> finnish()
    }

    private fun german() = build(
        numbers = "ein=1 eins=1 eine=1 zwei=2 zwo=2 drei=3 vier=4 fünf=5 sechs=6 sieben=7 acht=8 neun=9 zehn=10 elf=11 zwölf=12 " +
            "dreizehn=13 vierzehn=14 fünfzehn=15 sechzehn=16 siebzehn=17 achtzehn=18 neunzehn=19 zwanzig=20 dreißig=30 " +
            "dreissig=30 vierzig=40 fünfzig=50 sechzig=60 siebzig=70 achtzig=80 neunzig=90",
        hundreds = "hundert",
        scales = "tausend=K million=M millionen=M milliarde=G milliarden=G",
        connectors = "und",
        zero = "null",
        ambiguous = "ein eine",
        decimal = "komma",
        percent = "prozent",
        group = ".",
        percentSign = "$NBSP%",
    )

    private fun spanish() = build(
        numbers = "un=1 uno=1 una=1 dos=2 tres=3 cuatro=4 cinco=5 seis=6 siete=7 ocho=8 nueve=9 diez=10 once=11 doce=12 trece=13 " +
            "catorce=14 quince=15 dieciséis=16 dieciseis=16 diecisiete=17 dieciocho=18 diecinueve=19 veinte=20 veintiuno=21 " +
            "veintiún=21 veintiuna=21 veintidós=22 veintidos=22 veintitrés=23 veintitres=23 veinticuatro=24 veinticinco=25 " +
            "veintiséis=26 veintiseis=26 veintisiete=27 veintiocho=28 veintinueve=29 treinta=30 cuarenta=40 cincuenta=50 " +
            "sesenta=60 setenta=70 ochenta=80 noventa=90 cien=100 ciento=100 doscientos=200 doscientas=200 trescientos=300 " +
            "trescientas=300 cuatrocientos=400 cuatrocientas=400 quinientos=500 quinientas=500 seiscientos=600 seiscientas=600 " +
            "setecientos=700 setecientas=700 ochocientos=800 ochocientas=800 novecientos=900 novecientas=900",
        scales = "mil=K millón=M millon=M",
        boundScales = "millones=M",
        connectors = "y",
        zero = "cero",
        ambiguous = "un uno una",
        decimal = "coma",
        percent = "por ciento",
        group = ".",
        percentSign = "$NBSP%",
    )

    private fun italian() = build(
        numbers = "uno=1 un=1 una=1 due=2 tre=3 tré=3 quattro=4 cinque=5 sei=6 sette=7 otto=8 nove=9 dieci=10 undici=11 dodici=12 " +
            "tredici=13 quattordici=14 quindici=15 sedici=16 diciassette=17 diciotto=18 diciannove=19 venti=20 vent=20 trenta=30 " +
            "trent=30 quaranta=40 quarant=40 cinquanta=50 cinquant=50 sessanta=60 sessant=60 settanta=70 settant=70 ottanta=80 " +
            "ottant=80 novanta=90 novant=90",
        hundreds = "cento cent",
        scales = "mille=K milione=M miliardo=G",
        boundScales = "mila=K milioni=M miliardi=G",
        zero = "zero",
        ambiguous = "un uno una sei cent",
        decimal = "virgola",
        percent = "per cento|percento",
        group = ".",
        percentSign = "%",
    )

    private fun portuguese() = build(
        numbers = "um=1 uma=1 dois=2 duas=2 três=3 tres=3 quatro=4 cinco=5 seis=6 sete=7 oito=8 nove=9 dez=10 onze=11 doze=12 " +
            "treze=13 catorze=14 quatorze=14 quinze=15 dezesseis=16 dezasseis=16 dezessete=17 dezassete=17 dezoito=18 dezenove=19 " +
            "dezanove=19 vinte=20 trinta=30 quarenta=40 cinquenta=50 sessenta=60 setenta=70 oitenta=80 noventa=90 cem=100 " +
            "cento=100 duzentos=200 duzentas=200 trezentos=300 trezentas=300 quatrocentos=400 quatrocentas=400 quinhentos=500 " +
            "quinhentas=500 seiscentos=600 seiscentas=600 setecentos=700 setecentas=700 oitocentos=800 oitocentas=800 " +
            "novecentos=900 novecentas=900",
        scales = "mil=K milhão=M bilhão=G",
        boundScales = "milhões=M bilhões=G",
        connectors = "e",
        zero = "zero",
        ambiguous = "um uma",
        decimal = "vírgula virgula",
        percent = "por cento",
        group = ".",
        percentSign = "%",
    )

    private fun dutch() = build(
        numbers = "een=1 één=1 twee=2 drie=3 vier=4 vijf=5 zes=6 zeven=7 acht=8 negen=9 tien=10 elf=11 twaalf=12 dertien=13 " +
            "veertien=14 vijftien=15 zestien=16 zeventien=17 achttien=18 negentien=19 twintig=20 dertig=30 veertig=40 vijftig=50 " +
            "zestig=60 zeventig=70 tachtig=80 negentig=90",
        hundreds = "honderd",
        scales = "duizend=K miljoen=M miljard=G",
        connectors = "en ën",
        zero = "nul",
        ambiguous = "een",
        decimal = "komma",
        percent = "procent",
        group = ".",
        percentSign = "%",
    )

    private fun polish() = build(
        numbers = "jeden=1 jedna=1 jedno=1 dwa=2 dwie=2 trzy=3 cztery=4 pięć=5 sześć=6 siedem=7 osiem=8 dziewięć=9 dziesięć=10 " +
            "jedenaście=11 dwanaście=12 trzynaście=13 czternaście=14 piętnaście=15 szesnaście=16 siedemnaście=17 osiemnaście=18 " +
            "dziewiętnaście=19 dwadzieścia=20 trzydzieści=30 czterdzieści=40 pięćdziesiąt=50 sześćdziesiąt=60 siedemdziesiąt=70 " +
            "osiemdziesiąt=80 dziewięćdziesiąt=90 sto=100 dwieście=200 trzysta=300 czterysta=400 pięćset=500 sześćset=600 " +
            "siedemset=700 osiemset=800 dziewięćset=900",
        scales = "tysiąc=K milion=M miliard=G",
        boundScales = "tysiące=K tysięcy=K miliony=M milionów=M miliardy=G miliardów=G",
        zero = "zero",
        ambiguous = "jeden jedna jedno",
        decimal = "przecinek",
        percent = "procent",
        group = NBSP,
        percentSign = "%",
    )

    private fun romanian() = build(
        numbers = "unu=1 un=1 una=1 o=1 doi=2 două=2 trei=3 patru=4 cinci=5 șase=6 șapte=7 opt=8 nouă=9 zece=10 unsprezece=11 " +
            "doisprezece=12 douăsprezece=12 treisprezece=13 paisprezece=14 cincisprezece=15 șaisprezece=16 șaptesprezece=17 " +
            "optsprezece=18 nouăsprezece=19 douăzeci=20 treizeci=30 patruzeci=40 cincizeci=50 șaizeci=60 șaptezeci=70 optzeci=80 " +
            "nouăzeci=90",
        boundHundreds = "sută sute",
        scales = "milion=M miliard=G",
        boundScales = "mie=K mii=K milioane=M miliarde=G",
        connectors = "și",
        scaleConnectors = "de",
        zero = "zero",
        ambiguous = "un una o nouă",
        decimal = "virgulă",
        percent = "la sută|procent|procente",
        group = ".",
        percentSign = "%",
        // Cédille (ş, ţ) encore fréquente à la place de la virgule souscrite (ș, ț).
        variants = { word -> setOf(word, word.replace('ș', 'ş').replace('ț', 'ţ')) },
    )

    private fun czech() = build(
        numbers = "jeden=1 jedna=1 jedno=1 dva=2 dvě=2 tři=3 čtyři=4 pět=5 šest=6 sedm=7 osm=8 devět=9 deset=10 jedenáct=11 " +
            "dvanáct=12 třináct=13 čtrnáct=14 patnáct=15 šestnáct=16 sedmnáct=17 osmnáct=18 devatenáct=19 dvacet=20 třicet=30 " +
            "čtyřicet=40 padesát=50 šedesát=60 sedmdesát=70 osmdesát=80 devadesát=90 sto=100",
        boundHundreds = "stě sta set",
        scales = "tisíc=K milion=M miliarda=G",
        boundScales = "tisíce=K miliony=M milionů=M miliardy=G miliard=G",
        // « dvaadvacet » (22) : unité, « a », dizaine.
        connectors = "a",
        zero = "nula",
        ambiguous = "jeden jedna jedno",
        decimal = "celá celé celých",
        percent = "procent|procenta|procento",
        group = NBSP,
        percentSign = "$NBSP%",
    )

    private fun hungarian() = build(
        numbers = "egy=1 kettő=2 két=2 három=3 négy=4 öt=5 hat=6 hét=7 nyolc=8 kilenc=9 tíz=10 húsz=20 harminc=30 negyven=40 " +
            "ötven=50 hatvan=60 hetven=70 nyolcvan=80 kilencven=90 huszon=20",
        prefixes = "tizen=10",
        hundreds = "száz",
        scales = "ezer=K millió=M milliárd=G",
        zero = "nulla",
        // « egy » : article ; « hét » : semaine ; « hat » : verbe (agir).
        ambiguous = "egy hét hat",
        decimal = "egész",
        percent = "százalék",
        group = NBSP,
        percentSign = "%",
    )

    private fun swedish() = build(
        numbers = "en=1 ett=1 två=2 tre=3 fyra=4 fem=5 sex=6 sju=7 åtta=8 nio=9 tio=10 elva=11 tolv=12 tretton=13 fjorton=14 " +
            "femton=15 sexton=16 sjutton=17 arton=18 nitton=19 tjugo=20 trettio=30 fyrtio=40 femtio=50 sextio=60 sjuttio=70 " +
            "åttio=80 nittio=90",
        hundreds = "hundra",
        scales = "tusen=K miljon=M miljard=G",
        boundScales = "miljoner=M miljarder=G",
        zero = "noll",
        ambiguous = "en ett",
        decimal = "komma",
        percent = "procent",
        group = NBSP,
        percentSign = "$NBSP%",
    )

    private fun danish() = build(
        numbers = "en=1 et=1 to=2 tre=3 fire=4 fem=5 seks=6 syv=7 otte=8 ni=9 ti=10 elleve=11 tolv=12 tretten=13 fjorten=14 " +
            "femten=15 seksten=16 sytten=17 atten=18 nitten=19 tyve=20 tredive=30 fyrre=40 halvtreds=50 tres=60 halvfjerds=70 " +
            "firs=80 halvfems=90",
        hundreds = "hundrede hundred",
        scales = "tusind=K million=M milliard=G",
        boundScales = "millioner=M milliarder=G",
        connectors = "og",
        zero = "nul",
        ambiguous = "en et",
        decimal = "komma",
        percent = "procent",
        group = ".",
        percentSign = "$NBSP%",
    )

    private fun norwegian() = build(
        numbers = "en=1 ett=1 ei=1 to=2 tre=3 fire=4 fem=5 seks=6 sju=7 syv=7 åtte=8 ni=9 ti=10 elleve=11 tolv=12 tretten=13 " +
            "fjorten=14 femten=15 seksten=16 sytten=17 atten=18 nitten=19 tjue=20 tyve=20 tretti=30 tredve=30 førti=40 femti=50 " +
            "seksti=60 sytti=70 åtti=80 nitti=90",
        hundreds = "hundre",
        scales = "tusen=K million=M milliard=G",
        boundScales = "millioner=M milliarder=G",
        connectors = "og",
        zero = "null",
        ambiguous = "en ett ei",
        decimal = "komma",
        percent = "prosent",
        group = NBSP,
        percentSign = "$NBSP%",
    )

    private fun finnish() = build(
        numbers = "yksi=1 kaksi=2 kolme=3 neljä=4 viisi=5 kuusi=6 seitsemän=7 kahdeksan=8 yhdeksän=9 kymmenen=10 sata=100",
        boundHundreds = "sataa",
        scales = "tuhat=K miljoona=M miljardi=G",
        boundScales = "tuhatta=K miljoonaa=M miljardia=G",
        extra = mapOf("kymmentä" to Morpheme.TenTimes, "toista" to Morpheme.TeenSuffix),
        zero = "nolla",
        // « kuusi » : aussi « épicéa ».
        ambiguous = "kuusi",
        decimal = "pilkku",
        percent = "prosenttia|prosentti",
        group = NBSP,
        percentSign = "$NBSP%",
    )

    @Suppress("LongParameterList")
    private fun build(
        numbers: String,
        zero: String,
        ambiguous: String,
        decimal: String,
        percent: String,
        group: String,
        percentSign: String,
        prefixes: String = "",
        hundreds: String = "",
        boundHundreds: String = "",
        scales: String = "",
        boundScales: String = "",
        connectors: String = "",
        scaleConnectors: String = "",
        extra: Map<String, Morpheme> = emptyMap(),
        variants: (String) -> Set<String> = { setOf(it) },
    ): NumberLexicon {
        val morphemes = HashMap<String, Morpheme>()
        fun add(word: String, morpheme: Morpheme) {
            for (form in variants(word)) check(morphemes.put(form, morpheme) == null) { "« $form » en double" }
        }
        for ((word, value) in pairs(numbers)) add(word, Morpheme.Number(value))
        for ((word, value) in pairs(prefixes)) add(word, Morpheme.Number(value, prefix = true))
        for (word in words(hundreds)) add(word, Morpheme.Hundred(bound = false))
        for (word in words(boundHundreds)) add(word, Morpheme.Hundred(bound = true))
        for ((word, value) in pairs(scales)) add(word, Morpheme.Scale(value, bound = false))
        for ((word, value) in pairs(boundScales)) add(word, Morpheme.Scale(value, bound = true))
        for (word in words(connectors)) add(word, Morpheme.Connector(beforeScale = false))
        for (word in words(scaleConnectors)) add(word, Morpheme.Connector(beforeScale = true))
        for (word in words(zero)) add(word, Morpheme.Zero)
        for ((word, morpheme) in extra) add(word, morpheme)
        return NumberLexicon(
            morphemes = morphemes,
            ambiguous = words(ambiguous).flatMap(variants).toSet(),
            decimalWords = words(decimal).flatMap(variants).toSet(),
            groupSeparator = group,
            percentWords = percent.split('|').map { words(it) },
            percentSign = percentSign,
        )
    }

    private fun words(spec: String): List<String> = spec.split(' ').filter { it.isNotEmpty() }

    private fun pairs(spec: String): List<Pair<String, Long>> = words(spec).map { entry ->
        val (word, value) = entry.split('=')
        word to when (value) {
            "K" -> 1_000L
            "M" -> 1_000_000L
            "G" -> 1_000_000_000L
            else -> value.toLong()
        }
    }

    private const val NBSP = " "
}
