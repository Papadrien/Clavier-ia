package fr.junade.taipo.dictionary

import fr.junade.taipo.suggestion.WordText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Allemand, phase 5 : confusions de mots par le contexte (seid / seit, wie / wir, wurde / würde, das / dass, mal / Mal, heure du jour, ihn / ihm).
 * La frappe est simulée comme au banc : la correction de contexte tourne à chaque caractère qui n'est pas dans un mot, puis à la fin.
 * Les phrases justes pièges (`VALID`) ne doivent jamais changer : une règle qui en change une est retirée, pas assouplie.
 */
class GermanConfusionsTest {

    private fun typed(sentence: String): String {
        var text = ""
        for (c in sentence) {
            if (!WordText.isWordChar(c)) text = correct(text)
            text += c
        }
        return correct(text)
    }

    private fun correct(text: String): String {
        if (WordText.trailingWord(text).isEmpty()) return text
        return GermanContextCorrector.correct(text, textMayBeTruncated = false, allowRetro = true, frequencyOf = { 0L }) ?: text
    }

    @Test
    fun `les confusions evidentes sont corrigees`() {
        val failures = CORRECTED.filter { (sentence, expected) -> typed(sentence) != expected }.map { (sentence, expected) -> "$sentence -> ${typed(sentence)} (attendu $expected)" }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `les phrases justes ne changent pas`() {
        val failures = VALID.filter { typed(it) != it }.map { "$it -> ${typed(it)}" }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `une correction a deux mots suivants attend le deuxieme mot`() {
        // « Wie sind » : rien tant que le mot qui décide n'est pas tapé ; « Wie sind müde » : « wir ».
        assertEquals("Wie sind", correct("Wie sind"))
        assertEquals("Wir sind müde", correct("Wie sind müde"))
    }

    @Test
    fun `rien juste apres une annulation`() {
        // allowRetro = false : les règles à mot suivant ne s'appliquent pas (comme en français).
        assertEquals(null, GermanContextCorrector.correct("Wie sind müde", textMayBeTruncated = false, allowRetro = false, frequencyOf = { 0L }))
    }

    private companion object {
        val CORRECTED = listOf(
            "Ihr seit meine Freunde." to "Ihr seid meine Freunde.",
            "Ich wohne hier seid drei Jahren." to "Ich wohne hier seit drei Jahren.",
            "Er arbeitet seid Montag nicht mehr." to "Er arbeitet seit Montag nicht mehr.",
            "Wo seit ihr gestern gewesen?" to "Wo seid ihr gestern gewesen?",
            "Seid wann wohnst du hier?" to "Seit wann wohnst du hier?",
            "Ich kenne ihn seid langem." to "Ich kenne ihn seit langem.",
            "Wie sind müde." to "Wir sind müde.",
            "Wie haben keine Zeit." to "Wir haben keine Zeit.",
            "Wie müssen los." to "Wir müssen los.",
            "Ich wurde gern mitkommen." to "Ich würde gern mitkommen.",
            "Er wurde gern schlafen." to "Er würde gern schlafen.",
            "Wenn ich Zeit hätte, wurde ich kommen." to "Wenn ich Zeit hätte, würde ich kommen.",
            "Was wurdest du tun?" to "Was würdest du tun?",
            "Ich weiß, das du müde bist." to "Ich weiß, dass du müde bist.",
            "Ich glaube, das er recht hat." to "Ich glaube, dass er recht hat.",
            "Er sagt, das sie kommt." to "Er sagt, dass sie kommt.",
            "Ich hoffe, das wir uns bald sehen." to "Ich hoffe, dass wir uns bald sehen.",
            "Es ist schön, das du da bist." to "Es ist schön, dass du da bist.",
            "Zum ersten mal war ich in Hamburg." to "Zum ersten Mal war ich in Hamburg.",
            "Das nächste mal bringe ich Kuchen mit." to "Das nächste Mal bringe ich Kuchen mit.",
            "Ich war schon einmal dort, das letzte mal im Sommer." to "Ich war schon einmal dort, das letzte Mal im Sommer.",
            "Heute morgen war ich müde." to "Heute Morgen war ich müde.",
            "Heute abend gehen wir ins Theater." to "Heute Abend gehen wir ins Theater.",
            "Gestern abend war ich zu Hause." to "Gestern Abend war ich zu Hause.",
            "Heute nacht habe ich schlecht geschlafen." to "Heute Nacht habe ich schlecht geschlafen.",
            "Ich weiß, das sie kommt." to "Ich weiß, dass sie kommt.",
            "Es ist schade, das es regnet." to "Es ist schade, dass es regnet.",
            "Ich helfe ihn jeden Tag." to "Ich helfe ihm jeden Tag.",
            "Ich kenne ihm nicht." to "Ich kenne ihn nicht.",
            "Wir sehen uns heute abend." to "Wir sehen uns heute Abend.",
            "Wir treffen uns morgen abend." to "Wir treffen uns morgen Abend.",
            "Gute nacht und guten morgen." to "Gute Nacht und guten Morgen.",
            "Er kommt jedes mal zu spät." to "Er kommt jedes Mal zu spät.",
            "Wurdest du mitgehen?" to "Würdest du mitgehen?",
            "Wir wurden bestimmt kommen." to "Wir würden bestimmt kommen.",
        )

        val VALID = listOf(
            "Ihr seid meine Freunde.",
            "Seit drei Jahren wohne ich hier.",
            "Seid bitte leise!",
            "Seit wann wohnst du hier?",
            "Ihr seid seit Montag hier.",
            "Seid ihr schon fertig?",
            "Seit ihr hier wohnt, ist es ruhiger.",
            "Ihr seid drei Brüder.",
            "Ihr beide seid drei Stunden zu spät.",
            "Ihr zwei seid dem Chef treu.",
            "Seid doch drei Minuten still.",
            "Seid dem Chef gegenüber höflich.",
            "Ich kenne ihn seit langem.",
            "Wo seid ihr gewesen?",
            "Seit gestern regnet es.",
            "Das wisst ihr seit Jahren.",
            "Wie seit Jahren üblich, kommt er zu spät.",
            "Wo seit Jahren Krieg herrscht, ist Frieden selten.",
            "Wie sind die Preise?",
            "Wie haben Sie geschlafen?",
            "Wie gehen die Geschäfte?",
            "Wie können wir helfen?",
            "Wie müssen wir das machen?",
            "Wie sind alle Zimmer?",
            "Wie haben sie das gemacht?",
            "Wie gehen jetzt die Geschäfte?",
            "Wie wollen wir das lösen?",
            "Wie werden Sie zahlen?",
            "Wie sind eure Pläne?",
            "Wie arbeiten sie zusammen?",
            "Wie spielen Kinder zusammen?",
            "Wie lernen Kinder gern?",
            "Wie wohnen die Leute hier?",
            "Ich wurde gefragt.",
            "Er wurde gern eingeladen.",
            "Es wurde nie gesagt.",
            "Ich wurde gestern angerufen.",
            "Wurde ich gefragt?",
            "Als ich ankam, wurde ich begrüßt.",
            "Wir wurden gern gesehen.",
            "Das wurde das erste Mal gezeigt.",
            "Du wurdest geholt.",
            "Ihm wurde das nie gesagt.",
            "Er wurde bestimmt gefunden.",
            "Ich wurde auch gesehen.",
            "Wir wurden gern besucht.",
            "Es wurde das Schreiben unterzeichnet.",
            "Es wurde das Essen serviert.",
            "Sie wurden das Leben nicht los.",
            "Ich weiß, das Buch ist gut.",
            "Das Buch, das er liest, ist gut.",
            "Ich finde, das ist schön.",
            "Es ist schön, das Buch, das du liest.",
            "Das Gut, das er besitzt, ist groß.",
            "Alles, das du willst, bekommst du.",
            "Komm mal her.",
            "Sag mal, wie geht es dir?",
            "Zum ersten Mal war ich dort.",
            "Das ist das erste Mal.",
            "Morgen früh muss ich los.",
            "Ich komme heute, morgen und übermorgen.",
            "Bis morgen!",
            "Guten Morgen!",
            "Gestern Abend war ich müde.",
            "Heute Nacht habe ich schlecht geschlafen.",
            "Ich helfe, ihn zu finden.",
            "Er hilft ihm.",
            "Wir danken ihm.",
            "Das gehört ihm.",
            "Ich kenne ihn.",
            "Ich mag ihn.",
            "Sie liebt ihn.",
            "Wir besuchen ihn.",
            "Ich habe ihm geholfen.",
            "Ich folge ihm.",
            "Wir helfen ihnen.",
            "Morgen Abend gehen wir ins Kino.",
            "Wir essen zu Abend.",
            "Das Wetter ist morgen schön.",
            "Wir wollen morgen gehen.",
        )
    }
}
