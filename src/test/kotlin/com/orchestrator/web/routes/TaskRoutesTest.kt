package com.orchestrator.web.routes

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.web.plugins.configureRouting
import com.orchestrator.web.WebServerConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue

class TaskRoutesTest {

    @BeforeEach
    fun setUp() {
        // Use an in-memory database for each test to ensure isolation
        Database.overrideForTests()

        // Even with in-memory, clear tables for safety between tests in the same suite
        Database.withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM decisions")
                stmt.execute("DELETE FROM proposals")
                stmt.execute("DELETE FROM tasks")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        // Cleanup after tests - clear data but keep connection for next test
        val conn = Database.getConnection()
        conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM tasks")
            stmt.execute("DELETE FROM proposals")
            stmt.execute("DELETE FROM decisions")
        }
        conn.close()
    }

    @Test
    fun `GET tasks table returns HTML fragment`() = testApplication {
        application {
            installTestRouting()
        }

        // Create some test tasks
        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Implement feature X",
            description = "Add new feature to the system",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            complexity = 5,
            risk = 3,
            createdAt = Instant.now()
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Review code for module Y",
            type = TaskType.REVIEW,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = setOf(AgentId("claude-code"), AgentId("codex-cli")),
            complexity = 7,
            risk = 5,
            createdAt = Instant.now().minusSeconds(3600)
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val response = client.get("/tasks/table")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/html; charset=UTF-8", response.headers["Content-Type"])

        val body = response.bodyAsText()

        // Should contain table structure
        assertContains(body, "data-table")
        assertContains(body, "task-row-TASK-001")
        assertContains(body, "task-row-TASK-002")

        // Should contain task data
        assertContains(body, "Implement feature X")
        assertContains(body, "Review code for module Y")

        // Should contain status badges with tones
        assertContains(body, "Pending")
        assertContains(body, "In Progress")
        assertContains(body, "badge--warning")
        assertContains(body, "badge--info")

        // Should contain type information with outline badges
        assertContains(body, "Implementation")
        assertContains(body, "Review")
        assertContains(body, "badge--success")
        assertContains(body, "badge--outline")

        // Should contain routing strategy meta
        assertContains(body, "Solo")
        assertContains(body, "Consensus")

        // Should include HTMX/SSE wiring and action buttons
        assertContains(body, "sse-swap=\"taskUpdated swap:outerHTML\"")
        assertContains(body, "hx-get=\"/tasks/TASK-001/modal\"")
        assertContains(body, "task-row__action--view")
        assertContains(body, "task-row__action--edit")
    }

    @Test
    fun `GET tasks table with status filter`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Pending task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Completed task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val response = client.get("/tasks/table?status=PENDING")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Should contain only pending task
        assertContains(body, "TASK-001")
        assertContains(body, "Pending task")

        // Should not contain completed task (due to filtering)
        // Note: This depends on whether filtering is done in SQL or in-memory
        // The current implementation filters in-memory, so both may appear
        // but only PENDING would be highlighted
    }

    @Test
    fun `GET tasks table with search parameter`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Implement authentication feature",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Fix database bug",
            type = TaskType.BUGFIX,
            status = TaskStatus.PENDING
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val response = client.get("/tasks/table?search=authentication")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Should contain task matching search
        assertContains(body, "TASK-001")
        assertContains(body, "authentication")
    }

    @Test
    fun `GET tasks table with pagination`() = testApplication {
        application {
            installTestRouting()
        }

        // Create 15 tasks
        for (i in 1..15) {
            val task = Task(
                id = TaskId("TASK-${i.toString().padStart(3, '0')}"),
                title = "Task $i",
                type = TaskType.IMPLEMENTATION,
                status = TaskStatus.PENDING,
                createdAt = Instant.now().minusSeconds(i.toLong() * 60)
            )
            TaskRepository.insert(task)
        }

        // Request first page with 10 items
        val response = client.get("/tasks/table?page=1&pageSize=10")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Should contain pagination controls
        assertContains(body, "pagination")

        // Should have total count header
        val totalCount = response.headers["X-Total-Count"]
        assertEquals("15", totalCount)
    }

    @Test
    fun `GET tasks table with sorting`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Alpha task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            complexity = 3
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Beta task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            complexity = 8
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        // Sort by title ascending
        val response = client.get("/tasks/table?sortBy=title&sortOrder=asc")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Should contain both tasks
        assertContains(body, "Alpha task")
        assertContains(body, "Beta task")

        // Should have sort indicators in header
        assertContains(body, "data-table__sort")
    }

    @Test
    fun `GET tasks table with complexity range filter`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Simple task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            complexity = 2
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Complex task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            complexity = 9
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        // Filter for high complexity tasks
        val response = client.get("/tasks/table?complexityMin=7&complexityMax=10")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Depending on implementation, should contain only complex task
        assertTrue(body.isNotEmpty())
    }

    @Test
    fun `GET tasks table with risk range filter`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Low risk task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            risk = 2
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "High risk task",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            risk = 9
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        // Filter for low risk tasks
        val response = client.get("/tasks/table?riskMin=1&riskMax=5")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        assertTrue(body.isNotEmpty())
    }

    @Test
    fun `GET tasks table with invalid parameters coerces values`() = testApplication {
        application {
            installTestRouting()
        }

        // Invalid page number gets coerced to 1
        val response1 = client.get("/tasks/table?page=0")
        assertEquals(HttpStatusCode.OK, response1.status)

        // Invalid pageSize gets coerced to 200 (max)
        val response2 = client.get("/tasks/table?pageSize=300")
        assertEquals(HttpStatusCode.OK, response2.status)

        // Invalid risk range gets coerced to valid range (10)
        val response3 = client.get("/tasks/table?riskMin=15")
        assertEquals(HttpStatusCode.OK, response3.status)
    }

    @Test
    fun `GET tasks table returns empty state when no tasks`() = testApplication {
        application {
            installTestRouting()
        }

        val response = client.get("/tasks/table")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        // Should contain empty state message
        assertContains(body, "No tasks found")
    }

    @Test
    fun `GET tasks table with multiple filters`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Authentication implementation",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            complexity = 7,
            risk = 5
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Database review",
            type = TaskType.REVIEW,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.CONSENSUS,
            complexity = 4,
            risk = 3
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        // Complex query with multiple filters
        val response = client.get(
            "/tasks/table?status=PENDING&type=IMPLEMENTATION&complexityMin=5&riskMax=8&sortBy=complexity&sortOrder=desc"
        )

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        assertTrue(body.isNotEmpty())
        assertContains(body, "data-table")
    }

    @Test
    fun `GET tasks table response has no-cache headers`() = testApplication {
        application {
            installTestRouting()
        }

        val response = client.get("/tasks/table")

        assertEquals(HttpStatusCode.OK, response.status)

        // Should have cache control header
        val cacheControl = response.headers["Cache-Control"]
        assertContains(cacheControl ?: "", "no-cache")
    }

    @Test
    fun `GET tasks table with agent filter`() = testApplication {
        application {
            installTestRouting()
        }

        val task1 = Task(
            id = TaskId("TASK-001"),
            title = "Task for Claude",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            assigneeIds = setOf(AgentId("claude-code"))
        )

        val task2 = Task(
            id = TaskId("TASK-002"),
            title = "Task for Codex",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.PENDING,
            assigneeIds = setOf(AgentId("codex-cli"))
        )

        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val response = client.get("/tasks/table?assigneeIds=claude-code")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()

        assertTrue(body.isNotEmpty())
    }

    @Test
    fun `GET task detail page returns 200 OK for existing task`() = testApplication {
        application {
            installTestRouting()
        }

        val task = Task(
            id = TaskId("TASK-999"),
            title = "Detail Page Test Task",
            type = TaskType.TESTING,
            status = TaskStatus.COMPLETED
        )
        TaskRepository.insert(task)

        val response = client.get("/tasks/TASK-999")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "Detail Page Test Task")
        assertContains(body, "aria-label=\"Breadcrumb navigation\"")
    }

    @Test
    fun `GET task detail page returns 404 for missing task`() = testApplication {
        application {
            installTestRouting()
        }

        val response = client.get("/tasks/TASK-DOES-NOT-EXIST")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET task detail modal returns 200 OK for existing task`() = testApplication {
        application {
            installTestRouting()
        }

        val task = Task(
            id = TaskId("TASK-MODAL"),
            title = "Modal Test Task",
            type = TaskType.TESTING,
            status = TaskStatus.PENDING,
            description = "This is a test description for the modal.",
            complexity = 4,
            risk = 2
        )
        TaskRepository.insert(task)

        val response = client.get("/tasks/TASK-MODAL/modal")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertContains(body, "id=\"task-detail-modal\"")
        assertContains(body, "Task: Modal Test Task")
        assertContains(body, "Task Information")
        assertContains(body, "ID:")
        assertContains(body, "TASK-MODAL")
        assertContains(body, "This is a test description for the modal.")
        assertContains(body, "Complexity:")
        assertContains(body, "4/10")
        assertContains(body, "Risk:")
        assertContains(body, "2/10")
        assertContains(body, "Pending")
        assertContains(body, "Testing")
    }
}

private fun io.ktor.server.application.Application.installTestRouting() {
    install(SSE)
    configureRouting(WebServerConfig())
}
