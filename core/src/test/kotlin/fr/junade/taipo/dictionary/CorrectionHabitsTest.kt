package fr.junade.taipo.dictionary

import fr.junade.taipo.KeyboardLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrectionHabitsTest {

    private val fr = KeyboardLanguage.FR
    private val en = KeyboardLanguage.EN

    @Test
    fun `un objet neuf est vide et tous les comptes valent zero`() {
        val habits = CorrectionHabits()
        assertTrue(habits.isEmpty())
        assertEquals(0, habits.size())
        assertEquals(0, habits.undoCount(fr, "jai", "j'ai"))
        assertEquals(0, habits.acceptedCount(fr, "jai", "j'ai"))
        assertEquals(0, habits.usageCount(fr, "bonjour"))
    }

    @Test
    fun `les trois compteurs sont independants`() {
        val habits = CorrectionHabits()
        assertTrue(habits.recordUndo(fr, "teh", "the"))
        assertTrue(habits.recordUndo(fr, "teh", "the"))
        assertTrue(habits.recordAccepted(fr, "teh", "the"))
        assertTrue(habits.recordUsage(fr, "the"))
        assertEquals(2, habits.undoCount(fr, "teh", "the"))
        assertEquals(1, habits.acceptedCount(fr, "teh", "the"))
        assertEquals(1, habits.usageCount(fr, "the"))
        assertEquals(3, habits.size())
    }

    @Test
    fun `les comptes sont separes par langue`() {
        val habits = CorrectionHabits()
        habits.recordUndo(fr, "pain", "pair")
        habits.recordUsage(fr, "pain")
        assertEquals(0, habits.undoCount(en, "pain", "pair"))
        assertEquals(0, habits.usageCount(en, "pain"))
    }

    @Test
    fun `la casse est ignoree`() {
        val habits = CorrectionHabits()
        habits.recordUndo(fr, "Jai", "J'ai")
        habits.recordUsage(fr, "Bonjour")
        assertEquals(1, habits.undoCount(fr, "jai", "j'ai"))
        assertEquals(1, habits.usageCount(fr, "BONJOUR"))
    }

    @Test
    fun `une paire n'est pas la meme dans l'autre sens`() {
        val habits = CorrectionHabits()
        habits.recordUndo(fr, "ou", "où")
        assertEquals(1, habits.undoCount(fr, "ou", "où"))
        assertEquals(0, habits.undoCount(fr, "où", "ou"))
    }

    @Test
    fun `un changement de casse seul n'est pas une correction`() {
        val habits = CorrectionHabits()
        assertFalse(habits.recordUndo(fr, "paris", "Paris"))
        assertFalse(habits.recordAccepted(fr, "paris", "PARIS"))
        assertTrue(habits.isEmpty())
    }

    @Test
    fun `les mots avec chiffre, trop longs, vides ou avec espace ne sont jamais retenus`() {
        val habits = CorrectionHabits()
        assertFalse(habits.recordUsage(fr, "abc123"))
        assertFalse(habits.recordUsage(fr, "a".repeat(31)))
        assertFalse(habits.recordUsage(fr, "  "))
        assertFalse(habits.recordUsage(fr, "deux mots"))
        assertFalse(habits.recordUsage(fr, "tab\tulation"))
        assertFalse(habits.recordUndo(fr, "code1", "code"))
        assertFalse(habits.recordAccepted(fr, "mot", "mot suivant"))
        assertTrue(habits.isEmpty())
        assertEquals("", habits.serialize())
    }

    @Test
    fun `un compteur est plafonne`() {
        val habits = CorrectionHabits()
        repeat(CorrectionHabits.MAX_COUNT + 50) { habits.recordUsage(fr, "bonjour") }
        assertEquals(CorrectionHabits.MAX_COUNT, habits.usageCount(fr, "bonjour"))
        assertEquals(1, habits.size())
    }

    @Test
    fun `le nombre d'entrees est borne et les entrees rares sont elaguees d'abord`() {
        val habits = CorrectionHabits(maxEntries = 10)
        repeat(3) { habits.recordUsage(fr, "frequent") }
        for (index in 0 until 30) habits.recordUsage(fr, "rare" + ('a' + index % 26) + ('a' + index / 26))
        assertTrue(habits.size() <= 10, "taille : ${habits.size()}")
        assertEquals(3, habits.usageCount(fr, "frequent"))
    }

    @Test
    fun `clear efface tout`() {
        val habits = CorrectionHabits()
        habits.recordUndo(fr, "teh", "the")
        habits.recordUsage(en, "the")
        habits.clear()
        assertTrue(habits.isEmpty())
        assertEquals(0, habits.undoCount(fr, "teh", "the"))
        assertEquals("", habits.serialize())
    }

    @Test
    fun `la sauvegarde puis la relecture redonnent les memes comptes`() {
        val habits = CorrectionHabits()
        repeat(2) { habits.recordUndo(fr, "jai", "j'ai") }
        habits.recordAccepted(fr, "ou", "où")
        repeat(5) { habits.recordUsage(en, "hello") }

        val restored = CorrectionHabits.parse(habits.serialize())

        assertEquals(2, restored.undoCount(fr, "jai", "j'ai"))
        assertEquals(1, restored.acceptedCount(fr, "ou", "où"))
        assertEquals(5, restored.usageCount(en, "hello"))
        assertEquals(habits.size(), restored.size())
    }

    @Test
    fun `la sauvegarde ne contient que des mots isoles et des paires, jamais de phrase`() {
        val habits = CorrectionHabits()
        habits.recordUndo(fr, "jai", "j'ai")
        habits.recordUsage(fr, "bonjour")
        val lines = habits.serialize().lines().filter { it.isNotEmpty() }
        assertEquals(setOf("U\tFR\tjai\tj'ai\t1", "W\tFR\tbonjour\t1"), lines.toSet())
    }

    @Test
    fun `les lignes illisibles ou invalides sont ignorees a la relecture`() {
        val text = listOf(
            "W\tFR\tbonjour\t3", // valide
            "W\tXX\tbonjour\t3", // langue inconnue
            "W\tFR\tbonjour\tabc", // compte illisible
            "W\tFR\tbonjour\t0", // compte nul
            "W\tFR\tabc123\t2", // mot avec chiffre
            "W\tFR\tBonjour\t2", // pas en minuscules
            "U\tFR\tjai\t2", // paire incomplete
            "U\tFR\tmot\tmot\t2", // paire identique
            "Z\tFR\tmot\t2", // type inconnu
            "n'importe quoi",
            "",
        ).joinToString("\n")

        val habits = CorrectionHabits.parse(text)

        assertEquals(1, habits.size())
        assertEquals(3, habits.usageCount(fr, "bonjour"))
    }

    @Test
    fun `mergeFrom additionne les comptes sans modifier l'autre objet`() {
        val memory = CorrectionHabits()
        repeat(2) { memory.recordUsage(fr, "bonjour") }
        val disk = CorrectionHabits()
        repeat(3) { disk.recordUsage(fr, "bonjour") }
        disk.recordUndo(fr, "jai", "j'ai")

        memory.mergeFrom(disk)

        assertEquals(5, memory.usageCount(fr, "bonjour"))
        assertEquals(1, memory.undoCount(fr, "jai", "j'ai"))
        assertEquals(3, disk.usageCount(fr, "bonjour"))
    }

    @Test
    fun `mergeFrom avec soi-meme ne change rien`() {
        val habits = CorrectionHabits()
        habits.recordUsage(fr, "bonjour")
        habits.mergeFrom(habits)
        assertEquals(1, habits.usageCount(fr, "bonjour"))
    }

    @Test
    fun `une correction annulee deux fois est refusee`() {
        val habits = CorrectionHabits()
        assertFalse(habits.isRefused(fr, "jai", "j'ai"))
        habits.recordUndo(fr, "jai", "j'ai")
        assertFalse(habits.isRefused(fr, "jai", "j'ai"), "une seule annulation ne suffit pas")
        habits.recordUndo(fr, "Jai", "J'ai")
        assertTrue(habits.isRefused(fr, "jai", "j'ai"))
    }

    @Test
    fun `le refus ne vaut que pour la paire et la langue annulees`() {
        val habits = CorrectionHabits()
        repeat(CorrectionHabits.REFUSAL_THRESHOLD) { habits.recordUndo(fr, "jai", "j'ai") }
        assertFalse(habits.isRefused(fr, "jai", "jais"), "une autre correction du meme mot reste possible")
        assertFalse(habits.isRefused(fr, "pain", "j'ai"))
        assertFalse(habits.isRefused(en, "jai", "j'ai"))
    }

    @Test
    fun `effacer les habitudes leve le refus`() {
        val habits = CorrectionHabits()
        repeat(CorrectionHabits.REFUSAL_THRESHOLD) { habits.recordUndo(fr, "jai", "j'ai") }
        habits.clear()
        assertFalse(habits.isRefused(fr, "jai", "j'ai"))
    }

    @Test
    fun `le refus survit a la sauvegarde`() {
        val habits = CorrectionHabits()
        repeat(CorrectionHabits.REFUSAL_THRESHOLD) { habits.recordUndo(fr, "jai", "j'ai") }
        assertTrue(CorrectionHabits.parse(habits.serialize()).isRefused(fr, "jai", "j'ai"))
    }

    // ---- Adresses e-mail ----

    @Test
    fun `une adresse tapee est retenue en minuscules et comptee`() {
        val habits = CorrectionHabits()
        assertTrue(habits.recordEmail("Jean.Dupont@Gmail.com"))
        assertTrue(habits.recordEmail("jean.dupont@gmail.com"))
        assertEquals(2, habits.emailUsage("JEAN.DUPONT@gmail.com"))
        assertEquals(1, habits.emailsSize())
    }

    @Test
    fun `ce qui n'est pas une adresse n'est pas retenu`() {
        val habits = CorrectionHabits()
        assertFalse(habits.recordEmail("jean"))
        assertFalse(habits.recordEmail("jean@gmail"))
        assertEquals(0, habits.emailsSize())
        assertEquals(0, habits.emailUsage("jean@gmail"))
    }

    @Test
    fun `les adresses ne comptent ni dans size ni dans isEmpty`() {
        val habits = CorrectionHabits()
        habits.recordEmail("jean@x.fr")
        assertTrue(habits.isEmpty(), "les adresses ne jouent pas sur la correction")
        assertEquals(0, habits.size())
    }

    @Test
    fun `les adresses qui commencent comme le texte tape sont proposees, les plus tapees d'abord`() {
        val habits = CorrectionHabits()
        habits.recordEmail("jean.dupont@gmail.com")
        habits.recordEmail("jeanne@x.fr")
        habits.recordEmail("jeanne@x.fr")
        habits.recordEmail("marie@x.fr")
        assertEquals(listOf("jeanne@x.fr", "jean.dupont@gmail.com"), habits.suggestEmails("jea", 3))
        assertEquals(listOf("jean.dupont@gmail.com"), habits.suggestEmails("JEAN.", 3))
        assertEquals(listOf("jeanne@x.fr"), habits.suggestEmails("jeanne@", 3))
        assertEquals(listOf("marie@x.fr"), habits.suggestEmails("m", 3))
    }

    @Test
    fun `a egalite de compte la plus recente passe devant`() {
        val habits = CorrectionHabits()
        habits.recordEmail("tom@x.fr")
        habits.recordEmail("tim@x.fr")
        assertEquals(listOf("tim@x.fr", "tom@x.fr"), habits.suggestEmails("t", 3))
        habits.recordEmail("tom@x.fr")
        assertEquals(listOf("tom@x.fr", "tim@x.fr"), habits.suggestEmails("t", 3), "plus tapée : devant")
    }

    @Test
    fun `l'adresse complete elle-meme n'est pas proposee et la limite est respectee`() {
        val habits = CorrectionHabits()
        habits.recordEmail("a1@x.fr")
        habits.recordEmail("a2@x.fr")
        habits.recordEmail("a3@x.fr")
        assertTrue(habits.suggestEmails("a1@x.fr", 3).isEmpty())
        assertEquals(2, habits.suggestEmails("a", 2).size)
        assertTrue(habits.suggestEmails("", 3).isEmpty(), "un début vide ne propose rien")
        assertTrue(habits.suggestEmails("a", 0).isEmpty())
        assertTrue(habits.suggestEmails("zzz", 3).isEmpty())
    }

    @Test
    fun `les adresses survivent a la sauvegarde avec leur compte et leur ordre`() {
        val habits = CorrectionHabits()
        habits.recordUsage(fr, "bonjour")
        habits.recordEmail("ancien@x.fr")
        habits.recordEmail("ancien@x.fr")
        habits.recordEmail("recent@x.fr")
        val copy = CorrectionHabits.parse(habits.serialize())
        assertEquals(2, copy.emailUsage("ancien@x.fr"))
        assertEquals(1, copy.emailUsage("recent@x.fr"))
        assertEquals(1, copy.usageCount(fr, "bonjour"))
        assertEquals(habits.serialize(), copy.serialize())
    }

    @Test
    fun `les lignes d'adresse illisibles sont ignorees`() {
        val text = listOf(
            "E\tbon@x.fr\t3",
            "E\tMAJUSCULES@x.fr\t3",
            "E\tpas-une-adresse\t3",
            "E\tzero@x.fr\t0",
            "E\tmanque@x.fr",
            "E\ttrop@x.fr\t1\textra",
            "E\tnombre@x.fr\tabc",
        ).joinToString("\n")
        val habits = CorrectionHabits.parse(text)
        assertEquals(1, habits.emailsSize())
        assertEquals(3, habits.emailUsage("bon@x.fr"))
    }

    @Test
    fun `les adresses sont bornees et la moins utilisee part en premier`() {
        val habits = CorrectionHabits()
        habits.recordEmail("favori@x.fr")
        habits.recordEmail("favori@x.fr")
        repeat(CorrectionHabits.MAX_EMAILS + 20) { habits.recordEmail("adresse$it@x.fr") }
        assertEquals(CorrectionHabits.MAX_EMAILS, habits.emailsSize())
        assertEquals(2, habits.emailUsage("favori@x.fr"), "une adresse souvent tapée reste")
        assertEquals(1, habits.emailUsage("adresse${CorrectionHabits.MAX_EMAILS + 19}@x.fr"), "la dernière tapée entre toujours")
        assertEquals(0, habits.emailUsage("adresse0@x.fr"), "la plus ancienne des moins utilisées est partie")
    }

    @Test
    fun `fusionner ajoute les comptes et met les adresses du disque avant les nouvelles`() {
        val disk = CorrectionHabits()
        disk.recordEmail("disque@x.fr")
        disk.recordEmail("commune@x.fr")
        val live = CorrectionHabits()
        live.recordEmail("commune@x.fr")
        live.recordEmail("neuve@x.fr")
        live.mergeFrom(disk)
        assertEquals(1, live.emailUsage("disque@x.fr"))
        assertEquals(2, live.emailUsage("commune@x.fr"))
        assertEquals(1, live.emailUsage("neuve@x.fr"))
        // Ordre : disque (ancienne), commune, neuve (la plus récente en dernier).
        assertEquals(
            listOf("E\tdisque@x.fr\t1", "E\tcommune@x.fr\t2", "E\tneuve@x.fr\t1"),
            live.serialize().lines().filter { it.startsWith("E\t") },
        )
    }

    @Test
    fun `effacer les habitudes efface aussi les adresses`() {
        val habits = CorrectionHabits()
        habits.recordEmail("jean@x.fr")
        habits.clear()
        assertEquals(0, habits.emailsSize())
        assertTrue(habits.suggestEmails("j", 3).isEmpty())
        assertEquals("", habits.serialize())
    }
}
