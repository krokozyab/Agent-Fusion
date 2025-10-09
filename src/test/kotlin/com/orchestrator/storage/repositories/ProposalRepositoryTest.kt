package com.orchestrator.storage.repositories

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import kotlin.test.*
import java.time.Instant

class ProposalRepositoryTest {

    @BeforeTest
    fun setup() {
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM context_snapshots")
                st.executeUpdate("DELETE FROM conversation_messages")
                st.executeUpdate("DELETE FROM decisions")
                st.executeUpdate("DELETE FROM proposals")
                st.executeUpdate("DELETE FROM metrics_timeseries")
                st.executeUpdate("DELETE FROM tasks")
            }
        }
    }

    @Test
    fun testInsertAndFindById_withJsonContent() {
        val task = sampleTask("t-1")
        TaskRepository.insert(task)
        val p = sampleProposal("p-1", task.id, AgentId("agent-A"), content = mapOf(
            "text" to "Hello",
            "score" to 0.9,
            "flags" to listOf(true, false, null),
            "obj" to mapOf("k" to "v", "n" to 123)
        ))
        ProposalRepository.insert(p)
        val found = ProposalRepository.findById(p.id)
        assertNotNull(found)
        assertEquals(p.id, found.id)
        assertEquals(p.taskId, found.taskId)
        assertEquals(p.agentId, found.agentId)
        assertEquals(p.inputType, found.inputType)
        assertEquals(p.confidence, found.confidence, 1e-9)
        assertEquals(p.tokenUsage, found.tokenUsage)
        // Content round-trip basic checks
        val foundMap = found.content as Map<*, *>
        assertEquals("Hello", foundMap["text"])
        assertEquals(0.9, (foundMap["score"] as Number).toDouble(), 1e-9)
        val flags = foundMap["flags"] as List<*>
        assertEquals(listOf(true, false, null), flags)
        val obj = foundMap["obj"] as Map<*, *>
        assertEquals("v", obj["k"])
        assertEquals(123L, (obj["n"] as Number).toLong())
    }

    @Test
    fun testFindByTaskAndAgent() {
        val task1 = sampleTask("t-A")
        val task2 = sampleTask("t-B")
        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val a1 = AgentId("agent-1")
        val a2 = AgentId("agent-2")
        val p1 = sampleProposal("p-A1", task1.id, a1)
        val p2 = sampleProposal("p-A2", task1.id, a2)
        val p3 = sampleProposal("p-B1", task2.id, a1)
        ProposalRepository.insert(p1)
        ProposalRepository.insert(p2)
        ProposalRepository.insert(p3)

        val byTaskA = ProposalRepository.findByTask(task1.id)
        assertTrue(byTaskA.any { it.id == p1.id })
        assertTrue(byTaskA.any { it.id == p2.id })
        assertTrue(byTaskA.none { it.id == p3.id })

        val byAgent1 = ProposalRepository.findByAgent(a1)
        assertTrue(byAgent1.any { it.id == p1.id })
        assertTrue(byAgent1.any { it.id == p3.id })
        assertTrue(byAgent1.none { it.id == p2.id })
    }

    @Test
    fun testUpdateAndDelete() {
        val task = sampleTask("t-U")
        TaskRepository.insert(task)
        val orig = sampleProposal("p-U", task.id, AgentId("agent-U")).copy(
            confidence = 0.42,
            tokenUsage = TokenUsage(10, 20),
            metadata = mapOf("a" to "b")
        )
        ProposalRepository.insert(orig)
        val updated = orig.copy(
            inputType = InputType.TEST_PLAN,
            content = listOf("x", 1, true),
            confidence = 0.77,
            tokenUsage = TokenUsage(11, 22),
            metadata = mapOf("x" to "y")
        )
        ProposalRepository.update(updated)
        val found = ProposalRepository.findById(updated.id)
        assertNotNull(found)
        assertEquals(updated.inputType, found.inputType)
        assertEquals(updated.confidence, found.confidence, 1e-9)
        assertEquals(updated.tokenUsage, found.tokenUsage)
        assertEquals(updated.metadata, found.metadata)
        val content = found.content as List<*>
        assertEquals(listOf("x", 1L, true), content)

        // delete
        ProposalRepository.delete(updated.id)
        assertNull(ProposalRepository.findById(updated.id))
        // second delete no-op
        ProposalRepository.delete(updated.id)
    }

    @Test
    fun testForeignKeyConstraint_taskMustExist() {
        val missingTaskId = TaskId("no-task")
        val p = sampleProposal("p-FK", missingTaskId, AgentId("agent-Z"))
        val ex = assertFails { ProposalRepository.insert(p) }
        assertTrue(ex is java.sql.SQLException)
    }

    private fun sampleTask(id: String): Task {
        val now = Instant.now()
        return Task(
            id = TaskId(id),
            title = "Task $id",
            description = "desc",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("agent-1")),
            dependencies = emptySet(),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = null,
            dueAt = now.plusSeconds(600),
            metadata = mapOf("m" to "v")
        )
    }

    private fun sampleProposal(id: String, taskId: TaskId, agentId: AgentId, content: Any? = "text"): Proposal {
        return Proposal(
            id = ProposalId(id),
            taskId = taskId,
            agentId = agentId,
            inputType = InputType.ARCHITECTURAL_PLAN,
            content = content,
            confidence = 0.5,
            tokenUsage = TokenUsage(1, 2),
            createdAt = Instant.now(),
            metadata = mapOf("k" to "v")
        )
    }
}
