package fr.junade.taipo.dictionary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Table de confusions (phase 4) : homophones corrigés seulement quand le contexte les confirme, et phrases justes
 * qu'aucune règle ne doit toucher. Le texte est tapé caractère par caractère, comme au clavier : la correction se
 * déclenche quand un mot se termine, avec correction à rebours du mot précédent quand il faut connaître le suivant.
 */
class FrenchConfusionsTest {

    /** Fréquences minimales pour reconnaître le singulier d'un nom pluriel (« clés » -> « clé »). « avi » est rare : « avis » n'en est pas le pluriel. */
    private val frequencies = mapOf(
        "clé" to 5_000L, "clés" to 4_000L, "maison" to 100_000L, "maisons" to 50_000L, "avi" to 10L, "avis" to 30_000L,
        "enfant" to 90_000L, "enfants" to 80_000L, "ami" to 90_000L, "amis" to 80_000L,
    )

    private fun frequencyOf(word: String): Long = frequencies[word] ?: 0L

    private fun isWordChar(c: Char) = c.isLetter() || c == '\'' || c == '’' || c == '-'

    private fun type(sentence: String, allowRetro: Boolean = true, truncated: Boolean = false): String {
        var text = ""
        for (c in sentence) {
            if (!isWordChar(c) && text.isNotEmpty() && !text.last().isWhitespace()) {
                text = FrenchConfusions.fix(text, truncated, allowRetro, ::frequencyOf)
            }
            text += c
        }
        return FrenchConfusions.fix(text, truncated, allowRetro, ::frequencyOf)
    }

    private val corrections = listOf(
        "Il ce lève tôt." to "Il se lève tôt.",
        "Elle ce rend à Paris." to "Elle se rend à Paris.",
        "Ils ce sont vus hier." to "Ils se sont vus hier.",
        "On ce voit demain." to "On se voit demain.",
        "Il ne ce passe rien." to "Il ne se passe rien.",
        "Se livre est intéressant." to "Ce livre est intéressant.",
        "Dans se film, il y a un chien." to "Dans ce film, il y a un chien.",
        "Ils on fini leur travail." to "Ils ont fini leur travail.",
        "Elles on faim." to "Elles ont faim.",
        "Les enfants on joué dehors." to "Les enfants ont joué dehors.",
        "Les voisins on eu peur." to "Les voisins ont eu peur.",
        "Ont y va demain." to "On y va demain.",
        "Ils son partis hier." to "Ils sont partis hier.",
        "Elles son contentes." to "Elles sont contentes.",
        "Ils ne son pas là." to "Ils ne sont pas là.",
        "Il a perdu sont portable." to "Il a perdu son portable.",
        "Sont frère est grand." to "Son frère est grand.",
        "Il a vu sont père." to "Il a vu son père.",
        "Ces un bon ami." to "C'est un bon ami.",
        "Ces à toi de jouer." to "C'est à toi de jouer.",
        "Il c'est lavé les mains." to "Il s'est lavé les mains.",
        "Elle c'est trompée." to "Elle s'est trompée.",
        "On c'est lavé les mains." to "On s'est lavé les mains.",
        "S'est un bon film." to "C'est un bon film.",
        "Ils ont perdu leur clés." to "Ils ont perdu leurs clés.",
        "Ils ont vendu leur maisons." to "Ils ont vendu leurs maisons.",
        "Je leurs ai parlé hier." to "Je leur ai parlé hier.",
        "Ils leurs ont dit non." to "Ils leur ont dit non.",
        "Sa va bien merci." to "Ça va bien merci.",
        "Sa fait longtemps." to "Ça fait longtemps.",
        "Sa me plaît beaucoup." to "Ça me plaît beaucoup.",
        "Ça mère est gentille." to "Sa mère est gentille.",
        "Il pense à ça famille." to "Il pense à sa famille.",
        "Il et parti hier." to "Il est parti hier.",
        "Elle et fatiguée." to "Elle est fatiguée.",
        "Ce livre et intéressant." to "Ce livre est intéressant.",
        "La maison et fermée." to "La maison est fermée.",
        "Il fait beau est chaud." to "Il fait beau et chaud.",
        "Il peu venir demain." to "Il peut venir demain.",
        "On peu y aller." to "On peut y aller.",
        "Il ne peu pas venir." to "Il ne peut pas venir.",
        "Il y a peut de monde." to "Il y a peu de monde.",
        "Il mange très peut." to "Il mange très peu.",
        "Il a un peut de temps." to "Il a un peu de temps.",
        "Reste la, je reviens." to "Reste là, je reviens.",
        "Il n'est pas la aujourd'hui." to "Il n'est pas là aujourd'hui.",
        "D'ou viens-tu ?" to "D'où viens-tu ?",
        "Il a mangé a midi." to "Il a mangé à midi.",
        "C'est facile a faire." to "C'est facile à faire.",
        "J'ai quelque chose a te dire." to "J'ai quelque chose à te dire.",
        "Il n'a rien a te dire." to "Il n'a rien à te dire.",
        "Il y a du travail a faire." to "Il y a du travail à faire.",
        "Il l'a dit a son frère." to "Il l'a dit à son frère.",
    )

