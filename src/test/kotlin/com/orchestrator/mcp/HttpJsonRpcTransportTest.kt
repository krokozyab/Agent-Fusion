package com.orchestrator.mcp

import io.mockk.mockk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpJsonRpcTransportTest {

    private val server = mockk<McpServerImpl>(relaxed = true)

    @Test
    fun `numeric id is echoed back as a number, not a string`() {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 7) // numeric id
            put("method", "initialize")
        }

        val response = HttpJsonRpcTransport.handleRequest(request, server)!!.jsonObject
        val id = response["id"]!! as JsonPrimitive

        assertTrue(!id.isString, "numeric request id must be echoed as a JSON number, not a string")
        assertEquals("7", id.content)
    }

    @Test
    fun `unknown method returns method-not-found carrying the request id`() {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 42)
            put("method", "does/not/exist")
        }

        val response = HttpJsonRpcTransport.handleRequest(request, server)!!.jsonObject

        // id must be preserved (so the client can correlate), not nulled out as a parse error.
        assertEquals("42", (response["id"] as JsonPrimitive).content)
        assertEquals(-32601, (response["error"]!!.jsonObject["code"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `non-object request is a parse error with null id`() {
        val response = HttpJsonRpcTransport.handleRequest(JsonPrimitive("garbage"), server)!!.jsonObject

        assertEquals(JsonNull, response["id"])
        assertEquals(-32700, (response["error"]!!.jsonObject["code"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `notification without id yields no response`() {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        assertNull(HttpJsonRpcTransport.handleRequest(request, server))
    }

    @Test
    fun `unknown notification (no id) yields no response`() {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "some/other/notification")
        }
        assertNull(HttpJsonRpcTransport.handleRequest(request, server))
    }
}
