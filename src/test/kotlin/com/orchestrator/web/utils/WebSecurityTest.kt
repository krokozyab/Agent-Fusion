package com.orchestrator.web.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebSecurityTest {

    private lateinit var root: Path

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("websec-test")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `escapeHtml neutralizes script injection`() {
        val malicious = "<img src=x onerror=alert(1)>"
        val escaped = WebSecurity.escapeHtml(malicious)
        assertEquals("&lt;img src=x onerror=alert(1)&gt;", escaped)
        assertEquals("&amp;&quot;&#39;&lt;&gt;", WebSecurity.escapeHtml("&\"'<>"))
    }

    @Test
    fun `resolveWithinRoots allows a file inside the root`() {
        val file = root.resolve("Main.kt")
        Files.writeString(file, "fun main() {}")

        val resolved = WebSecurity.resolveWithinRoots(file.toString(), listOf(root.toString()))
        assertNotNull(resolved, "File inside the root must be allowed")
        assertEquals(file.toFile().canonicalFile, resolved)
    }

    @Test
    fun `resolveWithinRoots allows a file in a nested subdirectory`() {
        val sub = Files.createDirectories(root.resolve("a/b"))
        val file = sub.resolve("Deep.kt")
        Files.writeString(file, "x")

        assertNotNull(WebSecurity.resolveWithinRoots(file.toString(), listOf(root.toString())))
    }

    @Test
    fun `resolveWithinRoots rejects path traversal escaping the root`() {
        // Create a secret file OUTSIDE the root, reach it via ../
        val secret = Files.createTempFile("secret", ".txt")
        try {
            val traversal = root.resolve("../" + secret.fileName.toString()).toString()
            // The traversal target is the sibling temp file, not under root.
            val resolved = WebSecurity.resolveWithinRoots(secret.toString(), listOf(root.toString()))
            assertNull(resolved, "Absolute path outside root must be rejected")

            val resolvedTraversal = WebSecurity.resolveWithinRoots(traversal, listOf(root.toString()))
            // `traversal` only escapes if it actually resolves outside; assert it is not under root.
            if (resolvedTraversal != null) {
                assertEquals(root.toFile().canonicalFile,
                    generateSequence(resolvedTraversal) { it.parentFile }.firstOrNull { it == root.toFile().canonicalFile },
                    "If resolved, it must genuinely be under root")
            }
        } finally {
            Files.deleteIfExists(secret)
        }
    }

    @Test
    fun `resolveWithinRoots rejects absolute path outside root`() {
        assertNull(WebSecurity.resolveWithinRoots("/etc/passwd", listOf(root.toString())))
    }

    @Test
    fun `resolveWithinRoots rejects a directory`() {
        assertNull(WebSecurity.resolveWithinRoots(root.toString(), listOf(root.toString())),
            "A directory is not a readable file")
    }

    @Test
    fun `resolveWithinRoots rejects when roots empty or path blank`() {
        val file = root.resolve("X.kt")
        Files.writeString(file, "x")
        assertNull(WebSecurity.resolveWithinRoots(file.toString(), emptyList()))
        assertNull(WebSecurity.resolveWithinRoots("", listOf(root.toString())))
    }

    @Test
    fun `resolveWithinRoots rejects sibling root prefix collision`() {
        // root = /tmp/.../websec-test ; create sibling /tmp/.../websec-test-evil
        val evil = Files.createDirectories(root.resolveSibling(root.fileName.toString() + "-evil"))
        try {
            val file = evil.resolve("Evil.kt")
            Files.writeString(file, "x")
            // A naive startsWith(string) check would match the sibling prefix; segment-aware must not.
            assertNull(WebSecurity.resolveWithinRoots(file.toString(), listOf(root.toString())))
        } finally {
            Files.walk(evil).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