    private val validSentences = listOf(
        "Ils ont donné leur avis sur le projet.",
        "Ils ont perdu leurs clés.",
        "Je leur dis la vérité.",
        "Je ne leur dis rien.",
        "Il leur donne des conseils.",
        "Elle leur raconte des histoires.",
        "On leur prépare des surprises.",
        "Pour leur bien, on part.",
        "Ils ont vendu leur maison.",
        "Ils ont pris leur temps.",
        "Ils aiment leurs enfants.",
        "Nous leur offrons des fleurs.",
        "Il faut leur donner du temps.",
        "Dans leur voiture, il y avait trois sacs.",
        "Ceux qui leur ressemblent sont là.",
        "Les gens qui leur parlent sourient.",
        "On a dit que leur travail est bon.",
        "Quand il se lève, il se lave.",
        "Il ne se souvient de rien.",
        "Ils se sont vus hier.",
        "Elle se demande pourquoi.",
        "On se voit demain.",
        "Je me demande ce que tu fais.",
        "Ce livre est intéressant.",
        "Ce soir, on sort.",
        "Il ce soir-là, rien.",
        "Il faut se laver les mains.",
        "Pour se rendre à Paris, il prend le train.",
        "Il a décidé de se reposer.",
        "Il a peur de se tromper.",
        "Ils ont fini leur travail.",
        "Elles ont faim.",
        "Les enfants ont joué dehors.",
        "On y va demain.",
        "On a mangé tôt.",
        "Alors on y va, on a le temps.",
        "Nous on a décidé de rester.",
        "Vous on vous attend.",
        "Chez nous on mange tard.",
        "Dans les rues on voit des gens.",
        "Tous les jours on lit un peu.",
        "Les amis qui ont fait le voyage sont rentrés.",
        "Ils sont partis hier.",
        "Elles sont contentes.",
        "Ses parents sont gentils.",
        "Ce sont père et fils.",
        "Les enfants sont à l'école.",
        "Il a perdu son portable.",
        "Son frère est grand.",
        "Ils sont amis depuis longtemps.",
        "Ceux qui sont là partent.",
        "Marie et Paul sont père et mère de trois enfants.",
        "Ses frères sont venus.",
        "C'est un bon film.",
        "Ces gens sont gentils.",
        "Ces livres sont lourds.",
        "Ces mots sont très beaux.",
        "Ces très beaux jours sont finis.",
        "Ces pas résonnent dans la nuit.",
        "Ces plus beaux jours sont passés.",
        "Il s'est lavé les mains.",
        "Elle s'est trompée.",
        "Ils se sont lavés.",
        "Ça va bien merci.",
        "Sa mère est gentille.",
        "Sa voiture est neuve.",
        "Sa marche est lente.",
        "Il a pris sa place.",
        "Elle a perdu sa maison.",
        "Il est parti hier.",
        "Il est très fatigué et il dort.",
        "Il est grand et beau.",
        "Le grand et beau château est vieux.",
        "Le gros et beau chat dort.",
        "Le simple et facile est parfois difficile.",
        "Ce livre est intéressant et drôle.",
        "Il et elle sont amis.",
        "Paul et Marie sont amis.",
        "Il fait beau et chaud.",
        "Ce qui est beau est cher.",
        "Le plus beau est cher.",
        "Marie et Paul viennent demain.",
        "Elle est partie et il est resté.",
        "Il peut venir demain.",
        "On peut y aller.",
        "Il y a peu de monde.",
        "Il mange très peu.",
        "Il a un peu de temps.",
        "Elle ne peut pas venir.",
        "Si on peut, on y va.",
        "Ça peut marcher.",
        "Il mange peu à peu.",
        "Je suis là pour toi.",
        "Il est là depuis midi.",
        "Reste là, je reviens.",
        "Elle est là, derrière.",
        "Je la vois souvent.",
        "Donne-la moi.",
        "Je la, enfin, connais.",
        "Il la lui a donnée.",
        "Il va à la plage.",
        "Il a mangé à midi.",
        "Le temps a passé vite.",
        "Le travail a fini tard.",
        "Le prêt a expiré.",
        "Celui qui a dit a raison.",
        "Celui qui a parlé a tort.",
        "Elle a dit à son frère de venir.",
        "Il a dit a de la valeur.",
        "Ce qui est facile a été fait.",
        "Rien a voir avec ça.",
        "Il n'y a rien à te dire.",
        "Il a quelque chose à te dire.",
        "Il a faim, a soif et a peur.",
        "Le chat a faim.",
        "D'où viens-tu ?",
        "Il a beaucoup de travail à faire.",
        "Il y a du travail à faire.",
        "Le plus important a été fait.",
        "Où est-il passé ?",
    )

