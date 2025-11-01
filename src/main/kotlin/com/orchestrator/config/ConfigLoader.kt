package com.orchestrator.config

import com.akuleshov7.ktoml.Toml
import com.orchestrator.web.WebServerConfig
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config as TypesafeConfig
import kotlinx.serialization.Serializable
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.ContextConfigLoader
import com.orchestrator.domain.AgentConfig
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.AgentType
import java.io.File
import java.nio.file.Path

/**
 * Loads application configuration from fusionagent.toml TOML file.
 * - Loads orchestrator server config from fusionagent.toml [orchestrator.server] section
 * - Loads web server config from fusionagent.toml [web] section
 * - Loads agents from fusionagent.toml [agents] sections
 * - Loads context config from fusionagent.toml [context] section
 * - Supports environment variable substitution in all string values
 * - Validates all settings
 */
object ConfigLoader {
    private val envVarRegex = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

    @Serializable
    private data class AgentsRoot(
        val agents: Map<String, AgentToml> = emptyMap()
    )

    @Serializable
    private data class AgentToml(
        val type: String,
        val name: String? = null,
        val model: String? = null,
        val apiKeyRef: String? = null,
        val organization: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val extra: Map<String, String> = emptyMap()
    )

    @Serializable
    private data class OrchestratorServerToml(
        val host: String? = null,
        val port: Int? = null,
        val transport: String? = null
    )

    @Serializable
    private data class OrchestratorToml(
        val server: OrchestratorServerToml? = null
    )

    @Serializable
    private data class WebCorsToml(
        val enabled: Boolean? = null,
        val allowedOrigins: List<String>? = null
    )

    @Serializable
    private data class WebToml(
        val host: String? = null,
        val port: Int? = null,
        val staticPath: String? = null,
        val autoLaunchBrowser: Boolean? = null,
        val cors: WebCorsToml? = null
    )

    @Serializable
    private data class WebRoot(
        val web: WebToml? = null
    )

    @Serializable
    private data class FusionAgentRoot(
        val agents: Map<String, AgentToml> = emptyMap()
        // Note: [orchestrator.server] and [web] are parsed separately to avoid issues with other sections
    )

    data class AgentDefinition(
        val id: AgentId,
        val type: AgentType,
        val config: AgentConfig
    )
    
    data class ApplicationConfig(
        val orchestrator: OrchestratorConfig,
        val web: WebServerConfig,
        val agents: List<AgentDefinition>,
        val context: ContextConfig
    )

    /**
     * Load complete application configuration from fusionagent.toml.
     * @param hoconPath deprecated - no longer used (kept for backward compatibility)
     * @param tomlPath path to fusionagent.toml (defaults to fusionagent.toml)
     * @param env environment variables for substitution
     */
    fun loadAll(
        hoconPath: String? = null,
        tomlPath: Path = Path.of("fusionagent.toml"),
        env: Map<String, String> = System.getenv()
    ): ApplicationConfig {
        // Load orchestrator config from TOML file (primary source), fall back to HOCON for backward compatibility
        val orchestratorConfig = loadOrchestratorConfigFromToml(tomlPath, env)
            ?: if (hoconPath != null) parseOrchestratorConfig(resolveHocon(hoconPath), env) else null
            ?: error("No orchestrator configuration found in fusionagent.toml or application.conf")

        // Load web config from TOML file (primary source)
        val webConfig = loadWebConfigFromToml(tomlPath, env)
            ?: WebServerConfig()

        val agents = loadAgents(tomlPath, env)
        val contextConfig = ContextConfigLoader.load(tomlPath, env)
        return ApplicationConfig(orchestratorConfig, webConfig, agents, contextConfig)
    }
    
    /**
     * Load orchestrator configuration from HOCON.
     */
    fun loadHocon(path: String? = null, env: Map<String, String> = System.getenv()): OrchestratorConfig {
        val config = resolveHocon(path)
        return parseOrchestratorConfig(config, env)
    }

