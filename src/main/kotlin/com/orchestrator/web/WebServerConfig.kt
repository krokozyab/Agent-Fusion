package com.orchestrator.web

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/**
 * Configuration model for the embedded web dashboard server.
 *
 * Values are pulled from the `web` section of the main HOCON configuration and
 * can be overridden via environment variables using standard Typesafe Config
 * substitution (e.g. `${?WEB_HOST}`).
 */
data class WebServerConfig(
    val host: String = "0.0.0.0",
    val port: Int = 9090,
    val staticPath: String = "static",
    val corsEnabled: Boolean = true,
    val corsAllowedOrigins: List<String>? = null,  // null means auto-generate based on port
    val ssl: SslConfig = SslConfig(),
    val autoLaunchBrowser: Boolean = true
) {

    fun getEffectiveCorsOrigins(): List<String> {
        return corsAllowedOrigins ?: listOf(
            "http://localhost:$port",
            "http://127.0.0.1:$port",
            "http://0.0.0.0:$port"
        )
    }

    init {
        require(host.isNotBlank()) { "Web server host must not be blank" }
        require(port in 1..65_535) { "Web server port must be between 1 and 65535" }
        ssl.validate()
    }

    data class SslConfig(
        val enabled: Boolean = false,
        val keyStorePath: String? = null,
        val keyStorePassword: String? = null,
        val privateKeyPassword: String? = null
    ) {
        fun validate() {
            if (enabled) {
                require(!keyStorePath.isNullOrBlank()) { "TLS keyStorePath is required when SSL is enabled" }
                require(!keyStorePassword.isNullOrBlank()) { "TLS keyStorePassword is required when SSL is enabled" }
            }
        }
    }

    companion object {
        fun load(config: Config = ConfigFactory.load(), env: Map<String, String> = System.getenv()): WebServerConfig {
            val defaults = WebServerConfig()

            if (!config.hasPath("web")) return defaults

            val section = config.getConfig("web")

            val host = section.getOptionalString("host", env) ?: defaults.host
            val port = section.getOptionalInt("port") ?: defaults.port
            val staticPath = section.getOptionalString("staticPath", env) ?: defaults.staticPath
            val corsEnabled = section.getOptionalBoolean("cors.enabled") ?: defaults.corsEnabled
            val corsAllowed = when {
                section.hasPath("cors.allowedOrigins") -> section.getStringList("cors.allowedOrigins").map { it.expandEnv(env) }
                else -> null  // null means auto-generate based on port
            }

            val sslSection = if (section.hasPath("ssl")) section.getConfig("ssl") else null
            val ssl = if (sslSection != null) {
                SslConfig(
                    enabled = sslSection.getOptionalBoolean("enabled") ?: false,
                    keyStorePath = sslSection.getOptionalString("keyStorePath", env),
                    keyStorePassword = sslSection.getOptionalString("keyStorePassword", env),
                    privateKeyPassword = sslSection.getOptionalString("privateKeyPassword", env)
                )
            } else {
                SslConfig()
            }

            val autoLaunchBrowser = section.getOptionalBoolean("autoLaunchBrowser") ?: defaults.autoLaunchBrowser

            return WebServerConfig(
                host = host,
                port = port,
                staticPath = staticPath,
                corsEnabled = corsEnabled,
                corsAllowedOrigins = corsAllowed,
                ssl = ssl,
                autoLaunchBrowser = autoLaunchBrowser
            )
        }

        private fun Config.getOptionalString(path: String, env: Map<String, String>): String? =
            takeIf { hasPath(path) }?.getString(path)?.expandEnv(env)

        private fun Config.getOptionalInt(path: String): Int? =
            takeIf { hasPath(path) }?.getInt(path)

        private fun Config.getOptionalBoolean(path: String): Boolean? =
            takeIf { hasPath(path) }?.getBoolean(path)
    }
}

private val envRegex = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")

private fun String.expandEnv(env: Map<String, String>): String =
    replace(envRegex) { match ->
        val key = match.groupValues[1]
        env[key] ?: match.value
    }