    @Test
    fun `les confusions courantes sont corrigees`() {
        corrections.forEach { (typed, expected) -> assertEquals(expected, type(typed), typed) }
    }

    @Test
    fun `les phrases justes ne sont jamais touchees`() {
        validSentences.forEach { sentence -> assertEquals(sentence, type(sentence), sentence) }
    }

    @Test
    fun `chaque regle a un nom unique et un contexte`() {
        val names = FrenchConfusions.RULES.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(
            FrenchConfusions.RULES.all {
                it.always || it.previous != null || it.previous2 != null || it.next != null || it.sentenceStart || it.punctAfter
            },
        )
    }

    @Test
    fun `une regle a mot suivant attend la correction a rebours`() {
        assertEquals("Il ce lève tôt.", type("Il ce lève tôt.", allowRetro = false))
        assertEquals("Il se lève tôt.", type("Il ce lève tôt.", allowRetro = true))
    }

    @Test
    fun `une regle sans mot suivant s'applique tout de suite`() {
        assertEquals("Ils ont", type("Ils on", allowRetro = false))
    }

    @Test
    fun `la majuscule du mot tape est conservee`() {
        assertEquals("Son frère est grand.", type("Sont frère est grand."))
        assertEquals("Ça va bien merci.", type("Sa va bien merci."))
        assertEquals("On y va demain.", type("Ont y va demain."))
    }

    @Test
    fun `un mot en capitales n'est pas touche`() {
        assertEquals("ILS ON FINI", type("ILS ON FINI"))
    }

    @Test
    fun `une ponctuation entre les deux mots coupe le contexte`() {
        assertEquals("Ils, on a fini.", type("Ils, on a fini."))
        assertEquals("Il, ce livre est bon.", type("Il, ce livre est bon."))
    }

    @Test
    fun `une fenetre qui peut commencer au milieu d'une phrase ne donne pas un debut de phrase`() {
        assertEquals("Se livre est intéressant", type("Se livre est intéressant", truncated = true))
        assertEquals("Ce livre est intéressant", type("Se livre est intéressant", truncated = false))
    }

    @Test
    fun `la comme la suivi d'une ponctuation devient la apres reste`() {
        assertEquals("Reste là, je reviens", type("Reste la, je reviens"))
        assertEquals("Je la, vois", type("Je la, vois"))
    }
}
