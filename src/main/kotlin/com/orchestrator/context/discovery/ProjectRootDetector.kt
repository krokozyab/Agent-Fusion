package com.orchestrator.context.discovery

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Heuristics for detecting the logical project root when explicit configuration is absent.
 */
object ProjectRootDetector {

    data class DetectionResult(
        val root: Path,
        val confidence: Confidence,
        val reason: String
    )

    enum class Confidence { HIGH, MEDIUM, LOW }

    private val packageMarkers = listOf(
        "package.json",
        "pom.xml",
        "build.gradle",
        "build.gradle.kts",
        "Cargo.toml",
        "pyproject.toml",
        "setup.py",
        "go.mod"
    )

    private val structureDirs = listOf("src", "tests", "test", "lib")

    fun detect(start: Path = Path.of(".").toAbsolutePath().normalize()): DetectionResult {
        val git = findGitRoot(start)
        if (git != null) return DetectionResult(git, Confidence.HIGH, "Found .git directory")

        val pkg = findPackageRoot(start)
        if (pkg != null) return DetectionResult(pkg, Confidence.MEDIUM, "Found package manifest")

        val structure = findProjectStructure(start)
        if (structure != null) return DetectionResult(structure, Confidence.LOW, "Detected src/tests structure")

        val cwd = getCurrentWorkingDir(start)
        return DetectionResult(cwd, Confidence.LOW, "Fallback to current working dir")
    }

    private fun findGitRoot(start: Path): Path? {
        return ascend(start) { candidate -> Files.exists(candidate.resolve(".git")) }
    }

    private fun findPackageRoot(start: Path): Path? {
        return ascend(start) { candidate ->
            packageMarkers.any { marker -> Files.exists(candidate.resolve(marker)) }
        }
    }

    private fun findProjectStructure(start: Path): Path? {
        return ascend(start) { candidate ->
            val entries = candidate.toFile().list()?.toSet() ?: emptySet()
            structureDirs.count { entries.contains(it) } >= 2
        }
    }

    private fun getCurrentWorkingDir(start: Path): Path = start

    private fun ascend(start: Path, predicate: (Path) -> Boolean): Path? {
        var current = start
        while (true) {
            if (predicate(current)) return current
            val parent = current.parent ?: return null
            if (parent == current) return null
            current = parent
        }
    }
}
