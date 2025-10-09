package com.orchestrator.storage.repositories

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import kotlin.test.*
import java.time.Instant

class TaskRepositoryTest {

    @BeforeTest
    fun setup() {
        // Ensure DB is initialized and table is clean
        Database.withConnection { conn ->
            conn.createStatement().use { st ->
                // Delete in FK-safe order considering all FKs: context_snapshots -> conversation_messages -> decisions -> proposals -> metrics_timeseries -> tasks
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
    fun testInsertAndFindById() {
        val task = sampleTask("task-1")
        TaskRepository.insert(task)
        val found = TaskRepository.findById(task.id)
        assertNotNull(found)
        assertEquals(task.id, found.id)
        assertEquals(task.title, found.title)
        assertEquals(task.type, found.type)
        assertEquals(task.status, found.status)
        assertEquals(task.routing, found.routing)
        assertEquals(task.assigneeIds, found.assigneeIds)
        assertEquals(task.dependencies, found.dependencies)
        assertEquals(task.complexity, found.complexity)
        assertEquals(task.risk, found.risk)
        assertEquals(task.dueAt, found.dueAt)
        assertEquals(task.metadata, found.metadata)
    }

    @Test
    fun testFindByStatus() {
        val t1 = sampleTask("s-1").copy(status = TaskStatus.PENDING)
        val t2 = sampleTask("s-2").copy(status = TaskStatus.IN_PROGRESS)
        val t3 = sampleTask("s-3").copy(status = TaskStatus.PENDING)
        TaskRepository.insert(t1)
        TaskRepository.insert(t2)
        TaskRepository.insert(t3)

        val pending = TaskRepository.findByStatus(TaskStatus.PENDING)
        assertTrue(pending.any { it.id == t1.id })
        assertTrue(pending.any { it.id == t3.id })
        assertTrue(pending.none { it.id == t2.id })
    }

    @Test
    fun testFindByAgent() {
        val a1 = AgentId("agent-1")
        val a2 = AgentId("agent-2")
        val t1 = sampleTask("a-1").copy(assigneeIds = setOf(a1))
        val t2 = sampleTask("a-2").copy(assigneeIds = setOf(a2))
        val t3 = sampleTask("a-3").copy(assigneeIds = setOf(a1, a2))
        TaskRepository.insert(t1)
        TaskRepository.insert(t2)
        TaskRepository.insert(t3)

        val forA1 = TaskRepository.findByAgent(a1)
        assertTrue(forA1.any { it.id == t1.id })
        assertTrue(forA1.any { it.id == t3.id })
        assertTrue(forA1.none { it.id == t2.id })
    }

    @Test
    fun testUpdate() {
        val original = sampleTask("u-1").copy(status = TaskStatus.PENDING)
        TaskRepository.insert(original)
        val updated = original.copy(
            title = "Updated Title",
            description = "New Description",
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.PARALLEL,
            assigneeIds = setOf(AgentId("agent-3")),
            dependencies = setOf(TaskId("dep-x")),
            complexity = 7,
            risk = 3,
            updatedAt = Instant.now(),
            metadata = mapOf("k1" to "v1", "k2" to "v2")
        )
        TaskRepository.update(updated)
        val found = TaskRepository.findById(updated.id)
        assertNotNull(found)
        assertEquals(updated.title, found.title)
        assertEquals(updated.description, found.description)
        assertEquals(updated.status, found.status)
        assertEquals(updated.routing, found.routing)
        assertEquals(updated.assigneeIds, found.assigneeIds)
        assertEquals(updated.dependencies, found.dependencies)
        assertEquals(updated.complexity, found.complexity)
        assertEquals(updated.risk, found.risk)
        assertEquals(updated.metadata, found.metadata)
    }

    @Test
    fun testDelete() {
        val t = sampleTask("d-1")
        TaskRepository.insert(t)
        assertNotNull(TaskRepository.findById(t.id))
        TaskRepository.delete(t.id)
        assertNull(TaskRepository.findById(t.id))
        // deleting again should be a no-op (no exception)
        TaskRepository.delete(t.id)
    }

    private fun sampleTask(id: String): Task {
        val now = Instant.now()
        return Task(
            id = TaskId(id),
            title = "Title $id",
            description = "Desc $id",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(AgentId("agent-1"), AgentId("agent-2")),
            dependencies = setOf(TaskId("dep-1")),
            complexity = 5,
            risk = 5,
            createdAt = now,
            updatedAt = null,
            dueAt = now.plusSeconds(3600),
            metadata = mapOf("p" to "q")
        )
    }
}
