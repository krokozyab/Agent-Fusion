package com.orchestrator.context.discovery

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipFilterTest {

    @Test
    fun `empty patterns don't skip anything`() {
        val filter = SkipFilter.fromPatterns(emptyList())
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
        assertFalse(filter.shouldSkip(Paths.get("app.min.js")))
        assertFalse(filter.shouldSkip(Paths.get("test.spec.ts")))
    }

    @Test
    fun `minified file patterns work`() {
        val filter = SkipFilter.fromPatterns(listOf("*.min.js", "*.min.css"))
        assertTrue(filter.shouldSkip(Paths.get("app.min.js")))
        assertTrue(filter.shouldSkip(Paths.get("style.min.css")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
        assertFalse(filter.shouldSkip(Paths.get("style.css")))
    }

    @Test
    fun `test file patterns work`() {
        val filter = SkipFilter.fromPatterns(listOf("*.test.js", "*.spec.ts"))
        assertTrue(filter.shouldSkip(Paths.get("app.test.js")))
        assertTrue(filter.shouldSkip(Paths.get("utils.spec.ts")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
        assertFalse(filter.shouldSkip(Paths.get("utils.ts")))
    }

    @Test
    fun `directory patterns work`() {
        val filter = SkipFilter.fromPatterns(listOf("**/dist/**", "**/build/**"))
        assertTrue(filter.shouldSkip(Paths.get("dist/app.js")))
        assertTrue(filter.shouldSkip(Paths.get("src/dist/bundle.js")))
        assertTrue(filter.shouldSkip(Paths.get("build/output.jar")))
        assertFalse(filter.shouldSkip(Paths.get("src/app.js")))
    }

    @Test
    fun `multiple extensions pattern`() {
        val filter = SkipFilter.fromPatterns(listOf("*.min.*"))
        assertTrue(filter.shouldSkip(Paths.get("app.min.js")))
        assertTrue(filter.shouldSkip(Paths.get("style.min.css")))
        assertTrue(filter.shouldSkip(Paths.get("script.min.ts")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
    }


    @Test
    fun `complex patterns with wildcards`() {
        val filter = SkipFilter.fromPatterns(listOf(
            "*.test.*.js",  // matches app.test.utils.js
            "src/*/dist/**" // matches src/module/dist/**
        ))
        assertTrue(filter.shouldSkip(Paths.get("app.test.utils.js")))
        assertTrue(filter.shouldSkip(Paths.get("src/module/dist/bundle.js")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
    }

    @Test
    fun `patterns can be applied to absolute paths`() {
        val filter = SkipFilter.fromPatterns(listOf("*.min.js", "**/dist/**"))
        // Test with absolute path
        val absPath = Paths.get("/home/user/project/dist/app.min.js").toAbsolutePath()
        assertTrue(filter.shouldSkip(absPath))
    }

    @Test
    fun `multiple skip patterns all apply`() {
        val filter = SkipFilter.fromPatterns(listOf(
            "*.min.js",
            "*.test.ts",
            "**/node_modules/**",
            "**/.git/**"
        ))
        assertTrue(filter.shouldSkip(Paths.get("app.min.js")))
        assertTrue(filter.shouldSkip(Paths.get("utils.test.ts")))
        assertTrue(filter.shouldSkip(Paths.get("node_modules/lib/index.js")))
        assertTrue(filter.shouldSkip(Paths.get(".git/objects/abc123")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
    }

    @Test
    fun `question mark wildcard matches single character`() {
        val filter = SkipFilter.fromPatterns(listOf("app?.js"))
        assertTrue(filter.shouldSkip(Paths.get("app1.js")))
        assertTrue(filter.shouldSkip(Paths.get("appA.js")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
        assertFalse(filter.shouldSkip(Paths.get("app12.js")))
    }

    @Test
    fun `single star matches filename regardless of directory`() {
        val filter = SkipFilter.fromPatterns(listOf("*.js"))
        assertTrue(filter.shouldSkip(Paths.get("app.js")))
        assertTrue(filter.shouldSkip(Paths.get("utils.test.js")))
        // Now matches files with .js extension at any directory level
        assertTrue(filter.shouldSkip(Paths.get("src/app.js")))
        assertTrue(filter.shouldSkip(Paths.get("src/deep/nested/file.js")))
        // But doesn't match different extensions
        assertFalse(filter.shouldSkip(Paths.get("src/utils.ts")))
    }

    @Test
    fun `double star matches across directories`() {
        val filter = SkipFilter.fromPatterns(listOf("**/test/**"))
        assertTrue(filter.shouldSkip(Paths.get("test/app.js")))
        assertTrue(filter.shouldSkip(Paths.get("src/test/utils.js")))
        assertTrue(filter.shouldSkip(Paths.get("src/deep/test/helpers/setup.js")))
        assertFalse(filter.shouldSkip(Paths.get("src/app.js")))
    }

    @Test
    fun `whitespace in patterns is trimmed`() {
        val filter = SkipFilter.fromPatterns(listOf("  *.min.js  ", "\t*.test.ts\t"))
        assertTrue(filter.shouldSkip(Paths.get("app.min.js")))
        assertTrue(filter.shouldSkip(Paths.get("utils.test.ts")))
    }

    @Test
    fun `empty patterns in list are ignored`() {
        val filter = SkipFilter.fromPatterns(listOf("*.min.js", "", "  ", "*.test.ts"))
        assertTrue(filter.shouldSkip(Paths.get("app.min.js")))
        assertTrue(filter.shouldSkip(Paths.get("utils.test.ts")))
        assertFalse(filter.shouldSkip(Paths.get("app.js")))
    }
}
