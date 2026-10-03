package fr.junade.taipo.suggestion

import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicFileWriteTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `cree le fichier quand il n'existe pas`() {
        val target = File(dir, "model.enc")
        writeAtomically(target, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }

    @Test
    fun `remplace entierement un fichier existant plus long`() {
        val target = File(dir, "model.enc")
        target.writeBytes(ByteArray(100) { 7 })
        writeAtomically(target, byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), target.readBytes())
    }

    @Test
    fun `ne laisse aucun fichier temporaire`() {
        val target = File(dir, "model.enc")
        writeAtomically(target, byteArrayOf(1))
        writeAtomically(target, byteArrayOf(2))
        assertFalse(File(dir, "model.enc.tmp").exists())
        assertEquals(listOf("model.enc"), dir.list()!!.toList())
    }

    @Test
    fun `le fichier est ecrit quand la fonction rend la main`() {
        val target = File(dir, "model.enc")
        val payload = ByteArray(50_000) { (it % 251).toByte() }
        writeAtomically(target, payload)
        assertTrue(target.exists())
        assertArrayEquals(payload, target.readBytes())
    }
}
