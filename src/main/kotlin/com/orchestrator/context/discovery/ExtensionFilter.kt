package com.orchestrator.context.discovery

import java.nio.file.Path
import java.util.Locale

/**
 * Filters candidate files based on configured extension allowlist or blocklist.
 */
class ExtensionFilter private constructor(
    private val mode: Mode,
    private val extensions: Set<String>
) {

    enum class Mode { ALLOWLIST, BLOCKLIST, DISABLED }

    fun shouldInclude(path: Path): Boolean {
        if (mode == Mode.DISABLED) return true
        val fileName = path.fileName?.toString() ?: return mode != Mode.ALLOWLIST
        val normalized = fileName.lowercase(Locale.US)

        val matches = extensions.any { normalized.endsWith(it) }

        return when (mode) {
            Mode.ALLOWLIST -> matches
            Mode.BLOCKLIST -> !matches
            Mode.DISABLED -> true
        }
    }

    companion object {
        fun allowlist(extensions: Collection<String>): ExtensionFilter =
            ExtensionFilter(Mode.ALLOWLIST, normalizeExtensions(extensions))

        fun blocklist(extensions: Collection<String>): ExtensionFilter =
            ExtensionFilter(Mode.BLOCKLIST, normalizeExtensions(extensions))

        fun disabled(): ExtensionFilter = ExtensionFilter(Mode.DISABLED, emptySet())

        fun fromConfig(
            allowlist: Collection<String>,
            blocklist: Collection<String>
        ): ExtensionFilter {
            val normalizedAllow = normalizeExtensions(allowlist)
            val normalizedBlock = normalizeExtensions(blocklist)

            require(normalizedAllow.isEmpty() || normalizedBlock.isEmpty()) {
                "Allowlist and blocklist modes are mutually exclusive"
            }

            return when {
                normalizedAllow.isNotEmpty() -> ExtensionFilter(Mode.ALLOWLIST, normalizedAllow)
                normalizedBlock.isNotEmpty() -> ExtensionFilter(Mode.BLOCKLIST, normalizedBlock)
                else -> disabled()
            }
        }

        private fun normalizeExtensions(extensions: Collection<String>): Set<String> {
            return extensions
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { value ->
                    val withDot = if (value.startsWith('.')) value else ".${value}"
                    withDot.lowercase(Locale.US)
                }
                .toSet()
        }
    }
}
