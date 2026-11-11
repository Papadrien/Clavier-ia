package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Règles de contexte de l'espagnol ([SpanishConfusions]). Chaque phrase est tapée comme au clavier : à chaque espace ou signe de
 * ponctuation, la correction de contexte regarde la fin du texte. Les phrases justes ne doivent jamais changer.
 */
class SpanishConfusionsTest {

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '\u2019' || c == '-'

    private fun typed(sentence: String): String {
        var text = ""
        for (c in sentence) {
            if (!isWordChar(c) && text.isNotEmpty() && !text.last().isWhitespace()) {
                text = SpanishContextCorrector.correct(text, textMayBeTruncated = false, allowRetro = true) { 0L } ?: text
            }
            text += c
        }
        return SpanishContextCorrector.correct(text, textMayBeTruncated = false, allowRetro = true) { 0L } ?: text
    }

    private fun assertFixed(expected: String, typed: String) = assertEquals(expected, typed(typed))

    private fun assertUntouched(sentence: String) = assertEquals(sentence, typed(sentence))

    @Test
    fun `les interrogatifs prennent l'accent apres le point d'interrogation d'ouverture`() {
        assertFixed("¿cómo estás?", "¿como estas?")
        assertFixed("¿Qué haces?", "¿Que haces?")
        assertFixed("¿Por qué no vienes?", "¿Porque no vienes?")
        assertFixed("¿A dónde vas?", "¿A donde vas?")
        assertFixed("¿Y cómo estás?", "¿Y como estas?")
        assertFixed("¿Cómo? No entendí.", "¿Como? No entendí.")
        assertFixed("¿Qué está pasando?", "¿Que esta pasando?")
    }

    @Test
    fun `l'exclamation ne prend l'accent que devant un mot d'exclamation`() {
        assertFixed("¡Qué bonito!", "¡Que bonito!")
        assertUntouched("¡Que te vaya bien!")
    }

    @Test
    fun `les interrogatifs indirects apres un verbe de savoir`() {
        assertFixed("No sé cómo hacerlo.", "No se como hacerlo.")
        assertFixed("Dime dónde vives.", "Dime donde vives.")
        assertFixed("Yo sé que tienes prisa.", "Yo se que tienes prisa.")
    }

    @Test
    fun `tu el mi si ne changent que si le voisin rend l'autre lecture impossible`() {
        assertFixed("Tú eres mi amigo.", "Tu eres mi amigo.")
        assertFixed("Tu casa es grande.", "Tú casa es grande.")
        assertFixed("Él es mi hermano.", "El es mi hermano.")
        assertFixed("Hablé con él, pero no me escuchó.", "Hablé con el, pero no me escuchó.")
        assertFixed("A mí me gusta.", "A mi me gusta.")
        assertFixed("Sí, claro.", "Si, claro.")
        assertUntouched("El vino tinto es mi favorito.")
        assertUntouched("Mi casa está cerca.")
        assertUntouched("Si no vienes, me voy sin ti.")
        assertUntouched("Esa casa es más grande que la de Pedro.")
        assertUntouched("El habla de la región es muy particular.")
    }

    @Test
    fun `a ha de dos formes de haber et echo hecho`() {
        assertFixed("Ella ha venido.", "Ella a venido.")
        assertFixed("Voy a comprar pan.", "Voy ha comprar pan.")
        assertFixed("He hecho la tarea.", "He echo la tarea.")
        assertFixed("Te echo de menos.", "Te hecho de menos.")
        assertUntouched("Te echo de menos.")
        assertUntouched("De hecho, tienes razón.")
    }

    @Test
    fun `les mots soudes sont separes`() {
        assertFixed("A veces pienso en ti.", "Aveces pienso en ti.")
        assertFixed("Por favor ayúdame.", "Porfavor ayúdame.")
        assertFixed("A ver si me llamas.", "Haber si me llamas.")
        assertUntouched("La médula ósea produce células.")
    }

    @Test
    fun `une phrase juste ne change pas`() {
        for (sentence in listOf(
            "Hola, ¿cómo estás?", "¿Dónde está mi teléfono?", "No sé si vendrá.", "Quiero que me dé una respuesta.", "Aún no he terminado.",
            "Aun así, seguiré intentándolo.", "Yo se lo dije ayer.", "No se puede entrar sin permiso.", "Estas manzanas están maduras.",
        )) {
            assertUntouched(sentence)
        }
    }
}
