package com.orchestrator.config

/**
 * Configuration models with sensible defaults, validation, and environment variable support.
 *
 * Environment variable support:
 * - SERVER_HOST, SERVER_PORT, SERVER_TRANSPORT
 * - STORAGE_DB_PATH
 * - ROUTING_APPROVAL_THRESHOLD, ROUTING_DEFAULT_STRATEGY
 * - CONSENSUS_STRATEGIES (comma-separated), CONSENSUS_QUORUM_SIZE, CONSENSUS_AGREEMENT_THRESHOLD
 *
 * String values support ${VAR_NAME} expansion using process environment variables.
 */
@Suppress("MemberVisibilityCanBePrivate")
data class OrchestratorConfig(
    val server: ServerConfig = ServerConfig(),
    val storage: StorageConfig = StorageConfig(),
    val routing: RoutingConfig = RoutingConfig(),
    val consensus: ConsensusConfig = ConsensusConfig()
) {
    fun validate(): OrchestratorConfig {
        server.validate()
        storage.validate()
        routing.validate()
        consensus.validate()
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): OrchestratorConfig {
            return OrchestratorConfig(
                server = ServerConfig.fromEnv(env),
                storage = StorageConfig.fromEnv(env),
                routing = RoutingConfig.fromEnv(env),
                consensus = ConsensusConfig.fromEnv(env)
            ).validate()
        }
    }
}

enum class Transport { HTTP, GRPC }

data class ServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val transport: Transport = Transport.HTTP
) {
    fun validate(): ServerConfig {
        require(host.isNotBlank()) { "Server host must not be blank" }
        require(port in 1..65535) { "Server port must be between 1 and 65535" }
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            val host = env["SERVER_HOST"]?.let { it.expandEnv(env) } ?: ServerConfig().host
            val port = env["SERVER_PORT"]?.toIntOrNull() ?: ServerConfig().port
            val transport = env["SERVER_TRANSPORT"]
                ?.trim()?.uppercase()
                ?.let { runCatching { Transport.valueOf(it) }.getOrNull() }
                ?: ServerConfig().transport
            return ServerConfig(host = host, port = port, transport = transport).validate()
        }
    }
}

data class StorageConfig(
    val databasePath: String = "data/orchestrator.db"
) {
    fun validate(): StorageConfig {
        require(databasePath.isNotBlank()) { "Storage databasePath must not be blank" }
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): StorageConfig {
            val path = env["STORAGE_DB_PATH"]?.let { it.expandEnv(env) } ?: StorageConfig().databasePath
            return StorageConfig(databasePath = path).validate()
        }
    }
}

enum class RoutingStrategy { ROUND_ROBIN, LEAST_LOADED, WEIGHTED, RANDOM }

data class RoutingConfig(
    val approvalThreshold: Double = 0.6, // 60% default
    val defaultStrategy: RoutingStrategy = RoutingStrategy.ROUND_ROBIN
) {
    fun validate(): RoutingConfig {
        require(approvalThreshold in 0.0..1.0) { "Routing approvalThreshold must be in [0.0, 1.0]" }
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): RoutingConfig {
            val thr = env["ROUTING_APPROVAL_THRESHOLD"]?.toDoubleOrNull()
            val strategy = env["ROUTING_DEFAULT_STRATEGY"]
                ?.trim()?.uppercase()
                ?.let { runCatching { RoutingStrategy.valueOf(it) }.getOrNull() }
            return RoutingConfig(
                approvalThreshold = thr ?: RoutingConfig().approvalThreshold,
                defaultStrategy = strategy ?: RoutingConfig().defaultStrategy
            ).validate()
        }
    }
}

enum class ConsensusStrategy { MAJORITY, SUPERMAJORITY, UNANIMOUS, WEIGHTED }

data class ConsensusConfig(
    val strategies: List<ConsensusStrategy> = listOf(ConsensusStrategy.MAJORITY),
    val quorumSize: Int = 1,
    val agreementThreshold: Double = 0.5
) {
    fun validate(): ConsensusConfig {
        require(strategies.isNotEmpty()) { "Consensus strategies must not be empty" }
        require(quorumSize >= 1) { "Consensus quorumSize must be >= 1" }
        require(agreementThreshold in 0.0..1.0) { "Consensus agreementThreshold must be in [0.0, 1.0]" }
        return this
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ConsensusConfig {
            val strategies = env["CONSENSUS_STRATEGIES"]
                ?.split(',')
                ?.mapNotNull { token ->
                    val t = token.trim()
                    if (t.isEmpty()) null else runCatching { ConsensusStrategy.valueOf(t.uppercase()) }.getOrNull()
                }
                ?.takeIf { it.isNotEmpty() }
                ?: ConsensusConfig().strategies

            val quorum = env["CONSENSUS_QUORUM_SIZE"]?.toIntOrNull() ?: ConsensusConfig().quorumSize
            val threshold = env["CONSENSUS_AGREEMENT_THRESHOLD"]?.toDoubleOrNull()
                ?: ConsensusConfig().agreementThreshold

            return ConsensusConfig(
                strategies = strategies,
                quorumSize = quorum,
                agreementThreshold = threshold
            ).validate()
        }
    }
}

// --- Utilities ---
private val envVarRegex = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

private fun String.expandEnv(env: Map<String, String> = System.getenv()): String {
    return this.replace(envVarRegex) { m ->
        val key = m.groupValues[1]
        env[key] ?: m.value // leave placeholder intact if not found
    }
}
