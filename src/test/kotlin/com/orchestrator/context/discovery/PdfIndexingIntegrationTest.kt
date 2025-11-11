package com.orchestrator.context.discovery

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.ContextConfigLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

class PdfIndexingIntegrationTest {

    private val projectRoot: Path = Paths.get("").toAbsolutePath().normalize()

    private val contextConfig: ContextConfig by lazy {
        val configPath = projectRoot.resolve("config/context.toml")
        if (Files.exists(configPath)) {
            ContextConfigLoader.load(configPath)
        } else {
            ContextConfig()
        }
    }

    @Test
    fun `path validator accepts repository pdf files`() {
        val pdfPath = projectRoot.resolve("abraham_isaac_f_in_action_final_release.pdf")
        if (!Files.exists(pdfPath)) {
            // Skip assertion when fixture is absent
            return
        }

        assertTrue(
            contextConfig.indexing.maxFileSizeMb >= 8,
            "Indexing max file size must be at least 8 MB"
        )

        val watcher = contextConfig.watcher
        val pathFilter = PathFilter.fromSources(
            root = projectRoot,
            configPatterns = watcher.ignorePatterns,
            caseInsensitive = true,
            includeGitignore = watcher.useGitignore,
            includeContextignore = watcher.useContextignore,
            includeDockerignore = true
        )

        val extensionFilter = ExtensionFilter.fromConfig(
            allowlist = contextConfig.indexing.allowedExtensions,
            blocklist = contextConfig.indexing.blockedExtensions
        )

        val symlinkHandler = SymlinkHandler(listOf(projectRoot), contextConfig.indexing)
        val includePathsFilter = IncludePathsFilter.disabled()

        val validator = PathValidator(
            watchPaths = listOf(projectRoot),
            pathFilter = pathFilter,
            extensionFilter = extensionFilter,
            includePathsFilter = includePathsFilter,
            symlinkHandler = symlinkHandler,
            indexingConfig = contextConfig.indexing
        )

        val result = validator.validate(pdfPath)
        assertTrue(
            result.isValid(),
            "Expected PDF to be accepted but got ${result.code} (${result.message})"
        )

        val scanner = DirectoryScanner(validator = validator)
        val scanned = scanner.scan(listOf(projectRoot))
        assertTrue(
            scanned.any { it.toAbsolutePath().normalize() == pdfPath },
            "Directory scanner did not include repository PDF: ${pdfPath}"
        )
    }
}
