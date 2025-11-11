package com.orchestrator.web.routes

import com.orchestrator.domain.*
import com.orchestrator.storage.Database
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.plugins.configureRouting
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeRoutesTest {

    @BeforeEach
    fun setup() {
        Database.overrideForTests()
        // Database initialization happens automatically on first access
        val conn = Database.getConnection()

        // Clear all tasks before each test
        conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM tasks")
            stmt.execute("DELETE FROM proposals")
            stmt.execute("DELETE FROM decisions")
        }
        conn.close()
    }

    @AfterEach
    fun teardown() {
        // Cleanup after tests
        val conn = Database.getConnection()
        conn.createStatement().use { stmt ->
            stmt.execute("DELETE FROM tasks")
            stmt.execute("DELETE FROM proposals")
            stmt.execute("DELETE FROM decisions")
        }
        conn.close()
    }

    @Test
    fun `GET slash returns home page with navigation`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for HTML structure
        assertContains(body, "<!DOCTYPE html>", ignoreCase = true)
        assertContains(body, "<html", ignoreCase = true)
        assertContains(body, "</html>", ignoreCase = true)

        // Check for navigation
        assertContains(body, "Orchestrator", ignoreCase = false)

        // Check for page title
        assertContains(body, "Orchestrator Dashboard", ignoreCase = false)

        // Check for main content
        assertContains(body, "Dashboard", ignoreCase = false)

        // Check for cache control header
        val cacheControl = response.headers["Cache-Control"]
        assertTrue(cacheControl?.contains("no-cache") == true)
    }

    @Test
    fun `home page includes quick stats grid`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for stats grid
        assertContains(body, "quick-stats", ignoreCase = false)
        assertContains(body, "stat-card", ignoreCase = false)

        // Check for stat labels
        assertContains(body, "Total Tasks", ignoreCase = false)
        assertContains(body, "Completed", ignoreCase = false)
        assertContains(body, "Indexed Files", ignoreCase = false)
        assertContains(body, "Embeddings", ignoreCase = false)
    }

    @Test
    fun `home page includes activity feed and tasks`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for activity feed
        assertContains(body, "Recent Activity", ignoreCase = false)

        // Check for recent tasks
        assertContains(body, "Recent Tasks", ignoreCase = false)
        assertContains(body, "View all", ignoreCase = false)
    }

    @Test
    fun `home page includes HTMX for live updates`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for HTMX
        assertContains(body, "htmx.min.js", ignoreCase = false)
        assertContains(body, "hx-get", ignoreCase = false)
    }

    @Test
    fun `home page displays task statistics correctly`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        // Create test tasks
        val task1 = Task(
            id = TaskId("test-task-1"),
            title = "Test Task 1",
            description = "Description 1",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED,
            routing = RoutingStrategy.SOLO,
            risk = 5,
            complexity = 5,
            assigneeIds = setOf(AgentId("agent-1")),
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        val task2 = Task(
            id = TaskId("test-task-2"),
            title = "Test Task 2",
            description = "Description 2",
            type = TaskType.BUGFIX,
            status = TaskStatus.IN_PROGRESS,
            routing = RoutingStrategy.SOLO,
            risk = 3,
            complexity = 4,
            assigneeIds = setOf(AgentId("agent-1")),
            createdAt = Instant.now()
        )
        TaskRepository.insert(task1)
        TaskRepository.insert(task2)

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Should show 2 total tasks
        assertContains(body, "2", ignoreCase = false)
    }

    @Test
    fun `GET api stats returns stats fragment`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/api/stats")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for stats grid
        assertContains(body, "quick-stats", ignoreCase = false)
        assertContains(body, "stat-card", ignoreCase = false)

        // Check for HTMX attributes
        assertContains(body, "hx-get=\"/api/stats\"", ignoreCase = false)
        assertContains(body, "hx-trigger=\"refresh\"", ignoreCase = false)

        // Check for cache control
        val cacheControl = response.headers["Cache-Control"]
        assertTrue(cacheControl?.contains("no-cache") == true)
    }

    @Test
    fun `GET api activity returns activity fragment`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/api/activity")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Should return either activity list or empty state
        assertTrue(
            body.contains("activity-list") || body.contains("No recent activity"),
            "Should contain activity list or empty state"
        )

        // Check for cache control
        val cacheControl = response.headers["Cache-Control"]
        assertTrue(cacheControl?.contains("no-cache") == true)
    }

    @Test
    fun `api activity includes task activities`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        // Create test task
        val task = Task(
            id = TaskId("test-task-activity"),
            title = "Activity Test Task",
            description = "Test task for activity feed",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.COMPLETED,
            routing = RoutingStrategy.SOLO,
            risk = 5,
            complexity = 5,
            assigneeIds = setOf(AgentId("agent-1")),
            createdAt = Instant.now().minusSeconds(3600), // 1 hour ago
            updatedAt = Instant.now()
        )
        TaskRepository.insert(task)

        val response = client.get("/api/activity")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for activity items
        assertContains(body, "activity-item", ignoreCase = false)

        // Should show the task title
        assertContains(body, "Activity Test Task", ignoreCase = false)
    }

    @Test
    fun `home page includes required assets`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for required JS files
        assertContains(body, "htmx.min.js", ignoreCase = false)
        assertContains(body, "navigation.js", ignoreCase = false)

        // Check for required CSS files
        assertContains(body, "base.css", ignoreCase = false)
        assertContains(body, "orchestrator.css", ignoreCase = false)
    }

    @Test
    fun `home page has accessibility attributes`() = testApplication {
        application {
            install(SSE)
            configureRouting(WebServerConfig())
        }

        val response = client.get("/")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()

        // Check for ARIA roles
        assertContains(body, "role=\"main\"", ignoreCase = false)
    }
}
