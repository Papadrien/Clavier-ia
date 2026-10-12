package fr.junade.taipo.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Story 10.3 : le fichier de sauvegarde se relit à l'identique, et les fichiers étrangers ou trop récents sont refusés. */
class BackupCodecTest {

    private val full = BackupData(
        exportedAtMillis = 1_760_000_000_000,
        dictionary = listOf("Taipo", "iPhone", "Ritsanko"),
        pins = listOf(
            BackupPin("Mon adresse\n12 rue \"Test\" 🎉", "Adresse", 1_700_000_000_000),
            BackupPin("Sans étiquette", null, 1_700_000_000_001),
        ),
        keyboard = BackupKeyboardSettings(
            numberRow = true,
            hapticIntensity = "light",
            keyboardHeight = "large",
            recentEmojis = listOf("😀", "👍"),
            habitLearning = false,
        ),
        ai = BackupAiSettings(
            activeModelId = "equilibre",
            prompts = mapOf(BackupAiSettings.PROMPT_TRANSCRIPTION to "Ligne 1\nLigne 2 « ok »"),
        ),
    )

    @Test
    fun `une sauvegarde complete se relit a l'identique`() {
        assertEquals(full, BackupCodec.decode(BackupCodec.encode(full)))
    }

    @Test
    fun `une sauvegarde vide se relit`() {
        val empty = BackupData(0L, emptyList(), emptyList(), BackupKeyboardSettings(), BackupAiSettings())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }

    @Test
    fun `les reglages absents du fichier restent absents`() {
        val partial = BackupCodec.decode("""{"app":"taipo","formatVersion":1,"dictionary":["a"]}""")
        assertEquals(listOf("a"), partial.dictionary)
        assertNull(partial.keyboard.numberRow)
        assertNull(partial.keyboard.recentEmojis)
        assertNull(partial.ai.activeModelId)
        assertEquals(emptyMap<String, String>(), partial.ai.prompts)
    }

    @Test
    fun `les cles inconnues et les elements mal formes sont ignores`() {
        val data = BackupCodec.decode(
            """{"app":"taipo","formatVersion":1,"futur":42,"dictionary":["ok",3,null,"bien"],
               "pinnedClips":[{"text":"a","label":"L","pinnedAt":5},{"label":"sans texte"},"x",{"text":"b","label":7}],
               "settings":{"keyboard":{"numberRow":"oui","habitLearning":true}}}""",
        )
        assertEquals(listOf("ok", "bien"), data.dictionary)
        assertEquals(listOf(BackupPin("a", "L", 5), BackupPin("b", null, 0)), data.pins)
        assertNull(data.keyboard.numberRow)
        assertEquals(true, data.keyboard.habitLearning)
    }

    @Test
    fun `un fichier qui n'est pas une sauvegarde Taipo est refuse`() {
        listOf("", "pas du json", "[]", """{"app":"autre","formatVersion":1}""", """{"app":"taipo"}""").forEach { text ->
            val error = assertThrows(BackupCodec.InvalidBackupException::class.java) { BackupCodec.decode(text) }
            assertEquals(BackupCodec.Reason.NOT_A_BACKUP, error.reason, text)
        }
    }

    @Test
    fun `une sauvegarde d'une version plus recente est refusee`() {
        val error = assertThrows(BackupCodec.InvalidBackupException::class.java) {
            BackupCodec.decode("""{"app":"taipo","formatVersion":${BackupCodec.FORMAT_VERSION + 1}}""")
        }
        assertEquals(BackupCodec.Reason.TOO_NEW, error.reason)
    }
}
