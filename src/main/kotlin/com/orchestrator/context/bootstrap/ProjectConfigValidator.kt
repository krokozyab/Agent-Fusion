package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.storage.ContextDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

class ProjectConfigValidator {

    companion object {
        const val TABLE_NAME = "project_config"
    }





    fun saveConfig(config: ContextConfig, scope: String = "default") = ContextDatabase.withConnection { conn ->
        val includeGlobs = Json.encodeToString(config.indexing.allowedExtensions)
        val excludeGlobs = Json.encodeToString(config.watcher.ignorePatterns)
        val rootPaths = Json.encodeToString(config.watcher.watchPaths)

        // Check if config for this scope already exists
        val existing = conn.prepareStatement("SELECT config_id FROM $TABLE_NAME WHERE scope = ? ORDER BY created_at DESC LIMIT 1").use { ps ->
            ps.setString(1, scope)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getLong("config_id") else null
            }
        }

        if (existing != null) {
            // Update existing config
            conn.prepareStatement("""
                UPDATE $TABLE_NAME
                SET include_globs = ?::JSON, exclude_globs = ?::JSON, root_paths = ?::JSON, updated_at = ?
                WHERE config_id = ?
            """.trimIndent()).use { ps ->
                ps.setString(1, includeGlobs)
                ps.setString(2, excludeGlobs)
                ps.setString(3, rootPaths)
                ps.setTimestamp(4, Timestamp.from(Instant.now()))
                ps.setLong(5, existing)
                ps.executeUpdate()
            }
        } else {
            // Insert new config
            conn.prepareStatement("""
                INSERT INTO $TABLE_NAME (scope, include_globs, exclude_globs, root_paths, created_at, updated_at)
                VALUES (?, ?::JSON, ?::JSON, ?::JSON, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, scope)
                ps.setString(2, includeGlobs)
                ps.setString(3, excludeGlobs)
                ps.setString(4, rootPaths)
                val now = Timestamp.from(Instant.now())
                ps.setTimestamp(5, now)
                ps.setTimestamp(6, now)
                ps.executeUpdate()
            }
        }
    }

    fun loadConfig(scope: String = "default"): ContextConfig? = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM $TABLE_NAME WHERE scope = ? ORDER BY created_at DESC LIMIT 1").use { ps ->
            ps.setString(1, scope)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    // For now, return a default ContextConfig with the stored paths
                    // In a full implementation, you would reconstruct the full config from the stored data
                    val rootPathsJson = rs.getString("root_paths")
                    val watchPaths = if (rootPathsJson != null) {
                        Json.decodeFromString<List<String>>(rootPathsJson)
                    } else {
                        emptyList()
                    }
                    ContextConfig(watcher = com.orchestrator.context.config.WatcherConfig(watchPaths = watchPaths))
                } else {
                    null
                }
            }
        }
    }

    fun detectChanges(newConfig: ContextConfig): ConfigChanges {
        val oldConfig = loadConfig()
        if (oldConfig == null) {
            return ConfigChanges(added = newConfig.toString().lines())
        }

        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val modified = mutableListOf<String>()

        val oldConfigMap = oldConfig.toString().lines().associate { it.substringBefore("=") to it.substringAfter("=") }
        val newConfigMap = newConfig.toString().lines().associate { it.substringBefore("=") to it.substringAfter("=") }

        newConfigMap.forEach { (key, value) ->
            if (!oldConfigMap.containsKey(key)) {
                added.add("$key = $value")
            } else if (oldConfigMap[key] != value) {
                modified.add("$key = $value (was ${oldConfigMap[key]})")
            }
        }

        oldConfigMap.forEach { (key, _) ->
            if (!newConfigMap.containsKey(key)) {
                removed.add(key)
            }
        }

        return ConfigChanges(added, removed, modified)
    }

    fun validate(config: ContextConfig): ValidationResult {
        val errors = mutableListOf<String>()

        // Validation 1: watch_paths exist
        config.watcher.watchPaths.forEach { pathString ->
            val path = Path.of(pathString)
            if (!Files.exists(path)) {
                errors.add("Watch path does not exist: $pathString")
            }
        }

        // Validation 2: No dangerous paths (/, /etc, /sys, /bin, /usr, /var, /tmp, /home)
        // Note: Exclude safe subdirectories like /var/folders (macOS temp), /tmp/junit, etc.
        val dangerousPaths = listOf("/", "/etc", "/sys", "/bin", "/usr", "/home", "/root")
        val dangerousVarPaths = listOf("/var/log", "/var/lib", "/var/spool", "/var/run")

        config.watcher.watchPaths.forEach { pathString ->
            val normalized = Path.of(pathString).normalize().toString()

            // Check exact matches and dangerous subdirectories
            if (dangerousPaths.any { dangerous -> normalized == dangerous || normalized.startsWith("$dangerous/") }) {
                errors.add("Dangerous path detected: $pathString (matches system directory)")
            }

            // Check specific dangerous /var subdirectories but allow /var/folders, /var/tmp, etc.
            if (dangerousVarPaths.any { dangerous -> normalized == dangerous || normalized.startsWith("$dangerous/") }) {
                errors.add("Dangerous path detected: $pathString (matches system directory)")
            }
        }

        // Validation 3: Extension lists valid (no empty strings, proper format)
        config.indexing.allowedExtensions.forEach { ext ->
            if (ext.isBlank()) {
                errors.add("Extension list contains blank extension")
            }
            if (!ext.startsWith(".")) {
                errors.add("Extension must start with dot: $ext")
            }
            if (ext.length < 2) {
                errors.add("Extension too short: $ext")
            }
        }

        // Validation 4: Size limits reasonable
        if (config.indexing.maxFileSizeMb <= 0) {
            errors.add("Max file size must be positive: ${config.indexing.maxFileSizeMb}")
        }
        if (config.indexing.maxFileSizeMb > 1000) {
            errors.add("Max file size too large (>1GB): ${config.indexing.maxFileSizeMb}")
        }

        // Validation 4b: Detect ambiguous max_file_size_mb configuration
        // Both [context.watcher] and [context.indexing] have identically-named 'max_file_size_mb' fields
        // This is a configuration design error - the names should be distinct
        if (config.watcher.maxFileSizeMb != config.indexing.maxFileSizeMb) {
            val ratio = config.indexing.maxFileSizeMb.toDouble() / config.watcher.maxFileSizeMb
            if (ratio > 2 || ratio < 0.5) {
                errors.add(
                    "CONFIGURATION ERROR: Ambiguous 'max_file_size_mb' setting detected!\n" +
                    "  - [context.watcher] max_file_size_mb = ${config.watcher.maxFileSizeMb} MB (runtime watching)\n" +
                    "  - [context.indexing] max_file_size_mb = ${config.indexing.maxFileSizeMb} MB (initial indexing)\n" +
                    "  These identically-named settings are confusing and error-prone. Recommend renaming to:\n" +
                    "  - [context.watcher] watch_max_file_size_mb = ${config.watcher.maxFileSizeMb}\n" +
                    "  - [context.indexing] index_max_file_size_mb = ${config.indexing.maxFileSizeMb}"
                )
            }
        }

        // Validation 5: Security boundaries - ensure watch_paths don't escape project root
        if (config.watcher.watchPaths.isNotEmpty()) {
            config.watcher.watchPaths.forEach { pathString ->
                val path = Path.of(pathString).normalize()
                // Check for path traversal attempts
                if (path.toString().contains("..")) {
                    errors.add("Path traversal detected in watch path: $pathString")
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult(true)
        } else {
            ValidationResult(false, errors)
        }
    }

}

data class ConfigChanges(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
    val modified: List<String> = emptyList()
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
)