    /**
     * Load orchestrator configuration from fusionagent.toml TOML file.
     * @param tomlPath path to the fusionagent.toml
     * @param env environment variables for substitution
     * @return OrchestratorConfig if [orchestrator.server] section is present, null otherwise
     */
    private fun loadOrchestratorConfigFromToml(
        tomlPath: Path,
        env: Map<String, String> = System.getenv()
    ): OrchestratorConfig? {
        val file = tomlPath.toFile()
        if (!file.exists()) {
            return null
        }

        return try {
            val content = file.readText()
            // Parse just the orchestrator.server section to avoid issues with other sections
            val serverConfig = parseOrchestratorServerFromToml(content, env) ?: return null

            // Use defaults for other config sections from environment
            val storage = StorageConfig.fromEnv(env)
            val routing = RoutingConfig.fromEnv(env)
            val consensus = ConsensusConfig.fromEnv(env)

            OrchestratorConfig(serverConfig, storage, routing, consensus).validate()
        } catch (e: Exception) {
            // Log exception for debugging and return null as fallback
            System.err.println("Warning: Failed to parse orchestrator config from TOML: ${e.message}")
            e.printStackTrace(System.err)
            null
        }
    }

    /**
     * Parse just the [orchestrator.server] section from TOML content.
     * This avoids issues with other top-level sections in the TOML file.
     */
    private fun parseOrchestratorServerFromToml(content: String, env: Map<String, String>): ServerConfig? {
        // Find the [orchestrator.server] section header as a standalone line (not in a comment)
        val sectionRegex = Regex("^\\[orchestrator\\.server\\]\\s*$", RegexOption.MULTILINE)
        val match = sectionRegex.find(content) ?: return null

        val sectionStart = match.range.first
        val headerLineEnd = content.indexOf('\n', sectionStart)
        val contentStart = if (headerLineEnd == -1) content.length else headerLineEnd + 1

        // Extract section body - all lines until the next section header
        val lines = content.substring(contentStart).split('\n')
        val sectionLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            // Stop if we hit another section header
            if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                break
            }
            // Skip empty lines and comments at section level
            if (trimmed.isNotEmpty() && !trimmed.startsWith('#')) {
                sectionLines.add(line)
            }
        }

        val sectionBody = sectionLines.joinToString("\n")
        val host = extractTomlValue("host", sectionBody)?.expandEnv(env)
        val portStr = extractTomlValue("port", sectionBody)
        val port = portStr?.toIntOrNull()
        val transport = extractTomlValue("transport", sectionBody)

        val configHost = host ?: ServerConfig().host
        val configPort = port ?: ServerConfig().port
        val configTransport = transport?.let {
            Transport.valueOf(it.trim().uppercase())
        } ?: ServerConfig().transport

        return ServerConfig(host = configHost, port = configPort, transport = configTransport).validate()
    }

    /**
     * Extract a TOML value from a key=value line, handling quotes and comments.
     */
    private fun extractTomlValue(key: String, content: String): String? {
        // Find the line with the key
        for (line in content.split('\n')) {
            val trimmed = line.trim()
            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue

            // Check if this line contains our key
            if (!trimmed.startsWith(key) || trimmed.length <= key.length) continue

            // Check for equals sign after the key
            val afterKey = trimmed.substring(key.length).trimStart()
            if (!afterKey.startsWith('=')) continue

            // Extract the value part (everything after the =)
            var value = afterKey.substring(1).trimStart()

            // Remove inline comments
            val commentIndex = value.indexOf('#')
            if (commentIndex != -1) {
                value = value.substring(0, commentIndex)
            }

            value = value.trim()
            if (value.isEmpty()) return null

            // Remove quotes if present
            return when {
                value.startsWith("\"") && value.endsWith("\"") -> value.substring(1, value.length - 1)
                value.startsWith("'") && value.endsWith("'") -> value.substring(1, value.length - 1)
                else -> value
            }
        }
        return null
    }

    /**
     * Load web server configuration from fusionagent.toml TOML file.
     * @param tomlPath path to the fusionagent.toml
     * @param env environment variables for substitution
     * @return WebServerConfig if [web] section is present, null otherwise
     */
    private fun loadWebConfigFromToml(
        tomlPath: Path,
        env: Map<String, String> = System.getenv()
    ): WebServerConfig? {
        val file = tomlPath.toFile()
        if (!file.exists()) {
            return null
        }

        return try {
            val content = file.readText()
            val webRoot: WebRoot = Toml.decodeFromString(WebRoot.serializer(), content)
            val webToml = webRoot.web ?: return null

            val defaults = WebServerConfig()
            val host = webToml.host?.expandEnv(env) ?: defaults.host
            val port = webToml.port ?: defaults.port
            val staticPath = webToml.staticPath?.expandEnv(env) ?: defaults.staticPath
            val autoLaunchBrowser = webToml.autoLaunchBrowser ?: defaults.autoLaunchBrowser

            val corsEnabled = webToml.cors?.enabled ?: defaults.corsEnabled
            val corsAllowedOrigins = webToml.cors?.allowedOrigins?.map { it.expandEnv(env) }

            WebServerConfig(
                host = host,
                port = port,
                staticPath = staticPath,
                corsEnabled = corsEnabled,
                corsAllowedOrigins = corsAllowedOrigins,
                autoLaunchBrowser = autoLaunchBrowser
            )
        } catch (e: Exception) {
            // Return null if parsing fails
            null
        }
    }

    /**
     * Load agent definitions from TOML.
     * @param path path to the fusionagent.toml (defaults to fusionagent.toml).
     * @param env environment variables map for substitution, defaults to System.getenv().
     * @throws IllegalArgumentException on validation/parsing issues with clear message.
     */
    fun loadAgents(path: Path = Path.of("fusionagent.toml"), env: Map<String, String> = System.getenv()): List<AgentDefinition> {
        val file = path.toFile()
        if (!file.exists()) {
            throw IllegalArgumentException("Config file not found at ${file.path}. Provide fusionagent.toml in the project root or copy fusionagent.toml.example and adjust values.")
        }
        val content = file.readText()
        val root: AgentsRoot = try {
            Toml.decodeFromString(AgentsRoot.serializer(), content)
        } catch (e: Exception) {
            // Fallback: support nested tables like [agents.<id>] per example file
            val nested = parseNestedAgents(content)
            if (nested.isEmpty()) {
                throw IllegalArgumentException("Failed to parse ${file.path}: ${e.message}", e)
            } else {
                AgentsRoot(agents = nested)
            }
        }

        if (root.agents.isEmpty()) {
            throw IllegalArgumentException("No agents defined in ${file.path}. Add at least one agent under [agents.<id>].")
        }

        val results = mutableListOf<AgentDefinition>()
        root.agents.forEach { (rawId, rawAgent) ->
            val id = rawId.trim()
            require(id.isNotBlank()) { "Agent id key must not be blank under [agents]." }

            val type = runCatching { AgentType.valueOf(rawAgent.type.trim().uppercase()) }.getOrElse {
                throw IllegalArgumentException("Agent '$id' has invalid type '${rawAgent.type}'. Allowed: ${AgentType.entries.joinToString(", ") { it.name }}")
            }

            // Expand env vars in all string fields
            val name = rawAgent.name?.expandEnv(env)
            val model = rawAgent.model?.expandEnv(env)
            val apiKeyRef = rawAgent.apiKeyRef?.expandEnv(env)
            val organization = rawAgent.organization?.expandEnv(env)
            val extra = rawAgent.extra.mapValues { (_, v) -> v.expandEnv(env) }

            val agentConfig = try {
                AgentConfig(
                    name = name,
                    model = model,
                    apiKeyRef = apiKeyRef,
                    organization = organization,
                    temperature = rawAgent.temperature,
                    maxTokens = rawAgent.maxTokens,
                    extra = extra
                )
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid configuration for agent '$id': ${e.message}", e)
            }

            results += AgentDefinition(id = AgentId(id), type = type, config = agentConfig)
        }
        return results
    }
    
    private fun parseOrchestratorConfig(config: TypesafeConfig, env: Map<String, String>): OrchestratorConfig {
        val server = if (config.hasPath("orchestrator.server")) {
            val sc = config.getConfig("orchestrator.server")
            ServerConfig(
                host = if (sc.hasPath("host")) sc.getString("host").expandEnv(env) else ServerConfig().host,
                port = if (sc.hasPath("port")) sc.getInt("port") else ServerConfig().port,
                transport = if (sc.hasPath("transport")) {
                    Transport.valueOf(sc.getString("transport").uppercase())
                } else ServerConfig().transport
            )
        } else ServerConfig.fromEnv(env)
        
        val storage = if (config.hasPath("orchestrator.storage")) {
            val sc = config.getConfig("orchestrator.storage")
            StorageConfig(
                databasePath = if (sc.hasPath("databasePath")) {
                    sc.getString("databasePath").expandEnv(env)
                } else StorageConfig().databasePath
            )
        } else StorageConfig.fromEnv(env)
        
        val routing = if (config.hasPath("orchestrator.routing")) {
            val rc = config.getConfig("orchestrator.routing")
            RoutingConfig(
                approvalThreshold = if (rc.hasPath("approvalThreshold")) {
                    rc.getDouble("approvalThreshold")
                } else RoutingConfig().approvalThreshold,
                defaultStrategy = if (rc.hasPath("defaultStrategy")) {
                    RoutingStrategy.valueOf(rc.getString("defaultStrategy").uppercase())
                } else RoutingConfig().defaultStrategy
            )
        } else RoutingConfig.fromEnv(env)
        
        val consensus = if (config.hasPath("orchestrator.consensus")) {
            val cc = config.getConfig("orchestrator.consensus")
            ConsensusConfig(
                strategies = if (cc.hasPath("strategies")) {
                    cc.getStringList("strategies").map { ConsensusStrategy.valueOf(it.uppercase()) }
                } else ConsensusConfig().strategies,
                quorumSize = if (cc.hasPath("quorumSize")) {
                    cc.getInt("quorumSize")
                } else ConsensusConfig().quorumSize,
                agreementThreshold = if (cc.hasPath("agreementThreshold")) {
                    cc.getDouble("agreementThreshold")
                } else ConsensusConfig().agreementThreshold
            )
        } else ConsensusConfig.fromEnv(env)
        
        return OrchestratorConfig(server, storage, routing, consensus).validate()
    }

    private fun resolveHocon(path: String?): TypesafeConfig {
        return if (path != null) {
            ConfigFactory.parseFile(File(path))
                .withFallback(ConfigFactory.load())
                .resolve()
        } else {
            ConfigFactory.load()
        }
    }

    private fun parseNestedAgents(content: String): Map<String, AgentToml> {
        val result = LinkedHashMap<String, AgentToml>()
        val sectionRegex = Regex("^\\s*\\[agents\\.([A-Za-z0-9_.-]+)]\\s*$")
        var currentId: String? = null
        val buffer = StringBuilder()
        fun flush() {
            val id = currentId ?: return
            val body = buffer.toString().trim()
            if (body.isNotEmpty()) {
                val agent = try {
                    Toml.decodeFromString(AgentToml.serializer(), body)
                } catch (_: Exception) {
                    null
                }
                if (agent != null) result[id] = agent
            }
            currentId = null
            buffer.setLength(0)
        }
        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val m = sectionRegex.matchEntire(line)
            if (m != null) {
                flush()
                currentId = m.groupValues[1]
                return@forEach
            }
            if (currentId != null) {
                // Stop if we hit a new top-level table that is not under agents.
                if (line.startsWith("[") && sectionRegex.matchEntire(line) == null) {
                    flush()
                } else {
                    buffer.appendLine(line)
                }
            }
        }
        flush()
        return result
    }

    private fun String.expandEnv(env: Map<String, String>): String {
        return this.replace(envVarRegex) { m ->
            val key = m.groupValues[1]
            env[key] ?: m.value // leave placeholder intact if not found
        }
    }
}
