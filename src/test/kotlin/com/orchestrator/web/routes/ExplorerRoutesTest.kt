package com.orchestrator.web.routes

import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.configureWebApplication
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorerRoutesTest {

    @Test
    fun `explorer page responds with 200 OK`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `explorer page contains expected content`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        val body = response.bodyAsText()
        
        assertTrue(body.contains("Context Explorer"), "Page should contain title")
        assertTrue(body.contains("Search your codebase"), "Page should contain description")
        assertTrue(body.contains("query-input"), "Page should contain search input")
        assertTrue(body.contains("results-container"), "Page should contain results container")
    }

    @Test
    fun `explorer page has correct content type`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        assertEquals("text/html; charset=UTF-8", response.headers["Content-Type"])
    }

    @Test
    fun `explorer page contains filter controls`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        val body = response.bodyAsText()
        
        assertTrue(body.contains("filter-panel"), "Page should contain filter panel")
        assertTrue(body.contains("filter-paths"), "Page should contain paths filter")
        assertTrue(body.contains("filter-exclude"), "Page should contain exclude patterns filter")
        assertTrue(body.contains("filter-max-results"), "Page should contain max results slider")
        assertTrue(body.contains("filter-max-tokens"), "Page should contain max tokens slider")
        assertTrue(body.contains("languages"), "Page should contain language checkboxes")
        assertTrue(body.contains("kinds"), "Page should contain kind checkboxes")
    }

    @Test
    fun `file content endpoint returns 400 for missing path`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/api/files/content")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `file content endpoint returns 404 for non-existent file`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/api/files/content?path=/non/existent/file.txt")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `query endpoint returns error for missing query`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.post("/api/context/query") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Missing query parameter") || body.contains("Query cannot be empty"))
    }

    @Test
    fun `query endpoint returns error for empty query`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.post("/api/context/query") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("query=")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Query cannot be empty"))
    }

    @Test
    fun `query endpoint returns error with hint for missing query`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.post("/api/context/query") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("💡"), "Error should include hint icon")
        assertTrue(body.contains("at least 2 characters"), "Error should include helpful hint")
    }

    @Test
    fun `query endpoint returns error for short query`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.post("/api/context/query") {
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody("query=a")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Query too short") || body.contains("at least 2 characters"))
    }

    @Test
    fun `empty state contains query tips`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        // This would need a mock QueryContextTool that returns empty results
        // For now, we verify the empty state HTML structure exists in ResultsContainer
        val emptyHtml = com.orchestrator.web.components.ResultsContainer.renderEmpty()
        
        assertTrue(emptyHtml.contains("No results found"), "Empty state should have title")
        assertTrue(emptyHtml.contains("Query Tips"), "Empty state should have query tips")
        assertTrue(emptyHtml.contains("Good Queries"), "Empty state should show good examples")
        assertTrue(emptyHtml.contains("Bad Queries"), "Empty state should show bad examples")
        assertTrue(emptyHtml.contains("authentication JWT token"), "Empty state should have example query")
    }

    @Test
    fun `error state includes hint when provided`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val errorHtml = com.orchestrator.web.components.ResultsContainer.renderError(
            "Test error message",
            "This is a helpful hint"
        )
        
        assertTrue(errorHtml.contains("Test error message"), "Error should contain message")
        assertTrue(errorHtml.contains("This is a helpful hint"), "Error should contain hint")
        assertTrue(errorHtml.contains("💡"), "Error should have hint icon")
    }

    @Test
    fun `warning state is dismissible`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val warningHtml = com.orchestrator.web.components.ResultsContainer.renderWarning(
            "Slow Query",
            "Query took too long"
        )
        
        assertTrue(warningHtml.contains("Slow Query"), "Warning should contain title")
        assertTrue(warningHtml.contains("Query took too long"), "Warning should contain message")
        assertTrue(warningHtml.contains("btn-close"), "Warning should be dismissible")
        assertTrue(warningHtml.contains("alert-dismissible"), "Warning should have dismissible class")
    }

    @Test
    fun `explorer page includes keyboard shortcut handlers`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        val body = response.bodyAsText()
        
        assertTrue(body.contains("keydown"), "Page should have keydown event listener")
        assertTrue(body.contains("query-input"), "Page should have query input field")
        assertTrue(body.contains("query-form"), "Page should have query form")
    }

    @Test
    fun `explorer page includes HTMX configuration`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        val body = response.bodyAsText()
        
        assertTrue(body.contains("hx-post"), "Page should have HTMX post directive")
        assertTrue(body.contains("hx-target"), "Page should have HTMX target directive")
        assertTrue(body.contains("hx-indicator"), "Page should have HTMX indicator")
        assertTrue(body.contains("htmx-indicator"), "Page should have indicator class")
    }

    @Test
    fun `explorer page includes loading spinner`() = testApplication {
        application {
            configureWebApplication(WebServerConfig())
        }

        val response = client.get("/explorer")
        val body = response.bodyAsText()
        
        assertTrue(body.contains("run-query-btn"), "Page should have run query button")
        assertTrue(body.contains("spinner-border"), "Page should have Bootstrap spinner class")
        assertTrue(body.contains("htmx-indicator"), "Page should have HTMX indicator class")
    }
}
