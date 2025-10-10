package com.orchestrator.config

import com.orchestrator.domain.AgentType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ConfigLoaderTest {
    
    @Test
    fun `should load HOCON configuration from classpath`() {
        val config = ConfigLoader.loadHocon()
        
        assertNotNull(config)
        assertEquals("127.0.0.1", config.server.host)
        assertEquals(8080, config.server.port)
        assertEquals(Transport.HTTP, config.server.transport)
        assertEquals("data/orchestrator.db", config.storage.databasePath)
    }
    
    @Test
    fun `should load HOCON with environment variables`() {
        val env = mapOf(
            "SERVER_HOST" to "0.0.0.0",
            "SERVER_PORT" to "9090"
        )
        
        val config = OrchestratorConfig.fromEnv(env)
        
        assertEquals("0.0.0.0", config.server.host)
        assertEquals(9090, config.server.port)
    }
    
    @Test
    fun `should load agents from TOML`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.claude-code]
            type = "CLAUDE_CODE"
            name = "Claude Code"
            model = "claude-3-opus"
            
            [agents.codex-cli]
            type = "CODEX_CLI"
            name = "Codex CLI"
            model = "gpt-4"
        """.trimIndent())
        
        val agents = ConfigLoader.loadAgents(tomlFile)
        
        assertEquals(2, agents.size)
        assertEquals("claude-code", agents[0].id.value)
        assertEquals(AgentType.CLAUDE_CODE, agents[0].type)
        assertEquals("Claude Code", agents[0].config.name)
    }
    
    @Test
    fun `should load complete application configuration`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.test-agent]
            type = "CLAUDE_CODE"
            name = "Test Agent"
            model = "claude-3"
        """.trimIndent())
        
        val appConfig = ConfigLoader.loadAll(tomlPath = tomlFile)
        
        assertNotNull(appConfig.orchestrator)
        assertEquals(1, appConfig.agents.size)
        assertEquals("test-agent", appConfig.agents[0].id.value)
    }
    
    @Test
    fun `should expand environment variables in TOML`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.test]
            type = "CLAUDE_CODE"
            name = "Test"
            model = "${'$'}{TEST_MODEL}"
        """.trimIndent())
        
        val env = mapOf("TEST_MODEL" to "claude-3-opus")
        val agents = ConfigLoader.loadAgents(tomlFile, env)
        
        assertEquals("claude-3-opus", agents[0].config.model)
    }
    
    @Test
    fun `should validate orchestrator configuration`() {
        val config = ConfigLoader.loadHocon()
        
        // Should not throw
        config.validate()
        
        assertTrue(config.server.port in 1..65535)
        assertTrue(config.routing.approvalThreshold in 0.0..1.0)
        assertTrue(config.consensus.agreementThreshold in 0.0..1.0)
    }
    
    @Test
    fun `should throw on missing agents file`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.loadAgents(Path.of("nonexistent.toml"))
        }
        
        assertTrue(exception.message!!.contains("not found"))
    }
    
    @Test
    fun `should throw on invalid agent type`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.test]
            type = "INVALID_TYPE"
            name = "Test"
        """.trimIndent())
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.loadAgents(tomlFile)
        }
        
        assertTrue(exception.message!!.contains("invalid type"))
    }
    
    @Test
    fun `should require model for API agent types`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.test]
            type = "GPT"
            name = "Test"
        """.trimIndent())

        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.loadAgents(tomlFile)
        }

        assertTrue(exception.message!!.contains("must specify 'model'"))
    }

    @Test
    fun `should allow MCP agents without model field`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.claude]
            type = "CLAUDE_CODE"
            name = "Claude Code"

            [agents.codex]
            type = "CODEX_CLI"
            name = "Codex CLI"
        """.trimIndent())

        // Should not throw - MCP agents don't require model field
        val agents = ConfigLoader.loadAgents(tomlFile)

        assertEquals(2, agents.size)
        assertEquals("claude", agents[0].id.value)
        assertEquals("codex", agents[1].id.value)
        assertNull(agents[0].config.model)
        assertNull(agents[1].config.model)
    }

    @Test
    fun `should merge HOCON and TOML configurations`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.test]
            type = "CLAUDE_CODE"
            name = "Test"
            model = "claude-3"
        """.trimIndent())
        
        val appConfig = ConfigLoader.loadAll(tomlPath = tomlFile)
        
        // HOCON config
        assertNotNull(appConfig.orchestrator.server)
        assertNotNull(appConfig.orchestrator.storage)
        
        // TOML config
        assertEquals(1, appConfig.agents.size)
        assertEquals("test", appConfig.agents[0].id.value)
    }
    
    @Test
    fun `should handle empty agents file`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("")
        
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConfigLoader.loadAgents(tomlFile)
        }
        
        assertTrue(exception.message!!.contains("No agents defined"))
    }
    
    @Test
    fun `should support nested TOML format`(@TempDir tempDir: Path) {
        val tomlFile = tempDir.resolve("agents.toml")
        tomlFile.writeText("""
            [agents.agent1]
            type = "CLAUDE_CODE"
            name = "Agent 1"
            model = "claude-3"
            
            [agents.agent2]
            type = "CODEX_CLI"
            name = "Agent 2"
            model = "gpt-4"
        """.trimIndent())
        
        val agents = ConfigLoader.loadAgents(tomlFile)
        
        assertEquals(2, agents.size)
        assertTrue(agents.any { it.id.value == "agent1" })
        assertTrue(agents.any { it.id.value == "agent2" })
    }
}
