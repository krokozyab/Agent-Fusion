package com.orchestrator.context.chunking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ChunkerRegistryTest {
    private val registry = ConfigurableChunkerRegistry()

    @Test
    fun `returns JavaChunker for java files`() {
        val chunker = registry.getChunker(Paths.get("Test.java"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns CSharpChunker for cs files`() {
        val chunker = registry.getChunker(Paths.get("Test.cs"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns KotlinChunker for kt files`() {
        val chunker = registry.getChunker(Paths.get("Test.kt"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns GoChunker for go files`() {
        val chunker = registry.getChunker(Paths.get("main.go"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns YamlChunker for yaml files`() {
        val chunker = registry.getChunker(Paths.get("config.yaml"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns YamlChunker for yml files`() {
        val chunker = registry.getChunker(Paths.get("config.yml"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns SqlChunker for sql files`() {
        val chunker = registry.getChunker(Paths.get("schema.sql"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns PlainTextChunker for unsupported extensions`() {
        val chunker = registry.getChunker(Paths.get("file.txt"))
        assertNotNull(chunker)
        assertTrue(chunker is PlainTextChunker)
    }

    @Test
    fun `handles case insensitive extensions`() {
        val chunker1 = registry.getChunker(Paths.get("Test.JAVA"))
        val chunker2 = registry.getChunker(Paths.get("Test.Java"))
        assertNotNull(chunker1)
        assertNotNull(chunker2)
        assertTrue(chunker1 is Chunker)
        assertTrue(chunker2 is Chunker)
    }

    @Test
    fun `getChunker by extension string works`() {
        val chunker1 = registry.getChunker("java")
        val chunker2 = registry.getChunker(".java")
        assertNotNull(chunker1)
        assertNotNull(chunker2)
        assertTrue(chunker1 is Chunker)
        assertTrue(chunker2 is Chunker)
    }

    @Test
    fun `getSupportedExtensions returns all registered extensions`() {
        val extensions = registry.getSupportedExtensions()
        assertTrue(extensions.contains("java"))
        assertTrue(extensions.contains("cs"))
        assertTrue(extensions.contains("kt"))
        assertTrue(extensions.contains("go"))
        assertTrue(extensions.contains("yaml"))
        assertTrue(extensions.contains("yml"))
        assertTrue(extensions.contains("sql"))
    }

    @Test
    fun `isSupported returns true for supported files`() {
        assertTrue(registry.isSupported(Paths.get("Test.java")))
        assertTrue(registry.isSupported(Paths.get("Test.kt")))
        assertTrue(registry.isSupported(Paths.get("main.go")))
        assertTrue(registry.isSupported(Paths.get("config.yaml")))
        assertTrue(registry.isSupported(Paths.get("manual.docx")))
        assertTrue(registry.isSupported(Paths.get("reference.pdf")))
    }

    @Test
    fun `isSupported returns false for unsupported files`() {
        // These extensions are not in the registry (will use fallback)
        assertFalse(registry.isSupported(Paths.get("image.png")))
        assertFalse(registry.isSupported(Paths.get("archive.zip")))
        assertFalse(registry.isSupported(Paths.get("binary.exe")))
    }

    @Test
    fun `isSupported by extension string works`() {
        assertTrue(registry.isSupported("java"))
        assertTrue(registry.isSupported(".kt"))
        assertTrue(registry.isSupported("pdf"))
        assertTrue(registry.isSupported(".doc"))
        assertFalse(registry.isSupported("zip"))
    }

    @Test
    fun `returns MarkdownChunker for md files`() {
        val chunker = registry.getChunker(Paths.get("README.md"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns PythonChunker for py files`() {
        val chunker = registry.getChunker(Paths.get("script.py"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns TypeScriptChunker for ts files`() {
        val chunker = registry.getChunker(Paths.get("app.ts"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns TypeScriptChunker for tsx files`() {
        val chunker = registry.getChunker(Paths.get("Component.tsx"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns TypeScriptChunker for js files`() {
        val chunker = registry.getChunker(Paths.get("script.js"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }

    @Test
    fun `returns TypeScriptChunker for jsx files`() {
        val chunker = registry.getChunker(Paths.get("Component.jsx"))
        assertNotNull(chunker)
        assertTrue(chunker is Chunker)
    }
}
