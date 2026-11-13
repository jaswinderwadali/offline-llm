package ai.impact

import ai.impact.models.TextModelCatalog
import ai.impact.models.isGguf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TextModelsTest {

    @Test
    fun recognisesGgufFilesByTheirSignature() {
        val gguf = File.createTempFile("model", ".gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(16)) }
        val html = File.createTempFile("error", ".gguf").apply { writeText("<!DOCTYPE html><title>404</title>") }
        val empty = File.createTempFile("empty", ".gguf")
        try {
            assertTrue(isGguf(gguf))
            assertFalse(isGguf(html))
            assertFalse(isGguf(empty))
            assertFalse(isGguf(File("does-not-exist.gguf")))
        } finally {
            listOf(gguf, html, empty).forEach { it.delete() }
        }
    }

    @Test
    fun catalogEntriesAreUniqueGgufDownloads() {
        val models = TextModelCatalog.models
        assertEquals(models.size, models.map { it.id }.toSet().size)
        models.forEach {
            assertTrue(it.fileName, it.fileName.endsWith(".gguf"))
            assertTrue(it.url.startsWith("https://huggingface.co/"))
            assertTrue(it.sizeBytes > 500_000_000)
        }
        assertTrue(models.any { it.recommended })
    }
}
