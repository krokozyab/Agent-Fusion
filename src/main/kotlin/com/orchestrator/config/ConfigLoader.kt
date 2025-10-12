package com.orchestrator.config

import com.akuleshov7.ktoml.Toml
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
 * Loads application configuration from HOCON and TOML files.
 * - Loads application.conf (HOCON) for main configuration
 * - Loads agents.toml (TOML) for agent configurations
 * - Supports environment variable substitution
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

    data class AgentDefinition(
        val id: AgentId,
        val type: AgentType,
        val config: AgentConfig
    )
    
    data class ApplicationConfig(
        val orchestrator: OrchestratorConfig,
        val agents: List<AgentDefinition>,
        val context: ContextConfig
    )

    /**
     * Load complete application configuration.
     * @param hoconPath path to application.conf (defaults to classpath resource)
     * @param tomlPath path to agents.toml (defaults to config/agents.toml)
     * @param env environment variables for substitution
     */
    fun loadAll(
        hoconPath: String? = null,
        tomlPath: Path = Path.of("config/agents.toml"),
        contextPath: Path = Path.of("config/context.toml"),
        env: Map<String, String> = System.getenv()
    ): ApplicationConfig {
        val orchestratorConfig = loadHocon(hoconPath, env)
        val agents = loadAgents(tomlPath, env)
        val contextConfig = ContextConfigLoader.load(contextPath, env)
        return ApplicationConfig(orchestratorConfig, agents, contextConfig)
    }
    
    /**
     * Load orchestrator configuration from HOCON.
     */
    fun loadHocon(path: String? = null, env: Map<String, String> = System.getenv()): OrchestratorConfig {
        val config = if (path != null) {
            ConfigFactory.parseFile(File(path))
                .withFallback(ConfigFactory.load())
                .resolve()
        } else {
            ConfigFactory.load()
        }
        
        return parseOrchestratorConfig(config, env)
    }
    
    /**
     * Load agent definitions from TOML.
     * @param path path to the agents.toml (defaults to config/agents.toml).
     * @param env environment variables map for substitution, defaults to System.getenv().
     * @throws IllegalArgumentException on validation/parsing issues with clear message.
     */
    fun loadAgents(path: Path = Path.of("config/agents.toml"), env: Map<String, String> = System.getenv()): List<AgentDefinition> {
        val file = path.toFile()
        if (!file.exists()) {
            throw IllegalArgumentException("Agents config file not found at ${file.path}. Provide this file or copy config/agents.toml.example and adjust values.")
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

            val requiresModel = when (type) {
                AgentType.GPT,
                AgentType.GEMINI,
                AgentType.MISTRAL,
                AgentType.LLAMA -> true
                else -> false
            }
            if (requiresModel && agentConfig.model.isNullOrBlank()) {
                throw IllegalArgumentException("Agent '$id' of type '${type.name.lowercase()}' must specify 'model'")
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
