package com.orchestrator.core

import com.orchestrator.config.ConfigLoader
import com.orchestrator.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentRegistryTest {

    private fun writeTempAgentsToml(contents: String): Path {
        val dir = Files.createTempDirectory("agents-registry-test")
        val path = dir.resolve("agents.toml")
        Files.writeString(path, contents)
        return path
    }

    @Test
    fun loadsAgentsFromConfig() {
        val toml = """
            [agents]
            
            [agents.alpha]
            type = "GPT"
            name = "Alpha"
            model = "gpt-4o"
            
            [agents.bravo]
            type = "CLAUDE_CODE"
            name = "Bravo"
            model = "claude-3.5-sonnet"
        """.trimIndent()

        val path = writeTempAgentsToml(toml)
        val registry = AgentRegistry.fromConfig(path)

        val all = registry.getAllAgents()
        assertEquals(2, all.size, "Should load 2 agents")
        assertTrue(all.any { it.displayName == "Alpha" })
        assertTrue(all.any { it.displayName == "Bravo" })

        val a = registry.getAgent(AgentId("alpha"))
        assertNotNull(a)
        assertEquals(AgentType.GPT, a.type)
    }

    @Test
    fun queryByCapabilityIsEfficientAndReturnsAgents() {
        val defs = listOf(
            ConfigLoader.AgentDefinition(AgentId("a1"), AgentType.GPT, AgentConfig(name = "A1", model = "gpt-4o")),
            ConfigLoader.AgentDefinition(AgentId("a2"), AgentType.CLAUDE_CODE, AgentConfig(name = "A2", model = "claude-3.5-sonnet"))
        )
        val registry = AgentRegistry.build(defs)

        val codeGen = registry.getAgentsByCapability(Capability.CODE_GENERATION)
        assertEquals(2, codeGen.size, "Both non-custom agents should have default capabilities")

        val customDefs = listOf(
            ConfigLoader.AgentDefinition(AgentId("c1"), AgentType.CUSTOM, AgentConfig(name = "C1", model = "x"))
        )
        val reg2 = AgentRegistry.build(customDefs)
        val none = reg2.getAgentsByCapability(Capability.CODE_GENERATION)
        assertTrue(none.isEmpty())
    }

    @Test
    fun statusUpdatesAreAtomic() {
        val defs = listOf(
            ConfigLoader.AgentDefinition(AgentId("x"), AgentType.GPT, AgentConfig(name = "X", model = "gpt-4o"))
        )
        val registry = AgentRegistry.build(defs)

        // flip status concurrently many times
        val pool = Executors.newFixedThreadPool(4)
        repeat(1000) { i ->
            pool.submit {
                if (i % 2 == 0) registry.updateStatus(AgentId("x"), AgentStatus.BUSY)
                else registry.updateStatus(AgentId("x"), AgentStatus.ONLINE)
            }
        }
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        // final set and verify
        assertTrue(registry.updateStatus(AgentId("x"), AgentStatus.OFFLINE))
        val agent = registry.getAgent(AgentId("x"))
        assertEquals(AgentStatus.OFFLINE, agent?.status)
    }

    @Test
    fun concurrentAccessIsSafe() {
        val defs = (1..10).map { i ->
            ConfigLoader.AgentDefinition(AgentId("a$i"), AgentType.GPT, AgentConfig(name = "A$i", model = "gpt-4o"))
        }
        val registry = AgentRegistry.build(defs)

        val pool = Executors.newFixedThreadPool(8)
        repeat(1000) { i ->
            pool.submit { registry.getAllAgents().forEach { it.id } }
            pool.submit { registry.getAgentsByCapability(Capability.DEBUGGING).size }
            pool.submit {
                val id = AgentId("a${(i % 10) + 1}")
                registry.updateStatus(id, if (i % 3 == 0) AgentStatus.BUSY else AgentStatus.ONLINE)
            }
        }
        pool.shutdown()
        val finished = pool.awaitTermination(10, TimeUnit.SECONDS)
        assertTrue(finished, "Tasks should finish")

        // size remains constant
        assertEquals(10, registry.getAllAgents().size)
    }

    @Test
    fun healthCheckIntegrationUpdatesStatus() {
        val defs = listOf(
            ConfigLoader.AgentDefinition(AgentId("h1"), AgentType.GPT, AgentConfig(name = "H1", model = "gpt-4o"))
        )
        val registry = AgentRegistry.build(defs)

        val checker = object : AgentRegistry.HealthChecker {
            override fun check(agent: Agent): AgentStatus = AgentStatus.OFFLINE
        }
        registry.runHealthChecks(checker)

        val a = registry.getAgent(AgentId("h1"))
        assertEquals(AgentStatus.OFFLINE, a?.status)
    }
}
