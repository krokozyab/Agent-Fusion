package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.SymbolType
import com.orchestrator.context.storage.ContextDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolIndexBuilderTest {

    private lateinit var builder: SymbolIndexBuilder

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        builder = SymbolIndexBuilder()

        // Initialize database with temp file
        val dbPath = tempDir.resolve("symbols-test.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
    }

    @AfterEach
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `extracts Kotlin symbols correctly`() = runBlocking {
        // Given
        val kotlinCode = """
            package com.example

            import java.util.List

            class MyClass {
                fun myFunction(arg: Int): String {
                    return "result"
                }

                val myProperty: String = "value"
            }

            fun topLevelFunction() {
                println("Hello")
            }
        """.trimIndent()

        val sourceFile = tempDir.resolve("Test.kt")
        Files.writeString(sourceFile, kotlinCode)

        // Create file_state record first
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("""
                INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, is_deleted)
                VALUES (1, 'Test.kt', 'hash', 100, 1, FALSE)
            """.trimIndent()).use { it.executeUpdate() }
        }

        // When
        val symbols = builder.indexFile(sourceFile, fileId = 1, language = "kotlin")

        // Then
        assertTrue(symbols.isNotEmpty(), "Should extract symbols")

        val importSymbol = symbols.find { it.symbolType == SymbolType.IMPORT }
        assertEquals("List", importSymbol?.name)
        assertEquals("java.util.List", importSymbol?.qualifiedName)

        val classSymbol = symbols.find { it.symbolType == SymbolType.CLASS && it.name == "MyClass" }
        assertTrue(classSymbol != null, "Should find MyClass")
        assertEquals("com.example.MyClass", classSymbol?.qualifiedName)

        val functionSymbol = symbols.find { it.symbolType == SymbolType.FUNCTION && it.name == "myFunction" }
        assertTrue(functionSymbol != null, "Should find myFunction")
        assertTrue(functionSymbol?.signature?.contains("String") == true)

        val propertySymbol = symbols.find { it.symbolType == SymbolType.PROPERTY && it.name == "myProperty" }
        assertTrue(propertySymbol != null, "Should find myProperty")

        val topLevelFunc = symbols.find { it.symbolType == SymbolType.FUNCTION && it.name == "topLevelFunction" }
        assertTrue(topLevelFunc != null, "Should find topLevelFunction")
    }

    @Test
    fun `extracts Python symbols correctly`() = runBlocking {
        // Given
        val pythonCode = """
            import os
            from typing import List

            class MyClass:
                def my_method(self, arg: int) -> str:
                    return "result"

            def top_level_function():
                pass
        """.trimIndent()

        val sourceFile = tempDir.resolve("test.py")
        Files.writeString(sourceFile, pythonCode)

        // Create file_state record first
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("""
                INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, is_deleted)
                VALUES (2, 'test.py', 'hash', 100, 1, FALSE)
            """.trimIndent()).use { it.executeUpdate() }
        }

        // When
        val symbols = builder.indexFile(sourceFile, fileId = 2, language = "python")

        // Then
        assertTrue(symbols.isNotEmpty(), "Should extract symbols")

        val importSymbols = symbols.filter { it.symbolType == SymbolType.IMPORT }
        assertTrue(importSymbols.size >= 2, "Should find import statements")

        val classSymbol = symbols.find { it.symbolType == SymbolType.CLASS && it.name == "MyClass" }
        assertTrue(classSymbol != null, "Should find MyClass")

        val methodSymbol = symbols.find { it.symbolType == SymbolType.METHOD && it.name == "my_method" }
        assertTrue(methodSymbol != null, "Should find my_method")

        val functionSymbol = symbols.find { it.symbolType == SymbolType.FUNCTION && it.name == "top_level_function" }
        assertTrue(functionSymbol != null, "Should find top_level_function")
    }

    @Test
    fun `extracts TypeScript symbols correctly`() = runBlocking {
        // Given
        val tsCode = """
            import { Component } from 'react';

            interface MyInterface {
                name: string;
            }

            export class MyClass implements MyInterface {
                name: string;

                constructor(name: string) {
                    this.name = name;
                }
            }

            export function myFunction(arg: number): string {
                return arg.toString();
            }

            export const MY_CONST: string = "value";
        """.trimIndent()

        val sourceFile = tempDir.resolve("test.ts")
        Files.writeString(sourceFile, tsCode)

        // Create file_state record first
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("""
                INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, is_deleted)
                VALUES (3, 'test.ts', 'hash', 100, 1, FALSE)
            """.trimIndent()).use { it.executeUpdate() }
        }

        // When
        val symbols = builder.indexFile(sourceFile, fileId = 3, language = "typescript")

        // Then
        assertTrue(symbols.isNotEmpty(), "Should extract symbols")

        val interfaceSymbol = symbols.find { it.symbolType == SymbolType.INTERFACE && it.name == "MyInterface" }
        assertTrue(interfaceSymbol != null, "Should find MyInterface")

        val classSymbol = symbols.find { it.symbolType == SymbolType.CLASS && it.name == "MyClass" }
        assertTrue(classSymbol != null, "Should find MyClass")

        val functionSymbol = symbols.find { it.symbolType == SymbolType.FUNCTION && it.name == "myFunction" }
        assertTrue(functionSymbol != null, "Should find myFunction")

        val constSymbol = symbols.find { it.symbolType == SymbolType.VARIABLE && it.name == "MY_CONST" }
        assertTrue(constSymbol != null, "Should find MY_CONST")
    }

    @Test
    fun `stores symbols in database`() = runBlocking {
        // Given
        val kotlinCode = """
            package com.example

            class SimpleClass {
                fun simpleMethod(): Int = 42
            }
        """.trimIndent()

        val sourceFile = tempDir.resolve("Simple.kt")
        Files.writeString(sourceFile, kotlinCode)

        // Insert a file_state record first
        ContextDatabase.transaction { conn ->
            conn.prepareStatement("""
                INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, is_deleted)
                VALUES (100, 'Simple.kt', 'hash', 100, 1, FALSE)
            """.trimIndent()).use { it.executeUpdate() }
        }

        // When
        val symbols = builder.indexFile(sourceFile, fileId = 100, language = "kotlin")

        // Then
        assertTrue(symbols.isNotEmpty(), "Should extract symbols")

        // Verify symbols are stored in database
        val storedCount = ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM symbols WHERE file_id = 100").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

        assertTrue(storedCount > 0, "Symbols should be stored in database")
        assertEquals(symbols.size, storedCount, "All extracted symbols should be stored")
    }
}
